import java.util.UUID

import GameCanvas.StaticResources
import cats.Apply
import cats.effect.concurrent.Ref
import cats.effect.IO
import fs2.concurrent.SignallingRef
import fs2.{Pipe, Stream}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._
import scala.scalajs.js.annotation.{JSExport, JSExportTopLevel}

@JSExportTopLevel("GameClient")
object GameClient {
  implicit val cs = IO.contextShift(global)

 implicit val timer = IO.timer(global)

  val tickStream = Utils.evalEvery(GameLogic.tickRate)(IO.unit)

  val gamePipe: Boolean => Pipe[IO, Either[Unit, Event], GameStatus] = estimation => tickOrEventStream =>
    Stream.eval {
      for {
        eventsRef <- Ref.of[IO, List[Event]](List.empty)
        tickRef <- Ref.of[IO, Long](0)
        statusRef <- Ref.of[IO, List[GameStatus]](List.empty)
        statusOverride <- Ref.of[IO, Option[GameStatus]](None)
        fromTickOverride <- Ref.of[IO, Option[Long]](None)
      } yield {
        val tickPipe: Pipe[IO, Unit, Option[GameStatus]] = _.evalMap { _ =>
          for {
            tick <- tickRef.getAndUpdate(_ + 1)
            optStatus <- statusOverride.getAndSet(None)
            currentStatus <- optStatus.fold(ifEmpty = statusRef.get)(s => IO.pure(List(s)))
          } yield (currentStatus, tick)
        }
          .collect { case (currentStatus@_ :: _, tick) => (currentStatus, tick) }// Wait until there is a status
          .evalMap { case (currentStatus, tick) =>
            for {
              currentEvents <- eventsRef.get
              fromTick <- fromTickOverride.getAndSet(None)
              validStatus = fromTick.fold[List[GameStatus]](currentStatus)(from => currentStatus.dropWhile(_.tick > from))
              previousStatus = validStatus.headOption.getOrElse(GameStatus.empty) // TODO: if size = 0 - ask for new status
              newStatus = GameLogic.updateStatusUntil(previousStatus, currentEvents, tick)
              _ <- statusRef.set((newStatus :: validStatus).take(300))
            } yield newStatus
          }.map(Some(_))

        val eventPipe: Pipe[IO, Event, Option[GameStatus]] =
          _.through(
            if (estimation) updateEvents(eventsRef, tickRef, statusOverride, fromTickOverride)
            else pushEvents(eventsRef, tickRef, statusOverride)
          ).as(None)

        tickOrEventStream.through(Utils.foldEither(tickPipe, eventPipe)).collect {
          case Some(status) => status
        }
      }
    }.flatten

  def pushEvents(
    eventsRef: Ref[IO, List[Event]],
    tickRef: Ref[IO, Long],
    statusRef: Ref[IO, Option[GameStatus]]
  ): Pipe[IO, Event, Unit] = {
    _.evalMap { event =>
      event.body match {
        case RetrieveStatus(status) =>
          for {
            events <- eventsRef.get
            tickThen = events.find(_.requestId == event.requestId).flatMap(_.localTick)
            _ <- statusRef.set(Some(status))
            _ <- tickRef.update(currentTick => tickThen.fold(status.tick)(t => (currentTick - t) / 2 + status.tick))
          } yield ()
        case _ =>
          for {
            tick <- tickRef.get
            _ <- eventsRef.update(_ :+ event.copy(tick = tick + 1))
          } yield ()

      }
    }
  }

