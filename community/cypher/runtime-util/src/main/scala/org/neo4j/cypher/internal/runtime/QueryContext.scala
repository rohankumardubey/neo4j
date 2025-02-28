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
package org.neo4j.cypher.internal.runtime

import java.net.URL
import java.util.Optional
import org.neo4j.common.EntityType
import org.neo4j.configuration.Config
import org.neo4j.cypher.internal.expressions.SemanticDirection
import org.neo4j.cypher.internal.logical.plans.IndexOrder
import org.neo4j.cypher.internal.planner.spi.TokenContext
import org.neo4j.cypher.internal.profiling.KernelStatisticProvider
import org.neo4j.graphdb.Entity
import org.neo4j.graphdb.Path
import org.neo4j.internal.kernel.api.CursorFactory
import org.neo4j.internal.kernel.api.DefaultCloseListenable
import org.neo4j.internal.kernel.api.IndexReadSession
import org.neo4j.internal.kernel.api.KernelReadTracer
import org.neo4j.internal.kernel.api.NodeCursor
import org.neo4j.internal.kernel.api.NodeValueIndexCursor
import org.neo4j.internal.kernel.api.PropertyCursor
import org.neo4j.internal.kernel.api.PropertyIndexQuery
import org.neo4j.internal.kernel.api.Read
import org.neo4j.internal.kernel.api.RelationshipScanCursor
import org.neo4j.internal.kernel.api.RelationshipTraversalCursor
import org.neo4j.internal.kernel.api.RelationshipValueIndexCursor
import org.neo4j.internal.kernel.api.SchemaRead
import org.neo4j.internal.kernel.api.TokenRead
import org.neo4j.internal.kernel.api.TokenReadSession
import org.neo4j.internal.kernel.api.Write
import org.neo4j.internal.kernel.api.procs.ProcedureCallContext
import org.neo4j.internal.schema.ConstraintDescriptor
import org.neo4j.internal.schema.IndexConfig
import org.neo4j.internal.schema.IndexDescriptor
import org.neo4j.internal.schema.IndexProviderDescriptor
import org.neo4j.io.pagecache.context.CursorContext
import org.neo4j.kernel.GraphDatabaseQueryService
import org.neo4j.kernel.api.KernelTransaction
import org.neo4j.kernel.api.StatementConstants.NO_SUCH_NODE
import org.neo4j.kernel.database.NamedDatabaseId
import org.neo4j.kernel.impl.core.TransactionalEntityFactory
import org.neo4j.kernel.impl.factory.DbmsInfo
import org.neo4j.memory.EmptyMemoryTracker
import org.neo4j.memory.MemoryTracker
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.TextValue
import org.neo4j.values.storable.Value
import org.neo4j.values.virtual.NodeValue
import org.neo4j.values.virtual.RelationshipValue

import scala.collection.Iterator

/*
 * Developer note: This is an attempt at an internal graph database API, which defines a clean cut between
 * two layers, the query engine layer and, for lack of a better name, the core database layer.
 *
 * Building the query engine layer on top of an internal layer means we can move much faster, not
 * having to worry about deprecations and so on. It is also acceptable if this layer is a bit clunkier, in this
 * case we are, for instance, not exposing any node or relationship objects, but provide direct methods for manipulating
 * them by ids instead.
 *
 * The driver for this was clarifying who is responsible for ensuring query isolation. By exposing a query concept in
 * the core layer, we can move that responsibility outside of the scope of cypher.
 */
trait QueryContext extends TokenContext with DbAccess {

  // See QueryContextAdaptation if you need a dummy that overrides all methods as ??? for writing a test

  def entityAccessor: TransactionalEntityFactory

  def transactionalContext: QueryTransactionalContext

  def resources: ResourceManager

  def nodeOps: NodeOperations

  def relationshipOps: RelationshipOperations

  def createNode(labels: Array[Int]): NodeValue

  def createNodeId(labels: Array[Int]): Long

  def createRelationship(start: Long, end: Long, relType: Int): RelationshipValue

  def getOrCreateRelTypeId(relTypeName: String): Int

  def getRelationshipsForIds(node: Long, dir: SemanticDirection, types: Array[Int]): ClosingIterator[RelationshipValue]

  def getRelationshipsForIdsPrimitive(node: Long, dir: SemanticDirection, types: Array[Int]): ClosingLongIterator with RelationshipIterator

