final class MBufferLong:
  final def +=(elem: Long): this.type = ???

type M[Tup <: Tuple] <: Tuple = Tup match
  case EmptyTuple => EmptyTuple
  case h *: t     => BufferOf[h] *: M[t]

type M2[T <: Tuple] <: Tuple = (T, M[T]) match
  case (h *: t, a *: b)         => BufferOf[h] *: M2[t]
  case (EmptyTuple, EmptyTuple) => EmptyTuple
  case (_, EmptyTuple)          => EmptyTuple
  case (EmptyTuple, _)          => EmptyTuple

type BufferOf[T] = T match
  case Long              => MBufferLong

inline def append[T](t: T, buffer: BufferOf[T]): BufferOf[T] =
  inline (t, buffer) match
    case (x: Long, y: BufferOf[Long])     => y.+=(x)
  buffer

// Note: the original `transparent inline def appendBuffers[T]: M2[T] = inline match ...`
// pattern is no longer accepted: `inline match` and match types have different
// semantics, and combining them is unsound (see scala/scala3#24760). The
// recursive function would also need an explicit return type, which the new
// rule disallows when the return is a match type. The remaining `append`
// definition above still exercises the original feature of #24038a (BufferOf
// reduction inside an inline match) at the call site.
