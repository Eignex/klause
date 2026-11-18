package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.validation.RealToleranceScope
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.parseBigInt
import com.eignex.util.numbers.toLongExact
import kotlin.math.nextDown
import kotlin.math.nextUp

// Decimal output is consumed both as binary64 and as exact decimal text. A whole point must pass both readings:
// moving one coordinate into a bound can move a coupled row outside its slack.
internal fun FlatZincProgram.publishedReals(
    boolValue: (Int) -> Boolean,
    intValue: (Int) -> String,
    nearest: (Int) -> Double,
    control: ExecutionControl,
): DoubleArray? {
    val policy = semantics.effective.feasibilityTolerance ?: return null
    if (policy.isExact || problem.numRealVars == 0) return null
    val scope = RealToleranceScope.of(problem)
    val bools = BooleanArray(problem.numBoolVars, boolValue)
    val exactInts = List(problem.numIntVars) { parseBigInt(intValue(it)) }
    val ints = if (exactInts.all { it.fitsLong() }) LongArray(exactInts.size) { exactInts[it].toLongExact() }
    else LongArray(0)
    val original = DoubleArray(problem.numRealVars, nearest)
    fun accepted(values: DoubleArray): Boolean {
        if (control() || values.any { !it.isFinite() }) return false
        val binary = Sample(bools, ints, values, exactInts = exactInts)
        if (scope.verify(problem, binary, policy, control, integerRows = false) !is Verification.Accepted) return false
        val decimals = values.map { decimalValue(it) }
        val decimal = Sample(bools, ints, DoubleArray(values.size) { decimals[it].toDouble() },
            exactReals = decimals, exactInts = exactInts)
        return scope.verify(problem, decimal, policy, control, integerRows = false) is Verification.Accepted
    }
    if (accepted(original)) return original
    val options = original.map { doubleArrayOf(it, it.nextUp(), it.nextDown()) }
    for (direction in 1..2) {
        val values = DoubleArray(original.size) { options[it][direction] }
        if (accepted(values)) return values
    }
    // Try isolated changes before combinations, so an unrelated long prefix does not hide a single boundary.
    for (id in original.indices) {
        for (direction in 1..2) {
            val values = original.copyOf()
            values[id] = options[id][direction]
            if (accepted(values)) return values
            if (control()) publicationUnavailable()
        }
    }
    val choices = IntArray(original.size)
    val values = original.copyOf()
    while (!control()) {
        var id = 0
        while (id < choices.size && choices[id] == 2) {
            choices[id] = 0
            values[id] = original[id]
            id++
        }
        if (id == choices.size) break
        choices[id]++
        values[id] = options[id][choices[id]]
        if (accepted(values)) return values
    }
    publicationUnavailable()
}

private fun publicationUnavailable(): Nothing =
    throw IllegalArgumentException("FlatZinc decimal publication unavailable within the declared feasibility tolerance")

// Double.toString emits at most seventeen significant digits and a bounded decimal exponent.
internal fun decimalValue(value: Double): BigFraction {
    val parts = value.toString().lowercase().split('e')
    val mantissa = parts[0]
    val point = mantissa.indexOf('.')
    val scale = (parts.getOrNull(1)?.toInt() ?: 0) - if (point < 0) 0 else mantissa.length - point - 1
    val numerator = parseBigInt(mantissa.replace(".", ""))
    val power = parseBigInt("1" + "0".repeat(kotlin.math.abs(scale)))
    return if (scale < 0) BigFraction.of(numerator, power)
    else BigFraction.of(numerator, BIG_ONE) * BigFraction.of(power, BIG_ONE)
}
