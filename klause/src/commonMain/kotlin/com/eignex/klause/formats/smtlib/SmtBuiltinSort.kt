package com.eignex.klause.formats.smtlib

internal fun smtBuiltinIdentifier(root: SExpr): SExpr {
    var identifier = root
    while (identifier is SExpr.SList && (identifier.items.firstOrNull() as? SExpr.Atom)?.text == "as") {
        if (identifier.items.size != 3) return root
        identifier = identifier.items[1]
    }
    return when (identifier) {
        is SExpr.Atom -> SExpr.Atom(polynomialSymbol(identifier.text))
        is SExpr.SList -> if ((identifier.items.firstOrNull() as? SExpr.Atom)?.text == "_") {
            SExpr.SList(identifier.items.mapIndexed { index, part ->
                if (index == 1 && part is SExpr.Atom) SExpr.Atom(polynomialSymbol(part.text)) else part
            })
        } else identifier
    }
}

internal fun smtBuiltinResultSort(
    identifier: SExpr,
    arguments: List<SmtSort>,
    qualification: SmtSort? = null,
): SmtSort? {
    val resolved = smtBuiltinIdentifier(identifier)
    smtBitVectorBuiltinSort(resolved, arguments)?.let { return it }
    if (resolved is SExpr.SList) {
        return if (resolved.items.size == 3 && (resolved.items[0] as? SExpr.Atom)?.text == "_" &&
            (resolved.items[1] as? SExpr.Atom)?.text == "divisible" && arguments == listOf(SmtSort.Int)) {
            SmtSort.Bool
        } else null
    }
    val name = (resolved as SExpr.Atom).text
    val inferred = when (name) {
        "true", "false", "=", "distinct", "and", "or", "xor", "not", "=>", "<", "<=", ">", ">=", "is_int" ->
            SmtSort.Bool
        "ite" -> arguments.getOrNull(1)?.let { first ->
            val second = arguments.getOrNull(2) ?: return null
            if (first == second) first else numericSmtResult(listOf(first, second))
        }
        "to_int", "div", "mod", "**" -> SmtSort.Int
        "to_real", "/", "exp", "log", "sin", "cos", "pi" -> SmtSort.Real
        "+", "-", "*", "abs" -> numericSmtResult(arguments)
        "^" -> arguments.firstOrNull()?.takeIf { it == SmtSort.Int || it == SmtSort.Real }
        "select" -> (arguments.firstOrNull() as? SmtSort.Array)?.element
        "store" -> arguments.firstOrNull() as? SmtSort.Array
        else -> null
    }
    return if (qualification == SmtSort.Real && inferred == SmtSort.Int &&
        name in setOf("+", "-", "*", "abs", "^", "ite")) SmtSort.Real else inferred
}

internal fun smtBuiltinIdentifierAtRank(identifier: SExpr, arguments: List<SmtSort>, result: SmtSort): SExpr =
    if (result == SmtSort.Real && smtBuiltinResultSort(identifier, arguments) == SmtSort.Int) {
        SExpr.SList(listOf(SExpr.Atom("as"), identifier, SExpr.Atom("Real")))
    } else identifier

private fun numericSmtResult(arguments: List<SmtSort>): SmtSort? = when {
    arguments.any { it != SmtSort.Int && it != SmtSort.Real } -> null
    SmtSort.Real in arguments -> SmtSort.Real
    else -> SmtSort.Int
}
