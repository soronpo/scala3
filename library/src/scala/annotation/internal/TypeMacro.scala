package scala.annotation.internal

import scala.annotation.StaticAnnotation

/** Marks a type member as a *type macro* (see the "Type Macros" proposal).
 *
 *  This annotation is synthesised by the compiler for a bounded type definition
 *  whose right-hand side is a splice:
 *
 *  ```scala
 *  type From[T] <: AnyNamedTuple = ${ fromImpl[T] }
 *  ```
 *
 *  becomes an abstract, upper-bounded type carrying `@TypeMacro("fromImpl")`.
 *  The argument is the simple name of the macro-implementation method, looked up
 *  in the type's enclosing scope when the type is reduced. Users do not write
 *  this annotation directly; the `<: U = ${ … }` syntax produces it.
 *
 *  See `dotty.tools.dotc.transform.TypeMacros`.
 */
final class TypeMacro(impl: String) extends StaticAnnotation
