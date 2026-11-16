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
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.pipeline.OpenTheoryAssignment
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.io.CharSource
import com.eignex.util.io.StringCharSource
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
    /** Original declarations and their reconstruction recipes, excluding encoding auxiliaries. */
    val sourceBindings: List<SmtSourceBinding> = scalarSmtBindings(intVarNames, boolVarNames, realVarNames),
    /** Retained lexical source snapshot, independent of every lowered representation. */
    val originalSource: SmtSourceSnapshot? = null,
    internal val privateBindings: Map<String, SmtReconstruction> = emptyMap(),
    /** Original-source checks contributed by composite reductions. */
    val sourceChecks: List<SmtSourceCheck> = emptyList(),
    internal val encodingIntVarNames: Map<String, Int> = intVarNames,
    internal val encodingBoolVarNames: Map<String, Int> = boolVarNames,
    internal val encodingRealVarNames: Map<String, Int> = realVarNames,
) : SourceSemanticsOwner {
    init {
        require(objective == null || quadraticObjective == null) { "SMT source has two objective bindings" }
    }

    override val semantics: EffectiveSourceSemantics get() = source.semantics

    /** Reconstruct original values from a checked assignment in retained source coordinates. */
    fun reconstructModel(
        boolean: (Int) -> Boolean,
        integer: (Int) -> String,
        real: (Int) -> String,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): SmtSourceModel = reconstructSmtModel(this, boolean, integer, real, cancellation)

    /** Reconstruct a checked native assignment using its certified exact real representation. */
    fun reconstructModel(
        assignment: OpenTheoryAssignment,
        cancellation: ExecutionControl = ExecutionControl.Never,
    ): SmtSourceModel = reconstructSmtModel(this, assignment::boolValue, assignment::intValue,
        { assignment.smtRealValue(it, cancellation) }, cancellation,
        { assignment.smtExactRealValue(it, cancellation) })

    /** Reconstruct a checked sample, retaining arbitrary integers and exact real authority. */
    fun reconstructModel(sample: Sample, cancellation: ExecutionControl = ExecutionControl.Never): SmtSourceModel =
        reconstructSmtModel(this, sample::boolValue, { sample.exactIntValue(it).toString() },
            { sample.smtRealValue(it, cancellation) }, cancellation,
            { sample.smtExactRealValue(it, cancellation) })

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
        cancellation: ExecutionControl = ExecutionControl.Never,
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
        cancellation: ExecutionControl = ExecutionControl.Never,
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
        cancellation: ExecutionControl = ExecutionControl.Never,
        semanticOverrides: SemanticOverrides = SemanticOverrides(),
    ): SmtLibProblem = parseSmtLibSource(
        openSource, unboundedIntLo, unboundedIntHi, settings, cancellation, semanticOverrides,
    ) ?: throw CancellationException("SMT source admission interrupted")
}
