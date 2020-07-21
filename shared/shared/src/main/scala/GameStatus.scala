

case class Character(
  name: String,
  x: Float,
  y: Float,
  z: Float,
  ax: Float,
  ay: Float,
  az: Float,
  frame: Int,
  dir: Direction,
  moving: Set[Direction],
  lives: Int)

object Character {
  def withName(name: String) =
    Character(name, 400, 300, 0, 0, 0, 0, 0, Direction.Down, Set.empty, 3)
}

case class Bomb(id: String, owner: String, x: Float, y: Float, z: Float, frame: Int, direction: Direction)

case class GameStatus(
  tick: Long,
  characters: List[Character],
  bombs: List[Bomb]
)

object GameStatus {
  val empty = GameStatus(0, List.empty, List.empty)
}