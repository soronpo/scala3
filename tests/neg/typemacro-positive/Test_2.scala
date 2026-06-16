import poslib.Positive

object Test:
  val a: Positive[-3] = ??? // error
  val b: Positive[0] = ???  // error
