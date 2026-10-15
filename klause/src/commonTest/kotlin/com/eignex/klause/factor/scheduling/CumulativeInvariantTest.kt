package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.FactorPropagationOracle
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.scheduling.internals.CumulativeLsState
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.FunctionalObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CumulativeInvariantTest {
    @Test
    fun `unequal duration start swaps should repair jointly improving overloads`() {
        for (variableFootprints in listOf(false, true)) {
            val factor = Cumulative(
                intArrayOf(0, 1, 2, 3),
                longArrayOf(2, 1, 3, 1),
                longArrayOf(1, 1, 1, 1),
                2,
                durationVars = if (variableFootprints) intArrayOf(4, 5, 6, 5) else intArrayOf(),
                resourceVars = if (variableFootprints) intArrayOf(7, 7, 7, 7) else intArrayOf(),
            )
            val problem = Problem(
                0,
                8,
                arrayOf(
                    IntDomain(0, 2),
                    IntDomain(0, 2),
                    IntDomain(0, 0),
                    IntDomain(1, 1),
                    IntDomain(2, 2),
                    IntDomain(1, 1),
                    IntDomain(3, 3),
                    IntDomain(1, 1),
                ),
                arrayOf<Factor>(factor),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assign(0, 2, 0, 1, 2, 1, 3, 1)
            state.recompute()
            state.weights.factorWeights[0] = 3.5

            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)

            val move = assertNotNull(
                state.moveSink.list.filterIsInstance<Move.Compound>().firstOrNull {
                    it.parts.toSet() == setOf(IntSet(0, 2), IntSet(1, 0))
                },
            )
            assertEquals(-3.5, state.weightedNetDelta(move))
            state.apply(move)
            assertEquals(0L, state.cost)
            assertEquals(0, state.intPayload[0])
        }
    }

    @Test
    fun `start swap filters should retain ties and worsening overloads`() {
        for (worsening in listOf(false, true)) {
            val values = if (worsening) longArrayOf(0, 3, 0, 1, 3) else longArrayOf(0, 2, 0, 2)
            val durations = if (worsening) longArrayOf(3, 1, 6, 1, 3) else longArrayOf(2, 1, 3, 1)
            val problem = Problem(
                0,
                values.size,
                Array(values.size) {
                    if (it < 2) IntDomain(0, values[1]) else IntDomain(values[it], values[it])
                },
                arrayOf<Factor>(
                    Cumulative(
                        IntArray(values.size) { it },
                        durations,
                        LongArray(values.size) { 1L },
                        2,
                    ),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assign(*values)
            state.recompute()
            val swap = Move.Compound(listOf(IntSet(0, values[1]), IntSet(1, 0)))
            assertEquals(if (worsening) 1L else 0L, state.netDelta(swap))

            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)

            assertTrue(state.moveSink.list.none { it is Move.Compound })
        }
    }

    @Test
    fun `start swaps should grade every aliased role and restore the profile`() {
        for (alias in listOf("shared starts", "remapped starts", "duration", "resource", "capacity")) {
            val shared = alias == "shared starts" || alias == "remapped starts"
            val source = Cumulative(
                if (shared) {
                    intArrayOf(0, 1, 2, 3, if (alias == "remapped starts") 8 else 0, 1)
                } else {
                    intArrayOf(0, 1, 2, 3)
                },
                if (shared) longArrayOf(2, 1, 3, 1, 1, 1) else longArrayOf(2, 1, 3, 1),
                LongArray(if (shared) 6 else 4) { 1L },
                2,
                durationVars = if (alias == "duration") intArrayOf(1, 4, 5, 4) else intArrayOf(),
                resourceVars = if (alias == "resource") intArrayOf(1, 4, 4, 4) else intArrayOf(),
                capacityVar = if (alias == "capacity") 0 else -1,
            )
            val factor = if (alias == "remapped starts") {
                source.remap(VarRemap(intArrayOf(), intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 0)))
            } else {
                source
            }
            val problem = Problem(
                0,
                8,
                arrayOf(
                    IntDomain(0, 2),
                    IntDomain(0, 2),
                    IntDomain(0, 0),
                    IntDomain(1, 1),
                    IntDomain(1, 1),
                    IntDomain(3, 3),
                    IntDomain(0, 0),
                    IntDomain(0, 0),
                ),
                arrayOf<Factor>(factor),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assign(0, 2, 0, 1, 1, 3, 0, 0)
            state.recompute()
            val before = state.cost
            val snapshot = state.assignment.snapshot()
            val payload = state.refPayload[0] as CumulativeLsState
            val usage = payload.usage.copyOf()
            val cap = payload.cap

            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)

            val move = assertNotNull(
                state.moveSink.list.filterIsInstance<Move.Compound>().firstOrNull {
                    it.parts.toSet() == setOf(IntSet(0, 2), IntSet(1, 0))
                },
                alias,
            )
            assertEquals(snapshot, state.assignment.snapshot(), alias)
            assertContentEquals(usage, payload.usage, alias)
            assertEquals(before, payload.overage, alias)
            assertEquals(cap, payload.cap, alias)
            val predicted = state.netDelta(move)
            state.apply(move)
            assertEquals(before + predicted, state.cost, alias)
            assertEquals(if (shared) 2L else 0L, state.cost, alias)
        }
    }

    @Test
    fun `start swaps should require two admissible searched coordinates`() {
        for (protection in listOf("domain", "pin", "owner", "definition")) {
            for (protected in 0..1) {
                val factors = arrayListOf<Factor>(
                    Cumulative(
                        intArrayOf(0, 1, 2, 3),
                        longArrayOf(2, 1, 3, 1),
                        longArrayOf(1, 1, 1, 1),
                        2,
                    ),
                )
                if (protection == "definition") factors.add(Product(4, 5, protected))
                val problem = Problem(
                    0,
                    6,
                    arrayOf(
                        IntDomain(0, if (protection == "domain" && protected == 0) 1 else 2),
                        IntDomain(if (protection == "domain" && protected == 1) 1 else 0, 2),
                        IntDomain(0, 0),
                        IntDomain(1, 1),
                        IntDomain(0, 2),
                        IntDomain(1, 1),
                    ),
                    factors.toTypedArray(),
                )
                val assumptions = if (protection == "pin") {
                    Assumptions.None.withInt(protected, if (protected == 0) 0L else 2L)
                } else {
                    Assumptions.None
                }
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
                if (protection == "owner") state.moveSink.setOwners(IntArray(6) { if (it == protected) 7 else -1 })
                if (protection == "definition") {
                    state.invariants = assertNotNull(DefinitionalSweep.infer(factors.toTypedArray(), 6)).network(6, 0)
                }
                state.assign(0, 2, 0, 1, if (protected == 0) 0 else 2, 1)
                state.recompute()

                state.factors[0].proposeRepairMoves(state, 0, state.moveSink)

                assertTrue(state.moveSink.list.none { it is Move.Compound }, "$protection/$protected")
            }
        }
    }

    @Test
    fun `start swap grading should include maintained durations in other resources`() {
        val factors = arrayOf<Factor>(
            Cumulative(intArrayOf(0, 1, 2, 3), longArrayOf(2, 1, 3, 1), longArrayOf(1, 1, 1, 1), 2),
            Product(0, 4, 5),
            Cumulative(intArrayOf(6), longArrayOf(6), longArrayOf(2), 1, durationVars = intArrayOf(5)),
        )
        val problem = Problem(
            0,
            7,
            arrayOf(
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 0),
                IntDomain(1, 1),
                IntDomain(3, 3),
                IntDomain(0, 6),
                IntDomain(0, 0),
            ),
            factors,
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 7)).network(7, 0)
        state.assign(0, 2, 0, 1, 3, 0, 0)
        state.recompute()

        state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
        val move = assertNotNull(
            state.moveSink.list.filterIsInstance<Move.Compound>().firstOrNull {
                it.parts.toSet() == setOf(IntSet(0, 2), IntSet(1, 0))
            },
        )
        val predicted = state.netDelta(move)
        state.apply(move)

        assertEquals(5L, predicted)
        assertEquals(0, state.factorDegree[0])
        assertEquals(6L, state.assignment.intValue(5))
        assertEquals(6L, state.cost)
    }

    @Test
    fun `shared starts should preserve simultaneous overload after a shift`() {
        for (remapped in listOf(false, true)) {
            for (unary in listOf(false, true)) {
                val starts = if (remapped) intArrayOf(0, 1) else intArrayOf(0, 0)
                val source = if (unary) {
                    Cumulative.unary(starts, longArrayOf(1, 1))
                } else {
                    Cumulative(starts, longArrayOf(1, 1), longArrayOf(2, 2), 3)
                }
                val factor = if (remapped) source.remap(VarRemap(intArrayOf(), intArrayOf(0, 0))) else source
                val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf<Factor>(factor))
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
                state.assignment.setInt(0, 0)
                state.recompute()
                state.weights.factorWeights[0] = 3.5
                assertEquals(1L, state.cost)

                for (target in listOf(1L, 3L, 0L)) {
                    val move = IntSet(0, target)
                    assertEquals(0.0, state.weightedNetDelta(move))
                    state.apply(move)
                    assertEquals(1, state.intPayload[0])
                }
            }
        }
    }

    @Test
    fun `aliased integer and presence moves should agree with independent overloads`() {
        val positive = Lit.make(0, true)
        val negative = Lit.make(0, false)
        val factors = listOf(
            Cumulative(intArrayOf(0, 0), longArrayOf(1, 2), longArrayOf(2, 1), 2),
            Cumulative(intArrayOf(0, 1), longArrayOf(2, 2), longArrayOf(2, 2), 2, durationVars = intArrayOf(1, 1)),
            Cumulative(intArrayOf(0, 1), longArrayOf(2, 1), longArrayOf(2, 2), 1, resourceVars = intArrayOf(1, 1)),
            Cumulative(intArrayOf(0, 1), longArrayOf(2, 2), longArrayOf(2, 1), 2, durationVars = intArrayOf(0, 1)),
            Cumulative(intArrayOf(0, 1), longArrayOf(2, 1), longArrayOf(2, 2), 1, resourceVars = intArrayOf(0, 1)),
            Cumulative(
                intArrayOf(0, 0),
                longArrayOf(2, 2),
                longArrayOf(2, 2),
                2,
                durationVars = intArrayOf(1, 1),
                resourceVars = intArrayOf(1, 1),
                capacityVar = 1,
            ),
            Cumulative(intArrayOf(0, 1), longArrayOf(1, 2), longArrayOf(2, 1), 2, capacityVar = 0),
            Cumulative(
                intArrayOf(0, 0),
                longArrayOf(2, 1),
                longArrayOf(2, 2),
                2,
                presents = intArrayOf(positive, positive),
            ),
            Cumulative(
                intArrayOf(0, 0),
                longArrayOf(2, 1),
                longArrayOf(2, 2),
                2,
                presents = intArrayOf(positive, negative),
                durationVars = intArrayOf(1, 1),
            ),
        )
        for ((index, factor) in factors.withIndex()) {
            val problem = Problem(1, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(factor))
            FactorPropagationOracle.assertMoveScoring(problem, "aliases/$index") { sample ->
                var overage = 0L
                val cap = if (factor.capacityVar < 0) factor.capacity else sample.ints[factor.capacityVar]
                for (time in 0L..3L) {
                    var usage = 0L
                    for (task in factor.starts.indices) {
                        val present = factor.presents.isEmpty() ||
                            sample.bools[Lit.variable(factor.presents[task])] == Lit.isPositive(factor.presents[task])
                        val start = sample.ints[factor.starts[task]]
                        val duration = if (factor.durationVars.isEmpty()) {
                            factor.durations[task]
                        } else {
                            sample.ints[factor.durationVars[task]]
                        }
                        val resource = if (factor.resourceVars.isEmpty()) {
                            factor.resources[task]
                        } else {
                            sample.ints[factor.resourceVars[task]]
                        }
                        if (present && time >= start && time < start + duration) usage += resource
                    }
                    overage += maxOf(0L, usage - cap)
                }
                overage.toInt()
            }
        }
    }

    @Test
    fun `mixed moves should preserve aliased overloads through probes and recomputation`() {
        val factor = Cumulative(
            intArrayOf(0, 0, 1),
            longArrayOf(2, 2, 2),
            longArrayOf(2, 2, 2),
            3,
            presents = intArrayOf(Lit.make(0, true), Lit.make(0, true), Lit.make(0, false)),
            durationVars = intArrayOf(2, 2, 2),
            resourceVars = intArrayOf(3, 3, 3),
            capacityVar = 4,
        )
        val problem = Problem(1, 5, Array(5) { IntDomain(0, 3) }, arrayOf<Factor>(factor))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assign(0, 1, 1, 2, 3)
        state.assignment.setBool(0, true)
        state.recompute()
        state.weights.factorWeights[0] = 3.5
        val moves = listOf(
            Move.Compound(listOf(IntSet(0, 1), IntSet(2, 2), IntSet(3, 3))),
            Move.Compound(listOf(Move.BoolFlip(0), IntSet(0, 2), IntSet(1, 0), IntSet(4, 1))),
            Move.Compound(listOf(IntSet(2, 0), Move.BoolFlip(0), IntSet(2, 3), IntSet(0, 0))),
            Move.Compound(listOf(IntSet(3, 0), IntSet(4, 0), Move.BoolFlip(0))),
        )
        for (move in moves) {
            val before = state.cost
            val predicted = state.netDelta(move)
            assertEquals(3.5 * predicted, state.weightedNetDelta(move))

            state.apply(move)

            var expected = 0L
            for (time in 0L..5L) {
                var usage = 0L
                for (task in factor.starts.indices) {
                    val present = state.assignment.boolValue(0) == Lit.isPositive(factor.presents[task])
                    val start = state.assignment.intValue(factor.starts[task])
                    val duration = state.assignment.intValue(2)
                    if (present && time >= start && time < start + duration) usage += state.assignment.intValue(3)
                }
                expected += maxOf(0L, usage - state.assignment.intValue(4))
            }
            assertEquals(expected - before, predicted)
            assertEquals(expected, state.cost)
            assertEquals(expected.toInt(), state.intPayload[0])
        }
    }

    @Test
    fun `primitive deltas should match committed grades across payload saturation`() {
        val demand = Int.MAX_VALUE.toLong()
        for (coordinate in listOf("start", "duration", "resource", "capacity", "presence")) {
            val start = coordinate == "start"
            val presence = coordinate == "presence"
            val domains = arrayOf(
                IntDomain(0, 1),
                IntDomain(0, 0),
                IntDomain(1, 2),
                IntDomain(demand, demand + 20),
                IntDomain(1, 21),
                IntDomain(1, 1),
            )
            val resources = when (coordinate) {
                "start" -> longArrayOf(demand + 63, 32)
                "capacity", "presence" -> longArrayOf(demand + 20)
                else -> longArrayOf(demand)
            }
            val problem = Problem(
                if (presence) 1 else 0,
                6,
                domains,
                arrayOf<Factor>(
                    Cumulative(
                        if (start) intArrayOf(0, 1) else intArrayOf(0),
                        if (start) longArrayOf(1, 1) else longArrayOf(1),
                        resources,
                        if (start) 64 else 1,
                        presents = if (presence) intArrayOf(Lit.make(0, true)) else intArrayOf(),
                        durationVars = if (coordinate == "duration") intArrayOf(2) else intArrayOf(),
                        resourceVars = if (coordinate == "resource") intArrayOf(3) else intArrayOf(),
                        capacityVar = if (coordinate == "capacity") 4 else -1,
                    ),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assign(if (start) 1 else 0, 0, 1, demand, 1, 1)
            if (presence) state.assignment.setBool(0, false)
            state.recompute()
            state.weights.factorWeights[0] = 3.5
            val before = state.cost
            val move = when (coordinate) {
                "start" -> IntSet(0, 0)
                "duration" -> IntSet(2, 2)
                "resource" -> IntSet(3, demand + 20)
                "capacity" -> IntSet(4, 21)
                else -> Move.BoolFlip(0)
            }
            val expectedDelta = if (presence) 47L else 0L

            val predicted = state.netDelta(move)
            val weighted = state.weightedNetDelta(move)

            assertEquals(expectedDelta, predicted, coordinate)
            assertEquals(3.5 * expectedDelta, weighted, coordinate)
            state.apply(move)
            assertEquals(before + predicted, state.cost, coordinate)
        }
    }

    @Test
    fun `conditional choice swaps should repair both resources without moving starts`() {
        for (bound in 0..1) {
            for (reversed in listOf(false, true)) {
                for (reduced in listOf(false, true)) {
                    val source = Problem(4, 16, choiceSwapDomains(), choiceSwapFactors(bound, reversed))
                    val state = conditionalState(source, choiceSwapNetwork(source), reduced, 8..11)
                    val first = if (bound == 1) 0L else 1L
                    state.assignChoiceSwap(first)
                    val before = state.cost

                    state.factors[12].proposeRepairMoves(state, 12, state.moveSink)
                    val move = assertNotNull(state.moveSink.list.firstOrNull { state.netDelta(it) < 0L })
                    val predicted = state.netDelta(move)
                    state.apply(move)

                    assertEquals(
                        listOf(1L - first, first, 0L, 0L, 2L),
                        listOf(0, 1, 12, 13, 14).map { state.assignment.intValue(it) },
                    )
                    assertEquals(-before, predicted)
                    assertEquals(0L, state.cost)
                }
            }
        }
    }

    @Test
    fun `conditional choice swaps should preserve protected coordinates`() {
        val values = choiceSwapValues(first = 0L)
        for (protection in listOf(
            "first choice pin",
            "second choice owner",
            "first channel owner",
            "second indicator pin",
            "first duration pin",
            "second duration owner",
            "destination domain",
        )) {
            for (reduced in listOf(false, true)) {
                val domains = choiceSwapDomains()
                if (protection == "destination domain") domains[1] = IntDomain(1, 1)
                val source = Problem(4, 16, domains, choiceSwapFactors())
                val variable = when {
                    protection.contains("choice") -> if (protection.startsWith("first")) 0 else 1
                    protection.contains("channel") -> if (protection.startsWith("first")) 2 else 3
                    protection.contains("duration") -> if (protection.startsWith("first")) 8 else 9
                    else -> -1
                }
                val assumptions = when {
                    protection.contains("indicator") -> Assumptions.None.withBool(1, false)
                    protection.endsWith("pin") -> Assumptions.None.withInt(variable, values[variable])
                    else -> Assumptions.None
                }
                val state = conditionalState(source, choiceSwapNetwork(source), reduced, 8..11, assumptions)
                if (protection.endsWith("owner")) {
                    state.moveSink.setOwners(IntArray(16) { if (it == variable) 16 else -1 })
                }
                state.assignChoiceSwap(first = 0L)

                state.factors[12].proposeRepairMoves(state, 12, state.moveSink)

                assertTrue(
                    state.moveSink.list.none { move ->
                        move is Move.Compound && move.parts.any { it is IntSet && it.varId == 0 } &&
                            move.parts.any { it is IntSet && it.varId == 1 }
                    },
                    protection,
                )
            }
        }
    }

    @Test
    fun `conditional repairs should reach improving tasks beyond earlier candidates`() {
        val factors = ArrayList<Factor>()
        repeat(6) { i ->
            factors.add(ReifiedLinear(i, intArrayOf(1), intArrayOf(i), LinearOp.EQ, 0))
            factors.add(ReifiedLinear(i, intArrayOf(1), intArrayOf(6 + i), LinearOp.EQ, 1))
            factors.add(Product(6 + i, 12, 13 + i))
        }
        val cumulative = factors.size
        factors.add(
            Cumulative(
                IntArray(6) { 19 + it },
                LongArray(6) { 1 },
                LongArray(6) { 1 },
                5,
                durationVars = IntArray(6) { 13 + it },
            ),
        )
        repeat(5) { factors.add(Clause(intArrayOf(Lit.make(it, true)))) }
        val problem = Problem(
            6,
            25,
            Array(25) {
                when (it) {
                    in 0..5 -> IntDomain(0, 4)
                    in 6..11, in 13..18 -> IntDomain(0, 1)
                    12 -> IntDomain(1, 1)
                    else -> IntDomain(0, 0)
                }
            },
            factors.toTypedArray(),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem, IntArray(6) { 6 + it })).network(25, 6)
        state.assign(*LongArray(25) { if (it in 6..18) 1L else 0L })
        for (i in 0 until 6) state.assignment.setBool(i, true)
        state.recompute()
        val before = state.cost
        val moves = ArrayList<Move>()

        repeat(2) {
            state.moveSink.clear()
            state.factors[cumulative].proposeRepairMoves(state, cumulative, state.moveSink)
            assertTrue(state.moveSink.size <= 16)
            moves.addAll(state.moveSink.list)
        }
        val move = assertNotNull(moves.firstOrNull { state.netDelta(it) < 0L })
        val predicted = state.netDelta(move)
        state.apply(move)

        assertTrue(state.assignment.intValue(5) > 0L)
        assertEquals(-before, predicted)
        assertEquals(0L, state.cost)
    }

    @Test
    fun `start repairs should skip tasks without a resource footprint`() {
        for (inactive in listOf(
            "duration",
            "resource",
            "variable duration",
            "variable resource",
            "presence",
            "negated presence",
        )) {
            val optional = inactive == "presence" || inactive == "negated presence"
            val present = inactive == "negated presence"
            val factor = Cumulative(
                intArrayOf(0, 1, 2),
                longArrayOf(2, 2, if (inactive == "duration") 0 else 2),
                longArrayOf(1, 1, if (inactive == "resource") 0 else 1),
                1,
                presents = if (optional) {
                    intArrayOf(Lit.make(0, present), Lit.make(0, present), Lit.make(0, !present))
                } else {
                    intArrayOf()
                },
                durationVars = if (inactive == "variable duration") intArrayOf(3, 3, 4) else intArrayOf(),
                resourceVars = if (inactive == "variable resource") intArrayOf(5, 5, 6) else intArrayOf(),
            )
            val problem = Problem(
                1,
                7,
                arrayOf(
                    IntDomain(0, 4),
                    IntDomain(0, 4),
                    IntDomain(0, 4),
                    IntDomain(2, 2),
                    IntDomain(0, 2),
                    IntDomain(1, 1),
                    IntDomain(0, 1),
                ),
                arrayOf<Factor>(factor),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assign(0, 0, 0, 2, 0, 1, 0)
            state.assignment.setBool(0, present)
            state.recompute()

            state.factors[0].proposeRepairMoves(state, 0, state.moveSink)

            val moves = state.moveSink.list
            assertTrue(moves.isNotEmpty(), inactive)
            assertTrue(moves.all { it is IntSet && it.varId < 2 }, inactive)
            assertTrue(moves.any { state.netDelta(it) < 0L }, inactive)
        }
    }

    @Test
    fun `start repairs should retain improvements outside the maximum peak`() {
        val problem = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 9) },
            arrayOf<Factor>(
                Cumulative(intArrayOf(0, 1, 2, 3), longArrayOf(2, 2, 2, 2), longArrayOf(1, 1, 1, 1), 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assign(0, 0, 5, 5)
        state.recompute()
        val before = state.cost

        state.factors[0].proposeRepairMoves(state, 0, state.moveSink)
        val move = state.moveSink.list.first { it is IntSet && it.varId == 2 && it.newValue == 6L }
        val predicted = state.netDelta(move)
        state.apply(move)

        assertTrue(predicted < 0L)
        assertEquals(predicted, state.cost - before)
    }

    @Test
    fun `conditional duration choice repairs should grade other Boolean constraints`() {
        for (bound in 0..1) {
            for (reduced in listOf(false, true)) {
                val factors = durationChoiceFactors(bound) + Clause(intArrayOf(Lit.make(0, bound == 1)))
                val source = Problem(1, 7, durationChoiceDomains(), factors)
                val network = assertNotNull(DefinitionalSweep.infer(source, intArrayOf(1))).network(7, 1)
                val state = conditionalState(source, network, reduced, 3..3)
                state.assignDurationChoice(bound)
                val before = state.cost

                state.factors[3].proposeRepairMoves(state, 3, state.moveSink)
                val move = state.moveSink.list.first()
                val predicted = state.netDelta(move)
                state.apply(move)

                assertEquals(0L, state.assignment.intValue(3))
                assertEquals(0, state.factorDegree[3])
                assertTrue(state.factorDegree[4] > 0)
                assertEquals(state.cost - before, predicted)
            }
        }
    }

    @Test
    fun `conditional durations should repair overload through admissible choices`() {
        for (bound in 0..1) {
            for (retained in listOf(false, true)) {
                for (reduced in listOf(false, true)) {
                    val factors = durationChoiceFactors(bound)
                    val source = Problem(1, 7, durationChoiceDomains(), factors)
                    val sweep = if (retained) {
                        DefinitionalSweep.infer(source, intArrayOf(1))
                    } else {
                        DefinitionalSweep.infer(factors, 7)
                    }
                    val state = conditionalState(source, assertNotNull(sweep).network(7, 1), reduced, 3..3)
                    state.assignDurationChoice(bound)
                    val before = state.cost

                    state.factors[3].proposeRepairMoves(state, 3, state.moveSink)
                    val move = state.moveSink.list.first()
                    val predicted = state.netDelta(move)
                    state.apply(move)

                    assertEquals(state.assignment.intValue(0) == 0L, state.assignment.boolValue(0))
                    assertEquals(0L, state.assignment.intValue(3))
                    assertEquals(-before, predicted)
                    assertEquals(0L, state.cost)
                }
            }
        }
    }

    @Test
    fun `conditional choice repairs should retain destination overload`() {
        val factors = arrayOf<Factor>(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
            ReifiedLinear(1, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 1),
            Product(1, 3, 4),
            Product(2, 3, 5),
            Cumulative(intArrayOf(6, 8), longArrayOf(3, 3), longArrayOf(1, 1), 1, durationVars = intArrayOf(4, 7)),
            Cumulative(intArrayOf(6, 9), longArrayOf(3, 3), longArrayOf(1, 1), 1, durationVars = intArrayOf(5, 7)),
        )
        val problem = Problem(
            2,
            10,
            arrayOf(
                IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(3, 3),
                IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 0), IntDomain(3, 3),
                IntDomain(0, 0), IntDomain(0, 0),
            ),
            factors,
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem, intArrayOf(1, 2))).network(10, 2)
        state.assign(0, 1, 0, 3, 3, 0, 0, 3, 0, 0)
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, false)
        state.recompute()
        val before = state.cost

        state.factors[6].proposeRepairMoves(state, 6, state.moveSink)
        val move = state.moveSink.list.single()
        val predicted = state.netDelta(move)
        state.apply(move)

        assertEquals(3L, state.assignment.intValue(5))
        assertTrue(state.factorDegree[7] > 0)
        assertEquals(0L, predicted)
        assertEquals(before, state.cost)
    }

    @Test
    fun `conditional duration repairs should preserve pinned and owned coordinates`() {
        for (protection in listOf(
            "choice pin",
            "choice owner",
            "channel pin",
            "channel owner",
            "indicator pin",
            "duration pin",
            "duration owner",
        )) {
            val factors = durationChoiceFactors()
            val assumptions = when (protection) {
                "choice pin" -> Assumptions.None.withInt(0, 0L)
                "channel pin" -> Assumptions.None.withInt(1, 1L)
                "indicator pin" -> Assumptions.None.withBool(0, true)
                "duration pin" -> Assumptions.None.withInt(3, 3L)
                else -> Assumptions.None
            }
            val problem = Problem(1, 7, durationChoiceDomains(), factors)
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions)
            state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 7)).network(7, 1)
            val owned = when (protection) {
                "choice owner" -> 0
                "channel owner" -> 1
                "duration owner" -> 3
                else -> -1
            }
            if (owned >= 0) state.moveSink.setOwners(IntArray(7) { if (it == owned) 7 else -1 })
            state.assignDurationChoice()

            state.factors[3].proposeRepairMoves(state, 3, state.moveSink)

            assertTrue(state.moveSink.list.isEmpty(), protection)
        }
    }

    @Test
    fun `conditional durations should not be removed outside their root domains`() {
        for (zeroMissing in listOf("channel", "duration")) {
            val factors = durationChoiceFactors()
            val domains = durationChoiceDomains(
                channelMin = if (zeroMissing == "channel") 1L else 0L,
                durationMin = if (zeroMissing == "duration") 1L else 0L,
            )
            val state = LocalSearchState(LocalSearchModel.open(Problem(1, 7, domains, factors)), Random(3))
            state.invariants = assertNotNull(DefinitionalSweep.infer(factors, 7)).network(7, 1)
            state.assignDurationChoice()

            state.factors[3].proposeRepairMoves(state, 3, state.moveSink)

            assertTrue(state.moveSink.list.isEmpty(), zeroMissing)
        }
    }

    private fun LocalSearchState.assign(vararg values: Long) {
        for ((variable, value) in values.withIndex()) assignment.setInt(variable, value)
    }

    private fun choiceSwapFactors(bound: Int = 1, reversed: Boolean = false): Array<Factor> {
        val factors = ArrayList<Factor>()
        repeat(4) { i ->
            factors.add(ReifiedLinear(i, intArrayOf(1), intArrayOf(i % 2), LinearOp.EQ, i / 2))
            factors.add(ReifiedLinear(i, intArrayOf(1), intArrayOf(2 + i), LinearOp.EQ, bound))
            factors.add(if (reversed) Product(6 + i % 2, 2 + i, 8 + i) else Product(2 + i, 6 + i % 2, 8 + i))
        }
        factors.add(
            Cumulative(
                intArrayOf(12, 13, 14),
                longArrayOf(3, 1, 1),
                longArrayOf(1, 1, 1),
                1,
                durationVars = intArrayOf(8, 9, 15),
            ),
        )
        factors.add(
            Cumulative(
                intArrayOf(12, 13),
                longArrayOf(3, 1),
                longArrayOf(1, 1),
                1,
                durationVars = intArrayOf(10, 11),
            ),
        )
        return factors.toTypedArray()
    }

    private fun choiceSwapDomains(): Array<IntDomain> = Array(16) {
        when (it) {
            in 0..5 -> IntDomain(0, 1)
            6 -> IntDomain(3, 3)
            7, 15 -> IntDomain(1, 1)
            in 8..11 -> IntDomain(0, 3)
            14 -> IntDomain(2, 2)
            else -> IntDomain(0, 0)
        }
    }

    private fun choiceSwapNetwork(source: Problem): InvariantNetwork =
        assertNotNull(DefinitionalSweep.infer(source, intArrayOf(2, 3, 4, 5))).network(16, 4)

    private fun choiceSwapValues(first: Long): LongArray =
        longArrayOf(first, 1L - first, 1, 0, 0, 1, 3, 1, 3, 0, 0, 1, 0, 0, 2, 1)

    private fun LocalSearchState.assignChoiceSwap(first: Long) {
        assign(*choiceSwapValues(first))
        repeat(4) { assignment.setBool(it, assignment.intValue(it % 2) == (it / 2).toLong()) }
        recompute()
    }

    private fun durationChoiceFactors(bound: Int = 1): Array<Factor> = arrayOf(
        ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
        ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, bound),
        Product(1, 2, 3),
        Cumulative(intArrayOf(4, 6), longArrayOf(3, 3), longArrayOf(1, 1), 1, durationVars = intArrayOf(3, 5)),
    )

    private fun durationChoiceDomains(channelMin: Long = 0L, durationMin: Long = 0L): Array<IntDomain> = arrayOf(
        IntDomain(0, 2),
        IntDomain(channelMin, 1L),
        IntDomain(3, 3),
        IntDomain(durationMin, 3L),
        IntDomain(0, 0),
        IntDomain(3, 3),
        IntDomain(0, 0),
    )

    private fun LocalSearchState.assignDurationChoice(bound: Int = 1) {
        assign(if (bound == 1) 0L else 1L, 1, 3, 3, 0, 3, 0)
        assignment.setBool(0, bound == 1)
        recompute()
    }

    private fun conditionalState(
        source: Problem,
        network: InvariantNetwork,
        reduced: Boolean,
        channelOutputs: IntRange,
        assumptions: Assumptions = Assumptions.None,
    ): LocalSearchState {
        if (!reduced) return stateOf(source, network, assumptions)
        val domains = source.finiteIntDomains()
        val factors = source.factors.map { factor ->
            if (factor is Product) {
                val rewrite = factor.structuralReduce(domains)
                assertTrue(rewrite is FactorReduction.Rewrite)
                rewrite.replacement.single()
            } else {
                factor
            }
        }
        val problem = Problem(source.numBoolVars, source.numIntVars, domains, factors.toTypedArray())
        val channels = factors.filterIsInstance<Linear>()
        val linearized = InvariantNetwork(
            List(network.size) { index ->
                val node = network.node(index)
                val linear = channels.firstOrNull {
                    !node.outIsBool && node.out in channelOutputs && node.out in it.vars
                }
                if (linear == null) {
                    node
                } else {
                    val row = assertNotNull(linear.integerConstants)
                    val output = linear.vars.indexOf(node.out)
                    val input = 1 - output
                    DefinitionalSweep.SweepNode.IntDef(
                        FunctionalObjective.Lin(
                            node.out,
                            row.coeff(output),
                            longArrayOf(row.coeff(input)),
                            arrayOf(FunctionalObjective.Operand.v(linear.vars[input])),
                            row.bound,
                        ),
                        intArrayOf(linear.vars[input]),
                    )
                }
            },
            source.numIntVars,
            source.numBoolVars,
        )
        return stateOf(problem, linearized, assumptions)
    }

    private fun stateOf(problem: Problem, network: InvariantNetwork, assumptions: Assumptions): LocalSearchState =
        LocalSearchState(LocalSearchModel.open(problem), Random(3), assumptions).also { it.invariants = network }
}
