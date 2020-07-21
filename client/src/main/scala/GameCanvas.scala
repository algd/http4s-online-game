import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Resource, Sync}
import fs2.{Pipe, Stream}
import org.scalajs.dom

object GameCanvas {
  val defaultWidth = 800
  val defaultHeight = 600

  case class StaticResources(
    character: dom.html.Image,
    island: dom.html.Image,
    sky: dom.html.Image,
    rabbit: dom.html.Image,
    explosion: dom.html.Image,
    shadow: dom.html.Image
  )

  def resource[F[_]](element: dom.Element, resources: StaticResources, name: String, width: Int = defaultWidth, height: Int = defaultHeight)(
    implicit F: Sync[F]
  ): Resource[F, Pipe[F, GameStatus, Unit]] = {
    Resource.make {
      F.delay {
        val can: dom.html.Canvas = dom.document.createElement("canvas").asInstanceOf[dom.html.Canvas]
        can.width = width
        can.height = height
        element.appendChild(can)
        (can, (statusStream: Stream[F, GameStatus]) => statusStream.evalMap(status => drawStatus(status, name, can, resources)))
      }
    }{ case (canvas, _) => F.delay(element.removeChild(canvas)) }
      .map(_._2)
  }

  def smoothMove[F[_]](coeff: Float)(implicit F: Concurrent[F]): Pipe[F, GameStatus, GameStatus] = statusStream =>
    for {
      statusRef <- Stream.eval(Ref.of[F, Option[GameStatus]](None))
      newStatus <- statusStream
      statusToDraw <- Stream.eval(statusRef.updateAndGet {
        case None => Some(newStatus)
        case Some(oldStatus) =>
          val adjustedCharacters = newStatus.characters.map { c =>
            oldStatus.characters.find(_.name == c.name).fold(c){ oldC =>
              if (oldC.z < 0 && c.z >= 0) c
              else {
                val adjustedX = oldC.x * coeff + c.x * (1 - coeff)
                val adjustedY = oldC.y * coeff + c.y * (1 - coeff)
                c.copy(x = adjustedX, y = adjustedY)
              }
            }
          }
          val adjustedBombs = newStatus.bombs.map { b =>
            oldStatus.bombs.find(_.id == b.id).orElse(
              newStatus.characters.collectFirst { case c if c.name == b.owner => b.copy(x = c.x, y = c.y, z = c.z) }
            ).fold(b){ oldB =>
              val adjustedX = oldB.x * coeff + b.x * (1 - coeff)
              val adjustedY = oldB.y * coeff + b.y * (1 - coeff)
              b.copy(x = adjustedX, y = adjustedY)
            }
          }
          Some(newStatus.copy(characters = adjustedCharacters, bombs = adjustedBombs))
      }).collect { case Some(v) => v }
    } yield statusToDraw

  private def drawStatus[F[_]](status: GameStatus, name: String, canvas: dom.html.Canvas, resources: StaticResources)(implicit F: Sync[F]): F[Unit] = F.delay {
    val ctx = canvas.getContext("2d").asInstanceOf[dom.raw.CanvasRenderingContext2D]
    ctx.clearRect(0, 0, canvas.width, canvas.height)
    ctx.beginPath()
    ctx.rect(0, 0, canvas.width, canvas.height)
    ctx.fillStyle = "#101020"
    ctx.fill()
    drawBackground(status.tick, ctx, resources.sky)
    val (backCharacters, characters) = status.characters.partition(c => c.z < 0 && c.y < 271)
    val (explosions, bombs) = status.bombs.partition(_.frame <= 0)
    backCharacters.sortBy(_.y).foreach(c => drawCharacter(c, ctx, resources.character))
    drawIsland(ctx, resources.island)
    characters.sortBy(_.y).foreach(c => drawShadow(c, ctx, resources.shadow))
    bombs.sortBy(_.y).foreach(b => drawThrowBomb(b, ctx, resources.rabbit, resources.shadow))
    characters.sortBy(_.y).foreach(c => drawCharacter(c, ctx, resources.character))
    explosions.sortBy(_.y).foreach(b => drawExplosion(b, ctx, resources.explosion))
    ctx.globalCompositeOperation = "source-over"
    val me = status.characters.find(_.name == name)
    me.filter(_.lives <= 0).foreach(_ => drawGameOver(ctx))
    me.foreach(c => drawLives(c, ctx, resources.character))
    drawTick(status.tick, ctx)
    ctx.closePath()
  }

