import scala.quoted.*

object poslib:

  /** Type macro that reduces to `N` when `N` is a positive `Int` singleton, and
   *  aborts compilation with a clear message otherwise. Demonstrates a type macro
   *  that performs validation and reports a domain-specific error.
   */
  type Positive[N <: Int] <: Int = ${ positiveImpl[N] }

  def positiveImpl[N <: Int : Type](using Quotes): Type[? <: Int] =
    import quotes.reflect.*
    TypeRepr.of[N] match
      case ConstantType(IntConstant(n)) if n > 0 =>
        Type.of[N]
      case ConstantType(IntConstant(n)) =>
        report.errorAndAbort(s"Positive expects a positive Int, but got $n")
      case _ =>
        report.errorAndAbort("Positive expects an Int singleton type")
