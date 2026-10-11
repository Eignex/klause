package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ComparisonClause
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
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.result.LocalSearchResidualFactor
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class LocalSearchResidualRecorderTest {
    @Test
    fun `comparison residual details preserve ordered repeated literals with an omission count`() {
        val vars = IntArray(18) { it % 2 }
        val ops = Array(18) { if (it % 2 == 0) LinearOp.EQ else LinearOp.GE }
        val constants = LongArray(18) { 1L + it }
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 20) },
            arrayOf<Factor>(ComparisonClause(vars, ops, constants)))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.recompute()
        val recorder = LocalSearchResidualRecorder()

        recorder.observe(state)

        val observed = assertNotNull(recorder.best).factors.single()
        assertEquals(listOf(0, 1), observed.ints.keys.toList())
        assertEquals((0 until 16).map { "i${vars[it]}:${ops[it]}:${constants[it]}" },
            observed.details.filterKeys { it.startsWith("literal.") }.values.toList())
        assertEquals("2", observed.details["omittedLiterals"])
        assertEquals(0, observed.omittedInts)
    }

    @Test
    fun `integer row details retain signed terms and committed activity after a scoring probe`() {
        for (reified in listOf(false, true)) {
            val coefficients = IntArray(18) { if (it % 2 == 0) 3 else -2 }
            val vars = IntArray(18) { it }
            val factor = if (reified) ReifiedLinear(0, coefficients, vars, LinearOp.EQ, 14)
                else Linear(coefficients, vars, LinearOp.EQ, 14)
            val problem = Problem(1, 18, Array(18) { IntDomain(0, 2) }, arrayOf<Factor>(factor))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            repeat(18) { state.assignment.setInt(it, 1) }
            state.assignment.setBool(0, true)
            state.recompute()
            val recorder = LocalSearchResidualRecorder()
            recorder.observe(state)
            val initial = assertNotNull(recorder.best)

            assertEquals(-3L, state.netDelta(Move.IntSet(0, 2)))
            recorder.observe(state)

            assertSame(initial, recorder.best)
            val observed = initial.factors.single()
            assertEquals("9", observed.details["activity"])
            assertEquals("none", observed.details["i0.definition.kind"])
            assertEquals("EQ", observed.details["op"])
            assertEquals("14", observed.details["bound"])
            assertEquals((0 until 16).map { "${coefficients[it]}:i$it" },
                observed.details.filterKeys { it.startsWith("term.") }.values.toList())
            assertEquals("2", observed.details["omittedTerms"])
            assertEquals(1L, state.assignment.intValue(0))
            assertEquals(9L, state.longPayload[0])

            state.apply(Move.IntSet(0, 2))
            recorder.observe(state)

            assertEquals("12", recorder.best?.factors?.single()?.details?.get("activity"))
            val committedCost = state.cost
            state.recompute()
            assertEquals(committedCost, state.cost)
            assertEquals(12L, state.longPayload[0])
            assertEquals("9", observed.details["activity"])
        }
    }

    @Test
    fun `linear residual terms retain canonical inequality signs and coalesced order`() {
        val factor = Linear(intArrayOf(2, -3, 4), intArrayOf(1, 0, 1), LinearOp.GE, 11)
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(factor))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.recompute()
        val recorder = LocalSearchResidualRecorder()

        recorder.observe(state)

        val details = assertNotNull(recorder.best).factors.single().details
        assertEquals("LE", details["op"])
        assertEquals("-11", details["bound"])
        assertEquals(listOf("-6:i1", "3:i0"),
            details.filterKeys { it.startsWith("term.") }.values.toList())
        assertEquals("0", details["omittedTerms"])
        assertEquals("-3", details["activity"])
    }

    @Test
    fun `real row residual details identify unsupported constants and activity`() {
        val factor = Linear(intArrayOf(0), doubleArrayOf(0.5), intArrayOf(0),
            doubleArrayOf(1.5), LinearOp.EQ, 10.5)
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(factor),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(2.0))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.assignment.setInt(0, 1)
        state.assignment.setReal(0, 1.0)
        state.recompute()
        val recorder = LocalSearchResidualRecorder()

        recorder.observe(state)

        val details = assertNotNull(recorder.best).factors.single().details
        assertEquals("unsupported-constants", details["row"])
        assertEquals("unsupported-grader", details["activity"])
        assertEquals(null, details["bound"])
        assertEquals(null, details["term.0"])
    }

    @Test
    fun `residual definition references are bounded for pinned integer and Boolean outputs`() {
        val problem = Problem(18, 18, Array(18) { IntDomain(0, 20) }, arrayOf<Factor>(
            ComparisonClause(intArrayOf(0), arrayOf(LinearOp.EQ), longArrayOf(0)),
            Clause(intArrayOf(Lit.make(0, true))),
        ))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3),
            Assumptions.None.withInt(0, 17).withBool(0, false))
        val inputs = IntArray(17) { it + 1 }
        state.invariants = InvariantNetwork(listOf(
            DefinitionalSweep.SweepNode.IntDef(FunctionalObjective.Lin(
                0, 1, LongArray(17) { -1 }, Array(17) { FunctionalObjective.Operand.v(it + 1) }, 0,
            ), inputs),
            DefinitionalSweep.SweepNode.BoolFold(0, IntArray(17) { Lit.make(it + 1, true) }, true),
        ), 18, 18)
        repeat(18) {
            state.assignment.setInt(it, if (it == 0) 17 else 1)
            state.assignment.setBool(it, false)
        }
        state.recompute()
        val recorder = LocalSearchResidualRecorder()
        recorder.observe(state)
        val observed = assertNotNull(recorder.best)

        state.netDelta(Move.Compound(listOf(Move.IntSet(1, 2), Move.BoolFlip(1))))
        recorder.observe(state)

        assertSame(observed, recorder.best)
        val integerDetails = observed.factors.first { it.kind == "ComparisonClause" }.details
        val boolDetails = observed.factors.first { it.kind == "Clause" }.details
        assertEquals("IntDef.Lin", integerDetails["i0.definition.kind"])
        assertEquals((1..16).joinToString(",") { "i$it" }, integerDetails["i0.definition.inputs"])
        assertEquals("1", integerDetails["i0.definition.omittedInputs"])
        assertEquals("BoolFold", boolDetails["b0.definition.kind"])
        assertEquals((1..16).joinToString(",") { "b$it" }, boolDetails["b0.definition.inputs"])
        assertEquals("1", boolDetails["b0.definition.omittedInputs"])
        assertEquals(17L, state.assignment.intValue(0))
        assertEquals(1L, state.assignment.intValue(1))
        assertEquals(false, state.assignment.boolValue(0))
        assertEquals(false, state.assignment.boolValue(1))
        assertEquals(observed.cost, state.cost)
        state.recompute()
        assertEquals(observed.cost, state.cost)
    }

    @Test
    fun `captured definition references survive a replacement network and committed input change`() {
        val problem = Problem(1, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(
            ComparisonClause(intArrayOf(0, 1), arrayOf(LinearOp.EQ, LinearOp.EQ), longArrayOf(2, 2)),
        ))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
        state.invariants = InvariantNetwork(listOf(DefinitionalSweep.SweepNode.Bool2Int(0, 0)), 2, 1)
        state.moveSink.setOwners(intArrayOf(-1, 0))
        state.assignment.setBool(0, false)
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 0)
        state.recompute()
        val recorder = LocalSearchResidualRecorder()
        recorder.observe(state)
        val captured = assertNotNull(recorder.best).factors.single()

        state.apply(Move.BoolFlip(0))
        state.invariants = null
        recorder.observe(state)

        assertEquals("Bool2Int", captured.details["i0.definition.kind"])
        assertEquals("b0", captured.details["i0.definition.inputs"])
        assertEquals("0", captured.details["i0.definition.omittedInputs"])
        assertEquals("none", captured.details["i1.definition.kind"])
        assertEquals(null, captured.details["i1.definition.inputs"])
        assertEquals(null, captured.details["b0.definition.kind"])
        assertEquals(mapOf(0 to 0L, 1 to 0L), captured.ints)
        assertEquals("none", recorder.best?.factors?.single()?.details?.get("i0.definition.kind"))
    }

    @Test
    fun `exact row residual details identify unsupported activity instead of a payload placeholder`() {
        for ((wideConstants, reified) in listOf(false to false, true to false, false to true, true to true)) {
            val factor = if (wideConstants) {
                val wide = bigIntOf(Long.MAX_VALUE) + bigIntOf(1)
                if (reified) ReifiedLinear(0, intArrayOf(0), arrayOf(wide), LinearOp.EQ, bigIntOf(0))
                else Linear(intArrayOf(0), arrayOf(wide), LinearOp.EQ, bigIntOf(0))
            } else {
                if (reified) ReifiedLinear(0, longArrayOf(Long.MAX_VALUE), intArrayOf(0), LinearOp.EQ, 0L)
                else Linear(longArrayOf(Long.MAX_VALUE), intArrayOf(0), LinearOp.EQ, 0L)
            }
            val problem = Problem(1, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(factor))
            val state = LocalSearchState(LocalSearchModel.open(problem), Random(3))
            state.assignment.setInt(0, 1)
            state.assignment.setBool(0, true)
            state.recompute()
            val recorder = LocalSearchResidualRecorder()

            recorder.observe(state)

            val details = assertNotNull(recorder.best).factors.single().details
            assertEquals("EQ", details["op"])
            assertEquals("unsupported-grader", details["activity"])
            assertEquals(if (wideConstants) "unsupported-constants" else null, details["row"])
            assertEquals(if (wideConstants) null else "${Long.MAX_VALUE}:i0", details["term.0"])
            assertEquals(if (wideConstants) null else "0", details["bound"])
        }
    }

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
            val cells = LongArray(32) { 2L + (it - 2).mod(20) }
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
            initial?.factors?.first()?.copy(details = emptyMap()),
        )
        assertEquals(8L, improved?.cost)
        assertEquals(mapOf("Linear" to 7L, "Clause" to 1L), recorder.best?.byKind)
        assertEquals(
            listOf(
                LocalSearchResidualFactor(0, "Linear", 7L, emptyMap(), mapOf(0 to 3L)),
                LocalSearchResidualFactor(1, "Clause", 1L, mapOf(0 to false), emptyMap()),
            ),
            recorder.best?.factors?.map { it.copy(details = emptyMap()) },
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
            recorder.best?.factors?.map { it.copy(details = emptyMap()) },
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
