// https://github.com/scala/scala3/issues/26752
package lib

import scala.language.implicitConversions

final class Wrap:
  def wrapped: Int = 1

object Wrap:
  transparent inline implicit def conv(x: Int): Wrap = new Wrap

class Test:
  def f: Unit =
    val x: Int = 1
    x.toStrig // error   // `conv` cannot provide `toStrig`, so no import is suggested
    x.wrapped // error   // `conv` can provide `wrapped`, so importing it is suggested
