package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.util.binarySearchInt

// ===== Set predicates: bool-indicator decomposition =====
//
// Each `var set of E: S` was materialised by [allocSetVar] into one bool per universe
// element. The helpers below lower set algebra to bool algebra over those indicators —
// every set constraint reduces to clauses, cardinality, or pseudo-Boolean sums we already
// have factors for.

/** Resolve a set var reference to its layout. Accepts plain idents (`s`), array accesses
 *  (`a(2)`) into a set-var array, and set literals (`1..3`, `{1,3}`, or a set-param ident)
 *  — literals are materialised as anonymous pinned SetVarLayouts so downstream set algebra
 *  treats them uniformly. */
internal fun FlatZincCompiler.resolveSetVar(e: FznExpr): SetVarLayout = when (e) {
    is FznExpr.Ident -> setVarsByName[e.name] ?: run {
        // Could be a set parameter (e.g. `set of int: u = {1,3}`) flowing into a set
        // constraint. Lower it to a pinned layout on the fly.
        val members = resolveSetLiteral(e)
        materialisePinnedSetLayout("__set_param_${e.name}", members)
    }

    is FznExpr.ArrayAccess -> {
        val arr = arrays[e.name] as? FlatZincArray.SetVars
            ?: failHere("`${e.name}` is not a set var array")
        arr.layouts[e.index - 1]
    }

    is FznExpr.IntSetLit, is FznExpr.IntRangeLit -> {
        val members = resolveSetLiteral(e)
        materialisePinnedSetLayout("__set_lit_${setVarsByName.size}", members)
    }

    else -> failHere("expected a set var reference, got ${e::class.simpleName}")
}

/** Allocate a fresh SetVarLayout whose universe is exactly [members] (sorted), with every
 *  indicator pinned via a unit clause. Used to lift set literals into the indicator-bool
 *  representation set constraints expect. The empty case still allocates one dummy
 *  indicator (pinned false) so universes never have size 0. */
private fun FlatZincCompiler.materialisePinnedSetLayout(name: String, members: IntArray): SetVarLayout {
    val universe = if (members.isEmpty()) intArrayOf(0) else members
    val indicatorIds = IntArray(universe.size) { i ->
        allocBool("${name}_${universe[i]}")
    }
    val layout = SetVarLayout(name, universe, indicatorIds)
    setVarsByName[name] = layout
    for (i in universe.indices) {
        val inSet = if (members.isEmpty()) false else members.binarySearchInt(universe[i]) >= 0
        factors.add(Clause(intArrayOf(Lit.make(indicatorIds[i], inSet))))
    }
    return layout
}

/** Resolve a set-literal expression to its element list (sorted ascending). */
internal fun FlatZincCompiler.resolveSetLiteral(e: FznExpr): IntArray = when (e) {
    is FznExpr.IntSetLit -> e.values.map { it.toInt() }.toIntArray().also { it.sort() }

    is FznExpr.IntRangeLit -> IntArray((e.hi - e.lo + 1).toInt()) { (e.lo + it).toInt() }

    is FznExpr.Ident -> {
        // Parameter set: resolve via params map.
        val pv = params[e.name] ?: failHere("undefined set parameter `${e.name}`")
        when (pv) {
            is FlatZincCompiler.ParamValue.IntSet -> pv.values.map { it.toInt() }.toIntArray().also { it.sort() }
            else -> failHere("`${e.name}` is not a set parameter")
        }
    }

    else -> failHere("expected a set literal, got ${e::class.simpleName}")
}

/** `set_in(x, S)` and `set_in_reif(x, S, r)`. Two forms depending on `x`:
 *   - Constant int: direct lookup of the indicator bool for that element.
 *   - Var int: per-element channel `chanᵥ ↔ (x = v)` for every `v` in `x`'s domain,
 *     plus the constraint `chanᵥ ⇒ indicatorᵥ` for v ∈ universe(S) and `chanᵥ = false`
 *     for v ∉ universe(S). For reified, `r ↔ ⋁ (chanᵥ ∧ indicatorᵥ)` via aux ANDs.
 */
