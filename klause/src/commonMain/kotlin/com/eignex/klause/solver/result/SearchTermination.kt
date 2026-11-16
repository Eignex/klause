package com.eignex.klause.solver.result

import com.eignex.klause.solver.search.SearchStopCause

internal fun SearchStopCause?.terminationReason(): TerminationReason = when (this) {
    SearchStopCause.Timeout -> TerminationReason.Timeout
    SearchStopCause.Cancelled -> TerminationReason.Cancelled
    SearchStopCause.WorkAllowance, SearchStopCause.CheckAllowance, SearchStopCause.DecisionAllowance,
    SearchStopCause.OperationAllowance -> TerminationReason.BudgetExhausted
    SearchStopCause.RepresentationDecline -> TerminationReason.RepresentationDeclined
    null -> TerminationReason.Unsupported
}
