// Relaxed Extension Method Resolution — cases that must still be rejected.
// https://contributors.scala-lang.org/t/relaxed-extension-methods-sip-54-are-not-relaxed-enough/6585

class Foo
class Bar

object A:
  extension (foo: Foo) def bar: Int = 1
object B:
  extension (foo: Foo) def bar: Int = 2

object C:
  extension (b: Bar) def qux: Int = 0

val f = Foo()

// Two same-level candidates that BOTH apply to the receiver stay ambiguous
// (this is the SIP-54 rule; the relaxation must not silently pick one).
def ambiguous: Int =
  import A.*
  import B.*
  f.bar // error

// A same-named extension exists, but on an unrelated type, so nothing applies:
// the selection must fail cleanly (not fall through to a crash).
def nothingApplies: Int =
  import C.*
  f.qux // error
