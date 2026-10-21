package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.lowering.substituteBinaryArithmetic

internal fun Compiler.Builder.substituteBinaryHelpers(
    objective: LinearObjectiveSpec?,
    removed: SmtLibConditionalEquality.UnusedColumns,
): SmtLibConditionalEquality.UnusedColumns {
    fun spend() {
        if (sourceCancellation()) throw PolynomialSourceInterrupted()
        sourceCancellation.charge(1)
        if (sourceCancellation()) throw PolynomialSourceInterrupted()
    }
    val protected = HashSet<Int>()
    for (variable in intNames.values) {
        spend()
        protected.add(variable)
    }
    objective?.intCoefficientValues?.forEachIndexed { variable, coefficient ->
        spend()
        if (coefficient != 0L) protected.add(variable)
    }
    val binary = HashSet<Int>()
    val fixedValues = HashMap<Int, Long>()
    for (variable in intDomains.indices) {
        spend()
        val domain = (intDomains[variable] as? PresolveDomain.Finite)?.domain ?: continue
        if (domain.min == 0L && domain.max == 1L && domain.holeCount == 0L) binary.add(variable)
        if (domain.min == domain.max) fixedValues[variable] = domain.min
    }
    val eligible = binary.filterTo(HashSet()) { it !in protected && it !in removed.ints }
    val substituted = substituteBinaryArithmetic(factors, eligible, binary, fixedValues, sourceCancellation)
    return SmtLibConditionalEquality.UnusedColumns(removed.ints + substituted, removed.bools)
}
