package com.eignex.klause.formats.json

import com.eignex.klause.arithmetic.json.SemanticRationalSerializer
import com.eignex.klause.ir.DomainStorageSettings
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.NumericAuthority
import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.UndeclaredIntegerBounds
import com.eignex.klause.ir.UndeclaredRealBounds
import com.eignex.skema.serialization.CompactSerializer
import com.eignex.skema.serialization.JsonNodeCatalog
import com.eignex.skema.serialization.jsonNode
import kotlinx.serialization.Serializable

// Variables are their column ids; a Boolean literal is the Lit code `2·variable + negated` and a mixed
// coordinate is the Term code. Every optional field defaults to the IR default it stands for.
@Serializable
internal class ProblemDocument<F>(
    val bools: Int = 0,
    val ints: List<IntColumnDocument> = emptyList(),
    val reals: List<RealColumnDocument> = emptyList(),
    val factors: List<F> = emptyList(),
    val implied: List<Int>? = null,
    val symmetryBreaking: Boolean = false,
    val settings: SettingsDocument? = null,
    val semantics: SemanticsDocument? = null,
)

// An absent side is open. `values` lists the declared value set as `[lo, hi]` runs when narrower than the
// closed range; `box` is the finite fallback box a lane materialized for an open side and declares nothing.
@Serializable
internal class IntColumnDocument(
    val lower: Long? = null,
    val upper: Long? = null,
    val values: List<LongArray>? = null,
    val box: List<LongArray>? = null,
)

// An absent side is infinite.
@Serializable
internal class RealColumnDocument(val lower: Double? = null, val upper: Double? = null)

private val defaultSettings = ProblemSettings()

@Serializable
internal class SettingsDocument(
    val bitsetThreshold: Int = defaultSettings.storage.bitsetThreshold,
    val largeSpanThreshold: Long = defaultSettings.largeSpanThreshold,
    val lpMaxTableauCells: Long = defaultSettings.lpMaxTableauCells,
    val lpCeilingTableauCells: Long = defaultSettings.lpCeilingTableauCells,
) {
    fun toSettings(): ProblemSettings = ProblemSettings(
        storage = DomainStorageSettings(bitsetThreshold),
        largeSpanThreshold = largeSpanThreshold,
        lpMaxTableauCells = lpMaxTableauCells,
        lpCeilingTableauCells = lpCeilingTableauCells,
    )

    companion object {
        fun of(settings: ProblemSettings): SettingsDocument? = if (settings == defaultSettings) {
            null
        } else {
            SettingsDocument(
                settings.storage.bitsetThreshold,
                settings.largeSpanThreshold,
                settings.lpMaxTableauCells,
                settings.lpCeilingTableauCells,
            )
        }
    }
}

// The effective caller contract is present only when it differs from the declared format defaults.
@Serializable
internal class SemanticsDocument(
    val declared: SourceSemanticsDocument,
    val effective: SourceSemanticsDocument? = null,
) {
    fun toSemantics(): EffectiveSourceSemantics {
        val declaredSemantics = declared.toSemantics()
        return EffectiveSourceSemantics(declaredSemantics, effective?.toSemantics() ?: declaredSemantics)
    }

    companion object {
        fun of(semantics: EffectiveSourceSemantics): SemanticsDocument = SemanticsDocument(
            SourceSemanticsDocument.of(semantics.declared),
            if (semantics.hasOverrides) SourceSemanticsDocument.of(semantics.effective) else null,
        )
    }
}

@Serializable
internal class SourceSemanticsDocument(
    val format: SourceFormat,
    val numericAuthority: NumericAuthority = NumericAuthority.EXACT_VALUES,
    val floats: FloatSemanticsDocument? = null,
    val undeclaredIntegers: IntegerRangeDocument? = null,
    val undeclaredReals: RationalRangeDocument? = null,
    val feasibilityTolerance: ToleranceDocument? = null,
    val pinAbsentOptionalValues: Boolean? = null,
    val optimalityTolerance: ToleranceDocument? = null,
) {
    fun toSemantics(): SourceSemantics = SourceSemantics(
        format = format,
        numericAuthority = numericAuthority,
        floats = floats?.toSemantics(),
        undeclaredIntegers = undeclaredIntegers?.let { UndeclaredIntegerBounds(it.lower, it.upper) },
        undeclaredReals = undeclaredReals?.let { UndeclaredRealBounds(it.lower, it.upper) },
        feasibilityTolerance = feasibilityTolerance?.let { SourceTolerance(it.absolute, it.relative) },
        pinAbsentOptionalValues = pinAbsentOptionalValues,
        optimalityTolerance = optimalityTolerance?.let { OptimalityTolerance(it.absolute, it.relative) },
    )

    companion object {
        fun of(semantics: SourceSemantics): SourceSemanticsDocument = SourceSemanticsDocument(
            format = semantics.format,
            numericAuthority = semantics.numericAuthority,
            floats = semantics.floats?.let { FloatSemanticsDocument.of(it) },
            undeclaredIntegers = semantics.undeclaredIntegers?.let { IntegerRangeDocument(it.lower, it.upper) },
            undeclaredReals = semantics.undeclaredReals?.let { RationalRangeDocument(it.lower, it.upper) },
            feasibilityTolerance = semantics.feasibilityTolerance?.let { ToleranceDocument(it.absolute, it.relative) },
            pinAbsentOptionalValues = semantics.pinAbsentOptionalValues,
            optimalityTolerance = semantics.optimalityTolerance?.let { ToleranceDocument(it.absolute, it.relative) },
        )
    }
}

// An absent endpoint is open.
@Serializable
internal class IntegerRangeDocument(val lower: Long? = null, val upper: Long? = null)

// An absent endpoint is open.
@Serializable
internal class RationalRangeDocument(
    @Serializable(with = SemanticRationalSerializer::class) val lower: SemanticRational? = null,
    @Serializable(with = SemanticRationalSerializer::class) val upper: SemanticRational? = null,
)

@Serializable
internal class ToleranceDocument(
    @Serializable(with = SemanticRationalSerializer::class) val absolute: SemanticRational = SemanticRational.ZERO,
    @Serializable(with = SemanticRationalSerializer::class) val relative: SemanticRational = SemanticRational.ZERO,
)

@Serializable(with = FloatSemanticsSerializer::class)
internal sealed interface FloatSemanticsDocument {
    fun toSemantics(): FloatSemantics

    companion object {
        fun of(semantics: FloatSemantics): FloatSemanticsDocument = when (semantics) {
            FloatSemantics.Continuous -> ContinuousFloatsDocument
        }
    }
}

@Serializable
internal data object ContinuousFloatsDocument : FloatSemanticsDocument {
    override fun toSemantics(): FloatSemantics = FloatSemantics.Continuous
}

internal object FloatSemanticsSerializer : CompactSerializer<FloatSemanticsDocument>(
    "com.eignex.klause.formats.json.FloatSemantics",
    FloatSemanticsDocument::class,
    JsonNodeCatalog(),
    { listOf(jsonNode<ContinuousFloatsDocument>("continuous")) },
)
