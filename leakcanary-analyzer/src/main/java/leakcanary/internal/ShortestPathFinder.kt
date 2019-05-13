/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package leakcanary.internal

import leakcanary.AnalyzerProgressListener
import leakcanary.AnalyzerProgressListener.Step.CALCULATING_RETAINED_SIZE
import leakcanary.AnalyzerProgressListener.Step.FINDING_DOMINATORS
import leakcanary.AnalyzerProgressListener.Step.FINDING_SHORTEST_PATHS
import leakcanary.CanaryLog
import leakcanary.Exclusion
import leakcanary.Exclusion.ExclusionType.InstanceFieldExclusion
import leakcanary.Exclusion.ExclusionType.StaticFieldExclusion
import leakcanary.Exclusion.ExclusionType.ThreadExclusion
import leakcanary.Exclusion.Status
import leakcanary.Exclusion.Status.NEVER_REACHABLE
import leakcanary.Exclusion.Status.WEAKLY_REACHABLE
import leakcanary.ExclusionsFactory
import leakcanary.HeapValue
import leakcanary.HeapValue.ObjectReference
import leakcanary.HprofParser
import leakcanary.HprofReader
import leakcanary.LeakNode
import leakcanary.LeakNode.ChildNode
import leakcanary.LeakNode.RootNode
import leakcanary.LeakReference
import leakcanary.LeakTraceElement.Type.ARRAY_ENTRY
import leakcanary.LeakTraceElement.Type.INSTANCE_FIELD
import leakcanary.LeakTraceElement.Type.STATIC_FIELD
import leakcanary.ObjectIdMetadata.CLASS
import leakcanary.ObjectIdMetadata.EMPTY_INSTANCE
import leakcanary.ObjectIdMetadata.PRIMITIVE_ARRAY_OR_WRAPPER_ARRAY
import leakcanary.ObjectIdMetadata.PRIMITIVE_WRAPPER
import leakcanary.ObjectIdMetadata.STRING
import leakcanary.Record
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ClassDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.InstanceDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.ObjectArrayDumpRecord
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.BooleanArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ByteArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.CharArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.DoubleArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.FloatArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.IntArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.LongArrayDump
import leakcanary.Record.HeapDumpRecord.ObjectRecord.PrimitiveArrayDumpRecord.ShortArrayDump
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.PriorityQueue
import kotlin.reflect.KClass

/**
 * Not thread safe.
 *
 * Finds the shortest path from leaking references to a gc root, ignoring excluded
 * refs first and then including the ones that are not "always ignorable" as needed if no path is
 * found.
 *
 * Skips enqueuing strings as an optimization, so if the leaking reference is a string then it will
 * never be found.
 */
internal class ShortestPathFinder {

  /**
   * A segmented FIFO queue. The queue is segmented by [Status]. Within each segment the elements
   * are ordered FIFO.
   */
  private val toVisitQueue = PriorityQueue<LeakNode>(1024, Comparator { node1, node2 ->
    val priorityComparison = toVisitMap[node1.instance]!!.compareTo(toVisitMap[node2.instance]!!)
    if (priorityComparison != 0) {
      priorityComparison
    } else {
      node1.visitOrder.compareTo(node2.visitOrder)
    }
  })
  /** Set of instances to visit */
  private val toVisitMap = LinkedHashMap<Long, Status>()
  private val visitedSet = LinkedHashSet<Long>()
  private lateinit var referentMap: Map<Long, KeyedWeakReferenceMirror>
  private var visitOrder = 0
  /**
   * Instances that cannot be dominated by a leaking instance because they're dominated by an
   * ancestor of a leaking instance.
   */
  private val undominatedSet = LinkedHashSet<Long>()
  /**
   * Map of instances to their leaking dominator.
   */
  private val dominatedInstances = LinkedHashMap<Long, Long>()

  internal data class Result(
    val leakingNode: LeakNode,
    val exclusionStatus: Status?,
    val weakReference: KeyedWeakReferenceMirror,
    val retainedHeapSize: Int?
  )