  def getRelationshipsByType(tokenReadSession: TokenReadSession,  relType: Int, indexOrder: IndexOrder): ClosingLongIterator with RelationshipIterator

  def nodeCursor(): NodeCursor

  def traversalCursor(): RelationshipTraversalCursor

  def getOrCreateLabelId(labelName: String): Int

  def getOrCreateTypeId(relTypeName: String): Int

  def setLabelsOnNode(node: Long, labelIds: Iterator[Int]): Int

  def removeLabelsFromNode(node: Long, labelIds: Iterator[Int]): Int

  def getOrCreatePropertyKeyId(propertyKey: String): Int

  def getOrCreatePropertyKeyIds(propertyKeys: Array[String]): Array[Int]

  def addBtreeIndexRule(entityId: Int, entityType: EntityType, propertyKeyIds: Seq[Int], name: Option[String], provider: Option[String], indexConfig: IndexConfig): IndexDescriptor

  def addLookupIndexRule(entityType: EntityType, name: Option[String]): IndexDescriptor

  def addFulltextIndexRule(entityIds: List[Int], entityType: EntityType, propertyKeyIds: Seq[Int], name: Option[String], provider: Option[IndexProviderDescriptor], indexConfig: IndexConfig): IndexDescriptor

  def dropIndexRule(labelId: Int, propertyKeyIds: Seq[Int]): Unit

  def dropIndexRule(name: String): Unit

  def getAllIndexes(): Map[IndexDescriptor, IndexInfo]

  def btreeIndexReference(entityId: Int, entityType: EntityType, properties: Int*): IndexDescriptor

  def lookupIndexReference(entityType: EntityType): IndexDescriptor

  def fulltextIndexReference(entityIds: List[Int], entityType: EntityType, properties: Int*): IndexDescriptor

  def indexExists(name: String): Boolean

  def constraintExists(name: String): Boolean

  def constraintExists(matchFn: ConstraintDescriptor => Boolean, entityId: Int, properties: Int*): Boolean

  def nodeIndexSeek(index: IndexReadSession,
                    needsValues: Boolean,
                    indexOrder: IndexOrder,
                    queries: Seq[PropertyIndexQuery]): NodeValueIndexCursor

  def nodeIndexSeekByContains(index: IndexReadSession,
                              needsValues: Boolean,
                              indexOrder: IndexOrder,
                              value: TextValue): NodeValueIndexCursor

  def nodeIndexSeekByEndsWith(index: IndexReadSession,
                              needsValues: Boolean,
                              indexOrder: IndexOrder,
                              value: TextValue): NodeValueIndexCursor

  def nodeIndexScan(index: IndexReadSession,
                    needsValues: Boolean,
                    indexOrder: IndexOrder): NodeValueIndexCursor

  def nodeLockingUniqueIndexSeek(index: IndexDescriptor, queries: Seq[PropertyIndexQuery.ExactPredicate]): NodeValueIndexCursor

  def relationshipIndexSeek(index: IndexReadSession,
                            needsValues: Boolean,
                            indexOrder: IndexOrder,
                            queries: Seq[PropertyIndexQuery]): RelationshipValueIndexCursor

  def relationshipIndexSeekByContains(index: IndexReadSession,
                                      needsValues: Boolean,
                                      indexOrder: IndexOrder,
                                      value: TextValue): RelationshipValueIndexCursor

  def relationshipIndexSeekByEndsWith(index: IndexReadSession,
                                      needsValues: Boolean,
                                      indexOrder: IndexOrder,
                                      value: TextValue): RelationshipValueIndexCursor

  def relationshipIndexScan(index: IndexReadSession,
                            needsValues: Boolean,
                            indexOrder: IndexOrder): RelationshipValueIndexCursor

  def getNodesByLabel(tokenReadSession: TokenReadSession, id: Int, indexOrder: IndexOrder): ClosingIterator[NodeValue]

  def getNodesByLabelPrimitive(tokenReadSession: TokenReadSession, id: Int, indexOrder: IndexOrder): ClosingLongIterator

  /* return true if the constraint was created, false if preexisting, throws if failed */
  def createNodeKeyConstraint(labelId: Int, propertyKeyIds: Seq[Int], name: Option[String], provider: Option[String], indexConfig: IndexConfig): Unit

