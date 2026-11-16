package com.eignex.klause.formats.smtlib

import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.parseBigInt
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.rem
import com.eignex.util.numbers.shl
import com.eignex.util.numbers.shr
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toLongExact
import com.eignex.util.numbers.unaryMinus

internal sealed interface SmtBitVectorCoordinate {
    data class Bits(val names: List<String>) : SmtBitVectorCoordinate
    data class Boolean(val name: String) : SmtBitVectorCoordinate
    data class Integer(val name: String) : SmtBitVectorCoordinate
}

internal sealed interface SmtBitVectorValue {
    data class Bits(val bits: List<kotlin.Boolean>) : SmtBitVectorValue
    data class Boolean(val value: kotlin.Boolean) : SmtBitVectorValue
    data class Integer(val value: BigInt) : SmtBitVectorValue
}

internal class SmtBitVectorCheck(
    private val operator: String,
    private val indices: List<Int>,
    private val arguments: List<SmtBitVectorCoordinate>,
    private val result: SmtBitVectorCoordinate,
) {
    fun check(boolean: (String) -> Boolean, integer: (String) -> String): Boolean {
        fun bool(name: String): Boolean = when (name) {
            "true" -> true
            "false" -> false
            else -> boolean(name)
        }
        fun value(coordinate: SmtBitVectorCoordinate): SmtBitVectorValue = when (coordinate) {
            is SmtBitVectorCoordinate.Bits -> SmtBitVectorValue.Bits(coordinate.names.map(::bool))
            is SmtBitVectorCoordinate.Boolean -> SmtBitVectorValue.Boolean(bool(coordinate.name))
            is SmtBitVectorCoordinate.Integer -> SmtBitVectorValue.Integer(parseBigInt(integer(coordinate.name)))
        }
        return value(result) == evaluateSmtBitVector(operator, indices, arguments.map(::value))
    }
}

