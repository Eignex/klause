package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.toLongExact
import kotlin.time.TimeSource

// First-UIP conflict analysis (J. P. Marques-Silva, K. A. Sakallah, "GRASP: A Search Algorithm for Propositional
// Satisfiability", IEEE Trans. Computers 48(5), 1999, https://doi.org/10.1109/12.769433). This driver owns the
// implication-graph walk and pivot selection along the pin trail; a [ConflictResolvent] owns folding reasons
// into the nogood. The graph spans Boolean variables and order literals uniformly, so non-clause forces are
// resolved through rather than treated as leaves; a variable with null antecedents is a decision, assumption
// or root fact.
internal class ConflictAnalyzer internal constructor(private val state: PropagationState) : ReasonGraph {

    // The sound fallback when PB resolution cannot proceed.
    private val clauseResolvent: ConflictResolvent = ClauseResolvent(state, this)

    private val pbResolvent: PbConflictResolvent? =
        if (state.pbLearning && state.problem.numBoolVars > 0) PbConflictResolvent(state, this) else null

    // Its bump sets feed VSIDS.
    private var lastResolvent: ConflictResolvent = clauseResolvent

    private var universe = 0

    // Memo of atomLevelForConflict for atoms without a stored level, which recur across reasons. The path is
    // frozen during an analysis; the epoch invalidates the memo per analysis.
    private var atomLevelMemo = emptyIntArray()
    private var atomLevelStamp = emptyIntArray()
    private var atomLevelEpoch = 0

    // Valid only after a Learned result.
    fun lastBumpBoolVars(): IntArrayList = lastResolvent.bumpBoolVars

    fun lastBumpIntVars(): IntArrayList = lastResolvent.bumpIntVars

    override fun universeSize(): Int = universe

    sealed interface AnalysisResult {
        sealed interface LearnedConstraint : AnalysisResult {
            val backjumpLevel: Int

            val lbd: Int

            val decisionLevels: IntArray

            val asserting: Boolean

            // Integer rows expose no endpoint disjunction.
            val guardLiterals: IntArray

            val isContradiction: Boolean get() = this is Learned && literals.isEmpty()
        }

        data class Learned(
            val literals: IntArray,
            override val backjumpLevel: Int,
            override val lbd: Int,
            // Projects a conflict onto the participating assumption levels for core extraction.
            override val decisionLevels: IntArray,
            // False when the conflict rests on several literals at the conflict level; the engine then
            // backtracks chronologically instead of asserting a non-unit clause.
            override val asserting: Boolean = true,
        ) : LearnedConstraint {
            override val guardLiterals: IntArray get() = literals
            override fun equals(other: Any?): Boolean = other is Learned &&
                literals.contentEquals(other.literals) &&
                backjumpLevel == other.backjumpLevel &&
                lbd == other.lbd &&
                decisionLevels.contentEquals(other.decisionLevels)
            override fun hashCode(): Int = 31 * (31 * (31 * literals.contentHashCode() + backjumpLevel) + lbd) +
                decisionLevels.contentHashCode()
            override fun toString(): String =
                "Learned(literals=${literals.toList()}, backjumpLevel=$backjumpLevel, lbd=$lbd, " +
                    "levels=${decisionLevels.toList()})"
        }

        // Cutting-planes result, stronger than any single clause the same conflict could yield.
        data class LearnedPb(
            val weights: Array<com.eignex.util.numbers.BigInt>,
            val literals: IntArray,
            val degree: com.eignex.util.numbers.BigInt,
            override val backjumpLevel: Int,
            override val lbd: Int,
            override val decisionLevels: IntArray,
            override val asserting: Boolean = true,
            val fallbackClause: Learned? = null,
        ) : LearnedConstraint {
            override val guardLiterals: IntArray get() = literals
            override fun equals(other: Any?): Boolean = other is LearnedPb &&
                weights.contentEquals(other.weights) &&
                literals.contentEquals(other.literals) &&
                degree == other.degree &&
                backjumpLevel == other.backjumpLevel
            override fun hashCode(): Int =
                31 * (31 * (31 * weights.contentHashCode() + literals.contentHashCode()) + degree.hashCode()) +
                    backjumpLevel
            override fun toString(): String =
                "LearnedPb(Σw·ℓ≥$degree, lits=${literals.toList()}, weights=${weights.toList()}, " +
                    "backjumpLevel=$backjumpLevel, lbd=$lbd)"
        }

