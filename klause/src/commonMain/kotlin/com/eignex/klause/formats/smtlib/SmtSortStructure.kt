package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.ExecutionControl

internal fun SmtSort.checkpointStructure(control: ExecutionControl) {
    checkpointStructure { control.polynomialSourceCheckpoint(1) }
}

internal fun SmtSort.checkpointStructure(checkpoint: () -> Unit) {
    val work = ArrayDeque<SmtSort>()
    work.addLast(this)
    while (work.isNotEmpty()) {
        checkpoint()
        when (val sort = work.removeLast()) {
            is SmtSort.Array -> {
                work.addLast(sort.element)
                work.addLast(sort.index)
            }
            is SmtSort.Uninterpreted -> work.addAll(sort.arguments)
            else -> Unit
        }
    }
}

internal fun smtSortsEqual(left: SmtSort, right: SmtSort): Boolean {
    val work = ArrayDeque<Pair<SmtSort, SmtSort>>()
    work.addLast(left to right)
    while (work.isNotEmpty()) {
        val (first, second) = work.removeLast()
        if (first === second) continue
        when (first) {
            is SmtSort.Array -> {
                if (second !is SmtSort.Array) return false
                work.addLast(first.element to second.element)
                work.addLast(first.index to second.index)
            }
            is SmtSort.Uninterpreted -> {
                if (second !is SmtSort.Uninterpreted || first.name != second.name ||
                    first.arguments.size != second.arguments.size) return false
                for (index in first.arguments.indices) work.addLast(first.arguments[index] to second.arguments[index])
            }
            is SmtSort.BitVector -> if (second !is SmtSort.BitVector || first.width != second.width) return false
            else -> return false
        }
    }
    return true
}

internal fun smtSortHashCode(root: SmtSort): Int {
    val work = ArrayDeque<Pair<SmtSort, Boolean>>()
    val hashes = ArrayDeque<Int>()
    work.addLast(root to false)
    while (work.isNotEmpty()) {
        val (sort, combine) = work.removeLast()
        when (sort) {
            is SmtSort.Array -> if (combine) {
                val element = hashes.removeLast()
                hashes.addLast(31 * hashes.removeLast() + element)
            } else {
                work.addLast(sort to true)
                work.addLast(sort.element to false)
                work.addLast(sort.index to false)
            }
            is SmtSort.Uninterpreted -> if (combine) {
                val arguments = List(sort.arguments.size) { hashes.removeLast() }.asReversed()
                hashes.addLast(31 * sort.name.hashCode() + arguments.fold(1) { hash, child -> 31 * hash + child })
            } else {
                work.addLast(sort to true)
                for (argument in sort.arguments.asReversed()) work.addLast(argument to false)
            }
            else -> hashes.addLast(sort.hashCode())
        }
    }
    return hashes.single()
}
