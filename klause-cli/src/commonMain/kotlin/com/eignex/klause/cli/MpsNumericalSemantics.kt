package com.eignex.klause.cli

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceSemantics

// The key set of each policy object is the `mps-decimal-semantics-v1` contract: a reader that checks source
// points against it rejects an object with any other key, so the optimality gap is declared on its own line.
internal fun mpsNumericalSemantics(semantics: EffectiveSourceSemantics): String =
    "{\"schema\":\"mps-decimal-semantics-v1\",\"declared\":${mpsPolicy(semantics.declared)}," +
        "\"effective\":${mpsPolicy(semantics.effective)}}"

internal fun mpsOptimalityTolerance(semantics: EffectiveSourceSemantics): String =
    "{\"declared\":${mpsGap(semantics.declared)},\"effective\":${mpsGap(semantics.effective)}}"

private fun rational(value: SemanticRational?): String = value?.let {
    "\"${it.numerator}/${it.denominator}\""
} ?: "null"

private fun mpsGap(policy: SourceSemantics): String = policy.optimalityTolerance?.let {
    "{\"absolute\":${rational(it.absolute)},\"relative\":${rational(it.relative)}}"
} ?: "null"

private fun mpsPolicy(policy: SourceSemantics): String {
    val tolerance = checkNotNull(policy.feasibilityTolerance)
    val integers = checkNotNull(policy.undeclaredIntegers)
    val reals = checkNotNull(policy.undeclaredReals)
    fun integer(value: Long?): String = value?.let { "\"$it\"" } ?: "null"
    return "{\"numericAuthority\":\"${policy.numericAuthority}\"," +
        "\"feasibilityTolerance\":{\"absolute\":${rational(tolerance.absolute)}," +
        "\"relative\":${rational(tolerance.relative)}}," +
        "\"undeclaredIntegers\":{\"lower\":${integer(integers.lower)},\"upper\":${integer(integers.upper)}}," +
        "\"undeclaredReals\":{\"lower\":${rational(reals.lower)},\"upper\":${rational(reals.upper)}}," +
        "\"integrality\":\"exact\",\"objective\":\"exact\"}"
}
