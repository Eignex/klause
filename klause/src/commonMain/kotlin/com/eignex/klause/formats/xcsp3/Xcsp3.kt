package com.eignex.klause.formats.xcsp3

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.formats.FormatException
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceSemanticsOwner
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource

/** Raised when an XCSP3 construct outside the supported subset is encountered. */
class UnsupportedXcsp3Exception(msg: String) : FormatException("XCSP3", msg)

/** Exact finite integer declarations and constraints, with explicit source domains. */
val xcsp3Semantics: SourceSemantics = SourceSemantics(SourceFormat.XCSP3)

/** An XCSP3 source decoded and lowered into klause's representation. */
data class Xcsp3Problem(
    /** Compiled solver problem. */
    val problem: Problem,
    /** Objective, or null for satisfaction instances. */
    val objective: LinearObjectiveSpec?,
    /** Declared variable name to int var id. */
    val intVarNames: Map<String, Int> = emptyMap(),
    /** The objective's optimisation sense (minimise for satisfaction instances, which have none). */
    val sense: ObjectiveSense = ObjectiveSense.MINIMIZE,
    /** Int vars the front-end knows are functionally defined (sound local-search `defines_var` hints). */
    val definedVars: IntArray = IntArray(0),
    /** Typed source orientations in compiled coordinates; the source factors remain authoritative. */
    val definitionHints: List<DefinitionHint> = emptyList(),
) : SourceSemanticsOwner {
    /** Effective interpretation captured by the lowered source. */
    override val semantics: EffectiveSourceSemantics get() = problem.semantics
}

/** XCSP3 integer format facade. */
object Xcsp3 {
    /** Decode and lower XCSP3 [text]; [recoveryCancellation] stops optional collective recovery. */
    fun parse(
        text: String,
        settings: ProblemSettings = KlauseConfig.current.problemSettings(),
        semanticOverrides: SemanticOverrides = SemanticOverrides(),
        recoveryCancellation: Cancellation = Cancellation.Never,
    ): Xcsp3Problem = parse(StringCharSource(text), settings, semanticOverrides, recoveryCancellation)

    /** Decode and lower a streamed XCSP3 [source]; [recoveryCancellation] stops optional collective recovery. */
    @Suppress("SwallowedException")
    fun parse(
        source: CharSource,
        settings: ProblemSettings = KlauseConfig.current.problemSettings(),
        semanticOverrides: SemanticOverrides = SemanticOverrides(),
        recoveryCancellation: Cancellation = Cancellation.Never,
    ): Xcsp3Problem = try {
        Compiler.Builder(settings, xcsp3Semantics.resolve(semanticOverrides), recoveryCancellation).run {
            val reader = XmlReader(source)
            reader.openRoot()
            // Single forward pass over `<instance>`'s children keeps the container order and tolerates
            // absent sections without rewinding the streaming reader.
            while (true) {
                when (reader.nextChildTag() ?: break) {
                    "variables" -> if (reader.enterPeeked()) {
                        while (reader.nextChildTag() != null) declareVar(reader.materializeChild())
                    }

                    "constraints" -> if (reader.enterPeeked()) streamConstraints(reader)

                    // Only the first objective is taken; drain the rest to keep the outer cursor aligned.
                    "objectives" -> if (reader.enterPeeked()) {
                        var first = true
                        while (reader.nextChildTag() != null) {
                            if (first) objective(reader.materializeChild()) else reader.materializeChild()
                            first = false
                        }
                    }

                    else -> reader.materializeChild()
                }
            }
            build()
        }
    } catch (e: IllegalArgumentException) {
        // XML, expression, and tuple scanners use require; numeric reads may throw the same base type.
        throw UnsupportedXcsp3Exception(e.message ?: "malformed XCSP3 document")
    }
}
