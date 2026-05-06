package sip80

import scala.quoted.*
import scala.tasty.inspector.{Inspector, TastyInspector, Tasty}

import java.nio.file.{Files, Path, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** A SIP-80 incident: a position where SIP-80's `.X` shorthand would
  * resolve to the same expression the user already wrote (or to a bare-X
  * resolved through a wildcard import).
  */
final case class Finding(
    project: String,
    file: String,
    line: Int,
    col: Int,
    sourceText: String,
    member: String,
    expectedType: String,
    charsSaved: Int,
    importBased: Boolean,
    category: String,
)

object Finding:
  val header: String =
    "project\tfile\tline\tcol\tcategory\texpected\tmember\tchars_saved\timport_based\tsource"

  def tsv(f: Finding): String =
    s"${f.project}\t${f.file}\t${f.line}\t${f.col}\t${f.category}\t" +
      s"${f.expectedType}\t${f.member}\t${f.charsSaved}\t${f.importBased}\t" +
      f.sourceText.replace("\t", " ").replace("\n", " ")


/** TastyInspector that walks each compilation unit's tree and reports
  * positions where SIP-80 would fire.
  */
class Sip80Inspector(project: String, sourceRoots: List[Path]) extends Inspector:

  private val acc = mutable.ArrayBuffer.empty[Finding]
  def findings: List[Finding] = acc.toList

  private val sourceCache = mutable.Map.empty[String, Array[String]]

  // Index of basename → absolute path under each source root, populated
  // lazily on first miss. Used as a fallback when TASTy's relative path
  // includes scalac's project-relative prefix (e.g. ``os/src/ZipOps.scala``)
  // but the published source jar flattens that out.
  private val basenameIndex = mutable.Map.empty[String, Path]
  private var basenameIndexBuilt = false

  private def buildBasenameIndex(): Unit =
    if !basenameIndexBuilt then
      basenameIndexBuilt = true
      for root <- sourceRoots if Files.isDirectory(root) do
        Files.walk(root).forEach { p =>
          if Files.isRegularFile(p) && p.toString.endsWith(".scala") then
            val name = p.getFileName.toString
            // First entry wins; ties are rare in practice and we accept
            // any of them.
            if !basenameIndex.contains(name) then basenameIndex(name) = p
        }

  private def lookupSource(rawPath: String): Option[Array[String]] =
    sourceCache.get(rawPath).orElse {
      // The path stored in TASTy positions is relative to scalac's working
      // directory at compile time (e.g. ``fixtures/Patterns.scala`` or
      // ``os/src/ZipOps.scala``). Try the path as-is, then resolved
      // through each configured source root, then a basename fallback.
      val direct: List[Path] =
        Paths.get(rawPath) :: sourceRoots.map(_.resolve(rawPath))
      val byDirect = direct.find(Files.isRegularFile(_))
      val byBasename = byDirect.orElse {
        buildBasenameIndex()
        val basename = Paths.get(rawPath).getFileName.toString
        basenameIndex.get(basename)
      }
      byBasename.map { p =>
        val lines = Files.readAllLines(p, java.nio.charset.StandardCharsets.UTF_8)
          .asScala.toArray
        sourceCache(rawPath) = lines
        lines
      }
    }

  private def relativise(absPath: String): String =
    sourceRoots
      .map(_.toAbsolutePath.toString)
      .find(r => absPath.startsWith(r))
      .map(r => absPath.stripPrefix(r).stripPrefix("/").stripPrefix("\\"))
      .getOrElse(absPath)

  private object Cat:
    val TypedDecl     = "typed_decl"
    val DefaultArg    = "default_arg"
    val MatchCase     = "match_case"
    val NestedPattern = "nested_pattern"
    val CallArg       = "call_arg"
    val Ascription    = "ascription"
    val IfBranch      = "if_branch"
    val Other         = "other"

  override def inspect(using q: Quotes)(tastys: List[Tasty[q.type]]): Unit =
    import q.reflect.*

    val nullSym = TypeRepr.of[Null].typeSymbol

    def principalClass(tp: TypeRepr): Option[Symbol] =
      val widened = tp.widen.dealias
      widened match
        case OrType(l, r) =>
          val lIsNull = l.typeSymbol == nullSym
          val rIsNull = r.typeSymbol == nullSym
          if lIsNull && rIsNull then None
          else if lIsNull then principalClass(r)
          else if rIsNull then principalClass(l)
          else None
        case AndType(l, r) =>
          principalClass(l).orElse(principalClass(r))
        case Refinement(parent, _, _) =>
          principalClass(parent)
        case AppliedType(base, _) =>
          principalClass(base)
        case t =>
          val cs = t.classSymbol
          if cs.isDefined then cs
          else
            val ts = t.typeSymbol
            if ts.exists && ts.isClassDef then Some(ts) else None

    /** Member-of-companion check that follows SIP-80's "underlying expected
      * type" reduction: when type inference has bound a polymorphic
      * parameter to a singleton or specific subclass (e.g. a case object's
      * own class), the firing position's *prototype* expected type was the
      * containing sealed parent. Walk the principal class's base classes
      * and accept the member if any of them has the right companion.
      */
    def memberOfCompanion(sym: Symbol, principal: Symbol): Boolean =
      if !sym.exists || !principal.exists then false
      else
        val owner = sym.maybeOwner
        if !owner.exists then false
        else
          // Candidate companions: principal's own companion, plus those of
          // its base classes (excluding java.lang.Object / scala.Any).
          val skip = Set(
            defn.AnyClass, defn.AnyRefClass, defn.AnyValClass,
            defn.ObjectClass, defn.MatchableClass,
          )
          val candidates =
            try principal.typeRef.baseClasses.filterNot(skip.contains)
            catch case _: Throwable => List(principal)
          candidates.exists { c =>
            val mod = c.companionModule
            mod.exists && (owner == mod || owner == mod.moduleClass)
          }

    def textAt(pos: Position): String =
      val absPath = pos.sourceFile.path
      lookupSource(absPath).fold("") { lines =>
        val sLine = pos.startLine
        val eLine = pos.endLine
        val sCol = pos.startColumn
        val eCol = pos.endColumn
        if sLine == eLine then
          val L = if sLine < lines.length then lines(sLine) else ""
          val s = math.min(sCol, L.length)
          val e = math.min(eCol, L.length)
          if e > s then L.substring(s, e) else ""
        else
          val sb = new StringBuilder
          val first = if sLine < lines.length then lines(sLine) else ""
          sb.append(first.substring(math.min(sCol, first.length)))
          var i = sLine + 1
          while i < eLine && i < lines.length do
            sb.append('\n').append(lines(i))
            i += 1
          if eLine < lines.length then
            val last = lines(eLine)
            sb.append('\n').append(last.substring(0, math.min(eCol, last.length)))
          sb.toString
      }

    def paramTypesOf(funTpe: TypeRepr): List[TypeRepr] =
      funTpe.widen match
        case mt: MethodType => mt.paramTypes
        case _              => Nil

    def expectedAt(fun: Term, args: List[Term], idx: Int): Option[TypeRepr] =
      val ptypes = paramTypesOf(fun.tpe)
      args(idx) match
        case NamedArg(n, _) =>
          fun.tpe.widen match
            case mt: MethodType =>
              val i = mt.paramNames.indexOf(n)
              if i >= 0 then Some(mt.paramTypes(i)) else None
            case _ => None
        case _ =>
          if idx < ptypes.size then
            ptypes(idx) match
              case AppliedType(tycon, List(elem))
                  if tycon.show.contains("<repeated>") => Some(elem)
              case other => Some(other)
          else if ptypes.nonEmpty then
            ptypes.last match
              case AppliedType(tycon, List(elem))
                  if tycon.show.contains("<repeated>") => Some(elem)
              case _ => None
          else None

    def headRef(t: Tree): Tree = t match
      case Apply(f, _)     => headRef(f)
      case TypeApply(f, _) => headRef(f)
      case other           => other

    def tryRecord(t: Tree, et: TypeRepr, cat: String): Boolean =
      val (memberSym, posTree) = t match
        case s: Select => (s.symbol, s)
        case i: Ident  => (i.symbol, i)
        case _         => (Symbol.noSymbol, t)
      if !memberSym.exists then return false
      val pc = principalClass(et) match
        case Some(c) => c
        case None    => return false
      if !memberOfCompanion(memberSym, pc) then return false
      val pos = posTree.pos
      val absPath = pos.sourceFile.path
      if !absPath.endsWith(".scala") then return false
      val text = textAt(pos)
      if text.isEmpty then return false
      val memberName = memberSym.name
      // Require the user's source to actually mention the member: either
      // the bare name (after wildcard import) or ``.<name>`` (qualified).
      // Filters out synthesised positions whose Position points back to
      // a token like ``Shape`` while the resolved symbol is a compiler-
      // inserted ``apply``.
      val mentionsMember =
        text == memberName ||
        text.endsWith("." + memberName) ||
        text.contains("." + memberName + "[") ||
        text.contains("." + memberName + "(") ||
        text.contains("." + memberName + " ") ||
        text.contains("." + memberName + ",") ||
        text.contains("." + memberName + ")")
      if !mentionsMember then return false
      val isImportBased = !text.contains('.')
      val charsSaved =
        if isImportBased then 0
        else math.max(0, text.length - (memberName.length + 1))
      acc += Finding(
        project = project,
        file = relativise(absPath),
        line = pos.startLine + 1,
        col = pos.startColumn + 1,
        sourceText = text,
        member = memberName,
        expectedType = pc.fullName,
        charsSaved = charsSaved,
        importBased = isImportBased,
        category = cat,
      )
      true

    def unapplyComponentTypes(fn: Term): List[TypeRepr] =
      val resTpe = fn.tpe.widen match
        case mt: MethodType => mt.resType
        case other          => other
      resTpe.dealias match
        case AppliedType(tc, args)
            if tc.show.endsWith("Option") || tc.show.endsWith("Some") =>
          args.headOption match
            case Some(AppliedType(tt, ts)) if tt.show.startsWith("scala.Tuple") => ts
            case Some(t) => List(t)
            case None    => Nil
        case _ => Nil

    // A nested TreeTraverser so we can call protected traverseTreeChildren
    // for the default descent. The walker tracks expected-type context as
    // method parameters threaded through walk(...).
    class Walker extends TreeTraverser:

      def walk(tree: Tree, etOpt: Option[TypeRepr], cat: String): Unit =
        etOpt match
          case Some(et) =>
            val recorded = tryRecord(tree, et, cat)
            if !recorded then
              tree match
                case Apply(fn, _)     => tryRecord(headRef(fn), et, cat)
                case TypeApply(fn, _) => tryRecord(headRef(fn), et, cat)
                case _                => ()
          case None => ()

        tree match
          case vd @ ValDef(_, tpt, Some(rhs)) =>
            if !vd.symbol.flags.is(Flags.Synthetic) then
              val userTyped = tpt match
                case Inferred() => false
                case _          => true
              if userTyped then walk(rhs, Some(tpt.tpe), Cat.TypedDecl)
              else                walk(rhs, None, Cat.Other)
          case dd @ DefDef(name, paramss, ret, Some(rhs)) =>
            // Synthetic default-getter defs (``foo$default$1``) hold the
            // user-written default-value expressions; treat them as user
            // code with category DefaultArg. Other synthetic defs
            // (eta-expansion, case-class apply/copy, mirror glue) are
            // compiler-internal — skip entirely.
            val isDefaultGetter = name.contains("$default$")
            if isDefaultGetter then
              walk(rhs, Some(ret.tpe), Cat.DefaultArg)
            else if !dd.symbol.flags.is(Flags.Synthetic) then
              val userTyped = ret match
                case Inferred() => false
                case _          => true
              paramss.foreach {
                case TermParamClause(params) =>
                  params.foreach {
                    case ValDef(_, tpt, rhsOpt) =>
                      // (Default values are now in synthetic getters,
                      // captured above; this branch handles direct
                      // default-arg trees if they survive into TASTy.)
                      val pTyped = tpt match
                        case Inferred() => false
                        case _          => true
                      if pTyped then
                        rhsOpt.foreach(r => walk(r, Some(tpt.tpe), Cat.DefaultArg))
                  }
                case _ => ()
              }
              if userTyped then walk(rhs, Some(ret.tpe), Cat.TypedDecl)
              else                walk(rhs, None, Cat.Other)
          case Apply(fn, args) =>
            walk(fn, None, Cat.Other)
            args.zipWithIndex.foreach((arg, i) =>
              walk(arg, expectedAt(fn, args, i), Cat.CallArg)
            )
          case TypeApply(fn, _) =>
            walk(fn, etOpt, cat)
          case Typed(Repeated(elems, elemtpt), _) =>
            // varargs spread: each element is at the elem type, in CallArg.
            elems.foreach(walk(_, Some(elemtpt.tpe), Cat.CallArg))
          case Typed(expr, tpt) =>
            walk(expr, Some(tpt.tpe), Cat.Ascription)
          case Repeated(elems, elemtpt) =>
            elems.foreach(walk(_, Some(elemtpt.tpe), cat))
          case Block(stats, expr) =>
            stats.foreach(walk(_, None, Cat.Other))
            walk(expr, etOpt, cat)
          case If(cond, thenp, elsep) =>
            walk(cond, None, Cat.Other)
            walk(thenp, etOpt, Cat.IfBranch)
            walk(elsep, etOpt, Cat.IfBranch)
          case Match(scrut, cases) =>
            walk(scrut, None, Cat.Other)
            val scrutTpe = scrut.tpe.widen
            cases.foreach(walkCase(_, scrutTpe))
          case NamedArg(_, arg) =>
            walk(arg, etOpt, cat)
          case Inlined(_, _, body) =>
            walk(body, etOpt, cat)
          case _: Select | _: Ident | _: TypeTree | _: Import =>
            ()
          case other =>
            // Default: descend into children with no expected type.
            traverseTreeChildren(other)(Symbol.spliceOwner)

      def walkCase(cd: CaseDef, scrutTpe: TypeRepr): Unit =
        walkPattern(cd.pattern, scrutTpe, top = true)
        cd.guard.foreach(walk(_, None, Cat.Other))
        walk(cd.rhs, None, Cat.Other)

      def walkPattern(pat: Tree, et: TypeRepr, top: Boolean): Unit =
        val cat = if top then Cat.MatchCase else Cat.NestedPattern
        val recorded = tryRecord(pat, et, cat)
        if !recorded then
          pat match
            case Alternatives(alts) =>
              alts.foreach(walkPattern(_, et, top))
            case Bind(_, body) =>
              walkPattern(body, et, top)
            case TypedOrTest(inner, _) =>
              walkPattern(inner, et, top)
            case Unapply(fn, _, patterns) =>
              val componentTypes = unapplyComponentTypes(fn)
              patterns.zipWithIndex.foreach((sub, i) =>
                walkPattern(sub, componentTypes.lift(i).getOrElse(et), top = false)
              )
            case _ => ()

      // The TreeTraverser default — used only for unmatched trees in walk's
      // "other" branch. We override traverseTree so the default child
      // traversal also goes through our walk logic.
      override def traverseTree(t: Tree)(owner: Symbol): Unit =
        walk(t, None, Cat.Other)

    end Walker

    val walker = new Walker
    for t <- tastys do
      walker.walk(t.ast, None, Cat.Other)

  end inspect

end Sip80Inspector


object Main:
  def main(args: Array[String]): Unit =
    args.toList match
      case "fixtures" :: tastyDir :: outDir :: srcRoots =>
        val tastyFiles = collectTastyFiles(Paths.get(tastyDir)).map(_.toString)
        val inspector = new Sip80Inspector(
          project = "fixtures",
          sourceRoots = srcRoots.map(Paths.get(_)),
        )
        TastyInspector.inspectTastyFiles(tastyFiles)(inspector)
        emitReport(inspector.findings, Paths.get(outDir))

      case "jar" :: jar :: project :: outDir :: srcRoot :: depsClasspath :: Nil =>
        val inspector = new Sip80Inspector(
          project = project,
          sourceRoots = if srcRoot.nonEmpty then List(Paths.get(srcRoot)) else Nil,
        )
        val deps = depsClasspath.split(java.io.File.pathSeparator).toList.filter(_.nonEmpty)
        TastyInspector.inspectAllTastyFiles(Nil, List(jar), deps)(inspector)
        emitReport(inspector.findings, Paths.get(outDir))

      case "jar" :: jar :: project :: outDir :: srcRoots =>
        val inspector = new Sip80Inspector(
          project = project,
          sourceRoots = srcRoots.map(Paths.get(_)),
        )
        TastyInspector.inspectTastyFilesInJar(jar)(inspector)
        emitReport(inspector.findings, Paths.get(outDir))

      case _ =>
        Console.err.println(
          "usage: sip80-tasty fixtures <tasty-dir> <out-dir> [src-root...]\n" +
          "       sip80-tasty jar <jar> <project> <out-dir> <src-root> <deps-cp>"
        )
        sys.exit(2)

  def collectTastyFiles(root: Path): List[Path] =
    if !Files.isDirectory(root) then Nil
    else
      val out = mutable.ArrayBuffer.empty[Path]
      Files.walk(root).forEach(p =>
        if Files.isRegularFile(p) && p.toString.endsWith(".tasty") then out += p
      )
      out.toList

  def emitReport(findings: List[Finding], outDir: Path): Unit =
    Files.createDirectories(outDir)
    val tsv = outDir.resolve("findings.tsv")
    val w = Files.newBufferedWriter(tsv, java.nio.charset.StandardCharsets.UTF_8)
    try
      w.write(Finding.header); w.newLine()
      for f <- findings do
        w.write(Finding.tsv(f)); w.newLine()
    finally w.close()

    val totalIncidents = findings.size
    val totalChars = findings.iterator.map(_.charsSaved).sum
    val importBased = findings.count(_.importBased)
    val byCat = findings.groupBy(_.category).view.mapValues(_.size).toMap
    val byFile = findings.groupBy(_.file).view.mapValues(_.size).toMap

    val summary = outDir.resolve("summary.json")
    val sb = new StringBuilder
    sb.append("{\n")
    sb.append(s"""  "total_incidents": $totalIncidents,\n""")
    sb.append(s"""  "import_based": $importBased,\n""")
    sb.append(s"""  "total_chars_saved": $totalChars,\n""")
    sb.append("""  "by_category": {""").append('\n')
    sb.append(byCat.toSeq.sortBy(_._1).map((k, v) => s"""    "$k": $v""").mkString(",\n"))
    sb.append("\n  },\n")
    sb.append("""  "by_file": {""").append('\n')
    sb.append(byFile.toSeq.sortBy(_._1).map((k, v) => s"""    "$k": $v""").mkString(",\n"))
    sb.append("\n  }\n}\n")
    Files.writeString(summary, sb.toString, java.nio.charset.StandardCharsets.UTF_8)

    println(sb.toString)
end Main
