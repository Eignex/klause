package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.requiresPolynomialCompletion
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CharSource
import kotlin.coroutines.cancellation.CancellationException

internal fun parseSmtLibSource(
    openSource: () -> CharSource,
    unboundedIntLo: Long,
    unboundedIntHi: Long,
    settings: ProblemSettings,
    cancellation: Cancellation,
    semanticOverrides: SemanticOverrides,
): SmtLibProblem? {
    val semantics = resolveSmtLibSemantics(unboundedIntLo, unboundedIntHi, semanticOverrides)
    if (cancellation()) return null
    return try {
        val commands = withSource(openSource, cancellation) { source ->
            val reader = SExprReader(AdmissionSource(source, cancellation))
            val commands = ArrayList<SExpr>()
            while (true) {
                cancellation.polynomialSourceCheckpoint(1)
                commands.add(reader.readCommandOrNull() ?: break)
            }
            commands
        }
        val spellings = HashMap<String, String>()
        for (expression in commands) {
            cancellation.polynomialSourceCheckpoint()
            val command = expression as? SExpr.SList ?: continue
            val head = (command.items.firstOrNull() as? SExpr.Atom)?.text
            if (head == "declare-const" || head == "declare-fun") {
                val name = (command.items.getOrNull(1) as? SExpr.Atom)?.text ?: continue
                spellings[polynomialSymbol(name)] = name
            }
        }
        fun sourceNames(names: Map<String, Int>): Map<String, Int> {
            val original = LinkedHashMap<String, Int>()
            for ((name, id) in names) {
                cancellation.polynomialSourceCheckpoint()
                original[spellings.getValue(name)] = id
            }
            return original
        }
        val result = try {
            parseLinearAdmission(commands, unboundedIntLo, unboundedIntHi, settings, cancellation, semantics)
        } catch (factorFailure: UnsupportedSmtException) {
            if (cancellation()) throw PolynomialSourceInterrupted()
            try {
                val compiler = SmtLibPolynomialCompiler(cancellation, allowIntegers = true,
                    allowFunctions = true, semantics = semantics)
                for (command in commands) compiler.command(command)
                val captured = compiler.build()
                val functions = captured.transcendental
                if (functions != null) {
                    SmtLibProblem(SourceAttachment(functions, settings), null,
                        sourceNames(functions.intVarNames), sourceNames(functions.boolVarNames),
                        sourceNames(functions.realVarNames), functions.objectiveSense)
                } else if (captured.intVarNames.isNotEmpty()) {
                    val source = NonlinearIntegerProblem(
                        captured.formula, captured.realCount, captured.boolCount,
                        captured.intVarNames, captured.realVarNames, captured.boolVarNames, semantics,
                    )
                    SmtLibProblem(SourceAttachment(source, settings), null,
                        sourceNames(source.intVarNames), sourceNames(source.boolVarNames),
                        sourceNames(source.realVarNames),
                        captured.objectiveSense, captured.objective)
                } else {
                    val source = captured.realSource(semantics)
                    SmtLibProblem(SourceAttachment(source, settings), null,
                        emptyMap(), sourceNames(source.boolVarNames), sourceNames(source.realVarNames),
                        captured.objectiveSense, captured.objective)
                }
            } catch (_: UnsupportedSmtException) {
                throw factorFailure
            }
        }
        if (cancellation()) null else result
    } catch (_: PolynomialSourceInterrupted) {
        null
    }
}

private class AdmissionSource(private val source: CharSource, private val cancellation: Cancellation) : CharSource {
    override fun next(): String? {
        if (cancellation()) throw PolynomialSourceInterrupted()
        return source.next()
    }
}

