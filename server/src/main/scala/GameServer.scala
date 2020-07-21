import cats.{Foldable, Traverse}
import cats.effect._
import cats.effect.concurrent.Ref
import cats.implicits._
import io.circe.Json
import io.circe.parser
import io.circe.Printer
import io.circe.syntax._
import fs2._
import fs2.concurrent.{Queue, Topic}
import org.http4s._
import org.http4s.implicits._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.websocket._
import org.http4s.websocket.WebSocketFrame
import org.http4s.websocket.WebSocketFrame._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.global
import CustomEncoder._


/**
 * Created by Alvaro on 10/05/2020.
 */

class GameServerImpl[F[_]](
  subscriptionFor: String => (Stream[F, Event], Pipe[F, Command, Unit], F[Unit]))(
  implicit F: ConcurrentEffect[F],
  timer: Timer[F],
  cs: ContextShift[F]
) extends Http4sDsl[F] {

  import java.util.concurrent._

  val blockingPool = Executors.newFixedThreadPool(4)
  val blocker = Blocker.liftExecutorService(blockingPool)

  object NameQueryParamMatcher extends QueryParamDecoderMatcher[String]("name")

  def routes: HttpRoutes[F] = HttpRoutes.of[F] {

    case GET -> Root / "ws" :? NameQueryParamMatcher(name) =>

      val randomLag = 0//(scala.util.Random.nextInt(10))*10

      println(name + " - " + randomLag + "ms")

      val (out, in, onClose) = subscriptionFor(name)

      val wsOut: Stream[F, WebSocketFrame] = out
        .evalTap(_ => timer.sleep(randomLag.milliseconds)).through(Utils.toJson)
        .map(Text(_))

      val wsIn: Pipe[F, WebSocketFrame, Unit] = s => in(s
        .collect { case Text(json, _) => json }
        .through(Utils.parseAs[F, Command])
        .evalTap(_ => timer.sleep(randomLag.milliseconds)))

      WebSocketBuilder[F].build(wsOut, wsIn, onClose = onClose)

    case request @ GET -> path if path.lastOption.exists(s => List(".js", ".html", ".png", ".js.map", ".md", ".mp3").exists(s.endsWith)) =>
      StaticFile.fromResource("/public/" + path.toList.mkString("/"), blocker, Some(request)).getOrElseF(NotFound())

    case request @ GET -> Root =>
      StaticFile.fromResource("/public/index.html", blocker, Some(request)).getOrElseF(NotFound())
  }

  def stream: Stream[F, ExitCode] =
    BlazeServerBuilder[F](global)
      .bindHttp(8080, "0.0.0.0")
      .withHttpApp(routes.orNotFound)
      .serve
}

object GameServer extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    for {
      gameCreation <- GameServerLogic.createGame[IO]
      (gameStream, getSubscription) = gameCreation
      b = new GameServerImpl[IO](getSubscription).stream
      _ <- b.merge(gameStream).compile.drain
    } yield ExitCode.Success

}