  fun findPaths(
    parser: HprofParser,
    exclusionsFactory: ExclusionsFactory,
    leakingWeakRefs: List<KeyedWeakReferenceMirror>,
    gcRootIds: MutableList<Long>,
    computeRetainedHeapSize: Boolean,
    listener: AnalyzerProgressListener
  ): List<Result> {
    listener.onProgressUpdate(FINDING_SHORTEST_PATHS)
    clearState()

    val fieldNameByClassName = mutableMapOf<String, MutableMap<String, Exclusion>>()
    val staticFieldNameByClassName = mutableMapOf<String, MutableMap<String, Exclusion>>()
    // TODO Use thread name exclusions
    val threadNames = mutableMapOf<String, Exclusion>()

    exclusionsFactory(parser)
        .forEach { exclusion ->

          when (exclusion.type) {
            is ThreadExclusion -> {
              threadNames[exclusion.type.threadName] = exclusion
            }
            is StaticFieldExclusion -> {
              val mapOrNull = staticFieldNameByClassName[exclusion.type.className]
              val map = if (mapOrNull != null) mapOrNull else {
                val newMap = mutableMapOf<String, Exclusion>()
                staticFieldNameByClassName[exclusion.type.className] = newMap
                newMap
              }
              map[exclusion.type.fieldName] = exclusion
            }
            is InstanceFieldExclusion -> {
              val mapOrNull = fieldNameByClassName[exclusion.type.className]
              val map = if (mapOrNull != null) mapOrNull else {
                val newMap = mutableMapOf<String, Exclusion>()
                fieldNameByClassName[exclusion.type.className] = newMap
                newMap
              }
              map[exclusion.type.fieldName] = exclusion
            }
          }
        }

    // Referent object id to weak ref mirror
    referentMap = leakingWeakRefs.associateBy { it.referent.value }

    enqueueGcRoots(parser, gcRootIds)
    gcRootIds.clear()

    var lowestPriority = ALWAYS_REACHABLE
    val resultsFound = mutableListOf<Result>()
    visitingQueue@ while (!toVisitQueue.isEmpty()) {
      val node = toVisitQueue.poll()!!
      val priority = toVisitMap[node.instance]!!
      // Lowest priority has the highest value
      if (priority > lowestPriority) {
        lowestPriority = priority
      }

      toVisitMap.remove(node.instance)

      if (checkSeen(node)) {
        continue
      }

      val weakReference = referentMap[node.instance]
      if (weakReference != null) {
        val exclusionPriority = if (lowestPriority == ALWAYS_REACHABLE) null else lowestPriority
        resultsFound.add(Result(node, exclusionPriority, weakReference, null))
        // Found all refs, stop searching (unless computing retained size which stops on weak reachables)
        if (resultsFound.size == leakingWeakRefs.size) {
          if (computeRetainedHeapSize && lowestPriority < WEAKLY_REACHABLE) {
            listener.onProgressUpdate(FINDING_DOMINATORS)
          } else {
            break@visitingQueue
          }
        }
      }

      when (val record = parser.retrieveRecordById(node.instance)) {
        is ClassDumpRecord -> visitClassRecord(
            parser, record, node, staticFieldNameByClassName, computeRetainedHeapSize
        )
        is InstanceDumpRecord -> visitInstanceRecord(
            parser, record, node, fieldNameByClassName, computeRetainedHeapSize
        )
        is ObjectArrayDumpRecord -> visitObjectArrayRecord(
            parser, record, node, computeRetainedHeapSize
        )
      }
    }

    val results = if (computeRetainedHeapSize) {
      listener.onProgressUpdate(CALCULATING_RETAINED_SIZE)
      // TODO Check out NativeRegistryPostProcessor in perflib for native size computation
      // TODO Figure out how bitmaps native memory is accounted for in perflib

      val retainedCounts = LinkedHashMap<KClass<out Record>, Int>().withDefault { 0 }

      val retainedSizes = LinkedHashMap<Long, Int>().withDefault { 0 }
      dominatedInstances.forEach { (instanceId, dominatorId) ->
        var retainedSize = retainedSizes.getValue(dominatorId)
        val record = parser.retrieveRecordById(instanceId)
        val kClass = record::class
        retainedCounts[kClass] = retainedCounts.getValue(kClass) + 1
        retainedSize += when (record) {
          is InstanceDumpRecord -> {
            val classRecord = parser.retrieveRecordById(record.classId) as ClassDumpRecord
            // Note: instanceSize is the sum of shallow size through the class hierarchy
            classRecord.instanceSize
          }
          is ObjectArrayDumpRecord -> record.elementIds.size * parser.idSize
          is BooleanArrayDump -> record.array.size * HprofReader.BOOLEAN_SIZE
          is CharArrayDump -> record.array.size * HprofReader.CHAR_SIZE
          is FloatArrayDump -> record.array.size * HprofReader.FLOAT_SIZE
          is DoubleArrayDump -> record.array.size * HprofReader.DOUBLE_SIZE
          is ByteArrayDump -> record.array.size * HprofReader.BYTE_SIZE
          is ShortArrayDump -> record.array.size * HprofReader.SHORT_SIZE
          is IntArrayDump -> record.array.size * HprofReader.INT_SIZE
          is LongArrayDump -> record.array.size * HprofReader.LONG_SIZE
          else -> {
            throw IllegalStateException("Unexpected record $record")
          }
        }
        retainedSizes[dominatorId] = retainedSize
      }

      resultsFound.forEach { result ->
        val leakingInstanceId = result.weakReference.referent.value
        val instanceRecord = parser.retrieveRecordById(leakingInstanceId) as InstanceDumpRecord
        val classRecord = parser.retrieveRecordById(instanceRecord.classId) as ClassDumpRecord
        var retainedSize = retainedSizes.getValue(leakingInstanceId)
        retainedSize += classRecord.instanceSize
        retainedSizes[leakingInstanceId] = retainedSize
      }
      retainedCounts[InstanceDumpRecord::class] =
        retainedCounts.getValue(InstanceDumpRecord::class) + resultsFound.size

      // TODO Replace dominators, because the leak is coming from
      // the top instance and the closest one matters less
      // This is just an infinite loop that keeps running until no more changes

      // TODO Add this to the result, use an enum, split out for each result
      CanaryLog.d("Retained counts: $retainedCounts")

      resultsFound.map { result ->
        result.copy(retainedHeapSize = retainedSizes[result.weakReference.referent.value])
      }
    } else {
      resultsFound
    }

    clearState()
    return results
  }