  def updateEvents(
    eventsRef: Ref[IO, List[Event]],
    tickRef: Ref[IO, Long],
    statusRef: Ref[IO, Option[GameStatus]],
    fromTickOverride: Ref[IO, Option[Long]]
  ): Pipe[IO, Event, Unit] = {
    _.evalMap { event =>
      event.body match {
        case RetrieveStatus(status) =>
          for {
            events <- eventsRef.get
            tickThen = events.find(_.requestId == event.requestId).flatMap(_.localTick)
            _ <- statusRef.set(Some(status))
            _ <- tickRef.update(currentTick => tickThen.fold(status.tick)(t => (currentTick - t) / 2 + status.tick))
          } yield ()

        case _ if event.localTick.isEmpty =>
          val overrideTick: Long => Long => IO[Unit] = currentTick => newTick => for {
            _ <- if (newTick <= currentTick) fromTickOverride.update(t => Some(t.fold(newTick - 1)(Math.min(_, newTick - 1)))) else IO.unit
          } yield ()
          tickRef.get.flatMap { currentTick =>
          eventsRef.modify { evs =>
            evs.find(ev => ev.requestId == event.requestId && ev.localTick.isDefined) match {
              case Some(estimatedEvent) =>
                val evsWithoutEstimated = evs.filterNot(_ == estimatedEvent)
                val tickThen = estimatedEvent.localTick.getOrElse(event.tick) // always defined
                val estimated = (currentTick + tickThen) / 2
                val updateFromTick = for {
                  _ <- if (Math.abs(estimated - event.tick) > 10)
                    tickRef.set((currentTick - tickThen) / 2 + event.tick)
                  else if (Math.abs(currentTick - event.tick) > 1000)
                    tickRef.set(event.tick) *> fromTickOverride.set(Some(event.tick))
                  else IO.unit
                  _ <- overrideTick(currentTick)(Math.min(estimatedEvent.tick, event.tick))
                } yield ()
                ((evsWithoutEstimated :+ event).sortBy(_.timestamp), updateFromTick)
              case None =>
                ((evs :+ event).sortBy(_.timestamp), overrideTick(currentTick)(event.tick))
            }
          }.flatMap(identity)
          }
        case _ =>
          eventsRef.update {
            case sameEvs if sameEvs.exists(_.requestId == event.requestId) => sameEvs
            case evs => (event :: evs).sortBy(_.timestamp)
          }
      }
    }
  }


  def loadImg(name: String): IO[dom.html.Image] = IO.async { cb =>
      val img = dom.document.createElement("img").asInstanceOf[dom.html.Image]
      img.src = s"/images/$name.png"
      img.onload = (_: dom.Event) => cb(Right(img))
    }

  import CustomEncoder._

  val joinStream = Stream.emit[IO, Message](Join)

  def actionStreamFor(name: String, inputDisabled: Boolean = false): IO[Stream[IO, Command]] =
    Keys.keyStream[IO].map { keyActions =>
      import Keys._
      (
        if (inputDisabled)
          Stream.awakeEvery[IO](1.seconds).map(_ => Ping)
        else
          keyActions.collect[Message] {
            case KeyDown("d" | "ArrowRight") => StartMove(Direction.Right)
            case KeyDown("a" | "ArrowLeft") => StartMove(Direction.Left)
            case KeyDown("s" | "ArrowDown") => StartMove(Direction.Down)
            case KeyDown("w" | "ArrowUp") => StartMove(Direction.Up)
            case KeyUp("d" | "ArrowRight") => StopMove(Direction.Right)
            case KeyUp("a" | "ArrowLeft") => StopMove(Direction.Left)
            case KeyUp("s" | "ArrowDown") => StopMove(Direction.Down)
            case KeyUp("w" | "ArrowUp") => StopMove(Direction.Up)
            case KeyDown("o" | "Control") => ThrowBomb
          })
        .merge(joinStream)
        .map(Command(UUID.randomUUID().toString, name, _))
    }


  val toWsMessage: Pipe[IO, Command, String] =
    _.through(Utils.toJson).evalTap(msg => writeToScreen("✉️➡️ " + msg))

  val wsUri = dom.window.location.host + "/ws"

  def webSocketClient(name: String): IO[(Stream[IO, String], Pipe[IO, String, Unit])] =
    WebSocketClient.build[IO](s"ws://$wsUri?name=$name").map { case (receivedMessages, sendMessage) =>
      import WebSocketClient._
      val onlyText = receivedMessages.evalMap {
        case Connected =>
          writeToScreen("CONNECTED").as(None)
        case Error(error) =>
          writeToScreen("""<span style=#"color: red">ERROR: """ + error + "</span>").as(None)
        case Closed =>
          writeToScreen("DISCONNECTED").as(None)
        case Text(text) =>
          writeToScreen("""<span style=#"color: blue">⬅️✉️ """ + text + "</span>").as(Some(text))
      }.collect {
        case Some(msg) => msg
      }

      val wrapAndSend: Pipe[IO, String, Unit] = messages => messages.map(Text).through(sendMessage)

      (onlyText, wrapAndSend)
    }

