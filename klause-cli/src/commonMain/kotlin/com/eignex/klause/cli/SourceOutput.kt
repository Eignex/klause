package com.eignex.klause.cli

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.SemanticRational
import com.eignex.util.numbers.BIG_ONE

internal fun OutputProtocol.sourceQualified(
    semantics: EffectiveSourceSemantics?,
    comment: String,
    emit: (String) -> Unit = ::println,
): OutputProtocol {
    if (semantics == null || !semantics.hasOverrides) return this
    val delegate = this
    return object : OutputProtocol by delegate {
        override fun begin(optimize: Boolean, maximize: Boolean) {
            emit("$comment klause-source-semantics: ${sourcePolicyText(semantics)}")
            delegate.begin(optimize, maximize)
        }
    }
}

private fun sourcePolicyText(semantics: EffectiveSourceSemantics): String = buildList {
    val source = semantics.effective
    add("format=${source.format}")
    add("numeric=${source.numericAuthority}")
    add("overrides=true")
    when (val floats = source.floats) {
        null -> Unit
        FloatSemantics.Continuous -> add("floats=continuous")
    }
    source.undeclaredIntegers?.let {
        add("unbounded-int-lo=${it.lower ?: "open"}")
        add("unbounded-int-hi=${it.upper ?: "open"}")
    }
    source.undeclaredReals?.let {
        add("unbounded-real-lo=${it.lower.policyText()}")
        add("unbounded-real-hi=${it.upper.policyText()}")
    }
    source.feasibilityTolerance?.let {
        add("absolute-tolerance=${it.absolute.policyText()}")
        add("relative-tolerance=${it.relative.policyText()}")
    }
    source.optimalityTolerance?.let {
        add("absolute-optimality-tolerance=${it.absolute.policyText()}")
        add("relative-optimality-tolerance=${it.relative.policyText()}")
    }
    source.pinAbsentOptionalValues?.let { add("pin-absent-optional-values=$it") }
}.joinToString(" ")

private fun SemanticRational?.policyText(): String = when {
    this == null -> "open"
    denominator == BIG_ONE -> numerator.toString()
    else -> "$numerator/$denominator"
}
