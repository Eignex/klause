package com.eignex.klause.formats.smtlib

import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.QuadraticObjectiveSpec
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

    /** Exact polynomial source with original integer and mixed real declaration semantics. */
    data class NonlinearInteger(
        /** Neutral source formula and separate original coordinate identities. */
        val problem: NonlinearIntegerProblem,
    ) : SmtLibInput

    /** A captured quadratic objective on an existing exact source snapshot. */
    data class Quadratic(
        /** Polynomial real or nonlinear integer source, retaining original declarations. */
        val source: SmtLibInput,
        /** Exact quadratic coefficients and typed original coordinate identities. */
        val objective: QuadraticObjectiveSpec,
        /** Original source optimization direction. */
        val sense: ObjectiveSense,
    ) : SmtLibInput {
        init { require(source is Polynomial || source is NonlinearInteger) }
    }

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
                    SmtLibPolynomial.parse(source, cancellation, allowIntegers = true)
                }
                val source = if (polynomial.intVarNames.isNotEmpty()) {
                    SmtLibInput.NonlinearInteger(NonlinearIntegerProblem(
                        polynomial.formula, polynomial.realCount, polynomial.boolCount,
                        polynomial.intVarNames, polynomial.realVarNames, polynomial.boolVarNames,
                    ))
                } else SmtLibInput.Polynomial(
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
                polynomial.objective?.let { SmtLibInput.Quadratic(source, it, polynomial.objectiveSense) } ?: source
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
    val scope = SmtLibScope(cancellation)
    var integerHelpers = false
    var logic: String? = null
    var querySeen = false
    var exited = false
    var invalidNonlinearSession = false
    var nonlinearSyntax = false
    var signedSymbols = false
    var quadraticObjective = false
    while (true) {
        val command = reader.readCommandOrNull() ?: break
        if (!scope.command(command)) smtUnsupported("source symbol is outside its lexical scope")
        builder.command(command)
        nonlinearSyntax = nonlinearSyntax || hasNonlinearSyntax(command, cancellation)
        signedSymbols = signedSymbols || hasSignedNumberSymbol(command, cancellation)
        val head = ((command as? SExpr.SList)?.items?.firstOrNull() as? SExpr.Atom)?.text
        if (head == "minimize" || head == "maximize") {
            quadraticObjective = quadraticObjective || hasNonlinearSyntax(command, cancellation)
        }
        if (exited || (querySeen && head !in setOf("get-model", "exit", "set-info", "set-option"))) {
            invalidNonlinearSession = true
        }
        if (head == "check-sat" || head == "check-sat-assuming") querySeen = true
        if (head == "exit") exited = true
        if (head == "define-fun") integerHelpers = integerHelpers || hasIntegerSyntax(command, cancellation)
        if (head == "set-logic") logic = ((command as SExpr.SList).items.getOrNull(1) as? SExpr.Atom)?.text
    }
    if (quadraticObjective) smtUnsupported("quadratic objectives require exact source capture")
    // Definition scopes exclude caller-local bindings in pure satisfaction helpers.
    val polynomialLogic = logic == null || logic in setOf("QF_NRA", "QF_LRA", "QF_RDL", "ALL")
    if (polynomialLogic && !integerHelpers && builder.intNames.isEmpty() &&
        builder.objectiveSpec == null && builder.macros.isNotEmpty() &&
        builder.asserts.none { hasIntegerSyntax(it, cancellation) }
    ) {
        smtUnsupported("pure real/Boolean helpers require lexical polynomial lowering")
    }
    if (nonlinearSyntax && builder.macros.isNotEmpty()) {
        smtUnsupported("nonlinear helpers require lexical source lowering")
    }
    val problem = builder.build()
    val products = problem.model.factors.any { it is Product || it is RealProduct || it is ContinuousProduct }
    val signedDeclarations = (builder.intNames.keys + builder.realNames.keys + builder.boolNames.keys).any {
        it.length > 1 && it[0] == '-' && it.drop(1).all { digit -> digit in '0'..'9' || digit == '.' }
    }
    if (signedSymbols && (products || (nonlinearSyntax && signedDeclarations))) {
        smtUnsupported("nonlinear source requires exact numeral and symbol distinction")
    }
    if (invalidNonlinearSession && (nonlinearSyntax || problem.model.factors.any {
            it is Product || it is RealProduct || it is ContinuousProduct
        })
    ) smtUnsupported("nonlinear source requires one nonincremental satisfaction query")
    if (problem.model.factors.any { it is ContinuousProduct || it is RealProduct } &&
        (builder.intNames.isEmpty() || (0 until problem.model.numIntVars).any { id ->
            problem.model.intBounds.isOpenLower(id) || problem.model.intBounds.isOpenUpper(id)
        })) {
        smtUnsupported("pure real nonlinear source requires polynomial lowering")
    }
    return problem
}

private fun hasSignedNumberSymbol(expression: SExpr, cancellation: Cancellation): Boolean {
    val work = ArrayDeque<SExpr>()
    work.addLast(expression)
    while (work.isNotEmpty()) {
        if (cancellation()) throw PolynomialSourceInterrupted()
        when (val node = work.removeLast()) {
            is SExpr.SList -> work.addAll(node.items)
            is SExpr.Atom -> if (node.text.length > 1 && node.text[0] == '-' &&
                node.text.drop(1).all { it in '0'..'9' || it == '.' }
            ) return true
        }
    }
    return false
}

private fun hasNonlinearSyntax(expression: SExpr, cancellation: Cancellation): Boolean {
    val work = ArrayDeque<SExpr>()
    work.addLast(expression)
    while (work.isNotEmpty()) {
        if (cancellation()) throw PolynomialSourceInterrupted()
        val node = work.removeLast() as? SExpr.SList ?: continue
        val head = (node.items.firstOrNull() as? SExpr.Atom)?.text
        if (head == "^") return true
        if (head == "*" && node.items.drop(1).count { argument ->
                val text = (argument as? SExpr.Atom)?.text
                text == null || text.isEmpty() || text.any { it !in '0'..'9' && it != '.' }
            } > 1
        ) return true
        work.addAll(node.items)
    }
    return false
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

private fun <T> withSource(
    openSource: () -> CharSource,
    cancellation: Cancellation,
    read: (CharSource) -> T,
): T {
    val source = openSource()
    var interrupted = false
    val outcome = try {
        runCatching { read(source) }
    } finally {
        if (source is AutoCloseable) source.close()
        else while (true) {
            if (cancellation()) {
                interrupted = true
                break
            }
            if (source.next() == null) break
        }
    }
    if (interrupted) throw PolynomialSourceInterrupted()
    return outcome.getOrThrow()
}
