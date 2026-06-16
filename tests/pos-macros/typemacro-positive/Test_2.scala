import poslib.Positive

object Test:
  val a: Positive[5] = 5
  val b: Positive[1] = 1
  val _ = summon[Positive[42] =:= 42]

  // Stuck (abstract argument): typed against the declared upper bound `Int`.
  def f[N <: Int](n: Positive[N]): Int = n
