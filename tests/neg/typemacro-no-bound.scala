import scala.quoted.*

object bad:
  // A type-macro splice requires an explicit upper bound; without one it is rejected.
  type M[X <: Int] = ${ mImpl[X] } // error

  def mImpl[X <: Int : Type](using Quotes): Type[? <: Int] = ???
