// https://github.com/scala/scala3/issues/26310
class A
class B extends A:
  def m: Int = 1
transparent inline def choose: A = new B
object Test:
  inline def check =
    val x: Int = choose.m // error
