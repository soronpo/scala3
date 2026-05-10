// https://github.com/scala/scala3/issues/22943
// Improve the error for "unaccounted type parameter" so the user is told
// why the capture is unaccounted-for: SIP-56 only allows captures to be
// nested under covariant type constructors, otherwise they degenerate
// into a type-test that doesn't bind.

sealed trait KV[K, V]
sealed trait Append[Init, KV]

type KeysOf[KVs] = KVs match
  case KV[k, v] => k
  case Append[init, KV[k, v]] => KeysOf[init] | k

object Test:
  // OK
  summon[KeysOf[KV["x", Int]] =:= "x"]

  // triggers the legacy-pattern error on the second case, which now mentions
  // the covariance/capture rule
  summon[KeysOf[Append[KV["x", Int], KV["y", String]]] =:= ("x" | "y")] // error
