package com.eignex.klause.solver

import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.conditionedLinearSupported
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class SelectedEncoderRequest private constructor(
    val selection: PreparedFactorProjections,
    val target: EncodingTarget,
    val indices: List<Int>,
    val encoders: List<Encoder>,
    val fixedIntegers: Map<SourceIntId, BigInt>,
    val fixedBooleans: Map<SourceBoolId, Boolean>,
    val cancellation: Cancellation,
    val completeFiniteConditions: Boolean,
) {
    companion object {
        @Suppress("LongMethod", "CyclomaticComplexMethod")
        fun prepare(
            selection: PreparedFactorProjections,
            target: EncodingTarget,
            indices: List<Int>,
            fixedIntegers: Map<SourceIntId, BigInt>,
            fixedBooleans: Map<SourceBoolId, Boolean>,
            cancellation: Cancellation,
            completeFiniteConditions: Boolean,
            onDecline: (EncoderDecline) -> Unit,
        ): SelectedEncoderRequest? {
            val stop = cancellation.encodingPreparationScope()
            fun decline(reason: EncoderDecline): SelectedEncoderRequest? {
                onDecline(reason)
                return null
            }
            fun stopped(): Boolean {
                if (stop()) return true
                stop.charge(1)
                return stop()
            }
            if (stop()) return decline(EncoderDecline.RESOURCE)
            val source = selection.problem
            val integers = fixedIntegers.toMap()
            val booleans = fixedBooleans.toMap()
            for ((id, value) in integers) {
                if (stopped()) return decline(EncoderDecline.RESOURCE)
                if (!selection.acceptsIntegerCondition(id, value)) return decline(EncoderDecline.CONDITIONS)
            }
            for (id in booleans.keys) {
                if (stopped()) return decline(EncoderDecline.RESOURCE)
                if (id.index !in 0 until source.numBoolVars) return decline(EncoderDecline.CONDITIONS)
            }
            val selectedIndices = ArrayList<Int>(indices.size)
            val selectedEncoders = ArrayList<Encoder>(indices.size)
            val seen = HashSet<Int>()
            for (index in indices) {
                if (stopped()) return decline(EncoderDecline.RESOURCE)
                require(index in source.factors.indices && seen.add(index)) { "invalid selected factor scope" }
                val encoder = selection.capturedEncoder(index, target) ?: return decline(EncoderDecline.UNSUPPORTED)
                if (!encoder.enforcesSource && encoder.support != EncoderSupport.CONDITIONED) {
                    return decline(EncoderDecline.UNSUPPORTED)
                }
                for ((id, value) in encoder.requiredBooleans) {
                    if (stopped()) return decline(EncoderDecline.RESOURCE)
                    if (id.index !in 0 until source.numBoolVars || booleans[id] != value) {
                        return decline(EncoderDecline.CONDITIONS)
                    }
                }
                var missingInteger = false
                for (id in encoder.requiredIntegers) {
                    if (stopped()) return decline(EncoderDecline.RESOURCE)
                    if (id.index !in 0 until source.numIntVars) return decline(EncoderDecline.CONDITIONS)
                    if (id !in integers) missingInteger = true
                }
                if (missingInteger) {
                    if (!completeFiniteConditions || target != EncodingTarget.LINEAR) {
                        return decline(EncoderDecline.CONDITIONS)
                    }
                    val covered = selection.conditionedLinearSupported(index, stop)
                    if (stop()) return decline(EncoderDecline.RESOURCE)
                    if (!covered) return decline(EncoderDecline.CONDITIONS)
                }
                selectedIndices += index
                selectedEncoders += encoder
            }
            if (stop()) return decline(EncoderDecline.RESOURCE)
            return SelectedEncoderRequest(
                selection,
                target,
                selectedIndices,
                selectedEncoders,
                integers,
                booleans,
                stop,
                completeFiniteConditions,
            )
        }
    }
}

private fun PreparedFactorProjections.acceptsIntegerCondition(id: SourceIntId, value: BigInt): Boolean {
    if (id.index !in 0 until problem.numIntVars) return false
    val bounds = problem.intBounds
    if (bounds.hasLower(id.index) && value < bigIntOf(bounds.lower(id.index))) return false
    if (bounds.hasUpper(id.index) && value > bigIntOf(bounds.upper(id.index))) return false
    val domain = problem.intDomainOrNull(id.index) ?: return true
    return value.fitsLong() && value.toLongExact() in domain
}

internal fun Cancellation.encodingPreparationScope(): Cancellation =
    if (this is EncodingPreparationCancellation) this else EncodingPreparationCancellation(this)

@OptIn(ExperimentalAtomicApi::class)
private class EncodingPreparationCancellation(private val parent: Cancellation) : Cancellation {
    private val stopped = AtomicBoolean(false)

    override fun isCancelled(): Boolean {
        if (stopped.load()) return true
        val cancelled = parent()
        if (cancelled) stopped.store(true)
        return cancelled
    }

    override fun deadline() = parent.deadline()
    override fun workMeter() = parent.workMeter()
}
