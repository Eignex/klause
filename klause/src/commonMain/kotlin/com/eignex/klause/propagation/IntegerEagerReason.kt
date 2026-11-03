package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.util.IntHashSet

internal enum class IntegerEagerReason(val diagnostic: String) {
    Decision("decision"), Root("root"), Lazy("lazy"), OwnerUnavailable("owner-unavailable"),
    NonbinaryConclusion("nonbinary-conclusion"), NonunitConclusion("nonunit-conclusion"),
    TooWide("too-wide"), BooleanPremise("boolean-premise"), NonbinaryPremise("nonbinary-premise"),
    NoncanonicalAtom("noncanonical-atom"), InactivePremise("inactive-premise"),
    RepeatedCoordinate("repeated-coordinate"),
    BinaryClause("binary-clause"),
}

internal fun PropagationState.classifyIntegerEagerReason(
    variable: Int,
    lower: Boolean,
    sourceWork: IntegerSourceWork,
): IntegerEagerReason {
    sourceWork.visit()
    val reason = (if (lower) intMinAntecedents[variable] else intMaxAntecedents[variable])
        ?: return IntegerEagerReason.Decision
    if (isLazyReason(reason)) return IntegerEagerReason.Lazy
    if (reason.isEmpty()) return IntegerEagerReason.Root
    val owner = if (lower) intMinReason[variable] else intMaxReason[variable]
    if (incremental || owner !in 0 until totalFactorCount || retiredLearnedFactor(owner)) {
        return IntegerEagerReason.OwnerUnavailable
    }
    val bounds = problem.intBounds
    if (!bounds.hasLower(variable) || !bounds.hasUpper(variable) ||
        bounds.lower(variable) != 0L || bounds.upper(variable) != 1L) return IntegerEagerReason.NonbinaryConclusion
    val domain = intDomains[variable]
    if ((lower && domain.min != 1L) || (!lower && domain.max != 0L)) return IntegerEagerReason.NonunitConclusion
    if (reason.size >= INTEGER_LEARNING_TERMS) return IntegerEagerReason.TooWide
    val coordinates = IntHashSet()
    coordinates.add(variable)
    for (literal in reason) {
        sourceWork.visit()
        if (literal < 0) return IntegerEagerReason.NoncanonicalAtom
        val boolVariable = Lit.variable(literal)
        if (boolVariable < problem.numBoolVars) return IntegerEagerReason.BooleanPremise
        val atom = atomIdOf(boolVariable)
        if (atom !in 0 until atoms.intVar.size) return IntegerEagerReason.NoncanonicalAtom
        val source = atoms.intVar[atom]
        if (!bounds.hasLower(source) || !bounds.hasUpper(source) ||
            bounds.lower(source) != 0L || bounds.upper(source) != 1L) return IntegerEagerReason.NonbinaryPremise
        val threshold = atoms.threshold[atom]
        val canonical = when (atoms.kind[atom]) {
            AtomKind.GE -> threshold == 1L
            AtomKind.LE -> threshold == 0L
            AtomKind.EQ -> threshold == 0L || threshold == 1L
        }
        if (!canonical) return IntegerEagerReason.NoncanonicalAtom
        if (litTruth(literal) != false) return IntegerEagerReason.InactivePremise
        if (!coordinates.add(source)) return IntegerEagerReason.RepeatedCoordinate
    }
    return IntegerEagerReason.BinaryClause
}
