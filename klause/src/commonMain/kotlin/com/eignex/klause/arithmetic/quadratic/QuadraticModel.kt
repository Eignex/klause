package com.eignex.klause.arithmetic.quadratic

import com.eignex.klause.simplex.exact.BigFraction

internal enum class QuadraticRowRelation { LE, LT, EQ }

internal class QuadraticRow(coefficients: List<BigFraction>, val rhs: BigFraction, val relation: QuadraticRowRelation) {
    private val captured = coefficients.toList()
    val coefficients: List<BigFraction> get() = captured.toList()
}

internal class QuadraticModel(
    linear: List<BigFraction>,
    hessian: List<List<BigFraction>>,
    val constant: BigFraction = BigFraction.ZERO,
    rows: List<QuadraticRow> = emptyList(),
) {
    private val capturedLinear = linear.toList()
    private val capturedHessian = hessian.map { it.toList() }
    private val capturedRows = rows.toList()
    val linear: List<BigFraction> get() = capturedLinear.toList()
    val hessian: List<List<BigFraction>> get() = capturedHessian.map { it.toList() }
    val rows: List<QuadraticRow> get() = capturedRows.toList()
    val dimension: Int get() = capturedLinear.size

    init {
        require(capturedHessian.size == dimension && capturedHessian.all { it.size == dimension })
        require(capturedRows.all { it.coefficients.size == dimension })
        require(capturedHessian.indices.all { i ->
            capturedHessian.indices.all { j -> capturedHessian[i][j] == capturedHessian[j][i] }
        })
    }
}

internal class QuadraticStationaryCertificate(anchor: List<BigFraction>, multipliers: List<BigFraction>) {
    private val capturedAnchor = anchor.toList()
    private val capturedMultipliers = multipliers.toList()
    val anchor: List<BigFraction> get() = capturedAnchor.toList()
    val multipliers: List<BigFraction> get() = capturedMultipliers.toList()
}

internal class CheckedQuadraticBound internal constructor(
    val model: QuadraticModel,
    val value: BigFraction,
    val certificate: QuadraticStationaryCertificate,
)

internal class CheckedQuadraticOptimum internal constructor(val bound: CheckedQuadraticBound) {
    val point: List<BigFraction> get() = bound.certificate.anchor.toList()
}

internal class CheckedQuadraticInfimum internal constructor(
    val bound: CheckedQuadraticBound,
    direction: List<BigFraction>,
    val step: BigFraction,
) {
    private val capturedDirection = direction.toList()
    val direction: List<BigFraction> get() = capturedDirection.toList()
}

internal class CheckedQuadraticUnbounded internal constructor(
    val model: QuadraticModel,
    base: List<BigFraction>,
    direction: List<BigFraction>,
) {
    private val capturedBase = base.toList()
    private val capturedDirection = direction.toList()
    val base: List<BigFraction> get() = capturedBase.toList()
    val direction: List<BigFraction> get() = capturedDirection.toList()
}
