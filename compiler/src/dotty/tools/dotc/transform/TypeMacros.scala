package dotty.tools.dotc
package transform

import core.*
import Contexts.*, Symbols.*, Types.*, Decorators.*, Flags.*
import StdNames.nme
import ast.tpd.*
import util.SrcPos
import config.Printers.typr
import reporting.trace

/** Reduction of *type macros*.
 *
 *  A type macro is written with the bound-licensed splice syntax
 *
 *  ```scala
 *  type M[X1, ..., Xn] <: U = ${ impl[X1, ..., Xn] }
 *  ```
 *
 *  which the parser lowers to an abstract, upper-bounded type member
 *  `M[X1, ..., Xn] <: U` carrying `@scala.annotation.internal.TypeMacro("impl")`.
 *  The implementation is a metaprogram in the type's enclosing scope of shape:
 *
 *  ```scala
 *  def impl[X1 : Type, ..., Xn : Type](using Quotes): Type[? <: U] = ...
 *  ```
 *
 *  Reduction is lazy: `M[A1, ..., An]` only reduces once every argument is
 *  *concrete* (in the sense of `MatchTypes.isConcrete`). At that point the
 *  compiler synthesises the closure
 *
 *  ```scala
 *  (q: Quotes) => M[A1, ..., An](using Type.of[A1](using q), ...)(using q)
 *  ```
 *
 *  interprets it through the macro runtime (`Splicer.spliceType`), and uses the
 *  returned `scala.quoted.Type[R]` as the reduction `R`.
 *
 *  This is a prototype implementation of the "Type Macros" SIP. It deliberately
 *  reuses the same machinery that drives term macros and the `NamedTuple.From`
 *  intrinsic, demonstrating that an operation like `NamedTuple.From` need not be
 *  hard-coded into the compiler.
 */
object TypeMacros:

  /** Symbols whose reduction is currently in progress, to guard against
   *  reentrant (e.g. self-recursive) type-macro reductions.
   */
  private val reducing = new java.util.concurrent.ConcurrentHashMap[Symbol, java.lang.Boolean]()

  /** Try to reduce the type-macro application `tp`. Returns `NoType` when the
   *  application is stuck (some argument is not yet concrete) or when reduction
   *  fails, in which case the application keeps its declared upper bound.
   */
  def reduce(tp: AppliedType)(using Context): Type = tp.tycon match
    case tycon: TypeRef if defn.isTypeMacro(tycon.symbol) =>
      val tmSym = tycon.symbol
      // Lazy gate: only reduce once all arguments are concrete.
      if !tp.args.forall(arg => MatchTypes.isConcrete(arg)) then NoType
      else if reducing.containsKey(tmSym) then NoType
      else
        reducing.put(tmSym, java.lang.Boolean.TRUE)
        try reduceConcrete(tp, tmSym)
        catch
          case ex: CompilationUnit.SuspendException => throw ex
          case _: Throwable => NoType
        finally reducing.remove(tmSym)
    case _ => NoType

  private def reduceConcrete(tp: AppliedType, tmSym: Symbol)(using Context): Type =
    trace(i"reduce type macro $tp", typr, show = true) {
      // The `@internal.TypeMacro("impl")` annotation (synthesised from the
      // `type M[X] <: U = ${ impl[X] }` syntax) names the implementation method,
      // looked up in the type's enclosing scope by type-parameter arity.
      val implName = tmSym.getAnnotation(defn.TypeMacroAnnot).flatMap(_.argumentConstantString(0))
      implName match
        case Some(name) if name.nonEmpty =>
          val implAlts = tmSym.owner.info.member(name.toTermName).alternatives.filter { d =>
            d.symbol.is(Method) && (d.info match
              case pt: PolyType => pt.paramNames.length == tp.args.length
              case _ => tp.args.isEmpty)
          }
          implAlts match
            case d :: Nil =>
              val closure = buildClosure(d.symbol, tp.args)
              // Report macro errors at the use site (the enclosing tree being
              // typed) when available, falling back to the macro's definition.
              val pos: SrcPos =
                val t = ctx.tree
                if t != null && !t.isEmpty && t.span.exists then t.srcPos else tmSym.srcPos
              val inlinedFrom = TypeTree(tp).withSpan(pos.span)
              val tpt = inContext(quoted.MacroExpansion.context(inlinedFrom)) {
                Splicer.spliceType(closure, pos, pos, MacroClassLoader.fromContext)
              }
              val res = tpt.tpe
              if res.exists && !res.isError then res else NoType
            case _ =>
              report.error(em"Could not find a unique type-macro implementation method `$name` for $tp", tmSym.srcPos)
              NoType
        case _ => NoType
    }

  /** Build the interpretable closure `(q: Quotes) => impl[args](using Type.of[args](using q), ...)(using q)`. */
  private def buildClosure(implSym: Symbol, args: List[Type])(using Context): Tree =
    val quotesType = defn.QuotesClass.typeRef
    val resType = defn.QuotedTypeClass.typeRef.appliedTo(WildcardType)
    val mt = MethodType(nme.x_0 :: Nil)(_ => quotesType :: Nil, _ => resType)
    Lambda(mt, params => {
      val qRef = params.head
      def applyClauses(pre: Tree, info: Type): Tree = info match
        case info: PolyType =>
          applyClauses(pre.appliedToTypes(args), info.instantiate(args))
        case info: MethodType =>
          val termArgs = info.paramInfos.map { pinfo =>
            pinfo.dealias match
              case AppliedType(tc, targ :: Nil) if tc.typeSymbol == defn.QuotedTypeClass =>
                // a `Type[X]` evidence -> `Type.of[X](using q)`
                ref(defn.QuotedTypeModule_of).appliedToTypes(targ :: Nil).appliedTo(qRef)
              case pinfo1 if pinfo1.derivesFrom(defn.QuotesClass) =>
                qRef
              case pinfo1 =>
                throw new MatchError(s"unsupported type-macro implementation parameter: ${pinfo1.show}")
          }
          applyClauses(pre.appliedToTermArgs(termArgs), info.resType)
        case _ =>
          pre
      val body = applyClauses(ref(implSym), implSym.info)
      Typed(body, TypeTree(resType))
    })
end TypeMacros
