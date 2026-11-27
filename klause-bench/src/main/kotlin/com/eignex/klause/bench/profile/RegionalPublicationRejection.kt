package com.eignex.klause.bench.profile

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.validation.LinearSourceWitness
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BigFraction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

internal class RegionalPublicationRejection(
    private val original: Any, private val mapping: Any?, private val maximization: Boolean,
) {
    private class Candidate(val sample: Sample, val minimizedObjective: BigFraction)
    private var rejected: Candidate? = null

    @Suppress("UNCHECKED_CAST") // The JVM method exposes the production callback as Function3.
    fun observe(native: Any): (Sample, BigFraction, ExecutionControl) -> Boolean {
        val checker = native as (Sample, BigFraction, ExecutionControl) -> Boolean
        return { sample, value, token ->
            val accepted = checker(sample, value, token)
            if (!accepted && rejected == null) rejected = Candidate(sample, value)
            accepted
        }
    }

    fun inspect(sourceHash: String, pinsHash: String, stop: ExecutionControl, out: File) {
        val start = System.nanoTime()
        val deadline = stop or ExecutionControl.after(250.milliseconds)
        var work = 0L
        val check = object : ExecutionControl() {
            override val accounting = WorkMeter { work += it }
            override fun isCancelled(): Boolean = deadline() || work >= 100_000L
            override fun deadline() = deadline.deadline()
        }
        val candidate = rejected
        val lifted = candidate?.sample?.let { sample ->
            check.charge(sample.numBoolVars.toLong() + sample.numIntVars + sample.numRealVars)
            if (mapping == null) sample else {
                val target = call(mapping, "getTarget")
                call(mapping, "reconstructFrom", target, sample) as Sample
            }
        }
        val result = lifted?.takeUnless { check() }?.let {
            Class.forName("com.eignex.klause.solver.pipeline.LinearSourceOutcomeKt").methods
                .single { method ->
                    method.name == "check" && method.parameterCount == 3 &&
                        method.parameterTypes[1] == Sample::class.java
                }.invoke(null, original, it, check)
        }
        val witness = result?.takeIf { it.javaClass.simpleName == "Accepted" }
            ?.let { call(it, "getWitness") as LinearSourceWitness }
        val originalValue = witness?.objective
        val view = requireNotNull(call(original, "getView"))
        val scale = call(view, "getObjectiveScale") as BigFraction
        val scaled = originalValue?.times(scale)?.let { if (maximization) it.negated() else it }
        val expected = candidate?.minimizedObjective?.let {
            if (mapping == null) it else {
                val adjustment = requireNotNull(call(mapping, "getObjectiveAdjustment"))
                call(adjustment, "sourceValue", it) as BigFraction
            }
        }
        val integerPins = candidate?.sample?.let { sample ->
            (0 until sample.numIntVars).map { id ->
                check.charge(1L)
                JsonPrimitive(sample.exactIntValue(id).toString())
            }
        }
        out.resolve("publication-rejection.json").writeText(buildJsonObject {
            put("sourceSha256", sourceHash)
            put("pinsSha256", pinsHash)
            put("recordedNativeRejection", candidate != null)
            put("nativeSourceCheck", result?.javaClass?.simpleName)
            put("nativeExact", witness?.exact)
            put("nativeSourceObjective", originalValue?.toString())
            put("scaledNativeObjective", scaled?.toString())
            put("expectedScaledObjective", expected?.toString())
            put("matchesScaledObjective", scaled?.let { it == expected })
            integerPins?.let { put("workingIntegerValues", JsonArray(it)) }
            put("work", work)
            put("elapsedNs", System.nanoTime() - start)
            put("cancelled", check())
        }.toString())
    }

    @Suppress("SpreadOperator") // Java reflection requires varargs.
    private fun call(owner: Any, name: String, vararg args: Any?): Any? =
        NumericalResidualProfile.call(owner, name, *args)
}
