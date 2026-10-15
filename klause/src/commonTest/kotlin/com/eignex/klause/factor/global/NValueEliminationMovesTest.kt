package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.ElementResultDefinition
import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.buildRepairChain
import com.eignex.klause.propagation.Assumptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NValueEliminationMovesTest {

    @Test
    fun `structured moves should eliminate a repeated class and reconcile the count channel`() {
        for (aliases in listOf(false, true)) {
            val xs = if (aliases) intArrayOf(1, 1, 2, 2) else intArrayOf(1, 2, 3, 4)
            val p = Problem(
                1,
                6,
                Array(6) { IntDomain(0, 2) },
                arrayOf<Factor>(
                    NValue(0, xs),
                    Linear(intArrayOf(1, -1), intArrayOf(0, 5), LinearOp.EQ, 0),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
            state.assignment.setInt(0, 2)
            state.assignment.setInt(5, 2)
            xs.forEachIndexed { i, variable -> state.assignment.setInt(variable, (i / 2).toLong()) }
            state.assignment.setBool(0, false)
            state.recompute()

            state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
            val move = state.moveSink.list.first { candidate ->
                candidate is Move.Compound && candidate.parts.contains(Move.IntSet(0, 1)) &&
                    state.netDelta(candidate) == 0L
            }
            state.apply(move)

            assertEquals(1L, state.assignment.intValue(0))
            assertEquals(1L, state.assignment.intValue(5))
            assertTrue(state.assignment.boolValue(0))
            assertEquals(1, xs.map { state.assignment.intValue(it) }.distinct().size)
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `class elimination should respect every member domain`() {
        for (blocked in listOf(IntDomain(0, 0), IntDomain(0, 2).excludeValue(1))) {
            val p = Problem(
                0,
                5,
                arrayOf(IntDomain(1, 2), IntDomain(0, 1), blocked, IntDomain(0, 1), IntDomain(1, 1)),
                arrayOf<Factor>(NValue(0, intArrayOf(1, 2, 3, 4))),
            )
            val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
            for ((variable, value) in listOf(0 to 2L, 1 to 0L, 2 to 0L, 3 to 1L, 4 to 1L)) {
                state.assignment.setInt(variable, value)
            }
            state.recompute()

            state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)

            assertTrue(state.moveSink.list.none { it is Move.Compound && Move.IntSet(0, 1) in it.parts })
        }
    }

    @Test
    fun `class elimination should distribute aliased members among surviving values`() {
        for (seed in listOf(3, 17, 41)) {
            val p = Problem(
                0,
                5,
                arrayOf(
                    IntDomain(2, 3),
                    IntDomain(0, 2).excludeValue(1),
                    IntDomain(1, 2),
                    IntDomain(0, 0),
                    IntDomain(1, 1),
                ),
                arrayOf<Factor>(NValue(0, intArrayOf(1, 1, 2, 2, 3, 4))),
            )
            val state = LocalSearchState(LocalSearchModel.open(p), Random(seed))
            for ((v, value) in listOf(0 to 3L, 1 to 2L, 2 to 2L, 3 to 0L, 4 to 1L)) {
                state.assignment.setInt(v, value)
            }
            state.recompute()

            state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
            val move = state.moveSink.list.first { candidate ->
                candidate is Move.Compound && Move.IntSet(0, 2) in candidate.parts && state.netDelta(candidate) == 0L
            }
            state.apply(move)

            assertEquals(0L, state.assignment.intValue(1))
            assertEquals(1L, state.assignment.intValue(2))
            assertEquals(2L, state.assignment.intValue(0))
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `distributed elimination should respect maintained selected result domains and pins`() {
        for (protection in listOf("none", "domain", "pin")) {
            val p = Problem(
                0,
                10,
                arrayOf(
                    IntDomain(2, 3), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 0), IntDomain(1, 1),
                    IntDomain(0, 0), IntDomain(1, 1), IntDomain(2, 2), IntDomain(0, 2).excludeValue(1),
                    if (protection == "domain") IntDomain(2, 2) else IntDomain(1, 2),
                ),
                arrayOf<Factor>(
                    NValue(0, intArrayOf(1, 2, 3, 4)),
                    Element(1, 8, longArrayOf(5, 6, 7), true, 0),
                    Element(2, 9, longArrayOf(5, 6, 7), true, 0),
                ),
            )
            val pins = if (protection == "pin") Assumptions(ints = mapOf(9 to 2L)) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(p), Random(3), pins)
            state.invariants = InvariantNetwork(
                listOf(
                    ElementResultDefinition(p.factors[1] as Element),
                    ElementResultDefinition(p.factors[2] as Element),
                ),
                10,
                0,
            )
            for ((v, value) in listOf(
                0 to 3L, 1 to 2L, 2 to 2L, 3 to 0L, 4 to 1L,
                5 to 0L, 6 to 1L, 7 to 2L, 8 to 2L, 9 to 2L,
            )) {
                state.assignment.setInt(v, value)
            }
            state.recompute()

            state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
            val moves = state.moveSink.list.filter { candidate ->
                candidate is Move.Compound && Move.IntSet(0, 2) in candidate.parts && state.netDelta(candidate) == 0L
            }

            if (protection == "none") {
                state.apply(moves.first())
                assertEquals(0L, state.assignment.intValue(1))
                assertEquals(1L, state.assignment.intValue(2))
                assertEquals(0L, state.cost)
            } else {
                assertTrue(moves.isEmpty())
            }
        }
    }

    @Test
    fun `frozen members and count should block coordinated elimination`() {
        for (pins in listOf(mapOf(0 to 2L), mapOf(1 to 0L, 3 to 1L))) {
            val p = Problem(
                0,
                5,
                Array(5) { IntDomain(0, 2) },
                arrayOf<Factor>(NValue(0, intArrayOf(1, 2, 3, 4))),
            )
            val state = LocalSearchState(LocalSearchModel.open(p), Random(3), Assumptions(ints = pins))
            state.assignment.setInt(0, 2)
            for (variable in 1..4) state.assignment.setInt(variable, ((variable - 1) / 2).toLong())
            state.recompute()

            state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)

            assertTrue(state.moveSink.list.none { it is Move.Compound && Move.IntSet(0, 1) in it.parts })
        }
    }

    @Test
    fun `absent positions should not block elimination of their present value class`() {
        val p = Problem(
            1,
            4,
            arrayOf(IntDomain(0, 2), IntDomain(0, 0), IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(
                NValue(
                    0,
                    intArrayOf(1, 2, 3),
                    presents = intArrayOf(
                        Lit.make(0, true),
                        Lit.make(0, false),
                        Lit.make(0, false),
                    ),
                ),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        state.assignment.setBool(0, false)
        for ((variable, value) in listOf(0 to 2L, 1 to 0L, 2 to 0L, 3 to 1L)) {
            state.assignment.setInt(variable, value)
        }
        state.recompute()

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
        val move = state.moveSink.list.first { it is Move.Compound && Move.IntSet(2, 1) in it.parts }
        state.apply(move)

        assertEquals(0L, state.assignment.intValue(1))
        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `count aliases should retain the final distinct count`() {
        for (mode in NValue.Mode.entries) {
            val p = Problem(
                0,
                3,
                Array(3) { IntDomain(0, 2) },
                arrayOf<Factor>(NValue(0, intArrayOf(0, 0, 1, 2), mode)),
            )
            val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
            for ((variable, value) in listOf(0 to 2L, 1 to 1L, 2 to 1L)) state.assignment.setInt(variable, value)
            state.recompute()

            state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
            val moves = state.moveSink.list.filter {
                it == Move.IntSet(0, 1) || (it is Move.Compound && Move.IntSet(0, 1) in it.parts)
            }
            assertTrue(moves.isNotEmpty())
            assertTrue(state.moveSink.list.all { state.netDelta(it) == 0L })
            for (move in moves) {
                for ((variable, value) in listOf(0 to 2L, 1 to 1L, 2 to 1L)) state.assignment.setInt(variable, value)
                state.recompute()
                state.apply(move)

                assertEquals(1L, state.assignment.intValue(0))
                assertEquals(1, (0..2).map { state.assignment.intValue(it) }.distinct().size)
                assertEquals(0L, state.cost)
            }
        }
    }

    @Test
    fun `inequality modes should keep an admissible count after elimination`() {
        for ((mode, count) in listOf(NValue.Mode.AtLeast to 1L, NValue.Mode.AtMost to 2L)) {
            val p = Problem(
                0,
                3,
                Array(3) { IntDomain(0, 2) },
                arrayOf<Factor>(NValue(0, intArrayOf(1, 1, 2, 2), mode)),
            )
            val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
            for ((variable, value) in listOf(0 to count, 1 to 0L, 2 to 1L)) state.assignment.setInt(variable, value)
            state.recompute()

            state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
            val move = state.moveSink.list.first { candidate ->
                candidate is Move.Compound || (
                    candidate is Move.IntSet && candidate.newValue in 0L..1L &&
                        state.netDelta(candidate) == 0L
                    )
            }
            state.apply(move)

            assertEquals(1, listOf(1, 2).map { state.assignment.intValue(it) }.distinct().size)
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `whole model scoring should reject a class merge that violates another constraint`() {
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 2) },
            arrayOf<Factor>(
                NValue(0, intArrayOf(1, 1, 2, 2)),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.NE, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        for ((variable, value) in listOf(0 to 2L, 1 to 0L, 2 to 1L)) state.assignment.setInt(variable, value)
        state.recompute()

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
        val merges = state.moveSink.list.filter { it is Move.Compound && Move.IntSet(0, 1) in it.parts }

        assertTrue(merges.isNotEmpty())
        assertTrue(merges.all { state.netDelta(it) > 0L })
        assertEquals(0L, state.cost)
        assertEquals(2L, state.assignment.intValue(0))
    }

    @Test
    fun `foreign ownership should block the entire class instead of dropping a member`() {
        val p = Problem(
            0,
            5,
            Array(5) { IntDomain(0, 2) },
            arrayOf<Factor>(NValue(0, intArrayOf(1, 2, 3, 4))),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        state.assignment.setInt(0, 2)
        for (variable in 1..4) state.assignment.setInt(variable, ((variable - 1) / 2).toLong())
        state.recompute()
        state.moveSink.setOwners(intArrayOf(0, 1, 0, 1, 0))
        state.moveSink.proposer = 0

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)

        assertTrue(state.moveSink.list.none { it is Move.Compound && Move.IntSet(0, 1) in it.parts })
    }

    @Test
    fun `at most elimination should preserve a frozen count that remains satisfied`() {
        val p = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 2) },
            arrayOf<Factor>(NValue(0, intArrayOf(1, 1, 2, 2), NValue.Mode.AtMost)),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3), Assumptions(ints = mapOf(0 to 2L)))
        for ((variable, value) in listOf(0 to 2L, 1 to 0L, 2 to 1L)) state.assignment.setInt(variable, value)
        state.recompute()

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
        val move = state.moveSink.list.first { it == Move.IntSet(1, 1) || it == Move.IntSet(2, 0) }
        state.apply(move)

        assertEquals(2L, state.assignment.intValue(0))
        assertEquals(state.assignment.intValue(1), state.assignment.intValue(2))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `class elimination should decline a count excluded from the root domain`() {
        val p = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 2).excludeValue(1), IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(NValue(0, intArrayOf(1, 1, 2, 2))),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        for ((variable, value) in listOf(0 to 2L, 1 to 0L, 2 to 1L)) state.assignment.setInt(variable, value)
        state.recompute()

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)

        assertTrue(state.moveSink.list.none { it is Move.Compound && Move.IntSet(0, 1) in it.parts })
    }

    @Test
    fun `class elimination should decline maintained coordinates in another class`() {
        val p = Problem(
            0,
            4,
            arrayOf(IntDomain(0, 2), IntDomain(0, 4), IntDomain(0, 16), IntDomain(0, 4)),
            arrayOf<Factor>(NValue(0, intArrayOf(1, 2, 3)), Product(1, 1, 2)),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        state.invariants = assertNotNull(DefinitionalSweep.infer(p.factors, p.numIntVars)).network(4, 0)
        for ((variable, value) in listOf(0 to 2L, 1 to 2L, 2 to 4L, 3 to 4L)) state.assignment.setInt(variable, value)
        state.recompute()

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)

        assertTrue(state.moveSink.list.none { it is Move.Compound && Move.IntSet(0, 1) in it.parts })
        assertEquals(0L, state.cost)
    }

    @Test
    fun `class elimination should reconcile wider equality channels in one feasible proposal`() {
        val p = Problem(
            2,
            5,
            arrayOf(IntDomain(0, 2), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf<Factor>(
                NValue(0, intArrayOf(1, 1, 2, 2)),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(3), LinearOp.EQ, 0),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 1),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(4), LinearOp.EQ, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        for ((variable, value) in listOf(0 to 2L, 1 to 0L, 2 to 1L, 3 to 0L, 4 to 0L)) {
            state.assignment.setInt(variable, value)
        }
        state.assignment.setBool(0, true)
        state.assignment.setBool(1, true)
        state.recompute()

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
        val move = state.moveSink.list.first {
            it is Move.Compound && Move.IntSet(0, 1) in it.parts &&
                state.netDelta(it) == 0L
        }
        assertEquals(0L, state.cost)
        state.apply(move)

        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(state.assignment.intValue(1), state.assignment.intValue(2))
        assertTrue(state.assignment.intValue(3) > 0L || state.assignment.intValue(4) > 0L)
        assertEquals(0L, state.cost)
    }

    @Test
    fun `count channels should use a free partner when another is foreign owned`() {
        val p = Problem(
            0,
            8,
            arrayOf(
                IntDomain(1, 2),
                IntDomain(0, 1),
                IntDomain(0, 1),
                IntDomain(0, 1),
                IntDomain(0, 1),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
            ),
            arrayOf<Factor>(
                NValue(0, intArrayOf(1, 2, 3, 4)),
                AllDifferent(intArrayOf(5, 7), domainMin = 0, domainSize = 3),
                Linear(intArrayOf(1, -1, 1), intArrayOf(0, 5, 6), LinearOp.EQ, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        state.seedImplicitFeasible()
        for ((variable, value) in listOf(0 to 2L, 1 to 0L, 2 to 0L, 3 to 1L, 4 to 1L, 5 to 2L, 6 to 0L, 7 to 0L)) {
            state.assignment.setInt(variable, value)
        }
        state.recompute()
        state.moveSink.proposer = 0

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
        val move = state.moveSink.list.first {
            it is Move.Compound && Move.IntSet(0, 1) in it.parts && state.netDelta(it) == 0L
        }
        state.apply(move)

        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(2L, state.assignment.intValue(5))
        assertEquals(1L, state.assignment.intValue(6))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `class elimination should retain a maintained count indicator`() {
        val p = Problem(
            1,
            3,
            arrayOf(IntDomain(1, 2), IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(
                NValue(0, intArrayOf(1, 1, 2, 2)),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        state.invariants = InvariantNetwork(
            listOf(DefinitionalSweep.SweepNode.CmpReif(0, longArrayOf(1), intArrayOf(0), 1, LinearOp.EQ)),
            3,
            1,
        )
        for ((variable, value) in listOf(0 to 2L, 1 to 0L, 2 to 1L)) state.assignment.setInt(variable, value)
        state.assignment.setBool(0, false)
        state.recompute()

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
        val move = state.moveSink.list.first {
            it is Move.Compound && Move.IntSet(0, 1) in it.parts && state.netDelta(it) == 0L
        }
        state.apply(move)

        assertEquals(1L, state.assignment.intValue(0))
        assertTrue(state.assignment.boolValue(0))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `class completion should reconcile a feasible merge among competing owned classes`() {
        for (seed in listOf(3, 17, 41)) {
            val p = Problem(
                0,
                7,
                arrayOf(
                    IntDomain(1, 3),
                    IntDomain(0, 1),
                    IntDomain(0, 1),
                    IntDomain(0, 1),
                    IntDomain(0, 1),
                    IntDomain(0, 2),
                    IntDomain(0, 2),
                ),
                arrayOf<Factor>(
                    NValue(0, intArrayOf(1, 2, 3, 4, 5, 5)),
                    Linear(intArrayOf(1, -1), intArrayOf(1, 3), LinearOp.NE, 0),
                    Linear(intArrayOf(1, -1), intArrayOf(2, 4), LinearOp.NE, 0),
                    Linear(intArrayOf(1, -1), intArrayOf(5, 6), LinearOp.EQ, 0),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(p), Random(seed))
            state.seedImplicitFeasible()
            for ((variable, value) in listOf(0 to 3L, 1 to 0L, 2 to 0L, 3 to 1L, 4 to 1L, 5 to 2L, 6 to 2L)) {
                state.assignment.setInt(variable, value)
            }
            state.recompute()
            state.moveSink.proposer = 0

            state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
            val move = state.moveSink.list.first {
                it is Move.Compound && Move.IntSet(0, 2) in it.parts && state.netDelta(it) == 0L
            }
            state.apply(move)

            assertEquals(2L, state.assignment.intValue(0))
            assertEquals(2, (1..5).map { state.assignment.intValue(it) }.distinct().size)
            assertEquals(state.assignment.intValue(5), state.assignment.intValue(6))
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `count reduction should reconcile repeated classes within the caller's directed walk`() {
        for (seed in listOf(3, 17, 41)) {
            val p = Problem(
                0,
                6,
                arrayOf(
                    IntDomain(1, 2),
                    IntDomain(0, 1),
                    IntDomain(0, 1),
                    IntDomain(0, 1),
                    IntDomain(0, 1),
                    IntDomain(0, 4),
                ),
                arrayOf<Factor>(
                    NValue(0, intArrayOf(1, 2, 3, 4)),
                    Linear(intArrayOf(1, 1, 1, 1, -1), intArrayOf(1, 2, 3, 4, 5), LinearOp.EQ, 0),
                    Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(p), Random(seed))
            for ((v, value) in listOf(0 to 2L, 1 to 0L, 2 to 0L, 3 to 1L, 4 to 1L, 5 to 2L)) {
                state.assignment.setInt(v, value)
            }
            state.recompute()
            assertEquals(1L, state.cost)

            val parts = state.buildRepairChain(Move.IntSet(0, 1), 8, MoveSink(state.assumptions))
            val move = Move.Compound(assertNotNull(parts))
            assertEquals(1L, state.cost)
            state.apply(move)

            assertEquals(1L, state.assignment.intValue(0))
            assertEquals(1, (1..4).map { state.assignment.intValue(it) }.distinct().size)
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `class elimination should reconcile the combined drift of a surrounding equality`() {
        val p = Problem(
            0,
            7,
            arrayOf(
                IntDomain(0, 2),
                IntDomain(0, 1),
                IntDomain(0, 1),
                IntDomain(0, 1),
                IntDomain(0, 1),
                IntDomain(0, 2),
                IntDomain(0, 4),
            ),
            arrayOf<Factor>(
                NValue(0, intArrayOf(1, 2, 3, 4)),
                Linear(intArrayOf(1, -1), intArrayOf(0, 5), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1, 1, 1, -1), intArrayOf(1, 2, 3, 4, 6), LinearOp.EQ, 0),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        for ((variable, value) in listOf(0 to 2L, 1 to 0L, 2 to 0L, 3 to 1L, 4 to 1L, 5 to 2L, 6 to 2L)) {
            state.assignment.setInt(variable, value)
        }
        state.recompute()

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
        val move = state.moveSink.list.first {
            it is Move.Compound && Move.IntSet(0, 1) in it.parts &&
                state.netDelta(it) == 0L
        }
        state.apply(move)

        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(1L, state.assignment.intValue(5))
        assertEquals((1..4).sumOf { state.assignment.intValue(it) }, state.assignment.intValue(6))
        assertEquals(0L, state.cost)
    }

    @Test
    fun `class clause completion should respect protected auxiliary attributes`() {
        for (protection in listOf("pin", "domain hole", "owner")) {
            val p = Problem(
                3,
                6,
                arrayOf(
                    IntDomain(1, 2),
                    IntDomain(0, 0),
                    IntDomain(0, 0),
                    IntDomain(0, 1),
                    IntDomain(0, 1),
                    if (protection == "domain hole") IntDomain(-1, 1).excludeValue(0) else IntDomain(0, 1),
                ),
                arrayOf<Factor>(
                    NValue(0, intArrayOf(1, 2, 3, 4)),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(3), LinearOp.EQ, 1),
                    ReifiedLinear(1, intArrayOf(1), intArrayOf(4), LinearOp.EQ, 1),
                    ReifiedLinear(2, intArrayOf(1), intArrayOf(5), LinearOp.EQ, 0),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
                ),
            )
            val assumptions = if (protection == "pin") Assumptions.None.withInt(5, 1) else Assumptions.None
            val state = LocalSearchState(LocalSearchModel.open(p), Random(3), assumptions)
            state.invariants = InvariantNetwork(
                listOf(
                    DefinitionalSweep.SweepNode.CmpReif(0, longArrayOf(1), intArrayOf(3), 1, LinearOp.EQ),
                    DefinitionalSweep.SweepNode.CmpReif(1, longArrayOf(1), intArrayOf(4), 1, LinearOp.EQ),
                    DefinitionalSweep.SweepNode.CmpReif(2, longArrayOf(1), intArrayOf(5), 0, LinearOp.EQ),
                ),
                6,
                3,
            )
            if (protection == "owner") state.moveSink.setOwners(intArrayOf(-1, -1, -1, -1, -1, 7))
            state.assignment.setInt(0, 2)
            for (variable in 3..5) state.assignment.setInt(variable, 1)
            state.assignment.setBool(0, true)
            state.assignment.setBool(1, true)
            state.recompute()

            state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)

            assertTrue(
                state.moveSink.list.none { move ->
                    move is Move.Compound && Move.IntSet(0, 1) in move.parts && state.netDelta(move) == 0L
                },
                protection,
            )
        }
    }

    @Test
    fun `class elimination should carry a searched usage flag through its unary channels`() {
        for ((constant, constantFirst) in listOf(0L to false, 1L to true)) {
            val p = Problem(
                3,
                8,
                arrayOf(
                    IntDomain(1, 2),
                    IntDomain(0, 0),
                    IntDomain(0, 0),
                    IntDomain(0, 1),
                    IntDomain(0, 1),
                    IntDomain(0, 2),
                    IntDomain(0, 2),
                    IntDomain(constant, constant),
                ),
                arrayOf<Factor>(
                    NValue(0, intArrayOf(1, 2, 3, 4)),
                    ReifiedLinear(0, intArrayOf(1), intArrayOf(3), LinearOp.EQ, 1),
                    ReifiedLinear(1, intArrayOf(1), intArrayOf(4), LinearOp.EQ, 1),
                    ReifiedLinear(
                        2,
                        intArrayOf(1, -1),
                        if (constantFirst) intArrayOf(7, 5) else intArrayOf(5, 7),
                        LinearOp.NE,
                        0,
                    ),
                    ReifiedLinear(
                        2,
                        intArrayOf(1, -1),
                        if (constantFirst) intArrayOf(7, 6) else intArrayOf(6, 7),
                        LinearOp.NE,
                        0,
                    ),
                    Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
                    Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false))),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
            state.invariants = InvariantNetwork(
                listOf(
                    DefinitionalSweep.SweepNode.CmpReif(0, longArrayOf(1), intArrayOf(3), 1, LinearOp.EQ),
                    DefinitionalSweep.SweepNode.CmpReif(1, longArrayOf(1), intArrayOf(4), 1, LinearOp.EQ),
                ),
                8,
                3,
            )
            state.assignment.setInt(0, 2)
            for (variable in 3..4) state.assignment.setInt(variable, 1)
            state.assignment.setInt(5, constant + 1)
            state.assignment.setInt(6, constant + 1)
            state.assignment.setInt(7, constant)
            for (b in 0..2) state.assignment.setBool(b, true)
            state.recompute()

            state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
            val completion = state.moveSink.list.first { move ->
                move is Move.Compound && Move.IntSet(0, 1) in move.parts && state.netDelta(move) == 0L
            }
            state.apply(completion)

            assertEquals(1L, state.assignment.intValue(0))
            assertEquals(constant, state.assignment.intValue(5))
            assertEquals(constant, state.assignment.intValue(6))
            assertEquals(false, state.assignment.boolValue(2))
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `class completion should select the merge with the least reconciled cost`() {
        val p = Problem(
            5,
            7,
            arrayOf(
                IntDomain(2, 3),
                IntDomain(0, 0),
                IntDomain(1, 1),
                IntDomain(0, 2),
                IntDomain(1, 1),
                IntDomain(0, 1),
                IntDomain(0, 1),
            ),
            arrayOf<Factor>(
                NValue(0, intArrayOf(1, 2, 3)),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(3), LinearOp.EQ, 0),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(4), LinearOp.EQ, 0),
                ReifiedLinear(2, intArrayOf(1), intArrayOf(3), LinearOp.EQ, 1),
                ReifiedLinear(3, intArrayOf(1), intArrayOf(5), LinearOp.EQ, 0),
                ReifiedLinear(4, intArrayOf(1), intArrayOf(6), LinearOp.EQ, 0),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(4, true))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        state.invariants = InvariantNetwork(
            listOf(
                DefinitionalSweep.SweepNode.CmpReif(0, longArrayOf(1), intArrayOf(3), 0, LinearOp.EQ),
                DefinitionalSweep.SweepNode.CmpReif(1, longArrayOf(1), intArrayOf(4), 0, LinearOp.EQ),
                DefinitionalSweep.SweepNode.CmpReif(2, longArrayOf(1), intArrayOf(3), 1, LinearOp.EQ),
                DefinitionalSweep.SweepNode.CmpReif(3, longArrayOf(1), intArrayOf(5), 0, LinearOp.EQ),
                DefinitionalSweep.SweepNode.CmpReif(4, longArrayOf(1), intArrayOf(6), 0, LinearOp.EQ),
            ),
            7,
            5,
        )
        for ((v, value) in listOf(0 to 3L, 1 to 0L, 2 to 1L, 3 to 2L, 4 to 1L, 5 to 1L, 6 to 1L)) {
            state.assignment.setInt(v, value)
        }
        state.recompute()

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
        val move = state.moveSink.list.first { candidate ->
            candidate is Move.Compound && Move.IntSet(0, 2) in candidate.parts && state.netDelta(candidate) == 0L
        }
        state.apply(move)

        assertEquals(1L, state.assignment.intValue(3))
        assertEquals(0L, state.assignment.intValue(5))
        assertEquals(0L, state.assignment.intValue(6))
        assertEquals(2L, state.assignment.intValue(0))
        assertEquals(0L, state.cost)
    }
}
