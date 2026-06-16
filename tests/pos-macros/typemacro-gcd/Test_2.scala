import gcdlib.*

object Test:
  // `Gcd[A, B]` reduces to a concrete singleton once A and B are concrete.
  val a: Gcd[12, 18] = 6
  val b: Gcd[100, 8] = 4
  val c: Gcd[17, 5] = 1

  val _ = summon[Gcd[12, 18] =:= 6]
  val _ = summon[Gcd[100, 8] =:= 4]

  // Composes with `compiletime.ops`: lcm(a, b) = a * b / gcd(a, b)
  import scala.compiletime.ops.int.*
  val lcm: 4 * 6 / Gcd[4, 6] = 12
