// Pattern P2 — default argument with redundant prefix.

object P2Cases:

  sealed trait Color
  object Color:
    case object Red   extends Color
    case object Green extends Color
    case object Blue  extends Color

  // 1. single-arg default
  def f1(c: Color = Color.Red): Unit = ()

  // 2. trailing default in multi-arg
  def f2(label: String, c: Color = Color.Green): Unit = ()

  // 3. multiple defaults — two incidents
  def f3(a: Color = Color.Red, b: Color = Color.Blue): Unit = ()

  // 4. using clause
  def f4(using c: Color = Color.Green): Unit = ()
