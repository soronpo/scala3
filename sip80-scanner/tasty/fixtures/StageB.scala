// Stage-B fixture: positions where SIP-80 fires but Stage A misses.
// These are Stage B's exclusive wins: catching them requires real type
// information (TASTy) because they involve bare identifiers, ascriptions,
// or method calls whose param types are not visible to a regex.

package fixtures

object StageB:

  sealed trait Color
  object Color:
    case object Red   extends Color
    case object Green extends Color
    case object Blue  extends Color

  // -------------------------------------------------------------------------
  // B-1: bare identifier resolved through a wildcard import.
  //
  // ``import Color.*`` brings ``Red``, ``Green``, ``Blue`` into scope. The
  // user's source literally says ``Red``, but TASTy records the resolved
  // path ``Color.Red``. Stage A cannot tell ``Red`` from any other ident.
  // SIP-80 would let the user delete the import and write ``.Red`` here.
  // Character savings at the use site are NEGATIVE (``Red`` → ``.Red``)
  // but the import line is no longer needed; we record the incident with
  // 0 char savings at the use site and report it separately.
  // -------------------------------------------------------------------------

  import Color.*

  val b1a: Color = Red                            //+B import-based; save_at_site=0
  val b1b: Color = Green                          //+B import-based; save_at_site=0
  val b1c: Color = Blue                           //+B import-based; save_at_site=0

  // -------------------------------------------------------------------------
  // B-2: typed method call with redundant prefix in arg.
  //
  // Stage A's P3/P4 only catch ``T(T.X)`` and ``Coll[T](T.X)``. Plain
  // method calls ``f(T.X)`` where ``f``'s param has type ``T`` are missed
  // by Stage A. Stage B sees the param type via TASTy.
  // -------------------------------------------------------------------------

  def takesColor(c: Color): Unit = ()

  val b2a: Unit = takesColor(Color.Red)           //+B fn-arg; T=Color X=Red save=6
  val b2b: Unit = takesColor(Color.Green)         //+B fn-arg; T=Color X=Green save=6

  // -------------------------------------------------------------------------
  // B-3: ascription gives a target type to an otherwise untyped expression.
  // Stage A can't tell ``(Color.Red: Color)`` from ``(Color.Red: Any)``.
  // Stage B sees the ascription type as the expected type.
  // -------------------------------------------------------------------------

  val b3a = (Color.Red: Color)                    //+B ascription; T=Color X=Red save=6

  // -------------------------------------------------------------------------
  // B-4: branch arms in if/else/match returning a typed value.
  // Stage A catches the val itself (``val x: Color = ...``) but not each
  // branch. Stage B sees that each branch position has expected type
  // ``Color`` independently.
  // -------------------------------------------------------------------------

  def b4(cond: Boolean): Color =
    if cond then Color.Red else Color.Blue
                                                  //+ T=Color X=Red save=6
                                                  //+ T=Color X=Blue save=6

  // -------------------------------------------------------------------------
  // B-5: nested extractor patterns. ``Some(Color.Red)`` matched as
  // ``case Some(Color.Red)`` — the inner pattern's expected type is
  // ``Color``. Stage A's P5 only handles top-level cases.
  // -------------------------------------------------------------------------

  val b5opt: Option[Color] = Some(Color.Red)      //+B Option apply; T=Color X=Red save=6

  def b5match(o: Option[Color]): String =
    o match
      case Some(Color.Red)   => "red"             //+B nested pattern; T=Color X=Red save=6
      case Some(Color.Green) => "green"           //+B nested pattern; T=Color X=Green save=6
      case _                 => "other"
