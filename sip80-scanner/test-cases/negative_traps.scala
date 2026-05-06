// Negative traps — none of these lines should produce any incident.

object Traps:

  sealed trait Color
  object Color:
    case object Red extends Color

  sealed trait Other
  object Other:
    case object X extends Other

  // 1. Comment containing a positive-looking pattern.
  // val c: Color = Color.Red

  /* val c: Color = Color.Red */

  // 2. String literal containing a positive-looking pattern.
  val s1 = "val c: Color = Color.Red"

  val s2 = """
    val c: Color = Color.Red
    Shape(Shape.Geometry.Circle, Shape.Color.Red)
  """

  // 3. RHS prefix differs from LHS type — no SIP-80 win.
  val c1: Other = Color.Red.asInstanceOf[Other]   // RHS prefix Color != Other

  // 4. LHS has no type annotation — SIP-80 doesn't fire.
  val c2 = Color.Red

  // 5. Function call where neither arg uses the call's name as prefix.
  val s3 = Other(Color.Red)            // P3 needs `Other.` prefix in args

  // 6. Generic typed apply but type arg is a single-letter type variable.
  def fn[A](a: A): Seq[A] = Seq[A](a)  // Seq[A] with arg `a`, not `A.`

  // 7. case in a match without a typed scrutinee — P5 needs scrutinee type.
  val anything = ???
  // anything match { case Color.Red => () }   // anything's type is unknown
