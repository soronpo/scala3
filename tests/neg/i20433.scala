// https://github.com/scala/scala3/issues/20433
// Illegal match-type case must be reported even when a preceding legal
// case is present. Without the fix, the legal first case prevented the
// reducer from ever visiting the illegal second case at definition site.

trait TList[+T]
object Empty extends TList[Nothing]
class Cons[+E, +H <: E, +T <: TList[E]] extends TList[E]

type R[+T] = (Long, T)

type At[V, L <: TList[R[V]], K <: Long] <: Option[?] = L match
  case Empty.type => None.type
  case Cons[R[V], (k, v), tail] => None.type // error
