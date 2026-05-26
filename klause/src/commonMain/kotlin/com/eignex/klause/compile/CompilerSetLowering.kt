package com.eignex.klause.compile

import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.IntCompare
import com.eignex.klause.ast.IntLit
import com.eignex.klause.ast.IntRef
import com.eignex.klause.ast.IntSum
import com.eignex.klause.ast.SetCard
import com.eignex.klause.ast.SetDiff
import com.eignex.klause.ast.SetDisjoint
import com.eignex.klause.ast.SetEq
import com.eignex.klause.ast.SetExpr
import com.eignex.klause.ast.SetIn
import com.eignex.klause.ast.SetIntersect
import com.eignex.klause.ast.SetLiteral
import com.eignex.klause.ast.SetNominalIn
import com.eignex.klause.ast.SetNominalLiteral
import com.eignex.klause.ast.SetRef
import com.eignex.klause.ast.SetSubsetOf
import com.eignex.klause.ast.SetUnion
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.SetBitsetDisjoint
import com.eignex.klause.solver.factor.SetBitsetEq
import com.eignex.klause.solver.factor.SetBitsetSubset

/** Universe-size threshold (inclusive) above which set algebra constraints route through
 *  the bulk bitset factors instead of emitting one [Clause] per universe position. The
 *  bulk path collapses N watchers into one and processes the universe as a single sweep;
 *  small universes stay on clauses since N watchers + clause learning is cheaper there. */
private const val BITSET_UNIVERSE_THRESHOLD: Int = 64

/**
 * Compiler-side layout for a set variable. Mirrors FlatZinc's `SetVarLayout`: one bool
 * per universe element, indexed in parallel arrays.
 *
 *  - `universe[i]` is the integer value of the i'th universe element. For nominal-set
 *    vars the value is the label's index in [Compiler.Build.setLabelOrder] — the actual
 *    label string lives there.
 *  - `indicatorBoolIds[i]` is the klause-side Bool var id that's true iff `universe[i]`
 *    is currently a member of the set.
 *
 * Anything that walks a [com.eignex.klause.ast.SetExpr] eventually produces one of these,
 * either by looking up an existing set var via [Compiler.Build.materializeSet] or by
 * synthesising aux indicators for `union` / `intersect` / `diff` / set literals.
 */
class SetLayout(
    val universe: IntArray,
    val indicatorBoolIds: IntArray,
) {
    init {
        require(universe.size == indicatorBoolIds.size) {
            "SetLayout parallel arrays must have equal length"
        }
    }
    val size: Int get() = universe.size

    /** Index of [value] in this layout's universe, or -1 if not present. Universe is
     *  sorted ascending so a binary search is correct. */
    fun indexOf(value: Int): Int = universe.toList().binarySearch(value).let { if (it < 0) -1 else it }
}

/**
 * Materialise a [SetExpr] into a [SetLayout]. Looks up named set vars; for compound
 * expressions (union / intersect / diff / literal) allocates aux indicator bools and
 * ties them via clauses, then returns the new layout.
 *
 * The universe of the result is the union of the operand universes (for binary ops) or
 * the literal's own element list (for [SetLiteral] / [SetNominalLiteral]). This keeps
 * the lowering uniform — every downstream consumer iterates the universe once.
 */
