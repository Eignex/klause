package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.RealVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey

/** Exact membership of one source real in a finite set of authoritative binary64 values. */
class RealFiniteDomain(
    /** Coordinate in the source real namespace. */
    val variable: Int,
    values: DoubleArray,
) : Factor {
    init {
        require(variable >= 0) { "Finite real domain coordinate must be nonnegative" }
        require(values.all { it.isFinite() }) { "Finite real domain values must be finite" }
    }

    private val captured = values.map { if (it == 0.0) 0.0 else it }.distinct().sorted().toDoubleArray()

    /** Independent copy of the mathematical set in increasing order. */
    val values: DoubleArray get() = captured.copyOf()

    /** Number of distinct mathematical values; zero denotes a contradiction. */
    val size: Int get() = captured.size

    internal fun valueAt(index: Int): Double = captured[index]

    override val variables: RealVars = RealVars(intArrayOf(variable))

    override fun remap(mapping: VarRemap): Factor = RealFiniteDomain(mapping.real(variable), captured)

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REAL_FINITE_DOMAIN, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.REAL_FINITE_DOMAIN, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.realVar(variable)
        sink.int(captured.size)
        for (value in captured) sink.long(value.toBits())
    }

    override val structuralKeyWeight: Int get() = captured.size + 2
}
