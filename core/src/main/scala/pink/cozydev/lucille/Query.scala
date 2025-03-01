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
import cats.Applicative

/** A trait for all queries */
sealed trait Query extends Product with Serializable {

  /** Builds a new query by applying a `Term => Query` function to a Term if it is in the last position.
    *
    * @param f the function to apply to the last TermQuery
    * @return
    */
  def mapLastTerm(f: Query.Term => Query): Query

  /** Traverse or "walk" over the Query tree structure applying the effectful function `f` to the
    * TermQuery leaf nodes along the way and collecting the results. If the resulting context `G`
    * has a Query, it will have the same structure as the original Query. "Branch" queries like
    * Query.And/Or/Not/Group, and similar are not affected, just the leaf nodes.
    *
    * @param f effectful function
    * @return the query in context G
    */
  def traverseQ[G[_]: Applicative](f: TermQuery => G[Query]): G[Query]

  def and(q: Query): Query = Query.And(this, q)

  def or(q: Query): Query = Query.Or(this, q)

  def not: Query = Query.Not(this)

  def boost(b: Float): Query = Query.Boost(this, b)
}

/** A trait for all leaf node queries (meaning that they do not contain queries) */
sealed trait TermQuery extends Query {
  // noop for everything except Query.Term
  def mapLastTerm(f: Query.Term => Query): Query = this

  // TermQuery has no further structure to traverse, just run the function
  def traverseQ[G[_]: Applicative](f: TermQuery => G[Query]): G[Query] =
    f(this)
}

object Query {

  /** A term query
    * e.g. 'cat', 'catch22'
    *
    *  @param str the term
    */
  final case class Term(str: String) extends TermQuery {
    override def mapLastTerm(f: Query.Term => Query): Query =
      f(this)
  }

  /** A phrase query
    * e.g. 'the cat jumped'
    *
    * @param str the phrase
    */
  final case class Phrase(str: String) extends TermQuery

  /** A prefix query
    * Search for words starting with the given prefix
    * e.g. 'jump*'
    *
    * @param str the prefix
    */
  final case class Prefix(str: String) extends TermQuery

  /** A proximity query
    * Search for words within a specified word distance
    * e.g. '"cat jumped"\~3', '"one two three"\~2'
    *
    * @param str the words
    * @param num the word distance
    */
  final case class Proximity(str: String, num: Int) extends TermQuery

  /** A fuzzy query with an optional distance value
    * e.g. 'cat\~', 'cat\~1'
    *
    * @param str the string
    * @param num the number of edits allowed
    */
  final case class Fuzzy(str: String, num: Option[Int]) extends TermQuery

  /** A regex query
    * Search with a regular expression, the pattern is given between forward slashes, `/`.
    * e.g. '/.ump(s|ing)'
    *
    * @param str the regular expression query
    */
  final case class TermRegex(str: String) extends TermQuery

  /** A range query
    * Search for terms that fall between some upper and lower bounds. The bounds can be inclusive or exclusive.
    * e.g. '{cats TO dogs}', '[1 TO *]'
    *
    * @param lower the lower bound
    * @param upper the upper bound
    * @param lowerInc whether the lower bound is inclusive
    * @param upperInc whether the upper bound is inclusive
    */
  final case class TermRange(
      lower: Option[String],
      upper: Option[String],
      lowerInc: Boolean,
      upperInc: Boolean,
  ) extends TermQuery

  /** An Or operator
    * Join the given queries with OR, the equivalent of taking the union of the results of each query
    * e.g. 'q1 OR q2'
    *
    * @param qs the queries to union
    */
  sealed abstract case class Or private (qs: NonEmptyList[Query]) extends Query {
    def mapLastTerm(f: Query.Term => Query): Or =
      new Or(rewriteLastTerm(qs, f)) {}

    def traverseQ[G[_]: Applicative](f: TermQuery => G[Query]): G[Query] = {
      val gNelQ: G[NonEmptyList[Query]] = qs.traverse(q => q.traverseQ(f))
      Applicative[G].map(gNelQ)(qNel => new Or(qNel) {})
    }
  }
  object Or {
    def apply(left: Query, right: Query, tail: Query*): Or =
      new Or(NonEmptyList(left, right :: tail.toList)) {}

    def apply(left: Query, right: Query, tail: List[Query]): Or =
      new Or(NonEmptyList(left, right :: tail)) {}

    def fromListUnsafe(queries: List[Query]): Or =
      queries match {
        case Nil =>
          throw new IllegalArgumentException("Cannot create Or query from empty list")
        case _ :: Nil =>
          throw new IllegalArgumentException("Cannot create Or query from single element list")
        case h :: t => new Or(NonEmptyList(h, t)) {}
      }
  }

  /**  An And operator
    * Join the given queries with AND, the equivalent of taking the intersection of the results of each query
    * e.g. 'q1 AND q2'
    *
    * @param qs the queries to intersect
    */
  sealed abstract case class And private (qs: NonEmptyList[Query]) extends Query {
    def mapLastTerm(f: Query.Term => Query): And =
      new And(rewriteLastTerm(qs, f)) {}

    def traverseQ[G[_]: Applicative](f: TermQuery => G[Query]): G[Query] = {
      val gNelQ: G[NonEmptyList[Query]] = qs.traverse(q => q.traverseQ(f))
      Applicative[G].map(gNelQ)(qNel => new And(qNel) {})
    }

  }
  object And {
    def apply(left: Query, right: Query, tail: Query*): And =
      new And(NonEmptyList(left, right :: tail.toList)) {}