  def drawGameOver(ctx: dom.raw.CanvasRenderingContext2D): Unit = {
    ctx.globalAlpha = 0.6f
    ctx.rect(0, 0, ctx.canvas.width, ctx.canvas.height)
    ctx.fillStyle = "#101020"
    ctx.fill()
    ctx.globalAlpha = 1f
    ctx.font = "40px Arial"
    ctx.textAlign = "center"
    ctx.fillStyle = "white"
    ctx.fillText("Game Over", ctx.canvas.width/2, ctx.canvas.height/2)
  }

  private def drawTick(tick: Long, ctx: dom.raw.CanvasRenderingContext2D): Unit = {
    ctx.font = "12px Arial"
    ctx.textAlign = "left"
    ctx.fillStyle = "white"
    ctx.fillText("Tick: " + tick, 0, ctx.canvas.height - 20)
  }

  private def drawLatency(latency: Long, ctx: dom.raw.CanvasRenderingContext2D): Unit = {
    ctx.font = "12px Arial"
    ctx.textAlign = "left"
    ctx.fillStyle = "white"
    ctx.fillText(s"Lat: ${latency}ms", 3, ctx.canvas.height - 7)
  }

  def drawLives(character: Character, ctx: dom.raw.CanvasRenderingContext2D, img: dom.html.Image): Unit = {
    ctx.font = "20px Arial"
    ctx.textAlign = "right"
    ctx.fillStyle = "black"
    val w = img.width/3
    val h = img.height/5
    val text = character.lives + "x"
    ctx.fillText(text, ctx.canvas.width - w, 38)
    ctx.fillStyle = "white"
    ctx.fillText(text, ctx.canvas.width - w - 2, 36)
    ctx.drawImage(
      img,
      offsetX = w,
      offsetY = 0,
      width = w,
      height = h,
      canvasOffsetX = ctx.canvas.width - w - 4,
      canvasOffsetY = 4,
      canvasImageWidth = w,
      canvasImageHeight = h
    )
  }

  private def drawBackground(tick: Long, ctx: dom.raw.CanvasRenderingContext2D, img: dom.html.Image): Unit = {
    val x = tick % img.width
    ctx.drawImage(
      img,
      offsetX = img.width - x,
      offsetY = 0,
      width = x,
      height = img.height,
      canvasOffsetX = 0,
      canvasOffsetY = 0,
      canvasImageWidth = x,
      canvasImageHeight = img.height
    )
    ctx.drawImage(
    img,
    offsetX = 0,
    offsetY = 0,
    width = img.width - x,
    height = img.height,
    canvasOffsetX = x,
    canvasOffsetY = 0,
    canvasImageWidth = img.width - x,
    canvasImageHeight = img.height
    )
  }

  def drawIsland(ctx: dom.raw.CanvasRenderingContext2D, img: dom.html.Image) = {
    val offsettX = (ctx.canvas.width - defaultWidth) /2
    val offsettY = (ctx.canvas.height - defaultHeight) /2
    ctx.drawImage(
      img,
      offsetX = 0,
      offsetY = 0,
      width = img.width,
      height = img.height,
      canvasOffsetX = offsettX,
      canvasOffsetY = offsettY,
      canvasImageWidth = img.width,
      canvasImageHeight = img.height
    )
  }

  private def drawShadow(character: Character, ctx: dom.raw.CanvasRenderingContext2D, img: dom.html.Image): Unit = {
    val offsetX = (ctx.canvas.width - defaultWidth) /2
    val offsetY = (ctx.canvas.height - defaultHeight) /2
    val w = img.width
    val h = img.height
    val zoom = Math.max(0, 1 - character.z/150f)
    if (character.z >= 0)
      ctx.drawImage(
        img,
        offsetX = 0,
        offsetY = 0,
        width = w,
        height = h,
        canvasOffsetX = offsetX + Math.floor(character.x) - w * zoom / 2,
        canvasOffsetY = offsetY + Math.floor(character.y) - h * zoom / 2,
        canvasImageWidth = w * zoom,
        canvasImageHeight = h * zoom
      )
  }

