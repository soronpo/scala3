package dotty.tools.dotc
package sbt

import scala.language.unsafeNulls

import java.io.File
import java.nio.file.Path
import java.util.{Arrays, EnumSet}

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.classpath.FileUtils.{isTasty, hasClassExtension, hasTastyExtension}
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Decorators._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.NameOps._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.Phases._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Denotations.StaleSymbol
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.transform.SymUtils._
import dotty.tools.dotc.util.{SrcPos, NoSourcePosition}
import dotty.tools.io
import dotty.tools.io.{AbstractFile, PlainFile, ZipArchive, NoAbstractFile}
import xsbti.UseScope
import xsbti.api.DependencyContext
import xsbti.api.DependencyContext._

import scala.collection.{Set, mutable}


/** This phase sends information on classes' dependencies to sbt via callbacks.
 *
 *  This is used by sbt for incremental recompilation. Briefly, when a file
 *  changes sbt will recompile it, if its API has changed (determined by what
 *  `ExtractAPI` sent) then sbt will determine which reverse-dependencies
 *  (determined by what `ExtractDependencies` sent) of the API have to be
 *  recompiled depending on what changed.
 *
 *  See the documentation of `ExtractDependenciesCollector`, `ExtractAPI`,
 *  `ExtractAPICollector` and
 *  http://www.scala-sbt.org/0.13/docs/Understanding-Recompilation.html for more
 *  information on how sbt incremental compilation works.
 *
 *  The following flags affect this phase:
 *   -Yforce-sbt-phases
 *   -Ydump-sbt-inc
 *
 *  @see ExtractAPI
 */
class ExtractDependencies extends Phase {
  import ExtractDependencies._

  override def phaseName: String = ExtractDependencies.name

  override def description: String = ExtractDependencies.description

  override def isRunnable(using Context): Boolean = {
    super.isRunnable && ctx.runZincPhases
  }

  // Check no needed. Does not transform trees
  override def isCheckable: Boolean = false

  // This phase should be run directly after `Frontend`, if it is run after
  // `PostTyper`, some dependencies will be lost because trees get simplified.
  // See the scripted test `constants` for an example where this matters.
  // TODO: Add a `Phase#runsBefore` method ?

  override def run(using Context): Unit = {
    val unit = ctx.compilationUnit
    val rec = unit.depRecorder
    val collector = ExtractDependenciesCollector(rec)
    collector.traverse(unit.tpdTree)

    if (ctx.settings.YdumpSbtInc.value) {
      val deps = rec.classDependencies.map(_.toString).toArray[Object]
      val names = rec.usedNames.map { case (clazz, names) => s"$clazz: $names" }.toArray[Object]
      Arrays.sort(deps)
      Arrays.sort(names)

      val pw = io.File(unit.source.file.jpath).changeExtension("inc").toFile.printWriter()
      // val pw = Console.out
      try {
        pw.println("Used Names:")
        pw.println("===========")
        names.foreach(pw.println)
        pw.println()
        pw.println("Dependencies:")
        pw.println("=============")
        deps.foreach(pw.println)
      } finally pw.close()
    }

    rec.sendToZinc()
  }
}

object ExtractDependencies {
  val name: String = "sbt-deps"
  val description: String = "sends information on classes' dependencies to sbt"

  def classNameAsString(sym: Symbol)(using Context): String =
    sym.fullName.stripModuleClassSuffix.toString

  /** Report an internal error in incremental compilation. */
  def internalError(msg: => String, pos: SrcPos = NoSourcePosition)(using Context): Unit =
    report.error(em"Internal error in the incremental compiler while compiling ${ctx.compilationUnit.source}: $msg", pos)
}

/** Extract the dependency information of a compilation unit.
 *
 *  To understand why we track the used names see the section "Name hashing
 *  algorithm" in http://www.scala-sbt.org/0.13/docs/Understanding-Recompilation.html
 *  To understand why we need to track dependencies introduced by inheritance
 *  specially, see the subsection "Dependencies introduced by member reference and
 *  inheritance" in the "Name hashing algorithm" section.
 */
private class ExtractDependenciesCollector(rec: DependencyRecorder) extends tpd.TreeTraverser { thisTreeTraverser =>
  import tpd._