private fun parseLinearAdmission(
    commands: List<SExpr>,
    unboundedIntLo: Long,
    unboundedIntHi: Long,
    settings: ProblemSettings,
    cancellation: Cancellation,
    semantics: EffectiveSourceSemantics,
): SmtLibProblem {
    val builder = Compiler.Builder(unboundedIntLo, unboundedIntHi, strictBounds = false, settings = settings,
        sourceCancellation = cancellation, semantics = semantics)
    val scope = SmtLibScope(cancellation)
    val declarations = ArrayList<SExpr>()
    var integerHelpers = false
    var logic: String? = null
    var querySeen = false
    var exited = false
    var invalidNonlinearSession = false
    var nonlinearSyntax = false
    var signedSymbols = false
    var quadraticObjective = false
    for (command in commands) {
        builder.command(command)
        if (!scope.command(command)) smtUnsupported("source symbol is outside its lexical scope")
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
        if (head in setOf("declare-const", "declare-fun", "define-fun")) declarations.add(command)
        if (head == "define-fun") integerHelpers = integerHelpers || hasIntegerSyntax(command, cancellation)
        if (head == "set-logic") logic = ((command as SExpr.SList).items.getOrNull(1) as? SExpr.Atom)?.text
    }
    if (quadraticObjective) smtUnsupported("quadratic objectives require exact source capture")
    // Definition scopes exclude caller-local bindings in pure satisfaction helpers.
    val polynomialLogic = logic == null || logic in setOf("QF_NRA", "QF_LRA", "QF_RDL", "ALL")
    val pureRealHelpers = polynomialLogic && !integerHelpers && builder.intNames.isEmpty() &&
        builder.objectiveSpec == null && builder.macros.isNotEmpty() &&
        builder.asserts.none { hasIntegerSyntax(it, cancellation) }
    val nonlinearHelpers = nonlinearSyntax && builder.macros.isNotEmpty()
    if (pureRealHelpers || nonlinearHelpers) {
        val referenced = builder.asserts.any { referencesHelper(it, builder.macros.keys, cancellation) } ||
            builder.objectiveSpec?.first?.let { referencesHelper(it, builder.macros.keys, cancellation) } == true
        if (nonlinearHelpers && referenced) smtUnsupported("nonlinear helpers require lexical source lowering")
        // Definitions must satisfy lexical body and return-sort validation even when unused.
        val validation = SmtLibPolynomialCompiler(cancellation, allowIntegers = true)
        for (declaration in declarations) validation.command(declaration)
    }
    val problem = builder.build()
    if (builder.objectiveSpec != null && problem.model.factors.any {
            it is ContinuousProduct || it is RealProduct || (it is Power && it.base is Power.Coordinate.Real)
        }
    ) smtUnsupported("polynomial objectives require exact source capture")
    val products = problem.model.factors.any {
        it is Power || it is Product || it is RealProduct || it is ContinuousProduct
    }
    val signedDeclarations = (builder.intNames.keys + builder.realNames.keys + builder.boolNames.keys).any {
        it.length > 1 && it[0] == '-' && it.drop(1).all { digit -> digit in '0'..'9' || digit == '.' }
    }
    if (signedSymbols && (products || (nonlinearSyntax && signedDeclarations))) {
        smtUnsupported("nonlinear source requires exact numeral and symbol distinction")
    }
    if (invalidNonlinearSession && (nonlinearSyntax || problem.model.factors.any {
            it is Power || it is Product || it is RealProduct || it is ContinuousProduct
        })
    ) smtUnsupported("nonlinear source requires one nonincremental satisfaction query")
    if (problem.model.factors.any {
            it is ContinuousProduct || it is RealProduct || (it is Power && it.base is Power.Coordinate.Real)
        } &&
        ((0 until problem.model.numIntVars).any { id ->
                problem.model.intBounds.isOpenLower(id) || problem.model.intBounds.isOpenUpper(id)
            } && problem.model.requiresPolynomialCompletion(FactorProjectionProviders.Default.prepare(problem.model)))
    ) {
        smtUnsupported("open integer products require an exact source formula")
    }
    return problem
}

private fun referencesHelper(expression: SExpr, helpers: Set<String>, cancellation: Cancellation): Boolean {
    val pending = ArrayDeque<SExpr>()
    pending.addLast(expression)
    while (pending.isNotEmpty()) {
        if (cancellation()) throw PolynomialSourceInterrupted()
        when (val node = pending.removeLast()) {
            is SExpr.Atom -> if (node.text in helpers) return true
            is SExpr.SList -> pending.addAll(node.items)
        }
    }
    return false
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
        runCatching { read(source) }.also {
            if (it.exceptionOrNull() is PolynomialSourceInterrupted ||
                it.exceptionOrNull() is CancellationException
            ) interrupted = true
        }
    } finally {
        if (source is AutoCloseable) source.close()
        else while (!interrupted) {
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
