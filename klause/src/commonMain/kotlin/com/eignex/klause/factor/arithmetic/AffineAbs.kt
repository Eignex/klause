package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntVars
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.WideConstants
import com.eignex.klause.ir.WideConsts
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.abs
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.unaryMinus

internal class AbsAffineValue(coefficients: Map<Int, BigInt>, val constant: BigInt) {
    val coefficients: Map<Int, BigInt> = coefficients.entries.filter { it.value != BIG_ZERO }
        .sortedBy { it.key }.associate { it.key to it.value }

    init {
        require(this.coefficients.keys.all { it >= 0 })
    }

    fun value(at: (Int) -> Long): BigInt = exactValue { bigIntOf(at(it)) }

    fun exactValue(at: (Int) -> BigInt): BigInt {
        var result = constant
        for ((variable, coefficient) in coefficients) result += coefficient * at(variable)
        return result
    }

    fun remap(mapping: VarRemap): AbsAffineValue {
        val renamed = LinkedHashMap<Int, BigInt>()
        for ((variable, coefficient) in coefficients) {
            val target = mapping.int(variable)
            renamed[target] = (renamed[target] ?: BIG_ZERO) + coefficient
        }
        return AbsAffineValue(renamed, constant)
    }

    fun comparedTo(other: AbsAffineValue, sign: BigInt, op: LinearOp): LinearRow {
        val terms = LinkedHashMap(coefficients)
        for ((variable, coefficient) in other.coefficients) {
            terms[variable] = (terms[variable] ?: BIG_ZERO) - sign * coefficient
        }
        val kept = terms.filterValues { it != BIG_ZERO }
        val direction = if (op == LinearOp.GE) -BIG_ONE else BIG_ONE
        return LinearRow.ofColumns(kept.keys.toIntArray(), intArrayOf(),
            WideConstants(WideConsts(kept.values.map { direction * it }.toTypedArray()),
                direction * (sign * other.constant - constant)),
            if (op == LinearOp.GE) LinearOp.LE else op)
    }

    private val constantDigits = constant.toString().map { it.code }.toIntArray()
    private val coefficientDigits = this.coefficients.values.map { value ->
        value.toString().map { it.code }.toIntArray()
    }
    val keyWeight: Int = constantDigits.size + coefficientDigits.sumOf { it.size + 1 }

    fun buildKey(sink: KeySink) {
        sink.int(coefficients.size)
        for ((index, variable) in coefficients.keys.withIndex()) {
            sink.intVar(variable)
            sink.constInts(coefficientDigits[index])
        }
        sink.constInts(constantDigits)
    }
}

internal class AffineAbs(val operand: AbsAffineValue, val result: AbsAffineValue) : Factor {
    override val variables: VarList = IntVars((operand.coefficients.keys + result.coefficients.keys)
        .distinct().sorted().toIntArray())

    override fun remap(mapping: VarRemap): Factor = AffineAbs(operand.remap(mapping), result.remap(mapping))

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.AFFINE_ABS) {
        operand.buildKey(it)
        result.buildKey(it)
    }

    fun isSatisfied(at: (Int) -> BigInt): Boolean = operand.exactValue(at).abs() == result.exactValue(at)

    override val structuralKeyWeight: Int get() = operand.keyWeight + result.keyWeight + 2

    override val linearForm: LinearForm = LinearForm.Relaxation(listOf(
        result.comparedTo(operand, BIG_ONE, LinearOp.GE),
        result.comparedTo(operand, -BIG_ONE, LinearOp.GE),
    ))
}
