package com.eignex.klause.portfolio

import com.eignex.klause.ir.ObjectiveSense

import com.eignex.klause.solver.evidence.QuadraticEngineInvocation
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.pipeline.PreparedSourcePlan

internal class SourceWorkerBinding(
    val model: ModelIdentity,
    val invocation: EngineInvocation? = null,
    val sharingInvocation: EngineInvocation? = null,
    val scope: SourceScope? = invocation?.scope,
    val provesResults: Boolean = true,
    val preparedPlan: PreparedSourcePlan? = invocation?.preparedPlan,
    val quadraticInvocation: QuadraticEngineInvocation? = null,
) {
    constructor(
        invocation: EngineInvocation,
        sharingInvocation: EngineInvocation? = null,
        provesResults: Boolean = true,
    ) : this(invocation.model, invocation, sharingInvocation, provesResults = provesResults)

    init {
        require(invocation == null || quadraticInvocation == null) { "worker has incompatible execution bindings" }
        require(quadraticInvocation?.matchesModel(model) != false) { "quadratic producer differs from the worker model" }
        require(quadraticInvocation == null || (preparedPlan != null && quadraticInvocation.matchesModel(
            ModelIdentity.of(preparedPlan.originalSource, quadraticInvocation.objective)))) {
            "quadratic producer has another source plan"
        }
        require(quadraticInvocation == null || preparedPlan?.quadraticObjective == null ||
            (preparedPlan.quadraticObjective === quadraticInvocation.objective &&
                preparedPlan.maximize == (quadraticInvocation.sense == ObjectiveSense.MAXIMIZE))) {
            "quadratic producer has another source objective or direction"
        }
        require(sharingInvocation == null || invocation != null) { "sharing requires an actual producer invocation" }
        require(invocation == null || model.matches(invocation.model)) { "producer differs from the worker model" }
        require(invocation == null || scope == invocation.scope) { "producer has another source scope" }
        require(invocation == null || preparedPlan === invocation.preparedPlan) { "producer has another source plan" }
        require(sharingInvocation == null || model.matches(sharingInvocation.model)) {
            "sharing differs from the worker model"
        }
        require(sharingInvocation == null || preparedPlan === sharingInvocation.preparedPlan) {
            "sharing has another source plan"
        }
    }

    fun matches(other: SourceWorkerBinding): Boolean = model.matches(other.model) &&
        preparedPlan === other.preparedPlan && scope == other.scope && provesResults == other.provesResults &&
        invocation.sameAs(other.invocation) && sharingInvocation.sameAs(other.sharingInvocation) &&
        (if (quadraticInvocation == null) other.quadraticInvocation == null else
            other.quadraticInvocation?.let(quadraticInvocation::matches) == true)

    private fun EngineInvocation?.sameAs(other: EngineInvocation?): Boolean =
        if (this == null) other == null else other != null && matches(other)
}
