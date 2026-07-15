package repro
import Macros.*
object Test:
  val v = V(1)
  val a = v + V(0)          // error
  val b = v + (v + V(0))    // error