internal fun FlatZincCompiler.emitSetIn(c: FznConstraint, reified: Boolean) {
    require(c.args.size == if (reified) 3 else 2)
    val elem = c.args[0]
    val sExpr = c.args[1]
    val rExpr = if (reified) c.args[2] else null
    // Set-literal RHS: `set_in(x, {v1..vn})` or `set_in(x, lo..hi)`. The MZN compiler
    // produces this directly for `x in S` where S is a parameter set; the set-var
    // indicator layout from `resolveSetVar` is the wrong abstraction here, so handle
    // it inline against the literal values.
    if (sExpr is FznExpr.IntSetLit || sExpr is FznExpr.IntRangeLit) {
        val values = resolveSetLiteral(sExpr)
        emitSetInLiteral(elem, values, rExpr)
        return
    }
    val layout = resolveSetVar(sExpr)
    if (elem is FznExpr.IntLit) {
        emitSetInConst(elem.value.toInt(), layout, rExpr)
        return
    }
    // Var-int element path.
    val xVar = resolveIntVar(elem)
    val dom = intDomains[xVar]
    emitSetInVarInt(xVar, dom.min, dom.max, layout, rExpr)
}

/** `set_in(x, S)` where S is a constant set literal. Two cases by `x`:
 *   - Constant int: trivial — non-reified passes/fails immediately; reified pins `r` to the
 *     membership truth value.
 *   - Var int: decompose to `r ↔ ⋁ (x = vᵢ)` for vᵢ ∈ S via a fresh chan bool per element
 *     + ReifiedCardinality.atLeastOne. Non-reified path forces the disjunction with a plain
 *     Cardinality. Values outside `x`'s current domain contribute no chan (always false). */
private fun FlatZincCompiler.emitSetInLiteral(elem: FznExpr, values: IntArray, rExpr: FznExpr?) {
    if (elem is FznExpr.IntLit) {
        val v = elem.value.toInt()
        val isMember = values.binarySearchInt(v) >= 0
        if (rExpr != null) {
            val r = resolveBoolLit(rExpr)
            factors.add(Clause(intArrayOf(if (isMember) r else Lit.negate(r))))
        } else {
            if (!isMember) failHere("set_in: element $v outside literal set $values")
        }
        return
    }
    val xVar = resolveIntVar(elem)
    val dom = intDomains[xVar]
    val membershipLits = ArrayList<Int>()
    for (v in values) {
        // Skip values not in x's domain — including interior holes, not just values
        // outside [min, max]. A hole value can never make x ∈ S true, so it contributes
        // no membership literal; emitting a chan + ReifiedLinear(chan ↔ x = hole) for it
        // is at best dead weight and accumulating many such forced-false reifications has
        // tripped a false-UNSAT in the engine (klause `is/1YHXeG1xYs`).
        if (v !in dom) continue
        val chan = allocBool("__set_in_lit_chan_${xVar}_$v")
        factors.add(
            ReifiedLinear(
                auxBoolVar = chan,
                coeffs = intArrayOf(1),
                vars = intArrayOf(xVar),
                op = LinearOp.EQ,
                bound = v,
            ),
        )
        membershipLits += Lit.make(chan, true)
    }
    if (rExpr == null) {
        // Non-reified: at least one chan must be true (i.e. x ∈ S). If S is empty given
        // the domain, this is trivially unsatisfiable — emit an empty disjunction (false).
        if (membershipLits.isEmpty()) {
            factors.add(Clause(IntArray(0)))
            return
        }
        factors.add(Cardinality.atLeastOne(membershipLits.toIntArray()))
        return
    }
    val r = resolveBoolLit(rExpr)
    if (membershipLits.isEmpty()) {
        // r ↔ false → r must be false.
        factors.add(Clause(intArrayOf(Lit.negate(r))))
        return
    }
    factors.add(
        ReifiedCardinality(
            auxBoolVar = Lit.variable(r),
            literals = membershipLits.toIntArray(),
            min = 1,
            max = membershipLits.size,
        ),
    )
    // Lit.variable strips polarity; if r is negated, flip the reification by swapping
    // the cardinality bound (atLeastOne ↔ exactlyZero on a negated reification).
    if (!Lit.isPositive(r)) {
        // Replace the just-added factor with the negated form: ¬r ↔ ⋁ → r ↔ ¬⋁.
        // Encoded as: ¬r ⇒ all chan false, and r ⇒ some chan true. The simplest
        // equivalent is ReifiedCardinality with min=0,max=0 on the negated aux —
        // but we already emitted the positive form. Add the complement clause set.
        factors.removeAt(factors.size - 1)
        factors.add(
            ReifiedCardinality(
                auxBoolVar = Lit.variable(r),
                literals = membershipLits.toIntArray(),
                min = 0,
                max = 0,
            ),
        )
    }
}

