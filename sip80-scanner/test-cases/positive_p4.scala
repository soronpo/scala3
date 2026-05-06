// Pattern P4 — generic typed apply with redundant prefix.

object P4Cases:

  sealed trait Color
  object Color:
    case object Red   extends Color
    case object Green extends Color
    case object Blue  extends Color

  // 1+2. Seq[Color] with two redundant args.
  val s1 = Seq[Color](Color.Red, Color.Green)

  // 3. List with one arg.
  val s2 = List[Color](Color.Blue)

  // 4. Vector
  val s3 = Vector[Color](Color.Red)
