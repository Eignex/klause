package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComponentLpSolverTest {
    @Test
    fun `a component contradiction certifies against the complete exact row coordinates`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 1L)
        val y = builder.addVar(0L, 3L)
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 2L)
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertTrue(assertNotNull(result.conflictSupport).rows.any { it.first == 1 })
    }

    @Test
    fun `an unbounded isolated objective retains an exact source direction`() {
        val builder = LpBuilder()
        repeat(2) {
            val x = builder.addVar(0L, 3L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }
        val isolated = builder.addFreeVar(4L, null, cost = -1L)
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.UNBOUNDED, result.verdict)
        assertTrue(assertNotNull(result.unboundedness).direction[isolated] > BigFraction.ZERO)
    }

    @Test
    fun `exact components preserve scaled objectives logical costs origins and isolated bounds`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val three = ExactLpNumber.of(3L)
        val half = ExactLpNumber.of(BigFraction.ofLong(2L).reciprocal())
        val source = ExactLpModel(
            matrix = listOf(listOf(ExactLpEntry(0, half)), listOf(ExactLpEntry(1, one)), emptyList()),
            rhs = listOf(three, three),
            columns = listOf(
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(three)), origin = one),
                ExactLpColumn(ExactLpBounds(upper = ExactLpSide(three))),
                ExactLpColumn(ExactLpBounds(lower = ExactLpSide(half)), origin = three),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(three))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(three))),
            ),
            rows = listOf(ExactLpRow(), ExactLpRow()),
            objective = ExactLpObjective(listOf(one, one, one, one, zero), constant = three,
                scale = three, externalConstant = one, sense = Sense.MAXIMIZE),
        )
        val state = LpExactState(source)
        val model = assertNotNull(state.ownerWorkingModel())
        val monolithic = solveAndCertify(model, componentSplit = false)

        val component = assertIs<ComponentLpSolver>(newLpSolver(model)).use { solver ->
            val result = assertNotNull(solver.solve())
            assertSame(state, solver.solvedExactState)
            assertSame(state, result.exactState)
            val bound = assertNotNull(solver.exactBound())
            val witness = assertNotNull(solver.exactWitness())
            assertEquals(bound.value, witness.objective)
            assertSame(state, assertNotNull(bound.support).state)
            assertTrue(bound.support.sides.any { it.column == 2 && it.side.number == half })
            certifyLpResult(model, solver, result)
        }

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, component.verdict)
        assertEquals(monolithic.lowerBound, component.lowerBound)
        assertEquals(monolithic.witness?.objective, component.witness?.objective)
        assertEquals(monolithic.float?.objective, component.float?.objective)
        assertEquals(BigFraction.ofLong(3L) + half.value, component.exactPrimal?.get(2))
    }

    @Test
    fun `inactive component rows remain free and cannot supply certificate premises`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 5L, cost = 1L)
        val y = builder.addVar(0L, 5L, cost = 1L)
        builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 4L, global = false,
            premises = LpRowPremises(intArrayOf(7), booleanArrayOf(false), longArrayOf(4L)))
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 2L)
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        val state = LpExactState(source, rows = LpScopedRows.initial(2).deactivate(setOf(0)))
        val model = assertNotNull(state.ownerWorkingModel())

        val result = assertIs<ComponentLpSolver>(newLpSolver(model)).use { solver ->
            val raw = assertNotNull(solver.solve())
            val support = assertNotNull(assertNotNull(solver.exactBound()).support)
            assertTrue(support.rows.none { it.first == 0 })
            assertTrue(support.sides.none { it.column == model.n })
            certifyLpResult(model, solver, raw)
        }

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(BigFraction.ofLong(2L), result.lowerBound)
        assertEquals(BigFraction.ZERO, result.exactPrimal?.get(x))
    }

    @Test
    fun `zero cost isolated columns receive a witness inside strict one sided bounds`() {
        val one = ExactLpNumber.of(1L)
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one)), listOf(ExactLpEntry(1, one)), emptyList()),
            listOf(one, one),
            listOf(ExactLpColumn(ExactLpBounds()), ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(lower = ExactLpSide(one, strict = true))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)))),
            listOf(ExactLpRow(), ExactLpRow()), ExactLpObjective(List(5) { zero }),
        )
        val model = assertNotNull(LpExactState(source).ownerWorkingModel())

        val result = solveAndCertify(model)

        assertEquals(2, result.float?.blocks)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertTrue(assertNotNull(result.exactPrimal)[2] > one.value)
    }
}
