package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals

class LinearAuthorityInvocationReplacementTest : RuntimeInitializedTest() {
    private val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
    private val scope = SourceScope(SourceRevision("conditioned-linear", "exact-affine"),
        objective = SourceObjective("x", ObjectiveSense.MINIMIZE))

    @Test
    fun `a rejected replacement center should retire the previous advisory query`() {
        val columns = (0..2).map { SourceCoordinate(SourceCoordinate.Kind.Integer, it) }
        val rows = columns.flatMapIndexed { index, column ->
            listOf(
                "lower$index" to SourcePremise(SourceInequality(
                    mapOf(column to BigFraction.MINUS_ONE), BigFraction.ZERO)),
                "upper$index" to SourcePremise(SourceInequality(mapOf(column to BigFraction.ONE), BigFraction.ONE)),
                "cover$index" to SourcePremise(SourceInequality(mapOf(column to BigFraction.MINUS_ONE,
                    columns[(index + 1) % columns.size] to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
            )
        }.toMap() + mapOf(
            "real-lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), BigFraction.ZERO)),
            "real-upper" to SourcePremise(SourceInequality(mapOf(real to BigFraction.ONE), BigFraction.ZERO)),
        )
        val source = SourceProofContext(scope, columns.toSet() + real, rows,
            (columns + real).associateWith { BigFraction.ONE })
        val seed = Sample(booleanArrayOf(), longArrayOf(0, 0, 0), doubleArrayOf(1.99))

        var offered = seed
        LinearAuthorityInvocation(source, TheoryParams(sourceCuts = false), external = { offered },
            boundOrdered = true, incumbentProximity = true,
            incumbentCompletionCheck = { _, value, _ -> value != BigFraction.ofLong(2) })
            .use { invocation ->
                invocation.runSlice(ExecutionControl.Never, 1_000, 1) { _, _ -> }
                val consultations = invocation.stats.smt.completeSearchStages
                    ?.get("numericalProximityConsultations")
                offered = Sample(booleanArrayOf(), longArrayOf(1, 1, 0), doubleArrayOf(0.0),
                    exactReals = listOf(BigFraction.ZERO))

                repeat(128) {
                    if (!invocation.isDone) invocation.runSlice(ExecutionControl.Never, 1_000, 1) { _, _ -> }
                }

                assertEquals(BigFraction.ofLong(2), invocation.snapshot(TerminationReason.BudgetExhausted).value)
                assertEquals(consultations,
                    invocation.stats.smt.completeSearchStages?.get("numericalProximityConsultations"))
                assertEquals(1L,
                    invocation.stats.smt.completeSearchStages?.get("numericalProximitySupersededTargets"))
            }
    }
}
