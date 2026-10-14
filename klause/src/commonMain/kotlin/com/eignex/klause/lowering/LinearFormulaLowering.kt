package com.eignex.klause.lowering

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.util.Cancellation

internal class LinearFormulaLowering(private val cancellation: Cancellation) {
    fun relation(form: LinearForm): List<List<LinearRow>> {
        require(form !is LinearForm.Relaxation) { "a relaxation cannot enforce a source factor" }
        checkpoint(form.rows.size.toLong() + 1)
        return if (form is LinearForm.Disjunction) listOf(form.rows.toList())
        else form.rows.map { listOf(it) }
    }

    fun and(operands: List<List<List<LinearRow>>>): List<List<LinearRow>> {
        checkpoint(operands.sumOf { it.size.toLong() } + 1)
        return operands.flatten()
    }

    fun or(operands: List<List<List<LinearRow>>>): List<List<LinearRow>> {
        var clauses: List<List<LinearRow>> = listOf(emptyList())
        for (operand in operands) {
            val next = ArrayList<List<LinearRow>>()
            for (left in clauses) for (right in operand) {
                checkpoint(left.size.toLong() + right.size + 1)
                next += left + right
            }
            clauses = next
        }
        return clauses
    }

    fun forms(clauses: List<List<LinearRow>>): List<LinearForm> {
        checkpoint(clauses.size.toLong() + 1)
        val singletons = clauses.filter { it.size == 1 }.map { it.single() }
        return (if (singletons.isNotEmpty() || clauses.isEmpty()) listOf(LinearForm.Conjunction(singletons))
        else emptyList()) +
            clauses.filter { it.size != 1 }.map { LinearForm.Disjunction(it) }
    }

    fun checkpoint(units: Long = 1) {
        if (cancellation()) throw LinearEncodingInterrupted()
        cancellation.charge(units)
        if (cancellation()) throw LinearEncodingInterrupted()
    }
}

internal class LinearEncodingInterrupted : RuntimeException("linear encoding interrupted")
