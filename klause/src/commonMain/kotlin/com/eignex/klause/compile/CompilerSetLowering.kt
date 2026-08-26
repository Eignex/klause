package com.eignex.klause.compile

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.And
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.BoolRef
import com.eignex.klause.model.Iff
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.IntCompare
import com.eignex.klause.model.IntLit
import com.eignex.klause.model.IntRef
import com.eignex.klause.model.Not
import com.eignex.klause.model.Or
import com.eignex.klause.model.SetCard
import com.eignex.klause.model.SetDiff
import com.eignex.klause.model.SetDisjoint
import com.eignex.klause.model.SetEq
import com.eignex.klause.model.SetExpr
import com.eignex.klause.model.SetIn
import com.eignex.klause.model.SetIntersect
import com.eignex.klause.model.SetLiteral
import com.eignex.klause.model.SetNominalIn
import com.eignex.klause.model.SetNominalLiteral
import com.eignex.klause.model.SetRef
import com.eignex.klause.model.SetSubsetOf
import com.eignex.klause.model.SetUnion
import com.eignex.klause.solver.IntDomain

/**
 * Materialise a [SetExpr] into a [SetLayout]. Looks up named set vars; for compound
 * expressions (union / intersect / diff / literal) allocates aux indicator bools and
 * ties them via clauses, then returns the new layout.
 *
 * The universe of the result is the union of the operand universes (for binary ops) or
 * the literal's own element list (for [SetLiteral] / [SetNominalLiteral]). This keeps
 * the lowering uniform — every downstream consumer iterates the universe once.
 */
internal fun Lowering.materializeSet(expr: SetExpr): SetLayout = when (expr) {
    is SetRef -> setLayouts[expr.name]
        ?: error("Unknown set variable '${expr.name}'")

    is SetLiteral -> {
        // Constant set: allocate indicator-shaped layout with each indicator pinned to its
        // constant value via a unit clause. The downstream lowering doesn't care that the
        // indicators are constants — it just emits clauses against them, and the unit
        // clauses we add here are enough to propagate the truth.
        val sorted = expr.elements.distinct().sorted()
        require(sorted.isNotEmpty()) { "set literal must be non-empty" }
        val ids = IntArray(sorted.size) { newBoolVar() }
        for (id in ids) factors += Clause(intArrayOf(Lit.make(id, positive = true)))
        SetLayout(sorted.toIntArray(), ids)
    }

    is SetNominalLiteral -> {
        // The compiler can't resolve nominal labels without knowing which nominal-set var
        // they're being compared against. We require [SetNominalLiteral] only appear as
        // operand of [SetEq] / [SetSubsetOf] etc. against a [SetRef] over a nominal-set
        // var, in which case [reifySetWithNominalLiteral] handles the lookup explicitly.
        // Reaching here means the literal is being materialised standalone, which we
        // don't support — surface a clear error instead of silently fabricating a layout.
        error("nominal set literal cannot be materialised without a nominal-set var operand on the other side")
    }

    // out ↔ (left[v] ∨ right[v]); result universe is both operands'.
    is SetUnion -> materializeSetBinary(expr.left, expr.right, fullUnion = true) { outLit, ll, rl ->
        when {
            ll != null && rl != null -> {
                factors += Clause(intArrayOf(Lit.negate(ll), outLit))
                factors += Clause(intArrayOf(Lit.negate(rl), outLit))
                factors += Clause(intArrayOf(Lit.negate(outLit), ll, rl))
            }

            ll != null -> {
                factors += Clause(intArrayOf(Lit.negate(ll), outLit))
                factors += Clause(intArrayOf(Lit.negate(outLit), ll))
            }

            rl != null -> {
                factors += Clause(intArrayOf(Lit.negate(rl), outLit))
                factors += Clause(intArrayOf(Lit.negate(outLit), rl))
            }

            else -> factors += Clause(intArrayOf(Lit.negate(outLit))) // unreachable: universe ⊆ l ∪ r
        }
    }

    // out ↔ (left[v] ∧ right[v]); elements in only one universe can never be in the intersection.
    is SetIntersect -> materializeSetBinary(expr.left, expr.right, fullUnion = true) { outLit, ll, rl ->
        if (ll != null && rl != null) {
            factors += Clause(intArrayOf(Lit.negate(outLit), ll))
            factors += Clause(intArrayOf(Lit.negate(outLit), rl))
            factors += Clause(intArrayOf(outLit, Lit.negate(ll), Lit.negate(rl)))
        } else {
            factors += Clause(intArrayOf(Lit.negate(outLit)))
        }
    }

    // S \ T: out ↔ (left[v] ∧ ¬right[v]); result universe is S's, so `ll` is always present.
    is SetDiff -> materializeSetBinary(expr.left, expr.right, fullUnion = false) { outLit, ll, rl ->
        requireNotNull(ll) { "set diff: left indicator missing for a result-universe element" }
        if (rl != null) {
            factors += Clause(intArrayOf(Lit.negate(outLit), ll))
            factors += Clause(intArrayOf(Lit.negate(outLit), Lit.negate(rl)))
            factors += Clause(intArrayOf(outLit, Lit.negate(ll), rl))
        } else {
            // Nothing to subtract for this element → out ↔ ll.
            factors += Clause(intArrayOf(Lit.negate(outLit), ll))
            factors += Clause(intArrayOf(outLit, Lit.negate(ll)))
        }
    }
}

