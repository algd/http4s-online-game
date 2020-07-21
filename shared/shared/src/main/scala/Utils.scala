import cats.effect.concurrent.Ref
import cats.effect.{Clock, Concurrent, Sync, Timer}
import fs2.{Pipe, Stream}
import io.circe.{Decoder, Encoder, Printer}
import cats.implicits._

import scala.concurrent.duration.FiniteDuration

object Utils {
  def asEither[F[_]: Concurrent, A, B](s1: Stream[F, A], s2: Stream[F, B]): Stream[F, Either[A, B]] = {
    s1.map(_.asLeft).merge(s2.map(_.asRight))
  }

  def foldEither[F[_]: Concurrent, A, B, C](pipeLeft: Pipe[F, A, C], pipeRight: Pipe[F, B, C])(s: Stream[F, Either[A, B]]): Stream[F, C] = {
    val pipe1: Pipe[F, Either[A, B], C] = _.collect { case Left(l) => l }.through(pipeLeft)
    val pipe2: Pipe[F, Either[A, B], C] = _.collect { case Right(r) => r }.through(pipeRight)

    s.broadcastThrough(pipe1, pipe2)
  }

  def evalEvery[F[_], A](duration: FiniteDuration)(fa: F[A])(implicit F: Sync[F], timer: Timer[F]): Stream[F, A] = {
    val clock = Clock.extractFromTimer(timer)
    val nowStream = Stream.eval(clock.realTime(java.util.concurrent.TimeUnit.MILLISECONDS).map(_/duration.toMillis))

    for {
      timeRef     <- nowStream.evalMap(Ref.of[F, Long])
      _           <- Stream.fixedRate(duration)
      now         <- nowStream
      before      <- Stream.eval(timeRef.getAndSet(now))
      unitsToEmit = (now - before).toInt
      _           <- Stream.emits(List.fill(unitsToEmit)(()))
      element     <- Stream.eval(fa)
    } yield element
  }

  def mergeAndIgnore[F[_]: Concurrent, T](stream: Stream[F, _]) : Pipe[F, T, T] = {
    _.map(Some(_))
      .merge(stream.map(_ => None))
      .collect { case Some(t) => t }
  }

  def parseAs[F[_], T](implicit decoder: Decoder[T]): Pipe[F, String, T] =
    _.map(string => io.circe.parser.parse(string).toOption.flatMap(decoder.decodeJson(_).toOption)).collect {
      case Some(decoded) => decoded
    }

  def toJson[F[_], T](implicit encoder: Encoder[T]): Pipe[F, T, String] =
    _.map(encoder(_).printWith(Printer.noSpaces))

}
