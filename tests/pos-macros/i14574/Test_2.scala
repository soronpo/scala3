trait CompilationTest:
  case class A(s: A.S):
    MinimalExample.test(s)

  object A:
    case class S(bar: Int)

  val a = A(A.S(42))

object CompilationTestImpl extends CompilationTest
