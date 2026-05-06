// Stage-B fixture: places where Stage A's regex heuristic fires but the
// expected type at the position is NOT the prefix's principal class.
// Stage B (TASTy-aware) MUST NOT count these. Each ``//-`` comment marks a
// position where SIP-80 should not fire.

package fixtures

object FalsePositives:

  // -------------------------------------------------------------------------
  // FP-1: outer call's parameter type differs from outer call's class.
  //
  // ``Arbitrary(Arbitrary.arbitrary[Int])`` looks like Shape(Shape.X) to a
  // syntactic scanner: outer call name ``Arbitrary`` is repeated as the
  // arg's prefix. But ``Arbitrary.apply[A]`` takes a ``Gen[A]``, not an
  // ``Arbitrary[A]`` — so the expected type at the arg is ``Gen[Int]`` and
  // SIP-80 does NOT fire (Gen's companion has no ``arbitrary``).
  // -------------------------------------------------------------------------

  trait Gen[A]
  object Gen:
    def const[A](a: A): Gen[A] = new Gen[A] {}

  trait Arbitrary[A]
  object Arbitrary:
    def apply[A](g: => Gen[A]): Arbitrary[A] = new Arbitrary[A] {}
    def arbitrary[A]: Gen[A] = ???

  val fp1: Arbitrary[Int] = Arbitrary(Arbitrary.arbitrary[Int])
                                                  //- expected_type=Gen[Int], principal Gen

  // -------------------------------------------------------------------------
  // FP-2: ``methodName[T](T.factoryMethod)`` where the factory's result
  // type matches the *outer* call's param shape, not [T].
  //
  // ``parsedString[UUID](UUID.fromString)`` — the method takes a
  // ``String => UUID``, the user passes the eta-expanded ``UUID.fromString``
  // function. Expected type at the arg is ``String => UUID``; principal
  // class is ``Function1``, not ``UUID``. SIP-80 does NOT fire.
  // -------------------------------------------------------------------------

  trait Codec[A]
  def parsedString[T](f: String => T): Codec[T] = new Codec[T] {}

  class UUID
  object UUID:
    def fromString(s: String): UUID = new UUID

  val fp2: Codec[UUID] = parsedString[UUID](UUID.fromString)
                                                  //- expected_type=Function1[String, UUID]

  // -------------------------------------------------------------------------
  // FP-3: typed val with same-shaped RHS but different principal class.
  // Stage A's regex rejects this via the back-reference; Stage B must agree.
  // -------------------------------------------------------------------------

  sealed trait Color
  object Color:
    case object Red   extends Color
    case object Green extends Color

  sealed trait Other
  object Other:
    case object Red extends Other

  val fp3: Other = Color.Red.asInstanceOf[Other]  //- prefix Color != principal Other

  // -------------------------------------------------------------------------
  // FP-4: explicit-nulls reduction does NOT apply to general unions.
  // ``val x: A | B`` (with neither being Null) has no principal class —
  // SIP-80 explicitly does not fire here, even if the user happens to
  // write ``A.X``.
  // -------------------------------------------------------------------------

  val fp4: Color | Other = Color.Red              //- expected_type=Color | Other (no principal)
