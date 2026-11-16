package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.RealVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap

/**
 * Exact continuous equality `result = a * b`, with every ID in the source real namespace.
 * Aliasing in any role is valid: `a == b` expresses a square. Products can share results and
 * operands to form chains. Exact rational or algebraic theory witnesses enforce this equality;
 * floating scores cannot certify it. Frontend variants retain declared finite operand values and their
 * exact choice guards; selected linear consumers can enforce those variants under the captured guards.
 */
class ContinuousProduct private constructor(
    /** First real operand ID. */
    val a: Int,
    /** Second real operand ID. */
    val b: Int,
    /** Real result ID. */
    val result: Int,
    internal val finiteChoices: ContinuousProductChoices?,
) : Factor {
    /** Construct an unrestricted exact real product. */
    constructor(a: Int, b: Int, result: Int) : this(a, b, result, null)

    internal constructor(a: Int, b: Int, result: Int, operand: Int, values: DoubleArray, guards: IntArray? = null) :
        this(a, b, result, ContinuousProductChoices(operand, values, guards))

    init {
        require(a >= 0 && b >= 0 && result >= 0) { "Continuous product IDs must be nonnegative" }
        require(finiteChoices == null || finiteChoices.operand == a || finiteChoices.operand == b)
    }

    override val variables: VarList = finiteChoices?.takeIf { it.hasGuards }?.let { choices ->
        MixedVars(boolVars = IntArray(choices.size) { Lit.variable(choices.literalAt(it)) }.distinct().toIntArray(),
            reals = intArrayOf(a, b, result).distinct().toIntArray())
    } ?: RealVars(intArrayOf(a, b, result).distinct().toIntArray())

    override fun remap(mapping: VarRemap): Factor =
        ContinuousProduct(mapping.real(a), mapping.real(b), mapping.real(result), finiteChoices?.remap(mapping))

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.CONTINUOUS_PRODUCT) {
        int(minOf(a, b))
        int(maxOf(a, b))
        int(result)
        finiteChoices?.let { choices ->
            int(choices.operand)
            int(choices.size)
            int(if (choices.hasGuards) 1 else 0)
            for (index in 0 until choices.size) {
                long(choices.valueAt(index).toRawBits())
                if (choices.hasGuards) int(choices.literalAt(index))
            }
        }
    }

    override val structuralKeyWeight: Int get() =
        3 + (finiteChoices?.let { 3 + it.size * (if (it.hasGuards) 2 else 1) } ?: 0)
}

internal class ContinuousProductChoices(val operand: Int, values: DoubleArray, guards: IntArray? = null) {
    init {
        require(values.all { it.isFinite() })
        require(guards == null || (guards.size == values.size && guards.all { it >= 0 }))
    }

    private val entries = values.indices.map { (if (values[it] == 0.0) 0.0 else values[it]) to guards?.get(it) }
        .distinct().sortedWith(compareBy({ it.first }, { it.second }))
    private val captured = DoubleArray(entries.size) { entries[it].first }
    private val literals = guards?.let { IntArray(entries.size) { checkNotNull(entries[it].second) } }

    val size: Int get() = captured.size
    val hasGuards: Boolean get() = literals != null

    fun valueAt(index: Int): Double = captured[index]
    fun literalAt(index: Int): Int = checkNotNull(literals)[index]

    fun remap(mapping: VarRemap): ContinuousProductChoices = ContinuousProductChoices(mapping.real(operand), captured,
        literals?.let { source -> IntArray(source.size) {
            Lit.make(mapping.bool(Lit.variable(source[it])), Lit.isPositive(source[it]))
        } })

    fun nearest(value: Double): Double {
        if (!value.isFinite() || captured.isEmpty()) return Double.NaN
        val found = entries.binarySearch { it.first.compareTo(value) }
        if (found >= 0) return captured[found]
        val next = -found - 1
        if (next == 0) return captured.first()
        if (next == captured.size) return captured.last()
        return if (value - captured[next - 1] <= captured[next] - value) captured[next - 1] else captured[next]
    }
}
