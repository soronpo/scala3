// Relaxed Extension Method Resolution — most-specific selection within a tier.
// https://contributors.scala-lang.org/t/relaxed-extension-methods-sip-54-are-not-relaxed-enough/6585
//
// When several extension methods at the same precedence level apply to the
// receiver, the most specific one is selected (as co-located overloaded
// extension methods already are), instead of reporting an ambiguity. Lexical
// precedence still comes first: a closer, more general extension wins over a
// farther, more specific one (option (a)).

class Animal
class Dog extends Animal

object A:
  extension (a: Animal) def sound: String = "generic"
object B:
  extension (d: Dog) def sound: String = "woof"

@main def Test: Unit =
  import A.*
  import B.*

  // Both A.sound (Animal) and B.sound (Dog) apply to a Dog receiver and are at
  // the same precedence level (two wildcard imports). The more specific one (Dog)
  // is chosen rather than being reported as ambiguous.
  println((Dog(): Dog).sound)    // woof
  // Only the Animal extension applies to an Animal receiver.
  println((Dog(): Animal).sound) // generic

  // Precedence-first: a closer, more general extension shadows the farther,
  // more specific imported one. Specificity only breaks ties *within* a tier.
  locally {
    extension (a: Animal) def sound: String = "local-generic"
    println((Dog(): Dog).sound)  // local-generic
  }