  private fun checkSeen(node: LeakNode): Boolean {
    val neverSeen = visitedSet.add(node.instance)
    return !neverSeen
  }

  private fun clearState() {
    toVisitQueue.clear()
    toVisitMap.clear()
    visitedSet.clear()
    visitOrder = 0
    referentMap = emptyMap()
    undominatedSet.clear()
    dominatedInstances.clear()
  }

  private fun enqueueGcRoots(
    hprofParser: HprofParser,
    gcRootIds: List<Long>
  ) {
    // TODO sort GC roots based on type and class name (for class / instance / array)
    // Goal is to get a stable shortest path
    // TODO Add root type so that for java local we could exclude specific threads.
    // TODO java local: exclude specific threads,
    // TODO java local: parent should be set to the allocated thread
    gcRootIds.forEach {
      undominate(it)
      enqueue(hprofParser, RootNode(it), exclusionPriority = null)
    }
  }

  private fun visitClassRecord(
    hprofParser: HprofParser,
    record: ClassDumpRecord,
    node: LeakNode,
    staticFieldNameByClassName: Map<String, Map<String, Exclusion>>,
    computeRetainedHeapSize: Boolean
  ) {
    val className = hprofParser.className(record.id)

    val ignoredStaticFields = staticFieldNameByClassName[className] ?: emptyMap()

    for (staticField in record.staticFields) {
      val objectId = (staticField.value as? ObjectReference)?.value ?: continue
      val fieldName = hprofParser.hprofStringById(staticField.nameStringId)
      if (fieldName == "\$staticOverhead") {
        continue
      }

      if (computeRetainedHeapSize) {
        undominate(objectId)
      }

      val leakReference = LeakReference(STATIC_FIELD, fieldName, "object $objectId")

      val exclusion = ignoredStaticFields[fieldName]

      enqueue(
          hprofParser,
          ChildNode(objectId, visitOrder++, exclusion?.description, node, leakReference),
          exclusion?.status
      )
    }
  }

  private fun visitInstanceRecord(
    hprofParser: HprofParser,
    record: InstanceDumpRecord,
    parent: LeakNode,
    fieldNameByClassName: Map<String, Map<String, Exclusion>>,
    computeRetainedHeapSize: Boolean
  ) {
    val instance = hprofParser.hydrateInstance(record)

    val ignoredFields = LinkedHashMap<String, Exclusion>()

    instance.classHierarchy.forEach {
      ignoredFields.putAll(fieldNameByClassName[it.className] ?: emptyMap())
    }

    val fieldNamesAndValues = mutableListOf<Pair<String, HeapValue>>()

    instance.fieldValues.forEachIndexed { classIndex, classFieldValues ->
      classFieldValues.forEachIndexed { fieldIndex, fieldValue ->
        val fieldName = instance.classHierarchy[classIndex].fieldNames[fieldIndex]
        fieldNamesAndValues.add(fieldName to fieldValue)
      }
    }

    fieldNamesAndValues.sortBy { (name, _) -> name }

    fieldNamesAndValues.filter { (_, value) -> value is ObjectReference }
        .map { (name, reference) -> name to (reference as ObjectReference).value }
        .forEach { (fieldName, objectId) ->

          if (computeRetainedHeapSize) {
            if (hprofParser.objectIdMetadata(objectId) == CLASS) {
              undominate(objectId)
            } else {
              updateDominator(parent.instance, objectId)
            }
          }

          val exclusion = ignoredFields[fieldName]
          enqueue(
              hprofParser, ChildNode(
              objectId,
              visitOrder++, exclusion?.description, parent,
              LeakReference(INSTANCE_FIELD, fieldName, "object $objectId")
          ), exclusion?.status
          )
        }
  }

