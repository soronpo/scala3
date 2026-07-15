// https://github.com/soronpo/scala3/issues/13
// Companion to i2XYZW. A deferred `compiletime.error` produced by a (separately
// compiled) macro and routed through *user-defined* inline rewrite methods must
// still be reported at the outermost use site, hiding the rewrite methods'
// internals. This is the counterpart to i2XYZW, where the error must instead
// pinpoint a user-written nested operand: both are decided by whether the
// enclosing inline call is lexically contained in the use site the user wrote.
// See tests/neg-macros/i2XYZW-b.check.
package repro
import scala.quoted.*

final class V(val n: Int)

object Mac:
  transparent inline def check(inline v: V): V = ${ checkMacro('v) }

  def checkMacro(v: Expr[V])(using Quotes): Expr[V] =
    import quotes.reflect.*
    v.asTerm.underlyingArgument match
      case Apply(fun, List(Literal(IntConstant(0)))) if fun.symbol.name == "<init>" =>
        '{ compiletime.error("invalid operand") }   // deferred error, produced in this (macro) source
      case _ => v
end Mac