  val loadResources = for {
    c <- loadImg("character")
    i <- loadImg("island")
    s <- loadImg("sky")
    r <- loadImg("rabbit")
    e <- loadImg("explosion")
    sh <- loadImg("shadow")
  } yield GameCanvas.StaticResources(c, i ,s, r, e, sh)

  val loadSounds: IO[GameSound.StaticSounds] = {
    import GameSound._
    import cats.syntax.parallel._
    (loadSound[IO]("explosion"),
      loadSound[IO]("falling"),
      loadSound[IO]("ouch"))
      .parMapN(StaticSounds)
  }

  val estimationPipes: Boolean => IO[(Pipe[IO, Command, Event], Pipe[IO, GameStatus, Unit])] = if (_)
    Ref.of[IO, Long](0).map { tickRef =>
      val now = timer.clock.realTime(java.util.concurrent.TimeUnit.MILLISECONDS)
      (_.evalMap(command => Apply[IO].map2(now, tickRef.get)(command.toLocalEvent)), _.evalMap(s => tickRef.set(s.tick)))
    }
  else IO.pure((s => Stream.empty.through(Utils.mergeAndIgnore[IO, Event](s)), _.as(())))

  def connectToServer(name: String): IO[Unit] = {

    val validateCommand: Pipe[IO, Command, Event] = actions => Stream.eval(webSocketClient(name)).flatMap {
      case (receivedMessages, sendMessage) =>
        val sentCommands = actions.through(toWsMessage).through(sendMessage)
        val receivedEvents = receivedMessages.through(Utils.parseAs[IO, Event])

        receivedEvents.through(Utils.mergeAndIgnore(sentCommands))
    }

    for {
      resources <- loadResources
      appendTo = dom.document.getElementById("game")
      canvas <- GameCanvas.resource[IO](appendTo, resources, name).map(GameCanvas.smoothMove[IO](0.85f) andThen _).allocated
      sounds <- loadSounds
      soundStream = GameSound.soundStream[IO](sounds)
      (drawStream, _) = canvas
      actionStream <- actionStreamFor(name)
      pipes <- estimationPipes(true)
      (estimateCommand, updateEstimatedTick) = pipes
      eventStream = actionStream.broadcastThrough(validateCommand, estimateCommand)
      eventOrTickStream = Utils.asEither(tickStream, eventStream)
      gameStream = eventOrTickStream.through(gamePipe(true))
      _ <- gameStream.broadcastThrough(updateEstimatedTick, drawStream, soundStream).compile.drain
    } yield ()
  }

  def writeToScreen(message: String): IO[Unit] = IO {
    val output = dom.document.getElementById("output")
    val pre = dom.document.createElement("p")
    pre.innerHTML = message
    output.appendChild(pre)
    if (output.children.length > 4)
      output.removeChild(output.firstChild)
  }.void

  @JSExport
  def start(name: String): Unit = {
    connectToServer(name).unsafeRunAsyncAndForget()
  }

