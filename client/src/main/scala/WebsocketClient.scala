import cats.effect.{Async, Concurrent}
import fs2.Pipe
import org.scalajs.dom
import org.scalajs.dom.{MessageEvent, WebSocket}
import cats.implicits._

object WebSocketClient {
  sealed trait WSMessage
  case object Connected extends WSMessage
  case class Text(text: String) extends WSMessage
  case class Error(text: String) extends WSMessage
  case object Closed extends WSMessage

  def build[F[_]](uri: String)(implicit F: Concurrent[F], A: Async[F], FAF: FireAndForget[F]): F[(fs2.Stream[F, WSMessage], Pipe[F, WSMessage, Unit])] = {

    fs2.concurrent.Queue.unbounded[F, WSMessage].flatMap { in =>
      fs2.concurrent.Queue.unbounded[F, WSMessage].flatMap { out =>
        A.async[dom.WebSocket] { cb =>
          val socket = new WebSocket(uri)
          socket.onmessage = { (msg: MessageEvent) =>
            FAF.fireAndForget(in.enqueue1(Text(msg.data.toString)))
          }
          socket.onerror = { (msg: dom.Event) =>
            println("Error " + Error(msg.`type`))
            FAF.fireAndForget(in.enqueue1(Error(msg.`type`)))
          }
          socket.onclose = { (_: dom.Event) =>
            FAF.fireAndForget(in.enqueue1(Closed))
          }
          socket.onopen = { (_: dom.Event) =>
            FAF.fireAndForget(in.enqueue1(Connected))
            cb(Right(socket))
          }
        }.map { socket =>
          val received = in.dequeue
          val sent = out.dequeue.evalMap {
            case Text(msg) => F.delay(socket.send(msg))
            case _ => F.unit
          }

          val merged = received.merge(sent).collect {
            case ws: WSMessage => ws
          }

          (merged, out.enqueue)
        }
      }
    }
  }
}

trait FireAndForget[F[_]] {
  def fireAndForget(fa: F[_]): Unit
}

object FireAndForget {
  import cats.effect.IO
  implicit val ioFireAndForget = new FireAndForget[IO] {
    override def fireAndForget(fa: IO[_]): Unit = fa.unsafeRunAsyncAndForget()
  }
}