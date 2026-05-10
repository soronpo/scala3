class Inv[T]
class Cov[+T]
class Contra[-T]

// Nesting captures in non-covariant position

type InvNesting[X] = X match
  case Inv[Cov[t]] => t // error

type ContraNesting[X] = X match
  case Contra[Cov[t]] => t // error

// Intersection type to type-test and capture at the same time

type AndTypeMT[X] = X match
  case t & Seq[Any] => t // error

// Poly type alias with a bound to type-test and capture at the same time

type IsSeq[X <: Seq[Any]] = X

type TypeAliasWithBoundMT[X] = X match
  case IsSeq[t] => t // error

// Poly type alias with an unknown type member refinement

type TypeMemberAux[X] = { type TypeMember = X }

type TypeMemberExtractorMT[X] = X match
  case TypeMemberAux[t] => t // error

// Poly type alias with a refined member of stronger bounds than in the parent

class Base {
  type TypeMember
}

type TypeMemberAux2[X <: Seq[Any]] = Base { type TypeMember = X }

type TypeMemberExtractorMT2[X] = X match
  case TypeMemberAux2[t] => t // error