        class LearnedIntegerRow(
            val row: IntegerConflictRow,
            override val backjumpLevel: Int,
            override val lbd: Int,
            override val decisionLevels: IntArray,
            val fallbackClause: Learned,
        ) : LearnedConstraint {
            override val asserting: Boolean = true
            override val guardLiterals: IntArray = emptyIntArray()
        }

        data object NotApplicable : AnalysisResult
    }

    // The conflict level is the deepest level among the seed reason's literals, not `state.currentLevel`: that is
    // set over all of the failing factor's variables and can name a level no reason literal sits at, leaving 1UIP
    // without a pivot. Levelling is max-antecedent based, so nothing resolved in sits above it.
    fun analyze(conflictFactorId: Int): AnalysisResult = boundedAnalysis {
        beginAnalysis()
        val factor = state.factorAt(conflictFactorId)
        state.arithmeticLearningTrace?.seedEndpoints(state.factorIntVars(conflictFactorId), state)
        val attribution = state.factorAttribution
        val family = if (attribution == null) 0 else state.attributionFamily(conflictFactorId)
        val before = state.work
        val start = if (attribution == null) null else TimeSource.Monotonic.markNow()
        attribution?.note(family, FactorAttribution.Metric.ConflictReasonAttempts)
        val seedReason = try {
            factor.conflictReason(state, conflictFactorId)
        } finally {
            attribution?.let {
                it.note(family, FactorAttribution.Metric.ReasonWork, state.work - before)
                it.note(family, FactorAttribution.Metric.ReasonNanos,
                    requireNotNull(start).elapsedNow().inWholeNanoseconds)
            }
        }
        if (seedReason == null) {
            attribution?.note(family, FactorAttribution.Metric.ConflictReasonUnavailable)
            return@boundedAnalysis AnalysisResult.NotApplicable
        }
        attribution?.let {
            it.support(family, FactorAttribution.Metric.EagerReasons, seedReason.size)
            it.remember(seedReason, family)
        }
        return@boundedAnalysis analyzeFromSeed(seedReason, conflictLevelOf(seedReason), seedFactorId = conflictFactorId)
    }

    // Seeds from an external clause whose literals are all false, such as an LP Farkas certificate over bound
    // atoms.
    fun analyzeConflictClause(conflictClause: IntArray): AnalysisResult = boundedAnalysis {
        beginAnalysis()
        return@boundedAnalysis analyzeFromSeed(conflictClause, conflictLevelOf(conflictClause))
    }

    private inline fun boundedAnalysis(analyze: () -> AnalysisResult): AnalysisResult = try {
        state.finiteWork.begin()
        if (state.pbLearning || state.integerRowLearning || state.historicalTableReasons || state.reasonPreflight) {
            state.finiteWork.checkNow()
        }
        val result = analyze()
        if (state.pbLearning || state.integerRowLearning || state.historicalTableReasons || state.reasonPreflight) {
            state.finiteWork.checkNow()
        }
        if (state.runCancelled) AnalysisResult.NotApplicable else result
    } catch (_: FinitePropagationInterrupted) {
        state.runCancelled = true
        state.arithmeticLearningTrace?.record("analysis.cancelled")
        AnalysisResult.NotApplicable
    }

