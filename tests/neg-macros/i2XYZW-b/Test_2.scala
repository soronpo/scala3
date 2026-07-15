package repro
import Mac.*
object Rewrites:
  inline def innerRewrite(inline v: V): V = check(v)      // internal: not the use site
  inline def outerRewrite(inline v: V): V = innerRewrite(v) // internal: not the use site
object Test:
  val y = Rewrites.outerRewrite(V(0))    // error   <-- reported here, hiding the rewrite internals