  private fun visitObjectArrayRecord(
    hprofParser: HprofParser,
    record: ObjectArrayDumpRecord,
    parentNode: LeakNode,
    computeRetainedHeapSize: Boolean
  ) {
    record.elementIds.forEachIndexed { index, elementId ->
      if (computeRetainedHeapSize) {
        if (hprofParser.objectIdMetadata(elementId) == CLASS) {
          undominate(elementId)
        } else {
          updateDominator(parentNode.instance, elementId)
        }
      }
      val name = Integer.toString(index)
      val reference = LeakReference(ARRAY_ENTRY, name, "object $elementId")
      enqueue(hprofParser, ChildNode(elementId, visitOrder++, null, parentNode, reference), null)
    }
  }

  private fun enqueue(
    hprofParser: HprofParser,
    node: LeakNode,
    exclusionPriority: Status?
  ) {
    // 0L is null
    if (node.instance == 0L) {
      return
    }
    if (visitedSet.contains(node.instance)) {
      return
    }
    if (exclusionPriority == NEVER_REACHABLE) {
      return
    }

    val nodePriority = exclusionPriority ?: ALWAYS_REACHABLE

    // Whether we want to visit now or later, we should skip if this is already to visit.
    val existingPriority = toVisitMap[node.instance]

    if (existingPriority != null && existingPriority <= nodePriority) {
      return
    }

    val isLeakingInstance = referentMap[node.instance] != null

    val objectIdMetadata = hprofParser.objectIdMetadata(node.instance)
    if (!isLeakingInstance && objectIdMetadata in SKIP_ENQUEUE) {
      return
    }

    if (existingPriority != null) {
      toVisitQueue.removeAll { it.instance == node.instance }
    }
    toVisitMap[node.instance] = nodePriority
    toVisitQueue.add(node)
  }

  private fun updateDominator(
    parent: Long,
    instance: Long
  ) {
    if (undominatedSet.contains(instance)) {
      return
    }
    val currentDominator = dominatedInstances[instance]
    val parentDominator = dominatedInstances[parent]

    val parentIsRetainedInstance = referentMap.containsKey(parent)

    val nextDominator = if (parentIsRetainedInstance) parent else parentDominator

    if (nextDominator == null) {
      // TODO Remove this check once it works and has tests
      require(undominatedSet.contains(parent))
      // parent is not a retained instance and parent has no dominator, but it must have been
      // visited therefore we know parent belongs to undominated.
      undominatedSet.add(instance)

      if (currentDominator != null) {
        dominatedInstances.remove(instance)
      }
      return
    }
    if (currentDominator == null) {
      dominatedInstances[instance] = nextDominator
    } else {
      val parentDominators = mutableListOf<Long>()
      val currentDominators = mutableListOf<Long>()
      var dominator: Long? = nextDominator
      while (dominator != null) {
        parentDominators.add(dominator)
        dominator = dominatedInstances[dominator]
      }
      dominator = currentDominator
      while (dominator != null) {
        currentDominators.add(dominator)
        dominator = dominatedInstances[dominator]
      }

      var sharedDominator: Long? = null
      exit@ for (parentD in parentDominators) {
        for (currentD in currentDominators) {
          if (currentD == parentD) {
            sharedDominator = currentD
            break@exit
          }
        }
      }
      if (sharedDominator == null) {
        dominatedInstances.remove(instance)
        undominatedSet.add(instance)
      } else {
        dominatedInstances[instance] = sharedDominator
      }

    }
  }

  private fun undominate(instance: Long) {
    dominatedInstances.remove(instance)
    undominatedSet.add(instance)
  }

  companion object {
    private val SKIP_ENQUEUE =
      setOf(PRIMITIVE_WRAPPER, PRIMITIVE_ARRAY_OR_WRAPPER_ARRAY, STRING, EMPTY_INSTANCE)

    // Since NEVER_REACHABLE never ends up in the queue, we use its value to mean "ALWAYS_REACHABLE"
    // For this to work we need NEVER_REACHABLE to be declared as the first enum value.
    private val ALWAYS_REACHABLE = NEVER_REACHABLE
  }
}
