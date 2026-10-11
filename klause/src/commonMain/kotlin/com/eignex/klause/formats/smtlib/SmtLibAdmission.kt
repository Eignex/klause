package com.eignex.klause.formats.smtlib

import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.StringCharSource

/** The source representation admitted before the pipeline selects an arithmetic theory. */
sealed interface SmtLibInput {
    /** Source accepted by the existing linear lowering. */
    data class Linear(
        /** Exact source model and output identities. */
        val problem: SmtLibProblem,
    ) : SmtLibInput

    /** Pure real/Boolean polynomial source, retaining exact coefficients and formula structure. */
    data class Polynomial(
        /** Engine-neutral source snapshot, including declarations omitted from the formula. */
        val problem: PolynomialRealProblem,
    ) : SmtLibInput

    /** Admission stopped before a complete source snapshot could be published. */
    data object Interrupted : SmtLibInput
}

/** Linear-first source admission with a dedicated exact polynomial fallback. */
object SmtLibAdmission {
    /** Admit [text], preserving established linear lowering whenever it succeeds. */
    fun parse(
        text: String,
        unboundedIntLo: Long = DEFAULT_UNBOUNDED_INT_LO,
        unboundedIntHi: Long = DEFAULT_UNBOUNDED_INT_HI,
        settings: ProblemSettings = KlauseConfig.current.problemSettings(),
        cancellation: Cancellation = Cancellation.Never,
    ): SmtLibInput = parse({ StringCharSource(text) }, unboundedIntLo, unboundedIntHi, settings, cancellation)

    /**
     * Admit a streamed source. [openSource] must recreate the same input for each attempted lowering.
     * Owned closeable sources are closed on rejection; other sources are drained before fallback.
     */
    fun parse(
        openSource: () -> CharSource,
        unboundedIntLo: Long = DEFAULT_UNBOUNDED_INT_LO,
        unboundedIntHi: Long = DEFAULT_UNBOUNDED_INT_HI,
        settings: ProblemSettings = KlauseConfig.current.problemSettings(),
        cancellation: Cancellation = Cancellation.Never,
    ): SmtLibInput {
        if (cancellation()) return SmtLibInput.Interrupted
        return try {
            val result = try {
                val linear = withSource(openSource) { source ->
                    SmtLib.parse(
                        AdmissionSource(source, cancellation),
                        unboundedIntLo,
                        unboundedIntHi,
                        settings = settings,
                    )
                }
                SmtLibInput.Linear(linear)
            } catch (_: UnsupportedSmtException) {
                if (cancellation()) throw PolynomialSourceInterrupted()
                val polynomial = withSource(openSource) { source -> SmtLibPolynomial.parse(source, cancellation) }
                SmtLibInput.Polynomial(
                    PolynomialRealProblem(
                        polynomial.formula,
                        polynomial.realCount,
                        polynomial.boolCount,
                        polynomial.sourceRealCount,
                        polynomial.sourceBoolCount,
                        polynomial.realVarNames,
                        polynomial.boolVarNames,
                    ),
                )
            }
            if (cancellation()) SmtLibInput.Interrupted else result
        } catch (_: PolynomialSourceInterrupted) {
            SmtLibInput.Interrupted
        }
    }
}

private class AdmissionSource(private val source: CharSource, private val cancellation: Cancellation) : CharSource {
    override fun next(): String? {
        if (cancellation()) throw PolynomialSourceInterrupted()
        return source.next()
    }
}

private inline fun <T> withSource(openSource: () -> CharSource, read: (CharSource) -> T): T {
    val source = openSource()
    return try {
        read(source)
    } finally {
        if (source is AutoCloseable) source.close()
        else while (source.next() != null) Unit
    }
}
