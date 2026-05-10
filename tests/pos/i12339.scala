// https://github.com/scala/scala3/issues/12339
import Tuple.Concat

trait Zipper[-Left, -Right, +Result] {
  def zip(l: Left, r: Right): Result
}

object Zipper {
  given tuples[T1 <: Tuple, T2 <: Tuple]: Zipper[T1, T2, Concat[T1, T2]] with
    def zip(a: T1, b: T2): Concat[T1, T2] = a ++ b

  def zip[A, B, Res](a: A, b: B)(using z: Zipper[A, B, Res]) = z.zip(a, b)

  val iiZii: (Int, Int, Int, Int) = zip((1,1), (1,1))
}
