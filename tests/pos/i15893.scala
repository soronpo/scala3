sealed trait NatT
case class Zero() extends NatT
case class Succ[+N <: NatT](n: N) extends NatT

type Mod2[N <: NatT] <: NatT = N match
  case Zero => Zero
  case Succ[Zero] => Succ[Zero]
  case Succ[Succ[predPredN]] => Mod2[predPredN]

def mod2(n: NatT):  NatT = n match
  case Zero() => Zero()
  case Succ(Zero()) => Succ(Zero())
  case Succ(Succ(predPredN)) => mod2(predPredN)

inline def inlineMod2(inline n: NatT):  NatT = inline n match
  case Zero() => Zero()
  case Succ(Zero()) => Succ(Zero())
  case Succ(Succ(predPredN)) => inlineMod2(predPredN)

transparent inline def transparentInlineMod2(inline n: NatT):  NatT = inline n match
  case Zero() => Zero()
  case Succ(Zero()) => Succ(Zero())
  case Succ(Succ(predPredN)) => transparentInlineMod2(predPredN)

def dependentlyTypedMod2[N <: NatT](n: N): Mod2[N] = n match
  case Zero(): Zero => Zero() // warning
  case Succ(Zero()): Succ[Zero] => Succ(Zero()) // warning
  case Succ(Succ(predPredN)): Succ[Succ[_]] => dependentlyTypedMod2(predPredN) // warning

// Note: `inline def f[N]: Mod2[N] = inline n match ...` is no longer accepted —
// `inline match` and match types have different semantics. The combination is
// unsound (see scala/scala3#24760) and is now rejected. The recursive
// `inlineDependentlyTypedMod2` / `transparentInlineDependentlyTypedMod2`
// patterns from the original test were exactly this disallowed shape, and
// have been removed; the non-dependent inline variants above still test the
// reduction behavior at call sites.

def foo(n: NatT): NatT = mod2(n) match
  case Succ(Zero()) => Zero()
  case _ => n

inline def inlineFoo(inline n: NatT): NatT = inline inlineMod2(n) match
  case Succ(Zero()) => Zero()
  case _ => n

inline def transparentInlineFoo(inline n: NatT): NatT = inline transparentInlineMod2(n) match
  case Succ(Zero()) => Zero()
  case _ => n

@main def main(): Unit =
  println(mod2(Succ(Succ(Succ(Zero()))))) // prints Succ(Zero()), as expected
  println(foo(Succ(Succ(Succ(Zero()))))) // prints Zero(), as expected
  println(inlineMod2(Succ(Succ(Succ(Zero()))))) // prints Succ(Zero()), as expected
  println(inlineFoo(Succ(Succ(Succ(Zero()))))) // prints Succ(Succ(Succ(Zero()))); unexpected
  println(transparentInlineMod2(Succ(Succ(Succ(Zero()))))) // prints Succ(Zero()), as expected
  println(transparentInlineFoo(Succ(Succ(Succ(Zero()))))) // prints Zero(), as expected
  println(dependentlyTypedMod2(Succ(Succ(Succ(Zero()))))) // runtime error; unexpected