internal fun evaluateSmtBitVector(operator: String, indices: List<Int>, arguments: List<SmtBitVectorValue>): SmtBitVectorValue {
    fun number(value: List<Boolean>): BigInt = value.asReversed().fold(BIG_ZERO) { acc, bit ->
        (acc shl 1) + if (bit) BIG_ONE else BIG_ZERO
    }
    val bitArguments = arguments.filterIsInstance<SmtBitVectorValue.Bits>().map { it.bits }
    val leftBits = bitArguments.firstOrNull().orEmpty()
    val rightBits = bitArguments.getOrNull(1) ?: leftBits
    val width = leftBits.size
    val modulus = BIG_ONE shl width
    val left = number(leftBits)
    val right = number(rightBits)
    fun signed(value: BigInt, bits: List<Boolean>): BigInt = if (bits.last()) value - (BIG_ONE shl bits.size) else value
    fun bitwise(operation: (Boolean, Boolean) -> Boolean): BigInt = number(leftBits.indices.map { operation(leftBits[it], rightBits[it]) })
    fun signedOverflow(value: BigInt): Boolean = value < -(modulus shr 1) || value >= modulus shr 1
    val predicate = when (operator) {
        "=" -> bitArguments.map(::number).distinct().size == 1
        "distinct" -> bitArguments.map(::number).distinct().size == bitArguments.size
        "bvult" -> left < right
        "bvule" -> left <= right
        "bvugt" -> left > right
        "bvuge" -> left >= right
        "bvslt" -> signed(left, leftBits) < signed(right, rightBits)
        "bvsle" -> signed(left, leftBits) <= signed(right, rightBits)
        "bvsgt" -> signed(left, leftBits) > signed(right, rightBits)
        "bvsge" -> signed(left, leftBits) >= signed(right, rightBits)
        "bvnego" -> signed(left, leftBits) == -(modulus shr 1)
        "bvuaddo" -> left + right >= modulus
        "bvsaddo" -> signedOverflow(signed(left, leftBits) + signed(right, rightBits))
        "bvumulo" -> left * right >= modulus
        "bvsmulo" -> signedOverflow(signed(left, leftBits) * signed(right, rightBits))
        else -> null
    }
    if (predicate != null) return SmtBitVectorValue.Boolean(predicate)
    if (operator in setOf("ubv_to_int", "sbv_to_int", "bv2nat", "bv2int")) {
        return SmtBitVectorValue.Integer(if (operator == "sbv_to_int") signed(left, leftBits) else left)
    }
    val expected = when (operator) {
        "bvnot" -> modulus - BIG_ONE - left
        "bvneg" -> -left
        "bvand" -> number(leftBits.indices.map { bitArguments.all { bits -> bits[it] } })
        "bvor" -> number(leftBits.indices.map { bitArguments.any { bits -> bits[it] } })
        "bvxor" -> bitwise { a, b -> a != b }
        "bvnand" -> modulus - BIG_ONE - bitwise { a, b -> a && b }
        "bvnor" -> modulus - BIG_ONE - bitwise { a, b -> a || b }
        "bvxnor" -> bitwise { a, b -> a == b }
        "bvadd" -> bitArguments.fold(BIG_ZERO) { acc, bits -> acc + number(bits) }
        "bvsub" -> left - right
        "bvmul" -> bitArguments.fold(BIG_ONE) { acc, bits -> acc * number(bits) }
        "bvudiv" -> if (right == BIG_ZERO) modulus - BIG_ONE else left / right
        "bvurem" -> if (right == BIG_ZERO) left else left % right
        "bvsdiv" -> {
            val a = signed(left, leftBits)
            val b = signed(right, rightBits)
            if (b == BIG_ZERO) if (a < BIG_ZERO) BIG_ONE else -BIG_ONE else a / b
        }
        "bvsrem", "bvsmod" -> {
            val a = signed(left, leftBits)
            val b = signed(right, rightBits)
            val remainder = if (b == BIG_ZERO) a else a % b
            if (operator == "bvsmod" && remainder != BIG_ZERO && (a < BIG_ZERO) != (b < BIG_ZERO)) remainder + b else remainder
        }
        "bvshl" -> if (right >= bigIntOf(width)) BIG_ZERO else left shl right.toLongExact().toInt()
        "bvlshr" -> if (right >= bigIntOf(width)) BIG_ZERO else left shr right.toLongExact().toInt()
        "bvashr" -> {
            val a = signed(left, leftBits)
            if (right >= bigIntOf(width)) if (a < BIG_ZERO) -BIG_ONE else BIG_ZERO else a shr right.toLongExact().toInt()
        }
        "bvcomp" -> if (left == right) BIG_ONE else BIG_ZERO
        "bvredor" -> if (leftBits.any { it }) BIG_ONE else BIG_ZERO
        "bvredand" -> if (leftBits.all { it }) BIG_ONE else BIG_ZERO
        "concat" -> (left shl rightBits.size) + right
        "extract" -> left shr indices[1]
        "zero_extend" -> left
        "sign_extend" -> signed(left, leftBits)
        "repeat" -> {
            var value = BIG_ZERO
            repeat(indices[0]) { value = (value shl width) + left }
            value
        }
        "rotate_left", "rotate_right" -> {
            val distance = indices[0] % width
            if (operator == "rotate_left") (left shl distance) + (left shr (width - distance))
            else (left shr distance) + ((left % (BIG_ONE shl distance)) shl (width - distance))
        }
        "int_to_bv", "int2bv" -> (arguments.single() as SmtBitVectorValue.Integer).value
        "ite" -> if ((arguments[0] as SmtBitVectorValue.Boolean).value) left else right
        else -> error("unexpected bitvector operation '$operator'")
    }
    val outputWidth = when (operator) {
        "bvcomp", "bvredor", "bvredand" -> 1
        "concat" -> width + rightBits.size
        "extract" -> indices[0] - indices[1] + 1
        "zero_extend", "sign_extend" -> width + indices[0]
        "repeat" -> width * indices[0]
        "int_to_bv", "int2bv" -> indices[0]
        else -> width
    }
    val range = BIG_ONE shl outputWidth
    val remainder = expected % range
    var normalized = if (remainder < BIG_ZERO) remainder + range else remainder
    return SmtBitVectorValue.Bits(List(outputWidth) {
        val bit = normalized % bigIntOf(2) != BIG_ZERO
        normalized /= bigIntOf(2)
        bit
    })
}