    // Runs before anything reads a level: a level memoized by the previous conflict belongs to another path.
    private fun beginAnalysis() {
        state.arithmeticLearningTrace?.beginConflict(state.currentFactor, state.currentLevel, state.undo.size)
        state.factorAttribution?.beginAnalysis()
        state.lazyReasonMemo.clear()
        val atomCount = state.atoms.intVar.size
        if (atomLevelStamp.size < atomCount) {
            // Atoms keep materialising during search, so grow ahead of them rather than on every new one.
            val capacity = maxOf(atomCount, atomLevelStamp.size * 2)
            atomLevelStamp = IntArray(capacity)
            atomLevelMemo = IntArray(capacity)
            atomLevelEpoch = 0 // fresh arrays read as epoch 0, so don't start at 0
        }
        atomLevelEpoch++
    }

    private fun conflictLevelOf(reason: IntArray): Int {
        var max = 0
        for (lit in reason) {
            val l = levelOf(Lit.variable(lit))
            state.arithmeticLearningTrace?.record("analysis.seed-level", lit.toLong(), l.toLong())
            if (l > max) max = l
        }
        return max
    }

    // A decision contradicting a prior pin: no factor fired, and the violated clause is
    // `(prior pin's antecedents) ∨ ¬decision`. The conflict level is the attempted decision level rather than the
    // seed's maximum, which is the prior pin's level; holding it above the seed also lets the minimizer resolve
    // the decision literal away.
    fun analyzeDecisionConflict(conflictedVar: Int): AnalysisResult = boundedAnalysis {
        beginAnalysis()
        val priorValue = state.boolValues[conflictedVar] ?: return@boundedAnalysis AnalysisResult.NotApplicable
        val priorAnt = state.reasonOf(state.boolAntecedents[conflictedVar])
        // False because the prior pin still holds.
        val decisionLit = Lit.make(conflictedVar, !priorValue)
        val seed = if (priorAnt == null) {
            intArrayOf(decisionLit)
        } else {
            IntArray(priorAnt.size + 1).also {
                for (i in priorAnt.indices) it[i] = priorAnt[i]
                it[priorAnt.size] = decisionLit
            }
        }
        return@boundedAnalysis analyzeFromSeed(seed, state.currentLevel)
    }

