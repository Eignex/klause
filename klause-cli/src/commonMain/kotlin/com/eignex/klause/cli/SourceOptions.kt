package com.eignex.klause.cli

import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.parseBigInt
import com.eignex.util.numbers.times
import com.eignex.util.numbers.unaryMinus
import kotlin.math.abs

internal enum class SourceFloatMode { CONTINUOUS }

internal class SourceOptions {
    var linearToleranceEnvelope: Boolean? = null
    var floatMode: SourceFloatMode? = null
    var absoluteTolerance: SemanticRational? = null
    var relativeTolerance: SemanticRational? = null
    var absoluteOptimalityTolerance: SemanticRational? = null
    var relativeOptimalityTolerance: SemanticRational? = null
}

internal fun CommonOptions.sourceOverrides(declared: SourceSemantics): SemanticOverrides {
    var resolved = semanticOverrides
    val fields = sourceOptions
    require(fields.linearToleranceEnvelope != true || declared.format == SourceFormat.MPS) {
        "linear tolerance envelopes require an MPS source"
    }
    if (fields.floatMode == SourceFloatMode.CONTINUOUS) {
        resolved = resolved.copy(floats = FloatSemantics.Continuous)
    }
    if (fields.absoluteTolerance != null || fields.relativeTolerance != null) {
        val tolerance = resolved.feasibilityTolerance ?: if (exact && declared.feasibilityTolerance != null) {
            SourceTolerance()
        } else declared.feasibilityTolerance
        require(tolerance != null) { "source feasibility tolerance does not apply to ${declared.format}" }
        resolved = resolved.copy(feasibilityTolerance = SourceTolerance(
            fields.absoluteTolerance ?: tolerance.absolute,
            fields.relativeTolerance ?: tolerance.relative,
        ))
    }
    if (fields.absoluteOptimalityTolerance != null || fields.relativeOptimalityTolerance != null) {
        val gap = resolved.optimalityTolerance ?: if (exact && declared.optimalityTolerance != null) {
            OptimalityTolerance()
        } else declared.optimalityTolerance
        require(gap != null) { "source optimality tolerance does not apply to ${declared.format}" }
        resolved = resolved.copy(optimalityTolerance = OptimalityTolerance(
            fields.absoluteOptimalityTolerance ?: gap.absolute,
            fields.relativeOptimalityTolerance ?: gap.relative,
        ))
    }
    if (exact) {
        require((fields.absoluteTolerance == null || fields.absoluteTolerance == SemanticRational.ZERO) &&
            (fields.relativeTolerance == null || fields.relativeTolerance == SemanticRational.ZERO)
        ) {
            "positive source feasibility tolerance conflicts with --exact"
        }
        require(
            (fields.absoluteOptimalityTolerance ?: SemanticRational.ZERO) == SemanticRational.ZERO &&
                (fields.relativeOptimalityTolerance ?: SemanticRational.ZERO) == SemanticRational.ZERO,
        ) {
            "positive source optimality tolerance conflicts with --exact"
        }
        resolved = resolved.copy(
            floats = if (declared.floats == null) resolved.floats else FloatSemantics.Continuous,
            feasibilityTolerance = if (declared.feasibilityTolerance == null) resolved.feasibilityTolerance
            else SourceTolerance(),
            optimalityTolerance = if (declared.optimalityTolerance == null) resolved.optimalityTolerance
            else OptimalityTolerance(),
        )
    }
    sourceSemantics = declared.resolve(resolved)
    return resolved
}

