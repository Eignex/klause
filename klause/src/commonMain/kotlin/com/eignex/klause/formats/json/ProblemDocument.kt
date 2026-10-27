package com.eignex.klause.formats.json

import com.eignex.klause.ir.DomainStorageSettings
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.NumericAuthority
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

/**
 * Wire form of a [com.eignex.klause.ir.Problem]. Variables are their column ids; a Boolean literal is the
 * [com.eignex.klause.ir.Lit] code `2·variable + negated`, and a mixed coordinate is the
 * [com.eignex.klause.ir.Term] code. Every optional field defaults to the IR default it stands for.
 */
@Serializable
internal class ProblemDocument<F>(
    val bools: Int = 0,
    val ints: List<IntColumnDocument> = emptyList(),
    val reals: List<RealColumnDocument> = emptyList(),
    val factors: List<F> = emptyList(),
    /** Indices of factors the model declared implied. */
    val implied: List<Int>? = null,
    val symmetryBreaking: Boolean = false,
    val settings: SettingsDocument? = null,
    val semantics: SemanticsDocument? = null,
)

/**
 * One integer column. An absent side is open. [values] lists the declared value set as `[lo, hi]` runs
 * when it is narrower than the closed range; [box] lists the finite fallback box a lane materialized for
 * a column with an open side, which declares nothing.
 */
@Serializable
internal class IntColumnDocument(
    val lower: Long? = null,
    val upper: Long? = null,
    val values: List<LongArray>? = null,
    val box: List<LongArray>? = null,
)

/** One real column; an absent side is infinite. */
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

/** Declared format defaults, and the effective caller contract when it differs. */
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
) {
    fun toSemantics(): SourceSemantics = SourceSemantics(
        format = format,
        numericAuthority = numericAuthority,
        floats = floats?.toSemantics(),
        undeclaredIntegers = undeclaredIntegers?.let { UndeclaredIntegerBounds(it.lower, it.upper) },
        undeclaredReals = undeclaredReals?.let { UndeclaredRealBounds(it.lower, it.upper) },
        feasibilityTolerance = feasibilityTolerance?.let { SourceTolerance(it.absolute, it.relative) },
        pinAbsentOptionalValues = pinAbsentOptionalValues,
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
        )
    }
}

/** An absent endpoint is open. */
@Serializable
internal class IntegerRangeDocument(val lower: Long? = null, val upper: Long? = null)

/** An absent endpoint is open. */
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

/** Float interpretation: the bare key `"continuous"` or `{"grid": {...}}`. */
@Serializable(with = FloatSemanticsSerializer::class)
internal sealed interface FloatSemanticsDocument {
    fun toSemantics(): FloatSemantics

    companion object {
        fun of(semantics: FloatSemantics): FloatSemanticsDocument = when (semantics) {
            FloatSemantics.Continuous -> ContinuousFloatsDocument
            is FloatSemantics.Grid -> GridFloatsDocument(
                semantics.buckets,
                semantics.linearScale,
                RationalRangeDocument(semantics.unrangedBounds.lower, semantics.unrangedBounds.upper),
            )
        }
    }
}

@Serializable
internal data object ContinuousFloatsDocument : FloatSemanticsDocument {
    override fun toSemantics(): FloatSemantics = FloatSemantics.Continuous
}

@Serializable
internal class GridFloatsDocument(
    val buckets: Int,
    val linearScale: Long,
    val unrangedBounds: RationalRangeDocument,
) : FloatSemanticsDocument {
    override fun toSemantics(): FloatSemantics =
        FloatSemantics.Grid(buckets, linearScale, UndeclaredRealBounds(unrangedBounds.lower, unrangedBounds.upper))
}

internal object FloatSemanticsSerializer : CompactSerializer<FloatSemanticsDocument>(
    "com.eignex.klause.formats.json.FloatSemantics",
    FloatSemanticsDocument::class,
    JsonNodeCatalog(),
    { listOf(jsonNode<ContinuousFloatsDocument>("continuous"), jsonNode<GridFloatsDocument>("grid")) },
)
