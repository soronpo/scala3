// Stage-B fixture: positions where SIP-80 does NOT fire and neither
// Stage A nor Stage B should record an incident.

package fixtures

object Negatives:

  sealed trait Color
  object Color:
    case object Red extends Color

  // -------------------------------------------------------------------------
  // N-1: untyped val. Without a declared type, the expected type is the
  // RHS's own type; SIP-80 has nothing to drop.
  // -------------------------------------------------------------------------

  val n1 = Color.Red                              //- no declared type

  // -------------------------------------------------------------------------
  // N-2: type-argument position. SIP-80 explicitly does not apply at the
  // type level.
  // -------------------------------------------------------------------------

  // val n2 = List[Color.Red.type](Color.Red)     // ill-formed; just noting

  // -------------------------------------------------------------------------
  // N-3: equality ``c == Color.Red``. The right operand's expected type
  // is ``Any`` (``==`` is on Any), which has no companion members named
  // Red. SIP-80 does not fire.
  // -------------------------------------------------------------------------

  def n3(c: Color): Boolean = c == Color.Red      //- == on Any

  // -------------------------------------------------------------------------
  // N-4: bare RHS of an infix operator (parser-level exclusion in SIP-80).
  // Even with parens, the equality case above shows SIP-80 still does
  // not fire because the expected type is Any. Here we use a typed
  // user-defined extension to make the position triggering, which IS a
  // SIP-80 win — so this case is on the *positive* side and we count it.
  // The negative is the bare ``c.toString`` style call.
  // -------------------------------------------------------------------------

  def n4(c: Color): String = c.toString           //- no T.X form anywhere

  // -------------------------------------------------------------------------
  // N-5: direct constant without prefix. Nothing for SIP-80 to shorten.
  // -------------------------------------------------------------------------

  val n5: Int = 42                                //- not a companion lookup