  def dropNodeKeyConstraint(labelId: Int, propertyKeyIds: Seq[Int]): Unit

  /* return true if the constraint was created, false if preexisting, throws if failed */
  def createUniqueConstraint(labelId: Int, propertyKeyIds: Seq[Int], name: Option[String], provider: Option[String], indexConfig: IndexConfig): Unit

  def dropUniqueConstraint(labelId: Int, propertyKeyIds: Seq[Int]): Unit

  /* return true if the constraint was created, false if preexisting, throws if failed */
  def createNodePropertyExistenceConstraint(labelId: Int, propertyKeyId: Int, name: Option[String]): Unit

  def dropNodePropertyExistenceConstraint(labelId: Int, propertyKeyId: Int)

  /* return true if the constraint was created, false if preexisting, throws if failed */
  def createRelationshipPropertyExistenceConstraint(relTypeId: Int, propertyKeyId: Int, name: Option[String]): Unit

  def dropRelationshipPropertyExistenceConstraint(relTypeId: Int, propertyKeyId: Int)

  def dropNamedConstraint(name: String)

  def getAllConstraints(): Map[ConstraintDescriptor, ConstraintInfo]

  def getOptStatistics: Option[QueryStatistics] = None

  def getImportURL(url: URL): Either[String,URL]

  def nodeGetDegreeWithMax(maxDegree:Int, node: Long, dir: SemanticDirection, nodeCursor: NodeCursor): Int = dir match {
    case SemanticDirection.OUTGOING => nodeGetOutgoingDegreeWithMax(maxDegree, node, nodeCursor)
    case SemanticDirection.INCOMING => nodeGetIncomingDegreeWithMax(maxDegree, node, nodeCursor)
    case SemanticDirection.BOTH => nodeGetTotalDegreeWithMax(maxDegree, node, nodeCursor)
  }

  def nodeGetDegreeWithMax(maxDegree: Int, node: Long, dir: SemanticDirection, relTypeId: Int, nodeCursor: NodeCursor): Int = dir match {
    case SemanticDirection.OUTGOING => nodeGetOutgoingDegreeWithMax(maxDegree, node, relTypeId, nodeCursor)
    case SemanticDirection.INCOMING => nodeGetIncomingDegreeWithMax(maxDegree, node, relTypeId, nodeCursor)
    case SemanticDirection.BOTH => nodeGetTotalDegreeWithMax(maxDegree, node, relTypeId, nodeCursor)
  }
  def nodeGetDegree(node: Long, dir: SemanticDirection, nodeCursor: NodeCursor): Int = dir match {
    case SemanticDirection.OUTGOING => nodeGetOutgoingDegree(node, nodeCursor)
    case SemanticDirection.INCOMING => nodeGetIncomingDegree(node, nodeCursor)
    case SemanticDirection.BOTH => nodeGetTotalDegree(node, nodeCursor)
  }

  def nodeGetDegree(node: Long, dir: SemanticDirection, relTypeId: Int, nodeCursor: NodeCursor): Int = dir match {
    case SemanticDirection.OUTGOING => nodeGetOutgoingDegree(node, relTypeId, nodeCursor)
    case SemanticDirection.INCOMING => nodeGetIncomingDegree(node, relTypeId, nodeCursor)
    case SemanticDirection.BOTH => nodeGetTotalDegree(node, relTypeId, nodeCursor)
  }

  def nodeHasCheapDegrees(node: Long, nodeCursor: NodeCursor): Boolean

  def asObject(value: AnyValue): AnyRef

  def singleShortestPath(left: Long, right: Long, depth: Int, expander: Expander, pathPredicate: KernelPredicate[Path],
                         filters: Seq[KernelPredicate[Entity]], memoryTracker: MemoryTracker = EmptyMemoryTracker.INSTANCE): Option[Path]

  def allShortestPath(left: Long, right: Long, depth: Int, expander: Expander, pathPredicate: KernelPredicate[Path],
                      filters: Seq[KernelPredicate[Entity]], memoryTracker: MemoryTracker = EmptyMemoryTracker.INSTANCE): ClosingIterator[Path]

  def lockNodes(nodeIds: Long*)

  def lockRelationships(relIds: Long*)

  def callReadOnlyProcedure(id: Int, args: Array[AnyValue], allowed: Array[String], context: ProcedureCallContext): Iterator[Array[AnyValue]]

