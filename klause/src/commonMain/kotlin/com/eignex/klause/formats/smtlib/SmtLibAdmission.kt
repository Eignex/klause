package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.solver.pipeline.SourceAttachment
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
                original[spellings.getValue(polynomialSymbol(name))] = id
            }
            return original
        }
        val result = try {
            parseLinearAdmission(commands, unboundedIntLo, unboundedIntHi, settings, cancellation, semantics).let {
                it.copy(intVarNames = sourceNames(it.intVarNames), boolVarNames = sourceNames(it.boolVarNames),
                    realVarNames = sourceNames(it.realVarNames))
            }
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
    var nonlinearSyntax = false
    var quadraticObjective = false
    for (command in commands) {
        builder.command(command.factorSourceSymbols(cancellation))
        if (!scope.command(command)) smtUnsupported("source symbol or expression sort is invalid in its lexical scope")
        nonlinearSyntax = nonlinearSyntax || hasNonlinearSyntax(command, cancellation)
        val head = ((command as? SExpr.SList)?.items?.firstOrNull() as? SExpr.Atom)?.text
        if (head == "minimize" || head == "maximize") {
            quadraticObjective = quadraticObjective || hasNonlinearSyntax(command, cancellation)
        }
    }
    if (quadraticObjective) smtUnsupported("quadratic objectives require exact source capture")
    if (builder.objectiveSpec != null && nonlinearSyntax && builder.macros.isNotEmpty() &&
        referencesHelper(builder.objectiveSpec!!.first, builder.macros.keys, cancellation)
    ) smtUnsupported("nonlinear helper objectives require exact original objective capture")
    val problem = builder.build()
    if (builder.objectiveSpec != null && problem.model.factors.any {
            it is ContinuousProduct || it is RealProduct || (it is Power && it.base is Power.Coordinate.Real)
        }
    ) smtUnsupported("polynomial objectives require exact source capture")
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
