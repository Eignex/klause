package com.eignex.klause.solver.factor
import com.eignex.klause.solver.localsearch.LocalSearchFactor

import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.localsearch.SolverState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FactorPropertyTest {

    private val emptyDomains: Array<IntDomain> = emptyArray()

    @Test fun `clause delta matches apply`() {
        val factor = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true), Lit.make(3, false)))
        runFactorPropertyCheck(factor, numBoolVars = 4, intDomains = emptyDomains, seed = 1)
    }

    @Test fun `cardinality delta matches apply`() {
        val factor = Cardinality(
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, false)),
            min = 1, max = 2,
        )
        runFactorPropertyCheck(factor, numBoolVars = 4, intDomains = emptyDomains, seed = 2)
    }

    @Test fun `cardinality slow path delta matches apply`() {

        val factor = Cardinality(
            literals = intArrayOf(Lit.make(0, true), Lit.make(0, false), Lit.make(1, true), Lit.make(2, true)),
            min = 2, max = 3,
        )
        runFactorPropertyCheck(factor, numBoolVars = 3, intDomains = emptyDomains, seed = 3)
    }

    @Test fun `pseudo boolean delta matches apply`() {
        val factor = PseudoBoolean(
            weights = intArrayOf(3, -2, 5, 1),
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true), Lit.make(3, true)),
            op = PbOp.LE, bound = 4,
        )
        runFactorPropertyCheck(factor, numBoolVars = 4, intDomains = emptyDomains, seed = 4)
    }

    @Test fun `pseudo boolean ge delta matches apply`() {
        val factor = PseudoBoolean(
            weights = intArrayOf(2, 1, 1, 1),
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
            op = PbOp.GE, bound = 3,
        )
        runFactorPropertyCheck(factor, numBoolVars = 4, intDomains = emptyDomains, seed = 5)
    }

    @Test fun `xor delta matches apply`() {
        val factor = Xor(
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false), Lit.make(3, true)),
            targetParity = 1,
        )
        runFactorPropertyCheck(factor, numBoolVars = 4, intDomains = emptyDomains, seed = 6)
    }

    @Test fun `xor repeated var delta matches apply`() {

        val factor = Xor(
            literals = intArrayOf(Lit.make(0, true), Lit.make(0, false), Lit.make(1, true), Lit.make(2, true)),
            targetParity = 0,
        )
        runFactorPropertyCheck(factor, numBoolVars = 3, intDomains = emptyDomains, seed = 7)
    }

    @Test fun `linear delta matches apply`() {
        val factor = Linear(
            coeffs = intArrayOf(2, -1, 3),
            vars = intArrayOf(0, 1, 2),
            op = LinearOp.LE, bound = 5,
        )
        runFactorPropertyCheck(
            factor, numBoolVars = 0,
            intDomains = arrayOf(IntDomain(-3, 3), IntDomain(-3, 3), IntDomain(-3, 3)),
            seed = 8,
        )
    }

    @Test fun `linear eq delta matches apply`() {
        val factor = Linear(
            coeffs = intArrayOf(1, 1, 1),
            vars = intArrayOf(0, 1, 2),
            op = LinearOp.EQ, bound = 5,
        )
        runFactorPropertyCheck(
            factor, numBoolVars = 0,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4)),
            seed = 9,
        )
    }

    @Test fun `product delta matches apply`() {
        val factor = Product(a = 0, b = 1, result = 2)
        runFactorPropertyCheck(
            factor, numBoolVars = 0,
            intDomains = arrayOf(IntDomain(-3, 3), IntDomain(-3, 3), IntDomain(-9, 9)),
            seed = 10,
        )
    }

    @Test fun `all different delta matches apply`() {
        val factor = AllDifferent(vars = intArrayOf(0, 1, 2, 3), domainMin = 1, domainSize = 4)
        runFactorPropertyCheck(
            factor, numBoolVars = 0,
            intDomains = arrayOf(IntDomain(1, 4), IntDomain(1, 4), IntDomain(1, 4), IntDomain(1, 4)),
            seed = 11,
        )
    }

    @Test fun `int eq delta matches apply`() {
        runFactorPropertyCheck(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 3),
            numBoolVars = 0, intDomains = arrayOf(IntDomain(0, 5)), seed = 12,
        )
    }

    @Test fun `int geq delta matches apply`() {
        runFactorPropertyCheck(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
            numBoolVars = 0, intDomains = arrayOf(IntDomain(-3, 3)), seed = 13,
        )
    }

    @Test fun `int leq delta matches apply`() {
        runFactorPropertyCheck(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            numBoolVars = 0, intDomains = arrayOf(IntDomain(-3, 3)), seed = 14,
        )
    }

    @Test fun `int neq delta matches apply`() {
        runFactorPropertyCheck(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 0),
            numBoolVars = 0, intDomains = arrayOf(IntDomain(-2, 2)), seed = 15,
        )
    }

    @Test fun `reified linear delta matches apply`() {

        val factor = ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(2, -1),
            vars = intArrayOf(0, 1),
            op = LinearOp.LE, bound = 3,
        )
        runFactorPropertyCheck(
            factor, numBoolVars = 1,
            intDomains = arrayOf(IntDomain(-2, 3), IntDomain(-2, 3)),
            seed = 16,
        )
    }

    @Test fun `reified pseudo boolean delta matches apply`() {
        val factor = ReifiedPseudoBoolean(
            auxBoolVar = 0,
            weights = intArrayOf(2, 1, 3, 1),
            literals = intArrayOf(Lit.make(1, true), Lit.make(2, false), Lit.make(3, true), Lit.make(4, true)),
            op = PbOp.LE, bound = 4,
        )
        runFactorPropertyCheck(factor, numBoolVars = 5, intDomains = emptyDomains, seed = 17)
    }

    @Test fun `reified cardinality delta matches apply`() {
        val factor = ReifiedCardinality(
            auxBoolVar = 0,
            literals = intArrayOf(Lit.make(1, true), Lit.make(2, true), Lit.make(3, true), Lit.make(4, false)),
            min = 1, max = 2,
        )
        runFactorPropertyCheck(factor, numBoolVars = 5, intDomains = emptyDomains, seed = 18)
    }

    @Test fun `reified int compare delta matches apply`() {
        for (op in IntCmpOp.entries) {
            val factor = ReifiedIntCompare(auxBoolVar = 0, intVar = 0, op = op, 1)
            runFactorPropertyCheck(
                factor, numBoolVars = 1,
                intDomains = arrayOf(IntDomain(-2, 3)),
                seed = 19 + op.ordinal,
            )
        }
    }

    @Test fun `propose repair moves are valid`() {

        val cases: List<Pair<LocalSearchFactor, FactorEnv>> = listOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true)))
                to FactorEnv(numBoolVars = 3),
            Cardinality(
                literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                min = 1, max = 2,
            ) to FactorEnv(numBoolVars = 3),
            PseudoBoolean(
                weights = intArrayOf(3, -2, 5),
                literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                op = PbOp.LE, bound = 4,
            ) to FactorEnv(numBoolVars = 3),
            Xor(
                literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
                targetParity = 1,
            ) to FactorEnv(numBoolVars = 3),
            Linear(
                coeffs = intArrayOf(1, 2),
                vars = intArrayOf(0, 1),
                op = LinearOp.LE, bound = 3,
            ) to FactorEnv(intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5))),
            Product(a = 0, b = 1, result = 2) to FactorEnv(
                intDomains = arrayOf(IntDomain(-2, 2), IntDomain(-2, 2), IntDomain(-4, 4)),
            ),
            AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 1, domainSize = 3) to FactorEnv(
                intDomains = arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 3)),
            ),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 2) to FactorEnv(intDomains = arrayOf(IntDomain(0, 4))),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1) to FactorEnv(intDomains = arrayOf(IntDomain(-2, 2))),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1) to FactorEnv(intDomains = arrayOf(IntDomain(-2, 2))),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 0) to FactorEnv(intDomains = arrayOf(IntDomain(-2, 2))),

            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, -10) to FactorEnv(intDomains = arrayOf(IntDomain(0, 5))),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 99) to FactorEnv(intDomains = arrayOf(IntDomain(0, 5))),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 99) to FactorEnv(intDomains = arrayOf(IntDomain(0, 5))),
            ReifiedLinear(
                auxBoolVar = 0,
                coeffs = intArrayOf(2, -1),
                vars = intArrayOf(0, 1),
                op = LinearOp.LE, bound = 3,
            ) to FactorEnv(numBoolVars = 1, intDomains = arrayOf(IntDomain(-2, 3), IntDomain(-2, 3))),
            ReifiedPseudoBoolean(
                auxBoolVar = 0,
                weights = intArrayOf(2, 1, 3),
                literals = intArrayOf(Lit.make(1, true), Lit.make(2, true), Lit.make(3, false)),
                op = PbOp.LE, bound = 3,
            ) to FactorEnv(numBoolVars = 4),
            ReifiedCardinality(
                auxBoolVar = 0,
                literals = intArrayOf(Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
                min = 1, max = 2,
            ) to FactorEnv(numBoolVars = 4),
            ReifiedIntCompare(auxBoolVar = 0, intVar = 0, op = IntCmpOp.LE, 1) to
                FactorEnv(numBoolVars = 1, intDomains = arrayOf(IntDomain(-2, 3))),
        )
        for ((factor, env) in cases) {
            checkRepairValidity(factor, env)
        }
    }

    private data class FactorEnv(
        val numBoolVars: Int = 0,
        val intDomains: Array<IntDomain> = emptyArray(),
    )

    private fun checkRepairValidity(factor: LocalSearchFactor, env: FactorEnv) {
        val problem = Problem(env.numBoolVars, env.intDomains.size, env.intDomains, listOf(factor))
        val rng = Random(0xfeed)
        val sink = MoveSink()
        repeat(50) { iter ->
            val state = SolverState(problem, Random(iter.toLong()))
            randomizeAssignment(state, env, rng)
            state.recompute()
            if (!factor.isViolated(state, 0)) return@repeat
            sink.clear()
            factor.proposeRepairMoves(state, 0, sink)
            for (move in sink.list) {
                when (move) {
                    is Move.BoolFlip -> {
                        assertTrue(move.varId in factor.boolVars,
                            "${factor::class.simpleName} proposed flip of var ${move.varId} not in boolVars ${factor.boolVars.toList()}")
                    }
                    is Move.IntSet -> {
                        assertTrue(move.varId in factor.intVars,
                            "${factor::class.simpleName} proposed IntSet on var ${move.varId} not in intVars ${factor.intVars.toList()}")
                        val d = problem.intDomains[move.varId]
                        assertTrue(move.newValue in d.min..d.max,
                            "${factor::class.simpleName} proposed IntSet target ${move.newValue} out of domain $d")
                        assertTrue(move.newValue != state.assignment.intValue(move.varId),
                            "${factor::class.simpleName} proposed no-op IntSet at ${move.newValue}")
                    }
                }

                val sibling = SolverState(problem, Random(iter.toLong()))
                copyAssignment(state, sibling)
                sibling.recompute()
                val before = sibling.cost
                sibling.apply(move)
                val after = sibling.cost
                assertTrue(after <= before,
                    "${factor::class.simpleName} repair $move increased cost $before → $after")
            }
        }
    }

    private fun runFactorPropertyCheck(
        factor: LocalSearchFactor,
        numBoolVars: Int,
        intDomains: Array<IntDomain>,
        seed: Int,
        iters: Int = 200,
    ) {
        val problem = Problem(numBoolVars, intDomains.size, intDomains, listOf(factor))
        val state = SolverState(problem, Random(seed.toLong()))
        val rng = Random(seed.toLong() xor 0xC0FFEEL)
        randomizeAssignment(state, FactorEnv(numBoolVars, intDomains), rng)
        state.recompute()

        repeat(iters) { i ->
            val move = pickRandomMove(factor, state, intDomains, rng) ?: return@repeat
            val predicted = when (move) {
                is Move.BoolFlip -> factor.deltaIfBoolFlipped(state, 0, move.varId)
                is Move.IntSet -> factor.deltaIfIntSet(state, 0, move.varId, move.newValue)
            }
            val violatedBefore = factor.isViolated(state, 0)
            val costBefore = state.cost
            state.apply(move)
            val violatedAfter = factor.isViolated(state, 0)
            val observedDelta = (if (violatedAfter) 1 else 0) - (if (violatedBefore) 1 else 0)
            assertEquals(predicted, observedDelta,
                "${factor::class.simpleName}: predicted Δ != observed Δ on iter=$i move=$move")
            assertEquals(costBefore + predicted, state.cost,
                "${factor::class.simpleName}: cost drift after $move on iter=$i")
            assertEquals(violatedAfter, state.violated.contains(0),
                "${factor::class.simpleName}: violated set drift after $move on iter=$i")

            val sibling = SolverState(problem, Random(seed.toLong()))
            copyAssignment(state, sibling)
            sibling.recompute()
            assertEquals(sibling.intPayload[0], state.intPayload[0],
                "${factor::class.simpleName}: intPayload drift after $move on iter=$i")
            assertEquals(sibling.cost, state.cost,
                "${factor::class.simpleName}: cost drift vs recompute on iter=$i")
            assertEquals(sibling.violated.contains(0), state.violated.contains(0),
                "${factor::class.simpleName}: violation drift vs recompute on iter=$i")

            if (i > 0 && i % 30 == 0) {
                randomizeAssignment(state, FactorEnv(numBoolVars, intDomains), rng)
                state.recompute()
            }
        }
    }

    private fun pickRandomMove(
        factor: LocalSearchFactor,
        state: SolverState,
        intDomains: Array<IntDomain>,
        rng: Random,
    ): Move? {
        val haveBool = factor.boolVars.isNotEmpty()
        val haveInt = factor.intVars.isNotEmpty()
        val pickBool = when {
            !haveBool -> false
            !haveInt -> true
            else -> rng.nextBoolean()
        }
        return if (pickBool) {
            Move.BoolFlip(factor.boolVars[rng.nextInt(factor.boolVars.size)])
        } else {
            val v = factor.intVars[rng.nextInt(factor.intVars.size)]
            val d = intDomains[v]
            val cur = state.assignment.intValue(v)

            var target = cur
            repeat(8) {
                val candidate = d.min + rng.nextInt(d.size)
                if (candidate != cur) { target = candidate; return@repeat }
            }
            if (target == cur) return null
            Move.IntSet(v, target)
        }
    }

    private fun randomizeAssignment(state: SolverState, env: FactorEnv, rng: Random) {
        for (b in 0 until env.numBoolVars) state.assignment.setBool(b, rng.nextBoolean())
        for (i in env.intDomains.indices) {
            val d = env.intDomains[i]
            state.assignment.setInt(i, d.min + rng.nextInt(d.size))
        }
    }

    private fun copyAssignment(src: SolverState, dst: SolverState) {
        for (b in 0 until src.problem.numBoolVars) dst.assignment.setBool(b, src.assignment.boolValue(b))
        for (i in 0 until src.problem.numIntVars) dst.assignment.setInt(i, src.assignment.intValue(i))
    }
}
