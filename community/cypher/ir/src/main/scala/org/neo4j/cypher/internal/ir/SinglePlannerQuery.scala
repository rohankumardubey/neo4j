/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.ir

import org.neo4j.cypher.internal.ast.Hint
import org.neo4j.cypher.internal.expressions.LabelName
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.ir.SinglePlannerQuery.extractLabelInfo
import org.neo4j.cypher.internal.ir.SinglePlannerQuery.reverseProjectedInterestingOrder
import org.neo4j.cypher.internal.ir.ordering.InterestingOrder
import org.neo4j.exceptions.InternalException

import scala.annotation.tailrec
import scala.util.hashing.MurmurHash3

/**
 * A linked list of queries, each made up of, a query graph (MATCH ... WHERE ...), a required order, a horizon (WITH ...) and a pointer to the next query.
 */
trait SinglePlannerQuery extends PlannerQueryPart {

  /**
   * Optionally, an input to the query provided using INPUT DATA STREAM. These are the column names provided by IDS.
   */
  val queryInput: Option[Seq[String]]
  /**
   * The part of query from a MATCH/MERGE/CREATE until (excluding) the next WITH/RETURN.
   */
  val queryGraph: QueryGraph
  /**
   * The required order of a query graph and its horizon. The required order emerges from an ORDER BY or aggregation or distinct.
   */
  val interestingOrder: InterestingOrder

  /**
   * The WITH/RETURN part of a query
   */
  val horizon: QueryHorizon
  /**
   * Optionally, a next PlannerQuery for everything after the WITH in the current horizon.
   */
  val tail: Option[SinglePlannerQuery]

  def dependencies: Set[String]

  def readOnlySelf: Boolean = queryGraph.readOnly && horizon.readOnly
  override def readOnly: Boolean = readOnlySelf && tail.forall(_.readOnly)

  def last: SinglePlannerQuery = tail.map(_.last).getOrElse(this)

  def lastQueryGraph: QueryGraph = last.queryGraph
  def lastQueryHorizon: QueryHorizon = last.horizon

  def withTail(newTail: SinglePlannerQuery): SinglePlannerQuery = tail match {
    case None => copy(tail = Some(newTail))
    case Some(_) => throw new InternalException("Attempt to set a second tail on a query graph")
  }

  def withoutTail: SinglePlannerQuery = tail match {
    case None => this
    case _ => copy(tail = None)
  }

  def withoutLast: Option[SinglePlannerQuery] = tail match {
    case Some(tt) if tt.tail.isEmpty => Some(copy(tail = None))
    case Some(tt) => Some(copy(tail = Some(tt.withoutLast.get)))
    case None => None
  }

  def withInput(queryInput: Seq[String]): SinglePlannerQuery =
    copy(input = Some(queryInput), queryGraph = queryGraph.copy(argumentIds = queryGraph.argumentIds ++ queryInput))

  override def withoutHints(hintsToIgnore: Set[Hint]): SinglePlannerQuery = {
    copy(
      queryGraph = queryGraph.withoutHints(hintsToIgnore),
      horizon = horizon.withoutHints(hintsToIgnore),
      tail = tail.map(x => x.withoutHints(hintsToIgnore))
    )
  }

  def withHorizon(horizon: QueryHorizon): SinglePlannerQuery = copy(horizon = horizon)

  def withQueryGraph(queryGraph: QueryGraph): SinglePlannerQuery = copy(queryGraph = queryGraph)

  def withInterestingOrder(interestingOrder: InterestingOrder): SinglePlannerQuery =
    copy(interestingOrder = interestingOrder)

  /**
   * Sets an interestingOrder on the last part of this query and also propagates it to previous query parts.
   */
  def withTailInterestingOrder(interestingOrder: InterestingOrder): SinglePlannerQuery = {
    def f(plannerQuery: SinglePlannerQuery): (SinglePlannerQuery, InterestingOrder) = {
      plannerQuery.tail match {
        case None => (plannerQuery.copy(interestingOrder = interestingOrder), interestingOrder.asInteresting)
        case Some(q) =>
          val (newTail, tailOrder) = f(q)
          if (plannerQuery.interestingOrder.isEmpty) {
            val reverseProjected = reverseProjectedInterestingOrder(tailOrder, plannerQuery.horizon, newTail.queryGraph.argumentIds)
            (plannerQuery.copy(interestingOrder = reverseProjected, tail = Some(newTail)), reverseProjected)
          } else
            (plannerQuery.copy(tail = Some(newTail)), InterestingOrder.empty)
      }
    }

    f(this)._1
  }

