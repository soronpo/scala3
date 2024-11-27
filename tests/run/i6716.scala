//> using options -Xfatal-warnings

trait Monad[T]:
  def id: String
class Foo
object Foo {
  given Monad[Foo] { def id = "Foo" }
}

opaque type Bar = Foo
object Bar {
  given Monad[Bar] = summon[Monad[Foo]] // was error, fixed by given loop prevention
}

object Test extends App {
  println(summon[Monad[Foo]].id)
  println(summon[Monad[Bar]].id)
}