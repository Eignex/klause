package com.eignex.klause.formats.smtlib

import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.io.CharSource
import kotlin.coroutines.cancellation.CancellationException

internal fun parseSmtLibSource(
    openSource: () -> CharSource,
    unboundedIntLo: Long,
    unboundedIntHi: Long,
    settings: ProblemSettings,
    cancellation: ExecutionControl,
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
        val polymorphic = reduceSmtPolymorphism(captureSmtSource(commands, cancellation), cancellation)
        val ground = reduceSmtGroundFunctions(polymorphic, cancellation)
        val reduction = lowerSmtBitVectors(ground, cancellation)
        lowerSmtReduction(reduction, unboundedIntLo, unboundedIntHi, settings, cancellation, semantics)
    } catch (_: PolynomialSourceInterrupted) {
        null
    }
}

internal fun lowerSmtReduction(
    reduction: SmtReduction,
    unboundedIntLo: Long,
    unboundedIntHi: Long,
    settings: ProblemSettings,
    cancellation: ExecutionControl,
    semantics: EffectiveSourceSemantics,
): SmtLibProblem {
    val loweredCommands = reduction.commands
    val semanticDeclarations = reduction.declarations.filter { it.parameters.isEmpty() &&
        it.result in setOf(SmtSort.Int, SmtSort.Real) }.map { polynomialSymbol(it.name) }.toSet()
    val spellings = HashMap<String, String>()
    for (expression in loweredCommands) {
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
        if (reduction.original?.terms?.any { it.operator in setOf("exp", "log", "sin", "cos", "pi") } == true) {
            smtUnsupported("real functions require their original source domains")
        }
        parseLinearAdmission(loweredCommands, unboundedIntLo, unboundedIntHi, settings, cancellation, semantics,
            semanticDeclarations).let {
            it.copy(intVarNames = sourceNames(it.intVarNames), boolVarNames = sourceNames(it.boolVarNames),
                realVarNames = sourceNames(it.realVarNames))
        }
    } catch (factorFailure: UnsupportedSmtException) {
        if (cancellation()) throw PolynomialSourceInterrupted()
        try {
            val compiler = SmtLibPolynomialCompiler(cancellation, allowIntegers = true,
                allowFunctions = true, semantics = semantics, semanticDeclarations = semanticDeclarations)
            for (command in loweredCommands) compiler.command(command)
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
    cancellation.polynomialSourceCheckpoint()
        fun originals(sort: SmtSort): Set<String> = reduction.declarations.filter {
            it.parameters.isEmpty() && it.result == sort
        }.map { polynomialSymbol(it.name) }.toSet()
        val originalIntegers = originals(SmtSort.Int)
        val originalBooleans = originals(SmtSort.Bool)
        val originalReals = originals(SmtSort.Real)
    return result.copy(sourceBindings = reduction.bindings,
        privateBindings = reduction.privateBindings, originalSource = reduction.original?.let(::SmtSourceSnapshot),
        sourceChecks = reduction.checks,
            intVarNames = result.intVarNames.filterKeys { polynomialSymbol(it) in originalIntegers },
            boolVarNames = result.boolVarNames.filterKeys { polynomialSymbol(it) in originalBooleans },
            realVarNames = result.realVarNames.filterKeys { polynomialSymbol(it) in originalReals },
        encodingIntVarNames = result.intVarNames, encodingBoolVarNames = result.boolVarNames,
        encodingRealVarNames = result.realVarNames).withSourceWitnessValidation()
}

private class AdmissionSource(private val source: CharSource, private val cancellation: ExecutionControl) : CharSource {
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
    cancellation: ExecutionControl,
    semantics: EffectiveSourceSemantics,
    semanticDeclarations: Set<String>,
): SmtLibProblem {
    val builder = Compiler.Builder(unboundedIntLo, unboundedIntHi, strictBounds = false, settings = settings,
        sourceCancellation = cancellation, semantics = semantics, semanticDeclarations = semanticDeclarations)
    for (command in commands) {
        builder.command(command.factorSourceSymbols(cancellation))
    }
    return try {
        buildLinearAdmission(builder, commands, cancellation, semantics, semanticDeclarations)
    } catch (refuted: SmtLibRefutedPremises) {
        // Refutation does not excuse unsupported syntax elsewhere in the source.
        val compiler = SmtLibPolynomialCompiler(cancellation, allowIntegers = true,
            allowFunctions = true, semantics = semantics, semanticDeclarations = semanticDeclarations)
        for (command in commands) compiler.command(command)
        val captured = compiler.build()
        if (captured.transcendental?.hasObjective == true) {
            smtUnsupported("function objective requires its source expression")
        }
        val source = builder.refutedSource(refuted.premises)
        if (captured.objective != null) captured.bindObjective(source, cancellation) else source
    }
}

private fun buildLinearAdmission(
    builder: Compiler.Builder,
    commands: List<SExpr>,
    cancellation: ExecutionControl,
    semantics: EffectiveSourceSemantics,
    semanticDeclarations: Set<String>,
): SmtLibProblem {
    if (builder.objectiveSpec == null) return builder.build()
    var captureFailure: UnsupportedSmtException? = null
    val objective = try {
        captureSmtSourceObjective(commands, cancellation, semantics, semanticDeclarations)
    } catch (unsupported: UnsupportedSmtException) {
        captureFailure = unsupported
        null
    }
    if (objective == null) {
        val source = builder.build()
        if (!source.hasDeclaredAffineObjective()) throw requireNotNull(captureFailure)
        return source
    }
    return objective.bindObjective(builder.buildSource(), cancellation)
}

private fun <T> withSource(
    openSource: () -> CharSource,
    cancellation: ExecutionControl,
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
