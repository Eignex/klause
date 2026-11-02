package com.eignex.klause.cli

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceSemantics

internal fun mpsNumericalSemantics(semantics: EffectiveSourceSemantics): String = buildString {
    append("{\"schema\":\"mps-decimal-semantics-v1\",\"declared\":")
    appendMpsPolicy(semantics.declared)
    append(",\"effective\":")
    appendMpsPolicy(semantics.effective)
    append('}')
}

private fun StringBuilder.appendMpsPolicy(policy: SourceSemantics) {
    val tolerance = checkNotNull(policy.feasibilityTolerance)
    val integers = checkNotNull(policy.undeclaredIntegers)
    val reals = checkNotNull(policy.undeclaredReals)
    append("{\"numericAuthority\":\"").append(policy.numericAuthority)
    append("\",\"feasibilityTolerance\":{\"absolute\":")
    appendMpsRational(tolerance.absolute)
    append(",\"relative\":")
    appendMpsRational(tolerance.relative)
    append("},\"undeclaredIntegers\":{\"lower\":")
    appendMpsInteger(integers.lower)
    append(",\"upper\":")
    appendMpsInteger(integers.upper)
    append("},\"undeclaredReals\":{\"lower\":")
    appendMpsRational(reals.lower)
    append(",\"upper\":")
    appendMpsRational(reals.upper)
    append("},\"integrality\":\"exact\",\"objective\":\"exact\"}")
}

private fun StringBuilder.appendMpsRational(value: SemanticRational?) {
    if (value == null) append("null")
    else append('"').append(value.numerator).append('/').append(value.denominator).append('"')
}

private fun StringBuilder.appendMpsInteger(value: Long?) {
    if (value == null) append("null") else append('"').append(value).append('"')
}
