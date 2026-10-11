package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.tseitinAnd
import com.eignex.klause.lowering.tseitinIff
import com.eignex.klause.lowering.tseitinOr

internal fun Compiler.Builder.reifyAnd(literals: List<Int>): Int =
    retainBooleanDefinition { tseitinAnd(literals) }

internal fun Compiler.Builder.reifyOr(literals: List<Int>): Int =
    retainBooleanDefinition { tseitinOr(literals) }

internal fun Compiler.Builder.reifyIff(first: Int, second: Int): Int =
    retainBooleanDefinition { tseitinIff(first, second) }

private inline fun Compiler.Builder.retainBooleanDefinition(define: () -> Int): Int {
    val start = factors.size
    val literal = define()
    conditionalEqualities.definePredicate(Lit.variable(literal), factors.subList(start, factors.size))
    return literal
}