internal fun Compiler.Build.materializeSet(expr: SetExpr): SetLayout = when (expr) {
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
    is SetUnion -> {
        val l = materializeSet(expr.left)
        val r = materializeSet(expr.right)
        val universe = unionUniverse(l.universe, r.universe)
        val ids = IntArray(universe.size) { newBoolVar() }
        for (i in universe.indices) {
            val v = universe[i]
            val li = l.indexOf(v)
            val ri = r.indexOf(v)
            val outLit = Lit.make(ids[i], positive = true)
            // out ↔ (left[v] ∨ right[v])
            if (li >= 0 && ri >= 0) {
                val ll = Lit.make(l.indicatorBoolIds[li], positive = true)
                val rl = Lit.make(r.indicatorBoolIds[ri], positive = true)
                factors += Clause(intArrayOf(Lit.negate(ll), outLit))
                factors += Clause(intArrayOf(Lit.negate(rl), outLit))
                factors += Clause(intArrayOf(Lit.negate(outLit), ll, rl))
            } else if (li >= 0) {
                val ll = Lit.make(l.indicatorBoolIds[li], positive = true)
                factors += Clause(intArrayOf(Lit.negate(ll), outLit))
                factors += Clause(intArrayOf(Lit.negate(outLit), ll))
            } else if (ri >= 0) {
                val rl = Lit.make(r.indicatorBoolIds[ri], positive = true)
                factors += Clause(intArrayOf(Lit.negate(rl), outLit))
                factors += Clause(intArrayOf(Lit.negate(outLit), rl))
            } else {
                // Shouldn't be reachable — the universe was built from l and r.
                factors += Clause(intArrayOf(Lit.negate(outLit)))
            }
        }
        SetLayout(universe, ids)
    }
    is SetIntersect -> {
        val l = materializeSet(expr.left)
        val r = materializeSet(expr.right)
        // For ∩, only elements in both universes can be in the result; everything else is
        // forced false. We still allocate the full union to keep universe arithmetic
        // uniform downstream, but pin out-of-intersection indicators to false.
        val universe = unionUniverse(l.universe, r.universe)
        val ids = IntArray(universe.size) { newBoolVar() }
        for (i in universe.indices) {
            val v = universe[i]
            val li = l.indexOf(v)
            val ri = r.indexOf(v)
            val outLit = Lit.make(ids[i], positive = true)
            if (li >= 0 && ri >= 0) {
                val ll = Lit.make(l.indicatorBoolIds[li], positive = true)
                val rl = Lit.make(r.indicatorBoolIds[ri], positive = true)
                // out ↔ (ll ∧ rl)
                factors += Clause(intArrayOf(Lit.negate(outLit), ll))
                factors += Clause(intArrayOf(Lit.negate(outLit), rl))
                factors += Clause(intArrayOf(outLit, Lit.negate(ll), Lit.negate(rl)))
            } else {
                // Element appears in only one universe → never in intersection.
                factors += Clause(intArrayOf(Lit.negate(outLit)))
            }
        }
        SetLayout(universe, ids)
    }
    is SetDiff -> {
        val l = materializeSet(expr.left)
        val r = materializeSet(expr.right)
        // S \ T: out[v] = S[v] ∧ ¬T[v]. Universe is S's universe (anything outside S can't
        // be in the difference). Elements in S but not T's universe: out = S[v] directly.
        val universe = l.universe.copyOf()
        val ids = IntArray(universe.size) { newBoolVar() }
        for (i in universe.indices) {
            val v = universe[i]
            val outLit = Lit.make(ids[i], positive = true)
            val ll = Lit.make(l.indicatorBoolIds[i], positive = true)
            val ri = r.indexOf(v)
            if (ri >= 0) {
                val rl = Lit.make(r.indicatorBoolIds[ri], positive = true)
                // out ↔ (ll ∧ ¬rl)
                factors += Clause(intArrayOf(Lit.negate(outLit), ll))
                factors += Clause(intArrayOf(Lit.negate(outLit), Lit.negate(rl)))
                factors += Clause(intArrayOf(outLit, Lit.negate(ll), rl))
            } else {
                // out ↔ ll  (nothing to subtract for this element)
                factors += Clause(intArrayOf(Lit.negate(outLit), ll))
                factors += Clause(intArrayOf(outLit, Lit.negate(ll)))
            }
        }
        SetLayout(universe, ids)
    }
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

// -----------------------------------------------------------------------------------
//  Top-level assertions over set BoolExprs
// -----------------------------------------------------------------------------------
// `assertExpr` calls into these for set BoolExprs that appear as direct top-level
// constraints; `lowerToLit` calls the [reify*] variants below when the same expression
// shows up inside a sub-expression position.

internal fun Compiler.Build.assertSetIn(expr: SetIn) {
    val set = materializeSet(expr.set)
    // x ∈ S  ≡  ∨_{e ∈ universe(S)} (x = e ∧ S[e])
    // Top-level form: emit `∨_{e} (x = e ∧ S[e])` via Tseitin disjunction, then assert it.
    val pieces = mutableListOf<BoolExpr>()
    for (i in set.universe.indices) {
        val e = set.universe[i]
        val bId = set.indicatorBoolIds[i]
        pieces += com.eignex.klause.ast.And(listOf(
            IntCompare(expr.elem, IntCmpOp.EQ, IntLit(e)),
            indicatorBoolExpr(bId),
        ))
    }
    val expanded = if (pieces.size == 1) pieces[0] else com.eignex.klause.ast.Or(pieces)
    assertExpr(expanded)
}

internal fun Compiler.Build.assertSetNominalIn(expr: SetNominalIn) {
    val set = materializeSet(expr.set)
    val setName = setRefName(expr.set)
        ?: error("set membership of label '${expr.label}' requires a nominal-set var on the right, not a compound expression")
    val labels = setLabelOrder[setName]
        ?: error("set '$setName' is an integer-universe set; use `intVar inSet $setName` instead")
    val idx = labels.indexOf(expr.label)
    require(idx >= 0) { "label '${expr.label}' not in nominal-set '$setName'" }
    // For a nominal set, "label ∈ S" is simply: S[idx] = true.
    factors += Clause(intArrayOf(Lit.make(set.indicatorBoolIds[idx], positive = true)))
}

internal fun Compiler.Build.assertSetSubsetOf(expr: SetSubsetOf) {
    val l = materializeSet(expr.left)
    val r = materializeSet(expr.right)
    if (l.universe.size >= BITSET_UNIVERSE_THRESHOLD) {
        // Bulk path: emit one bitset-subset factor over l's universe; right is aligned by
        // value, with -1 sentinels where the value is absent from r's universe (which
        // forces l[i] = false, equivalent to the standalone unit clause the per-element
        // path would emit).
        val right = IntArray(l.universe.size) { i ->
            val ri = r.indexOf(l.universe[i])
            if (ri >= 0) r.indicatorBoolIds[ri] else -1
        }
        factors += SetBitsetSubset(l.indicatorBoolIds, right)
        return
    }
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

internal fun Compiler.Build.assertSetDisjoint(expr: SetDisjoint) {
    val l = materializeSet(expr.left)
    val r = materializeSet(expr.right)
    if (l.universe.size >= BITSET_UNIVERSE_THRESHOLD) {
        // Align r against l's universe; positions absent from r get the -1 sentinel
        // (which the disjoint factor treats as vacuous — no constraint at that position).
        val right = IntArray(l.universe.size) { i ->
            val ri = r.indexOf(l.universe[i])
            if (ri >= 0) r.indicatorBoolIds[ri] else -1
        }
        factors += SetBitsetDisjoint(l.indicatorBoolIds, right)
        return
    }
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

internal fun Compiler.Build.assertSetEq(expr: SetEq) {
    val l = materializeSet(expr.left)
    val r = materializeSet(expr.right)
    val union = unionUniverse(l.universe, r.universe)
    if (union.size >= BITSET_UNIVERSE_THRESHOLD) {
        // Align both sides against the unified universe with -1 sentinels for absent positions.
        val left = IntArray(union.size) { i ->
            val li = l.indexOf(union[i])
            if (li >= 0) l.indicatorBoolIds[li] else -1
        }
        val right = IntArray(union.size) { i ->
            val ri = r.indexOf(union[i])
            if (ri >= 0) r.indicatorBoolIds[ri] else -1
        }
        factors += SetBitsetEq(left, right)
        return
    }
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

// -----------------------------------------------------------------------------------
//  Reified set-expression lowering
// -----------------------------------------------------------------------------------

internal fun Compiler.Build.reifySetIn(expr: SetIn): Int {
    val set = materializeSet(expr.set)
    val pieces = mutableListOf<BoolExpr>()
    for (i in set.universe.indices) {
        pieces += com.eignex.klause.ast.And(listOf(
            IntCompare(expr.elem, IntCmpOp.EQ, IntLit(set.universe[i])),
            indicatorBoolExpr(set.indicatorBoolIds[i]),
        ))
    }
    return lowerToLit(if (pieces.size == 1) pieces[0] else com.eignex.klause.ast.Or(pieces))
}

internal fun Compiler.Build.reifySetNominalIn(expr: SetNominalIn): Int {
    val setName = setRefName(expr.set)
        ?: error("reified nominal-set membership needs a nominal-set var on the right")
    val layout = setLayouts[setName] ?: error("unknown set '$setName'")
    val labels = setLabelOrder[setName]
        ?: error("set '$setName' is an integer-universe set; use IntTerm inSet form instead")
    val idx = labels.indexOf(expr.label)
    require(idx >= 0) { "label '${expr.label}' not in nominal-set '$setName'" }
    return Lit.make(layout.indicatorBoolIds[idx], positive = true)
}

internal fun Compiler.Build.reifySetSubsetOf(expr: SetSubsetOf): Int {
    val l = materializeSet(expr.left)
    val r = materializeSet(expr.right)
    val pieces = mutableListOf<BoolExpr>()
    for (i in l.universe.indices) {
        val v = l.universe[i]
        val ll = indicatorBoolExpr(l.indicatorBoolIds[i])
        val ri = r.indexOf(v)
        pieces += if (ri >= 0) {
            com.eignex.klause.ast.Or(listOf(com.eignex.klause.ast.Not(ll), indicatorBoolExpr(r.indicatorBoolIds[ri])))
        } else {
            com.eignex.klause.ast.Not(ll)
        }
    }
    return lowerToLit(if (pieces.size == 1) pieces[0] else com.eignex.klause.ast.And(pieces))
}

internal fun Compiler.Build.reifySetDisjoint(expr: SetDisjoint): Int {
    val l = materializeSet(expr.left)
    val r = materializeSet(expr.right)
    val pieces = mutableListOf<BoolExpr>()
    for (i in l.universe.indices) {
        val v = l.universe[i]
        val ri = r.indexOf(v)
        if (ri < 0) continue
        val ll = indicatorBoolExpr(l.indicatorBoolIds[i])
        val rl = indicatorBoolExpr(r.indicatorBoolIds[ri])
        pieces += com.eignex.klause.ast.Or(listOf(com.eignex.klause.ast.Not(ll), com.eignex.klause.ast.Not(rl)))
    }
    return if (pieces.isEmpty()) trueLit()
    else lowerToLit(if (pieces.size == 1) pieces[0] else com.eignex.klause.ast.And(pieces))
}

internal fun Compiler.Build.reifySetEq(expr: SetEq): Int {
    val l = materializeSet(expr.left)
    val r = materializeSet(expr.right)
    val union = unionUniverse(l.universe, r.universe)
    val pieces = mutableListOf<BoolExpr>()
    for (v in union) {
        val li = l.indexOf(v)
        val ri = r.indexOf(v)
        pieces += when {
            li >= 0 && ri >= 0 -> com.eignex.klause.ast.Iff(
                indicatorBoolExpr(l.indicatorBoolIds[li]),
                indicatorBoolExpr(r.indicatorBoolIds[ri]),
            )
            li >= 0 -> com.eignex.klause.ast.Not(indicatorBoolExpr(l.indicatorBoolIds[li]))
            ri >= 0 -> com.eignex.klause.ast.Not(indicatorBoolExpr(r.indicatorBoolIds[ri]))
            else -> error("impossible")
        }
    }
    return lowerToLit(if (pieces.size == 1) pieces[0] else com.eignex.klause.ast.And(pieces))
}

// -----------------------------------------------------------------------------------
//  SetCard lift
// -----------------------------------------------------------------------------------

/** Lift `card(S)` to a fresh int var whose value equals `Σ indicator_i`. */
internal fun Compiler.Build.liftSetCard(expr: SetCard): IntRef {
    val layout = materializeSet(expr.set)
    val n = layout.size
    val aux = newAuxIntVar(IntDomain(0, n))
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
        factors += com.eignex.klause.solver.factor.ReifiedLinear(
            auxBool, intArrayOf(1), intArrayOf(id), LinearOp.EQ, 1,
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

// -----------------------------------------------------------------------------------
//  Helpers
// -----------------------------------------------------------------------------------

/** Wrap a bool var id (allocated for a set indicator) as a [BoolExpr] reusable in the
 *  AST-level Tseitin pipeline. Each indicator gets a synthetic name so the existing
 *  `BoolRef` machinery can route it through the standard bool-lookup path. */
private fun Compiler.Build.indicatorBoolExpr(boolId: Int): BoolExpr {
    // The bool var already exists in [boolVarIdByName] indirectly via the set layout —
    // but the table is keyed by name, not id. Synthesise a name and register the
    // back-link on demand so [lowerToLit]/[BoolRef] work uniformly.
    val name = boolVarIdByName.entries.firstOrNull { it.value == boolId }?.key
        ?: run {
            val synth = "__set_ind_$boolId"
            boolVarIdByName[synth] = boolId
            synth
        }
    return com.eignex.klause.ast.BoolRef(name)
}

/** Resolve a [SetExpr] to a set-var name if it's a bare [SetRef]; else null. */
private fun setRefName(expr: SetExpr): String? = (expr as? SetRef)?.name