  private def addMemberRefDependency(sym: Symbol)(using Context): Unit =
    if (!ignoreDependency(sym)) {
      rec.addUsedName(sym)
      // packages have class symbol. Only record them as used names but not dependency
      if (!sym.is(Package)) {
        val enclOrModuleClass = if (sym.is(ModuleVal)) sym.moduleClass else sym.enclosingClass
        assert(enclOrModuleClass.isClass, s"$enclOrModuleClass, $sym")

        rec.addClassDependency(enclOrModuleClass, DependencyByMemberRef)
      }
    }

  private def addInheritanceDependencies(tree: Closure)(using Context): Unit =
    // If the tpt is empty, this is a non-SAM lambda, so no need to register
    // an inheritance relationship.
    if !tree.tpt.isEmpty then
      rec.addClassDependency(tree.tpt.tpe.classSymbol, LocalDependencyByInheritance)

  private def addInheritanceDependencies(tree: Template)(using Context): Unit =
    if (tree.parents.nonEmpty) {
      val depContext = depContextOf(tree.symbol.owner)
      for parent <- tree.parents do
        rec.addClassDependency(parent.tpe.classSymbol, depContext)
    }

  private def depContextOf(cls: Symbol)(using Context): DependencyContext =
    if cls.isLocal then LocalDependencyByInheritance
    else DependencyByInheritance

  private def ignoreDependency(sym: Symbol)(using Context) =
    try
      !sym.exists ||
      sym.isAbsent(canForce = false) || // ignore dependencies that have a symbol but do not exist.
                                        // e.g. java.lang.Object companion object
      sym.isEffectiveRoot ||
      sym.isAnonymousFunction ||
      sym.isAnonymousClass
    catch case ex: StaleSymbol =>
      // can happen for constructor proxies. Test case is pos-macros/i13532.
      true


  /** Traverse the tree of a source file and record the dependencies and used names which
   *  can be retrieved using `dependencies` and`usedNames`.
   */
  override def traverse(tree: Tree)(using Context): Unit = try {
    tree match {
      case Match(selector, _) =>
        addPatMatDependency(selector.tpe)
      case Import(expr, selectors) =>
        def lookupImported(name: Name) =
          expr.tpe.member(name).symbol
        def addImported(name: Name) = {
          // importing a name means importing both a term and a type (if they exist)
          addMemberRefDependency(lookupImported(name.toTermName))
          addMemberRefDependency(lookupImported(name.toTypeName))
        }
        for sel <- selectors if !sel.isWildcard do
          addImported(sel.name)
          if sel.rename != sel.name then
            rec.addUsedRawName(sel.rename)
      case exp @ Export(expr, selectors) =>
        val dep = expr.tpe.classSymbol
        if dep.exists && selectors.exists(_.isWildcard) then
          // If an export is a wildcard, that means that the enclosing class
          // has forwarders to all the applicable signatures in `dep`,
          // those forwarders will cause member/type ref dependencies to be
          // recorded. However, if `dep` adds more members with new names,
          // there has been no record that the enclosing class needs to
          // recompile to capture the new members. We add an
          // inheritance dependency in the presence of wildcard exports
          // to ensure all new members of `dep` are forwarded to.
          val depContext = depContextOf(ctx.owner.lexicallyEnclosingClass)
          rec.addClassDependency(dep, depContext)
      case t: TypeTree =>
        addTypeDependency(t.tpe)
      case ref: RefTree =>
        addMemberRefDependency(ref.symbol)
        addTypeDependency(ref.tpe)
      case t: Closure =>
        addInheritanceDependencies(t)
      case t: Template =>
        addInheritanceDependencies(t)
      case _ =>
    }

    tree match {
      case tree: Inlined if !tree.inlinedFromOuterScope =>
        // The inlined call is normally ignored by TreeTraverser but we need to
        // record it as a dependency
        traverse(tree.call)
      case vd: ValDef if vd.symbol.is(ModuleVal) =>
        // Don't visit module val
      case t: Template if t.symbol.owner.is(ModuleClass) =>
        // Don't visit self type of module class
        traverse(t.constr)
        t.parents.foreach(traverse)
        t.body.foreach(traverse)
      case _ =>
        traverseChildren(tree)
    }
  } catch {
    case ex: AssertionError =>
      println(i"asserted failed while traversing $tree")
      throw ex
  }

