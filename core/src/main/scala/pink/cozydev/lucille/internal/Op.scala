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

package pink.cozydev.lucille.internal

import pink.cozydev.lucille.Query
import scala.collection.mutable.ListBuffer

private[lucille] sealed trait Op extends Product with Serializable

private[lucille] object Op {
  case object OR extends Op
  case object AND extends Op

  /** Associates a starting query and a list of OP-Query pairs.
    *
    * @param first First query in sequence of 'firstQ OP query OP query'
    * @param opQs List of OP-Query pairs
    * @return A Single top level `Or`/`And` query
    */
  def associateOps(first: Query, opQs: List[(Op, Query)]): Query =
    opQs match {
      case Nil => first
      case (headOp, headQ) :: remaining =>
        var currentOp = headOp
        var currentQ = headQ

        // We'll collect queries in 'tempAccumulator' while successive operators are the same type
        // e.g. (OR, q1), (OR, q2), (OR, q3), ...
        val tempAccumulator = ListBuffer.empty[Query]
        tempAccumulator += first

        // When successive operators change type, we clear 'tempAccumulator' and add them to 'bldr'
        // e.g. (OR, q1), (AND, q2), ...
        val bldr = ListBuffer.empty[Query]

        // Iterate through Op-Query pairs, looking "one ahead" to decide how to process 'currentQ'
        remaining.foreach { case (nextOp, nextQ) =>
          if (currentOp == nextOp) {
            // 'nextOp' hasn't changed, keep accumulating
            tempAccumulator += currentQ
          } else {
            // 'nextOp' is different from 'currentOp', so we're going to collapse the queries we've
            // accumulated so far into an AND/OR query before continuing.
            // How we do that depends on the precedence of the operator we're switching to.
            // AND has higher precedence than OR, so if we are switching from OR to AND, we
            // collapse before accumulating 'currentQ' and instead add it to the newly cleared
            // accumulator.
            nextOp match {
              case AND =>
                // OR -> AND
                // e.g. previousQ OR (currentQ AND nextQ)
                // From OR to AND, collapse now, new AND gets currentQ
                val qs = tempAccumulator.result()
                tempAccumulator.clear()
                bldr ++= qs
                tempAccumulator += currentQ
              case OR =>
                // AND -> OR
                // e.g. (previousQ AND currentQ) OR nextQ
                // From AND to OR, add currentQ before collapsing
                tempAccumulator += currentQ
                val qs = tempAccumulator.result()
                tempAccumulator.clear()

                bldr += Query.And.fromListUnsafe(qs)
            }
          }
          // get ready for next iteration
          currentOp = nextOp
          currentQ = nextQ
        }

        // We're done iterating
        // But because we were looking one ahead, we still have not processed the last 'currentQ'.
        // It's safe to add 'currentQ' to 'tempAccumulator', it's either already collecting queries
        // for 'currentOp', or we've just cleared it for a new Op type.
        tempAccumulator += currentQ
        val innerQs = tempAccumulator.result()
        currentOp match {
          case AND =>
            // Final OP was an AND, collapse into one AND query, add to 'bldr'
            bldr += Query.And.fromListUnsafe(innerQs)
          case OR =>
            // Final OP was an OR, directly add to 'bldr'.
            // Safe because all the ANDs have been grouped together.
            // Wrapping in an OR query would create unnecessary nesting.
            bldr ++= innerQs
        }
        val finalQs = bldr.result()
        // If we only have one query, it must be an AND query, directly return that.
        // Otherwise we wrap our multiple queries in an OR query.
        if (finalQs.size == 1) finalQs.head else Query.Or.fromListUnsafe(finalQs)
    }

}
