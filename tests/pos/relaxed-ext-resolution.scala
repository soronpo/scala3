// Relaxed Extension Method Resolution
// https://contributors.scala-lang.org/t/relaxed-extension-methods-sip-54-are-not-relaxed-enough/6585
//
// The motivating example: a library extension on Foo[Int] and a user extension
// on Foo[String] with the same name. Selecting on Foo[Int] must resolve to the
// library's extension rather than failing because the user's (closer, but
// inapplicable) extension shadows it by name.

class Foo[T]

object Lib:
  extension (foo: Foo[Int])
    def bar: Unit = {}

import Lib.*
extension (foo: Foo[String])
  def bar: Unit = {}

val fi = Foo[Int]()
val fs = Foo[String]()

def test: Unit =
  fi.bar // used to error: "value bar is not a member of Foo[Int]"
  fs.bar
