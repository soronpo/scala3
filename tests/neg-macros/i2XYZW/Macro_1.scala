// https://github.com/soronpo/scala3/issues/13
// A deferred `compiletime.error` produced by an inner inline macro must be
// reported at the sub-expression that produced it, even when an enclosing
// inline macro strips the error's `Inlined` wrapper and re-splices the bare
// node into its own expansion. See tests/neg-macros/i2XYZW.check.
package repro
import scala.quoted.*

final class V(val n: Int)

object Macros:
  extension (inline lhs: V)
    transparent inline def +(inline rhs: V): V = ${ plusMacro('lhs, 'rhs) }

  def plusMacro(lhs: Expr[V], rhs: Expr[V])(using Quotes): Expr[V] =
    import quotes.reflect.*
    def isInvalid(e: Expr[V]): Boolean =
      e.asTerm.underlyingArgument match
        case Apply(fun, List(Literal(IntConstant(0)))) if fun.symbol.name == "<init>" => true
        case _                                                                        => false
    if isInvalid(lhs) || isInvalid(rhs) then
      '{ compiletime.error("invalid operand") }          // deferred error, type Nothing
    else
      def flatten(t: Term): Term = t match               // strip Inlined/Block wrappers
        case Inlined(_, _, inner) => flatten(inner)
        case Block(_, expr)       => flatten(expr)
        case _                    => t
      val l = flatten(lhs.asTerm).asExprOf[V]
      val r = flatten(rhs.asTerm).asExprOf[V]
      '{ V($l.n + $r.n) }                                 // re-splice flattened operands
  end plusMacro
end Macros
