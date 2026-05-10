// https://github.com/scala/scala3/issues/24760
// Originally part of tests/pos/i15893.scala. The `inline def`/`transparent
// inline def` variants returning a match type with an `inline match` body are
// rejected after the soundness fix for #24760: `inline match` and match types
// have different semantics, and combining them is unsound.

sealed trait NatT
case class Zero() extends NatT
case class Succ[+N <: NatT](n: N) extends NatT

type Mod2[N <: NatT] <: NatT = N match
  case Zero => Zero
  case Succ[Zero] => Succ[Zero]
  case Succ[Succ[predPredN]] => Mod2[predPredN]

inline def inlineDependentlyTypedMod2[N <: NatT](inline n: N): Mod2[N] = inline n match
  case Zero(): Zero => Zero()                                                          // error
  case Succ(Zero()): Succ[Zero] => Succ(Zero())                                        // error
  case Succ(Succ(predPredN)): Succ[Succ[_]] => inlineDependentlyTypedMod2(predPredN)   // error

transparent inline def transparentInlineDependentlyTypedMod2[N <: NatT](inline n: N): Mod2[N] = inline n match
  case Zero(): Zero => Zero()                                                                              // error
  case Succ(Zero()): Succ[Zero] => Succ(Zero())                                                            // error
  case Succ(Succ(predPredN)): Succ[Succ[_]] => transparentInlineDependentlyTypedMod2(predPredN)            // error
