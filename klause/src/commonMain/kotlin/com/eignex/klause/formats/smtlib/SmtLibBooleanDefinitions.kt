package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.lowering.tseitinAnd
import com.eignex.klause.lowering.tseitinIff
import com.eignex.klause.lowering.tseitinOr
import com.eignex.klause.lowering.trueLit
import com.eignex.klause.util.Cancellation

internal fun Compiler.Builder.reifyAnd(literals: List<Int>): Int =
    retainBooleanDefinition { reifyLogical(literals, conjunction = true) }.also { result ->
        affineRealTables.conjunction(result, literals, trueLitCache)
    }

internal fun Compiler.Builder.reifyOr(literals: List<Int>): Int =
    retainBooleanDefinition { reifyLogical(literals, conjunction = false) }

internal fun Compiler.Builder.reifyIff(first: Int, second: Int): Int = retainBooleanDefinition {
    booleanWork(2L)
    when {
        first == second -> trueLit()
        first == Lit.negate(second) -> Lit.negate(trueLit())
        trueLitCache < 0 -> sharedIff(first, second)
        first == trueLitCache -> second
        second == trueLitCache -> first
        first == Lit.negate(trueLitCache) -> Lit.negate(second)
        second == Lit.negate(trueLitCache) -> Lit.negate(first)
        else -> sharedIff(first, second)
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
        else -> {
            val operands = kept.toList()
            val kind = if (conjunction) SmtBooleanGates.Kind.AND else SmtBooleanGates.Kind.OR
            booleanGates.literal(kind, operands) {
                if (conjunction) tseitinAnd(operands) else tseitinOr(operands)
            }
        }
    }
}

private fun Compiler.Builder.sharedIff(first: Int, second: Int): Int =
    booleanGates.literal(SmtBooleanGates.Kind.IFF, listOf(first, second)) { tseitinIff(first, second) }

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

internal class SmtBooleanGates(private val cancellation: Cancellation, private val maxEntries: Int = 65_536) {
    enum class Kind { AND, OR, IFF }

    private data class Key(val kind: Kind, val inputs: List<Int>)

    private val literals = HashMap<Key, Int>()
    private var retainedEntries = 0

    init {
        require(maxEntries >= 0)
    }

    fun literal(kind: Kind, inputs: List<Int>, create: () -> Int): Int {
        require(kind != Kind.IFF || inputs.size == 2)
        val entries = inputs.size.toLong() + 1L
        work(entries)
        if (entries > maxEntries) return create()
        val ordered = inputs.sortedWith { left, right ->
            work(1L)
            left.compareTo(right)
        }
        work(entries * 2L)
        val canonical = if (kind == Kind.IFF && ordered.isNotEmpty() && !Lit.isPositive(ordered[0])) {
            ordered.map(Lit::negate)
        } else ordered
        val key = Key(kind, canonical)
        literals[key]?.let { return it }
        val literal = create()
        if (entries <= maxEntries - retainedEntries) {
            literals[key] = literal
            retainedEntries += entries.toInt()
        }
        return literal
    }

    private fun work(units: Long) {
        cancellation.charge(units)
        if (cancellation()) throw PolynomialSourceInterrupted()
    }
}
