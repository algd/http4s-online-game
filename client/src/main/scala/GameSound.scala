import cats.effect.{Async, Sync}
import cats.effect.concurrent.Ref
import fs2.Pipe
import org.scalajs.dom

object GameSound {
  case class StaticSounds(
    explosion: dom.html.Audio,
    falling: dom.html.Audio,
    ouch: dom.html.Audio
  )

  def loadSound[F[_]](name: String)(implicit F: Async[F]): F[dom.html.Audio] = F.async { cb =>
    val sound = dom.document.createElement("audio").asInstanceOf[dom.html.Audio]
    sound.src = s"sounds/$name.mp3"
    sound.load()
    sound.onloadeddata = _ => cb(Right(sound))
  }

  private def playSound[F[_]](id: String, sound: dom.html.Audio, soundRef: Ref[F, Set[String]], volume: Float = 1f)(implicit F: Sync[F], FAF: FireAndForget[F]): F[Unit] = {
    F.flatMap(soundRef.modify(currentSounds =>
      if (currentSounds(id)) (currentSounds, Option.empty[String])
      else (currentSounds + id, Some(id))
    )) {
      case Some(id) =>F.delay {
        val newSound = sound.cloneNode().asInstanceOf[dom.html.Audio]
        newSound.volume = volume
        newSound.play()
        newSound.onended = {_ => FAF.fireAndForget(soundRef.update(_ - id)) }
      }
      case _ => F.unit
    }
  }

  def soundStream[F[_]](sounds: StaticSounds)(implicit F: Sync[F], FAF: FireAndForget[F]): Pipe[F, GameStatus, Unit] = statusStream =>
    fs2.Stream.eval(Ref.of[F, Set[String]](Set.empty)).flatMap { soundRef =>
      import cats.syntax.all._
      statusStream.evalMap { status =>
        for {
          _ <- status.bombs.find(b => b.frame > 10 && b.frame < 60).fold(F.unit){ bomb => playSound(bomb.id, sounds.explosion, soundRef, 0.5f) }
          _ <- status.characters.find(c => c.z < 0 && c.z > -20).fold(F.unit){ c => playSound(c.name + "-falling", sounds.falling, soundRef, 0.5f) }
          _ <- status.characters.find(c => c.az > 0 && c.az < 2).fold(F.unit){ c => playSound(c.name + "-ouch", sounds.ouch, soundRef) }
        } yield ()
      }
    }
}
