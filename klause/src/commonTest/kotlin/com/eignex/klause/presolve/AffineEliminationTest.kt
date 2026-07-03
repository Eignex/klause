package com.eignex.klause.presolve

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Table
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Affine variable elimination (#318/#335). Checks that the reduced problem has the same SAT/UNSAT
 * verdict as the original and that a reconstructed solution is genuinely feasible in the original —
 * including folding the affine relation into other linear factors and chained eliminations.
 */
class AffineEliminationTest {

    private fun isFeasible(problem: Problem, sample: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, sample.bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, sample.ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun verdictSat(problem: Problem): Boolean =
        BacktrackSolver(problem).solve(BacktrackParams()) is SolveResult.Sat

    private fun checkRoundTrip(name: String, original: Problem, expectEliminated: Boolean, expectSat: Boolean) {
        val elim = Presolve.eliminateAffineSingletons(original)
        assertEquals(expectEliminated, elim.problem !== original, "$name: elimination expectation wrong")
        assertEquals(expectSat, verdictSat(original), "$name: original verdict unexpected")
        assertEquals(verdictSat(original), verdictSat(elim.problem), "$name: verdict changed by elimination")
        if (verdictSat(elim.problem)) {
            val reduced = BacktrackSolver(elim.problem).solve(BacktrackParams())
            check(reduced is SolveResult.Sat)
            val full = elim.reconstruct(reduced.assignment)
            assertTrue(isFeasible(original, full), "$name: reconstructed sample infeasible in original")
        }
    }

    /** Brute feasible-set preservation: every feasible assignment of the reduced problem reconstructs
     *  to a feasible assignment of the original, and the reconstructed set is *exactly* the original's
     *  feasible set (no solution lost, none invented). Small domains only — full enumeration. */
    private fun checkFeasibleSetPreserved(name: String, original: Problem) {
        val elim = Presolve.eliminateAffineSingletons(original)
        assertTrue(elim.problem !== original, "$name: expected an elimination")
        val origFeasible = feasibleSet(original)
        val reconstructed = HashSet<List<Int>>()
        enumerate(elim.problem.intDomains) { assign ->
            if (isFeasible(elim.problem, Sample(BooleanArray(0), assign))) {
                val full = elim.reconstruct(Sample(BooleanArray(0), assign.copyOf()))
                assertTrue(isFeasible(original, full), "$name: reconstructed $full infeasible in original")
                reconstructed.add(full.ints.toList())
            }
        }
        assertEquals(origFeasible, reconstructed, "$name: feasible set not preserved")
    }

    private fun feasibleSet(problem: Problem): Set<List<Int>> {
        val out = HashSet<List<Int>>()
        enumerate(problem.intDomains) { assign ->
            if (isFeasible(problem, Sample(BooleanArray(0), assign))) out.add(assign.toList())
        }
        return out
    }

    /** Enumerate every integer assignment over [domains] (bounds only; small domains). */
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
    fun `eliminates x = 2y + 1 defined only by its equality`() {
        // x (0) defined by x - 2y = 1; y (1) also bounded y >= 1. x used nowhere else.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.GE, 1),
            ),
        )
        checkRoundTrip("x=2y+1", problem, expectEliminated = true, expectSat = true)
    }

    @Test
    fun `eliminates with a negative unit coefficient`() {
        // -x + 3y = 2  ⇒  x = 3y - 2.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 20), IntDomain(0, 5)),
            factors = listOf(Linear(intArrayOf(-1, 3), intArrayOf(0, 1), LinearOp.EQ, 2)),
        )
        checkRoundTrip("x=3y-2", problem, expectEliminated = true, expectSat = true)
    }

    @Test
    fun `folds x into another linear factor`() {
        // x (0) = 2y+1 from x-2y=1, and x also appears in x <= 8 → folds to 2y+1 <= 8.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 8),
            ),
        )
        checkRoundTrip("fold-into-linear", problem, expectEliminated = true, expectSat = true)
    }

    @Test
    fun `does not eliminate when both equation vars appear in a non-linear factor`() {
        // x (0) and y (1) both appear in an AllDifferent — neither the unit fold (x is in a global) nor
        // the residue elimination (y is not contained) can act, so nothing is eliminated.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 3), IntDomain(0, 5)),
            factors = listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1), // x = 2y+1
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 6), // both in a global
            ),
        )
        checkRoundTrip("vars-in-global", problem, expectEliminated = false, expectSat = true)
    }

    @Test
    fun `aliases x = y out of a non-linear factor`() {
        // x0 = x1 and x0 also in AllDifferent(x0, x2): the alias case substitutes x0 -> x1 into the
        // global via remap, eliminating x0 even though it's not a linear factor.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0), // x0 = x1
                AllDifferent(intArrayOf(0, 2), domainMin = 0, domainSize = 4), // x0 in a global
            ),
        )
        checkRoundTrip("alias-into-global", problem, expectEliminated = true, expectSat = true)
    }

    @Test
    fun `affine-substitutes a shifted index out of an element`() {
        // x = y + 1 and x is an Element index. The shift folds into the element's offset
        // (idx − offset becomes y − (offset − 1)), so x is projected out of the non-linear global.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 3), IntDomain(5, 7), IntDomain(0, 2)),
            factors = listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.EQ, 1), // x = y + 1
                Element(idx = 0, result = 1, arr = intArrayOf(5, 6, 7), arrIsVars = false, indexOffset = 1),
            ),
        )
        checkFeasibleSetPreserved("element-index-shift", problem)
    }

    @Test
    fun `does not affine-substitute a scaled element index`() {
        // x = 2y + 1 as an Element index would reindex the array, which Element cannot represent, so
        // the substitution declines and x is left in place. The extra `y <= 1` keeps y non-contained
        // so the residue-class pass can't eliminate it instead (isolating the Element decline).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 3), IntDomain(5, 7), IntDomain(0, 1)),
            factors = listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 2), LinearOp.EQ, 1), // x = 2y + 1
                Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 1), // y <= 1 (keeps y non-contained)
                Element(idx = 0, result = 1, arr = intArrayOf(5, 6, 7), arrIsVars = false, indexOffset = 1),
            ),
        )
        checkRoundTrip("element-scaled-index", problem, expectEliminated = false, expectSat = true)
    }

    @Test
    fun `affine-substitutes a shifted variable out of a table column`() {
        // x = y + 1 and x is a Table column; each row's x value shifts by -1 so the table constrains y.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(0, 2)),
            factors = listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 2), LinearOp.EQ, 1), // x = y + 1
                Table(intArrayOf(0, 1), intArrayOf(1, 1, 2, 2, 3, 3)), // (x, z) in {(1,1),(2,2),(3,3)}
            ),
        )
        checkFeasibleSetPreserved("table-column-shift", problem)
    }

    @Test
    fun `chained eliminations reconstruct correctly`() {
        // x = 2y+1 and y = z+1: eliminate x, then y (its defining EQ's partner folds), then z stays.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 20), IntDomain(0, 10), IntDomain(0, 4)),
            factors = listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 1),
            ),
        )
        checkRoundTrip("chain", problem, expectEliminated = true, expectSat = true)
    }

    @Test
    fun `eliminates an n-term unit-defined variable used nowhere else`() {
        // x0 = x1 + x2 - x3 (x0 - x1 - x2 + x3 = 0), x0 implied-free (appears in no other factor).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = listOf(Linear(intArrayOf(1, -1, -1, 1), intArrayOf(0, 1, 2, 3), LinearOp.EQ, 0)),
        )
        checkRoundTrip("n-term-contained", problem, expectEliminated = true, expectSat = true)
        checkFeasibleSetPreserved("n-term-contained", problem)
    }

    @Test
    fun `folds an n-term affine relation into another linear factor`() {
        // x0 = x1 + x2 (x0 - x1 - x2 = 0) and x0 also appears in x0 <= 3 → folds to x1 + x2 <= 3.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 6), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
            ),
        )
        checkRoundTrip("n-term-fold", problem, expectEliminated = true, expectSat = true)
        checkFeasibleSetPreserved("n-term-fold", problem)
    }

    @Test
    fun `eliminates an n-term relation with a negative unit pivot`() {
        // -x0 + x1 + x2 = 1  ⇒  x0 = x1 + x2 - 1.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(Linear(intArrayOf(-1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 1)),
        )
        checkRoundTrip("n-term-neg-pivot", problem, expectEliminated = true, expectSat = true)
        checkFeasibleSetPreserved("n-term-neg-pivot", problem)
    }

    @Test
    fun `does not eliminate an n-term equality with no unit pivot`() {
        // 2x0 + 3x1 + 4x2 = 12: no coefficient is +/-1, so no variable can be projected out integrally.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 6), IntDomain(0, 4), IntDomain(0, 3)),
            factors = listOf(Linear(intArrayOf(2, 3, 4), intArrayOf(0, 1, 2), LinearOp.EQ, 12)),
        )
        checkRoundTrip("n-term-no-unit", problem, expectEliminated = false, expectSat = true)
    }

    @Test
    fun `eliminates a contained non-unit pivot whose coefficient divides all partners and the bound`() {
        // 2x0 - 4x1 - 6x2 = 8  ⇒  x0 = 2x1 + 3x2 + 4, integral for every partner assignment since 2
        // divides each coefficient and the bound; x0 is contained, so it is projected out (#601).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 20), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(Linear(intArrayOf(2, -4, -6), intArrayOf(0, 1, 2), LinearOp.EQ, 8)),
        )
        checkRoundTrip("nonunit-divides-all", problem, expectEliminated = true, expectSat = true)
        checkFeasibleSetPreserved("nonunit-divides-all", problem)
    }

    @Test
    fun `does not eliminate a contained non-unit pivot that does not divide a partner`() {
        // 2x0 + 4x1 + 3x2 = 8: the pivot 2 divides the bound and x1's coefficient but not x2's, so the
        // fold would be non-integral. x0 is contained but stays — only the residue doubleton path could
        // act, and it needs a two-term equality, so nothing is eliminated.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 6), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(Linear(intArrayOf(2, 4, 3), intArrayOf(0, 1, 2), LinearOp.EQ, 8)),
        )
        checkRoundTrip("nonunit-partner-indivisible", problem, expectEliminated = false, expectSat = true)
    }

    @Test
    fun `does not eliminate a contained non-unit pivot that does not divide the bound`() {
        // 2x0 - 4x1 - 6x2 = 7: 2 divides every partner coefficient but not the odd bound, so no integer
        // x0 exists for any partner assignment — the projection would be unsound, so x0 is left in place
        // (the equality is correctly unsatisfiable and the verdict is preserved).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 20), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(Linear(intArrayOf(2, -4, -6), intArrayOf(0, 1, 2), LinearOp.EQ, 7)),
        )
        checkRoundTrip("nonunit-bound-indivisible", problem, expectEliminated = false, expectSat = false)
    }

    @Test
    fun `does not eliminate a non-unit pivot when neither equation var is contained`() {
        // 2x0 - 4x1 = 8 with BOTH x0 and x1 pinned into a global: the divisibility holds, but a non-unit
        // fold needs a contained pivot it can absorb, and neither var is contained, so neither the
        // non-unit unit-loop case nor the residue doubleton can act — nothing is eliminated.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 12), IntDomain(0, 3), IntDomain(0, 12)),
            factors = listOf(
                Linear(intArrayOf(2, -4), intArrayOf(0, 1), LinearOp.EQ, 8),
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 13),
            ),
        )
        checkRoundTrip("nonunit-not-contained", problem, expectEliminated = false, expectSat = true)
    }

    @Test
    fun `does not eliminate a non-unit pivot whose objective variable is protected`() {
        // 2x0 - 4x1 - 6x2 = 8 with x0 in the objective: even though x0 is contained and the divisibility
        // holds, an objective variable is never eliminated, so the equality survives untouched.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 20), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(Linear(intArrayOf(2, -4, -6), intArrayOf(0, 1, 2), LinearOp.EQ, 8)),
        )
        val elim = Presolve.eliminateAffineSingletons(problem, objectiveIntVars = setOf(0))
        assertTrue(elim.problem === problem, "objective-protected: must not eliminate x0")
    }

    @Test
    fun `aggregates a chain of equalities to a single representative`() {
        // x0 = x1, x1 = x2, x2 = x3: every link is an alias, so the whole chain collapses onto x3 and a
        // reconstructed solution restores x0..x2 from it.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 0),
                Linear(intArrayOf(1, -1), intArrayOf(2, 3), LinearOp.EQ, 0),
            ),
        )
        checkRoundTrip("alias-chain", problem, expectEliminated = true, expectSat = true)
        checkFeasibleSetPreserved("alias-chain", problem)
    }

    @Test
    fun `eliminates a non-unit doubleton via residue-class restriction`() {
        // 2x + 3y = 12 has no unit pivot, but x is contained, so x = (12 - 3y)/2 — an integer only for
        // even y. Eliminate x, restrict y to {0,2,4} (the residue class keeping x in [0,6]), and
        // reconstruct x with the divisor (#522).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 6), IntDomain(0, 4)),
            factors = listOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.EQ, 12)),
        )
        checkRoundTrip("residue", problem, expectEliminated = true, expectSat = true)
        checkFeasibleSetPreserved("residue", problem)
    }

    @Test
    fun `residue elimination preserves an unsatisfiable doubleton`() {
        // 2x + 2y = 3 has an odd right-hand side, so no y admits an integer x — no residue class exists,
        // nothing is eliminated, and the (correctly unsatisfiable) verdict is preserved.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.EQ, 3)),
        )
        checkRoundTrip("residue-unsat", problem, expectEliminated = false, expectSat = false)
    }

    @Test
    fun `does not residue-eliminate a non-unit doubleton when neither var is contained`() {
        // 2x + 3y = 12 with both x and y also pinned into a global: neither is contained, so no
        // non-integer fold is possible and nothing is eliminated.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 6), IntDomain(0, 4), IntDomain(0, 6)),
            factors = listOf(
                Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.EQ, 12),
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 7),
            ),
        )
        checkRoundTrip("residue-not-contained", problem, expectEliminated = false, expectSat = true)
    }

    @Test
    fun `does not divide by a zero pivot coefficient`() {
        // 0*x0 - 2*x1 = 1: a term with a zero coefficient is non-unit, so the pivot loop reaches the
        // divisibility test for x0; the zero coefficient can never be a pivot and must not be used as a
        // modulus (#872). The equality (-2*x1 = 1) is unsatisfiable, and that verdict is preserved.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 3)),
            factors = listOf(Linear(intArrayOf(0, -2), intArrayOf(0, 1), LinearOp.EQ, 1)),
        )
        checkRoundTrip("zero-pivot", problem, expectEliminated = false, expectSat = false)
    }

    @Test
    fun `preserves an unsat verdict`() {
        // x = 2y + 1 with x's domain forcing x even-only via tight bounds that y can't meet.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(4, 4), IntDomain(0, 3)), // x pinned to 4, but 2y+1 is odd
            factors = listOf(Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1)),
        )
        checkRoundTrip("unsat", problem, expectEliminated = true, expectSat = false)
    }
}
