// Pattern P3 — constructor self-prefix in args.

object P3Cases:

  final case class Shape(geometry: Shape.Geometry, color: Shape.Color)
  object Shape:
    sealed trait Geometry
    object Geometry:
      case object Triangle extends Geometry
      case object Circle   extends Geometry
    sealed trait Color
    object Color:
      case object Red   extends Color
      case object Green extends Color

  // 1+2. Two redundant args.
  val a1 = Shape(Shape.Geometry.Circle, Shape.Color.Red)

  // 3. Named arg form.
  val a2 = Shape(geometry = Shape.Geometry.Triangle, color = Shape.Color.Green)

  // 4. Single-arg call.
  val a3 = Shape(Shape.Geometry.Circle, Shape.Color.Red)
