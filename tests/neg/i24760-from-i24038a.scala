// https://github.com/scala/scala3/issues/24760
// Originally part of tests/pos/i24038a.scala's `appendBuffers`. The
// `transparent inline def` returning a match type (`M2[T]`) with an
// `inline match` body is rejected after the soundness fix for #24760.

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

transparent inline def appendBuffers[T <: Tuple](t: T, buffers: M[T]): M2[T] = {
  inline (t, buffers) match
    case abcd: ((h *: t), bh *: bt) =>
      val (hh *: tt, bh *: bt) = abcd
      val x: BufferOf[h] = append[h](hh, bh.asInstanceOf[BufferOf[h]])
      x *: appendBuffers[t](tt, bt.asInstanceOf[M[t]])              // error
    case _: (EmptyTuple, EmptyTuple) => EmptyTuple                  // error
    case _: (_, EmptyTuple)          => EmptyTuple                  // error
    case _: (EmptyTuple, _)          => EmptyTuple                  // error
}
