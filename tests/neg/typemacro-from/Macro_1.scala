import scala.quoted.*
import scala.NamedTuple.{NamedTuple, AnyNamedTuple}

/** `NamedTuple.From` re-implemented as an ordinary library *type macro*, instead
 *  of a hard-coded compiler intrinsic (`TypeEval.fieldsOf`). For a concrete case
 *  class `T`, `From[T]` reduces to `NamedTuple[(labels...), (types...)]`.
 */
object structural:

  type From[T] <: AnyNamedTuple = ${ fromImpl[T] }

  def fromImpl[T: Type](using Quotes): Type[? <: AnyNamedTuple] =
    import quotes.reflect.*
    val arg = TypeRepr.of[T].dealias
    val sym = arg.typeSymbol

    // right-nested pairs: T1 *: T2 *: ... *: EmptyTuple  (cf. compiler `nestedPairs`)
    def tupleOf(ts: List[TypeRepr]): TypeRepr =
      ts.foldRight(TypeRepr.of[EmptyTuple])((t, acc) => TypeRepr.of[*:].appliedTo(List(t, acc)))

    def named(labels: List[String], types: List[TypeRepr]): Type[? <: AnyNamedTuple] =
      val labelTuple = tupleOf(labels.map(l => ConstantType(StringConstant(l))))
      val typeTuple = tupleOf(types)
      TypeRepr.of[NamedTuple].appliedTo(List(labelTuple, typeTuple))
        .asType.asInstanceOf[Type[? <: AnyNamedTuple]]

    if sym.isClassDef && sym.flags.is(Flags.Case) then
      val fields = sym.caseFields
      named(fields.map(_.name), fields.map(f => arg.memberType(f)))
    else
      report.errorAndAbort(s"NamedTuple.From: ${arg.show} is not a concrete case class")
