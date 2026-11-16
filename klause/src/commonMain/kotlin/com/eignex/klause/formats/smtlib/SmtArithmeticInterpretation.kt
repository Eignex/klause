package com.eignex.klause.formats.smtlib

internal class SmtArithmeticInterpretation(private val applications: List<SmtArithmeticApplication>,
    private val resultIndex: Int, private val real: Boolean) : SmtReconstruction {
    override fun reconstruct(values: SmtModelValues): SmtValue {
        val sort = if (real) SmtSort.Real else SmtSort.Int
        val zero = if (real) SmtValue.Real("0.0") else SmtValue.Integer("0")
        val entries = ArrayList<Pair<List<SmtValue>, SmtValue>>()
        for (application in applications) {
            values.checkpoint()
            if (application.activation?.let { !values.boolean(it) } == true) continue
            val coordinates = application.coordinates
            val denominator = values.value(coordinates[1])
            val isZero = values.equal(denominator, zero) ?: throw SmtSourceCheckingUnavailable()
            if (isZero) entries += listOf(values.value(coordinates[0]), denominator) to
                values.value(coordinates[resultIndex])
        }
        return SmtValue.Function(listOf(sort, sort), entries, zero)
    }
}