    // A failed PB resolution (unsupported reason or overflow) re-runs the conflict through the always-sound
    // clause resolvent. [seedFactorId] is -1 for an external clause seed.
    private fun analyzeFromSeed(seedReason: IntArray, currentLevel: Int, seedFactorId: Int = -1): AnalysisResult {
        state.arithmeticLearningTrace?.record("analysis.seed", seedFactorId.toLong(), currentLevel.toLong())
        state.arithmeticLearningTrace?.clause("analysis.seed-clause", seedReason, state)
        if (currentLevel <= 0) {
            state.arithmeticLearningTrace?.record("analysis.decline.root")
            return AnalysisResult.NotApplicable
        }
        universe = state.problem.numBoolVars + state.atoms.intVar.size
        if (state.integerRowLearning && seedFactorId < 0) {
            state.arithmeticLearningTrace?.record("integer.decline.non-factor-seed")
        }

        if (state.integerRowLearning && seedFactorId >= 0) {
            val fallback = runResolution(clauseResolvent, seedReason, currentLevel)
            (fallback as? AnalysisResult.Learned)?.let {
                state.arithmeticLearningTrace?.clause("integer.fallback", it.literals, state)
            }
            val row = (fallback as? AnalysisResult.Learned)?.takeIf {
                state.hasIntegerArithmeticSource(seedFactorId).also { supported ->
                    if (!supported) state.arithmeticLearningTrace?.record("integer.decline.seed-source")
                }
            }?.let {
                IntegerConflictLearner(state).learn(seedFactorId, currentLevel, it)
            }
            if (fallback !is AnalysisResult.Learned) {
                state.arithmeticLearningTrace?.record("integer.decline.no-clausal-fallback")
            }
            if (row != null) {
                state.integerRowsDerived++
                state.arithmeticLearningTrace?.row("integer.derived", row.row, state)
                state.arithmeticLearningTrace?.record("integer.native-target", row.backjumpLevel.toLong())
                lastResolvent = clauseResolvent
                return row
            }
            if (pbResolvent == null && (!state.pbLearning || !state.historicalTableReasons)) {
                lastResolvent = clauseResolvent
                return fallback
            }
        }

        pbResolvent?.let { pb ->
            pb.seedFactorId = seedFactorId
            val r = try {
                runResolution(pb, seedReason, currentLevel)
            } catch (stop: ArithmeticLearningDeclined) {
                pb.resourceDecline(stop.reason)
                AnalysisResult.NotApplicable
            } finally {
                pb.releaseArithmetic()
            }
            if (!pb.failed) {
                state.arithmeticLearningTrace?.record(if (r is AnalysisResult.LearnedPb) "pb.derived" else "pb.clause")
                if (r is AnalysisResult.LearnedPb) {
                    state.arithmeticLearningTrace?.let { trace ->
                        trace.clause("pb.literals", r.literals, state)
                        if (trace.accepting && r.weights.size <= trace.payloadTermLimit &&
                            r.weights.all { it.fitsLong() } && r.degree.fitsLong()) {
                            val weights = LongArray(r.weights.size) { r.weights[it].toLongExact() }
                            trace.record("pb.weights", *weights)
                            trace.record("pb.target", r.degree.toLongExact(), r.backjumpLevel.toLong())
                        } else {
                            trace.omitPayload("pb.weights", r.weights.size)
                        }
                    }
                } else if (r is AnalysisResult.Learned) {
                    state.arithmeticLearningTrace?.clause("pb.clause-result", r.literals, state)
                }
                val result = if (r is AnalysisResult.LearnedPb && state.problem.numIntVars > 0) {
                    val fallback = runResolution(clauseResolvent, seedReason, currentLevel) as? AnalysisResult.Learned
                    fallback?.let { state.arithmeticLearningTrace?.clause("pb.fallback", it.literals, state) }
                    r.copy(fallbackClause = fallback)
                } else {
                    r
                }
                lastResolvent = pb
                return result
            }
        }
        lastResolvent = clauseResolvent
        val result = historicalTableResolution(seedReason, currentLevel)
        (result as? AnalysisResult.Learned)?.let {
            state.arithmeticLearningTrace?.clause("analysis.clause-result", it.literals, state)
        }
        return result
    }

    private fun historicalTableResolution(seedReason: IntArray, currentLevel: Int): AnalysisResult {
        if (!state.historicalTableReasons || universe > 4096) {
            return runResolution(clauseResolvent, seedReason, currentLevel)
        }
        val choice = HistoricalTableReason(state)
        state.historicalTableReason = choice
        var selectedFamilies = 0
        try {
            state.finiteWork.checkNow()
            val fallback = runResolution(clauseResolvent, seedReason, currentLevel)
            selectedFamilies = state.factorAttribution?.analysisFamilies ?: 0
            if (fallback !is AnalysisResult.Learned || !fallback.asserting || state.runCancelled ||
                fallback.literals.size > 64
            ) return fallback
            val prepared = choice.prepare(fallback.literals)
            state.finiteWork.checkNow()
            state.arithmeticLearningTrace?.record(if (prepared) "table.retry" else "table.decline")
            if (!prepared) return fallback
            universe = state.problem.numBoolVars + state.atoms.intVar.size
            if (universe > 4096) return fallback
            val alternateResolvent = ClauseResolvent(state, this)
            val alternate = runResolution(alternateResolvent, seedReason, currentLevel)
            state.finiteWork.checkNow()
            if (alternate !is AnalysisResult.Learned || !alternate.asserting) return fallback
            val improves = when {
                alternate.backjumpLevel != fallback.backjumpLevel -> alternate.backjumpLevel < fallback.backjumpLevel
                alternate.lbd != fallback.lbd -> alternate.lbd < fallback.lbd
                else -> alternate.literals.size < fallback.literals.size
            }
            if (!improves) return fallback
            state.factorAttribution?.note(1 shl FactorFamily.Table.ordinal, FactorAttribution.Metric.RefinementAccepted)
            state.arithmeticLearningTrace?.record("table.accepted", fallback.backjumpLevel.toLong(),
                alternate.backjumpLevel.toLong(), fallback.literals.size.toLong(), alternate.literals.size.toLong())
            lastResolvent = alternateResolvent
            selectedFamilies = state.factorAttribution?.analysisFamilies ?: 0
            return alternate
        } finally {
            state.factorAttribution?.selectFamilies(selectedFamilies)
            state.historicalTableReason = null
        }
    }