  def callReadWriteProcedure(id: Int, args: Array[AnyValue], allowed: Array[String], context: ProcedureCallContext): Iterator[Array[AnyValue]]

  def callSchemaWriteProcedure(id: Int, args: Array[AnyValue], allowed: Array[String], context: ProcedureCallContext): Iterator[Array[AnyValue]]

  def callDbmsProcedure(id: Int, args: Array[AnyValue], allowed: Array[String], context: ProcedureCallContext): Iterator[Array[AnyValue]]

  def aggregateFunction(id: Int, allowed: Array[String]): UserDefinedAggregator

  def graph(): GraphDatabaseQueryService

  /**
   * Delete the node with the specified id and all of its relationships and return the number of deleted relationships.
   *
   * Note, caller needs to make sure the node is not already deleted or else the count will be incorrect.
   *
   * @return number of deleted relationships
   */
  def detachDeleteNode(id: Long): Int

  def assertSchemaWritesAllowed(): Unit

  def getConfig: Config

  def assertShowIndexAllowed(): Unit

  def assertShowConstraintAllowed(): Unit

  override def nodeById(id: Long): NodeValue = nodeOps.getById(id)

  override def relationshipById(id: Long): RelationshipValue = relationshipOps.getById(id)

  override def propertyKey(name: String): Int = transactionalContext.tokenRead.propertyKey(name)

  override def nodeLabel(name: String): Int = transactionalContext.tokenRead.nodeLabel(name)

  override def relationshipType(name: String): Int = transactionalContext.tokenRead.relationshipType(name)

  override def relationshipTypeName(token: Int): String = transactionalContext.tokenRead.relationshipTypeName(token)

  override def nodeProperty(node: Long,
                            property: Int,
                            nodeCursor: NodeCursor,
                            propertyCursor: PropertyCursor,
                            throwOnDeleted: Boolean): Value =
    nodeOps.getProperty(node, property, nodeCursor, propertyCursor, throwOnDeleted)

  override def nodePropertyIds(node: Long,
                               nodeCursor: NodeCursor,
                               propertyCursor: PropertyCursor): Array[Int] =
    nodeOps.propertyKeyIds(node, nodeCursor, propertyCursor)

  override def nodeHasProperty(node: Long,
                               property: Int,
                               nodeCursor: NodeCursor,
                               propertyCursor: PropertyCursor): Boolean =
    nodeOps.hasProperty(node, property, nodeCursor, propertyCursor)

  override def relationshipProperty(relationship: Long,
                                    property: Int,
                                    relationshipScanCursor: RelationshipScanCursor,
                                    propertyCursor: PropertyCursor,
                                    throwOnDeleted: Boolean): Value =
    relationshipOps.getProperty(relationship, property, relationshipScanCursor, propertyCursor, throwOnDeleted)

  override def relationshipPropertyIds(relationship: Long,
                                       relationshipScanCursor: RelationshipScanCursor,
                                       propertyCursor: PropertyCursor): Array[Int] =
    relationshipOps.propertyKeyIds(relationship, relationshipScanCursor, propertyCursor)

  override def relationshipHasProperty(relationship: Long,
                                       property: Int,
                                       relationshipScanCursor: RelationshipScanCursor,
                                       propertyCursor: PropertyCursor): Boolean =
    relationshipOps.hasProperty(relationship, property, relationshipScanCursor, propertyCursor)

  override def hasTxStatePropertyForCachedNodeProperty(nodeId: Long, propertyKeyId: Int): Optional[java.lang.Boolean] = {
    nodeOps.hasTxStatePropertyForCachedProperty(nodeId, propertyKeyId) match {
      case None => Optional.empty()
      case Some(bool) => Optional.of(bool)
    }
  }

  override def hasTxStatePropertyForCachedRelationshipProperty(relId: Long, propertyKeyId: Int): Optional[java.lang.Boolean] = {
    relationshipOps.hasTxStatePropertyForCachedProperty(relId, propertyKeyId)match {
      case None => Optional.empty()
      case Some(bool) => Optional.of(bool)
    }
  }

  def createExpressionCursors(): ExpressionCursors = {
    val transactionMemoryTracker = transactionalContext.transaction.memoryTracker()
    val cursors = new ExpressionCursors(transactionalContext.cursors, transactionalContext.cursorContext, transactionMemoryTracker)
    resources.trace(cursors)
    cursors
  }
}