internal fun sourceFlagSpecs(options: CommonOptions): List<FlagSpec> = listOf(
    sourceFlag("--source-tolerance-envelope", "true|false", "exact LP cuts over an absolute MPS tolerance envelope") {
        options.sourceOptions.linearToleranceEnvelope = when (it) {
            "true" -> true
            "false" -> false
            else -> usageError("--source-tolerance-envelope expects true or false")
        }
    },
    sourceFlag("--unbounded-int-lo", "integer|open", "default lower bound for undeclared integers") {
        val bounds = options.semanticOverrides.undeclaredIntegers ?: BoundsOverride()
        options.semanticOverrides = options.semanticOverrides.copy(undeclaredIntegers = bounds.copy(
            lower = integerBound(it),
        ))
    },
    sourceFlag("--unbounded-int-hi", "integer|open", "default upper bound for undeclared integers") {
        val bounds = options.semanticOverrides.undeclaredIntegers ?: BoundsOverride()
        options.semanticOverrides = options.semanticOverrides.copy(undeclaredIntegers = bounds.copy(
            upper = integerBound(it),
        ))
    },
    sourceFlag("--unbounded-real-lo", "number|open", "default lower bound for undeclared reals") {
        val bounds = options.semanticOverrides.undeclaredReals ?: BoundsOverride()
        options.semanticOverrides = options.semanticOverrides.copy(undeclaredReals = bounds.copy(
            lower = realBound(it),
        ))
    },
    sourceFlag("--unbounded-real-hi", "number|open", "default upper bound for undeclared reals") {
        val bounds = options.semanticOverrides.undeclaredReals ?: BoundsOverride()
        options.semanticOverrides = options.semanticOverrides.copy(undeclaredReals = bounds.copy(
            upper = realBound(it),
        ))
    },
    sourceFlag("--float-semantics", "continuous", "float declaration interpretation") {
        when (it.lowercase()) {
            "continuous" -> options.sourceOptions.floatMode = SourceFloatMode.CONTINUOUS
            else -> usageError("--float-semantics expects continuous")
        }
    },
    sourceFlag("--source-absolute-tolerance", "number", "absolute source feasibility slack") {
        options.sourceOptions.absoluteTolerance = semanticNumber(it)
    },
    sourceFlag("--source-relative-tolerance", "number", "relative source feasibility slack") {
        options.sourceOptions.relativeTolerance = semanticNumber(it)
    },
    sourceFlag("--source-absolute-optimality-tolerance", "number", "absolute source optimality gap") {
        options.sourceOptions.absoluteOptimalityTolerance = semanticNumber(it)
    },
    sourceFlag("--source-relative-optimality-tolerance", "number", "relative source optimality gap") {
        options.sourceOptions.relativeOptimalityTolerance = semanticNumber(it)
    },
    sourceFlag("--pin-absent-optional-values", "true|false", "canonicalize absent optional values") {
        val pin = when (it.lowercase()) {
            "true" -> true
            "false" -> false
            else -> usageError("--pin-absent-optional-values expects true or false")
        }
        options.semanticOverrides = options.semanticOverrides.copy(pinAbsentOptionalValues = pin)
    },
)

private fun sourceFlag(
    name: String,
    value: String,
    help: String,
    apply: (String) -> Unit,
): FlagSpec = sourceFlag(listOf(name), value, help, apply)

private fun sourceFlag(
    names: List<String>,
    value: String,
    help: String,
    apply: (String) -> Unit,
): FlagSpec = FlagSpec(names, true, FlagGroup.KLAUSE, valueLabel = value, help = help) { apply(requireNotNull(it)) }

private fun integerBound(raw: String): BoundOverride<Long> = if (raw == "open") BoundOverride.Open else {
    BoundOverride.Value(raw.toLongOrNull() ?: usageError("integer declaration bounds expect a Long or open"))
}

private fun realBound(raw: String): BoundOverride<SemanticRational> =
    if (raw == "open") BoundOverride.Open else BoundOverride.Value(semanticNumber(raw))

internal fun semanticNumber(raw: String): SemanticRational = try {
    val fraction = raw.split('/')
    if (fraction.size == 2) {
        SemanticRational.of(parseBigInt(fraction[0]), parseBigInt(fraction[1]))
    } else {
        require(fraction.size == 1 && SEMANTIC_DECIMAL.matches(raw)) { "expected an exact decimal or rational" }
        val parts = raw.lowercase().split('e')
        val mantissa = parts[0]
        val exponent = if (parts.size == 1) 0L else requireNotNull(parts[1].toLongOrNull())
        val unsigned = mantissa.removePrefix("-").removePrefix("+")
        val dot = unsigned.indexOf('.')
        val places = if (dot < 0) 0 else unsigned.length - dot - 1
        require(exponent >= Long.MIN_VALUE + places) { "decimal exponent is outside the input representation" }
        val scale = exponent - places
        require(scale in -MAX_SEMANTIC_DECIMAL_SCALE..MAX_SEMANTIC_DECIMAL_SCALE) {
            "decimal exponent exceeds the source-option representation; use a rational"
        }
        var numerator = parseBigInt(unsigned.replace(".", ""))
        if (mantissa.startsWith('-')) numerator = -numerator
        var power = BIG_ONE
        repeat(abs(scale).toInt()) { power *= bigIntOf(10) }
        if (scale >= 0) SemanticRational.of(numerator * power) else SemanticRational.of(numerator, power)
    }
} catch (error: IllegalArgumentException) {
    usageError("invalid source-semantic number `$raw`: ${error.message.orEmpty()}")
}

private val SEMANTIC_DECIMAL = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
private const val MAX_SEMANTIC_DECIMAL_SCALE = 10000L
