package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.relaxation.leafRealFeasibility
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The hybrid MIP/CP leaf verdict (issue #1232): a Linear row with an LP-only continuous term does not
 * propagate in CP, so a CP-consistent leaf is a solution only if the residual real LP is feasible. These
 * exercise that verdict — feasible ⇒ SAT, exact-infeasible ⇒ UNSAT, uncertifiable ⇒ UNKNOWN — and that
 * local search declines a model it cannot evaluate.
 */
class LpOnlyContinuousLeafTest {

    private fun problem(numInt: Int, intDoms: Array<IntDomain>, realLo: Double, realHi: Double, row: Linear) = Problem(
        numBoolVars = 0,
        numIntVars = numInt,
        intDomains = intDoms,
        factors = arrayOf<Factor>(row),
        numRealVars = 1,
        realLower = doubleArrayOf(realLo),
        realUpper = doubleArrayOf(realHi),
    )

    @Test
    fun `feasible residual real LP is certified SAT via the exact basis solve`() {
        // x in [0,3], r in [0,10], row x + r <= 5 is feasible (r = 0); the exact basis reconstruction
        // certifies the continuous point, so the leaf is a genuine solution.
        val row = Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.LE, 5L)
        val p = problem(1, arrayOf(IntDomain(0, 3)), 0.0, 10.0, row)
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `equality-constrained continuous vertex with a rational value is certified SAT`() {
        // 2 r = 3 forces r = 3/2 — a non-integer vertex whose exact value has denominator det(B) = 2;
        // the fraction-free basis solve certifies it feasible (0 <= 3/2 <= 10).
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(2.0), intArrayOf(0), LinearOp.EQ, 3L)
        val p = problem(0, emptyArray(), 0.0, 10.0, row)
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a rational leaf witness is read back in real source coordinates`() {
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0 / 3.0), intArrayOf(0), LinearOp.EQ, 3L)
        val p = problem(0, emptyArray(), 7.0, 10.0, row)

        val result = leafRealFeasibility(p, objective = null, sample = Sample(booleanArrayOf(), longArrayOf()))

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(9.0, result.reals[0], absoluteTolerance = 1e-9)
    }

    @Test
    fun `a residual leaf solve is reported as standalone LP work`() {
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(2.0), intArrayOf(0), LinearOp.EQ, 3L)
        val p = problem(0, emptyArray(), 0.0, 10.0, row)
        val sink = LpStatsSink()

        val result = leafRealFeasibility(
            p,
            objective = null,
            sample = Sample(booleanArrayOf(), longArrayOf()),
            sink = sink,
        )

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        val stats = sink.snapshot()
        assertEquals(1.0, stats.standalonePasses.sum)
        assertEquals(0.0, stats.solves.sum)
    }

    @Test
    fun `an infeasible system over a lower-unbounded real column is unsat`() {
        // x >= 6 and x <= 5 over x in (-inf, +inf): once, folding the probe stand-in into the rhs
        // doubles collapsed both rows onto the same right-hand side and the leaf blessed a false SAT.
        val geSix = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 6.0)
        val leFive = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 5.0)
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(geSix, leFive),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a negative witness on a lower-unbounded real column reads back through the split`() {
        val leMinusSix = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, -6.0)
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(leMinusSix),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val r = assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
        assertEquals(true, r.assignment.reals[0] <= -6.0 + 1e-9)
    }

    @Test
    fun `a finite upper bound on a lower-unbounded real column is enforced`() {
        val geSeven = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 7.0)
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(geSeven),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(5.0),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `infeasible residual real LP is certified UNSAT`() {
        // No discrete variables; r in [0,1] with r >= 5 has no feasible point — exact Farkas certifies it.
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 5L)
        val p = problem(0, emptyArray(), 0.0, 1.0, row)
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a coefficient outside every scaling ladder is decided by the rational fallback`() {
        // 1/3 fits neither the dyadic nor the decimal rationalization ladder, so the scaled-integer
        // certifiers decline; the exact rational simplex reads the double as the rational it is and
        // refutes r/3 >= 1 over r in [0,2] outright.
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0 / 3.0), intArrayOf(0), LinearOp.GE, 1L)
        val p = problem(0, emptyArray(), 0.0, 2.0, row)
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a tripped deadline degrades the leaf verdict to INDETERMINATE rather than certifying`() {
        // 2 r = 3 is feasible (r = 3/2) and normally certified SAT; a fired cancellation cuts the residual
        // LP solve short so the leaf is INDETERMINATE (unknown) — bounding a large leaf LP, never unsound.
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(2.0), intArrayOf(0), LinearOp.EQ, 3L)
        val p = problem(0, emptyArray(), 0.0, 10.0, row)
        val res = leafRealFeasibility(
            p,
            objective = null,
            sample = Sample(booleanArrayOf(), longArrayOf()),
            cancellation = Cancellation { true },
        )
        assertEquals(LpVerdict.INDETERMINATE, res.verdict)
    }

    @Test
    fun `a leaf no theory could decide reports unknown rather than unsat`() {
        // The contract every later theory rests on: failing to decide a leaf is not a refutation. Here
        // the residual real model is past RESIDUAL_MAX_ROWS, so the leaf certify declines outright — a
        // deterministic decline rather than a cancelled one, so the search still exhausts normally and
        // the verdict comes from the undecided leaf and nothing else.
        val rows = Array<Factor>(1001) {
            Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(0), LinearOp.LE, 5L)
        }
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1)),
            factors = rows,
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(10.0),
        )
        val result = BacktrackSolver(p.bake()).solve(BacktrackParams(randomSeed = 1L))
        val unknown = assertIs<SolveResult.Unknown>(result, "an undecided leaf must never read as UNSAT")
        assertEquals(TerminationReason.Unsupported, unknown.reason)
    }

    @Test
    fun `the continuous leaf verdict is the same with LP bounding off and on`() {
        // LP bounding (`--lp off` vs on) is a branch-and-bound search accelerator, not the continuous
        // feasibility decision — that lives in the leaf solve, which runs whenever there are real vars.
        // So toggling bounding must not change the verdict: feasible stays SAT, infeasible stays UNSAT.
        val feasible = Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.LE, 5L)
        val infeasible = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 5L)
        for (bounding in booleanArrayOf(false, true)) {
            val params = BacktrackParams(lpPlan = LpPlan(bounding = bounding))
            assertIs<SolveResult.Sat>(
                BacktrackSolver(problem(1, arrayOf(IntDomain(0, 3)), 0.0, 10.0, feasible).bake()).solve(params),
            )
            assertIs<SolveResult.Unsat>(
                BacktrackSolver(problem(0, emptyArray(), 0.0, 1.0, infeasible).bake()).solve(params),
            )
        }
    }

    @Test
    fun `a continuous objective no row bounds below ends the minimize unbounded with its ray`() {
        // x in [0,3], r >= 0 with x - r <= 1: minimizing -r lets r grow without limit from any leaf.
        val row = Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0), LinearOp.LE, 1L)
        val p = problem(1, arrayOf(IntDomain(0, 3)), 0.0, Double.POSITIVE_INFINITY, row)

        val result = BacktrackSolver(p.bake()).minimize(LinearObjective(realCoefficients = doubleArrayOf(-1.0)))

        val unbounded = assertIs<MinimizeResult.Unbounded>(result)
        assertTrue(unbounded.direction.single().signum() > 0)
        assertTrue(unbounded.sample.ints[0] - unbounded.sample.reals[0] <= 1.0)
    }

    @Test
    fun `a leaf only tolerance accepts completes the minimize as optimal`() {
        // 3 r = 1 has the exact optimum r = 1/3, which a vetoing policy keeps every exact certifier from proving.
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(3.0), intArrayOf(0), LinearOp.EQ, 1L)
        val p = problem(0, emptyArray(), 0.0, 2.0, row).bake()
        val vetoed = LpSolveContext(certificationPolicy = LpCertificationPolicy { _, _ -> false })
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val cases = listOf<Pair<((Sample) -> Boolean)?, Boolean>>(null to false, { _: Sample -> true } to true)
        for ((check, optimal) in cases) {
            val result = BacktrackSolver(p, vetoed).minimize(objective, BacktrackParams(toleranceCheck = check))

            assertEquals(optimal, result is MinimizeResult.Optimal, "tolerance check present: ${check != null}")
        }
    }

    @Test
    fun `local search declines a model with LP-only continuous variables`() {
        val row = Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.LE, 5L)
        val p = problem(1, arrayOf(IntDomain(0, 3)), 0.0, 10.0, row)
        val r = assertIs<SolveResult.Unknown>(LocalSearchSolver(p.bake()).solve())
        assertEquals(TerminationReason.Unsupported, r.reason)
    }
}
