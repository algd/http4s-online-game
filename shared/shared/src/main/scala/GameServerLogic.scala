import java.util.UUID

import cats.effect.{Concurrent, Timer}
import cats.effect.concurrent.Ref
import fs2.{Pipe, Stream}
import fs2.concurrent.{Queue, Topic}
import cats.implicits._

sealed trait Audience {
  def send[T](t: T) = WithAudience(t, this)
}
object Audience {
  case object All extends Audience
  case class Only(ids: List[String]) extends Audience
  case class AllExcept(ids: List[String]) extends Audience
}

case class WithAudience[T](t: T, audience: Audience)

object WithAudience {
  import Audience._
  def filteredFor[F[_], T](id: String): Pipe[F, WithAudience[T], T] = _.collect {
    case WithAudience(t, All) => t
    case WithAudience(t, Only(ids)) if ids.contains(id) => t
    case WithAudience(t, AllExcept(ids)) if !ids.contains(id) => t
  }

  implicit class AudienceOps(event: Event) {
    def forAll = All.send(event)
    def onlyFor(ids: String*) = Only(ids.toList).send(event)
    def allExcept(ids: String*) = AllExcept(ids.toList).send(event)
  }
}

object GameServerLogic {
  def updateWithCommand(status: GameStatus, command: Command): (GameStatus, List[WithAudience[Event]]) = {
    import WithAudience._
    val now = java.lang.System.currentTimeMillis()
    val event = command.toServerEvent(now, status.tick + 1)

    val nextStatus = GameLogic.updateStatusWithEvent(status, event)

    val name = event.playerId

    val eventsToSend = event.body match {
      case Join =>
        List(
          event.copy(body = RetrieveStatus(nextStatus)).onlyFor(name),
          event.allExcept(name)
        )
      case Ping =>
        List(event.onlyFor(name))
      case _ =>
        List(event.forAll)
    }

    (nextStatus, eventsToSend)
  }

  def createGame[F[_]](implicit F: Concurrent[F], timer: Timer[F]) = {
    for {
      topic <- Topic[F, Option[WithAudience[Event]]](None)
      queue <- Queue.unbounded[F, Command]
      statusRef <- Ref.of[F, GameStatus](GameStatus.empty)
    } yield {

      val updateWithEvent: Stream[F, WithAudience[Event]] =
        queue.dequeue
          .evalMap(command => statusRef.modify(updateWithCommand(_, command)))
          .flatMap(Stream.emits)

      val updateWithTick = Utils.evalEvery[F, Unit](GameLogic.tickRate)(statusRef.update(GameLogic.updateStatusWithTick))

      val stream =
        updateWithEvent
          .through(Utils.mergeAndIgnore(updateWithTick))
          .map(Some(_))
          .through(topic.publish)

      val subscriptionFor: String => (Stream[F, Event], Pipe[F, Command, Unit], F[Unit]) = name =>
        (topic.subscribe(Int.MaxValue).tail
          .collect { case Some(event) => event }
          .through(WithAudience.filteredFor(name)),
          queue.enqueue,
          queue.enqueue1(Command(UUID.randomUUID().toString, name, Quit))
        )

      (stream, subscriptionFor)
    }
  }
}
