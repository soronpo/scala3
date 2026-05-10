// https://github.com/scala/scala3/issues/19799
// Illegal match-type cases must be reported at the definition site,
// regardless of case ordering. Previously the legality error was only
// emitted for the first illegal case actually visited by the reducer,
// so a preceding legal-but-stuck case would mask later illegal ones.

type IllegalMatch1[T] = T match {
  case Int => java.lang.Integer
  case a | Null => a // error
  case _ => T
}

type IllegalMatch2[T] = T match {
  case a | Null => a // error
  case _ => T
}
