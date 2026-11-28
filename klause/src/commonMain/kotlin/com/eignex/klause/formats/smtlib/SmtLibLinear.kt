package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.IntComb
import com.eignex.klause.lowering.LinComb
import com.eignex.klause.lowering.LinRelation
import com.eignex.klause.lowering.constRelationHolds
import com.eignex.klause.lowering.intCombDiff
import com.eignex.klause.lowering.linCombDiff
import com.eignex.klause.lowering.reifyDifferenceLinear
import com.eignex.klause.lowering.reifyLinear
import com.eignex.klause.lowering.trueLit
import com.eignex.klause.lowering.wideConstHolds
import com.eignex.util.numbers.addExact

// Head-op matching alone misjudges an int-sorted `ite`/`let` operand, hence the scope-aware [isBoolExpr].
internal fun Compiler.Builder.isArithmeticRelation(t: SExpr.SList): Boolean {
    val arg = t.items.getOrNull(1) ?: return false
    return !isBoolExpr(arg)
}

internal fun Compiler.Builder.assertLinear(t: SExpr.SList) {
    val op = t.atomAt(0, "relation operator")
    requireChainableRelation(t, op)
    // Fold each operand once: a side-effecting subterm allocates per fold, and an interior operand takes
    // part in two relations.
    val terms = (1 until t.items.size).map { linearTerm(t.items[it]) }
    if (postOrderChain(op, terms)) return
    for (i in 0 until terms.size - 1) assertRelation(op, terms[i], terms[i + 1])
}

// A two-operand relation is already one exact row, and a repeated variable would make the chain propagate
// one unit per wake, so both fall back to pairwise rows.
private fun Compiler.Builder.postOrderChain(op: String, terms: List<IntComb>): Boolean {
    if (terms.size < 3) return false
    val descending = when (op) {
        "<", "<=" -> false
        ">", ">=" -> true
        else -> return false
    }
    val vars = IntArray(terms.size) { i ->
        val narrow = terms[i] as? IntComb.Narrow ?: return false
        narrow.lin.asSimpleVar() ?: return false
    }
    if (vars.toHashSet().size != vars.size) return false
    factors.add(Increasing(if (descending) vars.reversedArray() else vars, strict = strictDelta(op) != 0))
    return true
}

/** A variable-free relation resolves to nothing or to the false literal, never an empty [Linear]. */
internal fun Compiler.Builder.assertLinearRow(coeffs: LongArray, vars: IntArray, op: LinearOp, bound: Long) {
    if (vars.isEmpty()) {
        if (!constRelationHolds(op, bound)) forceTrue(Lit.negate(trueLit()))
    } else {
        factors.add(Linear(coeffs, vars, op, bound))
    }
}

internal fun Compiler.Builder.assertRelation(op: String, a: IntComb, b: IntComb) {
    if (op == "=") conditionalEquality(a, b)?.let {
        forceTrue(it)
        return
    }
    val linOp = relLinearOp(op)
    when (val rel = intCombDiff(a, b, strictDelta(op).toLong())) {
        is LinRelation.LongRel -> assertLinearRow(rel.coeffs, rel.vars, linOp, rel.bound)

        is LinRelation.WideRel -> if (rel.vars.isEmpty()) {
            if (!wideConstHolds(linOp, rel.bound)) forceTrue(Lit.negate(trueLit()))
        } else {
            factors.add(Linear(rel.vars, rel.coeffs, linOp, rel.bound))
        }
    }
}

internal fun Compiler.Builder.reifyRelation(op: String, a: IntComb, b: IntComb): Int {
    if (op == "=") conditionalEquality(a, b)?.let { return it }
    val linOp = relLinearOp(op)
    return when (val rel = intCombDiff(a, b, strictDelta(op).toLong())) {
        is LinRelation.LongRel -> reifyIntegerRelation(rel, linOp).also {
            noteEqAtom(it, linOp, rel.coeffs, rel.vars, rel.bound)
        }

        is LinRelation.WideRel -> if (rel.vars.isEmpty()) {
            if (wideConstHolds(linOp, rel.bound)) trueLit() else Lit.negate(trueLit())
        } else {
            reifyLinear(rel.coeffs, rel.vars, linOp, rel.bound)
        }
    }
}

private fun Compiler.Builder.reifyIntegerRelation(rel: LinRelation.LongRel, op: LinearOp): Int {
    val difference = rel.vars.size == 2 && (rel.coeffs[0] == 1L || rel.coeffs[0] == -1L) &&
        rel.coeffs[1] == -rel.coeffs[0]
    if (!difference || (op != LinearOp.EQ && op != LinearOp.NE)) {
        return reifyLinear(rel.coeffs, rel.vars, op, rel.bound)
    }
    val start = factors.size
    val literal = reifyDifferenceLinear(rel.coeffs, rel.vars, op, rel.bound)
    conditionalEqualities.definePredicate(Lit.variable(literal), factors.subList(start, factors.size))
    return literal
}

