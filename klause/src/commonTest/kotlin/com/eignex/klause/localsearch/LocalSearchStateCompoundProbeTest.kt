package com.eignex.klause.localsearch
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.reifiedIntCompare
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.values
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.*
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * A `netDelta`/`breakScore` probe of a [Move.Compound] uses apply-then-revert internally, but must
 * leave the state *exactly* as it was — including the cross-epoch [TabuBook.touchCount]
 * activity counters that drive ALNS / WarmState. After a mix of real applies and Compound probes:
 *  - touchCount equals the count of *real* applies only (probes contribute nothing), and
 *  - the break/make vectors and cost match a fresh recompute (probes leave no residue).
 */
class LocalSearchStateCompoundProbeTest {

    private data class Case(val name: String, val problem: Problem)

    private val cases: List<Case> = listOf(
        boolHeavyCase(),
        intHeavyCase(),
        mixedReifiedCase(),
        permutationCase(),
    )

    @Test
    fun `definition input scores include the propagated assignment`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 1) },
            factors = arrayOf(Clause(intArrayOf(Lit.make(1, false)))),
        )
        val network = InvariantNetwork(
            listOf(
                DefinitionalSweep.SweepNode.Bool2Int(0, 0),
                DefinitionalSweep.SweepNode.CmpReif(1, longArrayOf(1, 1), intArrayOf(0, 1), 1L, LinearOp.EQ),
            ),
            2,
            2,
        )
        val objective = LinearObjective(boolWeights = longArrayOf(0, 3), intCoefficients = longArrayOf(2, 0))
        for (move in listOf(Move.BoolFlip(0), Move.IntSet(1, 1))) {
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
            state.invariants = network
            state.recompute()
            state.weights.factorWeights[0] = 2.5
            state.shaping.objective = objective
            state.shaping.shapingLambda = 0.5
            val cost = state.cost
            val value = objective.evaluate(state.assignment)
            val predicted = state.netDelta(move)
            val weighted = state.weightedNetDelta(move)
            val breakScore = state.breakScore(move)
            val objectiveDelta = state.objectiveDelta(objective, move)
            val shapedDelta = state.shapedObjectiveDelta(move)

            state.apply(move)

            assertEquals(state.cost - cost, predicted)
            assertEquals(predicted * 2.5, weighted)
            assertEquals(1, breakScore, "$move break")
            assertEquals(objective.evaluate(state.assignment) - value, objectiveDelta)
            assertEquals(checkNotNull(objectiveDelta) * 0.5, shapedDelta)
            assertEquals(1L, state.cost, "$move committed cost")
            assertEquals(true, state.assignment.boolValue(1), "$move committed definition")
            val inverse = if (move is Move.BoolFlip) move else Move.IntSet(1, 0)
            assertEquals(-2.5, state.weightedNetDelta(inverse), "$move inverse weighted delta")
            val inverseMake = state.makeScore(inverse)
            assertEquals(1L, state.cost, "$move inverse probe cost")
            assertEquals(true, state.assignment.boolValue(1), "$move inverse probe definition")
            state.apply(inverse)
            assertEquals(0L, state.cost, "$move inverse committed cost")
            assertEquals(false, state.assignment.boolValue(1), "$move inverse committed definition")
            assertEquals(1, inverseMake, "$move inverse make")
        }
    }

    @Test
    fun `compound objective deltas reflect final coordinate values`() {
        val problem = Problem(
            numBoolVars = 2, numIntVars = 1, intDomains = arrayOf(IntDomain(0, 2)), factors = emptyArray(),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        val objective = LinearObjective(boolWeights = longArrayOf(2, 3), intCoefficients = longArrayOf(5))
        val cases = listOf(
            Move.Compound(listOf(Move.BoolFlip(0), Move.BoolFlip(0))) to 0.0,
            Move.Compound(listOf(Move.IntSet(0, 1), Move.IntSet(0, 2))) to 10.0,
            Move.Compound(listOf(Move.BoolFlip(0), Move.IntSet(0, 1))) to 7.0,
        )
        val before = state.assignment.snapshot()
        state.shaping.objective = objective
        state.shaping.shapingLambda = 0.5
        state.recompute()

        for ((move, expected) in cases) {
            assertEquals(expected, state.objectiveDelta(objective, move))
            assertEquals(expected * 0.5, state.shapedObjectiveDelta(move))
            assertEquals(before, state.assignment.snapshot())
        }
    }

    @Test
    fun `compound probes restore derived values when the inverse cannot write`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 2) },
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        state.invariants = InvariantNetwork(
            listOf(DefinitionalSweep.SweepNode.ElementDef(1, 0, null, longArrayOf(2), 0)),
            3,
            0,
        )
        state.assignment.setInt(0, 1)
        state.recompute()
        val sample = state.assignment.snapshot()
        val cost = state.cost
        val step = state.step
        val touched = state.tabu.lastTouched.copyOf()
        val counts = state.tabu.touchCount.copyOf()
        val move = Move.Compound(listOf(Move.IntSet(0, 0), Move.IntSet(2, 1)))

        state.netDelta(move)
        state.breakScore(move)
        state.weightedNetDelta(move)

        assertEquals(sample, state.assignment.snapshot())
        assertEquals(cost, state.cost)
        assertEquals(step, state.step)
        assertContentEquals(touched, state.tabu.lastTouched)
        assertContentEquals(counts, state.tabu.touchCount)
        state.recompute()
        assertEquals(cost, state.cost)
    }

    @Test
    fun `compound scoring propagates definitions after all inputs change`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 2) },
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(2), LinearOp.EQ, 0)),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(7))
        state.invariants = InvariantNetwork(
            listOf(DefinitionalSweep.SweepNode.ElementDef(2, 0, intArrayOf(1), null, 0)),
            3,
            0,
        )
        state.assignment.setInt(1, 2)
        state.recompute()
        val move = Move.Compound(listOf(Move.IntSet(1, 1), Move.IntSet(0, 1)))
        val before = state.cost

        val predicted = state.netDelta(move)
        state.apply(move)

        assertEquals(state.cost - before, predicted)
        assertEquals(0L, predicted)
    }

    @Test
    fun `weighted compound predictions match the committed degree change`() {
        for (case in cases) {
            val baked = case.problem.bake()
            for (seed in 0 until 3) {
                val state = LocalSearchState(baked, Random(seed))
                state.restart()
                val weights = state.weights.factorWeights
                for (i in weights.indices) weights[i] = (i + 1) * 0.25
                val compound = randomCompound(case.problem, state, Random(seed + 100)) ?: continue
                val sibling = LocalSearchState(baked, Random(seed))
                copyAssignment(state, sibling)
                sibling.recompute()
                val before = sibling.factorDegree.copyOf()

                val predicted = state.weightedNetDelta(compound)
                sibling.apply(compound)

                var expected = 0.0
                for (i in before.indices) expected += weights[i] * (sibling.factorDegree[i] - before[i])
                assertEquals(expected, predicted, "${case.name} seed=$seed")
            }
        }
    }

    @Test
    fun `compound probes leave touchCount and break-make untouched`() {
        for (case in cases) {
            val baked = case.problem.bake()
            for (seed in 0 until 8) {
                val state = LocalSearchState(baked, Random(seed.toLong()))
                state.restart()

                val totalSlots = case.problem.numBoolVars + case.problem.numIntVars
                val expectedTouch = IntArray(totalSlots)
                val rng = Random(seed.toLong() xor 0x5151L)

                // Interleave real applies (which bump touchCount) with Compound probes (which must not).
                repeat(30) {
                    val move = randomPrimitive(case.problem, state, rng)
                    if (move != null) {
                        state.apply(move)
                        expectedTouch[slotOf(case.problem, move)]++
                    }
                    repeat(3) {
                        val compound = randomCompound(case.problem, state, rng) ?: return@repeat
                        state.netDelta(compound)
                        state.breakScore(compound)
                    }
                }

                for (slot in 0 until totalSlots) {
                    assertEquals(
                        expectedTouch[slot],
                        state.tabu.touchCount[slot],
                        "${case.name} seed=$seed: touchCount[$slot] inflated by probes " +
                            "(expected real-apply count ${expectedTouch[slot]})",
                    )
                }

                val sibling = LocalSearchState(baked, Random(seed.toLong()))
                copyAssignment(state, sibling)
                sibling.recompute()
                assertEquals(
                    sibling.cost,
                    state.cost,
                    "${case.name} seed=$seed: cost drifted after probes",
                )
                for (b in 0 until case.problem.numBoolVars) {
                    assertEquals(
                        boolBreak(sibling, b),
                        boolBreak(state, b),
                        "${case.name} seed=$seed: boolBreakCount[$b] drifted after probes",
                    )
                    assertEquals(
                        boolMake(sibling, b),
                        boolMake(state, b),
                        "${case.name} seed=$seed: boolMakeCount[$b] drifted after probes",
                    )
                }
            }
        }
    }

    // boolBreakCount/boolMakeCount are internal; reach them via the public break/make scores.
    private fun boolBreak(state: LocalSearchState, v: Int) = state.breakScore(Move.BoolFlip(v))
    private fun boolMake(state: LocalSearchState, v: Int) = state.makeScore(Move.BoolFlip(v))

    private fun slotOf(problem: Problem, move: Move): Int = when (move) {
        is Move.BoolFlip -> move.varId
        is Move.IntSet -> problem.numBoolVars + move.varId
        is Move.RealSet, is Move.Compound -> error("primitive discrete move expected")
    }

    private fun randomPrimitive(problem: Problem, state: LocalSearchState, rng: Random): Move? {
        val haveBool = problem.numBoolVars > 0
        val haveInt = problem.numIntVars > 0
        val pickBool = when {
            !haveBool -> false
            !haveInt -> true
            else -> rng.nextBoolean()
        }
        return if (pickBool) {
            Move.BoolFlip(rng.nextInt(problem.numBoolVars))
        } else {
            val v = rng.nextInt(problem.numIntVars)
            val d = state.rootDomains[v]
            val cur = state.assignment.intValue(v)
            var target = cur
            repeat(8) {
                val cand = d.values.valueAt(rng.nextInt(d.values.size))
                if (cand != cur) {
                    target = cand
                    return@repeat
                }
            }
            if (target == cur) null else Move.IntSet(v, target)
        }
    }

    /** Two distinct-variable primitives bundled as a Compound, or null if not enough vars. */
    private fun randomCompound(problem: Problem, state: LocalSearchState, rng: Random): Move.Compound? {
        val a = randomPrimitive(problem, state, rng) ?: return null
        repeat(8) {
            val b = randomPrimitive(problem, state, rng) ?: return@repeat
            if (slotOf(problem, b) != slotOf(problem, a)) {
                return Move.Compound(listOf(a, b))
            }
        }
        return null
    }

    private fun copyAssignment(src: LocalSearchState, dst: LocalSearchState) {
        for (b in 0 until src.problem.numBoolVars) dst.assignment.setBool(b, src.assignment.boolValue(b))
        for (i in 0 until src.problem.numIntVars) dst.assignment.setInt(i, src.assignment.intValue(i))
    }

    private fun boolHeavyCase(): Case {
        val factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true), Lit.make(4, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(4, false))),
            Cardinality(
                literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
                min = 1,
                max = 3,
            ),
            Xor(intArrayOf(Lit.make(0, true), Lit.make(2, true), Lit.make(4, true)), targetParity = 1),
            PseudoBoolean(
                weights = longArrayOf(2, 1, 3, 1),
                literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false), Lit.make(3, true)),
                op = PbOp.LE,
                bound = 4L,
            ),
        )
        return Case("boolHeavy", Problem(numBoolVars = 5, numIntVars = 0, intDomains = emptyArray(), factors = factors))
    }

    private fun intHeavyCase(): Case {
        val intDomains = arrayOf(IntDomain(-3, 3), IntDomain(-3, 3), IntDomain(0, 5))
        val factors = arrayOf<Factor>(
            Linear(coeffs = intArrayOf(2, -1, 1), vars = intArrayOf(0, 1, 2), op = LinearOp.LE, 4),
            Linear(coeffs = intArrayOf(1, 1, 1), vars = intArrayOf(0, 1, 2), op = LinearOp.GE, -1),
            Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 4),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 0),
        )
        return Case("intHeavy", Problem(numBoolVars = 0, numIntVars = 3, intDomains = intDomains, factors = factors))
    }

    private fun mixedReifiedCase(): Case {
        val intDomains = arrayOf(IntDomain(-2, 3), IntDomain(-2, 3))
        val factors = arrayOf<Factor>(
            ReifiedLinear(
                auxBoolVar = 0,
                coeffs = intArrayOf(2, -1),
                vars = intArrayOf(0, 1),
                op = LinearOp.LE,
                bound = 3,
            ),
            ReifiedCardinality(
                auxBoolVar = 1,
                literals = intArrayOf(Lit.make(2, true), Lit.make(3, true), Lit.make(4, true)),
                min = 1,
                max = 2,
            ),
            reifiedIntCompare(auxBoolVar = 5, intVar = 0, op = IntCmpOp.GE, 0),
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(5, false))),
        )
        return Case(
            "mixedReified",
            Problem(numBoolVars = 6, numIntVars = 2, intDomains = intDomains, factors = factors),
        )
    }

    private fun permutationCase(): Case {
        val intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3))
        val factors = arrayOf<Factor>(
            AllDifferent(vars = intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
            Linear(intArrayOf(1), intArrayOf(3), LinearOp.GE, 1),
        )
        return Case("permutation", Problem(numBoolVars = 0, numIntVars = 4, intDomains = intDomains, factors = factors))
    }
}