  /** Traverse a used type and record all the dependencies we need to keep track
   *  of for incremental recompilation.
   *
   *  As a motivating example, given a type `T` defined as:
   *
   *    type T >: L <: H
   *    type L <: A1
   *    type H <: B1
   *    class A1 extends A0
   *    class B1 extends B0
   *
   *  We need to record a dependency on `T`, `L`, `H`, `A1`, `B1`. This is
   *  necessary because the API representation that `ExtractAPI` produces for
   *  `T` just refers to the strings "L" and "H", it does not contain their API
   *  representation. Therefore, the name hash of `T` does not change if for
   *  example the definition of `L` changes.
   *
   *  We do not need to keep track of superclasses like `A0` and `B0` because
   *  the API representation of a class (and therefore its name hash) already
   *  contains all necessary information on superclasses.
   *
   *  A natural question to ask is: Since traversing all referenced types to
   *  find all these names is costly, why not change the API representation
   *  produced by `ExtractAPI` to contain that information? This way the name
   *  hash of `T` would change if any of the types it depends on change, and we
   *  would only need to record a dependency on `T`. Unfortunately there is no
   *  simple answer to the question "what does T depend on?" because it depends
   *  on the prefix and `ExtractAPI` does not compute types as seen from every
   *  possible prefix, the documentation of `ExtractAPI` explains why.
   *
   *  The tests in sbt `types-in-used-names-a`, `types-in-used-names-b`,
   *  `as-seen-from-a` and `as-seen-from-b` rely on this.
   */
  private abstract class TypeDependencyTraverser(using Context) extends TypeTraverser() {
    protected def addDependency(symbol: Symbol): Unit

    // Avoid cycles by remembering both the types (testcase:
    // tests/run/enum-values.scala) and the symbols of named types (testcase:
    // tests/pos-java-interop/i13575) we've seen before.
    val seen = new mutable.HashSet[Symbol | Type]
    def traverse(tp: Type): Unit = if (!seen.contains(tp)) {
      seen += tp
      tp match {
        case tp: NamedType =>
          val sym = tp.symbol
          if !seen.contains(sym) && !sym.is(Package) then
            seen += sym
            addDependency(sym)
            if !sym.isClass then traverse(tp.info)
            traverse(tp.prefix)
        case tp: ThisType =>
          traverse(tp.underlying)
        case tp: ConstantType =>
          traverse(tp.underlying)
        case tp: ParamRef =>
          traverse(tp.underlying)
        case _ =>
          traverseChildren(tp)
      }
    }
  }

  def addTypeDependency(tpe: Type)(using Context): Unit = {
    val traverser = new TypeDependencyTraverser {
      def addDependency(symbol: Symbol) = addMemberRefDependency(symbol)
    }
    traverser.traverse(tpe)
  }

  def addPatMatDependency(tpe: Type)(using Context): Unit = {
    val traverser = new TypeDependencyTraverser {
      def addDependency(symbol: Symbol) =
        if (!ignoreDependency(symbol) && symbol.is(Sealed)) {
          rec.addUsedName(symbol, includeSealedChildren = true)
        }
    }
    traverser.traverse(tpe)
  }
}

case class ClassDependency(fromClass: Symbol, toClass: Symbol, context: DependencyContext)

/** Record dependencies using `addUsedName`/`addClassDependency` and inform Zinc using `sendToZinc()`.
 *
 *  Note: As an alternative design choice, we could directly call the appropriate
 *  callback as we record each dependency, this way we wouldn't need to record
 *  them locally and we could get rid of `sendToZinc()`, but this may be less
 *  efficient since it would mean calling `classNameAsString` on each call
 *  to `addUsedName` rather than once per class.
 */
class DependencyRecorder {
  import ExtractDependencies.*

  /** A map from a non-local class to the names it uses, this does not include
   *  names which are only defined and not referenced.
   */
  def usedNames: collection.Map[Symbol, UsedNamesInClass] = _usedNames

  /** Record a reference to the name of `sym` from the current non-local
   *  enclosing class.
   *
   *  @param includeSealedChildren  See documentation of `addUsedRawName`.
   */
  def addUsedName(sym: Symbol, includeSealedChildren: Boolean = false)(using Context): Unit =
    addUsedRawName(sym.zincMangledName, includeSealedChildren)

