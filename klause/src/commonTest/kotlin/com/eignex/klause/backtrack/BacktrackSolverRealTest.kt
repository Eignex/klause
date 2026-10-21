package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.VariableSelector
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.lp.bounding.LpPlan
import com.eignex.klause.lp.engine.LpCertificationPolicy
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.relaxation.leafRealFeasibility
import com.eignex.klause.portfolio.PoolClauseExchange
import com.eignex.klause.portfolio.SharedClausePool
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.search.BranchingConflict
import com.eignex.klause.solver.search.VarRef
import com.eignex.klause.solver.validation.verifyRealCoordinates
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BacktrackSolverRealTest {
    @Test
    fun `source real prefix clauses should share only without assumptions`() {
        for (conditioned in listOf(false, true)) {
            val source = Problem(3, 0, emptyArray(), Array(1002) { row ->
                ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                    if (row == 1) LinearOp.GE else LinearOp.LE,
                    when (row) {
                        0 -> BigFraction.ZERO
                        1 -> BigFraction.ONE
                        else -> BigFraction.ofLong(10)
                    }, activator = if (row < 2) row else -1)
            }, numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
            val pool = SharedClausePool(shareMaxLbd = 0, shareMaxLen = 0)
            val assumptions = if (conditioned) Assumptions(bools = mapOf(2 to true)) else Assumptions.None

            val result = BacktrackSolver(source.bake()).solve(BacktrackParams(randomSeed = 1,
                variableSelector = InputOrder, valueSelector = IndomainMax, phaseSaving = false,
                targetPhasing = false, nodeBudget = NodeBudget(3), assumptions = assumptions,
                clauseExchange = PoolClauseExchange(pool)))

            val sat = assertIs<SolveResult.Sat>(result)
            assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(source, sat.assignment))
            val peer = PropagationSession(source)
            assertIs<PropagationResult.Implied>(peer.seed(Assumptions.None))
            PoolClauseExchange(pool).onSearchStart(peer)
            assertIs<PropagationResult.Implied>(peer.pinBool(0, true))
            assertEquals(if (conditioned) null else false, peer.boolValue(1))
            assertNull(peer.boolValue(2))
        }
    }

    @Test
    fun `source real clauses should retain integer guards in a fresh peer`() {
        val source = Problem(2, 2, Array(2) { IntDomain(0, 1) }, Array<Factor>(1002) {
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1),
                Term.ofRealVar(2), Term.ofIntVar(0)), List(4) { BigFraction.ONE },
                LinearOp.GE, BigFraction.ONE)
        } + arrayOf<Factor>(
            ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                LinearOp.EQ, BigFraction.ZERO),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
        ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val pool = SharedClausePool(shareMaxLbd = 0, shareMaxLen = 0)

        val result = BacktrackSolver(source.bake()).solve(BacktrackParams(randomSeed = 1,
            variableSelector = InputOrder, valueSelector = IndomainMin, phaseSaving = false,
            targetPhasing = false, nodeBudget = NodeBudget(8), clauseExchange = PoolClauseExchange(pool)))

        val sat = assertIs<SolveResult.Sat>(result)
        assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(source, sat.assignment))
        val peer = PropagationSession(source)
        assertIs<PropagationResult.Implied>(peer.seed(Assumptions.None))
        peer.boundGeLit(1, 1, true)
        PoolClauseExchange(pool).onSearchStart(peer)
        assertIs<PropagationResult.Implied>(peer.pinInt(1, 0))
        assertEquals(1L, peer.intDomain(0).min)
        assertEquals(1L, peer.intDomain(0).max)
    }

    @Test
    fun `a partial real point should guide an unobserved integer without a conflict`() {
        val source = Problem(2, 1, arrayOf(IntDomain(0, 1)), Array<Factor>(1002) {
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1),
                Term.ofRealVar(2), Term.ofIntVar(0)), List(4) { BigFraction.ONE },
                LinearOp.GE, BigFraction.ONE)
        } + Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            numRealVars = 3, realLower = DoubleArray(3), realUpper = DoubleArray(3))

        val result = BacktrackSolver(source.bake()).solve(BacktrackParams(randomSeed = 1,
            variableSelector = InputOrder, valueSelector = IndomainMin,
            phaseSaving = true, nodeBudget = NodeBudget(8)))

        val sat = assertIs<SolveResult.Sat>(result)
        assertEquals(1L, sat.assignment.intValue(0))
        assertEquals(0L, sat.stats.search.fails.sum.toLong())
        val outcomes = sat.stats.lp.phases["PREFIX_REAL_HINT_ROOT"]?.outcomes
        assertTrue((outcomes?.get("PHASE_INTEGER_FALLBACK") ?: 0L) > 0L)
        assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(source, sat.assignment))
    }

    @Test
    fun `a partial real point should guide an unobserved Boolean phase without a conflict`() {
        for (integers in 0..1) {
            val source = Problem(3, integers, Array(integers) { IntDomain(0, 1) }, Array<Factor>(1002) { row ->
                ExactLinear.of(IntArray(3 + integers) { if (it < 3) Term.ofRealVar(it) else Term.ofIntVar(0) },
                    List(3 + integers) { BigFraction.ONE },
                    LinearOp.GE, BigFraction.ONE, activator = if (row == 0) 1 else -1)
            } + Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
                realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })

            val result = BacktrackSolver(source.bake()).solve(BacktrackParams(randomSeed = 1,
                variableSelector = InputOrder, valueSelector = IndomainMin,
                phaseSaving = true, nodeBudget = NodeBudget(8)))

            val sat = assertIs<SolveResult.Sat>(result)
            assertEquals(true, sat.assignment.bools[1])
            assertEquals(0L, sat.stats.search.fails.sum.toLong())
            assertTrue((sat.stats.lp.phases["PREFIX_REAL_HINT_ROOT"]?.outcomes?.get("PHASE_FALLBACK") ?: 0L) > 0L)
            assertTrue((sat.stats.lp.phases["PREFIX_REAL_HINT_ROOT"]?.outcomes
                ?.get("SELECTED_GUARD_VARIABLE") ?: 0L) > 0L)
            assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(source, sat.assignment))
        }
    }

    @Test
    fun `selected real conflicts should backjump before unrelated Boolean decisions`() {
        val source = Problem(3, 0, emptyArray(), Array(1002) { row ->
            ExactLinear.of(IntArray(3) { Term.ofRealVar(it) }, List(3) { BigFraction.ONE },
                if (row == 1) LinearOp.GE else LinearOp.LE,
                when (row) {
                    0 -> BigFraction.ZERO
                    1 -> BigFraction.ONE
                    else -> BigFraction.ofLong(10)
                },
                activator = if (row < 2) row else -1)
        }, numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })

        val result = BacktrackSolver(source.bake()).solve(BacktrackParams(randomSeed = 1,
            variableSelector = InputOrder, valueSelector = IndomainMax, nodeBudget = NodeBudget(3)))

        val sat = assertIs<SolveResult.Sat>(result)
        assertEquals(true, sat.assignment.bools[0])
        assertEquals(false, sat.assignment.bools[1])
        assertTrue(sat.stats.search.learnedClauses.sum > 0.0)
        assertTrue((sat.stats.lp.phases["PREFIX_REAL_HANDOFF_ROOT"]?.outcomes?.get("BACKJUMP") ?: 0L) > 0L)
        assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(source, sat.assignment))
    }

    @Test
    fun `required real conflicts should notify the native variable selector`() {
        val source = Problem(2, 0, emptyArray(), Array(1002) { row ->
            if (row == 0) ExactLinear.of(IntArray(3) { Term.ofRealVar(it) },
                List(3) { BigFraction.ONE }, LinearOp.LE, BigFraction.ZERO, activator = 0)
            else ExactLinear.of(IntArray(3) { Term.ofRealVar(it) },
                List(3) { BigFraction.ONE }, LinearOp.GE, BigFraction.ONE)
        }, numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })
        val reported = ArrayList<Set<Int>>()
        val selector = object : VariableSelector by InputOrder {
            override fun fresh(): VariableSelector = this
            override fun onConflict(varRef: VarRef?, conflict: BranchingConflict) {
                if (varRef == null) reported += conflict.bools.toSet()
            }
        }

        val result = BacktrackSolver(source.bake()).solve(BacktrackParams(randomSeed = 1,
            variableSelector = selector, valueSelector = IndomainMax))

        assertIs<SolveResult.Sat>(result)
        assertEquals(listOf(setOf(0)), reported)
        assertEquals(1L, result.stats.search.fails.sum.toLong())
    }

    @Test
    fun `required real completion should learn its compacted source conflict and preserve a sibling`() {
        val source = Problem(2, 0, emptyArray(), Array(1002) { row ->
            if (row == 0) ExactLinear.of(IntArray(3) { Term.ofRealVar(it) },
                List(3) { BigFraction.ONE }, LinearOp.LE,
                BigFraction.ZERO, activator = 0)
            else ExactLinear.of(IntArray(3) { Term.ofRealVar(it) },
                List(3) { BigFraction.ONE }, LinearOp.GE,
                BigFraction.ONE)
        }, numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY })

        val result = BacktrackSolver(source.bake()).solve(BacktrackParams(randomSeed = 1, valueSelector = IndomainMax))

        val sat = assertIs<SolveResult.Sat>(result)
        assertEquals(false, sat.assignment.bools[0])
        assertTrue(sat.stats.search.learnedClauses.sum > 0.0)
        assertIs<Verification.Accepted<*, *>>(verifyRealCoordinates(source, sat.assignment))
    }

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
    fun `a feasible residual real LP should be certified SAT`() {
        val row = Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.LE, 5L)
        val p = problem(1, arrayOf(IntDomain(0, 3)), 0.0, 10.0, row)
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a rational continuous vertex should be certified SAT`() {
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(2.0), intArrayOf(0), LinearOp.EQ, 3L)
        val p = problem(0, emptyArray(), 0.0, 10.0, row)
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a rational leaf witness should be read back in real source coordinates`() {
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0 / 3.0), intArrayOf(0), LinearOp.EQ, 3L)
        val p = problem(0, emptyArray(), 7.0, 10.0, row)

        val result = leafRealFeasibility(p, objective = null, sample = Sample(booleanArrayOf(), longArrayOf()))

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(9.0, result.reals[0], absoluteTolerance = 1e-9)
    }

    @Test
    fun `an infeasible system over a lower-unbounded real column should be unsat`() {
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
    fun `a negative witness on a lower-unbounded real column should read back through the split`() {
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
    fun `a finite upper bound on a lower-unbounded real column should be enforced`() {
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
    fun `an infeasible residual real LP should be certified UNSAT`() {
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(0), LinearOp.GE, 5L)
        val p = problem(0, emptyArray(), 0.0, 1.0, row)
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a coefficient outside every scaling ladder should be decided by the rational fallback`() {
        // 1/3 fits neither the dyadic nor the decimal ladder, so only the rational fallback decides it.
        val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0 / 3.0), intArrayOf(0), LinearOp.GE, 1L)
        val p = problem(0, emptyArray(), 0.0, 2.0, row)
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a tripped deadline should degrade the leaf verdict to indeterminate`() {
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
    fun `a leaf no theory could decide should report unknown rather than unsat`() {
        val rows = Array<Factor>(1001) { row ->
            Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, (row + 1).toDouble()),
                intArrayOf(0, 1), LinearOp.LE, 5L)
        }
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 1)),
            factors = rows,
            numRealVars = 2,
            realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(10.0, 10.0),
        )
        val result = BacktrackSolver(p.bake()).solve(BacktrackParams(randomSeed = 1L))
        val unknown = assertIs<SolveResult.Unknown>(result)
        assertEquals(TerminationReason.Unsupported, unknown.reason)
    }

    @Test
    fun `the continuous leaf verdict should not depend on LP bounding`() {
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
    fun `an objective no row bounds below should end the minimize unbounded with its ray`() {
        val row = Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0), LinearOp.LE, 1L)
        val p = problem(1, arrayOf(IntDomain(0, 3)), 0.0, Double.POSITIVE_INFINITY, row)

        val result = BacktrackSolver(p.bake()).minimize(LinearObjective(realCoefficients = doubleArrayOf(-1.0)))

        val unbounded = assertIs<MinimizeResult.Unbounded>(result)
        assertTrue(unbounded.direction.single().signum() > 0)
        assertTrue(unbounded.sample.ints[0] - unbounded.sample.reals[0] <= 1.0)
    }

    @Test
    fun `a leaf only tolerance accepts should complete the minimize as optimal`() {
        // 3 r = 1 has the exact optimum 1/3, which the vetoing policy keeps every exact certifier from proving.
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
    fun `local search should decline a model with LP-only continuous variables`() {
        val row = Linear(longArrayOf(1L), intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), LinearOp.LE, 5L)
        val p = problem(1, arrayOf(IntDomain(0, 3)), 0.0, 10.0, row)
        val r = assertIs<SolveResult.Unknown>(LocalSearchSolver(p.bake()).solve())
        assertEquals(TerminationReason.Unsupported, r.reason)
    }

    @Test
    fun `fractional leaf improvements should retain exact ordering below floating precision`() {
        val row = Linear(longArrayOf(-1), intArrayOf(0), doubleArrayOf(3.0), intArrayOf(0), LinearOp.EQ, 0)
        val source = problem(1, arrayOf(IntDomain(0, 2)), 0.0, 1.0, row).bake()
        val objective = LinearObjective(constant = 1L shl 62, realCoefficients = doubleArrayOf(-1.0))

        val result = BacktrackSolver(source).minimize(
            objective,
            BacktrackParams(randomSeed = 17, valueSelector = IndomainMin),
        )

        val sample = assertIs<MinimizeResult.Optimal>(result).sample
        assertEquals(2L, sample.intValue(0))
        assertEquals("2/3", sample.exactReals?.single().toString())
    }

    // Reals: index 0 = operand, index 1 = result.
    private fun problem(intDoms: Array<IntDomain>, opLo: Double, opHi: Double, vararg factors: Factor) = Problem(
        numBoolVars = 0,
        numIntVars = intDoms.size,
        intDomains = intDoms,
        factors = arrayOf(*factors),
        numRealVars = 2,
        realLower = doubleArrayOf(opLo, 0.0),
        realUpper = doubleArrayOf(opHi, 100.0),
    )

    private fun resultEquals(value: Long): Linear =
        Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0), intArrayOf(1), LinearOp.EQ, value)

    @Test
    fun `a fixed integer operand should yield an exact product certified SAT`() {
        val p = problem(arrayOf(IntDomain(2, 2)), 0.0, 5.0, RealProduct(0, 0, 1, 0.0, 5.0), resultEquals(6))
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `an exact product with no feasible real completion should be UNSAT`() {
        val p = problem(arrayOf(IntDomain(2, 2)), 0.0, 5.0, RealProduct(0, 0, 1, 0.0, 5.0), resultEquals(12))
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `an integer operand should be enumerated with every leaf product checked`() {
        val p = problem(arrayOf(IntDomain(1, 3)), 2.0, 2.0, RealProduct(0, 0, 1, 2.0, 2.0), resultEquals(5))
        assertIs<SolveResult.Unsat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a feasible bilinear product across a branch should be SAT`() {
        val p = problem(arrayOf(IntDomain(1, 3)), 0.0, 5.0, RealProduct(0, 0, 1, 0.0, 5.0), resultEquals(9))
        assertIs<SolveResult.Sat>(BacktrackSolver(p.bake()).solve(BacktrackParams()))
    }
}