private fun Compiler.Builder.conditionalEquality(a: IntComb, b: IntComb): Int? {
    if (a !is IntComb.Narrow || b !is IntComb.Narrow) return null
    val variable = if (b.lin.coeffs.isEmpty()) a.lin.asSimpleVar() else {
        if (a.lin.coeffs.isEmpty()) b.lin.asSimpleVar() else null
    } ?: return null
    closeIteChain(variable)
    val value = if (b.lin.coeffs.isEmpty()) b.lin.constant else a.lin.constant
    return conditionalEqualities.reify(variable, value, this)?.also { literal ->
        if (intDomains[variable] is PresolveDomain.Finite) {
            iteChains.noteAtom(literal, variable, value, allowsElement = !conditionalEqualities.isDefined(variable))
        }
    }
}

// Lets an `ite` chain read its condition back off the literal. An open selector could never index an array.
private fun Compiler.Builder.noteEqAtom(lit: Int, op: LinearOp, coeffs: LongArray, vars: IntArray, bound: Long) {
    if (op != LinearOp.EQ || vars.size != 1) return
    val c = coeffs[0]
    if (c != 1L && c != -1L) return
    if (c == -1L && bound == Long.MIN_VALUE) return
    conditionalEqualities.rememberPrimitive(vars[0], bound * c, lit)
    if (intDomains[vars[0]] !is PresolveDomain.Finite) return
    iteChains.noteAtom(lit, vars[0], bound * c)
}

internal fun requireChainableRelation(node: SExpr.SList, op: String) {
    if (node.items.size < 3) {
        throw UnsupportedSmtException("$op with ${node.items.size - 1} operands is not a relation")
    }
}

// Skips a pair with a wide operand, which its factor enforces.
internal fun Compiler.Builder.relationToLinear(t: SExpr.SList, out: MutableList<Rel>) {
    val op = t.atomAt(0, "relation operator")
    requireChainableRelation(t, op)
    val terms = (1 until t.items.size).map { linearTerm(t.items[it]) }
    for (i in 0 until terms.size - 1) {
        val a = terms[i]
        val b = terms[i + 1]
        if (a is IntComb.Narrow && b is IntComb.Narrow) out.add(relFromOperands(op, a.lin, b.lin))
    }
}

internal fun Compiler.Builder.relFromOperands(op: String, a: LinComb, b: LinComb): Rel {
    val (vars, coeffs, baseBound) = diff(a, b)
    val (linOp, delta) = when (op) {
        "<=" -> LinearOp.LE to 0
        ">=" -> LinearOp.GE to 0
        "=" -> LinearOp.EQ to 0
        "distinct" -> LinearOp.NE to 0
        "<" -> LinearOp.LE to -1
        ">" -> LinearOp.GE to 1
        else -> throw UnsupportedSmtException("relation '$op'")
    }
    return Rel(vars, coeffs, linOp, foldChecked { addExact(baseBound, delta.toLong()) })
}

internal inline fun <T> foldChecked(block: () -> T): T = try {
    block()
} catch (_: ArithmeticException) {
    throw UnsupportedSmtException("integer overflow while folding a linear term")
}

internal fun Compiler.Builder.linearTerm(t: SExpr): IntComb = (evalTerm(t, Sort.INT) as Res.I).term

// For consumers with no arbitrary-precision path.
internal fun Compiler.Builder.linearTermNarrow(t: SExpr): LinComb = when (val ic = linearTerm(t)) {
    is IntComb.Narrow -> ic.lin
    is IntComb.Wide -> throw UnsupportedSmtException("integer beyond the 64-bit range in this context")
}

internal fun Compiler.Builder.intBinding(name: String, b: Compiler.Builder.Binding): IntComb {
    if (b.isBool) throw UnsupportedSmtException("'$name' used as Int but bound to a Bool term")
    return b.lin ?: throw UnsupportedSmtException("'$name' has no compiled Int value")
}

// Scanned explicitly: a `\d` regex's Unicode-digit semantics differ across KMP targets.
internal fun Compiler.Builder.isIntegerLiteral(s: String): Boolean {
    val start = if (s.startsWith('-')) 1 else 0
    if (start >= s.length) return false
    for (i in start until s.length) if (s[i] !in '0'..'9') return false
    return true
}

internal fun Compiler.Builder.isRealLiteral(s: String): Boolean = '.' in s && s.toDoubleOrNull() != null

internal fun Compiler.Builder.diff(a: LinComb, b: LinComb): Triple<IntArray, LongArray, Long> =
    foldChecked { linCombDiff(a, b) }

internal fun Compiler.Builder.linearObjective(t: SExpr, negate: Boolean): LinearObjectiveSpec {
    val lt = linearTermNarrow(t)
    val coeffs = LongArray(nextInt)
    for ((v, c) in lt.coeffs) coeffs[v] = if (negate) -c else c
    return LinearObjectiveSpec(
        intCoefficients = coeffs,
        constant = if (negate) -lt.constant else lt.constant,
    )
}
