package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntVars
import com.eignex.klause.ir.RealVars
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap

/** Exact `result = base^exponent`, including `0^0 = 1`, over typed source coordinates. */
class Power(
    /** Integer or real source operand. */
    val base: Coordinate,
    /** Fixed nonnegative integer exponent. */
    val exponent: Int,
    /** Result in the same numeric namespace as [base]. */
    val result: Coordinate,
) : Factor {
    /** A numeric coordinate whose namespace remains explicit through projection and remapping. */
    sealed interface Coordinate {
        /** Integer source coordinate, independent of finite or arithmetic ownership. */
        data class Integer(
            /** Source integer location. */
            val id: SourceIntId,
        ) : Coordinate
        /** Continuous source coordinate. */
        data class Real(
            /** Source real location. */
            val id: SourceRealId,
        ) : Coordinate
    }

    /** Finite or open integer source power. */
    constructor(base: Int, exponent: Int, result: Int) : this(
        Coordinate.Integer(SourceIntId(base)), exponent, Coordinate.Integer(SourceIntId(result)),
    )

    init {
        require(exponent >= 0) { "Power requires a nonnegative integer exponent" }
        require((base is Coordinate.Integer) == (result is Coordinate.Integer)) { "Power namespaces must agree" }
    }

    override val variables: VarList = when (base) {
        is Coordinate.Integer -> IntVars(
            intArrayOf(base.id.index, (result as Coordinate.Integer).id.index).distinct().toIntArray(),
        )
        is Coordinate.Real -> RealVars(
            intArrayOf(base.id.index, (result as Coordinate.Real).id.index).distinct().toIntArray(),
        )
    }

    override fun remap(mapping: VarRemap): Factor = Power(base.remap(mapping), exponent, result.remap(mapping))

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.POWER) {
        int(if (base is Coordinate.Integer) 0 else 1)
        int(variables.ints.firstOrNull() ?: variables.reals.first())
        int(when (result) {
            is Coordinate.Integer -> result.id.index
            is Coordinate.Real -> result.id.index
        })
        int(exponent)
    }

    private fun Coordinate.remap(mapping: VarRemap): Coordinate = when (this) {
        is Coordinate.Integer -> Coordinate.Integer(SourceIntId(mapping.int(id.index)))
        is Coordinate.Real -> Coordinate.Real(SourceRealId(mapping.real(id.index)))
    }
}