    private fun runResolution(resolvent: ConflictResolvent, seedReason: IntArray, currentLevel: Int): AnalysisResult {
        val numBoolVars = state.problem.numBoolVars
        resolvent.reset(universe)
        state.factorAttribution?.beginAnalysis()

        state.factorAttribution?.let { it.participate(it.reasonFamily(seedReason), seedReason.size) }
        resolvent.resolve(seedReason, currentLevel)

        // Under single establishment a reason cites only lower-position literals, so the pivot cursor descends
        // monotonically, O(trail) per analysis. An off-trail pivot's derived antecedents may touch the trail
        // anywhere, so one full rescan follows it; a purely monotonic cursor would emit a non-asserting clause.
        var pinCursor = state.boolPinOrder.size - 1
        var rescanFromTop = true
        while (true) {
            val scanFrom = if (rescanFromTop) state.boolPinOrder.size - 1 else pinCursor
            val pivotPos = trailPivotPosition(resolvent, scanFrom, currentLevel, numBoolVars)
            var pivot = if (pivotPos >= 0) state.boolPinOrder[pivotPos] else -1
            // A trail pivot with no reason is the level's decision, so it is the UIP only once nothing else at
            // the level is live; a literal the cursor passed, or an atom off the trail, may still be, and
            // resolving it first keeps the clause asserting.
            val decisionTooSoon = pivot >= 0 && resolvent.liveAtCurrentLevel > 1 && antecedentsOf(pivot) == null
            val offTrailFirst = if (decisionTooSoon) explainedPivot(resolvent, currentLevel, numBoolVars) else -1
            if (offTrailFirst >= 0) {
                pivot = offTrailFirst
                rescanFromTop = true
            } else if (pivot >= 0) {
                // Its antecedents land strictly below `pivotPos`.
                pinCursor = pivotPos - 1
                rescanFromTop = false
            } else {
                // An atom materialized mid-analysis is cited by a derived reason but never woken, so it is
                // absent from the pin trail.
                pivot = offTrailPivot(resolvent, currentLevel, numBoolVars)
                if (pivot < 0) break
                // Off-trail pivot: its derived antecedents may cite trail literals above the cursor,
                // so re-arm the full rescan for the next iteration.
                rescanFromTop = true
            }
            resolvent.resolveOut(pivot)
            if (resolvent.liveAtCurrentLevel == 0) {
                resolvent.addAsserting(pivot)
                return resolvent.finalizeResult(currentLevel)
            }
            val antecedents = antecedentsOf(pivot)
                ?: run {
                    // Leaf pivot — promote and drain the rest.
                    resolvent.addAsserting(pivot)
                    resolvent.drainFrontier()
                    return resolvent.finalizeResult(currentLevel)
                }
            state.factorAttribution?.let { it.participate(it.reasonFamily(antecedents), antecedents.size) }
            resolvent.resolve(antecedents, currentLevel)
        }
        resolvent.drainFrontier()
        return resolvent.finalizeResult(currentLevel)
    }

    // The pivot scans are their own methods: inside the resolution loop each is a loop C2 would otherwise compile
    // the whole analysis again on-stack for.

