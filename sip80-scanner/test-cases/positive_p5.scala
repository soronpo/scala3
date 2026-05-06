// Pattern P5 — `case T.X` after a typed scrutinee.

object P5Cases:

  sealed trait Color
  object Color:
    case object Red   extends Color
    case object Green extends Color
    case object Blue  extends Color

  def example1: String =
    val c: Color = Color.Red
    c match
      case Color.Red   => "red"
      case Color.Green => "green"
      case Color.Blue  => "blue"

  def example2(c: Color): String =
    c match {
      case Color.Red => "red"
    }
