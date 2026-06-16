/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc. dba Akka
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.annotation

import scala.language.`2.13`

/** Marks an abstract, upper-bounded type member as a *type macro*.
 *
 *  A type macro is reduced by the compiler by invoking a metaprogram that
 *  computes a type, rather than by the built-in reduction rules of match
 *  types or `scala.compiletime.ops`. The metaprogram is an ordinary method,
 *  named like the type member, declared in the same enclosing scope, with the
 *  shape:
 *
 *  ```scala
 *  import scala.quoted.*
 *  import scala.annotation.typeMacro
 *
 *  object example:
 *    @typeMacro type Gcd[A <: Int, B <: Int] <: Int
 *    def Gcd[A <: Int : Type, B <: Int : Type](using Quotes): Type[? <: Int] = ???
 *  ```
 *
 *  The application `Gcd[A, B]` reduces to the type returned by the
 *  implementation, but only once every type argument is *concrete* (fully
 *  defined). Until then it is treated as an abstract type bounded by the
 *  declared upper bound (`<: Int` above), exactly like an unreduced
 *  `compiletime.ops` type or a stuck match type.
 *
 *  This is a prototype of the "Type Macros" SIP. The annotation stands in for
 *  the proposed surface syntax `type Gcd[A, B] <: Int = $\{ gcdImpl[A, B] \}`;
 *  the same-named companion method plays the role of the spliced
 *  implementation.
 */
final class typeMacro extends StaticAnnotation
