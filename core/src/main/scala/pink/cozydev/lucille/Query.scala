/*
 * Copyright 2022 CozyDev
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package pink.cozydev.lucille

import cats.data.NonEmptyList

sealed trait Query extends Product with Serializable

final case class MultiQuery(qs: NonEmptyList[Query]) extends Query {
  def mapLast(f: Query => Query): MultiQuery =
    if (qs.size == 1) MultiQuery(NonEmptyList.one(f(qs.head)))
    else {
      val newT = qs.tail.init :+ f(qs.last)
      MultiQuery(NonEmptyList(qs.head, newT))
    }
}
object MultiQuery {
  def apply(head: Query, tail: Query*): MultiQuery =
    MultiQuery(NonEmptyList(head, tail.toList))
}

object Query {
  final case class Term(str: String) extends Query
  final case class Phrase(str: String) extends Query
  final case class Prefix(str: String) extends Query
  final case class Proximity(str: String, num: Int) extends Query
  final case class Fuzzy(str: String, num: Option[Int]) extends Query
  final case class TermRegex(str: String) extends Query
  final case class TermRange(
      lower: Option[String],
      upper: Option[String],
      lowerInc: Boolean,
      upperInc: Boolean,
  ) extends Query

  final case class Or(qs: NonEmptyList[Query]) extends Query
  object Or {
    def apply(head: Query, tail: Query*): Or =
      Or(NonEmptyList(head, tail.toList))
  }

  final case class And(qs: NonEmptyList[Query]) extends Query
  object And {
    def apply(head: Query, tail: Query*): And =
      And(NonEmptyList(head, tail.toList))
  }

  final case class Not(q: Query) extends Query

  final case class Group(qs: NonEmptyList[Query]) extends Query
  object Group {
    def apply(head: Query, tail: Query*): Group =
      Group(NonEmptyList(head, tail.toList))
  }

  final case class UnaryPlus(q: Query) extends Query
  final case class UnaryMinus(q: Query) extends Query
  final case class MinimumMatch(qs: NonEmptyList[Query], num: Int) extends Query
  final case class Field(field: String, q: Query) extends Query
}