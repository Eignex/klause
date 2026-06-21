package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
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
        BacktrackSolver(problem).solve(BacktrackParams()) is SolveResult.Sat

    private fun checkRoundTrip(name: String, original: Problem, expectMerged: Boolean, expectSat: Boolean) {
        val merge = Presolve.mergeDuplicateColumns(original)
        assertEquals(expectMerged, merge.problem !== original, "$name: merge expectation wrong")
        assertEquals(expectSat, verdictSat(original), "$name: original verdict unexpected")
        assertEquals(verdictSat(original), verdictSat(merge.problem), "$name: verdict changed by merge")
        if (verdictSat(merge.problem)) {
            val reduced = BacktrackSolver(merge.problem).solve(BacktrackParams())
            check(reduced is SolveResult.Sat)
            val full = merge.reconstruct(reduced.assignment)
            assertTrue(isFeasible(original, full), "$name: reconstructed sample infeasible in original")
        }
    }

    /** Soundness over the whole reduced feasible set: every feasible assignment of the reduced problem
     *  reconstructs to a feasible assignment of the original (aggregation is many-to-one, so it does
     *  not recover *every* original solution — see `preservesSolutionSet = false`), and the reduced
     *  problem is feasible exactly when the original is. Small domains only — full enumeration. */
    private fun checkAllReconstructionsFeasible(name: String, original: Problem) {
        val merge = Presolve.mergeDuplicateColumns(original)
        assertTrue(merge.problem !== original, "$name: expected a merge")
        var anyReduced = false
        enumerate(merge.problem.intDomains) { assign ->
            if (isFeasible(merge.problem, Sample(BooleanArray(0), assign))) {
                anyReduced = true
                val full = merge.reconstruct(Sample(BooleanArray(0), assign.copyOf()))
                assertTrue(isFeasible(original, full), "$name: reconstructed $full infeasible in original")
            }
        }
        assertEquals(anyOriginalFeasible(original), anyReduced, "$name: feasibility verdict changed")
    }

    private fun anyOriginalFeasible(problem: Problem): Boolean {
        var any = false
        enumerate(problem.intDomains) { assign ->
            if (isFeasible(problem, Sample(BooleanArray(0), assign))) any = true
        }
        return any
    }

    private fun enumerate(domains: Array<IntDomain>, body: (IntArray) -> Unit) {
        val n = domains.size
        val assign = IntArray(n) { domains[it].min }
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
        val merge = Presolve.mergeDuplicateColumns(problem)
        assertTrue(merge.problem !== problem, "expected a merge")
        assertTrue(
            merge.problem.factors.none { 1 in it.intVars },
            "the dropped duplicate column is absorbed and appears in no factor",
        )
        checkAllReconstructionsFeasible("exact-duplicate", problem)
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
        val merge = DuplicateColumns.mergeDuplicateColumns(problem, objectiveIntVars = setOf(1))
        assertSame(problem, merge.problem, "an objective variable must not be merged")
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
