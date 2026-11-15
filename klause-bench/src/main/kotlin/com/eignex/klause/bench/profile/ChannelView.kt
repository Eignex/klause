package com.eignex.klause.bench.profile

import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.util.ExecutionControl

internal enum class ChannelView {
    Maintained,
    SearchedValues,
    SearchedPredicates,
    SearchedChannels,
    InverseAssignment,
}

internal fun ChannelView.sweep(sweep: DefinitionalSweep?, stop: ExecutionControl): DefinitionalSweep? {
    if (this == ChannelView.Maintained || this == ChannelView.InverseAssignment || sweep == null) return sweep
    return DefinitionalSweep::class.java.methods.single {
        it.name.substringBefore('$') == "searchingChannels" && it.parameterCount == 3
    }.invoke(sweep, this != ChannelView.SearchedPredicates, this != ChannelView.SearchedValues, stop)
        as DefinitionalSweep?
}

internal fun inverseAssignmentView(problem: Problem, stop: ExecutionControl): Problem? =
    Class.forName("com.eignex.klause.localsearch.ChannelDefinitionViewKt").methods.single {
        it.name == "inverseAssignmentView"
    }.invoke(null, problem, stop) as Problem?
