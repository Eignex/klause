package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.Cancellation

internal fun substituteSmtSort(root: SmtSort, substitution: Map<String, SmtSort>,
    cancellation: Cancellation): SmtSort {
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

internal fun smtSortParameters(root: SmtSort, parameters: Set<String>, cancellation: Cancellation): Set<String> {
    val found = LinkedHashSet<String>()
    val work = ArrayDeque<SmtSort>()
    work.addLast(root)
    while (work.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        when (val sort = work.removeLast()) {
            is SmtSort.Array -> { work.addLast(sort.index); work.addLast(sort.element) }
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
    arguments: List<SmtSort>, qualification: SmtSort?, cancellation: Cancellation,
): Pair<List<SmtSort>, SmtSort>? {
    if (parameters.size != arguments.size) return null
    val substitutions = HashMap<String, SmtSort>()
    val pending = ArrayDeque<Pair<SmtSort, SmtSort>>()
    if (qualification != null) pending.addLast(result to qualification)
    for (index in parameters.indices.reversed()) pending.addLast(parameters[index] to arguments[index])
    while (pending.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        val (expected, actual) = pending.removeLast()
        if (expected is SmtSort.Uninterpreted && expected.arguments.isEmpty() &&
            polynomialSymbol(expected.name) in typeParameters) {
            val name = polynomialSymbol(expected.name)
            val previous = substitutions[name]
            if (previous == null) substitutions[name] = actual else if (previous != actual) return null
        } else when {
            expected == actual || expected == SmtSort.Real && actual == SmtSort.Int -> Unit
            expected is SmtSort.Array && actual is SmtSort.Array -> {
                pending.addLast(expected.index to actual.index)
                pending.addLast(expected.element to actual.element)
            }
            expected is SmtSort.Uninterpreted && actual is SmtSort.Uninterpreted &&
                polynomialSymbol(expected.name) == polynomialSymbol(actual.name) &&
                expected.arguments.size == actual.arguments.size -> {
                for (index in expected.arguments.indices) {
                    pending.addLast(expected.arguments[index] to actual.arguments[index])
                }
            }
            else -> return null
        }
    }
    val instantiated = substituteSmtSort(result, substitutions, cancellation)
    if (qualification != null && qualification != instantiated) return null
    return parameters.map { substituteSmtSort(it, substitutions, cancellation) } to instantiated
}
