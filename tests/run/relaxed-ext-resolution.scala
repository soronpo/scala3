// Relaxed Extension Method Resolution
// https://contributors.scala-lang.org/t/relaxed-extension-methods-sip-54-are-not-relaxed-enough/6585
//
// An extension method visible in a closer scope must not shadow an applicable
// extension method of the same name in an enclosing scope. The closest
// *applicable* candidate wins; lexical precedence is only a tie-breaker.

class Foo[T]

object Lib:
  extension (foo: Foo[Int]) def bar: String = "Lib.bar(Int)"

object Outer:
  extension (foo: Foo[Int]) def baz: String = "Outer.baz(Int)"

@main def Test: Unit =
  import Lib.*
  // Local `bar` on Foo[String] lexically shadows the imported `bar` on Foo[Int].
  extension (foo: Foo[String]) def bar: String = "local.bar(String)"

  val fi = Foo[Int]()
  val fs = Foo[String]()

  // Fallback across the import boundary: the local `bar` does not apply to
  // Foo[Int], so resolution falls back to the imported Lib.bar.
  println(fi.bar) // Lib.bar(Int)
  // The local `bar` applies to Foo[String] and is used directly.
  println(fs.bar) // local.bar(String)

  // Fallback across a definition boundary (models split top-level extensions):
  // the block-local `baz` on Foo[String] shadows the imported `baz` on Foo[Int].
  locally {
    import Outer.*
    extension (foo: Foo[String]) def baz: String = "local.baz(String)"
    println(fi.baz) // Outer.baz(Int)  -- fell back to the imported extension
    println(fs.baz) // local.baz(String)
  }

  // Genuine shadowing is preserved: when the closer candidate also applies to
  // the receiver type, it wins over the enclosing one, exactly as before.
  locally {
    extension (foo: Foo[Int]) def bar: String = "shadow.bar(Int)"
    println(fi.bar) // shadow.bar(Int)
  }
