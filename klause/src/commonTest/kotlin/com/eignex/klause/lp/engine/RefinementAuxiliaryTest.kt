package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefinementAuxiliaryTest {
    @Test
    fun `an improving cone on an infeasible source yields a source Farkas proof`() {
        val zero = ExactLpNumber.of(0L)
        val one = ExactLpNumber.of(1L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, one)), emptyList()),
                listOf(ExactLpNumber.of(-1L)),
                listOf(
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero)), integral = false),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero)), integral = false),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero)), integral = false),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(zero, ExactLpNumber.of(-1L), zero)),
            ),
        )
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                basis = Basis(intArrayOf(2), arrayOf(VarStatus.AT_LOWER, VarStatus.AT_LOWER, VarStatus.BASIC)),
            )

            val conflict = assertNotNull(result.conflict)
            assertNull(result.unboundedness)
            assertNull(result.witness)
            assertTrue(result.metrics.auxiliaries > 0)
            assertTrue(result.usedBasis)
            assertEquals(listOf(0), conflict.rows.toList())
            assertTrue(conflict.multipliers.single().signum() > 0)
            assertEquals(state, assertNotNull(result.support).state)
        }
    }

    @Test
    fun `a true recession requires an independently feasible source point`() {
        val zero = ExactLpNumber.of(0L)
        val state = LpExactState(
            ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(upper = ExactLpSide(zero)), integral = false)),
                emptyList(),
                ExactLpObjective(listOf(ExactLpNumber.of(1L))),
            ),
        )
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
            )

            val proof = assertNotNull(result.unboundedness)
            assertEquals(listOf(BigFraction.ZERO), proof.witness.primal)
            assertTrue(proof.direction.single().signum() < 0)
            assertTrue(result.metrics.auxiliaries > 0)
        }
    }

    @Test
    fun `a strict closure boundary is not a source feasible point`() {
        val zero = ExactLpNumber.of(0L)
        val state = LpExactState(
            ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(
                    ExactLpColumn(
                        ExactLpBounds(
                            ExactLpSide(zero, strict = true),
                            ExactLpSide(ExactLpNumber.of(1L)),
                        ),
                        integral = false,
                    ),
                ),
                emptyList(),
                ExactLpObjective(listOf(ExactLpNumber.of(1L))),
            ),
        )
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
            )

            assertNull(result.witness)
            assertNull(result.conflict)
            assertNull(result.unboundedness)
        }
    }

    @Test
    fun `standalone certification reaches unboundedness from a null float terminal`() {
        val model = ExactLpModel(
            listOf(emptyList()),
            emptyList(),
            listOf(ExactLpColumn(ExactLpBounds(upper = ExactLpSide(ExactLpNumber.of(0L))), integral = false)),
            emptyList(),
            ExactLpObjective(listOf(ExactLpNumber.of(1L))),
        )

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.UNBOUNDED, result.verdict)
        assertNotNull(result.witness)
        assertNotNull(result.unboundedness)
        assertEquals(1, assertNotNull(result.refinement).rays)
    }

    @Test
    fun `an integer recession rescales a rational direction using an integer feasible point`() {
        val state = LpExactState(
            ExactLpModel(
                listOf(emptyList()),
                emptyList(),
                listOf(ExactLpColumn(ExactLpBounds(upper = ExactLpSide(ExactLpNumber.of(0L))))),
                emptyList(),
                ExactLpObjective(listOf(ExactLpNumber.of(1L))),
            ),
        )
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
                direction = doubleArrayOf(-1.0 / 3.0),
            )

            val proof = assertNotNull(result.unboundedness)
            assertEquals(listOf(BigFraction.ZERO), proof.witness.primal)
            assertEquals(listOf(BigFraction.MINUS_ONE), proof.direction)
            assertEquals(1, result.metrics.auxiliaries)
        }
    }

    @Test
    fun `a fractional integer coordinate prevents a mixed integer unboundedness claim`() {
        val zero = ExactLpNumber.of(0L)
        val state = LpExactState(
            ExactLpModel(
                listOf(listOf(ExactLpEntry(0, ExactLpNumber.of(2L))), emptyList()),
                listOf(ExactLpNumber.of(1L)),
                listOf(
                    ExactLpColumn(ExactLpBounds()),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero))),
                    ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
                ),
                listOf(ExactLpRow()),
                ExactLpObjective(listOf(zero, ExactLpNumber.of(-1L), zero)),
            ),
        )
        LpScopedSolver(state).use { owner ->
            val result = refineLp(
                assertNotNull(state.toWorkingModel()),
                LpRefinementRequest(owner, owner.refinementCache, LpRefinementLimits()),
            )

            assertNotNull(result.witness)
            assertNull(result.unboundedness)
            assertNull(result.conflict)
        }
    }
}