  /** Record a reference to `name` from the current non-local enclosing class (aka, "from class").
   *
   *  Most of the time, prefer to use `addUsedName` which takes
   *  care of name mangling.
   *
   *  Zinc will use this information to invalidate the current non-local
   *  enclosing class if something changes in the set of definitions named
   *  `name` among the possible dependencies of the from class.
   *
   *  @param includeSealedChildren  If true, the addition or removal of children
   *                                to a sealed class called `name` will also
   *                                invalidate the from class.
   *                                Note that this only has an effect if zinc's
   *                                `IncOptions.useOptimizedSealed` is enabled,
   *                                otherwise the addition or removal of children
   *                                always lead to invalidation.
   *
   *  TODO: If the compiler reported to zinc all usages of
   *  `SymDenotation#{children,sealedDescendants}` (including from macro code),
   *  we should be able to turn `IncOptions.useOptimizedSealed` on by default
   *  safely.
   */
  def addUsedRawName(name: Name, includeSealedChildren: Boolean = false)(using Context): Unit = {
    val fromClass = resolveDependencySource
    if (fromClass.exists) {
      val usedName = _usedNames.getOrElseUpdate(fromClass, new UsedNamesInClass)
      usedName.update(name, includeSealedChildren)
    }
  }

  // The two possible value of `UseScope`. To avoid unnecessary allocations,
  // we use vals here, but that means we must be careful to never mutate these sets.
  private val DefaultScopes = EnumSet.of(UseScope.Default)
  private val PatMatScopes = EnumSet.of(UseScope.Default, UseScope.PatMatTarget)

  /** An object that maintain the set of used names from within a class */
  final class UsedNamesInClass {
    /** Each key corresponds to a name used in the class. To understand the meaning
     *  of the associated value, see the documentation of parameter `includeSealedChildren`
     *  of `addUsedRawName`.
     */
    private val _names = new mutable.HashMap[Name, DefaultScopes.type | PatMatScopes.type]

    def names: collection.Map[Name, EnumSet[UseScope]] = _names

    private[DependencyRecorder] def update(name: Name, includeSealedChildren: Boolean): Unit = {
      if (includeSealedChildren)
        _names(name) = PatMatScopes
      else
        _names.getOrElseUpdate(name, DefaultScopes)
    }

    override def toString(): String = {
      val builder = new StringBuilder
      names.foreach { case (name, scopes) =>
        builder.append(name.mangledString)
        builder.append(" in [")
        scopes.forEach(scope => builder.append(scope.toString))
        builder.append("]")
        builder.append(", ")
      }
      builder.toString()
    }
  }


  private val _classDependencies = new mutable.HashSet[ClassDependency]

  def classDependencies: Set[ClassDependency] = _classDependencies

  /** Record a dependency to the class `to` in a given `context`
   *  from the current non-local enclosing class.
  */
  def addClassDependency(toClass: Symbol, context: DependencyContext)(using Context): Unit =
    val fromClass = resolveDependencySource
    if (fromClass.exists)
      _classDependencies += ClassDependency(fromClass, toClass, context)

  private val _usedNames = new mutable.HashMap[Symbol, UsedNamesInClass]

  /** Send the collected dependency information to Zinc and clear the local caches. */
  def sendToZinc()(using Context): Unit =
    ctx.withIncCallback: cb =>
      usedNames.foreach:
        case (clazz, usedNames) =>
          val className = classNameAsString(clazz)
          usedNames.names.foreach:
            case (usedName, scopes) =>
              cb.usedName(className, usedName.toString, scopes)
      val siblingClassfiles = new mutable.HashMap[PlainFile, Path]
      classDependencies.foreach(recordClassDependency(cb, _, siblingClassfiles))
    clear()

   /** Clear all state. */
  def clear(): Unit =
    _usedNames.clear()
    _classDependencies.clear()
    lastOwner = NoSymbol
    lastDepSource = NoSymbol
    _responsibleForImports = NoSymbol