trait Operations[T, CURSOR] {
  /**
   * Delete entity
   *
   * @return true if something was deleted, false if no entity was found for this id
   */
  def delete(id: Long): Boolean

  def setProperty(obj: Long, propertyKeyId: Int, value: Value)

  def removeProperty(obj: Long, propertyKeyId: Int): Boolean

  /**
   * @param throwOnDeleted if this is `true` an Exception will be thrown when the entity with id `obj` has been deleted in this transaction.
   *                       If this is `false`, it will return `Values.NO_VALUE` in that case.
   */
  def getProperty(obj: Long, propertyKeyId: Int, cursor: CURSOR, propertyCursor: PropertyCursor, throwOnDeleted: Boolean): Value

  def hasProperty(obj: Long, propertyKeyId: Int, cursor: CURSOR, propertyCursor: PropertyCursor): Boolean

  /**
   * @return `null` if there are no changes.
   *         `NO_VALUE` if the property was deleted.
   *         `v` if the property was set to v
   * @throws org.neo4j.exceptions.EntityNotFoundException if the node was deleted
   */
  def getTxStateProperty(obj: Long, propertyKeyId: Int): Value

  /**
   * @return `None` if TxState has no changes.
   *         `Some(true)` if the property was changed.
   *         `Some(false)` if the property or the entity were deleted in TxState.
   */
  def hasTxStatePropertyForCachedProperty(entityId: Long, propertyKeyId: Int): Option[Boolean]

  def propertyKeyIds(obj: Long, cursor: CURSOR, propertyCursor: PropertyCursor): Array[Int]

  def getById(id: Long): T

  def isDeletedInThisTx(id: Long): Boolean

  def all: ClosingIterator[T]

  def allPrimitive: ClosingLongIterator

  def acquireExclusiveLock(obj: Long): Unit

  def releaseExclusiveLock(obj: Long): Unit

  def getByIdIfExists(id: Long): Option[T]
}

trait NodeOperations extends Operations[NodeValue, NodeCursor]

trait RelationshipOperations extends Operations[RelationshipValue, RelationshipScanCursor]

trait QueryTransactionalContext extends CloseableResource {

  def transaction : KernelTransaction

  def cursors : CursorFactory

  def cursorContext: CursorContext

  def dataRead: Read

  def tokenRead: TokenRead

  def schemaRead: SchemaRead

  def dataWrite: Write

  def isTopLevelTx: Boolean

  def close()

  def rollback()

  def commitAndRestartTx()

  def kernelStatisticProvider: KernelStatisticProvider

  def dbmsInfo: DbmsInfo

  def databaseId: NamedDatabaseId
}

trait KernelPredicate[T] {
  def test(obj: T): Boolean
}

trait Expander {
  def addRelationshipFilter(newFilter: KernelPredicate[Entity]): Expander
  def addNodeFilter(newFilter: KernelPredicate[Entity]): Expander
  def nodeFilters: Seq[KernelPredicate[Entity]]
  def relFilters: Seq[KernelPredicate[Entity]]
}

trait UserDefinedAggregator {
  def update(args: IndexedSeq[AnyValue]): Unit
  def result: AnyValue
}

trait CloseableResource extends AutoCloseable {
  def close(): Unit
}

object NodeValueHit {
  val EMPTY = new NodeValueHit(NO_SUCH_NODE, null, null)
}

class NodeValueHit(val nodeId: Long, val values: Array[Value], read: Read) extends DefaultCloseListenable with NodeValueIndexCursor {

  private var _next = nodeId != -1L

  override def numberOfProperties(): Int = values.length

  override def hasValue: Boolean = true

  override def propertyValue(offset: Int): Value = values(offset)

  override def node(cursor: NodeCursor): Unit = {
    read.singleNode(nodeId, cursor)
  }

  override def nodeReference(): Long = nodeId

  override def next(): Boolean = {
    val temp = _next
    _next = false
    temp
  }

  override def closeInternal(): Unit = _next = false

  override def isClosed: Boolean = _next

  override def score(): Float = Float.NaN

  //this cursor doesn't need tracing since all values has already been read.
  override def setTracer(tracer: KernelReadTracer): Unit = {}
  override def removeTracer(): Unit = {}
}
