package com.eignex.klause.lowering

internal interface BitVectorGates<T> {
    val zero: T
    val one: T
    fun not(value: T): T
    fun and(left: T, right: T): T
    fun or(left: T, right: T): T
    fun xor(left: T, right: T): T
    fun ite(condition: T, yes: T, no: T): T
}

internal class BitVectorCircuit<T>(private val gates: BitVectorGates<T>) {
    private val zero get() = gates.zero
    private val one get() = gates.one

    fun not(value: List<T>): List<T> = value.map(gates::not)

    fun and(left: List<T>, right: List<T>): List<T> = zip(left, right, gates::and)

    fun or(left: List<T>, right: List<T>): List<T> = zip(left, right, gates::or)

    fun xor(left: List<T>, right: List<T>): List<T> = zip(left, right, gates::xor)

    fun ite(condition: T, yes: List<T>, no: List<T>): List<T> = zip(yes, no) { a, b -> gates.ite(condition, a, b) }

    fun equal(left: List<T>, right: List<T>): T = xor(left, right).fold(one) { acc, bit -> gates.and(acc, gates.not(bit)) }

    fun unsignedLess(left: List<T>, right: List<T>): T {
        requireSameWidth(left, right)
        var less = zero
        for (i in left.indices) {
            less = gates.ite(gates.xor(left[i], right[i]), right[i], less)
        }
        return less
    }

    fun signedLess(left: List<T>, right: List<T>): T {
        requireSameWidth(left, right)
        return gates.ite(gates.xor(left.last(), right.last()), left.last(), unsignedLess(left, right))
    }

    fun add(left: List<T>, right: List<T>): List<T> = addWithCarry(left, right).first

    fun negate(value: List<T>): List<T> = addWithCarry(not(value), List(value.size) { zero }, one).first

    fun subtract(left: List<T>, right: List<T>): List<T> = addWithCarry(left, not(right), one).first

    fun multiply(left: List<T>, right: List<T>): List<T> {
        requireSameWidth(left, right)
        var result = List(left.size) { zero }
        for (i in right.indices) {
            val row = List(left.size) { j -> if (j < i) zero else gates.and(left[j - i], right[i]) }
            result = add(result, row)
        }
        return result
    }

    fun unsignedDivRem(left: List<T>, right: List<T>): Pair<List<T>, List<T>> {
        requireSameWidth(left, right)
        var remainder = List(left.size + 1) { zero }
        val divisor = right + zero
        val quotient = MutableList(left.size) { zero }
        for (i in left.indices.reversed()) {
            val shifted = listOf(left[i]) + remainder.dropLast(1)
            val take = gates.not(unsignedLess(shifted, divisor))
            remainder = ite(take, subtract(shifted, divisor), shifted)
            quotient[i] = take
        }
        return quotient to remainder.dropLast(1)
    }

    fun signedDivide(left: List<T>, right: List<T>): List<T> {
        val quotient = unsignedDivRem(magnitude(left), magnitude(right)).first
        return ite(gates.xor(left.last(), right.last()), negate(quotient), quotient)
    }

    fun signedRemainder(left: List<T>, right: List<T>): List<T> {
        val remainder = unsignedDivRem(magnitude(left), magnitude(right)).second
        return ite(left.last(), negate(remainder), remainder)
    }

    fun signedModulo(left: List<T>, right: List<T>): List<T> {
        val remainder = signedRemainder(left, right)
        val nonzero = gates.not(equal(remainder, List(remainder.size) { zero }))
        val differentSigns = gates.xor(left.last(), right.last())
        return ite(gates.and(nonzero, differentSigns), add(remainder, right), remainder)
    }

    fun shiftLeft(value: List<T>, amount: List<T>): List<T> = shift(value, amount, false, false)

    fun logicalShiftRight(value: List<T>, amount: List<T>): List<T> = shift(value, amount, true, false)

    fun arithmeticShiftRight(value: List<T>, amount: List<T>): List<T> = shift(value, amount, true, true)

    fun concat(high: List<T>, low: List<T>): List<T> {
        require(high.isNotEmpty() && low.isNotEmpty())
        require(high.size <= Int.MAX_VALUE - low.size)
        return low + high
    }

