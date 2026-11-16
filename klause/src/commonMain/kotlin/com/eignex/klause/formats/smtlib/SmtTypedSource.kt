package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.ExecutionControl

internal data class SmtTypedTerm(val operator: String, val arguments: List<Int>, val sort: SmtSort)

internal class SmtTypedSource(
    val commands: List<SExpr>,
    val declarations: List<SmtSourceDeclaration>,
    val terms: List<SmtTypedTerm>,
    val assertions: List<Int>,
)

internal data class SmtReduction(
    val commands: List<SExpr>,
    val declarations: List<SmtSourceDeclaration>,
    val bindings: List<SmtSourceBinding>,
    val privateBindings: Map<String, SmtReconstruction> = emptyMap(),
    val original: SmtTypedSource? = null,
    val checks: List<SmtSourceCheck> = emptyList(),
)

internal fun captureSmtSource(commands: List<SExpr>, cancellation: ExecutionControl): SmtReduction {
    val scope = SmtLibScope(cancellation)
    for (command in commands) {
        cancellation.polynomialSourceCheckpoint(1)
        if (!scope.command(command)) smtUnsupported("source symbol or expression sort is invalid in its lexical scope")
    }
    val declarations = scope.declarations
    val original = SmtTypedSource(commands.toList(), declarations, scope.terms, scope.assertions)
    val bindings = declarations.map {
        cancellation.polynomialSourceCheckpoint(1)
        SmtSourceBinding(it, SmtReconstruction.Scalar(it.name, it.result))
    }
    return SmtReduction(original.commands, declarations, bindings, original = original)
}
