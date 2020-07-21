case class Command(
  requestId: String,
  playerId: String,
  body: Message
) {
  def toLocalEvent(newTimestamp: Long, newTick: Long) = Event(
    timestamp = newTimestamp,
    tick = newTick,
    requestId = requestId,
    playerId = playerId,
    body = body,
    localTick = Some(newTick)
  )

  def toServerEvent(newTimestamp: Long, newTick: Long) = Event(
    timestamp = newTimestamp,
    tick = newTick,
    requestId = requestId,
    playerId = playerId,
    body = body,
    localTick = None
  )
}

case class Event(
  timestamp: Long,
  tick: Long,
  requestId: String,
  playerId: String,
  body: Message,
  localTick: Option[Long]
)

sealed abstract class Direction(val id: Int)

object Direction {
  def fromInt(id: Int): Option[Direction] = values.find(_.id == id)

  case object Down extends Direction(0)
  case object Left extends Direction(1)
  case object Right extends Direction(2)
  case object Up extends Direction(3)

  val values = Set(Up, Right, Left, Down)
}

trait Message

case class StartMove(direction: Direction) extends Message
case class StopMove(direction: Direction) extends Message
case object Join extends Message
case class RetrieveStatus(status: GameStatus) extends Message
case object ThrowBomb extends Message
case object Quit extends Message
case object Ping extends Message


object CustomEncoder {
  import io.circe._
  import io.circe.generic.semiauto._
  import io.circe.syntax._

  implicit val directionEncoder: Encoder[Direction] = Encoder.encodeInt.contramap(_.id)
  implicit val startMoveEncoder: Encoder[StartMove] = deriveEncoder
  implicit val stopMoveEncoder: Encoder[StopMove] = deriveEncoder
  implicit val bombEncoder: Encoder[Bomb] = deriveEncoder
  implicit val characterEncoder: Encoder[Character] = deriveEncoder
  implicit val statusEncoder: Encoder[GameStatus] = deriveEncoder
  implicit val retrieveStatusEncoder: Encoder[RetrieveStatus] = deriveEncoder
  implicit val messageEncoder: Encoder[Message] = Encoder.instance {
    case sm: StartMove => sm.asJson.mapObject(_.add("type", Json.fromString("Start")))
    case sm: StopMove => sm.asJson.mapObject(_.add("type", Json.fromString("Stop")))
    case rs: RetrieveStatus => rs.asJson.mapObject(_.add("type", Json.fromString("Status")))
    case ThrowBomb => Json.obj("type" -> Json.fromString("ThrowBomb"))
    case Quit => Json.obj("type" -> Json.fromString("Quit"))
    case Join => Json.obj("type" -> Json.fromString("Join"))
    case Ping => Json.obj("type" -> Json.fromString("Ping"))
  }
  implicit val directionDecoder: Decoder[Direction] = Decoder.decodeInt.map(i => Direction.fromInt(i).get)
  implicit val startMoveDecoder: Decoder[StartMove] = deriveDecoder
  implicit val stopMoveDecoder: Decoder[StopMove] = deriveDecoder
  implicit val bombDecoder: Decoder[Bomb] = deriveDecoder
  implicit val characterDecoder: Decoder[Character] = deriveDecoder
  implicit val statusDecoder: Decoder[GameStatus] = deriveDecoder
  implicit val retrieveStatusDecoder: Decoder[RetrieveStatus] = deriveDecoder
  implicit val messageDecoder: Decoder[Message] = Decoder.instance { h =>
    for {
      tp <- h.get[String]("type")
      msg <- tp match {
        case "Start" => h.as[StartMove]
        case "Stop" => h.as[StopMove]
        case "Status" => h.as[RetrieveStatus]
        case "ThrowBomb" => Right(ThrowBomb)
        case "Quit" => Right(Quit)
        case "Join" => Right(Join)
        case "Ping" => Right(Ping)
      }
    } yield msg
  }
  implicit val commandEncoder: Encoder[Command] = deriveEncoder
  implicit val commandDecoder: Decoder[Command] = deriveDecoder
  implicit val eventEncoder: Encoder[Event] = deriveEncoder
  implicit val eventDecoder: Decoder[Event] = deriveDecoder
}