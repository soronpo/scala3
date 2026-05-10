// https://github.com/scala/scala3/issues/13614
// Regression check: a refinement containing a match-type alias should
// pickle and unpickle to an equal type. Previously the pre-pickle
// printed `Any{Out = String}` (with the match type reduced) and the
// post-pickle printed `Any{Out = M[Int]}` (unreduced), causing a TASTy
// round-trip mismatch under `-Ytest-pickler`.

object Test {
  type M[X] = X match { case Int => String }
  def a: Any { type Out = M[Int] } = a
}
