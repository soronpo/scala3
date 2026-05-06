// Stage-B fixture: patterns Stage A already catches.
// Stage B (TASTy-aware) MUST catch all of these too, with the same or
// higher fidelity. Each ``//+`` comment is a place SIP-80 should fire.

package fixtures

object Patterns:

  sealed trait Color
  object Color:
    case object Red   extends Color
    case object Green extends Color
    case object Blue  extends Color
    def empty: Color = Red

  final case class Shape(geometry: Shape.Geometry, color: Shape.Color)
  object Shape:
    sealed trait Geometry
    object Geometry:
      case object Triangle  extends Geometry
      case object Rectangle extends Geometry
      case object Circle    extends Geometry
    sealed trait Color
    object Color:
      case object Red   extends Color
      case object Green extends Color
      case object Blue  extends Color

  // -------------------------------------------------------------------------
  // P1  typed val/var/def with redundant prefix
  // -------------------------------------------------------------------------

  val p1a: Color = Color.Red                      //+ T=Color X=Red save=6
  var p1b: Color = Color.Green                    //+ T=Color X=Green save=6
  lazy val p1c: Color = Color.Blue                //+ T=Color X=Blue save=6
  def p1d: Color = Color.Red                      //+ T=Color X=Red save=6
  def p1e(): Color = Color.Green                  //+ T=Color X=Green save=6
  def p1f(x: Int): Color = Color.Blue             //+ T=Color X=Blue save=6
  val p1g: Color | Null = Color.Red               //+ T=Color X=Red save=6
  val p1h: Color = Color.empty                    //+ T=Color X=empty save=6

  // -------------------------------------------------------------------------
  // P2  default argument with redundant prefix
  // -------------------------------------------------------------------------

  def p2a(c: Color = Color.Red): Unit = ()        //+ T=Color X=Red save=6
  def p2b(label: String, c: Color = Color.Green): Unit = ()
                                                  //+ T=Color X=Green save=6
  def p2c(a: Color = Color.Red, b: Color = Color.Blue): Unit = ()
                                                  //+ T=Color X=Red save=6
                                                  //+ T=Color X=Blue save=6
  def p2d(using c: Color = Color.Green): Unit = ()
                                                  //+ T=Color X=Green save=6

  // -------------------------------------------------------------------------
  // P3  constructor self-prefix (real ADT construction)
  // -------------------------------------------------------------------------

  val p3a: Shape = Shape(Shape.Geometry.Circle, Shape.Color.Red)
                                                  //+ T=Shape.Geometry X=Circle save=15
                                                  //+ T=Shape.Color    X=Red    save=12

  val p3b: Shape =
    Shape(geometry = Shape.Geometry.Triangle, color = Shape.Color.Green)
                                                  //+ T=Shape.Geometry X=Triangle save=15
                                                  //+ T=Shape.Color    X=Green    save=12

  // -------------------------------------------------------------------------
  // P4  generic typed apply with redundant prefix (real collection case)
  // -------------------------------------------------------------------------

  val p4a: Seq[Color]    = Seq[Color](Color.Red, Color.Green)
                                                  //+ T=Color X=Red   save=6
                                                  //+ T=Color X=Green save=6
  val p4b: List[Color]   = List[Color](Color.Blue)
                                                  //+ T=Color X=Blue  save=6
  val p4c: Vector[Color] = Vector[Color](Color.Red)
                                                  //+ T=Color X=Red   save=6

  // -------------------------------------------------------------------------
  // P5  match cases on a typed scrutinee
  // -------------------------------------------------------------------------

  def p5a(c: Color): String =
    c match
      case Color.Red   => "red"                   //+ T=Color X=Red save=6
      case Color.Green => "green"                 //+ T=Color X=Green save=6
      case Color.Blue  => "blue"                  //+ T=Color X=Blue save=6

  def p5b(c: Color): String =
    c match {
      case Color.Red => "red"                     //+ T=Color X=Red save=6
      case _         => "other"
    }

  def p5c(c: Color): String =
    c match
      case Color.Red | Color.Blue => "primary-ish"
                                                  //+ T=Color X=Red  save=6
                                                  //+ T=Color X=Blue save=6
      case _                       => "other"