private fun FlatZincCompiler.emitSetInConst(xConst: Int, layout: SetVarLayout, rExpr: FznExpr?) {
    val idx = layout.elements.binarySearchInt(xConst)
    if (idx < 0) {
        if (rExpr != null) {
            val r = resolveBoolLit(rExpr)
            factors.add(Clause(intArrayOf(Lit.negate(r))))
        } else {
            failHere("set_in: element $xConst outside set `${layout.name}`'s universe")
        }
        return
    }
    val indicator = layout.indicatorBoolIds[idx]
    if (rExpr != null) {
        val r = resolveBoolLit(rExpr)
        factors.add(Clause(intArrayOf(Lit.negate(r), Lit.make(indicator, true))))
        factors.add(Clause(intArrayOf(r, Lit.make(indicator, false))))
    } else {
        factors.add(Clause(intArrayOf(Lit.make(indicator, true))))
    }
}

private fun FlatZincCompiler.emitSetInVarInt(xVar: Int, xLo: Int, xHi: Int, layout: SetVarLayout, rExpr: FznExpr?) {
    val membershipLits = ArrayList<Int>()
    for (v in xLo..xHi) {
        // chanᵥ ↔ (x = v) via int_lin_eq_reif([1], [x], v, chanᵥ).
        val chan = allocBool("__set_in_chan_${layout.name}_$v")
        factors.add(
            ReifiedLinear(
                auxBoolVar = chan,
                coeffs = intArrayOf(1),
                vars = intArrayOf(xVar),
                op = LinearOp.EQ,
                bound = v,
            ),
        )
        val setIdx = layout.elements.binarySearchInt(v)
        if (rExpr == null) {
            // Non-reified: x ∈ S must hold.
            if (setIdx < 0) {
                // x = v would put x outside S's universe → forbid.
                factors.add(Clause(intArrayOf(Lit.make(chan, false))))
            } else {
                // chanᵥ ⇒ indicatorᵥ : (¬chanᵥ ∨ indicatorᵥ)
                factors.add(
                    Clause(
                        intArrayOf(
                            Lit.make(chan, false),
                            Lit.make(layout.indicatorBoolIds[setIdx], true),
                        ),
                    ),
                )
            }
        } else {
            // Reified: build per-element AND aux_v ↔ (chanᵥ ∧ indicatorᵥ), then r ↔ ⋁ aux_v.
            if (setIdx < 0) {
                // chanᵥ → r = false. Captured by membershipLits not including v.
                // No clause needed: aux_v would be permanently false.
                continue
            }
            val ind = layout.indicatorBoolIds[setIdx]
            val aux = allocBool("__set_in_aux_${layout.name}_$v")
            // aux ↔ (chan ∧ indicator): (¬aux ∨ chan), (¬aux ∨ indicator), (aux ∨ ¬chan ∨ ¬indicator).
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(chan, true))))
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(ind, true))))
            factors.add(Clause(intArrayOf(Lit.make(aux, true), Lit.make(chan, false), Lit.make(ind, false))))
            membershipLits += Lit.make(aux, true)
        }
    }
    if (rExpr != null) {
        val r = resolveBoolLit(rExpr)
        // r ↔ ⋁ membershipLits.
        val lits = membershipLits.toIntArray()
        if (lits.isEmpty()) {
            // No element of x's domain is in S's universe → r must be false.
            factors.add(Clause(intArrayOf(Lit.negate(r))))
        } else {
            // r → ⋁ aux : (¬r ∨ aux1 ∨ ... ∨ auxn)
            factors.add(Clause(intArrayOf(Lit.negate(r)) + lits))
            // each auxᵢ → r : (¬auxᵢ ∨ r)
            for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(l), r)))
        }
    }
}

/** `set_subset(S, T)` / `set_subset_reif(S, T, r)`. Non-reified: for each universe element
 *  of S, `Sᵢ ⇒ Tᵢ` (with elements outside T's universe forced absent from S).
 *  Reified: per element, channel `auxᵢ ↔ (Sᵢ ⇒ Tᵢ)`, then `r ↔ ⋀ auxᵢ`. */
