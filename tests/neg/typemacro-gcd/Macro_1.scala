import scala.quoted.*

object gcdlib:

  /** Type macro computing the greatest common divisor of two `Int` singletons.
   *  This operation is *not* provided by `scala.compiletime.ops` and would
   *  otherwise require a new compiler intrinsic.
   */
  type Gcd[A <: Int, B <: Int] <: Int = ${ gcdImpl[A, B] }

  def gcdImpl[A <: Int : Type, B <: Int : Type](using Quotes): Type[? <: Int] =
    import quotes.reflect.*
    def gcd(x: Int, y: Int): Int = if y == 0 then x else gcd(y, x % y)
    (TypeRepr.of[A], TypeRepr.of[B]) match
      case (ConstantType(IntConstant(a)), ConstantType(IntConstant(b))) =>
        ConstantType(IntConstant(gcd(a, b))).asType.asInstanceOf[Type[? <: Int]]
      case _ =>
        report.errorAndAbort("Gcd expects two integer singleton types")
