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

/** Linear-first source admission with lexical polynomial helpers and an exact polynomial fallback. */
object SmtLibAdmission {
    /** Admit [text], retaining linear lowering where its helper semantics preserve the source. */
    fun parse(
        text: String,
        unboundedIntLo: Long = DEFAULT_UNBOUNDED_INT_LO,
        unboundedIntHi: Long = DEFAULT_UNBOUNDED_INT_HI,
        settings: ProblemSettings = KlauseConfig.current.problemSettings(),
        cancellation: Cancellation = Cancellation.Never,
    ): SmtLibInput = parse({ StringCharSource(text) }, unboundedIntLo, unboundedIntHi, settings, cancellation)

    /**
     * Admit a streamed source. [openSource] must recreate the same input for each attempted lowering.
     * Owned closeable sources are closed on rejection; active non-closeable sources drain before fallback.
     * Cancellation stops draining and prevents reopening.
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
                val linear = withSource(openSource, cancellation) { source ->
                    parseLinearAdmission(
                        AdmissionSource(source, cancellation),
                        unboundedIntLo,
                        unboundedIntHi,
                        settings,
                        cancellation,
                    )
                }
                SmtLibInput.Linear(linear)
            } catch (_: UnsupportedSmtException) {
                if (cancellation()) throw PolynomialSourceInterrupted()
                val polynomial = withSource(openSource, cancellation) { source ->
                    SmtLibPolynomial.parse(source, cancellation)
                }
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

private fun parseLinearAdmission(
    source: CharSource,
    unboundedIntLo: Long,
    unboundedIntHi: Long,
    settings: ProblemSettings,
    cancellation: Cancellation,
): SmtLibProblem {
    val builder = Compiler.Builder(unboundedIntLo, unboundedIntHi, strictBounds = false, settings = settings)
    val reader = SExprReader(source)
    var integerHelpers = false
    var logic: String? = null
    while (true) {
        val command = reader.readCommandOrNull() ?: break
        builder.command(command)
        val head = ((command as? SExpr.SList)?.items?.firstOrNull() as? SExpr.Atom)?.text
        if (head == "define-fun") integerHelpers = integerHelpers || hasIntegerSyntax(command, cancellation)
        if (head == "set-logic") logic = ((command as SExpr.SList).items.getOrNull(1) as? SExpr.Atom)?.text
    }
    // Definition scopes exclude caller-local bindings in pure satisfaction helpers.
    val polynomialLogic = logic == null || logic in setOf("QF_NRA", "QF_LRA", "QF_RDL", "ALL")
    if (polynomialLogic && !integerHelpers && builder.intNames.isEmpty() &&
        builder.objectiveSpec == null && builder.macros.isNotEmpty() &&
        builder.asserts.none { hasIntegerSyntax(it, cancellation) }
    ) {
        smtUnsupported("pure real/Boolean helpers require lexical polynomial lowering")
    }
    return builder.build()
}

private val INTEGER_SOURCE_OPERATORS = setOf("to_real", "to_int", "is_int", "div", "mod")

private fun hasIntegerSyntax(command: SExpr, cancellation: Cancellation): Boolean {
    if (command is SExpr.SList && (command.items.firstOrNull() as? SExpr.Atom)?.text == "define-fun") {
        if ((command.items.getOrNull(3) as? SExpr.Atom)?.text == "Int") return true
        val parameters = command.items.getOrNull(2) as? SExpr.SList
        if (parameters?.items?.any {
                ((it as? SExpr.SList)?.items?.getOrNull(1) as? SExpr.Atom)?.text == "Int"
            } == true
        ) return true
    }
    val pending = ArrayDeque<SExpr>()
    pending.addLast(command)
    while (pending.isNotEmpty()) {
        if (cancellation()) throw PolynomialSourceInterrupted()
        val node = pending.removeLast() as? SExpr.SList ?: continue
        val head = (node.items.firstOrNull() as? SExpr.Atom)?.text
        if (head in INTEGER_SOURCE_OPERATORS) return true
        pending.addAll(node.items)
    }
    return false
}

private inline fun <T> withSource(
    openSource: () -> CharSource,
    cancellation: Cancellation,
    read: (CharSource) -> T,
): T {
    val source = openSource()
    return try {
        read(source)
    } finally {
        if (source is AutoCloseable) source.close()
        else while (true) {
            if (cancellation()) throw PolynomialSourceInterrupted()
            if (source.next() == null) break
        }
    }
}