/**
 * Shared scaffold for the binary set-materialisation ops (∪, ∩, \). Materialises both operands,
 * allocates one fresh indicator bool per result-universe element, and for each element invokes
 * [emit] with the result literal plus each operand's indicator literal at that element (`null`
 * when the element is absent from that operand's universe). [fullUnion] picks the result universe:
 * the union of both operand universes (∪, ∩), or just the left operand's (\, where the result can
 * only hold left-side elements). The per-element clause logic lives in [emit].
 */
private fun Lowering.materializeSetBinary(
    left: SetExpr,
    right: SetExpr,
    fullUnion: Boolean,
    emit: (outLit: Int, ll: Int?, rl: Int?) -> Unit,
): SetLayout {
    val l = materializeSet(left)
    val r = materializeSet(right)
    val universe = if (fullUnion) unionUniverse(l.universe, r.universe) else l.universe.copyOf()
    val ids = IntArray(universe.size) { newBoolVar() }
    for (i in universe.indices) {
        val v = universe[i]
        val li = l.indexOf(v)
        val ri = r.indexOf(v)
        val ll = if (li >= 0) Lit.make(l.indicatorBoolIds[li], positive = true) else null
        val rl = if (ri >= 0) Lit.make(r.indicatorBoolIds[ri], positive = true) else null
        emit(Lit.make(ids[i], positive = true), ll, rl)
    }
    return SetLayout(universe, ids)
}

/** Sorted ascending union of two universe arrays. */
private fun unionUniverse(a: IntArray, b: IntArray): IntArray {
    val merged = LinkedHashSet<Int>(a.size + b.size)
    for (v in a) merged.add(v)
    for (v in b) merged.add(v)
    val out = merged.toIntArray()
    out.sort()
    return out
}

// `assertExpr` calls into these for set BoolExprs that appear as direct top-level
// constraints; `lowerToLit` calls the [reify*] variants below when the same expression
// shows up inside a sub-expression position.

internal fun Lowering.assertSetIn(expr: SetIn) {
    val set = materializeSet(expr.set)
    // x ∈ S  ≡  ∨_{e ∈ universe(S)} (x = e ∧ S[e])
    // Top-level form: emit `∨_{e} (x = e ∧ S[e])` via Tseitin disjunction, then assert it.
    val pieces = mutableListOf<BoolExpr>()
    for (i in set.universe.indices) {
        val e = set.universe[i]
        val bId = set.indicatorBoolIds[i]
        pieces += And(
            listOf(
                IntCompare(expr.elem, IntCmpOp.EQ, IntLit(e)),
                indicatorBoolExpr(bId),
            ),
        )
    }
    assertExpr(orOf(pieces))
}

internal fun Lowering.assertSetNominalIn(expr: SetNominalIn) {
    val set = materializeSet(expr.set)
    val setName = setRefName(expr.set)
        ?: error(
            "set membership of label '${expr.label}' requires a nominal-set var on the right, " +
                "not a compound expression",
        )
    val labels = setLabelOrder[setName]
        ?: error("set '$setName' is an integer-universe set; use `intVar inSet $setName` instead")
    val idx = labels.indexOf(expr.label)
    require(idx >= 0) { "label '${expr.label}' not in nominal-set '$setName'" }
    // For a nominal set, "label ∈ S" is simply: S[idx] = true.
    factors += Clause(intArrayOf(Lit.make(set.indicatorBoolIds[idx], positive = true)))
}

