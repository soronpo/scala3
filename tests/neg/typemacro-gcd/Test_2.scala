import gcdlib.Gcd

object Test:
  // `Gcd[12, 18]` reduces to `6`, so assigning it `5` is a type mismatch.
  val x: Gcd[12, 18] = 5 // error
