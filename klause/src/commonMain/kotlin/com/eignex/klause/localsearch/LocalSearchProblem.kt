package com.eignex.klause.localsearch

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.util.EmptyIntArray

private const val PROJECTION_BATCH = 256

/** Local-search-engine projection of an immutable `Problem`. */
class LocalSearchProblem private constructor(
    /** Immutable model data compiled by this projection. */
    val problem: Problem,
    /** One local-search invariant per model factor. */
    val invariants: Array<out Invariant>,
    internal val selection: PreparedFactorProjections?,
    /** Invariant occurrences indexed by Boolean variable. */
    val boolOccurrences: Array<IntArray>,
    /** Invariant occurrences indexed by integer variable. */
    val intOccurrences: Array<IntArray>,
    /** Invariant occurrences indexed by real variable; empty for a model with no continuous column. */
    val realOccurrences: Array<IntArray>,
) {
    private constructor(compiled: LocalSearchProblem) : this(
        compiled.problem, compiled.invariants, compiled.selection, compiled.boolOccurrences,
        compiled.intOccurrences, compiled.realOccurrences,
    )

    /** Compile [problem], retaining exact linear rows when sums over [domains] can leave the 64-bit range. */
    constructor(problem: Problem, domains: Array<IntDomain>? = null) :
        this(preparation(problem, domains).asSequence().filterNotNull().first())

    /** Compile private scoring mechanisms from the selected canonical source. */
    constructor(problem: Problem, selection: PreparedFactorProjections, domains: Array<IntDomain>? = null) :
        this(preparation(problem, domains, selection).asSequence().filterNotNull().first())

    internal companion object {
        fun preparation(
            problem: Problem,
            domains: Array<IntDomain>?,
            selection: PreparedFactorProjections? = (problem as? BakedProblem)?.factorProjections,
        ): Iterator<LocalSearchProblem?> =
            sequence<LocalSearchProblem?> {
                selection?.requireSource(problem)
                val invariants = arrayOfNulls<Invariant>(problem.numFactors)
                yield(null)
                val boolCounts = IntArray(problem.numBoolVars)
                yield(null)
                val intCounts = IntArray(problem.numIntVars)
                yield(null)
                val realCounts = IntArray(problem.numRealVars)
                yield(null)
                val boolSeen = IntArray(problem.numBoolVars)
                yield(null)
                val intSeen = IntArray(problem.numIntVars)
                yield(null)
                val realSeen = IntArray(problem.numRealVars)
                yield(null)
                for (from in 0 until problem.numFactors step PROJECTION_BATCH) {
                    for (fid in from until minOf(from + PROJECTION_BATCH, problem.numFactors)) {
                        val factor = problem.factors[fid]
                        invariants[fid] = selection?.invariant(fid, domains) ?: factor.invariantProjection(domains)
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
                for (counts in listOf(boolCounts, intCounts, realCounts, boolSeen, intSeen, realSeen)) {
                    for (from in counts.indices step PROJECTION_BATCH) {
                        counts.fill(0, from, minOf(from + PROJECTION_BATCH, counts.size))
                        yield(null)
                    }
                }
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
                @Suppress("UNCHECKED_CAST")
                yield(LocalSearchProblem(
                    problem, invariants as Array<Invariant>, selection, boolOccurrences, intOccurrences, realOccurrences,
                ))
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
            val out = arrayOfNulls<IntArray>(counts.size)
            yield(null)
            for (from in counts.indices step PROJECTION_BATCH) {
                for (v in from until minOf(from + PROJECTION_BATCH, counts.size)) {
                    out[v] = if (counts[v] == 0) EmptyIntArray else IntArray(counts[v])
                }
                yield(null)
            }
            @Suppress("UNCHECKED_CAST")
            return out as Array<IntArray>
        }
    }
}
