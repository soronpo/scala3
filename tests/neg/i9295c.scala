import scala.language.dynamics

class Foo extends Dynamic {
  def selectDynamic(name: String)(using DummyImplicit): Bar = ???
}

class Bar extends Dynamic {
  def applyDynamic(name: String)(x: Int) = ???
}

val foo = new Foo
def baz = foo.blah(42) // error
