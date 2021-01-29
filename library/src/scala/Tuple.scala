package scala
import annotation.showAsInfix
import compiletime._

/** Tuple of arbitrary arity */
sealed trait Tuple extends Product {
  import Tuple._

  /** Create a copy this tuple as an Array */
  inline def toArray: Array[Object] =
    runtime.Tuples.toArray(this)

  /** Create a copy this tuple as a List */
  inline def toList: List[Union[this.type]] =
    this.productIterator.toList
      .asInstanceOf[List[Union[this.type]]]

  /** Create a copy this tuple as an IArray */
  inline def toIArray: IArray[Object] =
    runtime.Tuples.toIArray(this)

  /** Return a new tuple by prepending the element to `this` tuple.
   *  This operation is O(this.size)
   */
  inline def *: [H, This >: this.type <: Tuple] (x: H): H *: This =
    runtime.Tuples.cons(x, this).asInstanceOf[H *: This]

  /** Return a new tuple by concatenating `this` tuple with `that` tuple.
   *  This operation is O(this.size + that.size)
   */
  inline def ++ [This >: this.type <: Tuple](that: Tuple): Concat[This, that.type] =
    runtime.Tuples.concat(this, that).asInstanceOf[Concat[This, that.type]]

  /** Return the size (or arity) of the tuple */
  inline def size[This >: this.type <: Tuple]: Size[This] =
    runtime.Tuples.size(this).asInstanceOf[Size[This]]

  /** Given two tuples, `(a1, ..., an)` and `(a1, ..., an)`, returns a tuple
   *  `((a1, b1), ..., (an, bn))`. If the two tuples have different sizes,
   *  the extra elements of the larger tuple will be disregarded.
   *  The result is typed as `((A1, B1), ..., (An, Bn))` if at least one of the
   *  tuple types has a `EmptyTuple` tail. Otherwise the result type is
   *  `(A1, B1) *: ... *: (Ai, Bi) *: Tuple`
   */
  inline def zip[This >: this.type <: Tuple, T2 <: Tuple](t2: T2): Zip[This, T2] =
    runtime.Tuples.zip(this, t2).asInstanceOf[Zip[This, T2]]

  /** Called on a tuple `(a1, ..., an)`, returns a new tuple `(f(a1), ..., f(an))`.
   *  The result is typed as `(F[A1], ..., F[An])` if the tuple type is fully known.
   *  If the tuple is of the form `a1 *: ... *: Tuple` (that is, the tail is not known
   *  to be the cons type.
   */
  inline def map[F[_]](f: [t] => t => F[t]): Map[this.type, F] =
    runtime.Tuples.map(this, f).asInstanceOf[Map[this.type, F]]

  /** Given a tuple `(a1, ..., am)`, returns the tuple `(a1, ..., an)` consisting
   *  of its first n elements.
   */
  inline def take[This >: this.type <: Tuple](n: Int): Take[This, n.type] =
    runtime.Tuples.take(this, n).asInstanceOf[Take[This, n.type]]


  /** Given a tuple `(a1, ..., am)`, returns the tuple `(an+1, ..., am)` consisting
   *  all its elements except the first n ones.
   */
  inline def drop[This >: this.type <: Tuple](n: Int): Drop[This, n.type] =
    runtime.Tuples.drop(this, n).asInstanceOf[Drop[This, n.type]]

  /** Given a tuple `(a1, ..., am)`, returns a pair of the tuple `(a1, ..., an)`
   *  consisting of the first n elements, and the tuple `(an+1, ..., am)` consisting
   *  of the remaining elements.
   */
  // FIXME remove transparent
  //       failure in bench-run/src/main/scala/dotty/tools/benchmarks/tuples/TupleOps.scala
  transparent inline def splitAt[This >: this.type <: Tuple](n: Int): Split[This, n.type] =
    runtime.Tuples.splitAt(this, n).asInstanceOf[Split[This, n.type]]
}

object Tuple {

  /** Type of the head of a tuple */
  type Head[X <: NonEmptyTuple] = X match {
    case x *: _ => x
  }

  /** Type of the tail of a tuple */
  type Tail[X <: NonEmptyTuple] <: Tuple = X match {
    case _ *: xs => xs
  }

