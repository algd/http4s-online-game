
import cats.effect.Concurrent
import cats.effect.concurrent.Ref
import org.scalajs.dom
import org.scalajs.dom.raw.KeyboardEvent
import cats.implicits._

object Keys {
  sealed trait KeyAction
  case class KeyUp(key: String) extends KeyAction
  case class KeyDown(key: String) extends KeyAction

  def keyStream[F[_]](implicit F: Concurrent[F], FAF: FireAndForget[F]): F[fs2.Stream[F, KeyAction]] =
    Ref.of[F, Set[String]](Set.empty).flatMap { pressedKeys =>
      fs2.concurrent.Queue.unbounded[F, KeyAction].flatMap { queue =>
        F.delay {
          dom.window.addEventListener("keydown", { (keyEvent: KeyboardEvent) =>
            val key = keyEvent.key
            FAF.fireAndForget(for {
              currentKeys <- pressedKeys.getAndUpdate(_ + key)
              _ <- if (!currentKeys(key)) queue.enqueue1(KeyDown(key)) else F.unit
            } yield ())
          })
        } *> F.delay {
          dom.window.addEventListener("keyup", { (keyEvent: KeyboardEvent) =>
            val key = keyEvent.key
            FAF.fireAndForget(for {
              currentKeys <- pressedKeys.getAndUpdate(_ - key)
              _ <- if (currentKeys(key)) queue.enqueue1(KeyUp(key)) else F.unit
            } yield ())
          })
        }.as(queue.dequeue)
      }
    }

}
