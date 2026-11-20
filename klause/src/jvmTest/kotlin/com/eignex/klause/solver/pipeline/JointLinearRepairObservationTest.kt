package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JointLinearRepairObservationTest : RuntimeInitializedTest() {
    @Test
    fun `private proposals should identify their checked center`() {
        val model = Problem(0,
            intBounds = IntBounds.fromModelBounds(LongArray(3), LongArray(3) { 1 }, null, null),
            factors = Array(3) { id ->
                Linear(intArrayOf(1, 1), intArrayOf(id, (id + 1) % 3), LinearOp.LE, 1)
            }, numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1, -1))
        val source = assertNotNull(model.linearAuthorityContext(objective, ObjectiveSense.MINIMIZE,
            ExecutionControl.Never))
        val seed = Sample(booleanArrayOf(), longArrayOf(0, 0, 0), doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))
        val records = mutableListOf<JointRepairObservation>()

        JointLinearRepair(model, source, objective, TheoryParams(maxFlips = 1, sourceLpOperations = 2),
            external = { seed }, incumbentProximity = true, incumbentCompletionCheck = { _, _, _ -> true },
            onObservation = records::add).use { repair ->
            repair.search(adaptive = false).use { search ->
                var done = false
                repeat(128) { if (!done) done = search.advance(ExecutionControl.Never, 5_000) }
            }
        }

        val proposal = records.first { it.phase == "proposal" && it.cutoff != null }
        assertEquals("checked-private", proposal.centerKind)
        assertEquals((0..2).associateWith { BigFraction.ZERO }, proposal.centers)
    }
}
