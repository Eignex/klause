package com.eignex.klause.formats.smtlib

import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceSemanticsOwner
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource
import kotlin.coroutines.cancellation.CancellationException

/** An SMT-LIB source decoded and lowered into klause's representation. */
data class SmtLibProblem(
    /** Original engine-neutral source and its captured interpretation and settings. */
    val source: SourceAttachment,
    /** Original affine objective in [sense]; other objectives remain in [quadraticObjective] or [source]. */
    val objective: LinearObjectiveSpec?,
    /** Declared `Int` variable name to its retained source integer coordinate. */
    val intVarNames: Map<String, Int> = emptyMap(),
    /** Declared `Bool` variable name to its retained source Boolean coordinate. */
    val boolVarNames: Map<String, Int> = emptyMap(),
    /** Declared `Real` variable name to its retained source real coordinate. */
    val realVarNames: Map<String, Int> = emptyMap(),
    /** The objective's optimisation sense (minimise for satisfaction instances, which have none). */
    val sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    /** Original exact quadratic objective, without conversion to a finite cost. */
    val quadraticObjective: QuadraticObjectiveSpec? = null,
) : SourceSemanticsOwner {
    init {
        require(objective == null || quadraticObjective == null) { "SMT source has two objective bindings" }
    }

    override val semantics: EffectiveSourceSemantics get() = source.semantics

    internal val model: Problem get() = source.originalSource as? Problem
        ?: error("SMT source retains an exact formula")

    internal constructor(
        model: Problem,
        objective: LinearObjectiveSpec?,
        intVarNames: Map<String, Int> = emptyMap(),
        boolVarNames: Map<String, Int> = emptyMap(),
        realVarNames: Map<String, Int> = emptyMap(),
        sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    ) : this(SourceAttachment(model), objective, intVarNames, boolVarNames, realVarNames, sense)
}

/** SMT-LIB source admission with exact declarations and captured caller defaults. */
object SmtLib {
    /** Admit [text] as one source for the shared setup pipeline. */
    fun parse(
        text: String,
        unboundedIntLo: Long = DEFAULT_UNBOUNDED_INT_LO,
        unboundedIntHi: Long = DEFAULT_UNBOUNDED_INT_HI,
        settings: ProblemSettings = KlauseConfig.current.problemSettings(),
        cancellation: Cancellation = Cancellation.Never,
        semanticOverrides: SemanticOverrides = SemanticOverrides(),
    ): SmtLibProblem = parse(
        { StringCharSource(text) }, unboundedIntLo, unboundedIntHi, settings, cancellation, semanticOverrides,
    )

    /** Admit [source], closing it once when closeable, including refusal before the first read. */
    fun parse(
        source: CharSource,
        unboundedIntLo: Long = DEFAULT_UNBOUNDED_INT_LO,
        unboundedIntHi: Long = DEFAULT_UNBOUNDED_INT_HI,
        settings: ProblemSettings = KlauseConfig.current.problemSettings(),
        cancellation: Cancellation = Cancellation.Never,
        semanticOverrides: SemanticOverrides = SemanticOverrides(),
    ): SmtLibProblem {
        var transferred = false
        return try {
            parse(
                {
                    transferred = true
                    source
                },
                unboundedIntLo, unboundedIntHi, settings, cancellation, semanticOverrides,
            )
        } finally {
            if (!transferred && source is AutoCloseable) source.close()
        }
    }

    /**
     * Open and parse one source, retaining its commands through lowering.
     * A closeable source is closed once. Other sources are exhausted unless interrupted.
     * @throws CancellationException when admission stops before a complete source is available.
     */
    fun parse(
        openSource: () -> CharSource,
        unboundedIntLo: Long = DEFAULT_UNBOUNDED_INT_LO,
        unboundedIntHi: Long = DEFAULT_UNBOUNDED_INT_HI,
        settings: ProblemSettings = KlauseConfig.current.problemSettings(),
        cancellation: Cancellation = Cancellation.Never,
        semanticOverrides: SemanticOverrides = SemanticOverrides(),
    ): SmtLibProblem = parseSmtLibSource(
        openSource, unboundedIntLo, unboundedIntHi, settings, cancellation, semanticOverrides,
    ) ?: throw CancellationException("SMT source admission interrupted")
}