    fun extract(value: List<T>, high: Int, low: Int): List<T> {
        require(low >= 0 && high >= low && high < value.size)
        return value.subList(low, high + 1).toList()
    }

    fun extend(value: List<T>, count: Int, signed: Boolean): List<T> {
        require(value.isNotEmpty() && count >= 0 && count <= Int.MAX_VALUE - value.size)
        return value + List(count) { if (signed) value.last() else zero }
    }

    fun repeat(value: List<T>, count: Int): List<T> {
        require(value.isNotEmpty() && count > 0 && count <= Int.MAX_VALUE / value.size)
        return List(value.size * count) { value[it % value.size] }
    }

    fun rotate(value: List<T>, amount: Int, right: Boolean): List<T> {
        require(value.isNotEmpty() && amount >= 0)
        val distance = amount % value.size
        return List(value.size) { i ->
            val source = if (right) (i.toLong() + distance) % value.size else (i.toLong() + value.size - distance) % value.size
            value[source.toInt()]
        }
    }

    fun negateOverflow(value: List<T>): T {
        require(value.isNotEmpty())
        return gates.and(value.last(), value.dropLast(1).fold(one) { acc, bit -> gates.and(acc, gates.not(bit)) })
    }

    fun unsignedAddOverflow(left: List<T>, right: List<T>): T = addWithCarry(left, right).second

    fun signedAddOverflow(left: List<T>, right: List<T>): T {
        val sum = add(left, right)
        return gates.and(gates.not(gates.xor(left.last(), right.last())), gates.xor(left.last(), sum.last()))
    }

    fun unsignedSubtractOverflow(left: List<T>, right: List<T>): T = unsignedLess(left, right)

    fun signedSubtractOverflow(left: List<T>, right: List<T>): T {
        val difference = subtract(left, right)
        return gates.and(gates.xor(left.last(), right.last()), gates.xor(left.last(), difference.last()))
    }

    fun signedDivideOverflow(left: List<T>, right: List<T>): T {
        requireSameWidth(left, right)
        return gates.and(negateOverflow(left), right.fold(one, gates::and))
    }

    fun unsignedMultiplyOverflow(left: List<T>, right: List<T>): T {
        requireSameWidth(left, right)
        val product = multiply(extend(left, left.size, false), extend(right, right.size, false))
        return product.drop(left.size).fold(zero, gates::or)
    }

    fun signedMultiplyOverflow(left: List<T>, right: List<T>): T {
        requireSameWidth(left, right)
        val product = multiply(extend(left, left.size, true), extend(right, right.size, true))
        return product.drop(left.size).fold(zero) { acc, bit -> gates.or(acc, gates.xor(bit, product[left.size - 1])) }
    }

    private fun magnitude(value: List<T>): List<T> = ite(value.last(), negate(value), value)

    private fun shift(value: List<T>, amount: List<T>, right: Boolean, signed: Boolean): List<T> {
        requireSameWidth(value, amount)
        var result = value
        var distance = 1
        var index = 0
        val fill = if (signed) value.last() else zero
        while (index < amount.size && distance < value.size) {
            val bit = amount[index++]
            val shifted = List(value.size) { i ->
                val source = if (right) i.toLong() + distance else i.toLong() - distance
                if (source in 0L until value.size.toLong()) result[source.toInt()] else fill
            }
            result = ite(bit, shifted, result)
            distance = minOf(value.size.toLong(), distance.toLong() * 2).toInt()
        }
        val oversized = amount.drop(index).fold(zero, gates::or)
        return ite(oversized, List(value.size) { fill }, result)
    }

    private fun addWithCarry(left: List<T>, right: List<T>, initialCarry: T = zero): Pair<List<T>, T> {
        requireSameWidth(left, right)
        var carry = initialCarry
        val sum = List(left.size) { i ->
            val differing = gates.xor(left[i], right[i])
            val bit = gates.xor(differing, carry)
            carry = gates.or(gates.and(left[i], right[i]), gates.and(differing, carry))
            bit
        }
        return sum to carry
    }

    private fun zip(left: List<T>, right: List<T>, operation: (T, T) -> T): List<T> {
        requireSameWidth(left, right)
        return List(left.size) { operation(left[it], right[it]) }
    }

    private fun requireSameWidth(left: List<T>, right: List<T>) {
        require(left.isNotEmpty() && left.size == right.size)
    }
}