  /** Handles dependency on given symbol by trying to figure out if represents a term
   *  that is coming from either source code (not necessarily compiled in this compilation
   *  run) or from class file and calls respective callback method.
   */
  private def recordClassDependency(cb: interfaces.IncrementalCallback, dep: ClassDependency,
      siblingClassfiles: mutable.Map[PlainFile, Path])(using Context): Unit = {
    val fromClassName = classNameAsString(dep.fromClass)
    val sourceFile = ctx.compilationUnit.source

    /**For a `.tasty` file, constructs a sibling class to the `jpath`.
     * Does not validate if it exists as a real file.
     *
     * Because classpath scanning looks for tasty files first, `dep.fromClass` will be
     * associated to a `.tasty` file. However Zinc records all dependencies either based on `.jar` or `.class` files,
     * where classes are in directories on the filesystem.
     *
     * So if the dependency comes from an upstream `.tasty` file and it was not packaged in a jar, then
     * we need to call this to resolve the classfile that will eventually exist at runtime.
     *
     * The way this works is that by the end of compilation analysis,
     * we should have called `cb.generatedNonLocalClass` with the same class file name.
     *
     * FIXME: we still need a way to resolve the correct classfile when we split tasty and classes between
     * different outputs (e.g. stdlib-bootstrapped).
     */
    def cachedSiblingClass(pf: PlainFile): Path =
      siblingClassfiles.getOrElseUpdate(pf, {
        val jpath = pf.jpath
        jpath.getParent.resolve(jpath.getFileName.toString.stripSuffix(".tasty") + ".class")
      })

    def binaryDependency(path: Path, binaryClassName: String) =
      cb.binaryDependency(path, binaryClassName, fromClassName, sourceFile, dep.context)

    val depClass = dep.toClass
    val depFile = depClass.associatedFile
    if depFile != null then {
      // Cannot ignore inheritance relationship coming from the same source (see sbt/zinc#417)
      def allowLocal = dep.context == DependencyByInheritance || dep.context == LocalDependencyByInheritance
      val isTasty = depFile.hasTastyExtension

      def processExternalDependency() = {
        val binaryClassName = depClass.binaryClassName
        depFile match {
          case ze: ZipArchive#Entry => // The dependency comes from a JAR
            ze.underlyingSource match
              case Some(zip) if zip.jpath != null =>
                binaryDependency(zip.jpath, binaryClassName)
              case _ =>
          case pf: PlainFile => // The dependency comes from a class file, Zinc handles JRT filesystem
            binaryDependency(if isTasty then cachedSiblingClass(pf) else pf.jpath, binaryClassName)
          case _ =>
            internalError(s"Ignoring dependency $depFile of unknown class ${depFile.getClass}}", dep.fromClass.srcPos)
        }
      }

      if isTasty || depFile.hasClassExtension then
        processExternalDependency()
      else if allowLocal || depFile != sourceFile.file then
        // We cannot ignore dependencies coming from the same source file because
        // the dependency info needs to propagate. See source-dependencies/trait-trait-211.
        val toClassName = classNameAsString(depClass)
        cb.classDependency(toClassName, fromClassName, dep.context)
    }
  }

  private var lastOwner: Symbol = _
  private var lastDepSource: Symbol = _

  /** The source of the dependency according to `nonLocalEnclosingClass`
   *  if it exists, otherwise fall back to `responsibleForImports`.
   *
   *  This is backed by a cache which is invalidated when `ctx.owner` changes.
   */
  private def resolveDependencySource(using Context): Symbol = {
    if (lastOwner != ctx.owner) {
      lastOwner = ctx.owner
      val source = nonLocalEnclosingClass
      lastDepSource = if (source.is(PackageClass)) responsibleForImports else source
    }

    lastDepSource
  }

  /** The closest non-local enclosing class from `ctx.owner`. */
  private def nonLocalEnclosingClass(using Context): Symbol = {
    var clazz = ctx.owner.enclosingClass
    var owner = clazz

    while (!owner.is(PackageClass)) {
      if (owner.isTerm) {
        clazz = owner.enclosingClass
        owner = clazz
      } else {
        owner = owner.owner
      }
    }
    clazz
  }

  private var _responsibleForImports: Symbol = _

  /** Top level import dependencies are registered as coming from a first top level
   *  class/trait/object declared in the compilation unit. If none exists, issue a warning and return NoSymbol.
   */
  private def responsibleForImports(using Context) = {
    import tpd.*
    def firstClassOrModule(tree: Tree) = {
      val acc = new TreeAccumulator[Symbol] {
        def apply(x: Symbol, t: Tree)(using Context) =
          t match {
            case typeDef: TypeDef =>
              typeDef.symbol
            case other =>
              foldOver(x, other)
          }
      }
      acc(NoSymbol, tree)
    }

    if (_responsibleForImports == null) {
      val tree = ctx.compilationUnit.tpdTree
      _responsibleForImports = firstClassOrModule(tree)
      if (!_responsibleForImports.exists)
          report.warning("""|No class, trait or object is defined in the compilation unit.
                            |The incremental compiler cannot record the dependency information in such case.
                            |Some errors like unused import referring to a non-existent class might not be reported.
                            |""".stripMargin, tree.sourcePos)
    }
    _responsibleForImports
  }
}
