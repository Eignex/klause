package com.eignex.klause.solver.search

/**
 * Arithmetic participation in one source-root coordinate agreement.
 *
 * Source deductions constrain peers before completion. Checking consumes the immutable values of
 * the sole coordinate contributors, rather than combining independently chosen fragment witnesses.
 * Participation supplies no procedure-completeness or global claim authority.
 */
interface SourceCoordinateAgreement {
    /** Publish only exact consequences justified by the current source fragment and scope. */
    fun shareSourcePredicates(context: SearchContext): ComponentResult

    /** Check the authoritative coordinates under current conditions, or decline without refuting the source. */
    fun checkSourceCoordinates(model: AssembledSearchModel, context: SearchContext): ComponentCheck
}

/**
 * Completes explicit arithmetic peers against one existing immutable model assembly.
 *
 * [snapshot] must use the containing session's standard assembly after its component checks.
 * Coordinate ownership remains with those contributors. An invalid chosen point is indeterminate;
 * only the traversal and source proof owners can establish exhaustion or a global claim.
 */
class SourceCoordinateAgreementComponent(
    participants: List<SourceCoordinateAgreement>,
    private val snapshot: () -> AssembledSearchModel,
    private val supported: Boolean = true,
) : SearchComponent {
    private val participants = participants.toList()

    init {
        require(!supported || this.participants.isNotEmpty()) { "supported agreement needs an arithmetic participant" }
    }

    override fun propagate(context: SearchContext): ComponentResult {
        if (context.cancelled()) return ComponentResult.Indeterminate
        if (!supported) {
            context.executionControls.reportStop(SearchStopCause.RepresentationDecline)
            return ComponentResult.Indeterminate
        }
        for (participant in participants) {
            val result = participant.shareSourcePredicates(context)
            if (result != ComponentResult.Consistent) return result
        }
        return ComponentResult.Consistent
    }

    override fun check(context: SearchContext): ComponentCheck {
        if (context.cancelled()) return ComponentCheck.Indeterminate
        if (!supported) return declined(context)
        val model = try {
            snapshot()
        } catch (_: IllegalArgumentException) {
            return declined(context)
        } catch (_: IllegalStateException) {
            return declined(context)
        }
        for (participant in participants) {
            if (participant.checkSourceCoordinates(model, context) != ComponentCheck.Feasible) {
                return declined(context)
            }
        }
        return if (context.cancelled()) ComponentCheck.Indeterminate else ComponentCheck.Feasible
    }
    private fun declined(context: SearchContext): ComponentCheck {
        if (!context.cancelled() && context.executionControls.stopCause == null) {
            context.executionControls.reportStop(SearchStopCause.RepresentationDecline)
        }
        return ComponentCheck.Indeterminate
    }
}
