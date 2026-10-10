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
        fun preparation(problem: Problem, domains: Array<IntDomain>?): Iterator<LocalSearchProblem?> = sequence<LocalSearchProblem?> {
            val invariants = Array<Invariant>(problem.numFactors) { NoInvariant }
            val boolCounts = IntArray(problem.numBoolVars)
            val intCounts = IntArray(problem.numIntVars)
            val realCounts = IntArray(problem.numRealVars)
            for (from in 0 until problem.numFactors step PROJECTION_BATCH) {
                for (fid in from until minOf(from + PROJECTION_BATCH, problem.numFactors)) {
                    val factor = problem.factors[fid]
                    invariants[fid] = factor.invariantProjection(domains)
                    if (invariants[fid] === NoInvariant) continue
                    for (v in factor.boolVars) boolCounts[v]++
                    for (v in factor.intVars) intCounts[v]++
                    for (v in factor.variables.reals) realCounts[v]++
                }
                yield(null)
            }
            val boolOccurrences = allocateOccurrences(boolCounts)
            val intOccurrences = allocateOccurrences(intCounts)
            val realOccurrences = allocateOccurrences(realCounts)
            boolCounts.fill(0)
            intCounts.fill(0)
            realCounts.fill(0)
            for (from in 0 until problem.numFactors step PROJECTION_BATCH) {
                for (fid in from until minOf(from + PROJECTION_BATCH, problem.numFactors)) {
                    if (invariants[fid] === NoInvariant) continue
                    val factor = problem.factors[fid]
                    for (v in factor.boolVars) boolOccurrences[v][boolCounts[v]++] = fid
                    for (v in factor.intVars) intOccurrences[v][intCounts[v]++] = fid
                    for (v in factor.variables.reals) realOccurrences[v][realCounts[v]++] = fid
                }
                yield(null)
            }
            yield(LocalSearchProblem(problem, invariants, boolOccurrences, intOccurrences, realOccurrences))
        }.iterator()

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
