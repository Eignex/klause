package com.eignex.klause.solver.validation

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WitnessValidationCompatibilityTest {
    @Test
    fun `legacy checker owners retain static JVM descriptors and default bridges`() {
        val signatures = listOf(
            Triple("backtrack.IncumbentCandidatesKt", "composedFixpoint", Verification::class.java to listOf(
                Problem::class.java, Candidate::class.java, Cancellation::class.java,
                Assumptions::class.java, Function1::class.java)),
            Triple("backtrack.IncumbentCandidatesKt", "composedFixpoint", Verification::class.java to listOf(
                Problem::class.java, Candidate::class.java, Cancellation::class.java,
                Assumptions::class.java, Set::class.java, Function1::class.java)),
            Triple("portfolio.RealWitnessVerificationKt", "verifyRealCoordinates", Verification::class.java to listOf(
                Problem::class.java, Sample::class.java)),
            Triple("portfolio.FiniteWitnessVerificationKt", "finiteWitnessVerifier", CandidateVerifier::class.java to listOf(
                BakedProblem::class.java, LinearObjective::class.java, Cancellation::class.java, Function1::class.java)),
            Triple("solver.pipeline.OpenWitnessKt", "verifyOpenWitness", Verification::class.java to listOf(
                Problem::class.java, Sample::class.java, Cancellation::class.java)),
            Triple("solver.pipeline.OpenWitnessKt", "refuteOpenWitness", String::class.java to listOf(
                Problem::class.java, Sample::class.java)),
        )

        for ((owner, name, signature) in signatures) {
            val type = Class.forName("com.eignex.klause.$owner")
            val (result, arguments) = signature
            val method = type.getDeclaredMethod(name, *arguments.toTypedArray())
            assertEquals(result, method.returnType)
            assertTrue(Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers))
            if (name in setOf("composedFixpoint", "finiteWitnessVerifier", "verifyOpenWitness")) {
                val bridge = type.getDeclaredMethod("${name}\$default",
                    *(arguments + listOf(Int::class.javaPrimitiveType!!, Any::class.java)).toTypedArray())
                assertEquals(result, bridge.returnType)
                assertTrue(Modifier.isPublic(bridge.modifiers) && Modifier.isStatic(bridge.modifiers))
            }
        }
    }
}
