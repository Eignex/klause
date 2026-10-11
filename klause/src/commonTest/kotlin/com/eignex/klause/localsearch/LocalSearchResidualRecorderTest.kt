package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.result.LocalSearchResidualFactor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class LocalSearchResidualRecorderTest {
    @Test
    fun `element residual coordinates prioritize the selected cell across index offsets`() {
        for (offset in listOf(0, 1, -17, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val factor = Element(0, 1, LongArray(32) { 2L + it }, true, offset)
            val index = offset.toLong() + 31L
            val domains = Array(34) { IntDomain(0, 40) }
            domains[0] = IntDomain(offset.toLong(), index)
            val problem = Problem(0, 34, domains, arrayOf<Factor>(factor))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(0, index)
            repeat(32) { state.assignment.setInt(2 + it, 1L + it) }
            state.recompute()
            val recorder = LocalSearchResidualRecorder()

            recorder.observe(state)

            val observed = assertNotNull(recorder.best).factors.single()
            assertEquals(listOf(0, 1, 33) + (2..14), observed.ints.keys.toList(), "$offset")
            assertEquals(index, observed.ints[0], "$offset")
            assertEquals(0L, observed.ints[1], "$offset")
            assertEquals(32L, observed.ints[33], "$offset")
            assertEquals(18, observed.omittedInts, "$offset")
            assertEquals(emptyMap(), observed.bools, "$offset")
            assertEquals(state.cost, recorder.best?.cost, "$offset")
        }
    }

    @Test
    fun `element residual selection uses the offset after affine index substitution`() {
        val original = Element(0, 1, longArrayOf(2, 3), true)
        val lowered = assertNotNull(original.substituteAffine(0, 1, 7, 4)) as Element
        val domains = Array(5) { IntDomain(0, 10) }
        domains[4] = IntDomain(-6, -5)
        val problem = Problem(0, 5, domains, arrayOf<Factor>(lowered))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(4, -5)
        state.assignment.setInt(2, 3)
        state.assignment.setInt(3, 9)
        state.recompute()
        val recorder = LocalSearchResidualRecorder()

        recorder.observe(state)

        val observed = assertNotNull(recorder.best).factors.single()
        assertEquals(mapOf(4 to -5L, 1 to 0L, 3 to 9L, 2 to 3L), observed.ints)
        assertEquals(listOf(4, 1, 3, 2), observed.ints.keys.toList())
        assertEquals(0, observed.omittedInts)
    }

    @Test
    fun `element residual omissions count distinct coordinates across aliased roles`() {
        for ((sharedResult, selectedIndex) in listOf(false to false, true to false, false to true)) {
            val cells = LongArray(32) { 2L + it % 20 }
            cells[0] = 0
            cells[1] = 1
            cells[31] = if (selectedIndex) 0 else 21
            val factor = Element(0, if (sharedResult) 0 else 1, cells, true, 0)
            val problem = Problem(0, 22, Array(22) { IntDomain(0, 40) }, arrayOf<Factor>(factor))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(0, 31)
            for (v in 2..21) state.assignment.setInt(v, 8)
            state.recompute()
            val recorder = LocalSearchResidualRecorder()

            recorder.observe(state)

            val observed = assertNotNull(recorder.best).factors.single()
            val expected = when {
                sharedResult -> listOf(0, 21) + (1..14)
                selectedIndex -> (0..15).toList()
                else -> listOf(0, 1, 21) + (2..14)
            }
            assertEquals(expected, observed.ints.keys.toList())
            assertEquals(6, observed.omittedInts)
        }
    }

    @Test
    fun `element residual invalid indexes retain the ordinary bounded coordinate sample`() {
        for (offset in listOf(0, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val lower = offset.toLong()
            val indexes = listOf(lower - 1, lower + 32) +
                if (offset == 0) listOf(Long.MIN_VALUE, Long.MAX_VALUE) else emptyList()
            for (index in indexes) {
                val factor = Element(0, 1, LongArray(32) { 2L + it }, true, offset)
                val domains = Array(34) { IntDomain(0, 1) }
                domains[0] = IntDomain(index, index)
                val problem = Problem(0, 34, domains, arrayOf<Factor>(factor))
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
                state.assignment.setInt(0, index)
                state.recompute()
                val recorder = LocalSearchResidualRecorder()

                recorder.observe(state)

                val observed = assertNotNull(recorder.best).factors.single()
                assertEquals((0..15).toList(), observed.ints.keys.toList(), "$offset/$index")
                assertEquals(index, observed.ints[0], "$offset/$index")
                assertEquals(18, observed.omittedInts, "$offset/$index")
            }
        }
    }

    @Test
    fun `element residual constant arrays contain only index and result coordinates`() {
        for (index in listOf(-1L, 31L, 32L)) {
            val factor = Element(0, 1, LongArray(32) { Long.MAX_VALUE }, false, 0)
            val problem = Problem(0, 2, arrayOf(IntDomain(-1, 32), IntDomain(0, 1)), arrayOf<Factor>(factor))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(0, index)
            state.recompute()
            val recorder = LocalSearchResidualRecorder()

            recorder.observe(state)

            val observed = assertNotNull(recorder.best).factors.single()
            assertEquals(mapOf(0 to index, 1 to 0L), observed.ints)
            assertEquals(0, observed.omittedInts)
        }
    }

    @Test
    fun `element residual snapshots retain committed best selections through probes and later moves`() {
        val factor = Element(0, 1, LongArray(32) { 2L + it }, true, 0)
        val problem = Problem(0, 34, Array(34) { IntDomain(0, 40) }, arrayOf<Factor>(factor))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 31)
        state.assignment.setInt(32, 8)
        state.assignment.setInt(33, 10)
        state.recompute()
        val recorder = LocalSearchResidualRecorder()
        recorder.observe(state)
        val initial = assertNotNull(recorder.best)

        state.netDelta(Move.Compound(listOf(Move.IntSet(0, 30), Move.IntSet(32, 1))))
        recorder.observe(state)
        assertSame(initial, recorder.best)
        state.apply(Move.IntSet(0, 30))
        recorder.observe(state)
        val improved = assertNotNull(recorder.best)
        state.apply(Move.IntSet(32, 9))
        state.apply(Move.IntSet(33, 12))
        recorder.observe(state)

        assertSame(improved, recorder.best)
        assertEquals(10L, initial.cost)
        assertEquals(listOf(0, 1, 33), initial.factors.single().ints.keys.take(3))
        assertEquals(10L, initial.factors.single().ints[33])
        assertEquals(8L, improved.cost)
        assertEquals(listOf(0, 1, 32), improved.factors.single().ints.keys.take(3))
        assertEquals(8L, improved.factors.single().ints[32])
        assertEquals(18, improved.factors.single().omittedInts)
    }

    @Test
    fun `cumulative residual coordinates prioritize tasks at the overloaded peak`() {
        for (inactive in listOf("duration", "resource", "presence", "negated presence")) {
            val optional = inactive == "presence" || inactive == "negated presence"
            val positive = inactive != "negated presence"
            val factor = Cumulative(
                IntArray(32) { it }, LongArray(32) { 1 }, LongArray(32) { 1 }, 1,
                presents = if (optional) {
                    IntArray(32) { Lit.make(if (it < 30) 0 else 1, positive) }
                } else {
                    intArrayOf()
                },
                durationVars = IntArray(32) { 32 + it }, resourceVars = IntArray(32) { 64 + it },
            )
            val problem = Problem(2, 96, Array(96) { IntDomain(0, 1) }, arrayOf<Factor>(factor))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            repeat(32) { i ->
                state.assignment.setInt(i, 0L)
                state.assignment.setInt(32 + i, if (inactive == "duration" && i < 30) 0L else 1L)
                state.assignment.setInt(64 + i, if (inactive == "resource" && i < 30) 0L else 1L)
            }
            state.assignment.setBool(0, !positive)
            state.assignment.setBool(1, positive)
            state.recompute()
            val recorder = LocalSearchResidualRecorder()

            recorder.observe(state)

            val observed = recorder.best?.factors?.single()
            for ((coordinate, value) in mapOf(30 to 0L, 31 to 0L, 62 to 1L, 63 to 1L, 94 to 1L, 95 to 1L)) {
                assertEquals(value, observed?.ints?.get(coordinate), inactive)
            }
            assertEquals(16, observed?.ints?.size, inactive)
            assertEquals(80, observed?.omittedInts, inactive)
            assertEquals(1L, recorder.best?.cost, inactive)
        }
    }

    @Test
    fun `reified residual categories distinguish comparison and input shape`() {
        for (op in LinearOp.entries) {
            for (shape in listOf("binary", "unary", "multi")) {
                val vars = if (shape == "multi") intArrayOf(0, 1) else intArrayOf(0)
                val domains = Array(vars.size) { IntDomain(0L, if (shape == "binary") 1L else 3L) }
                val problem = Problem(
                    1, vars.size, domains,
                    arrayOf<Factor>(ReifiedLinear(0, IntArray(vars.size) { 1 }, vars, op, 1)),
                )
                val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
                for (v in vars) state.assignment.setInt(v, 0)
                state.assignment.setBool(0, op != LinearOp.LE && op != LinearOp.NE)
                state.recompute()
                val recorder = LocalSearchResidualRecorder()

                recorder.observe(state)

                assertEquals(1L, recorder.best?.cost)
                assertEquals(mapOf("ReifiedLinear.$op.$shape" to 1L), recorder.best?.byKind)
            }
        }
    }

    @Test
    fun `a residual snapshot keeps the degrees from its observed assignment`() {
        val problem = Problem(
            1, 1, arrayOf(IntDomain(0, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 10),
                Clause(intArrayOf(Lit.make(0, true))),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.recompute()
        val recorder = LocalSearchResidualRecorder()
        recorder.observe(state)
        val initial = recorder.best

        state.apply(Move.IntSet(0, 3))
        recorder.observe(state)
        val improved = recorder.best
        state.apply(Move.IntSet(0, 0))
        recorder.observe(state)

        assertEquals(11L, initial?.cost)
        assertEquals(mapOf("Linear" to 10L, "Clause" to 1L), initial?.byKind)
        assertEquals(
            LocalSearchResidualFactor(0, "Linear", 10L, emptyMap(), mapOf(0 to 0L)),
            initial?.factors?.first(),
        )
        assertEquals(8L, improved?.cost)
        assertEquals(mapOf("Linear" to 7L, "Clause" to 1L), recorder.best?.byKind)
        assertEquals(
            listOf(
                LocalSearchResidualFactor(0, "Linear", 7L, emptyMap(), mapOf(0 to 3L)),
                LocalSearchResidualFactor(1, "Clause", 1L, mapOf(0 to false), emptyMap()),
            ),
            recorder.best?.factors,
        )
    }

    @Test
    fun `representative factors keep the largest degree of each kind`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 3)),
            arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 10),
            ),
        )
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.recompute()
        val recorder = LocalSearchResidualRecorder()

        recorder.observe(state)

        assertEquals(mapOf("Linear" to 12L), recorder.best?.byKind)
        assertEquals(
            listOf(LocalSearchResidualFactor(1, "Linear", 10L, emptyMap(), mapOf(0 to 0L))),
            recorder.best?.factors,
        )
    }

    @Test
    fun `representative coordinates are bounded and report omissions`() {
        for (integers in listOf(false, true)) {
            val problem = Problem(
                17, 17, Array(17) { IntDomain(0, 3) },
                arrayOf<Factor>(
                    ReifiedLinear(16, IntArray(17) { 1 }, IntArray(17) { it }, LinearOp.GE, 1),
                    Clause(IntArray(17) { Lit.make(it, true) }),
                ),
            )
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            repeat(17) {
                state.assignment.setBool(it, integers && it == 16)
                state.assignment.setInt(it, 0)
            }
            state.recompute()
            val recorder = LocalSearchResidualRecorder()

            recorder.observe(state)

            val factor = recorder.best?.factors?.single()
            assertEquals(if (integers) mapOf(16 to true) else (0 until 16).associateWith { false }, factor?.bools)
            assertEquals(if (integers) (0 until 16).associateWith { 0L } else emptyMap(), factor?.ints)
            assertEquals(if (integers) 0 else 1, factor?.omittedBools)
            assertEquals(if (integers) 1 else 0, factor?.omittedInts)
        }
    }

    @Test
    fun `representative factor count is bounded without truncating kind totals`() {
        val factors = Array<Factor>(12) { fid ->
            val vars = when (fid % 3) {
                0 -> intArrayOf(0)
                1 -> intArrayOf(1)
                else -> intArrayOf(1, 2)
            }
            ReifiedLinear(fid, IntArray(vars.size) { 1 }, vars, LinearOp.entries[fid / 3], 1)
        }
        val problem = Problem(12, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 3), IntDomain(0, 3)), factors)
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        repeat(3) { state.assignment.setInt(it, 0) }
        repeat(12) {
            val op = LinearOp.entries[it / 3]
            state.assignment.setBool(it, op != LinearOp.LE && op != LinearOp.NE)
        }
        state.recompute()
        val recorder = LocalSearchResidualRecorder()

        recorder.observe(state)

        assertEquals(12L, recorder.best?.cost)
        assertEquals(12, recorder.best?.byKind?.size)
        assertEquals(8, recorder.best?.factors?.size)
        assertEquals(8, recorder.best?.factors?.map { it.kind }?.distinct()?.size)
    }
}
