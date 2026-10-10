package com.eignex.klause.localsearch

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.EmptyIntArray

private const val PROJECTION_BATCH = 256

/** Local-search-engine projection of an immutable `Problem`. */
class LocalSearchProblem private constructor(
    /** Immutable model data compiled by this projection. */
    val problem: Problem,
    /** One local-search invariant per model factor. */
    val invariants: Array<out Invariant>,
    /** Invariant occurrences indexed by Boolean variable. */
    val boolOccurrences: Array<IntArray>,
    /** Invariant occurrences indexed by integer variable. */
    val intOccurrences: Array<IntArray>,
    /** Invariant occurrences indexed by real variable; empty for a model with no continuous column. */
    val realOccurrences: Array<IntArray>,
) {
    private constructor(compiled: LocalSearchProblem) : this(
        compiled.problem, compiled.invariants, compiled.boolOccurrences,
        compiled.intOccurrences, compiled.realOccurrences,
    )

    /** Compile [problem], retaining exact linear rows when sums over [domains] can leave the 64-bit range. */
    constructor(problem: Problem, domains: Array<IntDomain>? = null) :
        this(preparation(problem, domains).asSequence().filterNotNull().first())

    internal companion object {
        fun preparation(problem: Problem, domains: Array<IntDomain>?): Iterator<LocalSearchProblem?> =
            sequence<LocalSearchProblem?> {
                val invariants = Array<Invariant>(problem.numFactors) { NoInvariant }
                val boolCounts = IntArray(problem.numBoolVars)
                val intCounts = IntArray(problem.numIntVars)
                val realCounts = IntArray(problem.numRealVars)
                val boolSeen = IntArray(problem.numBoolVars)
                val intSeen = IntArray(problem.numIntVars)
                val realSeen = IntArray(problem.numRealVars)
                for (from in 0 until problem.numFactors step PROJECTION_BATCH) {
                    for (fid in from until minOf(from + PROJECTION_BATCH, problem.numFactors)) {
                        val factor = problem.factors[fid]
                        invariants[fid] = factor.invariantProjection(domains)
                        if (invariants[fid] === NoInvariant) continue
                        factor.boolVars.forEachVariableOnce(boolSeen, fid) { boolCounts[it]++ }
                        factor.intVars.forEachVariableOnce(intSeen, fid) { intCounts[it]++ }
                        factor.variables.reals.forEachVariableOnce(realSeen, fid) { realCounts[it]++ }
                    }
                    yield(null)
                }
                val boolOccurrences = allocateOccurrences(boolCounts)
                val intOccurrences = allocateOccurrences(intCounts)
                val realOccurrences = allocateOccurrences(realCounts)
                boolCounts.fill(0)
                intCounts.fill(0)
                realCounts.fill(0)
                boolSeen.fill(0)
                intSeen.fill(0)
                realSeen.fill(0)
                for (from in 0 until problem.numFactors step PROJECTION_BATCH) {
                    for (fid in from until minOf(from + PROJECTION_BATCH, problem.numFactors)) {
                        if (invariants[fid] === NoInvariant) continue
                        val factor = problem.factors[fid]
                        factor.boolVars.forEachVariableOnce(boolSeen, fid) {
                            boolOccurrences[it][boolCounts[it]++] = fid
                        }
                        factor.intVars.forEachVariableOnce(intSeen, fid) { intOccurrences[it][intCounts[it]++] = fid }
                        factor.variables.reals.forEachVariableOnce(realSeen, fid) {
                            realOccurrences[it][realCounts[it]++] = fid
                        }
                    }
                    yield(null)
                }
                yield(LocalSearchProblem(problem, invariants, boolOccurrences, intOccurrences, realOccurrences))
            }.iterator()

        private inline fun IntArray.forEachVariableOnce(seen: IntArray, factorId: Int, action: (Int) -> Unit) {
            // The invariant updates all repeated positions itself; a move dispatches to it once.
            val stamp = factorId + 1
            for (v in this) {
                if (seen[v] == stamp) continue
                seen[v] = stamp
                action(v)
            }
        }

        private suspend fun SequenceScope<LocalSearchProblem?>.allocateOccurrences(counts: IntArray): Array<IntArray> {
            val out = Array(counts.size) { EmptyIntArray }
            for (from in counts.indices step PROJECTION_BATCH) {
                for (v in from until minOf(from + PROJECTION_BATCH, counts.size)) {
                    if (counts[v] != 0) out[v] = IntArray(counts[v])
                }
                yield(null)
            }
            return out
        }
    }
}
