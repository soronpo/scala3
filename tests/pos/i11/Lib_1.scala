// https://github.com/soronpo/scala3/issues/11
package mh

import scala.compiletime.*
import scala.deriving.*

trait Hammer[I, O] { def hammer(input: I): O }

object Hammer {
  inline def summonFirst[Ts <: Tuple, O]: (Hammer[?, O], Int) =
    inline erasedValue[Ts] match {
      case _: (t *: rest) => summonInline[Hammer[t, O]] -> 0
      case _              => error("not found")
    }

  inline def makeOne[S, O](using m: Mirror.ProductOf[S]): Hammer[S, O] =
    new Hammer[S, O] {
      lazy val (h, idx) = summonFirst[m.MirroredElemTypes, O]
      def hammer(source: S): O = h.hammer(source.asInstanceOf)
    }

  inline def makeAll[S: Mirror.ProductOf, Os <: Tuple]: Unit =
    inline erasedValue[Os] match {
      case _: (o *: rest) => makeOne[S, o]; makeAll[S, rest]
      case _: EmptyTuple  => ()
    }

  inline def makeProductHammer[S, O](using
    ms: Mirror.ProductOf[S], mo: Mirror.ProductOf[O]
  ): Hammer[S, O] = {
    makeAll[S, mo.MirroredElemTypes]
    new Hammer[S, O] { def hammer(input: S): O = ??? }
  }
}

given identity[I]: Hammer[I, I] with { def hammer(i: I): I = i }
inline given derived[I: Mirror.ProductOf, O: Mirror.ProductOf]: Hammer[I, O] =
  Hammer.makeProductHammer
