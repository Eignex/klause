package com.eignex.klause.cli

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.util.BIG_ONE

internal fun OutputProtocol.sourceQualified(
    semantics: EffectiveSourceSemantics?,
    comment: String,
): OutputProtocol {
    if (semantics == null || !semantics.hasOverrides) return this
    val delegate = this
    return object : OutputProtocol by delegate {
        override fun begin(optimize: Boolean, maximize: Boolean) {
            println("$comment klause-source-semantics: ${sourcePolicyText(semantics)}")
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
        is FloatSemantics.Grid -> {
            add("floats=grid")
            add("buckets=${floats.buckets}")
            add("scale=${floats.linearScale}")
            add("grid-lo=${floats.unrangedBounds.lower.policyText()}")
            add("grid-hi=${floats.unrangedBounds.upper.policyText()}")
        }
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
    source.pinAbsentOptionalValues?.let { add("pin-absent-optional-values=$it") }
}.joinToString(" ")

private fun SemanticRational?.policyText(): String = when {
    this == null -> "open"
    denominator == BIG_ONE -> numerator.toString()
    else -> "$numerator/$denominator"
}
