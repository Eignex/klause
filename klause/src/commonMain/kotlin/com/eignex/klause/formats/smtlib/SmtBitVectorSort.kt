package com.eignex.klause.formats.smtlib

import com.eignex.util.numbers.magnitudeBitLength
import com.eignex.util.numbers.parseBigInt

internal fun smtBitVectorBuiltinSort(head: SExpr, arguments: List<SmtSort>): SmtSort? = when (head) {
    is SExpr.Atom -> smtBitVectorOperatorSort(polynomialSymbol(head.text), arguments)
    is SExpr.SList -> smtIndexedBitVectorSort(head, arguments)
}

internal fun smtBitVectorLiteralSort(text: String): SmtSort? {
    val radix = when {
        text.startsWith("#b") -> 2
        text.startsWith("#x") -> 16
        else -> return null
    }
    val digits = text.drop(2)
    if (digits.isEmpty() || digits.any {
        if (radix == 2) it != '0' && it != '1' else it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F'
    }) smtUnsupported("invalid bitvector literal")
    val width = digits.length.toLong() * if (radix == 2) 1 else 4
    if (width > Int.MAX_VALUE) smtUnsupported("bitvector literal width exceeds the encoding budget")
    return SmtSort.BitVector(width.toInt())
}

internal fun smtBitVectorOperatorSort(operator: String, arguments: List<SmtSort>): SmtSort? {
    val first = arguments.firstOrNull() as? SmtSort.BitVector ?: return null
    if (operator == "concat") {
        val second = arguments.getOrNull(1) as? SmtSort.BitVector ?: return null
        if (arguments.size != 2) return null
        return bitVectorSort(first.width.toLong() + second.width)
    }
    if (arguments.any { it != first }) return null
    return when (operator) {
        "bvnot", "bvneg" -> if (arguments.size == 1) first else null
        "bvand", "bvor", "bvxor", "bvadd", "bvmul" -> if (arguments.size >= 2) first else null
        "bvnand", "bvnor", "bvxnor", "bvsub", "bvudiv", "bvurem", "bvsdiv", "bvsrem", "bvsmod",
        "bvshl", "bvlshr", "bvashr" -> if (arguments.size == 2) first else null
        "bvult", "bvule", "bvugt", "bvuge", "bvslt", "bvsle", "bvsgt", "bvsge",
        "bvuaddo", "bvsaddo", "bvumulo", "bvsmulo", "bvusubo", "bvssubo", "bvsdivo" ->
            if (arguments.size == 2) SmtSort.Bool else null
        "bvnego" -> if (arguments.size == 1) SmtSort.Bool else null
        "bvcomp" -> if (arguments.size == 2) SmtSort.BitVector(1) else null
        "bvredor", "bvredand" -> if (arguments.size == 1) SmtSort.BitVector(1) else null
        "ubv_to_int", "sbv_to_int", "bv2nat", "bv2int" -> if (arguments.size == 1) SmtSort.Int else null
        else -> null
    }
}

internal fun smtIndexedBitVectorSort(indexed: SExpr.SList, arguments: List<SmtSort>): SmtSort? {
    if ((indexed.items.firstOrNull() as? SExpr.Atom)?.text != "_") return null
    val operator = (indexed.items.getOrNull(1) as? SExpr.Atom)?.text ?: return null
    val indices = indexed.items.drop(2).map {
        val text = (it as? SExpr.Atom)?.text ?: return null
        if (text.isEmpty() || text.any { digit -> digit !in '0'..'9' }) return null
        text
    }
    if (operator.startsWith("bv") && operator.drop(2).let {
            it.isNotEmpty() && it.all { digit -> digit in '0'..'9' }
        }) {
        if (arguments.isNotEmpty() || indices.size != 1) return null
        val sort = bitVectorSort(indices[0].toLongOrNull() ?: return null) ?: return null
        if (parseBigInt(operator.drop(2)).magnitudeBitLength() > sort.width) return null
        return sort
    }
    if (operator == "int_to_bv" || operator == "int2bv") {
        if (arguments != listOf(SmtSort.Int) || indices.size != 1) return null
        return bitVectorSort(indices[0].toLongOrNull() ?: return null)
    }
    val value = arguments.singleOrNull() as? SmtSort.BitVector ?: return null
    if (operator == "rotate_left" || operator == "rotate_right") return if (indices.size == 1) value else null
    val numbers = indices.map { it.toLongOrNull() ?: return null }
    return when (operator) {
        "extract" -> if (numbers.size == 2 && numbers[0] < value.width && numbers[0] >= numbers[1]) {
            bitVectorSort(numbers[0] - numbers[1] + 1)
        } else null
        "zero_extend", "sign_extend" -> if (numbers.size == 1 && numbers[0] <= Int.MAX_VALUE - value.width) {
            bitVectorSort(value.width + numbers[0])
        } else null
        "repeat" -> if (numbers.size == 1 && numbers[0] > 0 && numbers[0] <= Int.MAX_VALUE.toLong() / value.width) {
            bitVectorSort(value.width * numbers[0])
        } else null
        else -> null
    }
}

private fun bitVectorSort(width: Long): SmtSort.BitVector? =
    if (width in 1..Int.MAX_VALUE.toLong()) SmtSort.BitVector(width.toInt()) else null
