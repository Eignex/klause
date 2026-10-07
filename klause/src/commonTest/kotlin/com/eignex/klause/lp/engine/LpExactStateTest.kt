package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpExactStateTest {
    @Test
    fun `editing a caller projection cannot change later projections of its source`() {
        val one = ExactLpNumber.of(1L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one))),
            listOf(one),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(one), ExactLpSide(one)), origin = one, tag = 7),
                ExactLpColumn(ExactLpBounds()),
            ),
            listOf(ExactLpRow(global = true)),
            ExactLpObjective(listOf(one, one), constant = one),
        )
        val state = LpExactState(source)
        val caller = assertNotNull(state.toWorkingModel())
        val input = assertNotNull(caller.doubleView)
        input.rhs[0] = 0.0
        input.cost[0] = 0.0
        input.upper[0] = 0.0
        input.hasUpper[0] = false
        input.loShift[0] = 0.0
        input.objConstant = 0.0
        caller.tag[0] = 9
        caller.rowGlobal[0] = false
        caller.probeClampedLo[0] = true
        caller.probeClampedHi[0] = true
        caller.rowPremises[0] = LpRowPremises(intArrayOf(7), booleanArrayOf(true), longArrayOf(1L))

        val projected = assertNotNull(state.toWorkingModel())

        assertEquals(1.0, projected.rhsD(0))
        assertEquals(1.0, projected.costD(0))
        assertEquals(1.0, projected.upperD(0))
        assertTrue(assertNotNull(projected.doubleView).hasUpper[0])
        assertEquals(1.0, projected.loShiftD(0))
        assertEquals(1.0, projected.objConstantD)
        assertEquals(7, projected.tag[0])
        assertTrue(projected.rowGlobal[0])
        assertFalse(projected.probeClampedLo[0])
        assertFalse(projected.probeClampedHi[0])
        assertNull(projected.rowPremises[0])
        assertSame(state, projected.exactState)
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
            assertSame(state, incremental.exactState)
        }
    }

    @Test
    fun `a bound edit projects within a budget too small for unchanged vectors`() {
        val zero = ExactLpNumber.of(0L)
        val ten = ExactLpNumber.of(10L)
        val source = ExactLpModel(
            List(128) { emptyList() },
            emptyList(),
            List(128) { ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ten))) },
            emptyList(),
            ExactLpObjective(List(128) { zero }),
        )
        val trail = LpBoundTrail(source)
        val original = assertNotNull(trail.state.toWorkingModel())
        assertTrue(trail.assertBound(7, true, ExactLpSide(ExactLpNumber.of(3L)), 0L))
        val budget = LpProjectionMeter(workLimit = 140L, allocationLimit = 1800L)

        val changed = assertNotNull(trail.state.ownerWorkingModel(budget))

        assertEquals(3.0, changed.upperD(7))
        assertEquals(10.0, original.upperD(7))
        assertEquals(10.0, changed.upperD(8))
        assertSame(trail.state, changed.exactState)
        assertEquals(0L, budget.matrixWork)
    }

    @Test
    fun `repeated projection keeps its exact owner without projecting vectors again`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            List(128) { emptyList() },
            emptyList(),
            List(128) { ExactLpColumn(ExactLpBounds()) },
            emptyList(),
            ExactLpObjective(List(128) { zero }),
        )
        val trail = LpBoundTrail(source)
        assertNotNull(trail.state.toWorkingModel())
        assertTrue(trail.push())

        val repeated = assertNotNull(trail.state.ownerWorkingModel(LpProjectionMeter(workLimit = 1L)))

        assertSame(trail.state, repeated.exactState)
        assertEquals(1, repeated.exactState?.depth)
    }

    @Test
    fun `a declined incremental projection leaves its predecessor and retry intact`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L))))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(source)
        val original = assertNotNull(trail.state.toWorkingModel())
        assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(3L)), 0L))

        assertNull(trail.state.toWorkingModel(LpProjectionMeter(allocationLimit = 0L)))

        assertEquals(10.0, original.upperD(0))
        val retry = assertNotNull(trail.state.toWorkingModel())
        assertEquals(3.0, retry.upperD(0))
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
    fun `nested bound projections restore absent upper bounds without changing sibling snapshots`() {
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(source)
        val root = assertNotNull(trail.state.toWorkingModel())
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(3L)), 0L))
        val parent = assertNotNull(trail.state.toWorkingModel())
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(1L)), 1L))
        val child = assertNotNull(trail.state.toWorkingModel())
        assertTrue(trail.pop(1))
        val restored = assertNotNull(trail.state.toWorkingModel())
        assertTrue(trail.pop(0))
        val open = assertNotNull(trail.state.toWorkingModel())

        assertFalse(root.hasFiniteUpper(0))
        assertFalse(open.hasFiniteUpper(0))
        assertEquals(3.0, parent.upperD(0))
        assertEquals(1.0, child.upperD(0))
        assertEquals(3.0, restored.upperD(0))
        assertFalse(assertNotNull(open.doubleView).hasUpper[0])
    }

    @Test
    fun `equal and weaker assertions retain declared source while strict assertion selects its premise`() {
        val zero = ExactLpNumber.of(0L)
        val ieeeZero = ExactLpNumber.ofIeee(-0.0)
        val declaredPremise = ExactLpPremises(listOf(ExactLpPremise(9, false, zero)))
        val assertedPremise = ExactLpPremises(listOf(ExactLpPremise(10, true, zero)), listOf(3))
        val model = ExactLpModel(
            listOf(emptyList(), emptyList()),
            emptyList(),
            listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(ieeeZero, premises = declaredPremise))),
                ExactLpColumn(ExactLpBounds()),
            ),
            emptyList(),
            ExactLpObjective(listOf(zero, zero)),
        )
        val trail = LpBoundTrail(model)
        val root = trail.state
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(-1L), premises = assertedPremise), 1L))
        assertTrue(trail.assertBound(0, false, ExactLpSide(zero, premises = assertedPremise), 2L))
        assertTrue(trail.assertBound(1, false, ExactLpSide(ExactLpNumber.of(1L)), 3L))

        assertSame(model.column(0), trail.state.model.column(0))
        assertEquals(-1L, trail.state.activeSide(0, false)?.witness)
        assertEquals(declaredPremise, trail.state.model.column(0).bounds.lower?.premises)
        assertEquals((-0.0).toRawBits(), trail.state.model.column(0).bounds.lower?.number?.ieeeBits)
        assertEquals(listOf(1L, 2L, 3L), trail.state.assertions.map { it.witness })

        assertTrue(trail.assertBound(0, false, ExactLpSide(ieeeZero, strict = true, premises = assertedPremise), 4L))
        assertNotSame(model.column(0), trail.state.model.column(0))
        assertEquals(4L, trail.state.activeSide(0, false)?.witness)
        assertEquals(assertedPremise, trail.state.model.column(0).bounds.lower?.premises)
        assertTrue(trail.pop(0))
        assertSame(model.column(0), trail.state.model.column(0))
        assertTrue(root.model.sameAuthority(trail.state.model))
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
    fun `matrix underflow preserves source authority while cost underflow declines`() {
        val zero = ExactLpNumber.of(0L)
        val tiny = ExactLpNumber.of(BigFraction.of(BIG_ONE, BIG_ONE shl 2048))
        for (matrixUnderflow in listOf(false, true)) {
            val model = ExactLpModel(
                listOf(listOf(ExactLpEntry(0, if (matrixUnderflow) tiny else zero))),
                listOf(zero),
                listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds())),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(if (matrixUnderflow) zero else tiny, zero)),
            )

            val state = LpExactState(model)
            val working = state.toWorkingModel()
            if (matrixUnderflow) {
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
    fun `IEEE subnormal coefficients project from input bits`() {
        val zero = ExactLpNumber.ofIeee(-0.0)
        val tiny = ExactLpNumber.ofIeee(Double.MIN_VALUE)
        val model = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, tiny))),
            listOf(tiny),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero))), ExactLpColumn(ExactLpBounds())),
            listOf(ExactLpRow()),
            ExactLpObjective(listOf(tiny, zero)),
        )

        val working = assertNotNull(LpExactState(model).toWorkingModel())

        assertEquals(Double.MIN_VALUE, working.costD(0))
        assertEquals(Double.MIN_VALUE, working.rhsD(0))
        assertEquals(Double.MIN_VALUE, assertNotNull(working.doubleView).colVal.single())
        assertEquals((-0.0).toRawBits(), working.costD(1).toRawBits())
        assertEquals((-0.0).toRawBits(), model.column(0).bounds.lower?.number?.ieeeBits)
    }

    @Test
    fun `snapshots own assertion and scope collections`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds())),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val assertions = mutableListOf(LpBoundAssertion(0, false, ExactLpSide(zero), 7L, 1))
        val scopes = mutableListOf(0)
        val state = LpExactState(model, assertions, scopes, boundRevision = 1L)

        assertions.clear()
        scopes.clear()

        assertEquals(7L, state.assertions.single().witness)
        assertEquals(listOf(0), state.scopes)
        assertEquals(1, state.depth)
        assertEquals(zero, state.model.column(0).bounds.lower?.number)
    }

    @Test
    fun `empty scopes and weaker witnesses distinguish otherwise equal boxes`() {
        val zero = ExactLpNumber.of(0L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))),
            emptyList(),
            ExactLpObjective(listOf(zero)),
        )
        val trail = LpBoundTrail(model)
        val root = trail.state
        assertTrue(trail.push())
        val pushed = trail.state
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(-1L)), 1L))

        assertFalse(root.fullAuthorityEquals(pushed))
        assertFalse(pushed.fullAuthorityEquals(trail.state))
        assertTrue(root.model.sameAuthority(trail.state.model))
        assertTrue(root.sameMatrix(trail.state))
        assertTrue(trail.pop(0))
        assertFalse(root.fullAuthorityEquals(trail.state))
        assertTrue(root.model.sameAuthority(trail.state.model))
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

    @Test
    fun `repeated scalar projection preserves raw values and exact identity`() {
        val zero = ExactLpNumber.of(0L)
        val huge = BIG_ONE shl 2048
        val values = listOf(
            ExactLpNumber.of(Long.MIN_VALUE),
            ExactLpNumber.of(Long.MAX_VALUE),
            ExactLpNumber.of(BigFraction.of(BIG_ONE, bigIntOf(3))),
            ExactLpNumber.ofIeee(-0.0),
            ExactLpNumber.ofIeee(Double.MIN_VALUE),
            ExactLpNumber.of(BigFraction.of(-BIG_ONE, huge)),
            ExactLpNumber.of(BigFraction.of(huge, BIG_ONE)),
            ExactLpNumber.of(BigFraction.of(huge + BIG_ONE, huge - BIG_ONE)),
        )
        for (number in values) {
            val copy = number.ieeeBits?.let {
                ExactLpNumber.ofIeee(
                    Double.fromBits(it),
                )
            } ?: ExactLpNumber.of(number.value)
            val hash = number.hashCode()
            val expected = number.ieeeBits?.let { Double.fromBits(it) } ?: number.value.toDouble()
            val model = ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(number), ExactLpSide(number)))),
                emptyList(),
                ExactLpObjective(listOf(zero)),
            )
            val state = LpExactState(model)

            repeat(2) {
                val projection = state.toWorkingModel()
                if (expected.isFinite()) {
                    assertEquals(expected.toRawBits(), assertNotNull(projection).upperD(0).toRawBits())
                    assertEquals(expected.toRawBits(), projection.lowerD(0).toRawBits())
                } else {
                    assertNull(projection)
                }
            }

            assertEquals(copy, number)
            assertEquals(hash, number.hashCode())
            assertTrue(model.sameAuthority(state.model))
        }
    }

    @Test
    fun `bound and objective revisions project their own values`() {
        val zero = ExactLpNumber.of(0L)
        val three = ExactLpNumber.of(3L)
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(ExactLpNumber.of(10L))))),
            emptyList(),
            ExactLpObjective(
                listOf(ExactLpNumber.of(5L)),
                scale = ExactLpNumber.of(2L),
                externalConstant = ExactLpNumber.of(5L),
            ),
        )
        val trail = LpBoundTrail(model)
        val initial = assertNotNull(trail.state.toWorkingModel())
        assertTrue(trail.assertBound(0, true, ExactLpSide(three), 0L))
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 1L))
        assertNotNull(trail.state.toWorkingModel())

        assertTrue(trail.replaceObjective(ExactLpObjective(listOf(three), scale = three, externalConstant = three)))
        val updated = assertNotNull(trail.state.toWorkingModel())

        assertEquals(3.0, updated.costD(0))
        assertEquals(3.0, updated.upperD(0))
        assertEquals(2.0, updated.lowerD(0))
        assertEquals(7.0, updated.objectiveD(12.0))
        assertEquals(5.0, initial.costD(0))
        assertEquals(10.0, initial.upperD(0))
        assertEquals(0.0, initial.lowerD(0))
        assertEquals(11.0, initial.objectiveD(12.0))
        assertSame(trail.state, updated.exactState)
    }

    @Test
    fun `objective projections preserve input bits and arithmetic order`() {
        val zero = ExactLpNumber.of(0L)
        val third = ExactLpNumber.of(BigFraction.of(BIG_ONE, bigIntOf(3)))
        for (scale in listOf(third, ExactLpNumber.ofIeee(Double.MIN_VALUE), ExactLpNumber.ofIeee(2.0))) {
            for (constant in listOf(third, ExactLpNumber.ofIeee(-0.0), ExactLpNumber.ofIeee(Double.MIN_VALUE))) {
                val model = ExactLpModel(
                    listOf(emptyList()),
                    emptyList(),
                    listOf(ExactLpColumn(ExactLpBounds())),
                    emptyList(),
                    ExactLpObjective(listOf(zero), scale = scale, externalConstant = constant),
                )
                val working = assertNotNull(LpExactState(model).toWorkingModel())
                val expectedScale = scale.ieeeBits?.let(Double::fromBits) ?: scale.value.toDouble()
                val expectedConstant = constant.ieeeBits?.let(Double::fromBits) ?: constant.value.toDouble()

                for (value in listOf(-0.0, Double.MIN_VALUE, 1.0, Double.MAX_VALUE)) {
                    val expected = value / expectedScale + expectedConstant
                    repeat(2) { assertEquals(expected.toRawBits(), working.objectiveD(value).toRawBits()) }
                }
            }
        }
    }
}
