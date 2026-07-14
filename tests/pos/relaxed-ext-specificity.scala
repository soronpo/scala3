// Relaxed Extension Method Resolution — most-specific selection within a tier.
// https://contributors.scala-lang.org/t/relaxed-extension-methods-sip-54-are-not-relaxed-enough/6585

// Subtype specificity across two import sources.
class Animal
class Dog extends Animal
object A:
  extension (a: Animal) def sound: String = "generic"
object B:
  extension (d: Dog) def sound: String = "woof"

// Generic (covariant) specificity across two import sources.
class Box[+T]
object P:
  extension (b: Box[Any]) def tag: Int = 0
object Q:
  extension (b: Box[Int]) def tag: Int = 1

def test: Unit =
  import A.*, B.*
  val _: String = (Dog(): Dog).sound     // picks B (Dog), the most specific
  val _: String = (Dog(): Animal).sound  // only A (Animal) applies

  import P.*, Q.*
  val _: Int = (Box[Int](): Box[Int]).tag // picks Q (Box[Int]), the most specific
  val _: Int = (Box[String](): Box[String]).tag // only P (Box[Any]) applies
