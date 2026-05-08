// Regression test for scala/scala3#26015.
//
// `-rewrite -source 3.4-migration` rewrites `with` to `&` in all type
// positions, including the RHS of typed-pattern ascriptions. The
// pre-fix grammar was `Pattern1 ::= PatVar ':' RefinedType`, which
// accepts `with` (compound type) but not `&` (infix type), so the
// auto-rewritten code stopped parsing.
//
// The fix accepts `&`-chained intersections in typed-pattern type
// positions (but not `|`, which remains the pattern-alternative
// separator).
trait A
trait B
trait C
case class N(child: Any)

def m(n: N): String = n match
  case N(child: A & B)     => "ab"
  case N(child: A & B & C) => "abc"
  case _                   => "other"

// `|` after a typed-pattern is still the pattern-alternative separator,
// not a type union — explicit parens are required for a union ascription.
def alt(x: Any): Int = x match
  case _: Int | _: String => 1
  case _: (Int | String)  => 2
  case _                  => 0
