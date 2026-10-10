package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpExactStateTest {
    @Test
    fun `trail storage follows retained witnesses through nested pop and row retirement`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = assertNotNull(LpBuilder().apply { addVar(0L, 9L) }.build(Sense.MINIMIZE).authoritativeModel())
        val trail = LpBoundTrail(source)
        val premises = ExactLpPremises(listOf(ExactLpPremise(0, false, one)), listOf(7, 9))
        assertTrue(trail.assertBound(0, false, ExactLpSide(one, premises = premises), 0L))
        assertEquals(10L, trail.state.trailStorageUnits)
        assertTrue(trail.push())
        assertTrue(trail.append(LpScopedRow(0L, listOf(0 to one), one,
            ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))), scoped = true))
        assertTrue(trail.assertBound(1, true, ExactLpSide(one), 1L))
        assertEquals(16L, trail.state.trailStorageUnits)
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(4L), premises = premises), 2L))
        assertEquals(27L, trail.state.trailStorageUnits)

        assertTrue(trail.pop(1))

        assertEquals(16L, trail.state.trailStorageUnits)
        assertFalse(trail.suspend(setOf(0L)))
        assertEquals(16L, trail.state.trailStorageUnits)
        assertTrue(trail.pop(0))
        assertEquals(10L, trail.state.trailStorageUnits)
        assertEquals(premises, trail.state.activeSide(0, false)?.side?.premises)
    }

    @Test
    fun `cached owner views respect cancellation and caller projection isolation`() {
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
    fun `incremental and fresh projections agree after bound and structural edits`() {
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
            { it.assertBound(0, true, ExactLpSide(ExactLpNumber.of(7L)), 0L) },
            { it.push() },
            { it.assertBound(0, false, ExactLpSide(one, strict = true), 1L) },
            { it.pop(1) },
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
    fun `objective revisions retain exact values and signed zero when rounding agrees`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(emptyList(), emptyList()), emptyList(),
            List(2) { ExactLpColumn(ExactLpBounds()) }, emptyList(), ExactLpObjective(listOf(one, zero)),
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
    fun `a declined objective projection preserves its predecessor and retry`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            List(4) { emptyList() }, emptyList(), List(4) { ExactLpColumn(ExactLpBounds()) }, emptyList(),
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
    fun `bound underflow status follows tightening and rollback on either side`() {
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
    fun `exact model copies isolate caller collections and preserve unchanged authority`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val entries = mutableListOf(ExactLpEntry(0, one))
        val matrix = mutableListOf<List<ExactLpEntry>>(entries)
        val rhs = mutableListOf(one)
        val columns = mutableListOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds()))
        val rows = mutableListOf(ExactLpRow())
        val costs = mutableListOf(zero, zero)
        val model = ExactLpModel(matrix, rhs, columns, rows, ExactLpObjective(costs))
        val copiedRhs = mutableListOf(one)
        val copiedColumns = columns.toMutableList()
        val copiedRows = rows.toMutableList()
        val copy = model.copy(rhs = copiedRhs, columns = copiedColumns, rows = copiedRows)

        entries.clear()
        matrix.clear()
        rhs[0] = zero
        columns.clear()
        rows.clear()
        costs.clear()
        copiedRhs[0] = zero
        copiedColumns.clear()
        copiedRows.clear()

        assertTrue(model.sameAuthority(copy))
        assertTrue(model.sameAuthority(model.copy()))
        assertEquals(listOf(ExactLpEntry(0, one)), model.entries(0))
        assertEquals(one, copy.rhs(0))
        assertEquals(2, copy.numVars)
        assertEquals(1, copy.m)
    }

    @Test
    fun `inactive strict rows remove logical strictness integrality and sides`() {
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
    fun `working projections preserve rational authority and source metadata`() {
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
    fun `matrix compatibility includes exact input identity and row premises`() {
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
    fun `an underflowed bound cannot authorize cost or scale underflow`() {
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

}
