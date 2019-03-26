/*
  Fury, version 0.4.0. Copyright 2018-19 Jon Pretty, Propensive Ltd.

  The primary distribution site is: https://propensive.com/

  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
  in compliance with the License. You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required  by applicable  law or  agreed to  in writing,  software  distributed  under the
  License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
  express  or  implied.  See  the  License for  the specific  language  governing  permissions and
  limitations under the License.
 */
package fury.core

import fury.strings._, fury.io._

import escritoire._
import gastronomy._

import scala.collection.immutable.SortedSet
import scala.language.implicitConversions
import scala.util._

object `package` {
  implicit def resolverExt[T](items: Traversable[T]): ResolverExt[T] = new ResolverExt[T](items)

  implicit def ansiShow[T: MsgShow](implicit theme: Theme): AnsiShow[T] =
    value => implicitly[MsgShow[T]].show(value).string(theme)

  implicit def msgShowTraversable[T: MsgShow]: MsgShow[SortedSet[T]] =
    xs => xs.size match {
      case 0 => msg"${'{'}${'}'}"
      case 1 => msg"${xs.head}"
      case _ => msg"${'{'}${xs.map { x => msg"$x" }.reduce { (l, r) => msg"$l${','} $r" }}${'}'}"
    }

  implicit def stringShowOrdering[T: StringShow]: Ordering[T] =
    Ordering.String.on(implicitly[StringShow[T]].show(_))

  implicit val diff: Diff[Path]                 = Diff.on(_.value)
  implicit val msgShowBoolean: MsgShow[Boolean] = if (_) msg">" else msg""
  implicit val msgShowPath: MsgShow[Path]       = path => UserMsg(_.path(path.value))

  implicit val fileSystemSafeBase64Url: ByteEncoder[Base64Url] =
    ByteEncoder.base64.encode(_).replace('/', '_').takeWhile(_ != '=')

  implicit class Unitize[T](t: T)   { def unit: Unit       = ()         }
  implicit class AutoRight[T](t: T) { def unary_~ : Try[T] = Success(t) }
  implicit class Ascribe[T](value: Option[T]) {
    def ascribe(e: Exception): Try[T] = value.map(Success(_)).getOrElse(Failure(e))
  }
}