internal fun Lowering.assertSetSubsetOf(expr: SetSubsetOf) {
    val l = materializeSet(expr.left)
    val r = materializeSet(expr.right)
    // ∀ e ∈ universe(L): if e ∈ universe(R) then L[e] → R[e], else L[e] = false.
    for (i in l.universe.indices) {
        val v = l.universe[i]
        val ll = Lit.make(l.indicatorBoolIds[i], positive = true)
        val ri = r.indexOf(v)
        if (ri >= 0) {
            val rl = Lit.make(r.indicatorBoolIds[ri], positive = true)
            factors += Clause(intArrayOf(Lit.negate(ll), rl))
        } else {
            factors += Clause(intArrayOf(Lit.negate(ll)))
        }
    }
}

internal fun Lowering.assertSetDisjoint(expr: SetDisjoint) {
    val l = materializeSet(expr.left)
    val r = materializeSet(expr.right)
    // ∀ e ∈ universe(L) ∩ universe(R): ¬(L[e] ∧ R[e]).
    for (i in l.universe.indices) {
        val v = l.universe[i]
        val ri = r.indexOf(v)
        if (ri < 0) continue
        val ll = Lit.make(l.indicatorBoolIds[i], positive = true)
        val rl = Lit.make(r.indicatorBoolIds[ri], positive = true)
        factors += Clause(intArrayOf(Lit.negate(ll), Lit.negate(rl)))
    }
}

internal fun Lowering.assertSetEq(expr: SetEq) {
    val l = materializeSet(expr.left)
    val r = materializeSet(expr.right)
    val union = unionUniverse(l.universe, r.universe)
    // Per-element biconditional over the unified universe.
    for (v in union) {
        val li = l.indexOf(v)
        val ri = r.indexOf(v)
        when {
            li >= 0 && ri >= 0 -> {
                val ll = Lit.make(l.indicatorBoolIds[li], positive = true)
                val rl = Lit.make(r.indicatorBoolIds[ri], positive = true)
                factors += Clause(intArrayOf(Lit.negate(ll), rl))
                factors += Clause(intArrayOf(Lit.negate(rl), ll))
            }

            li >= 0 -> {
                factors += Clause(intArrayOf(Lit.negate(Lit.make(l.indicatorBoolIds[li], positive = true))))
            }

            ri >= 0 -> {
                factors += Clause(intArrayOf(Lit.negate(Lit.make(r.indicatorBoolIds[ri], positive = true))))
            }
        }
    }
}

internal fun Lowering.reifySetIn(expr: SetIn): Int {
    val set = materializeSet(expr.set)
    val pieces = mutableListOf<BoolExpr>()
    for (i in set.universe.indices) {
        pieces += And(
            listOf(
                IntCompare(expr.elem, IntCmpOp.EQ, IntLit(set.universe[i])),
                indicatorBoolExpr(set.indicatorBoolIds[i]),
            ),
        )
    }
    return lowerToLit(orOf(pieces))
}

internal fun Lowering.reifySetNominalIn(expr: SetNominalIn): Int {
    val setName = setRefName(expr.set)
        ?: error("reified nominal-set membership needs a nominal-set var on the right")
    val layout = setLayouts[setName] ?: error("unknown set '$setName'")
    val labels = setLabelOrder[setName]
        ?: error("set '$setName' is an integer-universe set; use IntTerm inSet form instead")
    val idx = labels.indexOf(expr.label)
    require(idx >= 0) { "label '${expr.label}' not in nominal-set '$setName'" }
    return Lit.make(layout.indicatorBoolIds[idx], positive = true)
}

internal fun Lowering.reifySetSubsetOf(expr: SetSubsetOf): Int {
    val l = materializeSet(expr.left)
    val r = materializeSet(expr.right)
    val pieces = mutableListOf<BoolExpr>()
    for (i in l.universe.indices) {
        val v = l.universe[i]
        val ll = indicatorBoolExpr(l.indicatorBoolIds[i])
        val ri = r.indexOf(v)
        pieces += if (ri >= 0) {
            Or(listOf(Not(ll), indicatorBoolExpr(r.indicatorBoolIds[ri])))
        } else {
            Not(ll)
        }
    }
    return lowerToLit(andOf(pieces))
}

