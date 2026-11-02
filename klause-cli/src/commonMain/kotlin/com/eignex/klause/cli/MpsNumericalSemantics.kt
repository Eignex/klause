package com.eignex.klause.cli

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceSemantics

internal fun mpsNumericalSemantics(semantics: EffectiveSourceSemantics): String =
    "{\"schema\":\"mps-decimal-semantics-v1\",\"declared\":${mpsPolicy(semantics.declared)}," +
        "\"effective\":${mpsPolicy(semantics.effective)}}"

private fun mpsPolicy(policy: SourceSemantics): String {
    val tolerance = checkNotNull(policy.feasibilityTolerance)
    val integers = checkNotNull(policy.undeclaredIntegers)
    val reals = checkNotNull(policy.undeclaredReals)
    fun rational(value: SemanticRational?): String = value?.let {
        "\"${it.numerator}/${it.denominator}\""
    } ?: "null"
    fun integer(value: Long?): String = value?.let { "\"$it\"" } ?: "null"
    return "{\"numericAuthority\":\"${policy.numericAuthority}\"," +
        "\"feasibilityTolerance\":{\"absolute\":${rational(tolerance.absolute)}," +
        "\"relative\":${rational(tolerance.relative)}}," +
        "\"undeclaredIntegers\":{\"lower\":${integer(integers.lower)},\"upper\":${integer(integers.upper)}}," +
        "\"undeclaredReals\":{\"lower\":${rational(reals.lower)},\"upper\":${rational(reals.upper)}}," +
        "\"integrality\":\"exact\",\"objective\":\"exact\"}"
}
