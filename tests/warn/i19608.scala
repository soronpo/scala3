// https://github.com/scala/scala3/issues/19608
// Match-type traces should use the source-level wildcard syntax. Under
// -source:future the wildcard is `?`, not the deprecated `_`.

//> using options -source future

trait Bar[T]

type MatchType[T] = T match
  case Bar[?] => Nothing

def bar[T](x: T): MatchType[T] = ???

val r: String = bar[Int](1) // warn
