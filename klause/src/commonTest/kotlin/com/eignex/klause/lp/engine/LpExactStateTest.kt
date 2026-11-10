package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpExactStateTest {
    @Test
    fun `single appended bounds match a fresh exact replay`() {
        val source = assertNotNull(LpBuilder().apply { repeat(2) { addVar(0L, 10L) } }
            .build(Sense.MINIMIZE).authoritativeModel())
        val root = LpExactState(source)
        val existing = LpBoundAssertion(1, false, ExactLpSide(ExactLpNumber.of(4L)), 1L, 0)
        val parent = LpExactState(source, listOf(existing), previous = root)
        val cases = listOf(
            Triple(0, false, ExactLpSide(ExactLpNumber.of(2L))),
            Triple(0, true, ExactLpSide(ExactLpNumber.of(8L))),
            Triple(0, false, ExactLpSide(ExactLpNumber.of(-1L))),
            Triple(0, true, ExactLpSide(ExactLpNumber.of(11L))),
            Triple(0, false, ExactLpSide(ExactLpNumber.of(0L), strict = true)),
            Triple(0, true, ExactLpSide(ExactLpNumber.of(10L), strict = true)),
            Triple(1, false, ExactLpSide(ExactLpNumber.of(5L))),
            Triple(1, true, ExactLpSide(ExactLpNumber.of(3L))),
        )
        for ((column, upper, side) in cases) {
            val assertions = parent.assertions + LpBoundAssertion(column, upper, side, 2L, 0)

            val incremental = LpExactState(source, assertions, previous = parent)
            val fresh = LpExactState(source, assertions)

            for (j in 0 until source.numVars) {
                assertEquals(fresh.model.column(j), incremental.model.column(j))
                assertEquals(fresh.activeSide(j, false), incremental.activeSide(j, false))
                assertEquals(fresh.activeSide(j, true), incremental.activeSide(j, true))
            }
            assertEquals(fresh.conflict, incremental.conflict)
        }
    }

    @Test
    fun `probe descendants preserve parent and sibling bounds`() {
        for (upper in listOf(false, true)) {
            val source = assertNotNull(LpBuilder().apply {
                addVar(3L, 9L)
                addVar(0L, 10L)
            }.build(Sense.MINIMIZE).authoritativeModel())
            val parent = LpExactState(source)
            val parentView = assertNotNull(parent.ownerWorkingModel())
            val expectedParent = List(2) { parent.model.column(it) }
            val parentSides = List(2) { listOf(parent.activeSide(it, false), parent.activeSide(it, true)) }
            val child = assertNotNull(parent.probeBound(0, upper, 6L, LpProjectionMeter()))
            val childView = assertNotNull(child.ownerWorkingModel())
            val expectedChild = List(2) { child.model.column(it) }
            val childSides = List(2) { listOf(child.activeSide(it, false), child.activeSide(it, true)) }
            val sibling = assertNotNull(parent.probeBound(0, !upper, 6L, LpProjectionMeter()))
            val siblingView = assertNotNull(sibling.ownerWorkingModel())
            val expectedSibling = List(2) { sibling.model.column(it) }
            val siblingSides = List(2) { listOf(sibling.activeSide(it, false), sibling.activeSide(it, true)) }

            val descendant = assertNotNull(child.probeBound(1, !upper, 5L, LpProjectionMeter()))
            assertNotNull(descendant.ownerWorkingModel())

            assertEquals(expectedParent, List(2) { parent.model.column(it) })
            assertEquals(expectedChild, List(2) { child.model.column(it) })
            assertEquals(expectedSibling, List(2) { sibling.model.column(it) })
            for (j in 0..1) {
                assertEquals(parentSides[j], listOf(parent.activeSide(j, false), parent.activeSide(j, true)))
                assertEquals(childSides[j], listOf(child.activeSide(j, false), child.activeSide(j, true)))
                assertEquals(siblingSides[j], listOf(sibling.activeSide(j, false), sibling.activeSide(j, true)))
            }
            assertEquals(0.0, parentView.lowerD(0))
            assertEquals(6.0, parentView.upperD(0))
            assertEquals(if (upper) 0.0 else 3.0, childView.lowerD(0))
            assertEquals(if (upper) 3.0 else 6.0, childView.upperD(0))
            assertEquals(if (upper) 3.0 else 0.0, siblingView.lowerD(0))
            assertEquals(if (upper) 6.0 else 3.0, siblingView.upperD(0))
        }
    }

    @Test
    fun `appended conflicts retain the first column and its exact witnesses`() {
        for (order in listOf(listOf(0, 1), listOf(1, 0))) {
            val source = assertNotNull(LpBuilder().apply { repeat(2) { addVar(0L, 10L) } }
                .build(Sense.MINIMIZE).authoritativeModel())
            var state = LpExactState(source)
            for (column in order) {
                val assertion = LpBoundAssertion(column, false,
                    ExactLpSide(ExactLpNumber.of(11L)), column.toLong(), 0)
                state = LpExactState(source, state.assertions + assertion, previous = state)
            }
            val upper = LpBoundAssertion(0, true, ExactLpSide(ExactLpNumber.of(9L)), 2L, 0)

            val tightened = LpExactState(source, state.assertions + upper, previous = state)

            val conflict = assertNotNull(tightened.conflict)
            assertEquals(0, conflict.column)
            assertEquals(0L, conflict.lower.witness)
            assertEquals(2L, conflict.upper.witness)
            assertEquals(ExactLpNumber.of(11L), conflict.lower.side.number)
            assertEquals(ExactLpNumber.of(9L), conflict.upper.side.number)
        }
    }

    @Test
    fun `cached owner views should respect cancellation and caller projection isolation`() {
        val source = assertNotNull(LpBuilder().apply { addVar(3L, 9L) }.build(Sense.MINIMIZE).authoritativeModel())
        val state = LpExactState(source)
        val owner = assertNotNull(state.ownerWorkingModel())
        val caller = assertNotNull(state.toWorkingModel())
        assertNotNull(caller.doubleView).upper[0] = -1.0
        val meter = LpProjectionMeter(workLimit = 1L, allocationLimit = 0L)

        val repeated = assertNotNull(state.ownerWorkingModel(meter))

        assertSame(owner, repeated)
        assertEquals(6.0, repeated.upperD(0))
        assertEquals(1L, meter.work)
        assertEquals(0L, meter.allocation)
        assertNull(state.ownerWorkingModel(LpProjectionMeter(cancellation = Cancellation { true })))
        assertSame(owner, assertNotNull(state.ownerWorkingModel()))
    }

    @Test
    fun `incremental and fresh projections should agree after bound and structural edits`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val third = ExactLpNumber.of(BigFraction.of(BIG_ONE, bigIntOf(3)))
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, third))),
            listOf(one),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L)))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
            ),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(third, zero)),
        )
        val trail = LpBoundTrail(source)
        assertNotNull(trail.state.ownerWorkingModel())
        val edits = listOf<(LpBoundTrail) -> Boolean>(
            { it.push() },
            { it.assertBound(0, false, ExactLpSide(one, strict = true), 1L) },
            { it.pop(0) },
            { it.recenter(listOf(third)) },
            { it.replaceObjective(ExactLpObjective(listOf(one, zero), constant = third)) },
            { it.push() },
            { it.append(LpScopedRow(10L, listOf(0 to one), one, ExactLpColumn(ExactLpBounds())), scoped = true) },
            { it.assertBound(2, true, ExactLpSide(third), 2L) },
            { it.pop(0) },
            { it.compact() },
        )
        for (edit in edits) {
            assertTrue(edit(trail))
            val state = trail.state

            val incremental = assertNotNull(state.ownerWorkingModel())
            val fresh = assertNotNull(
                LpExactState(state.baseModel, state.assertions, state.scopes, rows = state.rows).toWorkingModel(),
            )

            assertEquals(fresh.n, incremental.n)
            assertEquals(fresh.m, incremental.m)
            for (i in 0 until fresh.m) {
                assertEquals(fresh.rhsD(i).toRawBits(), incremental.rhsD(i).toRawBits())
                assertEquals(fresh.rowStrict[i], incremental.rowStrict[i])
                assertEquals(fresh.rowGlobal[i], incremental.rowGlobal[i])
            }
            for (j in 0 until fresh.numVars) {
                assertEquals(fresh.costD(j).toRawBits(), incremental.costD(j).toRawBits())
                assertEquals(fresh.lowerD(j).toRawBits(), incremental.lowerD(j).toRawBits())
                assertEquals(fresh.upperD(j).toRawBits(), incremental.upperD(j).toRawBits())
                assertEquals(fresh.hasFiniteUpper(j), incremental.hasFiniteUpper(j))
            }
            assertEquals(fresh.objConstantD.toRawBits(), incremental.objConstantD.toRawBits())
            assertEquals(fresh.objectiveD(3.0).toRawBits(), incremental.objectiveD(3.0).toRawBits())
            assertSame(state, incremental.exactState)
        }
    }

    @Test
    fun `objective revisions should retain exact values and signed zero when rounding agrees`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList(), emptyList()),
            emptyList(),
            List(2) { ExactLpColumn(ExactLpBounds()) },
            emptyList(),
            ExactLpObjective(listOf(one, zero)),
        )
        val trail = LpBoundTrail(source)
        val original = assertNotNull(trail.state.ownerWorkingModel())
        val denominator = BIG_ONE shl 100
        val roundedOne = ExactLpNumber.of(BigFraction.of(denominator + BIG_ONE, denominator))
        val negativeZero = ExactLpNumber.ofIeee(-0.0)
        assertTrue(trail.replaceObjective(ExactLpObjective(listOf(roundedOne, negativeZero))))

        val changed = assertNotNull(trail.state.ownerWorkingModel())

        assertEquals(original.costD(0).toRawBits(), changed.costD(0).toRawBits())
        assertEquals((-0.0).toRawBits(), changed.costD(1).toRawBits())
        assertEquals(roundedOne, assertNotNull(changed.exactState).model.objective.cost(0))
        assertEquals(one, assertNotNull(original.exactState).model.objective.cost(0))
        assertFalse(assertNotNull(original.exactState).fullAuthorityEquals(assertNotNull(changed.exactState)))
    }

    @Test
    fun `a declined objective projection should preserve its predecessor and allow retry`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            List(4) { emptyList() },
            emptyList(),
            List(4) { ExactLpColumn(ExactLpBounds()) },
            emptyList(),
            ExactLpObjective(List(4) { zero }),
        )
        val trail = LpBoundTrail(source)
        val original = assertNotNull(trail.state.ownerWorkingModel())
        assertTrue(trail.replaceObjective(ExactLpObjective(List(4) { one })))
        var polls = 0

        assertNull(trail.state.ownerWorkingModel(LpProjectionMeter(allocationLimit = 0L)))
        assertNull(trail.state.ownerWorkingModel(LpProjectionMeter(cancellation = Cancellation { ++polls >= 8 })))

        assertEquals(0.0, original.costD(0))
        val retry = assertNotNull(trail.state.ownerWorkingModel())
        assertEquals(1.0, retry.costD(0))
        assertSame(trail.state, retry.exactState)
    }

    @Test
    fun `bound underflow status should follow tightening and rollback on either side`() {
        val zero = ExactLpNumber.of(0L)
        val tiny = ExactLpNumber.of(BigFraction.of(BIG_ONE, BIG_ONE shl 2048))
        for (upper in listOf(false, true)) {
            val source = ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L))))),
                emptyList(),
                ExactLpObjective(listOf(zero)),
            )
            val trail = LpBoundTrail(source)
            val root = assertNotNull(trail.state.toWorkingModel())
            assertTrue(trail.push())
            assertTrue(trail.assertBound(0, upper, ExactLpSide(tiny), 0L))
            val underflowed = assertNotNull(trail.state.toWorkingModel())
            assertTrue(trail.assertBound(0, upper, ExactLpSide(if (upper) zero else ExactLpNumber.of(1L)), 1L))
            val recovered = assertNotNull(trail.state.toWorkingModel())

            assertEquals(false, trail.state.projectionLostNonzero(recovered))
            assertEquals(true, underflowed.exactState?.projectionLostNonzero(underflowed))
            assertTrue(trail.pop(0))
            val restored = assertNotNull(trail.state.toWorkingModel())
            assertEquals(false, trail.state.projectionLostNonzero(restored))
            assertEquals(false, root.exactState?.projectionLostNonzero(root))
        }
    }

    @Test
    fun `inactive strict rows should remove logical strictness integrality and sides`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            listOf(zero),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            listOf(ExactLpRow(strict = true)),
            ExactLpObjective(listOf(zero, zero)),
        )
        val active = LpExactState(model)
        val inactive = LpExactState(model, rows = LpScopedRows.initial(1).deactivate(setOf(0)))

        assertTrue(assertNotNull(active.model.column(1).bounds.lower).strict)
        assertTrue(active.model.column(1).integral)
        assertFalse(inactive.model.row(0).strict)
        assertFalse(inactive.model.column(1).integral)
        assertNull(inactive.model.column(1).bounds.lower)
        assertTrue(model.row(0).strict)
        assertFalse(assertNotNull(model.column(1).bounds.lower).strict)
    }

    @Test
    fun `working projections should preserve rational authority and source metadata`() {
        val zero = ExactLpNumber.of(0L)
        val third = ExactLpNumber.of(BigFraction.of(BIG_ONE, bigIntOf(3)))
        val premises = ExactLpPremises(listOf(ExactLpPremise(8, true, third)))
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, third))),
            listOf(third),
            listOf(
                ExactLpColumn(ExactLpBounds(upper = ExactLpSide(third, strict = true)), third, false, 42),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow(false, true, premises)),
            ExactLpObjective(listOf(third, zero), third, third, third, Sense.MAXIMIZE),
        )
        val state = LpExactState(model)

        val working = assertNotNull(state.toWorkingModel())

        assertSame(state, working.exactState)
        assertTrue(model.sameAuthority(assertNotNull(working.exactState).baseModel))
        assertTrue(assertNotNull(state.model.column(1).bounds.lower).strict)
        assertNotNull(state.conflict)
        assertEquals(1.0 / 3.0, working.costD(0))
        assertEquals(1.0 / 3.0, working.rhsD(0))
        assertEquals(1.0 / 3.0, working.loShiftD(0))
        assertEquals(0L, working.cost[0])
        assertEquals(0L, working.rhs[0])
        assertEquals(0L, working.csc.colVal[0])
        assertEquals(42, working.tag[0])
        assertTrue(working.colContinuous[0])
        assertFalse(working.rowGlobal[0])
        assertTrue(working.rowStrict[0])
        assertEquals(Sense.MAXIMIZE, working.sense)
    }

    @Test
    fun `matrix compatibility should include exact input identity and row premises`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds())),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        val state = LpExactState(model)
        val ieee = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.ofIeee(1.0)))),
            listOf(one),
            List(2) { model.column(it) },
            listOf(model.row(0)),
            model.objective,
        )

        assertFalse(state.sameMatrix(LpExactState(ieee)))
        assertFalse(state.sameMatrix(LpExactState(model.copy(rows = listOf(ExactLpRow(global = false))))))
        assertTrue(state.sameMatrix(LpExactState(model.recentered(listOf(one)))))
        assertTrue(state.sameMatrix(LpExactState(model.copy(objective = ExactLpObjective(listOf(one, one))))))
    }

    @Test
    fun `an underflowed bound should not authorize cost or scale underflow`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val tiny = ExactLpNumber.of(BigFraction.of(BIG_ONE, BIG_ONE shl 2048))
        val boundModel = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(ExactLpColumn(ExactLpBounds(upper = ExactLpSide(tiny))), ExactLpColumn(ExactLpBounds())),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(zero, zero)),
        )
        assertEquals(0.0, assertNotNull(LpExactState(boundModel).toWorkingModel()).upperD(0))
        for (role in listOf("matrix", "cost", "scale")) {
            val model = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, if (role == "matrix") tiny else one))),
                listOf(one),
                List(2) { ExactLpColumn(ExactLpBounds()) },
                listOf(ExactLpRow()),
                ExactLpObjective(
                    listOf(if (role == "cost") tiny else zero, zero),
                    scale = if (role == "scale") tiny else one,
                ),
            )

            val state = LpExactState(model)
            val working = state.toWorkingModel()
            if (role == "matrix") {
                assertNotNull(working)
                assertSame(state, working.exactState)
                assertEquals(tiny, state.model.entries(0).single().number)
                assertEquals(0.0, assertNotNull(working.doubleView).colVal.single())
                assertEquals(LpMatrixProjectionStatus(1, 0), state.matrixProjectionStatus)
            } else {
                assertNull(working)
            }
        }
    }

    @Test
    fun `matrix underflow should preserve structural support and exact authority`() {
        val tiny = BigFraction.of(BIG_ONE, parseBigInt("1" + "0".repeat(400)))
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(tiny)))),
            listOf(zero),
            List(2) { ExactLpColumn(ExactLpBounds()) },
            listOf(ExactLpRow()),
            ExactLpObjective(List(2) { zero }),
        )
        val state = LpExactState(source)

        val working = assertNotNull(state.toWorkingModel())

        assertEquals(1, working.csc.rowIdx.size)
        assertEquals(tiny, state.model.entries(0).single().number.value)
        assertEquals(LpMatrixProjectionStatus(1, 0), state.matrixProjectionStatus)
    }

    @Test
    fun `budget and cancellation declines should permit a later projection`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val state = LpExactState(source)

        assertNull(state.toWorkingModel(LpProjectionMeter(workLimit = 0L)))
        assertNull(state.toWorkingModel(LpProjectionMeter(cancellation = Cancellation { true })))

        assertFalse(state.matrixProjectionDeclined)
        assertNotNull(state.toWorkingModel())
    }
}
