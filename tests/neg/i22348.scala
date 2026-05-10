// https://github.com/scala/scala3/issues/22348
// Single indirection in the implicit's type used to make `implicitNotFound`
// print the alias's parameter name (`A`) instead of the resolved value
// (`Double`). The fix carries the accumulated (params, args) substitution
// into the alias-body recursion so the inner `IsIntLike[A]` is rendered
// as `IsIntLike[Double]` when computing the message.

type IntLike[T] <: Boolean = T match
  case Int => true

@annotation.implicitNotFound("Comparing ${T} to Int")
type IsIntLike[T] = IntLike[T] =:= true

type Indirection[A] = IsIntLike[A] & (true =:= true)

def f[Z](using Indirection[Z]): Unit = ()

@main def main(): Unit = f[Double] // error // warn