    // Position on the pin trail of the most recent frontier literal at [currentLevel], scanning down from
    // [scanFrom], or -1.
    private fun trailPivotPosition(
        resolvent: ConflictResolvent,
        scanFrom: Int,
        currentLevel: Int,
        numBoolVars: Int,
    ): Int {
        for (i in scanFrom downTo 0) {
            val v = state.boolPinOrder[i]
            if (!resolvent.isFrontier(v)) continue
            val lvl = if (v < numBoolVars) state.boolLevel[v] else cachedAtomLevel(v - numBoolVars)
            if (lvl == currentLevel) return i
        }
        return -1
    }

    // A frontier atom at [currentLevel] absent from the pin trail, or -1.
    private fun offTrailPivot(resolvent: ConflictResolvent, currentLevel: Int, numBoolVars: Int): Int {
        val offTrail = resolvent.offTrailFrontier
        for (k in 0 until offTrail.size) {
            val v = offTrail[k]
            if (resolvent.isFrontier(v) && cachedAtomLevel(v - numBoolVars) == currentLevel) return v
        }
        return -1
    }

    // A live literal at [currentLevel] with a reason to resolve through, or -1: one on the trail that the
    // descending cursor passed before a reason cited it, or an atom off the trail.
    private fun explainedPivot(resolvent: ConflictResolvent, currentLevel: Int, numBoolVars: Int): Int {
        for (i in state.boolPinOrder.size - 1 downTo 0) {
            val v = state.boolPinOrder[i]
            if (!resolvent.isFrontier(v)) continue
            val lvl = if (v < numBoolVars) state.boolLevel[v] else cachedAtomLevel(v - numBoolVars)
            if (lvl == currentLevel && antecedentsOf(v) != null) return v
        }
        val offTrail = resolvent.offTrailFrontier
        for (k in 0 until offTrail.size) {
            val v = offTrail[k]
            if (!resolvent.isFrontier(v) || cachedAtomLevel(v - numBoolVars) != currentLevel) continue
            if (antecedentsOf(v) != null) return v
        }
        return -1
    }

    // Out-of-range atom ids are reachable only through the minimizer's redundancy DFS; treating them as leaves
    // keeps the literal, which is sound, rather than indexing past the atom table.
    override fun antecedentsOf(v: Int): IntArray? {
        val numBoolVars = state.problem.numBoolVars
        return if (v < numBoolVars) {
            if (v < 0) null else state.reasonOf(state.boolAntecedents[v])
        } else {
            val atomId = v - numBoolVars
            if (atomId < state.atoms.intVar.size) state.atomAntecedentsDerived(atomId) else null
        }
    }

    override fun knownLeaf(v: Int): Boolean {
        val bools = state.problem.numBoolVars
        if (v < bools) return v >= 0 && state.boolAntecedents[v] == null
        val atom = v - bools
        return atom < state.atoms.intVar.size && state.atoms.lvl[atom] >= 0 && state.atoms.ant[atom] == null
    }

    override fun deferredReason(v: Int): Boolean {
        val bools = state.problem.numBoolVars
        val reason = if (v < bools) {
            if (v < 0) return false
            state.boolAntecedents[v]
        } else {
            val atom = v - bools
            if (atom >= state.atoms.intVar.size || state.atoms.lvl[atom] < 0) return false
            state.atoms.ant[atom]
        }
        if (!isLazyReason(reason)) return false
        return !state.lazyReasonMemo.containsKey(requireNotNull(reason))
    }

    override fun levelOf(v: Int): Int {
        val numBoolVars = state.problem.numBoolVars
        return if (v < numBoolVars) {
            state.boolLevel[v]
        } else {
            cachedAtomLevel(v - numBoolVars)
        }
    }

    // Atoms materialized mid-analysis lie past the memo arrays and fall through to the direct call.
    private fun cachedAtomLevel(id: Int): Int {
        if (id >= atomLevelStamp.size) return state.atomLevelForConflict(id)
        if (atomLevelStamp[id] == atomLevelEpoch) return atomLevelMemo[id]
        val lv = state.atomLevelForConflict(id)
        atomLevelMemo[id] = lv
        atomLevelStamp[id] = atomLevelEpoch
        return lv
    }
}
