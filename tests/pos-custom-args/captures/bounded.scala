class CC
type Cap = CC^

def test(c: Cap) =
  class B[X <: Object^{c}](x: X):
    def elem = x
    def lateElem = () => x

  def f(x: Int): Int = if c == c then x else 0
  val b = new B(f)
  val r1 = b.elem
  val r1c: Int ->{c} Int = r1
  val r2 = b.lateElem
  val r2c: () ->{c} Int ->{c} Int = r2