internal fun FlatZincCompiler.emitSetSubset(c: FznConstraint, reified: Boolean) {
    require(c.args.size == if (reified) 3 else 2)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    if (!reified) {
        for (i in s.elements.indices) {
            val e = s.elements[i]
            val sBit = s.indicatorBoolIds[i]
            val tIdx = t.elements.binarySearchInt(e)
            if (tIdx < 0) {
                factors.add(Clause(intArrayOf(Lit.make(sBit, false))))
            } else {
                factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(t.indicatorBoolIds[tIdx], true))))
            }
        }
        return
    }
    val r = resolveBoolLit(c.args[2])
    val auxes = ArrayList<Int>(s.elements.size)
    for (i in s.elements.indices) {
        val sBit = s.indicatorBoolIds[i]
        val tIdx = t.elements.binarySearchInt(s.elements[i])
        val aux = allocBool("__subset_aux_${s.name}_${t.name}_${s.elements[i]}")
        auxes += Lit.make(aux, true)
        if (tIdx < 0) {
            // aux ↔ ¬Sᵢ : two clauses.
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(sBit, false))))
            factors.add(Clause(intArrayOf(Lit.make(aux, true), Lit.make(sBit, true))))
        } else {
            val tBit = t.indicatorBoolIds[tIdx]
            // aux ↔ (¬Sᵢ ∨ Tᵢ): three clauses.
            // (¬aux ∨ ¬Sᵢ ∨ Tᵢ), (Sᵢ ∨ aux), (¬Tᵢ ∨ aux)
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(sBit, false), Lit.make(tBit, true))))
            factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(aux, true))))
            factors.add(Clause(intArrayOf(Lit.make(tBit, false), Lit.make(aux, true))))
        }
    }
    reifyAndOfLits(auxes.toIntArray(), r)
}

/** `r ↔ ⋀ lits`. Decomposes to one big OR (the "r false implies some lit false" direction)
 *  plus one binary clause per lit (the "r true implies lit true" direction). */
internal fun FlatZincCompiler.reifyAndOfLits(lits: IntArray, r: Int) {
    factors.add(Clause(lits.map { Lit.negate(it) }.toIntArray() + intArrayOf(r)))
    for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(r), l)))
}

/** `set_eq(S, T)` / `set_eq_reif(S, T, r)`. Non-reified: per element of S ∪ T's universe,
 *  `Sᵢ ↔ Tᵢ`; elements only in one universe force the corresponding indicator false.
 *  Reified: per element, channel `auxᵢ ↔ (Sᵢ ↔ Tᵢ)`, then `r ↔ ⋀ auxᵢ`. */
internal fun FlatZincCompiler.emitSetEq(c: FznConstraint, reified: Boolean) {
    require(c.args.size == if (reified) 3 else 2)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    if (!reified) {
        for (i in s.elements.indices) {
            val e = s.elements[i]
            val sBit = s.indicatorBoolIds[i]
            val tIdx = t.elements.binarySearchInt(e)
            if (tIdx < 0) {
                factors.add(Clause(intArrayOf(Lit.make(sBit, false))))
            } else {
                val tBit = t.indicatorBoolIds[tIdx]
                factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(tBit, true))))
                factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(tBit, false))))
            }
        }
        for (i in t.elements.indices) {
            if (s.elements.binarySearchInt(t.elements[i]) < 0) {
                factors.add(Clause(intArrayOf(Lit.make(t.indicatorBoolIds[i], false))))
            }
        }
        return
    }
    val r = resolveBoolLit(c.args[2])
    val auxes = ArrayList<Int>()
    val emitEqAux: (Int, Int, Int) -> Unit = { sBit, tBit, aux ->
        // aux ↔ (Sᵢ = Tᵢ): four clauses from the truth table.
        // (S=0,T=0,aux=0): S∨T∨aux. (0,1,1): S∨¬T∨¬aux.
        // (1,0,1): ¬S∨T∨¬aux. (1,1,0): ¬S∨¬T∨aux.
        factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(tBit, true), Lit.make(aux, true))))
        factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(tBit, false), Lit.make(aux, false))))
        factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(tBit, true), Lit.make(aux, false))))
        factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(tBit, false), Lit.make(aux, true))))
    }
    for (i in s.elements.indices) {
        val sBit = s.indicatorBoolIds[i]
        val tIdx = t.elements.binarySearchInt(s.elements[i])
        val aux = allocBool("__eq_aux_${s.name}_${t.name}_${s.elements[i]}")
        auxes.add(Lit.make(aux, true))
        if (tIdx < 0) {
            // No counterpart in T → aux ↔ ¬Sᵢ.
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(sBit, false))))
            factors.add(Clause(intArrayOf(Lit.make(aux, true), Lit.make(sBit, true))))
        } else {
            emitEqAux(sBit, t.indicatorBoolIds[tIdx], aux)
        }
    }
    for (i in t.elements.indices) {
        if (s.elements.binarySearchInt(t.elements[i]) < 0) {
            val tBit = t.indicatorBoolIds[i]
            val aux = allocBool("__eq_aux_${s.name}_${t.name}_only_t_${t.elements[i]}")
            auxes.add(Lit.make(aux, true))
            // aux ↔ ¬Tᵢ.
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(tBit, false))))
            factors.add(Clause(intArrayOf(Lit.make(aux, true), Lit.make(tBit, true))))
        }
    }
    reifyAndOfLits(auxes.toIntArray(), r)
}

