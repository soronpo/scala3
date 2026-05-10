// https://github.com/scala/scala3/issues/13250
import scala.deriving.Mirror

class MyGeneric[A, Repr]

type TransformTuple[T <: Tuple] <: Tuple = T match {
  case x *: xs => x *: TransformTuple[xs]
  case EmptyTuple => EmptyTuple
}

object NotInline {
  given materializeProduct[T <: Product](
    using m: Mirror.ProductOf[T]
  ): MyGeneric[T, TransformTuple[m.MirroredElemTypes]] =
    new MyGeneric[T, TransformTuple[m.MirroredElemTypes]]
}

object WithInline {
  inline given materializeProduct[T <: Product](
    using m: Mirror.ProductOf[T]
  ): MyGeneric[T, TransformTuple[m.MirroredElemTypes]] =
    new MyGeneric[T, TransformTuple[m.MirroredElemTypes]]
}

@main def testGen = {
  val t = (23, "foo", true)
  type T = (Int, String, Boolean)
  val g1 = NotInline.materializeProduct[T]
  val g2 = WithInline.materializeProduct[T]

  takesGen(t)(using g1)
  takesGen(t)(using NotInline.materializeProduct[T])

  val dummy1 = {
    import NotInline.materializeProduct
    takesGen(t)
  }

  val dummy2 = {
    import WithInline.materializeProduct
    takesGen(t)
  }
}

def takesGen[A, Repr](a: A)(using MyGeneric[A, Repr]): String = "Foo"
