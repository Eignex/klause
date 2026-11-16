package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.ExecutionControl

internal fun substituteSmtSort(root: SmtSort, substitution: Map<String, SmtSort>,
    cancellation: ExecutionControl): SmtSort {
    val work = ArrayDeque<Pair<SmtSort, Boolean>>()
    val values = ArrayDeque<SmtSort>()
    work.addLast(root to false)
    while (work.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        val (sort, combine) = work.removeLast()
        when (sort) {
            is SmtSort.Array -> if (combine) {
                val element = values.removeLast()
                values.addLast(SmtSort.Array(values.removeLast(), element))
            } else {
                work.addLast(sort to true)
                work.addLast(sort.element to false)
                work.addLast(sort.index to false)
            }
            is SmtSort.Uninterpreted -> if (sort.arguments.isEmpty()) {
                values.addLast(substitution[polynomialSymbol(sort.name)] ?: sort)
            } else if (combine) {
                values.addLast(SmtSort.Uninterpreted(sort.name,
                    List(sort.arguments.size) { values.removeLast() }.reversed()))
            } else {
                work.addLast(sort to true)
                for (argument in sort.arguments.asReversed()) work.addLast(argument to false)
            }
            else -> values.addLast(sort)
        }
    }
    return values.single()
}

internal fun smtSortParameters(root: SmtSort, parameters: Set<String>, cancellation: ExecutionControl): Set<String> {
    val found = LinkedHashSet<String>()
    val work = ArrayDeque<SmtSort>()
    work.addLast(root)
    while (work.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        when (val sort = work.removeLast()) {
            is SmtSort.Array -> {
                work.addLast(sort.index)
                work.addLast(sort.element)
            }
            is SmtSort.Uninterpreted -> {
                if (sort.arguments.isEmpty() && polynomialSymbol(sort.name) in parameters) {
                    found += polynomialSymbol(sort.name)
                }
                for (argument in sort.arguments) work.addLast(argument)
            }
            else -> Unit
        }
    }
    return found
}

internal fun instantiateSmtRank(parameters: List<SmtSort>, result: SmtSort, typeParameters: Set<String>,
    arguments: List<SmtSort>, qualification: SmtSort?, cancellation: ExecutionControl,
): Pair<List<SmtSort>, SmtSort>? {
    if (parameters.size != arguments.size) return null
    val constraints = HashMap<String, MutableList<Pair<SmtSort, Boolean>>>()
    val pending = ArrayDeque<Triple<SmtSort, SmtSort, Boolean>>()
    if (qualification != null) pending.addLast(Triple(result, qualification, false))
    for (index in parameters.indices.reversed()) {
        pending.addLast(Triple(parameters[index], arguments[index], true))
    }
    while (pending.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        val (expected, actual, coercible) = pending.removeLast()
        if (expected is SmtSort.Uninterpreted && expected.arguments.isEmpty() &&
            polynomialSymbol(expected.name) in typeParameters) {
            constraints.getOrPut(polynomialSymbol(expected.name)) { ArrayList() } += actual to coercible
        } else when {
            expected == actual || (coercible && expected == SmtSort.Real && actual == SmtSort.Int) -> Unit
            expected is SmtSort.Array && actual is SmtSort.Array -> {
                pending.addLast(Triple(expected.index, actual.index, false))
                pending.addLast(Triple(expected.element, actual.element, false))
            }
            expected is SmtSort.Uninterpreted && actual is SmtSort.Uninterpreted &&
                polynomialSymbol(expected.name) == polynomialSymbol(actual.name) &&
                expected.arguments.size == actual.arguments.size -> {
                for (index in expected.arguments.indices) {
                    pending.addLast(Triple(expected.arguments[index], actual.arguments[index], false))
                }
            }
            else -> return null
        }
    }
    val substitutions = HashMap<String, SmtSort>()
    for ((name, bounds) in constraints) {
        cancellation.polynomialSourceCheckpoint(bounds.size.toLong())
        val first = bounds.first().first
        val chosen = if (bounds.all { it.first == first }) first
        else if (bounds.all { (it.first == SmtSort.Int && it.second) || it.first == SmtSort.Real }) SmtSort.Real
        else return null
        substitutions[name] = chosen
    }
    val instantiated = substituteSmtSort(result, substitutions, cancellation)
    if (qualification != null && qualification != instantiated) return null
    return parameters.map { substituteSmtSort(it, substitutions, cancellation) } to instantiated
}

internal fun smtRankSubstitution(declaration: SmtSourceDeclaration, parameters: List<SmtSort>,
    result: SmtSort, typeParameters: Set<String>, cancellation: ExecutionControl): Map<String, SmtSort> {
    val found = HashMap<String, SmtSort>()
    val work = ArrayDeque<Pair<SmtSort, SmtSort>>()
    for (pair in (declaration.parameters + declaration.result).zip(parameters + result)) work.addLast(pair)
    while (work.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        val (template, actual) = work.removeLast()
        when (template) {
            is SmtSort.Uninterpreted -> if (template.arguments.isEmpty() &&
                polynomialSymbol(template.name) in typeParameters) {
                found[polynomialSymbol(template.name)] = actual
            } else if (actual is SmtSort.Uninterpreted) {
                for (pair in template.arguments.zip(actual.arguments)) work.addLast(pair)
            }
            is SmtSort.Array -> if (actual is SmtSort.Array) {
                work.addLast(template.index to actual.index)
                work.addLast(template.element to actual.element)
            }
            else -> Unit
        }
    }
    return found
}
