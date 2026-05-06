// Pattern P1 — typed val/var/def/lazy val with redundant prefix.
// Each line below is one P1 incident.

object P1Cases:

  sealed trait Color
  object Color:
    case object Red   extends Color
    case object Green extends Color
    case object Blue  extends Color
    def empty: Color = Red

  // 1. Plain val with simple type.
  val c1: Color = Color.Red

  // 2. var
  var c2: Color = Color.Green

  // 3. lazy val
  lazy val c3: Color = Color.Blue

  // 4. def with no params
  def c4: Color = Color.Red

  // 5. def with empty parens
  def c5(): Color = Color.Green

  // 6. def with params
  def c6(x: Int): Color = Color.Blue

  // 7. RHS chained — only the leading `Color.` is removable
  def c7: Color = Color.Red

  // 8. nullable type
  val c8: Color | Null = Color.Red

  // 9. `def empty` → factory member with no args
  val c9: Color = Color.empty
