package com.eignex.klause.localsearch

import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayDeque
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.MutableIntIntMap
import com.eignex.util.collections.MutableIntObjectMap
import com.eignex.util.collections.storage.borrowBackingArray
import com.eignex.util.collections.toSortedIntArray

/**
 * Passive index over definitional nodes: [affectedNodes] returns the cone to re-evaluate after a move, in topological
 * order, and defined vars are excluded from search. The state performs the writes through its incremental apply path
 * so payloads and break/make counts stay maintained.
 */
class InvariantNetwork private constructor(data: DefinitionIndex) {
    internal constructor(nodes: List<DefinitionalSweep.SweepNode>, numIntVars: Int, numBoolVars: Int) :
        this(indexPreparation(nodes, numIntVars, numBoolVars).filterNotNull().first())

    private val nodeArr: Array<DefinitionalSweep.SweepNode> = data.nodeArr
    private val definedInt: BooleanArray = data.definedInt
    private val definedBool: BooleanArray = data.definedBool
    private val intDefinitions: MutableIntObjectMap<FunctionalObjective.Node> = data.intDefinitions
    private val literalChannels: MutableIntObjectMap<LiteralIntDefinition> = data.literalChannels
    private val unaryPredicates: MutableIntObjectMap<UnaryPredicateDefinition> = data.unaryPredicates
    private val booleanLiterals: MutableIntIntMap = data.booleanLiterals
    private val elementResults: MutableIntObjectMap<ElementResultDefinition> = data.elementResults
    private val extremumRepairs: BooleanArray = data.extremumRepairs
    private val intReaders: Array<IntArray> = data.intReaders
    private val boolReaders: Array<IntArray> = data.boolReaders
    private val intWriters: IntArray = data.intWriters
    private val boolWriters: IntArray = data.boolWriters

    internal companion object {
        fun preparation(
            nodes: List<DefinitionalSweep.SweepNode>,
            numIntVars: Int,
            numBoolVars: Int,
        ): Sequence<InvariantNetwork?> = indexPreparation(nodes, numIntVars, numBoolVars).map {
            it?.let { data -> InvariantNetwork(data) }
        }

        private fun indexPreparation(
            nodes: List<DefinitionalSweep.SweepNode>,
            numIntVars: Int,
            numBoolVars: Int,
        ): Sequence<DefinitionIndex?> = sequence {
            val nodeArr = nodes.toTypedArray()
            yield(null)
            val definedInt = BooleanArray(numIntVars)
            yield(null)
            val definedBool = BooleanArray(numBoolVars)
            yield(null)
            val extremumRepairs = BooleanArray(numIntVars)
            yield(null)
            val intWriters = IntArray(numIntVars)
            yield(null)
            val boolWriters = IntArray(numBoolVars)
            yield(null)
            val intDefinitions = MutableIntObjectMap<FunctionalObjective.Node>()
            val literalChannels = MutableIntObjectMap<LiteralIntDefinition>()
            val unaryPredicates = MutableIntObjectMap<UnaryPredicateDefinition>()
            val booleanLiterals = MutableIntIntMap()
            val elementResults = MutableIntObjectMap<ElementResultDefinition>()
            var hasDynamicReaders = false
            for (i in nodeArr.indices) {
                if (i % INITIALIZATION_BATCH == 0) yield(null)
                val n = nodeArr[i]
                if (n.outIsBool) definedBool[n.out] = true else definedInt[n.out] = true
                if (n.outIsBool) boolWriters[n.out] = i + 1 else intWriters[n.out] = i + 1
                val intDef = n as? DefinitionalSweep.SweepNode.IntDef
                if (intDef != null) {
                    intDefinitions.put(n.out, intDef.node)
                    extremumRepairs[n.out] = intDef.inverseRepair
                }
                if (n is LiteralIntDefinition) literalChannels.put(n.out, n)
                if (n is UnaryPredicateDefinition) unaryPredicates.put(n.out, n)
                if (n is DefinitionalSweep.SweepNode.BoolFold && n.ins.size == 1) {
                    booleanLiterals.put(n.out, n.ins[0])
                }
                if (n is ElementResultDefinition) {
                    elementResults.put(n.out, n)
                    hasDynamicReaders = true
                }
            }
            var intReaders: Array<IntArray>? = null
            for (part in readerPreparation(nodeArr, numIntVars) { it.intInputs }) {
                if (part == null) yield(null) else intReaders = part
            }
            yield(null)
            var boolReaders: Array<IntArray>? = null
            for (part in readerPreparation(nodeArr, numBoolVars) { it.boolInputs }) {
                if (part == null) yield(null) else boolReaders = part
            }
            yield(
                DefinitionIndex(
                    nodeArr, definedInt, definedBool, intDefinitions, literalChannels,
                    unaryPredicates, booleanLiterals, elementResults, extremumRepairs,
                    checkNotNull(intReaders), checkNotNull(boolReaders),
                    intWriters, boolWriters, hasDynamicReaders,
                ),
            )
        }

        private fun readerPreparation(
            nodes: Array<DefinitionalSweep.SweepNode>,
            slots: Int,
            inputs: (DefinitionalSweep.SweepNode) -> IntArray,
        ): Sequence<Array<IntArray>?> = sequence {
            val counts = IntArray(slots)
            yield(null)
            var visits = 0
            for (node in nodes) {
                if (++visits % INITIALIZATION_BATCH == 0) yield(null)
                for (v in inputs(node)) {
                    counts[v]++
                    if (++visits % INITIALIZATION_BATCH == 0) yield(null)
                }
            }
            val result = arrayOfNulls<IntArray>(slots)
            yield(null)
            for (v in result.indices) {
                result[v] = if (counts[v] == 0) emptyIntArray() else IntArray(counts[v])
                counts[v] = 0
                if ((v + 1) % INITIALIZATION_BATCH == 0) yield(null)
            }
            for (i in nodes.indices) {
                if (++visits % INITIALIZATION_BATCH == 0) yield(null)
                for (v in inputs(nodes[i])) {
                    checkNotNull(result[v])[counts[v]++] = i
                    if (++visits % INITIALIZATION_BATCH == 0) yield(null)
                }
            }
            @Suppress("UNCHECKED_CAST")
            yield(result as Array<IntArray>)
        }
    }

