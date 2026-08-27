package com.eignex.klause.presolve.structural

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.SharedIntOccurrence
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Duplicate-column aggregation. Folding two coinciding columns into one aggregate must preserve the
 * SAT/UNSAT verdict, and every reconstructed solution of the reduced problem must be feasible in the
 * original (the aggregation is many-to-one, so it preserves feasibility rather than the full solution
 * set — checked over the whole reduced feasible set on small domains).
 */
class DuplicateColumnsTest {

    private fun isFeasible(problem: Problem, sample: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, sample.bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, sample.ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun verdictSat(problem: Problem): Boolean =
        BacktrackSolver(problem.bake()).solve(BacktrackParams()) is SolveResult.Sat

    private fun checkRoundTrip(name: String, original: Problem, expectMerged: Boolean, expectSat: Boolean) {
        val delta = Presolve.mergeDuplicateColumns(original)
        assertEquals(expectMerged, !delta.isEmpty, "$name: merge expectation wrong")
        val reduced = original.withPassDelta(delta, BakeConfig.NONE)
        val reconstruct = delta.reconstruct ?: { it }
        assertEquals(expectSat, verdictSat(original), "$name: original verdict unexpected")
        assertEquals(verdictSat(original), verdictSat(reduced), "$name: verdict changed by merge")
        if (verdictSat(reduced)) {
            val solved = BacktrackSolver(reduced.bake()).solve(BacktrackParams())
            check(solved is SolveResult.Sat)
            val full = reconstruct(solved.assignment)
            assertTrue(isFeasible(original, full), "$name: reconstructed sample infeasible in original")
        }
    }

    /** Soundness over the whole reduced feasible set: every feasible assignment of the reduced problem
     *  reconstructs to a feasible assignment of the original (aggregation is many-to-one, so it does
     *  not recover *every* original solution — see `preservesSolutionSet = false`), and the reduced
     *  problem is feasible exactly when the original is. Small domains only — full enumeration. */
    private fun checkAllReconstructionsFeasible(name: String, original: Problem) {
        val delta = Presolve.mergeDuplicateColumns(original)
        assertTrue(!delta.isEmpty, "$name: expected a merge")
        val reduced = original.withPassDelta(delta, BakeConfig.NONE)
        val reconstruct = delta.reconstruct ?: { it }
        var anyReduced = false
        enumerate(reduced.requireFiniteIntDomains()) { assign ->
            if (isFeasible(reduced, Sample(BooleanArray(0), assign))) {
                anyReduced = true
                val full = reconstruct(Sample(BooleanArray(0), assign.copyOf()))
                assertTrue(isFeasible(original, full), "$name: reconstructed $full infeasible in original")
            }
        }
        assertEquals(anyOriginalFeasible(original), anyReduced, "$name: feasibility verdict changed")
    }

    private fun anyOriginalFeasible(problem: Problem): Boolean {
        var any = false
        enumerate(problem.requireFiniteIntDomains()) { assign ->
            if (isFeasible(problem, Sample(BooleanArray(0), assign))) any = true
        }
        return any
    }

    private fun enumerate(domains: Array<IntDomain>, body: (LongArray) -> Unit) {
        val n = domains.size
        val assign = LongArray(n) { domains[it].min }
        while (true) {
            body(assign)
            var i = 0
            while (i < n) {
                if (assign[i] < domains[i].max) {
                    assign[i]++
                    break
                }
                assign[i] = domains[i].min
                i++
            }
            if (i == n) return
        }
    }

    @Test
    fun `merges two exact-duplicate columns into one aggregate`() {
        // x (0) and y (1) occur in exactly the same rows with the same coefficient (x + y + z <= 4 and
        // x + y >= 1), never elsewhere — duplicate columns. They aggregate into z' = x + y, widening
        // x's domain to the Minkowski sum [0,6] and dropping y's term from each row.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        val reduced = problem.withPassDelta(Presolve.mergeDuplicateColumns(problem), BakeConfig.NONE)
        assertTrue(reduced !== problem, "expected a merge")
        assertTrue(
            reduced.factors.none { 1 in it.intVars },
            "the dropped duplicate column is absorbed and appears in no factor",
        )
        checkAllReconstructionsFeasible("exact-duplicate", problem)
    }

    @Test
    fun `leaves duplicate columns alone when their aggregate domain would overflow`() {
        // Both columns are pinned at the open-domain clamp value, so they are singletons — contiguous,
        // hence eligible — whose Minkowski sum reaches 2^63 and wraps to Long.MIN_VALUE. The aggregate is
        // then a well-formed singleton at the wrong value, which is why it reads as a plain UNSAT.
        val pinned = IntDomain(1L shl 62, 1L shl 62)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(pinned, pinned),
            factors = listOf(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1)),
        )
        val delta = Presolve.mergeDuplicateColumns(problem)
        val aggregates = delta.domains ?: problem.requireFiniteIntDomains()
        assertTrue(
            aggregates.all { it.min >= 0L },
            "aggregating two non-negative columns must not wrap to a negative domain",
        )
    }

    @Test
    fun `does not merge columns that differ in one factor`() {
        // x (0) and y (1) share a row x + y + z <= 5 but x carries coefficient 2 there and y carries 1
        // — the columns differ, so they are not duplicates and nothing is merged.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(2, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 5),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        checkRoundTrip("differ-one-factor", problem, expectMerged = false, expectSat = true)
    }

    @Test
    fun `does not merge a variable the objective reads`() {
        // x (0) and y (1) are duplicate columns, but y is read by the objective, so folding it into x
        // would silently rewrite the objective — skip the merge.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        val delta = DuplicateColumns.mergeDuplicateColumns(problem, objectiveIntVars = setOf(1))
        assertTrue(delta.isEmpty, "an objective variable must not be merged")
    }

    @Test
    fun `does not merge a variable in a non-linear factor`() {
        // x (0) and y (1) are duplicate columns in the linear rows, but x also sits in an AllDifferent,
        // which reads it value-wise rather than as a column coefficient — so it is ineligible.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                AllDifferent(intArrayOf(0, 2), domainMin = 0, domainSize = 4),
            ),
        )
        checkRoundTrip("var-in-global", problem, expectMerged = false, expectSat = true)
    }

    @Test
    fun `is a no-op on a problem with no duplicate columns`() {
        // Two distinct single-variable rows with no shared structure: no columns coincide.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = listOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
                Linear(intArrayOf(2), intArrayOf(1), LinearOp.GE, 4),
            ),
        )
        checkRoundTrip("no-op", problem, expectMerged = false, expectSat = true)
    }

    @Test
    fun `reconstructs a split that satisfies every original row`() {
        // x (0) and y (1) are duplicate columns across x + y + z <= 4 and x + y >= 3. After
        // aggregating to z' = x + y in [0,6], the reduced solve picks some z', and the contiguous
        // split must hand back an (x, y) feasible in both original rows.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 2)),
            factors = listOf(
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
            ),
        )
        checkRoundTrip("reconstruct", problem, expectMerged = true, expectSat = true)
        checkAllReconstructionsFeasible("reconstruct", problem)
    }

    @Test
    fun `collapses a chain of three duplicate columns in one pass`() {
        // x (0), y (1), w (2) occur in exactly the same rows with the same coefficient — a chain of
        // three duplicate columns. A single pass folds all three into one aggregate (the fixpoint
        // re-signs w against the x+y aggregate), and every reconstruction splits the aggregate back to
        // a feasible triple. u (3) shares only the first row, so it is not a duplicate and stays.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, 1, 1, 1), intArrayOf(0, 1, 2, 3), LinearOp.LE, 6),
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 2),
            ),
        )
        val reduced = problem.withPassDelta(Presolve.mergeDuplicateColumns(problem), BakeConfig.NONE)
        assertTrue(
            reduced.factors.none { 1 in it.intVars || 2 in it.intVars },
            "both dropped duplicates are absorbed in a single pass",
        )
        checkRoundTrip("chain-of-three", problem, expectMerged = true, expectSat = true)
        checkAllReconstructionsFeasible("chain-of-three", problem)
    }

    /** The int-variable occurrence CSR the incremental session hands a pass: for each variable, the
     *  ascending factor indices mentioning it — matching what a fresh scan over `problem.factors` builds. */
    private fun sharedOcc(problem: Problem): SharedIntOccurrence {
        val n = problem.numIntVars
        val offsets = IntArray(n + 1)
        for (f in problem.factors) for (v in f.intVars) offsets[v + 1]++
        for (v in 0 until n) offsets[v + 1] += offsets[v]
        val cursor = offsets.copyOf()
        val flat = IntArray(offsets[n])
        problem.factors.forEachIndexed { fid, f -> for (v in f.intVars) flat[cursor[v]++] = fid }
        return SharedIntOccurrence(offsets, flat)
    }

    @Test
    fun `bails on a re-run when no touched column is eligible`() {
        // x (0) and y (1) are duplicate columns a full scan would merge. On a re-run whose only touched
        // variable is z (2) — read value-wise by an AllDifferent, so column-ineligible — the fast-bail
        // returns empty: an already-collapsed class only re-forms on a touched eligible column.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                AllDifferent(intArrayOf(2, 3), domainMin = 0, domainSize = 4),
            ),
        )
        assertTrue(!Presolve.mergeDuplicateColumns(problem).isEmpty, "a full scan merges the duplicate columns")
        val delta = DuplicateColumns.mergeDuplicateColumns(
            problem,
            sharedIntOcc = sharedOcc(problem),
            incrementalTouchedVars = intArrayOf(2),
        )
        assertTrue(delta.isEmpty, "no touched eligible column: the re-run bails")
    }

    @Test
    fun `re-runs the full merge when a touched column is eligible`() {
        // Same duplicate columns; the re-run's touched set includes the eligible column x (0), so the
        // fast-bail falls through to the full scan and produces the identical merge (same dropped rows).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        val full = Presolve.mergeDuplicateColumns(problem)
        val incremental = DuplicateColumns.mergeDuplicateColumns(
            problem,
            sharedIntOcc = sharedOcc(problem),
            incrementalTouchedVars = intArrayOf(0),
        )
        assertTrue(!incremental.isEmpty, "a touched eligible column falls through to the merge")
        assertEquals(
            full.droppedIndices.toList(),
            incremental.droppedIndices.toList(),
            "the incremental re-run drops the same rows as the full scan",
        )
    }

    @Test
    fun `preserves an unsat verdict`() {
        // x + y + z >= 7 with all domains [0,2]: x and y are duplicate columns of the single row, so
        // they aggregate to z' in [0,4]; z' + z >= 7 with z <= 2 stays unreachable (max 6), preserving
        // the unsatisfiable verdict.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = listOf(Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 7)),
        )
        checkRoundTrip("unsat", problem, expectMerged = true, expectSat = false)
    }
}
