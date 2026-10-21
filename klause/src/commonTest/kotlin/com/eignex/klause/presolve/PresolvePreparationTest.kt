package com.eignex.klause.presolve

import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.result.PresolveInvocationOutcome
import com.eignex.klause.solver.result.PresolveLane
import com.eignex.klause.solver.result.PresolvePreparationStage
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresolvePreparationTest {
    @Test
    fun `a required operation token should restore its containing meter after preparation`() {
        val budget = PresolveBudget(0L)
        val meter = WorkMeter { }
        val containing = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = meter
        }
        val problem = Problem(0, 0, emptyArray(), emptyList())

        val token = budget.observePreparation(
            PresolvePreparationStage.SOURCE_ENCODER, PresolveLane.SOURCE, problem, containing,
        ) { observed, _ -> observed }

        assertSame(meter, token.workMeter())
    }

    @Test
    fun `containing work should be forwarded once without spending the elective allowance`() {
        val budget = PresolveBudget(0L)
        var work = 0L
        val containing = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        val problem = Problem(0, 0, emptyArray(), emptyList())

        budget.observePreparation(
            PresolvePreparationStage.SOURCE_ENCODER, PresolveLane.SOURCE, problem, containing,
        ) { token, _ -> token.charge(7L) }

        val stage = budget.preparationStages.single()
        assertEquals(7L, work)
        assertEquals(7L, stage.nativeWork)
        assertEquals(0L, budget.spent())
        assertEquals(0L, stage.work)
        assertEquals(PresolveInvocationOutcome.COMPLETE, stage.outcome)
    }

    @Test
    fun `an interrupted required operation should retain its containing work`() {
        val budget = PresolveBudget(0L)
        var work = 0L
        val containing = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 3L
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        val problem = Problem(0, 0, emptyArray(), emptyList())

        budget.observePreparation(
            PresolvePreparationStage.SOURCE_ENCODER, PresolveLane.SOURCE, problem, containing,
        ) { token, _ ->
            token.charge(3L)
            assertTrue(token())
        }

        val stage = budget.preparationStages.single()
        assertEquals(PresolveInvocationOutcome.INTERRUPTED, stage.outcome)
        assertEquals(3L, stage.nativeWork)
        assertEquals(3L, work)
        assertEquals(0L, budget.spent())
    }

    @Test
    fun `nested required operations should not duplicate native work`() {
        val budget = PresolveBudget(0L)
        val problem = Problem(0, 0, emptyArray(), emptyList())

        budget.observePreparation(
            PresolvePreparationStage.SOURCE_ENCODER, PresolveLane.SOURCE, problem, Cancellation.Never,
        ) { outer, _ ->
            outer.charge(2L)
            budget.observePreparation(
                PresolvePreparationStage.ENGINE_BAKE, PresolveLane.FINITE, problem, outer,
            ) { inner, _ -> inner.charge(3L) }
            outer.charge(5L)
        }

        assertEquals(listOf(3L, 7L), budget.preparationStages.map { it.nativeWork })
        assertEquals(0L, budget.spent())
    }
}