  /**
   * First interesting order with non-empty required order that is usable by the current query part.
   */
  def findFirstRequiredOrder: Option[InterestingOrder] = {
    if (interestingOrder.requiredOrderCandidate.nonEmpty) {
      Some(interestingOrder)
    } else {
      tail.flatMap { nextPart =>
        nextPart
          .findFirstRequiredOrder
          .map(reverseProjectedInterestingOrder(_, horizon, nextPart.queryGraph.argumentIds))
          .filter(_.requiredOrderCandidate.nonEmpty)
      }
    }
  }

  def isCoveredByHints(other: SinglePlannerQuery): Boolean = allHints.forall(other.allHints.contains)

  override def allHints: Set[Hint] = {
    val headHints = queryGraph.allHints ++ horizon.allHints
    tail.fold(headHints)(_.allHints ++ headHints)
  }

  override def numHints: Int = allHints.size

  def amendQueryGraph(f: QueryGraph => QueryGraph): SinglePlannerQuery = withQueryGraph(f(queryGraph))

  def updateHorizon(f: QueryHorizon => QueryHorizon): SinglePlannerQuery = withHorizon(f(horizon))

  def updateQueryProjection(f: QueryProjection => QueryProjection): SinglePlannerQuery = horizon match {
    case projection: QueryProjection => withHorizon(f(projection))
    case _ => throw new InternalException("Tried updating projection when there was no projection there")
  }

  def updateTail(f: SinglePlannerQuery => SinglePlannerQuery): SinglePlannerQuery = tail match {
    case None => this
    case Some(tailQuery) => copy(tail = Some(f(tailQuery)))
  }

  def updateTailOrSelf(f: SinglePlannerQuery => SinglePlannerQuery): SinglePlannerQuery = tail match {
    case None => f(this)
    case Some(_) => this.updateTail(_.updateTailOrSelf(f))
  }

  def tailOrSelf: SinglePlannerQuery = tail match {
    case None => this
    case Some(t) => t.tailOrSelf
  }

  def exists(f: SinglePlannerQuery => Boolean): Boolean =
    f(this) || tail.exists(_.exists(f))

  def ++(other: SinglePlannerQuery): SinglePlannerQuery = {
    (this.horizon, other.horizon) match {
      case (a: RegularQueryProjection, b: RegularQueryProjection) =>
        RegularSinglePlannerQuery(
          queryGraph = queryGraph ++ other.queryGraph,
          interestingOrder = interestingOrder,
          horizon = a ++ b,
          tail = either(tail, other.tail),
          queryInput = either(queryInput, other.queryInput))

      case _ =>
        throw new InternalException("Tried to concatenate non-regular query projections")
    }
  }

  private def either[T](a: Option[T], b: Option[T]): Option[T] = (a, b) match {
    case (Some(aa), Some(bb)) => throw new InternalException(s"Can't join two query graphs. First: $aa, Second: $bb")
    case (s@Some(_), None) => s
    case (None, s) => s
  }

  // This is here to stop usage of copy from the outside
  protected def copy(queryGraph: QueryGraph = queryGraph,
                     interestingOrder: InterestingOrder = interestingOrder,
                     horizon: QueryHorizon = horizon,
                     tail: Option[SinglePlannerQuery] = tail,
                     input: Option[Seq[String]] = queryInput): SinglePlannerQuery

  def foldMap(f: (SinglePlannerQuery, SinglePlannerQuery) => SinglePlannerQuery): SinglePlannerQuery = tail match {
    case None => this
    case Some(oldTail) =>
      val newTail = f(this, oldTail)
      copy(tail = Some(newTail.foldMap(f)))
  }

  def fold[A](in: A)(f: (A, SinglePlannerQuery) => A): A = {

    @tailrec
    def recurse(acc: A, pq: SinglePlannerQuery): A = {
      val nextAcc = f(acc, pq)

      pq.tail match {
        case Some(tailPQ) => recurse(nextAcc, tailPQ)
        case None => nextAcc
      }
    }

    recurse(in, this)
  }

  override lazy val allQGsWithLeafInfo: Seq[QgWithLeafInfo] = allPlannerQueries.flatMap(q => q.queryGraph.allQGsWithLeafInfo ++ q.horizon.allQueryGraphs)

  //Returns list of planner query and all of its tails
  def allPlannerQueries: Seq[SinglePlannerQuery] = {
    val buffer = scala.collection.mutable.ArrayBuffer[SinglePlannerQuery]()
    var current = this
    while (current != null) {
      buffer += current
      current = current.tail.orNull
    }
    buffer
  }

  lazy val firstLabelInfo: Map[String, Set[LabelName]] =
    extractLabelInfo(this)

  lazy val lastLabelInfo: Map[String, Set[LabelName]] =
    extractLabelInfo(last)

  override def returns: Set[String] = {
    lastQueryHorizon match {
      case projection: QueryProjection => projection.keySet
      case _ => Set.empty
    }
  }

  override def asSinglePlannerQuery: SinglePlannerQuery = this
}