internal fun Lowering.reifySetDisjoint(expr: SetDisjoint): Int {
    val l = materializeSet(expr.left)
    val r = materializeSet(expr.right)
    val pieces = mutableListOf<BoolExpr>()
    for (i in l.universe.indices) {
        val v = l.universe[i]
        val ri = r.indexOf(v)
        if (ri < 0) continue
        val ll = indicatorBoolExpr(l.indicatorBoolIds[i])
        val rl = indicatorBoolExpr(r.indicatorBoolIds[ri])
        pieces += Or(listOf(Not(ll), Not(rl)))
    }
    return if (pieces.isEmpty()) {
        trueLit()
    } else {
        lowerToLit(andOf(pieces))
    }
}

internal fun Lowering.reifySetEq(expr: SetEq): Int {
    val l = materializeSet(expr.left)
    val r = materializeSet(expr.right)
    val union = unionUniverse(l.universe, r.universe)
    val pieces = mutableListOf<BoolExpr>()
    for (v in union) {
        val li = l.indexOf(v)
        val ri = r.indexOf(v)
        pieces += when {
            li >= 0 && ri >= 0 -> Iff(
                indicatorBoolExpr(l.indicatorBoolIds[li]),
                indicatorBoolExpr(r.indicatorBoolIds[ri]),
            )

            li >= 0 -> Not(indicatorBoolExpr(l.indicatorBoolIds[li]))

            ri >= 0 -> Not(indicatorBoolExpr(r.indicatorBoolIds[ri]))

            else -> error("impossible")
        }
    }
    return lowerToLit(andOf(pieces))
}

/** Lift `card(S)` to a fresh int var whose value equals `Σ indicator_i`. */
internal fun Lowering.liftSetCard(expr: SetCard): IntRef {
    val layout = materializeSet(expr.set)
    val n = layout.size
    val aux = newAuxIntVar(IntDomain(0, n.toLong()))
    val auxId = intVarOf(aux)
    // Channel each indicator bool to a 0/1 int via ReifiedLinear (PB factor takes bool
    // literals only, but the count here is an int var), then assert Σ ints − count = 0.
    val indicatorIntVars = IntArray(n) { i ->
        val intName = newAuxIntVar(IntDomain(0, 1))
        val id = intVarOf(intName)
        // id = 1 ⟺ indicator[i] is true. Channel via ReifiedLinear on a fresh aux bool
        // tied to indicator[i] via clauses.
        val boolId = layout.indicatorBoolIds[i]
        val auxBool = newBoolVar()
        // auxBool ↔ (id = 1)
        factors += ReifiedLinear(
            auxBool,
            intArrayOf(1),
            intArrayOf(id),
            LinearOp.EQ,
            1,
        )
        // auxBool ↔ indicator[i]: two clauses.
        val auxLit = Lit.make(auxBool, positive = true)
        val indLit = Lit.make(boolId, positive = true)
        factors += Clause(intArrayOf(Lit.negate(auxLit), indLit))
        factors += Clause(intArrayOf(Lit.negate(indLit), auxLit))
        id
    }
    // Σ indicatorInts - count = 0.
    val coeffs = IntArray(n + 1) { if (it < n) 1 else -1 }
    val vars = IntArray(n + 1) { if (it < n) indicatorIntVars[it] else auxId }
    factors += Linear(coeffs, vars, LinearOp.EQ, 0)
    return IntRef(aux)
}

/** Wrap a bool var id (allocated for a set indicator) as a [BoolExpr] reusable in the
 *  AST-level Tseitin pipeline. Each indicator gets a synthetic name so the existing
 *  `BoolRef` machinery can route it through the standard bool-lookup path. */
private fun Lowering.indicatorBoolExpr(boolId: Int): BoolExpr {
    // The bool var already exists in [boolVarIdByName] indirectly via the set layout —
    // but the table is keyed by name, not id. Synthesise a name and register the
    // back-link on demand so [lowerToLit]/[BoolRef] work uniformly.
    val name = idToBoolName[boolId]
        ?: run {
            val synth = "__set_ind_$boolId"
            bindBoolName(synth, boolId)
            synth
        }
    return BoolRef(name)
}

/** Resolve a [SetExpr] to a set-var name if it's a bare [SetRef]; else null. */
private fun setRefName(expr: SetExpr): String? = (expr as? SetRef)?.name

private fun andOf(pieces: List<BoolExpr>): BoolExpr = if (pieces.size == 1) pieces[0] else And(pieces)

private fun orOf(pieces: List<BoolExpr>): BoolExpr = if (pieces.size == 1) pieces[0] else Or(pieces)