  @JSExport
  def startForSlide(slide: Int): Unit = {
    def validateCommandFor(latency: Int): ((Stream[IO, Event], Pipe[IO, Command, Unit], IO[Unit])) => Pipe[IO, Command, Event] = {
      case (events, sendCommand, _) => stream =>
        def latencyPipe[A]: Pipe[IO, A, A] =
          if (latency > 0) _.mapAsync(10)(timer.sleep((latency/2).milliseconds).as(_)) else identity
        events
          .through(latencyPipe)
          .through(Utils.mergeAndIgnore(
            stream.through(latencyPipe).through(sendCommand)))
    }

    def createClient(
      name: String,
      appendTo: dom.Element,
      subscriptionFor: String => (Stream[IO, Event], Pipe[IO, Command, Unit], IO[Unit]),
      latency: Int = 0,
      small: Boolean = true,
      simple: Boolean = false,
      estimation: Boolean = true,
      disabled: Boolean = false
      ): Pipe[IO, Unit, Unit] = tickStream =>
      Stream.eval {
        val smoothMovement: Pipe[IO, GameStatus, GameStatus] =
          if (simple) identity else GameCanvas.smoothMove[IO](0.85f)

        val width = if (small) 520 else 800

        for {
          resources <- loadResources
          canvas <- GameCanvas.resource[IO](appendTo, resources, name, width).map(smoothMovement.andThen).allocated
          (drawStream, releaseCanvas) = canvas
          actionStream <- actionStreamFor(name, disabled)
          pipes <- estimationPipes(estimation)
          (estimateCommand, updateEstimatedTick) = pipes
          validateCommand = validateCommandFor(latency)(subscriptionFor(name))
          eventStream = actionStream.broadcastThrough(validateCommand, estimateCommand)
          eventOrTickStream = Utils.asEither(tickStream, eventStream)
          gameStream = eventOrTickStream.through(gamePipe(estimation))
        } yield gameStream.broadcastThrough(updateEstimatedTick, drawStream).onFinalize(releaseCanvas)
      }.flatten

    val makeCancellable: Pipe[IO, Unit, Unit] = s => for {
      cancelled <- Stream.eval(SignallingRef[IO, Boolean](false))
      _ <- Stream.eval(IO(currentGame = Some(cancelled.set(true))))
      _ <- s.interruptWhen(cancelled)
    } yield ()

    val container = dom.document.getElementById("game" + slide)

    (slide match {
      case 23 =>
        (for {
          sharedGame <- GameServerLogic.createGame[IO]
          (serverStream, subscriptionFor) = sharedGame
          clientStream = createClient("Example", container, subscriptionFor, small = false, simple = true, estimation = false)
          _ <- tickStream.through(clientStream).merge(serverStream).through(makeCancellable).compile.drain
        } yield ())
      case 34 =>
        (for {
          sharedGame <- GameServerLogic.createGame[IO]
          (serverStream, subscriptionFor) = sharedGame
          player1 = createClient("Test1", container, subscriptionFor, simple = true, estimation = false)
          player2 = createClient("Test2", container, subscriptionFor, simple = true, estimation = false, disabled = true)
          clientStream = tickStream.broadcastThrough(player1, player2)
          _ <- clientStream.merge(serverStream).through(makeCancellable).compile.drain
        } yield ())
      case 35 =>
        (for {
          sharedGame <- GameServerLogic.createGame[IO]
          (serverStream, subscriptionFor) = sharedGame
          player1 = createClient("Test1", container, subscriptionFor, 100, true, true, false)
          player2 = createClient("Test2", container, subscriptionFor, 500, true, true, false, true)
          clientStream = tickStream.broadcastThrough(player1, player2)
          _ <- clientStream.merge(serverStream).through(makeCancellable).compile.drain
        } yield ())
      case 42 =>
        (for {
          sharedGame <- GameServerLogic.createGame[IO]
          (serverStream, subscriptionFor) = sharedGame
          player1 = createClient("Test1", container, subscriptionFor, 100, simple = true)
          player2 = createClient("Test2", container, subscriptionFor, 500, true,true, true, true)
          clientStream = tickStream.broadcastThrough(player1, player2)
          _ <- clientStream.merge(serverStream).through(makeCancellable).compile.drain
        } yield ())
      case 44 =>
        (for {
          sharedGame <- GameServerLogic.createGame[IO]
          (serverStream, subscriptionFor) = sharedGame
          player1 = createClient("Test1", container, subscriptionFor, 100)
          player2 = createClient("Test2", container, subscriptionFor, 500, disabled =true)
          clientStream = tickStream.broadcastThrough(player1, player2)
          _ <- clientStream.merge(serverStream).through(makeCancellable).compile.drain
        } yield ())
      case _ =>
        IO.unit
    }).unsafeRunAsyncAndForget()
  }

  var currentGame: Option[IO[Unit]] = None

  @JSExport
  def stop(): Unit = {
    currentGame.foreach(_.unsafeRunAsyncAndForget())
    currentGame = None
  }

}