package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.flatzinc.linearObjective
import com.eignex.klause.formats.flatzinc.parseFlatZincExecution
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Move.BoolFlip
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalSearchStateTest {
    @Test
    fun `feasible objective grades should include every maintained compound output`() {
        val problem = Problem(
            4,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(1, true), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(3, false))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(2, true)
        state.assignment.setBool(3, true)
        state.invariants = DefinitionalSweep.infer(
            problem,
            boolFolds = listOf(
                BoolFoldDefinition(2, intArrayOf(Lit.make(0, false)), true),
                BoolFoldDefinition(3, intArrayOf(Lit.make(1, false)), true),
            ),
        )?.network(0, 4)
        state.recompute()
        val objective = LinearObjective(boolWeights = longArrayOf(0, 0, 2, 3))
        val move = Move.Compound(listOf(Move.BoolFlip(0), Move.BoolFlip(1)))

        val delta = state.feasibleObjectiveDelta(objective, move)

        assertEquals(-5.0, delta)
        assertTrue(state.assignment.boolValue(2))
        assertTrue(state.assignment.boolValue(3))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `feasible objective grades should reject hard violations from maintained inputs`() {
        val problem = Problem(
            2,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.assignment.setBool(1, true)
        state.invariants = DefinitionalSweep.infer(
            problem,
            boolFolds = listOf(
                BoolFoldDefinition(1, intArrayOf(Lit.make(0, false)), true),
            ),
        )?.network(0, 2)
        state.recompute()
        val objective = LinearObjective(boolWeights = longArrayOf(0, 5))

        val delta = state.feasibleObjectiveDelta(objective, Move.BoolFlip(0))

        assertEquals(Double.POSITIVE_INFINITY, delta)
        assertFalse(state.assignment.boolValue(0))
        assertTrue(state.assignment.boolValue(1))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `feasible objective grades should rebalance defined bin packing loads through a board swap`() {
        val indicators = (1..4).flatMap { p -> (1..2).map { t -> p to t } }
        val ratings = longArrayOf(5, 3, 4, 2)
        val source = buildString {
            append("var 1..2: p1; var 1..2: p2; var 1..2: p3; var 1..2: p4; ")
            for ((p, t) in indicators) append("var bool: in${p}_$t; var 0..1: w${p}_$t; ")
            append("var 0..10: load1; var 0..10: load2; var 0..10: hi; var 0..10: lo; var 0..10: balance; ")
            append("constraint all_different_int([p1, p2]); constraint all_different_int([p3, p4]); ")
            append("constraint int_lin_eq([1, 1], [load1, load2], 14); ")
            for ((p, t) in indicators) {
                append("constraint int_eq_reif(p$p, $t, in${p}_$t) :: defines_var(in${p}_$t); ")
                append("constraint bool2int(in${p}_$t, w${p}_$t) :: defines_var(w${p}_$t); ")
            }
            for (t in 1..2) {
                append("constraint int_lin_eq([1, ${ratings.joinToString { "-$it" }}], ")
                append("[load$t, ${(1..4).joinToString { "w${it}_$t" }}], 0) :: defines_var(load$t); ")
                append("constraint bool_lin_le([${ratings.joinToString()}], ")
                append("[${(1..4).joinToString { "in${it}_$t" }}], 10); ")
            }
            append("constraint array_int_maximum(hi, [load1, load2]) :: defines_var(hi); ")
            append("constraint array_int_minimum(lo, [load1, load2]) :: defines_var(lo); ")
            append("constraint int_lin_eq([1, -1, 1], [balance, hi, lo], 0) :: defines_var(balance); ")
            append("solve minimize balance;")
        }
        val execution = parseFlatZincExecution(source)
        val program = execution.program
        val problem = program.problem.bake()
        val sweep = assertNotNull(execution.definitionalSweep)
        val state = LocalSearchState(problem, Random(0))
        for (v in 0 until problem.numIntVars) state.assignment.setInt(v, state.rootDomains[v].min)
        val team = IntArray(4) { program.intVarsByName.getValue("p${it + 1}") }
        for ((player, value) in longArrayOf(1, 2, 1, 2).withIndex()) state.assignment.setInt(team[player], value)
        state.invariants = sweep.network(problem.numIntVars, problem.numBoolVars)
        sweep.sweep(state.assignment, state.rootDomains)
        state.recompute()
        assertEquals(0L, state.cost)
        val swap = Move.Compound(listOf(Move.IntSet(team[2], 2), Move.IntSet(team[3], 1)))

        val delta = state.feasibleObjectiveDelta(assertNotNull(program.linearObjective()), swap)

        assertEquals(-4.0, delta)
    }

    @Test
    fun `defined output writes should preserve the source tabu window`() {
        for (positive in listOf(false, true)) {
            val problem = Problem(
                2,
                0,
                emptyArray(),
                arrayOf<Factor>(
                    Clause(intArrayOf(Lit.make(0, !positive), Lit.make(1, true))),
                    Clause(intArrayOf(Lit.make(0, positive), Lit.make(1, false))),
                ),
            )
            val sweep = assertNotNull(
                DefinitionalSweep.infer(
                    problem,
                    boolFolds = listOf(
                        BoolFoldDefinition(1, intArrayOf(Lit.make(0, positive)), true),
                    ),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
            state.invariants = sweep.network(0, 2)
            sweep.sweep(state.assignment, state.rootDomains)
            state.recompute()

            state.apply(Move.BoolFlip(0))

            assertTrue(state.tabu.isTaboo(Move.BoolFlip(0), tenure = 1))
        }
    }

    @Test
    fun `compound intermediate costs should not set the best committed cost`() {
        val problem = Problem(
            2,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.recompute()

        state.apply(Move.Compound(listOf(Move.BoolFlip(0), Move.BoolFlip(1))))

        assertEquals(1L, state.cost)
        assertEquals(1L, state.bestCostSeen)
    }

    @Test
    fun `definition intermediate costs should not set the best committed cost`() {
        val problem = Problem(
            2,
            0,
            emptyArray(),
            arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, true))),
            ),
        )
        val sweep = assertNotNull(
            DefinitionalSweep.infer(
                problem,
                boolFolds = listOf(
                    BoolFoldDefinition(1, intArrayOf(Lit.make(0, false)), true),
                ),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.invariants = sweep.network(0, 2)
        sweep.sweep(state.assignment, state.rootDomains)
        state.recompute()

        state.apply(Move.BoolFlip(0))

        assertEquals(2L, state.cost)
        assertEquals(2L, state.bestCostSeen)
    }

    @Test
    fun `suspended initial assignment should preserve mixed seeded draws and pins`() {
        val problem = Problem(513, 513, Array(513) { IntDomain(-3, 7) }, emptyArray())
        val pins = Assumptions(bools = mapOf(0 to true, 512 to false), ints = mapOf(0 to 6L, 512 to -2L))
        for (anchored in listOf(false, true)) {
            val model = if (anchored) LocalSearchModel.open(problem) else LocalSearchModel.of(problem.bake())
            val direct = LocalSearchState(model, Random(7), pins)
            val suspended = LocalSearchState(model, Random(7), pins)

            direct.randomizeAssignment()
            val steps = suspended.initialAssignmentSteps(null).iterator()
            while (steps.hasNext()) steps.next()

            assertEquals(direct.assignment.snapshot(), suspended.assignment.snapshot())
            assertEquals(direct.rng.nextLong(), suspended.rng.nextLong())
            assertTrue(suspended.assignment.boolValue(0))
            assertEquals(-2L, suspended.assignment.intValue(512))
        }
    }

    private fun reifiedChannelingProblem(): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 2)),
        factors = arrayOf<Factor>(
            ReifiedLinear(auxBoolVar = 0, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, bound = 0),
            ReifiedLinear(auxBoolVar = 1, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, bound = 1),
            ReifiedLinear(auxBoolVar = 2, coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, bound = 2),
        ),
    )

    @Test
    fun `states sharing a projection should keep assignments and invariant payloads independent`() {
        val model = LocalSearchModel.of(reifiedChannelingProblem().bake())
        val projection = LocalSearchProblem(model.problem, model.domains)
        val first = LocalSearchState(model, Random(1), projection = projection)
        val second = LocalSearchState(model, Random(2), projection = projection)
        for (state in listOf(first, second)) {
            state.assignment.setInt(0, 0)
            state.assignment.setBool(0, true)
            state.recompute()
        }
        val cost = second.cost
        val delta = second.netDelta(Move.IntSet(0, 1))

        first.apply(Move.IntSet(0, 1))

        assertTrue(first.cost > cost)
        assertEquals(0L, second.assignment.intValue(0))
        assertEquals(cost, second.cost)
        assertEquals(delta, second.netDelta(Move.IntSet(0, 1)))
    }

    private fun foldedProblem() = Problem(
        numBoolVars = 0,
        numIntVars = 1,
        intDomains = arrayOf(IntDomain(0, 10)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3)),
    ).bake()

    @Test
    fun `search should seed its root domains from the projection fold rather than the declared box`() {
        val state = LocalSearchState(foldedProblem(), Random(1))

        assertEquals(3L, state.rootDomains[0].max)
    }

    @Test
    fun `re-summing the real rows should reconcile a drifted sum`() {
        val row = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(row),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(10.0),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        state.restart()
        state.doublePayload[0] = 5.0
        state.apply(Move.RealSet(0, 0.5))
        assertTrue(state.cost > 0L)

        state.refreshRealRows()

        assertEquals(0L, state.cost)
    }

    @Test
    fun `local search cost should stay exact across a flip of a variable repeated in one factor`() {
        val a = 0
        val b = 1
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(a, true), Lit.make(a, false), Lit.make(b, true)),
            min = 1,
            max = 2,
        )
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(7))
        state.recompute()
        val brute = if (state.factors[0].isViolated(state, 0)) 1L else 0L
        assertEquals(brute, state.cost)
        state.apply(BoolFlip(a))
        val brute2 = if (state.factors[0].isViolated(state, 0)) 1L else 0L
        assertEquals(brute2, state.cost)
    }
}
