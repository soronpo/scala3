// https://github.com/scala/scala3/issues/18211
// Originally a pos test. After the legality-reporter fix for scala/scala3#19799
// the match-type case legality is checked at the definition site for every
// illegal case regardless of ordering, so the previously-masked
// `case AnyInt[a] => S[a]` (a type alias to a match type, not a legal
// pattern under SIP-56) is now reported here.

import scala.compiletime.ops.int.*

type AnyInt[A <: Int] <: Int = A match {
  case _ => A
}

type IndexOf[A, T <: Tuple] <: Int = T match {
  case EmptyTuple => -1
  case A *: t     => 0
  case _ *: t =>
    IndexOf[A, t] match {
      case -1        => -1
      case AnyInt[a] => S[a] // error
    }
}