  /** Type of the concatenation of two tuples */
  type Concat[X <: Tuple, +Y <: Tuple] <: Tuple = X match {
    case EmptyTuple => Y
    case x1 *: xs1 => x1 *: Concat[xs1, Y]
  }

  /** Type of the element a position N in the tuple X */
  type Elem[X <: Tuple, N <: Int] = X match {
    case x *: xs =>
      N match {
        case 0 => x
        case S[n1] => Elem[xs, n1]
      }
  }

  /** Literal constant Int size of a tuple */
  type Size[X <: Tuple] <: Int = X match {
    case EmptyTuple => 0
    case x *: xs => S[Size[xs]]
  }

  /** Fold a tuple `(T1, ..., Tn)` into `F[T1, F[... F[Tn, Z]...]]]` */
  type Fold[T <: Tuple, Z, F[_, _]] = T match
    case EmptyTuple => Z
    case h *: t => F[h, Fold[t, Z, F]]

  /** Converts a tuple `(T1, ..., Tn)` to `(F[T1], ..., F[Tn])` */
  type Map[Tup <: Tuple, F[_]] <: Tuple = Tup match {
    case EmptyTuple => EmptyTuple
    case h *: t => F[h] *: Map[t, F]
  }

  /** Converts a tuple `(T1, ..., Tn)` to a flattened `(..F[T1], ..., ..F[Tn])` */
  type FlatMap[Tup <: Tuple, F[_] <: Tuple] <: Tuple = Tup match {
    case EmptyTuple => EmptyTuple
    case h *: t => Concat[F[h], FlatMap[t, F]]
  }

  /** Filters out those members of the tuple for which the predicate `P` returns `false`.
   *  A predicate `P[X]` is a type that can be either `true` or `false`. For example:
   *  ```
   *  type IsString[x] = x match {
   *    case String => true
   *    case _ => false
   *  }
   *  Filter[(1, "foo", 2, "bar"), IsString] =:= ("foo", "bar")
   * ```
   */
  type Filter[Tup <: Tuple, P[_] <: Boolean] <: Tuple = Tup match {
    case EmptyTuple => EmptyTuple
    case h *: t => P[h] match {
      case true => h *: Filter[t, P]
      case false => Filter[t, P]
    }
  }

  /** Given two tuples, `A1 *: ... *: An * At` and `B1 *: ... *: Bn *: Bt`
   *  where at least one of `At` or `Bt` is `EmptyTuple` or `Tuple`,
   *  returns the tuple type `(A1, B1) *: ... *: (An, Bn) *: Ct`
   *  where `Ct` is `EmptyTuple` if `At` or `Bt` is `EmptyTuple`, otherwise `Ct` is `Tuple`.
   */
  type Zip[T1 <: Tuple, T2 <: Tuple] <: Tuple = (T1, T2) match {
    case (h1 *: t1, h2 *: t2) => (h1, h2) *: Zip[t1, t2]
    case (EmptyTuple, _) => EmptyTuple
    case (_, EmptyTuple) => EmptyTuple
    case _ => Tuple
  }

  /** Converts a tuple `(F[T1], ..., F[Tn])` to `(T1,  ... Tn)` */
  type InverseMap[X <: Tuple, F[_]] <: Tuple = X match {
    case F[x] *: t => x *: InverseMap[t, F]
    case EmptyTuple => EmptyTuple
  }

  /** Implicit evidence. IsMappedBy[F][X] is present in the implicit scope iff
   *  X is a tuple for which each element's type is constructed via `F`. E.g.
   *  (F[A1], ..., F[An]), but not `(F[A1], B2, ..., F[An])` where B2 does not
   *  have the shape of `F[A]`.
   */
  type IsMappedBy[F[_]] = [X <: Tuple] =>> X =:= Map[InverseMap[X, F], F]

  /** Transforms a tuple `(T1, ..., Tn)` into `(T1, ..., Ti)`. */
  type Take[T <: Tuple, N <: Int] <: Tuple = N match {
    case 0 => EmptyTuple
    case S[n1] => T match {
      case EmptyTuple => EmptyTuple
      case x *: xs => x *: Take[xs, n1]
    }
  }

