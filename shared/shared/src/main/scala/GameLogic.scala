import scala.concurrent.duration._

object GameLogic {

  val tickRate = 10.milliseconds

  def updateStatusUntil(status: GameStatus, events: List[Event], currentTick: Long) = {
    (status.tick until currentTick).foldLeft(status)(
      (oldStatus, tick) =>
        updateStatusWithTick(events.filter(_.tick == tick).foldLeft(oldStatus)(updateStatusWithEvent))
    )
  }

  def updateStatusWithEvent(status: GameStatus, event: Event) = {
    val name = event.playerId

    val updateCharacterList: List[Character] => List[Character] = cl => event.body match {
      case Join =>
        println("Adding new player " + name)
        Character.withName(name) :: cl.filterNot(_.name == name)
      case Quit => cl.filterNot(_.name == event.playerId)
      case _ => cl
    }

    val updateBombList: List[Bomb] => List[Bomb] = bl => event.body match {
      case ThrowBomb =>
        bl ++ status.characters.collectFirst {
          case character if character.name == name && character.lives > 0 =>
            Bomb(event.requestId, event.playerId, character.x, character.y, character.z, 60, character.dir)
        }.toList
      case _ => bl
    }

    val update: Character => Character = c => event.body match {
      case StartMove(direction) => c.copy(moving = c.moving + direction)
      case StopMove(direction) => c.copy(moving = c.moving - direction)
      case _ => c
    }

    status.copy(
      characters = updateCharacterList(status.characters).map {
        case character if character.name == name => update(character)
        case other => other
      },
      bombs = updateBombList(status.bombs)
    )
  }

  def updateStatusWithTick(status: GameStatus) = {
    status.copy(
      tick = status.tick + 1,
      characters = status.characters.map { c =>
        def distTo(p: (Float, Float)): Float = Math.sqrt(pow(c.x - p._1) + pow(c.y - p._2)).toFloat

        val fallAcceleration = 0.1f
        val maxSpeed = 2f

        val falling = c.z < 0 || (distTo((395 - 262, 271)) + distTo((395 + 262, 271))) > 640
        val c0 = if (falling && c.z < -1000) {
          Character.withName(c.name).copy(lives = Math.max(c.lives - 1, 0), z = 40)
        }
        else if (falling) c.copy(az = c.az - fallAcceleration)
        else c

        val c1 = {
          if (c0.z > 0 && c0.z + c0.az <= 0 && !falling) c0.copy(z = 0, az = 0)
          else if (c0.z > 0 || falling || c0.az > 0) c0.copy(z = c0.z + c0.az, az = c0.az - fallAcceleration)
          else c0
        }

        val c2 = if (c.moving(Direction.Right)) {
          val acc = if (c.ax < maxSpeed) 0.2f else 0f
          c1.copy(ax = c.ax + acc, dir = Direction.Right)
        } else if (c.moving(Direction.Left)) {
          val acc = if (c.ax > -maxSpeed) -0.2f else 0f
          c1.copy(ax = c.ax + acc, dir = Direction.Left)
        } else {
          if (c.ax > 0.1f)
            c1.copy(ax = c.ax - 0.05f)
          else if (c.ax < -0.1f)
            c1.copy(ax = c.ax + 0.05f)
          else
            c1.copy(ax = 0f)
        }

        val c3 = if (c2.moving(Direction.Down)) {
          val acc = if (c.ay < maxSpeed) 0.2f else 0f
          c2.copy(ay = c2.ay + acc, dir = Direction.Down)
        } else if (c2.moving(Direction.Up)) {
          val acc = if (c.ay > -maxSpeed) -0.2f else 0f
          c2.copy(ay = c2.ay + acc, dir = Direction.Up)
        } else {
          if (c2.ay > 0.1f)
            c2.copy(ay = c2.ay - 0.05f)
          else if (c2.ay < -0.1f)
            c2.copy(ay = c2.ay + 0.05f)
          else
            c2.copy(ay = 0)
        }

        val c4 = {
          val ax = if (c3.ax > maxSpeed) c3.ax - 0.2f
          else if (c3.ax < -maxSpeed) c3.ax + 0.2f
          else c3.ax
          val ay = if (c3.ay > maxSpeed) c3.ay - 0.2f
          else if (c3.ay < -maxSpeed) c3.ay + 0.3f
          else c3.ay
          c3.copy(ax = ax, ay = ay)
        }

        // check bomb explosions
        val c5 = {

          status.bombs.foldLeft(c4) { (c, b) =>
            val xDist = pow(b.x - c.x)
            val yDist = pow(b.y - c.y)
            val zDist = pow(b.z - c.z)
            if (c.lives > 0 && xDist + yDist + zDist < pow(100) && b.frame == -10) {
              val power = (pow(100)-(xDist + yDist + zDist))/pow(100)

              val ang = Math.atan2(c.y - b.y, c.x - b.x)
              val ax = Math.cos(ang).toFloat * 8 * power
              val ay = Math.sin(ang).toFloat * 8 * power
              val az = 4 * power

              c.copy(az = c.az + az, ax = c.ax + ax, ay = c.ay + ay)
            } else c
          }
        }

        val frame = Math.max(Math.ceil(Math.abs(c.ax)), Math.ceil(Math.abs(c.ay))).toInt/2
        c5.copy(x = c3.x + c3.ax, y = c3.y + c3.ay, frame = c3.frame + frame)
      },
      bombs = status.bombs.map { bomb =>
        (if (bomb.frame > 40 || bomb.frame > 0 && bomb.z > 0) {
          val speed = 1 + bomb.frame * 0.1f
          bomb.direction match {
            case Direction.Right => bomb.copy(x = bomb.x + speed)
            case Direction.Left => bomb.copy(x = bomb.x - speed)
            case Direction.Up => bomb.copy(y = bomb.y - speed)
            case Direction.Down => bomb.copy(y = bomb.y + speed)
          }
        } else bomb).copy(frame = bomb.frame - 1, z = Math.max(0, bomb.z + 3.5f - (60 - bomb.frame) * 0.3f ))
      }.filter(_.frame > -36)
    )
  }


  @inline private def pow(a: Float) = a * a
}
