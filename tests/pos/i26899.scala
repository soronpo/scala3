// https://github.com/scala/scala3/issues/26899
// Used to crash in the backend with
//   assertion failed: missing outer accessor in package <root>
// because the lambda in `M` is lifted into `Tr` as a static method while
// still referring to `Tr.this` through the prefix of the summoned given.
package issue26899

trait Codec[A]

object O:
  object T:
    class V
    object V:
      given Codec[V] = new Codec[V] {}

trait Tr:
  export O.T
  object M:
    val f: () => Codec[T.V] = () => summon[Codec[T.V]]
