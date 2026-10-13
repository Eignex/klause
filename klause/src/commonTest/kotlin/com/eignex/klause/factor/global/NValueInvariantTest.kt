package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NValueInvariantTest {

    // Var 0 = n (target), vars 1..3 = xs
    private fun problem(domainHi: Int = 3): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = Array(4) { IntDomain(0, domainHi.toLong()) },
        factors = arrayOf<Factor>(NValue(n = 0, xs = intArrayOf(1, 2, 3))),
    )

    @Test
    fun `violated when distinct count differs from n by the size of the mismatch`() {
        // n=3 throughout; degree = |n - distinct(xs)|.
        val cases = listOf(
            listOf(0L, 0L, 1L) to 1,
            listOf(0L, 0L, 0L) to 2,
        )
        for ((xs, degree) in cases) {
            val state = LocalSearchState(problem().bake(), Random(0))
            state.assignment.setInt(0, 3)
            for (i in 0..2) state.assignment.setInt(1 + i, xs[i])
            state.recompute()
            assertTrue(state.factors[0].isViolated(state, 0), "xs=$xs must violate nvalue")
            assertEquals(degree, state.factors[0].violationDegree(state, 0), "xs=$xs")
        }
    }

    @Test
    fun `delta predicts degree change when introducing new distinct value`() {
        val p = problem()
        val state = LocalSearchState(p.bake(), Random(0))
        // n=3, xs=[0,0,0] → degree=2; change xs[0]=0 to xs[0]=1 → distinct=2, degree=1
        state.assignment.setInt(0, 3)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 0)
        state.assignment.setInt(3, 0)
        state.recompute()
        val before = state.factors[0].violationDegree(state, 0)
        val delta = state.factors[0].deltaIfIntSet(state, 0, 1, 1)
        state.apply(Move.IntSet(1, 1))
        val after = state.factors[0].violationDegree(state, 0)
        assertEquals(after - before, delta)
    }

    @Test
    fun `structured moves eliminate a repeated class and reconcile the count channel`() {
        for (aliases in listOf(false, true)) {
            val xs = if (aliases) intArrayOf(1, 1, 2, 2) else intArrayOf(1, 2, 3, 4)
            val p = Problem(
                1, 6, Array(6) { IntDomain(0, 2) },
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
            state.recompute()
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `class elimination respects every member domain`() {
        for (blocked in listOf(IntDomain(0, 0), IntDomain(0, 2).excludeValue(1))) {
            val p = Problem(
                0, 5, arrayOf(IntDomain(1, 2), IntDomain(0, 1), blocked, IntDomain(0, 1), IntDomain(1, 1)),
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
    fun `frozen members and count block coordinated elimination`() {
        for (pins in listOf(mapOf(0 to 2L), mapOf(1 to 0L, 3 to 1L))) {
            val p = Problem(
                0, 5, Array(5) { IntDomain(0, 2) },
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
    fun `absent positions do not block elimination of their present value class`() {
        val p = Problem(
            1, 4, arrayOf(IntDomain(0, 2), IntDomain(0, 0), IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(NValue(0, intArrayOf(1, 2, 3), presents = intArrayOf(
                Lit.make(0, true), Lit.make(0, false), Lit.make(0, false),
            ))),
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
    fun `count aliases retain the final distinct count`() {
        val p = Problem(
            0, 3, Array(3) { IntDomain(0, 2) },
            arrayOf<Factor>(NValue(0, intArrayOf(0, 0, 1, 2))),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        for ((variable, value) in listOf(0 to 2L, 1 to 1L, 2 to 1L)) state.assignment.setInt(variable, value)
        state.recompute()

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
        val move = state.moveSink.list.first { it is Move.IntSet && it == Move.IntSet(0, 1) }
        assertTrue(state.moveSink.list.all { state.netDelta(it) == 0L })
        state.apply(move)

        assertEquals(1L, state.assignment.intValue(0))
        assertEquals(0L, state.cost)
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `inequality modes keep an admissible count after elimination`() {
        for ((mode, count) in listOf(NValue.Mode.AtLeast to 1L, NValue.Mode.AtMost to 2L)) {
            val p = Problem(
                0, 3, Array(3) { IntDomain(0, 2) },
                arrayOf<Factor>(NValue(0, intArrayOf(1, 1, 2, 2), mode)),
            )
            val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
            for ((variable, value) in listOf(0 to count, 1 to 0L, 2 to 1L)) state.assignment.setInt(variable, value)
            state.recompute()

            state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)
            val move = state.moveSink.list.first { candidate ->
                candidate is Move.Compound || candidate is Move.IntSet && candidate.newValue in 0L..1L &&
                    state.netDelta(candidate) == 0L
            }
            state.apply(move)

            assertEquals(1, listOf(1, 2).map { state.assignment.intValue(it) }.distinct().size)
            assertEquals(0L, state.cost)
        }
    }

    @Test
    fun `whole model scoring rejects a class merge that violates another constraint`() {
        val p = Problem(
            0, 3, Array(3) { IntDomain(0, 2) },
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
    fun `foreign ownership blocks the entire class instead of dropping a member`() {
        val p = Problem(
            0, 5, Array(5) { IntDomain(0, 2) },
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
    fun `at most elimination preserves a frozen count that remains satisfied`() {
        val p = Problem(
            0, 3, Array(3) { IntDomain(0, 2) },
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
    fun `class elimination declines a count excluded from the root domain`() {
        val p = Problem(
            0, 3, arrayOf(IntDomain(0, 2).excludeValue(1), IntDomain(0, 1), IntDomain(0, 1)),
            arrayOf<Factor>(NValue(0, intArrayOf(1, 1, 2, 2))),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        for ((variable, value) in listOf(0 to 2L, 1 to 0L, 2 to 1L)) state.assignment.setInt(variable, value)
        state.recompute()

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)

        assertTrue(state.moveSink.list.none { it is Move.Compound && Move.IntSet(0, 1) in it.parts })
    }

    @Test
    fun `class elimination declines classes exceeding the coordinate cap`() {
        val p = Problem(
            0, 67, Array(67) { IntDomain(0, 2) },
            arrayOf<Factor>(NValue(0, IntArray(66) { it + 1 })),
        )
        val state = LocalSearchState(LocalSearchModel.open(p), Random(3))
        state.assignment.setInt(0, 2)
        for (variable in 1..66) state.assignment.setInt(variable, ((variable - 1) / 33).toLong())
        state.recompute()

        state.factors[0].proposeStructuredMoves(state, 0, state.moveSink)

        assertTrue(state.moveSink.list.none { it is Move.Compound && Move.IntSet(0, 1) in it.parts })
    }
}
