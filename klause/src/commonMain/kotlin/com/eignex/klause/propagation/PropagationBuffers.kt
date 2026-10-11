package com.eignex.klause.propagation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.util.Bits
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

internal class PropagationBuffers(
    val boolAssigned: Bits,
    val boolValueBits: Bits,
    val rootDomains: Array<IntDomain>,
    val intDomains: Array<IntDomain>,
    val dirtyBools: IntArrayDeque,
    val dirtyInts: IntArrayDeque,
    val intEvents: IntEventMachinery,
    val propQueue: IntArrayDeque,
    val propStamp: IntArray,
    val boolLevel: IntArray,
    val intLevel: IntArray,
    val intMinLevel: IntArray,
    val intMaxLevel: IntArray,
    val boolReason: IntArray,
    val intMinReason: IntArray,
    val intMaxReason: IntArray,
    val holeHistAnt: Array<ArrayList<IntArray?>?>,
    val boundMoves: Array<IntArrayList?>,
    val boundMovePriorMin: Array<LongArrayList?>,
    val boundMovePriorMax: Array<LongArrayList?>,
    val holeHistVal: Array<LongArrayList?>,
    val holeHistLvl: Array<IntArrayList?>,
    val holeHistPos: Array<IntArrayList?>,
    val boolAntecedents: Array<IntArray?>,
    val intMinAntecedents: Array<IntArray?>,
    val intMaxAntecedents: Array<IntArray?>,
    val boolPinPos: IntArray,
    val atoms: AtomStore,
    val watches: BoolWatcherIndex,
    val refPayloadStore: ArrayList<Any?>,
    val binaryClauseCount: Int,
) {
    companion object {
        fun preparation(projection: PropagationProblem): Sequence<PropagationBuffers?> = sequence {
            val problem = projection.problem
            val boolAssigned = Bits(problem.numBoolVars)
            yield(null)
            val boolValueBits = Bits(problem.numBoolVars)
            yield(null)
            val folded = (problem as? BakedProblem)?.foldedIntDomains
            val roots = if (folded == null) arrayOfNulls<IntDomain>(problem.numIntVars) else null
            yield(null)
            if (roots != null) for (v in roots.indices) {
                roots[v] = problem.finiteIntDomain(v)
                if ((v + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            @Suppress("UNCHECKED_CAST")
            val rootDomains = folded ?: (roots as Array<IntDomain>)
            val domains = arrayOfNulls<IntDomain>(problem.numIntVars)
            yield(null)
            for (v in domains.indices) {
                domains[v] = rootDomains[v]
                if ((v + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            @Suppress("UNCHECKED_CAST")
            val intDomains = domains as Array<IntDomain>
            val dirtyBools = IntArrayDeque(initialCapacity = problem.numBoolVars.coerceAtLeast(8))
            yield(null)
            val dirtyInts = IntArrayDeque(initialCapacity = problem.numIntVars.coerceAtLeast(8))
            yield(null)
            var events: IntEventMachinery? = null
            for (next in IntEventMachinery.preparation(projection)) {
                if (next != null) events = next
                yield(null)
            }
            val intEvents = checkNotNull(events)
            val propQueue = IntArrayDeque(initialCapacity = problem.numFactors.coerceAtLeast(8))
            yield(null)
            val propStamp = IntArray(problem.numFactors.coerceAtLeast(8))
            yield(null)
            val boolLevel = IntArray(problem.numBoolVars)
            yield(null)
            for (v in boolLevel.indices) {
                boolLevel[v] = -1
                if ((v + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            val intLevel = IntArray(problem.numIntVars)
            yield(null)
            for (v in intLevel.indices) {
                intLevel[v] = -1
                if ((v + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            val intMinLevel = IntArray(problem.numIntVars)
            yield(null)
            for (v in intMinLevel.indices) {
                intMinLevel[v] = -1
                if ((v + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            val intMaxLevel = IntArray(problem.numIntVars)
            yield(null)
            for (v in intMaxLevel.indices) {
                intMaxLevel[v] = -1
                if ((v + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            val boolReason = IntArray(problem.numBoolVars)
            yield(null)
            for (v in boolReason.indices) {
                boolReason[v] = -1
                if ((v + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            val intMinReason = IntArray(problem.numIntVars)
            yield(null)
            for (v in intMinReason.indices) {
                intMinReason[v] = -1
                if ((v + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            val intMaxReason = IntArray(problem.numIntVars)
            yield(null)
            for (v in intMaxReason.indices) {
                intMaxReason[v] = -1
                if ((v + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            val holeHistAnt = arrayOfNulls<ArrayList<IntArray?>>(problem.numIntVars)
            yield(null)
            val boundMoves = arrayOfNulls<IntArrayList>(problem.numIntVars)
            yield(null)
            val boundMovePriorMin = arrayOfNulls<LongArrayList>(problem.numIntVars)
            yield(null)
            val boundMovePriorMax = arrayOfNulls<LongArrayList>(problem.numIntVars)
            yield(null)
            val holeHistVal = arrayOfNulls<LongArrayList>(problem.numIntVars)
            yield(null)
            val holeHistLvl = arrayOfNulls<IntArrayList>(problem.numIntVars)
            yield(null)
            val holeHistPos = arrayOfNulls<IntArrayList>(problem.numIntVars)
            yield(null)
            val boolAntecedents = arrayOfNulls<IntArray>(problem.numBoolVars)
            yield(null)
            val intMinAntecedents = arrayOfNulls<IntArray>(problem.numIntVars)
            yield(null)
            val intMaxAntecedents = arrayOfNulls<IntArray>(problem.numIntVars)
            yield(null)
            val boolPinPos = IntArray(problem.numBoolVars)
            yield(null)
            val atoms = AtomStore(problem.numIntVars)
            yield(null)
            var watcherCount = 0
            for (fid in projection.propagators.indices) {
                watcherCount += projection.propagators[fid].initialBoolWatchers?.size ?: 0
                if ((fid + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            var watcherIndex: BoolWatcherIndex? = null
            for (next in BoolWatcherIndex.preparation(problem.numBoolVars, watcherCount)) {
                if (next != null) watcherIndex = next
                yield(null)
            }
            val refPayloadStore = ArrayList<Any?>(problem.numFactors)
            yield(null)
            repeat(problem.numFactors) { fid ->
                refPayloadStore.add(null)
                if ((fid + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            var binaryClauseCount = 0
            for (fid in problem.factors.indices) {
                val factor = problem.factors[fid]
                if (factor is Clause && factor.literals.size == 2) binaryClauseCount++
                if ((fid + 1) % PROPAGATION_PREPARATION_BATCH_SIZE == 0) yield(null)
            }
            yield(PropagationBuffers(
                boolAssigned,
                boolValueBits,
                rootDomains,
                intDomains,
                dirtyBools,
                dirtyInts,
                intEvents,
                propQueue,
                propStamp,
                boolLevel,
                intLevel,
                intMinLevel,
                intMaxLevel,
                boolReason,
                intMinReason,
                intMaxReason,
                holeHistAnt,
                boundMoves,
                boundMovePriorMin,
                boundMovePriorMax,
                holeHistVal,
                holeHistLvl,
                holeHistPos,
                boolAntecedents,
                intMinAntecedents,
                intMaxAntecedents,
                boolPinPos,
                atoms,
                checkNotNull(watcherIndex),
                refPayloadStore,
                binaryClauseCount,
            ))
        }
    }
}
