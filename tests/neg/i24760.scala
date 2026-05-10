// https://github.com/scala/scala3/issues/24760
// An `inline match` should not typecheck against a match type as expected type.
// The two are not semantically equivalent: inline match selects on the
// scrutinee's static type/term-level patterns, match type selects on the
// type's structural shape; combining them allows a body whose actual type
// (e.g. Int) to be silently accepted at a position where the match type
// reduces to a different type (e.g. String), producing ClassCastException.

import scala.compiletime.erasedValue

object Test1:
  type MatchType[T] = T match
    case EmptyTuple => String
    case _          => Int

  trait MyTrait[T]:
    def f: MatchType[T]

  class MyClass[T] extends MyTrait[T]:
    override inline def f: MatchType[T] = inline erasedValue[T] match
      case _: EmptyTuple => "empty" // error
      case _             => 42      // error

object Test2:
  type MatchType[T] = T match
    case EmptyTuple => String
    case _          => Int

  class MyClass[T]:
    def f: MatchType[T] = f2
    inline def f2: MatchType[T] = inline erasedValue[T] match
      case _: EmptyTuple => "empty" // error
      case _             => 42      // error