    /** Number of indexed definitions. */
    val size: Int get() = nodeArr.size

    /** True iff int var `v` is definitionally determined (should not be searched). */
    fun isDefinedInt(v: Int): Boolean = definedInt[v]

    /** True iff bool var `v` is definitionally determined (should not be searched). */
    fun isDefinedBool(v: Int): Boolean = definedBool[v]

    internal fun intDefinition(v: Int): FunctionalObjective.Node? = intDefinitions[v]

    internal fun literalChannel(v: Int): LiteralIntDefinition? = literalChannels[v]

    internal fun equalityPredicate(v: Int): EqualityPredicateDefinition? =
        unaryPredicates[v] as? EqualityPredicateDefinition

    internal fun unaryPredicate(v: Int): UnaryPredicateDefinition? = unaryPredicates[v]

    internal val hasBooleanLiterals: Boolean get() = booleanLiterals.size > 0

    internal fun booleanLiteral(v: Int): Int? = booleanLiterals.getOrDefault(v, -1).takeIf { it >= 0 }

    internal fun elementResultDefinition(v: Int): ElementResultDefinition? = elementResults[v]

    internal fun hasExtremumRepair(v: Int): Boolean = extremumRepairs[v]

    internal fun readsInt(v: Int): Boolean = intReaders[v].isNotEmpty()

    internal fun readsBool(v: Int): Boolean = boolReaders[v].isNotEmpty()

    internal fun intReaders(v: Int): IntArray = intReaders[v]

    internal fun boolReaders(v: Int): IntArray = boolReaders[v]

    internal fun intWriter(v: Int): Int = intWriters[v] - 1

    internal fun boolWriter(v: Int): Int = boolWriters[v] - 1

    internal val hasDynamicReaders: Boolean = data.hasDynamicReaders

    internal fun linearDefinition(v: Int): FunctionalObjective.Lin? = intDefinitions[v] as? FunctionalObjective.Lin

    /** The node at [index] (indexes ascend in topological order). */
    fun node(index: Int): DefinitionalSweep.SweepNode = nodeArr[index]

