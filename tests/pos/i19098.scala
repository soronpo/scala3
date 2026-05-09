// https://github.com/scala/scala3/issues/19098
type Rec[F[_], A] = A match
  case Int => Int | F[Rec[F, Int]]
  case _ => A | F[Rec[F, A]]

type Json = Rec[List, Int]

def arr(values: Json*): Json = ???

val x: Json = arr(1)