  /** Transforms a tuple `(T1, ..., Tn)` into `(Ti+1, ..., Tn)`. */
  type Drop[T <: Tuple, N <: Int] <: Tuple = N match {
    case 0 => T
    case S[n1] => T match {
      case EmptyTuple => EmptyTuple
      case x *: xs => Drop[xs, n1]
    }
  }

  /** Splits a tuple (T1, ..., Tn) into a pair of two tuples `(T1, ..., Ti)` and
   * `(Ti+1, ..., Tn)`.
   */
  type Split[T <: Tuple, N <: Int] = (Take[T, N], Drop[T, N])

  /** Given a tuple `(T1, ..., Tn)`, returns a union of its
   *  member types: `T1 | ... | Tn`. Returns `Nothing` if the tuple is empty.
   */
  type Union[T <: Tuple] = Fold[T, Nothing, [x, y] =>> x | y]

  /** Empty tuple */
  def apply(): EmptyTuple = EmptyTuple

  /** Tuple with one element */
  def apply[T](x: T): T *: EmptyTuple = Tuple1(x)

  /** Matches an empty tuple. */
  def unapply(x: EmptyTuple): true = true

  /** Convert an array into a tuple of unknown arity and types */
  def fromArray[T](xs: Array[T]): Tuple = {
    val xs2 = xs match {
      case xs: Array[Object] => xs
      case xs => xs.map(_.asInstanceOf[Object])
    }
    runtime.Tuples.fromArray(xs2).asInstanceOf[Tuple]
  }

  /** Convert an immutable array into a tuple of unknown arity and types */
  def fromIArray[T](xs: IArray[T]): Tuple = {
    val xs2: IArray[Object] = xs match {
      case xs: IArray[Object] @unchecked => xs
      case xs =>
        // TODO support IArray.map
        xs.asInstanceOf[Array[T]].map(_.asInstanceOf[Object]).asInstanceOf[IArray[Object]]
    }
    runtime.Tuples.fromIArray(xs2).asInstanceOf[Tuple]
  }

  /** Convert a Product into a tuple of unknown arity and types */
  def fromProduct(product: Product): Tuple =
    runtime.Tuples.fromProduct(product)

  def fromProductTyped[P <: Product](p: P)(using m: scala.deriving.Mirror.ProductOf[P]): m.MirroredElemTypes =
    runtime.Tuples.fromProduct(p).asInstanceOf[m.MirroredElemTypes]
}

/** A tuple of 0 elements */
type EmptyTuple = EmptyTuple.type

/** A tuple of 0 elements. */
object EmptyTuple extends Tuple {
  override def productArity: Int = 0

  @throws(classOf[IndexOutOfBoundsException])
  override def productElement(n: Int): Any =
    throw new IndexOutOfBoundsException(n.toString())

  def canEqual(that: Any): Boolean = this == that

  override def toString(): String = "()"
}

/** Tuple of arbitrary non-zero arity */
sealed trait NonEmptyTuple extends Tuple {
  import Tuple._

  /** Get the i-th element of this tuple.
   *  Equivalent to productElement but with a precise return type.
   */
  // FIXME remove transparent
  transparent inline def apply[This >: this.type <: NonEmptyTuple](n: Int): Elem[This, n.type] =
    runtime.Tuples.apply(this, n).asInstanceOf[Elem[This, n.type]]

  /** Get the head of this tuple */
  // FIXME remove transparent
  transparent inline def head[This >: this.type <: NonEmptyTuple]: Head[This] =
    runtime.Tuples.apply(this, 0).asInstanceOf[Head[This]]

  /** Get the tail of this tuple.
   *  This operation is O(this.size)
   */
  // FIXME remove transparent
  //       fails in tests/run/tuples1a.scala
  //       https://github.com/lampepfl/dotty/issues/11236
  transparent inline def tail[This >: this.type <: NonEmptyTuple]: Tail[This] =
    runtime.Tuples.tail(this).asInstanceOf[Tail[This]]

}

@showAsInfix
sealed abstract class *:[+H, +T <: Tuple] extends NonEmptyTuple

object *: {
  def unapply[H, T <: Tuple](x: H *: T): (H, T) = (x.head, x.tail)
}
