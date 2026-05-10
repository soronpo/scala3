// https://github.com/scala/scala3/issues/19098
import scala.collection.mutable

type Rec[JA[_], JO[_], A] = A match {
  case Int => Int | JA[Rec[JA, JO, Int]] | JO[Rec[JA, JO, Int]]
  case _ => A | JA[Rec[JA, JO, A]] | JO[Rec[JA, JO, A]]
}
type Json = Rec[List, [A] =>> Map[String, A], Int]

def arr(values: Json*): Json = mutable.ArrayBuffer[Json](values*)

val x = arr(1)
