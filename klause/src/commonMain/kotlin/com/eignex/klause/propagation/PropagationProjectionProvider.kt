package com.eignex.klause.propagation

import com.eignex.klause.ir.Factor
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.defaultExecutionCapabilities

/** Selects a deductive implementation from immutable source data; null refuses the finite route. */
fun interface PropagationProjectionProvider {
    /** Select during preparation, without allocating mutable propagation state. */
    fun select(factor: Factor): PropagationProjection?
}

/** A declared deductive role coupled to the factory that implements it. */
class PropagationProjection internal constructor(
    /** Sound filtering offered by this implementation. */
    val capability: PropagationCapability,
    /** Authority of this implementation at a fully pinned assignment. */
    val assignmentCheck: AssignmentCheckCapability,
    /** Explanation when deduction is deliberately inert. */
    val inertReason: String?,
    private val factory: (FactorProjectionPreparation?) -> Propagator,
) {
    init {
        require(capability != PropagationCapability.INERT ||
            (assignmentCheck != AssignmentCheckCapability.EXACT && !inertReason.isNullOrBlank())) {
            "inert deduction requires an enforcing peer or source certification, with an explicit reason"
        }
    }

    /** Factory calls must produce private mechanisms or stateless projections with owner-local payloads. */
    constructor(
        capability: PropagationCapability,
        assignmentCheck: AssignmentCheckCapability,
        inertReason: String? = null,
        factory: () -> Propagator,
    ) : this(capability, assignmentCheck, inertReason, { _ -> factory() })

    internal fun create(preparation: FactorProjectionPreparation?): Propagator {
        check(capability != PropagationCapability.UNSUPPORTED) { "unsupported propagation route" }
        return factory(preparation).also {
            check((it === NoPropagator) == (capability == PropagationCapability.INERT)) {
                "propagation capability disagrees with projection"
            }
        }
    }
}

/** Default finite deduction, including compatibility with custom factors implementing [Propagator]. */
object BuiltInPropagationProjectionProvider : PropagationProjectionProvider {
    override fun select(factor: Factor): PropagationProjection? {
        val capabilities = factor.defaultExecutionCapabilities()
        if (capabilities.propagation == PropagationCapability.UNSUPPORTED) return null
        return PropagationProjection(
            capabilities.propagation, capabilities.propagationCheck, capabilities.inertReason,
            factor::buildPropagatorProjection,
        )
    }
}