/** `set_ne(S, T)` / `set_ne_reif(S, T, r)`. Allocates an `eq` aux mirroring `set_eq_reif`'s
 *  channel, then ties `r = ¬eq` (reified) or `eq = false` (non-reified). */
internal fun FlatZincCompiler.emitSetNe(c: FznConstraint, reified: Boolean) {
    require(c.args.size == if (reified) 3 else 2)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    // Allocate the eq-channel and run the per-element decomposition over it.
    val eqLit = if (reified) {
        val r = resolveBoolLit(c.args[2])
        val eqAux = allocBool("__set_ne_eq_${s.name}_${t.name}")
        val eqLit = Lit.make(eqAux, true)
        // r ↔ ¬eq:  (r ∨ eq), (¬r ∨ ¬eq)
        factors.add(Clause(intArrayOf(r, eqLit)))
        factors.add(Clause(intArrayOf(Lit.negate(r), Lit.negate(eqLit))))
        eqLit
    } else {
        // Hard inequality: force eq = false.
        val eqAux = allocBool("__set_ne_eq_${s.name}_${t.name}")
        val eqLit = Lit.make(eqAux, true)
        factors.add(Clause(intArrayOf(Lit.negate(eqLit))))
        eqLit
    }
    emitSetEqChannel(s, t, eqLit)
}

/** Resolve a set-var-array argument to a list of [SetVarLayout]. Accepts either an array
 *  name (registered as [FlatZincArray.SetVars]) or an inline array literal of set var idents. */
internal fun FlatZincCompiler.resolveSetVarArray(e: FznExpr): List<SetVarLayout> = when (e) {
    is FznExpr.Ident -> when (val a = arrays[e.name]) {
        is FlatZincArray.SetVars -> a.layouts
        else -> failHere("`${e.name}` is not an array of set vars")
    }

    is FznExpr.ArrayLit -> e.elements.map { resolveSetVar(it) }

    else -> failHere("expected an array of set vars, got ${e::class.simpleName}")
}

/**
 * `set_le(S, T)` / `set_lt(S, T)` (+ `_reif`) — lex-of-sorted-elements comparison from
 * MiniZinc's `std/nosets.mzn`. Walks the joint universe top-down; at each element `e`,
 * cascade-equality flows from the higher level unless one side has `e` and the other
 * doesn't — then the comparison is determined by whether the side-without-e has any
 * larger element, captured by per-set `max` ints.
 *
 * Decomposition (per the MZN std impl):
 *   - Let `U` = sorted ascending union of S.universe ∪ T.universe.
 *   - For S and T, alloc `xmax`/`ymax` int vars = max(set ∪ {U(0)-1}). Channel each
 *     indicator bool to a 0/1 int and use ArrayMax.
 *   - Allocate `b(i)` bool for each position i in U, representing "lex-≤ considering
 *     only elements ≥ `U(i)`".
 *   - Top of the table: `b(u)` = `S_has(u) → T_has(u)` (last position).
 *   - Inner i: 4-case truth-table over (S_has, T_has):
 *       `(0,0) → b(i) = b(i+1)`
 *       `(0,1) → b(i) = (xmax < U(i))` — S has nothing ≥ Uᵢ, T has Uᵢ, S<T
 *       `(1,0) → b(i) = (ymax > U(i))` — S has Uᵢ, T must have larger, else S>T
 *       `(1,1) → b(i) = b(i+1)`
 *   - Result: `b(0)` is the lex-≤ verdict. For `set_lt` (strict), the final bit is
 *     `b(0) ∧ ¬(S = T)` — implemented by reifying set_eq as an aux and combining.
 */
