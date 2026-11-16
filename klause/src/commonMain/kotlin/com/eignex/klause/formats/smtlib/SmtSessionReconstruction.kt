package com.eignex.klause.formats.smtlib

import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.util.ExecutionControl
import kotlin.coroutines.cancellation.CancellationException

/** Capture an owned interpretation for all later model queries on this checked sample. */
fun SmtLibProblem.reconstructSessionModel(
    sample: Sample, cancellation: ExecutionControl = ExecutionControl.Never,
): SmtSessionModel = retainSmtSessionModel(this, sample::boolValue,
    { sample.exactIntValue(it).toString() }, { sample.smtRealValue(it, cancellation) },
    { sample.smtExactRealValue(it, cancellation) }, cancellation)

/** Capture an owned interpretation while the native exact witness remains available. */
fun SmtLibProblem.reconstructSessionModel(
    assignment: OpenTheoryAssignment, cancellation: ExecutionControl = ExecutionControl.Never,
): SmtSessionModel = retainSmtSessionModel(this, assignment::boolValue, assignment::intValue,
    { assignment.smtRealValue(it, cancellation) }, { assignment.smtExactRealValue(it, cancellation) }, cancellation)

private fun retainSmtSessionModel(
    problem: SmtLibProblem,
    boolean: (Int) -> Boolean,
    integer: (Int) -> String,
    real: (Int) -> String,
    exactReal: (Int) -> ExactRealValue?,
    control: ExecutionControl,
): SmtSessionModel = withSmtModelValues(problem, boolean, integer, real, control, exactReal) { values, checkpoint ->
    val captured = LinkedHashMap<String, SmtValue>()
    val authorities = HashMap<SmtValue.Real, ExactRealValue>()
    fun capture(name: String, value: SmtValue) {
        captured[smtSessionSymbol(name)] = value
        val work = ArrayDeque<SmtValue>()
        work += value
        while (work.isNotEmpty()) {
            checkpoint()
            when (val item = work.removeLast()) {
                is SmtValue.Real -> values.exactReal(item)?.let { authorities[item] = it }
                is SmtValue.Application -> work.addAll(item.arguments)
                is SmtValue.Function -> {
                    work += item.default
                    item.entries.forEach { (arguments, result) -> work.addAll(arguments); work += result }
                }
                is SmtValue.Array -> {
                    work += item.default
                    item.entries.forEach { (index, element) -> work += index; work += element }
                }
                else -> Unit
            }
        }
    }
    for (name in problem.encodingBoolVarNames.keys) capture(name, SmtValue.Boolean(values.boolean(name)))
    for (name in problem.encodingIntVarNames.keys) capture(name, SmtValue.Integer(values.integer(name)))
    for (name in problem.encodingRealVarNames.keys) capture(name, SmtValue.Real(values.real(name)))
    for (name in problem.privateBindings.keys) capture(name, values.value(name))
    val declarations = problem.sourceBindings.map { binding ->
        checkpoint()
        val value = values.value(binding.key)
        capture(binding.key, value)
        binding.declaration to value
    }
    for (check in problem.sourceChecks) {
        checkpoint()
        require(check.check(values)) { "SMT retained interpretation failed original-source checking" }
    }
    val snapshot = object : SmtModelValues, SmtSortCardinalities {
        override val cancellation: ExecutionControl = control
        override fun checkpoint(units: Long) {
            control.charge(units)
            if (control()) throw CancellationException("SMT retained interpretation interrupted")
        }
        override fun value(name: String): SmtValue {
            checkpoint()
            return captured[smtSessionSymbol(name)] ?: error("missing retained SMT value '$name'")
        }
        override fun boolean(name: String): Boolean = (value(name) as SmtValue.Boolean).value
        override fun integer(name: String): String = (value(name) as SmtValue.Integer).value
        override fun real(name: String): String = (value(name) as SmtValue.Real).term
        override fun exactReal(value: SmtValue.Real): ExactRealValue? =
            authorities[value] ?: smtRationalValue(value.term, control)
        override fun cardinality(sort: SmtSort, cap: Int): Int? =
            problem.originalSource?.typed?.datatypes?.cardinality(sort, cap, control)
    }
    val source = problem.originalSource?.typed?.commands ?: emptyList()
    SmtFixedModel(source, SmtSourceModel(declarations), snapshot, control)
}
