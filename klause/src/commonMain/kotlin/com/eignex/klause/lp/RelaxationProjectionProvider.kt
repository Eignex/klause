package com.eignex.klause.lp

import com.eignex.klause.ir.Factor
import com.eignex.klause.solver.RelaxationCapability
import com.eignex.klause.solver.defaultExecutionCapabilities

/** Selects model-facing relaxation emission; null contributes no relaxation. */
fun interface RelaxationProjectionProvider {
    /** Select an immutable emitter descriptor during preparation. */
    fun select(factor: Factor): RelaxationProjection?
}

/** Sound relaxation emission; every emitted row must preserve the original factor's solutions. */
class RelaxationProjection internal constructor(
    /** Declared relaxation authority; engine feature and resource gates still apply. */
    val capability: RelaxationCapability,
    internal val builtIn: Boolean,
    private val emitter: (Factor, RelaxationBuilder, LinearLpProjection?) -> Unit,
) {
    /** Emit from the supplied engine-view factor; custom rows never imply retained-layout eligibility. */
    constructor(capability: RelaxationCapability, emitter: (Factor, RelaxationBuilder) -> Unit) :
        this(capability, false, { factor, builder, _ -> emitter(factor, builder) })

    internal fun emit(factor: Factor, builder: RelaxationBuilder, linear: LinearLpProjection?) {
        if (capability == RelaxationCapability.SOUND) emitter(factor, builder, linear)
    }
}

/** Default relaxations, preserving linear row caches, source attribution and existing numerical gates. */
object BuiltInRelaxationProjectionProvider : RelaxationProjectionProvider {
    internal fun emit(factor: Factor, builder: RelaxationBuilder, linear: LinearLpProjection?) {
        if (factor.defaultExecutionCapabilities().relaxation == RelaxationCapability.SOUND) {
            factor.buildLpRelaxation(builder, linear)
        }
    }

    override fun select(factor: Factor): RelaxationProjection? {
        val capability = factor.defaultExecutionCapabilities().relaxation
        if (capability == RelaxationCapability.NONE) return null
        return RelaxationProjection(capability, true) { view, builder, linear -> view.buildLpRelaxation(builder, linear) }
    }
}