    def apply(left: Query, right: Query, tail: List[Query]): And =
      new And(NonEmptyList(left, right :: tail)) {}

    def fromListUnsafe(queries: List[Query]): And =
      queries match {
        case Nil =>
          throw new IllegalArgumentException("Cannot create And query from empty list")
        case _ :: Nil =>
          throw new IllegalArgumentException("Cannot create And query from single element list")
        case h :: t => new And(NonEmptyList(h, t)) {}
      }
  }

  /** A Not operator
    * Exclude terms that would match the given query
    * e.g. 'NOT cats'
    *
    * @param q the query to exclude
    */
  final case class Not(q: Query) extends Query {
    def mapLastTerm(f: Query.Term => Query): Not =
      Not(q.mapLastTerm(f))

    def traverseQ[G[_]: Applicative](f: TermQuery => G[Query]): G[Query] =
      Applicative[G].map(q.traverseQ(f))(newQ => Not(newQ))
  }

  /** A group query
    * Queries grouped together with parentheses
    * e.g. '(cats AND dogs)'
    *
    * @param qs the queries to group
    */
  final case class Group(q: Query) extends Query {
    def mapLastTerm(f: Query.Term => Query): Group = this

    def traverseQ[G[_]: Applicative](f: TermQuery => G[Query]): G[Query] =
      Applicative[G].map(q.traverseQ(f))(newQ => Group(newQ))
  }

  /** A unary plus query
    * Search for documents which must contain the given query
    * e.g. '+cat', '+(cats AND dogs)'
    *
    * @param q the query
    */
  final case class UnaryPlus(q: Query) extends Query {
    def mapLastTerm(f: Query.Term => Query): UnaryPlus =
      UnaryPlus(q.mapLastTerm(f))

    def traverseQ[G[_]: Applicative](f: TermQuery => G[Query]): G[Query] =
      Applicative[G].map(q.traverseQ(f))(newQ => UnaryPlus(newQ))
  }

  /** A unary minus query
    * Search for documents which must not contain the given query
    * e.g. '-cat', '-(cats AND dogs)'
    *
    * @param q the query
    */
  final case class UnaryMinus(q: Query) extends Query {
    def mapLastTerm(f: Query.Term => Query): UnaryMinus =
      UnaryMinus(q.mapLastTerm(f))

    def traverseQ[G[_]: Applicative](f: TermQuery => G[Query]): G[Query] =
      Applicative[G].map(q.traverseQ(f))(newQ => UnaryMinus(newQ))
  }

  /** A query with a boost weight
    * Search for documents with the underlying query as usual, the boost is only used in scoring
    * e.g. 'cats^2 OR dogs^3.1'
    *
    * @param q the query
    * @param boost the boost weight
    */
  final case class Boost(q: Query, boost: Float) extends Query {
    def mapLastTerm(f: Query.Term => Query): Boost = this

    def traverseQ[G[_]: Applicative](f: TermQuery => G[Query]): G[Query] =
      Applicative[G].map(q.traverseQ(f))(newQ => this.copy(q = newQ))
  }

  /** A minimum match query
    * Search for documents that match at least `num` of the given queries
    * e.g. '(one two three)@2'
    *
    * @param qs the queries
    * @param num the number of queries that must match
    */
  final case class MinimumMatch(qs: NonEmptyList[Query], num: Int) extends Query {
    def mapLastTerm(f: Query.Term => Query): MinimumMatch = this

    def traverseQ[G[_]: Applicative](f: TermQuery => G[Query]): G[Query] = {
      val gNelQ: G[NonEmptyList[Query]] = qs.traverse(q => q.traverseQ(f))
      Applicative[G].map(gNelQ)(qNel => this.copy(qs = qNel))
    }
  }

  /** A field query
    * Search for documents by applying the given query only to the named field
    * e.g. 'author:"Silly Goose"', 'title:(cats AND dogs)'
    *
    * @param field the field name
    * @param q the query
    */
  final case class Field(field: String, q: Query) extends Query {
    def mapLastTerm(f: Query.Term => Query): Field =
      Field(field, q.mapLastTerm(f))

    def traverseQ[G[_]: Applicative](f: TermQuery => G[Query]): G[Query] =
      Applicative[G].map(q.traverseQ(f))(newQ => this.copy(q = newQ))
  }

  sealed trait WildCardOp extends Product with Serializable
  object WildCardOp {
    case object SingleChar extends WildCardOp
    case object ManyChar extends WildCardOp
    case class Str(str: String) extends WildCardOp
  }

  final case class WildCard(ops: NonEmptyList[WildCardOp]) extends TermQuery

  private def rewriteLastTerm(
      qs: NonEmptyList[Query],
      f: Query.Term => Query,
  ): NonEmptyList[Query] =
    if (qs.size == 1) NonEmptyList.one(qs.head.mapLastTerm(f))
    else {
      val newT = qs.tail.init :+ qs.last.mapLastTerm(f)
      NonEmptyList(qs.head, newT)
    }
}