object SinglePlannerQuery {
  def empty: RegularSinglePlannerQuery = RegularSinglePlannerQuery()

  def coveredIdsForPatterns(patternNodeIds: Set[String], patternRels: Set[PatternRelationship]): Set[String] = {
    val patternRelIds = patternRels.flatMap(_.coveredIds)
    patternNodeIds ++ patternRelIds
  }

  /**
   * Rename and filter the columns in an interesting order to before a given horizon.
   *
   * @param order       the InterestingOrder
   * @param horizon     the horizon
   * @param argumentIds the arguments to the next query part
   */
  def reverseProjectedInterestingOrder(order: InterestingOrder, horizon: QueryHorizon, argumentIds: Set[String]): InterestingOrder = {
    horizon match {
      case qp: QueryProjection => order.withReverseProjectedColumns(qp.projections, argumentIds)
      case _ => order.withReverseProjectedColumns(Map.empty, argumentIds)
    }
  }

  def extractLabelInfo(q: SinglePlannerQuery) : Map[String, Set[LabelName]] = {
    val labelInfo = q.queryGraph.selections.labelInfo
    val projectedLabelInfo = q.horizon match {
      case projection: QueryProjection =>
        projection.projections.collect {
          case (projectedName, Variable(name)) if labelInfo.contains(name) =>
            projectedName -> labelInfo(name)
        }
      case _ => Map.empty[String, Set[LabelName]]
    }
    labelInfo ++ projectedLabelInfo
  }
}

case class RegularSinglePlannerQuery(queryGraph: QueryGraph = QueryGraph.empty,
                                     interestingOrder: InterestingOrder = InterestingOrder.empty,
                                     horizon: QueryHorizon = QueryProjection.empty,
                                     tail: Option[SinglePlannerQuery] = None,
                                     queryInput: Option[Seq[String]] = None) extends SinglePlannerQuery {

  // This is here to stop usage of copy from the outside
  override protected def copy(queryGraph: QueryGraph = queryGraph,
                              interestingOrder: InterestingOrder = interestingOrder,
                              horizon: QueryHorizon = horizon,
                              tail: Option[SinglePlannerQuery] = tail,
                              queryInput: Option[Seq[String]] = queryInput): SinglePlannerQuery =
    RegularSinglePlannerQuery(queryGraph, interestingOrder, horizon, tail, queryInput)

  override def dependencies: Set[String] = horizon.dependencies ++ queryGraph.dependencies ++ tail.map(_.dependencies).getOrElse(Set.empty)

  override def canEqual(that: Any): Boolean = that.isInstanceOf[RegularSinglePlannerQuery]

  override def equals(other: Any): Boolean = other match {
    // Make sure it corresponds with pointOutDifference
    case that: RegularSinglePlannerQuery =>
      (that canEqual this) &&
        queryInput == that.queryInput &&
        queryGraph == that.queryGraph &&
        horizon == that.horizon &&
        tail == that.tail &&
        interestingOrder.requiredOrderCandidate.order == that.interestingOrder.requiredOrderCandidate.order
    case _ => false
  }

  private var theHashCode: Int = -1

  override def hashCode(): Int = {
    if (theHashCode == -1) {
      val state = Seq(queryInput, queryGraph, horizon, tail, interestingOrder.requiredOrderCandidate.order)
      theHashCode = MurmurHash3.seqHash(state)
    }
    theHashCode
  }

  def pointOutDifference(other: RegularSinglePlannerQuery): String = {
    // Make sure it corresponds with equals
    val builder = StringBuilder.newBuilder
    builder.append("Differences:\n")
    if (queryInput != other.queryInput) {
      builder.append(" - QueryInput\n")
      builder.append(s"    A: $queryInput\n")
      builder.append(s"    B: ${other.queryInput}\n")
    }
    if (queryGraph != other.queryGraph) {
      builder.append(" - QueryGraph\n")
      builder.append(s"    A: $queryGraph\n")
      builder.append(s"    B: ${other.queryGraph}\n")

    }
    if (horizon != other.horizon) {
      builder.append(" - Horizon\n")
      builder.append(s"    A: $horizon\n")
      builder.append(s"    B: ${other.horizon}\n")
    }
    if (tail != other.tail) {
      builder.append(" - Tail\n")
      builder.append(s"    A: $tail\n")
      builder.append(s"    B: ${other.tail}\n")
    }
    if (interestingOrder.requiredOrderCandidate.order != other.interestingOrder.requiredOrderCandidate.order) {
      builder.append(" - interestingOrder.requiredOrderCandidate.order\n")
      builder.append(s"    A: ${interestingOrder.requiredOrderCandidate.order}\n")
      builder.append(s"    B: ${other.interestingOrder.requiredOrderCandidate.order}\n")
    }
    builder.toString()
  }
}
