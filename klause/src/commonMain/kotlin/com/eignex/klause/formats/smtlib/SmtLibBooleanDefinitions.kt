package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.lowering.tseitinAnd
import com.eignex.klause.lowering.tseitinIff
import com.eignex.klause.lowering.tseitinOr
import com.eignex.klause.lowering.trueLit

internal fun Compiler.Builder.reifyAnd(literals: List<Int>): Int =
    retainBooleanDefinition { reifyLogical(literals, conjunction = true) }

internal fun Compiler.Builder.reifyOr(literals: List<Int>): Int =
    retainBooleanDefinition { reifyLogical(literals, conjunction = false) }

internal fun Compiler.Builder.reifyIff(first: Int, second: Int): Int = retainBooleanDefinition {
    booleanWork(2L)
    when {
        first == second -> trueLit()
        first == Lit.negate(second) -> Lit.negate(trueLit())
        trueLitCache < 0 -> tseitinIff(first, second)
        first == trueLitCache -> second
        second == trueLitCache -> first
        first == Lit.negate(trueLitCache) -> Lit.negate(second)
        second == Lit.negate(trueLitCache) -> Lit.negate(first)
        else -> tseitinIff(first, second)
    }
}

private fun Compiler.Builder.reifyLogical(literals: List<Int>, conjunction: Boolean): Int {
    booleanWork(1L)
    val constant = trueLitCache
    val kept = LinkedHashSet<Int>()
    for (literal in literals) {
        booleanWork(1L)
        if (constant >= 0) {
            val value = if (literal == constant) true else if (literal == Lit.negate(constant)) false else null
            if (value == !conjunction) return literal
            if (value == conjunction) continue
        }
        if (Lit.negate(literal) in kept) return if (conjunction) Lit.negate(trueLit()) else trueLit()
        kept += literal
    }
    return when (kept.size) {
        0 -> if (conjunction) trueLit() else Lit.negate(trueLit())
        1 -> kept.single()
        else -> if (conjunction) tseitinAnd(kept.toList()) else tseitinOr(kept.toList())
    }
}

private fun Compiler.Builder.booleanWork(units: Long) {
    sourceCancellation.charge(units)
    if (sourceCancellation()) throw PolynomialSourceInterrupted()
}

private inline fun Compiler.Builder.retainBooleanDefinition(define: () -> Int): Int {
    val start = factors.size
    val literal = define()
    if (factors.size != start) {
        conditionalEqualities.definePredicate(Lit.variable(literal), factors.subList(start, factors.size))
    }
    return literal
}
