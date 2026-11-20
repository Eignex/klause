package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class JointRepairNeighborhoodRecourseTest(private val kind: String) : RuntimeInitializedTest() {
    @Test
    fun `unavailable continuous support should preserve its containing resource limits`() {
        val integers = List(6) { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val source = SourceProofContext(SourceScope(SourceRevision("continuous-" + kind, "exact")),
            integers.toSet() + real, integers.mapIndexed { id, coordinate ->
                id.toString() to SourcePremise(SourceInequality(mapOf(coordinate to BigFraction.ONE,
                    real to BigFraction.ONE), BigFraction.ONE))
            }.toMap())
        val selector = assertNotNull(JointRepairNeighborhood.prepare(source, 6, 1, ExecutionControl.Never))
        val work = if (kind == "work") 8_450L else 1_000_000L
        val allocation = if (kind == "allocation") 5_000L else 1_000_000L
        val funding = SourceLpFunding(work, allocation).also { it.grant(work) }
        val budget = SourceLpBudget(funding = funding)
        val centers = List(6) { BigFraction.ZERO }
        val pair = assertIs<JointRepairNeighborhood.AdvisorySelection.Ready>(selector.advisoryNeighborhood(
            centers, 0, BigFraction.ONE, budget, ExecutionControl.Never))
        var fundedPolls = 0
        val token = ExecutionControl { kind == "cancelled" && funding.allocated > 4_000 && ++fundedPolls > 3 }

        val selected = selector.advisoryNeighborhood(centers, 0, BigFraction.ONE, budget, token,
            expand = true, previous = pair.condition)

        assertEquals(JointRepairNeighborhood.AdvisorySelection.Unavailable, selected)
        assertTrue(funding.spent <= work)
        assertTrue(funding.allocated <= allocation)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "resource={0}")
        fun cases() = listOf("work", "allocation", "cancelled").map { arrayOf(it) }
    }
}