    private val members: Set<DefinitionalSweep.SweepNode> by lazy { nodeArr.toHashSet() }

    internal fun maintains(node: DefinitionalSweep.SweepNode): Boolean = node in members

    /** Topologically ordered indexes of the read-cone of the seed vars; negative ids are ignored for scratch reuse. */
    fun affectedNodes(seedInts: IntArray, seedBools: IntArray): IntArray = findAffectedNodes(seedInts, seedBools, null)

    internal fun affectedNodes(seedInts: IntArray, seedBools: IntArray, assignment: Assignment): IntArray =
        findAffectedNodes(seedInts, seedBools, assignment)

    private fun findAffectedNodes(seedInts: IntArray, seedBools: IntArray, assignment: Assignment?): IntArray {
        val marked = IntHashSet()
        val work = IntArrayDeque()
        fun seedInt(v: Int) {
            if (v < 0) return
            for (i in intReaders[v]) {
                val node = nodeArr[i]
                if (assignment != null && node is ElementResultDefinition && !node.reads(v, assignment)) continue
                if (marked.add(i)) work.addLast(i)
            }
        }
        fun seedBool(v: Int) {
            if (v >= 0) for (i in boolReaders[v]) if (marked.add(i)) work.addLast(i)
        }
        for (v in seedInts) seedInt(v)
        for (v in seedBools) seedBool(v)
        while (work.isNotEmpty()) {
            val i = work.removeFirst()
            val n = nodeArr[i]
            if (n.outIsBool) seedBool(n.out) else seedInt(n.out)
        }
        return marked.toSortedIntArray()
    }

    internal fun affectedNodes(
        seedInts: IntArrayList,
        seedBools: IntArrayList,
        assignment: Assignment,
        traversal: DefinitionTraversal,
    ): IntArrayList = findAffectedNodes(
        seedInts.borrowBackingArray(),
        seedInts.size,
        seedBools.borrowBackingArray(),
        seedBools.size,
        assignment,
        traversal,
    )

    private fun findAffectedNodes(
        seedInts: IntArray,
        intCount: Int,
        seedBools: IntArray,
        boolCount: Int,
        assignment: Assignment?,
        traversal: DefinitionTraversal,
    ): IntArrayList {
        check(traversal.network === this) { "definition traversal belongs to another network" }
        if (traversal.reuses(seedInts, intCount, seedBools, boolCount)) return traversal.nodes
        traversal.clear()
        val work = traversal.nodes
        fun seedInt(v: Int) {
            if (v < 0) return
            for (i in intReaders[v]) {
                val node = nodeArr[i]
                if (assignment != null && node is ElementResultDefinition && !node.reads(v, assignment)) continue
                traversal.add(i)
            }
        }
        fun seedBool(v: Int) {
            if (v >= 0) for (i in boolReaders[v]) traversal.add(i)
        }
        for (p in 0 until intCount) seedInt(seedInts[p])
        for (p in 0 until boolCount) seedBool(seedBools[p])
        var position = 0
        while (position < work.size) {
            val n = nodeArr[work[position++]]
            if (n.outIsBool) seedBool(n.out) else seedInt(n.out)
        }
        work.borrowBackingArray().sort(0, work.size)
        traversal.remember(seedInts, intCount, seedBools, boolCount)
        return work
    }
}

private class DefinitionIndex(
    val nodeArr: Array<DefinitionalSweep.SweepNode>,
    val definedInt: BooleanArray,
    val definedBool: BooleanArray,
    val intDefinitions: MutableIntObjectMap<FunctionalObjective.Node>,
    val literalChannels: MutableIntObjectMap<LiteralIntDefinition>,
    val unaryPredicates: MutableIntObjectMap<UnaryPredicateDefinition>,
    val booleanLiterals: MutableIntIntMap,
    val elementResults: MutableIntObjectMap<ElementResultDefinition>,
    val extremumRepairs: BooleanArray,
    val intReaders: Array<IntArray>,
    val boolReaders: Array<IntArray>,
    val intWriters: IntArray,
    val boolWriters: IntArray,
    val hasDynamicReaders: Boolean,
)