internal fun FlatZincCompiler.emitSetLex(c: FznConstraint, strict: Boolean, reified: Boolean) {
    require(c.args.size == if (reified) 3 else 2)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    val universe = (s.elements.toSet() + t.elements.toSet()).sorted().toIntArray()
    if (universe.isEmpty()) {
        // Both sets are over empty universes: lex-≤ trivially holds, strict-≤ fails.
        if (reified) {
            val r = resolveBoolLit(c.args[2])
            val lit = if (strict) Lit.negate(r) else r
            factors.add(Clause(intArrayOf(lit)))
        } else if (strict) {
            factors.add(Clause(IntArray(0)))
        }
        return
    }
    // Allocate xmax/ymax via ArrayMax over channel int vars: channel(i) = element if
    // indicator true else (universe(0) - 1).
    val lo = universe.first() - 1
    val hi = universe.last()
    fun maxOf(set: SetVarLayout, label: String): Int {
        // For each universe element, find the indicator (or skip if not in set's universe).
        // Channel vars: `c(i) in [lo, hi]`, c(i) = element if indicator(i) true else lo.
        val channels = IntArray(set.elements.size)
        for (i in set.elements.indices) {
            val elem = set.elements[i]
            val ind = set.indicatorBoolIds[i]
            val ch = allocInt("__setlex_${label}_${set.name}_$elem", lo, hi)
            channels[i] = ch
            // ind=true  ⇒ ch=elem;   ind=false ⇒ ch=lo
            // Encoded as ReifiedLinear(ind, [1] * [ch], EQ, elem) plus
            //            ReifiedLinear(¬ind, [1] * [ch], EQ, lo).
            factors.add(
                ReifiedLinear(
                    ind,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(ch),
                    op = LinearOp.EQ,
                    bound = elem,
                ),
            )
            // For "ind=false ⇒ ch=lo", allocate negation aux.
            val negInd = allocBool("__setlex_${label}_neg_${set.name}_${elem}_${factors.size}")
            factors.add(
                Clause(
                    intArrayOf(
                        Lit.make(ind, true),
                        Lit.make(negInd, true),
                    ),
                ),
            )
            factors.add(
                Clause(
                    intArrayOf(
                        Lit.make(ind, false),
                        Lit.make(negInd, false),
                    ),
                ),
            )
            factors.add(
                ReifiedLinear(
                    negInd,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(ch),
                    op = LinearOp.EQ,
                    bound = lo,
                ),
            )
        }
        val maxVar = allocInt("__setlex_${label}max_${set.name}_${factors.size}", lo, hi)
        if (channels.isEmpty()) {
            // Empty set universe: max is lo by definition.
            factors.add(Linear(intArrayOf(1), intArrayOf(maxVar), LinearOp.EQ, lo))
        } else {
            factors.add(ArrayMinMax(maxVar, channels, max = true))
        }
        return maxVar
    }
    val xmax = maxOf(s, "x")
    val ymax = maxOf(t, "y")
    // Allocate b(i) bools for i = 0..U.size-1.
    val b = IntArray(universe.size) { allocBool("__setlex_b_${s.name}_${t.name}_${universe[it]}_${factors.size}") }
    val emptyLit = Lit

    // Lookup S/T indicator (or null if elem not in that set's universe).
    fun indicator(set: SetVarLayout, elem: Int): Int? {
        val idx = set.elements.binarySearchInt(elem)
        return if (idx < 0) null else set.indicatorBoolIds[idx]
    }
    // Top of table: b(last) = (S_has(last) → T_has(last))  ≡  (¬S_has ∨ T_has).
    run {
        val last = universe.size - 1
        val sLit = indicator(s, universe[last])
        val tLit = indicator(t, universe[last])
        val sHas = if (sLit != null) Lit.make(sLit, true) else null
        val tHas = if (tLit != null) Lit.make(tLit, true) else null
        when {
            sHas == null && tHas == null -> {
                // Neither set has the element: b(last) = true trivially.
                factors.add(Clause(intArrayOf(emptyLit.make(b[last], true))))
            }

            sHas == null -> {
                // S can't have it: implication is vacuously true → b(last) = true.
                factors.add(Clause(intArrayOf(emptyLit.make(b[last], true))))
            }

            tHas == null -> {
                // T can't have it: b(last) = ¬S_has.
                // b ↔ ¬s_has: (b ∨ s_has) ∧ (¬b ∨ ¬s_has)
                factors.add(Clause(intArrayOf(emptyLit.make(b[last], true), sHas)))
                factors.add(
                    Clause(
                        intArrayOf(emptyLit.make(b[last], false), emptyLit.negate(sHas)),
                    ),
                )
            }

            else -> {
                // b ↔ (¬s_has ∨ t_has):
                // (¬b ∨ ¬s_has ∨ t_has) ∧ (b ∨ s_has) ∧ (b ∨ ¬t_has)
                factors.add(
                    Clause(
                        intArrayOf(emptyLit.make(b[last], false), emptyLit.negate(sHas), tHas),
                    ),
                )
                factors.add(Clause(intArrayOf(emptyLit.make(b[last], true), sHas)))
                factors.add(
                    Clause(
                        intArrayOf(emptyLit.make(b[last], true), emptyLit.negate(tHas)),
                    ),
                )
            }
        }
    }
    // Inner positions i = u-2 down to 0.
    for (i in universe.size - 2 downTo 0) {
        val elem = universe[i]
        val sLit = indicator(s, elem)
        val tLit = indicator(t, elem)
        // Allocate the (xmax < elem) and (ymax > elem) reified aux bools as needed.
        val xmaxLessLit: Int by lazy {
            val aux = allocBool("__setlex_xmaxlt_${elem}_${factors.size}")
            // aux ↔ (xmax ≤ elem-1)  ≡  Linear coeffs [1] vars [xmax] op LE bound elem-1
            factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(xmax), LinearOp.LE, elem - 1))
            emptyLit.make(aux, true)
        }
        val ymaxGreaterLit: Int by lazy {
            val aux = allocBool("__setlex_ymaxgt_${elem}_${factors.size}")
            // aux ↔ (ymax ≥ elem+1)  ≡  Linear coeffs [-1] vars [ymax] op LE bound -(elem+1)
            factors.add(ReifiedLinear(aux, intArrayOf(-1), intArrayOf(ymax), LinearOp.LE, -(elem + 1)))
            emptyLit.make(aux, true)
        }
        val bi = emptyLit.make(b[i], true)
        val nbi = emptyLit.make(b[i], false)
        val bn = emptyLit.make(b[i + 1], true)
        val nbn = emptyLit.make(b[i + 1], false)
        // Encode the 4-case truth table as case-conditioned biconditionals.
        // Effectively: b(i) = (S_has ⊕ T_has) ? (S_has ? ymax>elem : xmax<elem) : b(i+1).
        // For each combo of (S_has, T_has) ∈ {present, absent}, post the rule.
        val sHas = if (sLit != null) emptyLit.make(sLit, true) else null
        val tHas = if (tLit != null) emptyLit.make(tLit, true) else null
        when {
            sHas == null && tHas == null -> {
                // (0,0) → b(i) = b(i+1)
                factors.add(Clause(intArrayOf(nbi, bn)))
                factors.add(Clause(intArrayOf(bi, nbn)))
            }

            sHas == null -> {
                // S can't have elem: case is (0, T_has). T_has=0 → b(i)=b(i+1); T_has=1 → b(i)=(xmax<elem).
                // Implications guarded by tHas/¬tHas:
                // (¬tHas → b(i)=b(i+1)): (tHas ∨ ¬b(i) ∨ b(i+1)) ∧ (tHas ∨ b(i) ∨ ¬b(i+1))
                factors.add(Clause(intArrayOf(requireNotNull(tHas), nbi, bn)))
                factors.add(Clause(intArrayOf(tHas, bi, nbn)))
                // (tHas → b(i)=xmaxLess): (¬tHas ∨ ¬b(i) ∨ xmaxLessLit) ∧ (¬tHas ∨ b(i) ∨ ¬xmaxLessLit)
                factors.add(Clause(intArrayOf(emptyLit.negate(tHas), nbi, xmaxLessLit)))
                factors.add(
                    Clause(
                        intArrayOf(emptyLit.negate(tHas), bi, emptyLit.negate(xmaxLessLit)),
                    ),
                )
            }

            tHas == null -> {
                // T can't have elem: case is (S_has, 0). S_has=0 → b(i)=b(i+1); S_has=1 → b(i)=(ymax>elem).
                factors.add(Clause(intArrayOf(sHas, nbi, bn)))
                factors.add(Clause(intArrayOf(sHas, bi, nbn)))
                factors.add(
                    Clause(intArrayOf(emptyLit.negate(sHas), nbi, ymaxGreaterLit)),
                )
                factors.add(
                    Clause(
                        intArrayOf(emptyLit.negate(sHas), bi, emptyLit.negate(ymaxGreaterLit)),
                    ),
                )
            }

            else -> {
                // Both indicators exist. Four sub-cases by (S_has, T_has):
                // (0,0): both absent → b(i)=b(i+1)  | guard: ¬S ∧ ¬T
                // (0,1): only T     → b(i)=xmax<i  | guard: ¬S ∧  T
                // (1,0): only S     → b(i)=ymax>i  | guard:  S ∧ ¬T
                // (1,1): both       → b(i)=b(i+1)  | guard:  S ∧  T
                // Implication (¬S ∧ ¬T) → (b(i) ↔ b(i+1)):
                factors.add(Clause(intArrayOf(sHas, tHas, nbi, bn)))
                factors.add(Clause(intArrayOf(sHas, tHas, bi, nbn)))
                // (¬S ∧ T) → (b(i) ↔ xmaxLess):
                factors.add(
                    Clause(intArrayOf(sHas, emptyLit.negate(tHas), nbi, xmaxLessLit)),
                )
                factors.add(
                    Clause(
                        intArrayOf(sHas, emptyLit.negate(tHas), bi, emptyLit.negate(xmaxLessLit)),
                    ),
                )
                // (S ∧ ¬T) → (b(i) ↔ ymaxGreater):
                factors.add(
                    Clause(
                        intArrayOf(emptyLit.negate(sHas), tHas, nbi, ymaxGreaterLit),
                    ),
                )
                factors.add(
                    Clause(
                        intArrayOf(emptyLit.negate(sHas), tHas, bi, emptyLit.negate(ymaxGreaterLit)),
                    ),
                )
                // (S ∧ T) → (b(i) ↔ b(i+1)):
                factors.add(
                    Clause(
                        intArrayOf(emptyLit.negate(sHas), emptyLit.negate(tHas), nbi, bn),
                    ),
                )
                factors.add(
                    Clause(
                        intArrayOf(emptyLit.negate(sHas), emptyLit.negate(tHas), bi, nbn),
                    ),
                )
            }
        }
    }
    // Verdict: r ↔ b(0) for set_le. For set_lt, r ↔ (b(0) ∧ ¬(s = t)).
    val verdict = b[0]
    if (!strict && !reified) {
        // Non-reified, non-strict: assert b(0) = true.
        factors.add(Clause(intArrayOf(emptyLit.make(verdict, true))))
    } else if (!strict && reified) {
        val r = resolveBoolLit(c.args[2])
        // r ↔ verdict
        factors.add(Clause(intArrayOf(r, emptyLit.make(verdict, false))))
        factors.add(
            Clause(intArrayOf(emptyLit.negate(r), emptyLit.make(verdict, true))),
        )
    } else {
        // Strict variants: combine verdict with set-inequality.
        val eqAux = allocBool("__setlex_eq_${s.name}_${t.name}_${factors.size}")
        emitSetEqChannel(s, t, emptyLit.make(eqAux, true))
        val strictAux = allocBool("__setlex_strict_${factors.size}")
        // strict ↔ verdict ∧ ¬eq
        // (¬strict ∨ verdict) ∧ (¬strict ∨ ¬eq) ∧ (strict ∨ ¬verdict ∨ eq)
        factors.add(
            Clause(
                intArrayOf(emptyLit.make(strictAux, false), emptyLit.make(verdict, true)),
            ),
        )
        factors.add(
            Clause(
                intArrayOf(emptyLit.make(strictAux, false), emptyLit.make(eqAux, false)),
            ),
        )
        factors.add(
            Clause(
                intArrayOf(emptyLit.make(strictAux, true), emptyLit.make(verdict, false), emptyLit.make(eqAux, true)),
            ),
        )
        if (reified) {
            val r = resolveBoolLit(c.args[2])
            factors.add(Clause(intArrayOf(r, emptyLit.make(strictAux, false))))
            factors.add(
                Clause(intArrayOf(emptyLit.negate(r), emptyLit.make(strictAux, true))),
            )
        } else {
            factors.add(Clause(intArrayOf(emptyLit.make(strictAux, true))))
        }
    }
}
