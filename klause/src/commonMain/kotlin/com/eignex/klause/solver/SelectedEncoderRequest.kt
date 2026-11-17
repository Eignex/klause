package com.eignex.klause.solver

import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.EncoderFailure
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.conditionedLinearSupported
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.bindStopControl
import com.eignex.klause.util.pollStop
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.toLongExact
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class SelectedEncoderRequest private constructor(
    val selection: PreparedFactorProjections,
    val target: EncodingTarget,
    val indices: List<Int>,
    val encoders: List<Encoder>,
    val fixedIntegers: Map<SourceIntId, BigInt>,
    val fixedBooleans: Map<SourceBoolId, Boolean>,
    val cancellation: ExecutionControl,
    val completeFiniteConditions: Boolean,
    private val onFailure: (EncoderFailure) -> Unit,
) {
    internal fun reportFailure(reason: EncoderDecline, index: Int? = null) {
        val encoder = index?.let { selection.capturedEncoder(it, target) }
        onFailure(encoderFailure(target, reason, index, encoder, fixedIntegers, fixedBooleans))
    }

    companion object {
        @Suppress("LongMethod", "CyclomaticComplexMethod")
        fun prepare(
            selection: PreparedFactorProjections,
            target: EncodingTarget,
            indices: List<Int>,
            fixedIntegers: Map<SourceIntId, BigInt>,
            fixedBooleans: Map<SourceBoolId, Boolean>,
            cancellation: ExecutionControl,
            completeFiniteConditions: Boolean,
            onDecline: (EncoderDecline) -> Unit,
            onFailure: (EncoderFailure) -> Unit,
        ): SelectedEncoderRequest? {
            val stop = cancellation.encodingPreparationScope()
            val polling = stop.bindStopControl()
            val accounting = stop.accounting
            val admission = stop.admission
            fun decline(reason: EncoderDecline, index: Int? = null): SelectedEncoderRequest? {
                onFailure(encoderFailure(target, reason, index, index?.let { selection.capturedEncoder(it, target) },
                    fixedIntegers, fixedBooleans))
                onDecline(reason)
                return null
            }
            fun stopped(): Boolean {
                if (polling.pollStop()) return true
                accounting?.charge(1)
                return admission?.exhausted == true || polling.pollStop()
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
                val encoder = selection.capturedEncoder(index, target)
                    ?: return decline(EncoderDecline.UNSUPPORTED, index)
                if (!encoder.enforcesSource && encoder.support != EncoderSupport.CONDITIONED) {
                    return decline(EncoderDecline.UNSUPPORTED, index)
                }
                for ((id, value) in encoder.requiredBooleans) {
                    if (stopped()) return decline(EncoderDecline.RESOURCE)
                    if (id.index !in 0 until source.numBoolVars || booleans[id] != value) {
                        return decline(EncoderDecline.CONDITIONS, index)
                    }
                }
                var missingInteger = false
                for (id in encoder.requiredIntegers) {
                    if (stopped()) return decline(EncoderDecline.RESOURCE)
                    if (id.index !in 0 until source.numIntVars) return decline(EncoderDecline.CONDITIONS, index)
                    if (id !in integers) missingInteger = true
                }
                if (missingInteger) {
                    if (!completeFiniteConditions || target != EncodingTarget.LINEAR) {
                        return decline(EncoderDecline.CONDITIONS, index)
                    }
                    val covered = selection.conditionedLinearSupported(index, stop)
                    if (stop()) return decline(EncoderDecline.RESOURCE)
                    if (!covered) return decline(EncoderDecline.CONDITIONS, index)
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
                onFailure,
            )
        }
    }
}

private fun encoderFailure(
    target: EncodingTarget,
    reason: EncoderDecline,
    index: Int?,
    encoder: Encoder?,
    integers: Map<SourceIntId, BigInt>,
    booleans: Map<SourceBoolId, Boolean>,
): EncoderFailure = EncoderFailure(target, reason, index,
    if (reason == EncoderDecline.CONDITIONS) encoder?.requiredIntegers?.filter { it !in integers }.orEmpty()
    else emptyList(),
    if (reason == EncoderDecline.CONDITIONS) encoder?.requiredBooleans?.filter { (id, value) ->
        booleans[id] != value
    }.orEmpty() else emptyMap())

private fun PreparedFactorProjections.acceptsIntegerCondition(id: SourceIntId, value: BigInt): Boolean {
    if (id.index !in 0 until problem.numIntVars) return false
    val bounds = problem.intBounds
    if (bounds.hasLower(id.index) && value < bigIntOf(bounds.lower(id.index))) return false
    if (bounds.hasUpper(id.index) && value > bigIntOf(bounds.upper(id.index))) return false
    val domain = problem.intDomainOrNull(id.index) ?: return true
    return value.fitsLong() && value.toLongExact() in domain
}

internal fun ExecutionControl.encodingPreparationScope(): ExecutionControl =
    if (this is EncodingPreparationCancellation) this else EncodingPreparationCancellation(this)

@OptIn(ExperimentalAtomicApi::class)
private class EncodingPreparationCancellation(private val parent: ExecutionControl) : ExecutionControl(parent) {
    override val containingStop get() = parent
    private val stopped = AtomicBoolean(false)

    override fun isCancelled(): Boolean {
        if (stopped.load()) return true
        if (parent()) stopped.store(true)
        return stopped.load()
    }

    override fun deadline() = parent.deadline()
    override val accounting get() = parent.accounting
    override val admission get() = admissionFrom(parent)
}