  private def drawCharacter(character: Character, ctx: dom.raw.CanvasRenderingContext2D, img: dom.html.Image): Unit = {
    val offsetX = (ctx.canvas.width - defaultWidth) /2
    val offsetY = (ctx.canvas.height - defaultHeight) /2
    val pos = Math.abs(((character.frame/10) % 4) - 1)
    ctx.font = "15px Arial"
    ctx.textAlign = "center"
    ctx.fillStyle = "white"
    val w = img.width/3
    val h = img.height/4
    val realy = offsetY + Math.floor(character.y - character.z)
    val name = if (character.lives <= 0) {
      ctx.globalAlpha = 0.4f
      s"${character.name}'s ghost"
    } else character.name
    ctx.fillText(name, offsetX + Math.floor(character.x), realy - 10 - h)
    ctx.drawImage(
      img,
      offsetX = pos * w,
      offsetY = character.dir.id * h,
      width = w,
      height = h,
      canvasOffsetX = offsetX + Math.floor(character.x) - w/2,
      canvasOffsetY = realy - h,
      canvasImageWidth = w,
      canvasImageHeight = h
    )
    ctx.globalAlpha = 1f
  }

  private def drawThrowBomb(bomb: Bomb, ctx: dom.raw.CanvasRenderingContext2D, img: dom.html.Image, shadow: dom.html.Image): Unit = {
    val offsetX = (ctx.canvas.width - defaultWidth) /2
    val offsetY = (ctx.canvas.height - defaultHeight) /2

    val pos = if (bomb.frame > 40) 0 else 1

    val w = img.width/3
    val h = img.height/4

    val zoom = Math.max(0, 0.7f - bomb.z/150f)
    ctx.drawImage(
      shadow,
      offsetX = 0,
      offsetY = 0,
      width = shadow.width,
      height = shadow.height,
      canvasOffsetX = offsetX + Math.floor(bomb.x) - shadow.width * zoom / 2,
      canvasOffsetY = offsetY + Math.floor(bomb.y) - shadow.height * zoom / 4,
      canvasImageWidth = shadow.width * zoom,
      canvasImageHeight = shadow.height * zoom / 2
    )
    ctx.drawImage(
      img,
      offsetX = pos * w,
      offsetY = bomb.direction.id * h,
      width = w,
      height = h,
      canvasOffsetX = offsetX + Math.floor(bomb.x) - w/2,
      canvasOffsetY = offsetY + Math.floor(bomb.y) - h - Math.floor(bomb.z),
      canvasImageWidth = w,
      canvasImageHeight = h
    )
  }

  private def drawExplosion(bomb: Bomb, ctx: dom.raw.CanvasRenderingContext2D, img: dom.html.Image): Unit = {
    val offsetX = (ctx.canvas.width - defaultWidth) /2
    val offsetY = (ctx.canvas.height - defaultHeight) /2

    val realFrame = Math.abs(bomb.frame/2)
    val row = realFrame/5
    val column = realFrame%5

    val w = img.width/5 * (0.25 + 0.25f * realFrame)
    val h = img.height/4 * (0.25 + 0.25f * realFrame)
    ctx.globalCompositeOperation = "lighter"
    ctx.drawImage(
      img,
      offsetX = column * img.width/5,
      offsetY = row * img.height/4,
      width = img.width/5,
      height = img.height/4,
      canvasOffsetX = offsetX + Math.floor(bomb.x) - w/2,
      canvasOffsetY = offsetY + Math.floor(bomb.y) - h/2 - Math.floor(bomb.z),
      canvasImageWidth = w,
      canvasImageHeight = h
    )
    ctx.drawImage(
      img,
      offsetX = column * img.width/5,
      offsetY = row * img.height/4,
      width = img.width/5,
      height = img.height/4,
      canvasOffsetX = offsetX + Math.floor(bomb.x) - w/2,
      canvasOffsetY = offsetY + Math.floor(bomb.y) - h/4 - Math.floor(bomb.z),
      canvasImageWidth = w,
      canvasImageHeight = h/2
    )

  }
}
