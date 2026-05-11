// https://github.com/soronpo/scala3/issues/11
package usage

import mh.{*, given}

final case class A(x: Int)
final case class B(x: Int)
final case class C(a: A)
final case class D(b: B)

object Test {
  val r: Hammer[C, D] = summon
}
