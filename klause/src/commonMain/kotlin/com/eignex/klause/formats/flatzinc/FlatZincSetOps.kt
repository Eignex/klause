package com.eignex.klause.formats.flatzinc

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.util.binarySearchInt

/**
 * `array_set_element(x, ys, z)` — `z = ys(x)` where `ys` is an array of *constant* sets.
 * For each universe element `k` of `z`, the indicator `z.ind(k)` holds iff
 * `x ∈ { i : k ∈ ys(i) }`. Encoded as one reified `set_in` per element with a constant
 * "elements-of-x-that-pick-k" mask.
 *
 * `array_var_set_element(x, ys, z)` — `z = ys(x)` where `ys` is an array of *var* sets.
 * For each `i` and each element `k`, post `x=i → (z.ind(k) ↔ ys(i).ind(k))` as a
 * reified clause guarded by `(x = i)`. O(|x.domain| × |universe|) clauses.
 */
internal fun FlatZincCompiler.emitArraySetElement(c: FznConstraint, varArray: Boolean) {
    require(c.args.size == 3)
    val x = resolveIntVar(c.args[0])
    val z = resolveSetVar(c.args[2])
    val xDom = intDomains[x]
    if (varArray) {
        val ys = resolveSetVarArray(c.args[1])
        // ys is 1-indexed in FZN by convention; xDom.min should be 1 unless declared otherwise.
        // Channel each indicator under each candidate value.
        for (vi in xDom.min..xDom.max) {
            val yIdx = vi - xDom.min
            if (yIdx !in ys.indices) {
                // x can't realistically take value vi (no corresponding ys entry); forbid via
                // an empty clause (unsat sentinel).
                factors.add(Clause(intArrayOf()))
                continue
            }
            val ySet = ys[yIdx]
            // x_eq_vi reified bool.
            val xEqAux = allocBool("__arraysetelem_xeq_${vi}_${factors.size}")
            factors.add(
                ReifiedLinear(
                    xEqAux,
                    intArrayOf(1),
                    intArrayOf(x),
                    LinearOp.EQ,
                    vi,
                ),
            )
            // For each universe element of z, channel through ySet.
            for (zi in z.elements.indices) {
                val k = z.elements[zi]
                val zBit = z.indicatorBoolIds[zi]
                val yIdxInSet = ySet.elements.binarySearchInt(k)
                if (yIdxInSet < 0) {
                    // ySet's universe doesn't contain k → if x=vi then z.ind(k)=false.
                    factors.add(
                        Clause(
                            intArrayOf(
                                Lit.make(xEqAux, false),
                                Lit.make(zBit, false),
                            ),
                        ),
                    )
                } else {
                    val yBit = ySet.indicatorBoolIds[yIdxInSet]
                    // (x=vi) → (z.ind(k) ↔ yBit)
                    // (¬xEq ∨ ¬z.ind ∨ yBit) ∧ (¬xEq ∨ z.ind ∨ ¬yBit)
                    factors.add(
                        Clause(
                            intArrayOf(
                                Lit.make(xEqAux, false),
                                Lit.make(zBit, false),
                                Lit.make(yBit, true),
                            ),
                        ),
                    )
                    factors.add(
                        Clause(
                            intArrayOf(
                                Lit.make(xEqAux, false),
                                Lit.make(zBit, true),
                                Lit.make(yBit, false),
                            ),
                        ),
                    )
                }
            }
        }
        return
    }
    // Constant-set array: extract each row as IntArray (sorted ascending), then per
    // universe element of z, build the "x values that pick k" mask and post a reified
    // set_in over x.
    val arrName = (c.args[1] as? FznExpr.Ident)?.name
        ?: failHere("array_set_element: second arg must be an array identifier")
    val arr = arrays[arrName] ?: failHere("array_set_element: unknown array `$arrName`")
    val rows: List<IntArray> = when (arr) {
        is FlatZincArray.IntSetParam -> arr.values
        else -> failHere("array_set_element: expected array of set-of-int param, got ${arr::class.simpleName}")
    }
    // x is 1-indexed by FZN convention; xDom describes its valid range. For each
    // universe element k of z, the picking constraint is z.ind(k) ↔ x ∈ mask_k.
    for (zi in z.elements.indices) {
        val k = z.elements[zi]
        val zBit = z.indicatorBoolIds[zi]
        // Collect the set of x-values (1-indexed) for which k ∈ rows(x-1).
        val pick = ArrayList<Int>()
        for ((rowIdx, row) in rows.withIndex()) {
            if (row.binarySearchInt(k) >= 0) pick.add(rowIdx + 1)
        }
        when {
            pick.isEmpty() -> {
                // No x value leads to z containing k → force z.ind(k) = false.
                factors.add(
                    Clause(
                        intArrayOf(
                            Lit.make(zBit, false),
                        ),
                    ),
                )
            }

            pick.size == rows.size -> {
                // Every x value gives z containing k → force z.ind(k) = true.
                factors.add(
                    Clause(
                        intArrayOf(
                            Lit.make(zBit, true),
                        ),
                    ),
                )
            }

            else -> {
                // Reified disjunction: zBit ↔ ⋁ (x = pick(j)) for j.
                // For each pick value, alloc a reified bool xEq_v ↔ (x = v).
                val orLits = IntArray(pick.size)
                for ((idx, v) in pick.withIndex()) {
                    val aux = allocBool("__aseelem_${arrName}_${k}_x${v}_${factors.size}")
                    factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(x), LinearOp.EQ, v))
                    orLits[idx] = Lit.make(aux, true)
                }
                // zBit ↔ ⋁ orLits
                // (¬zBit ∨ ⋁ orLits) ∧ for each orLit: (zBit ∨ ¬orLit)
                factors.add(
                    Clause(
                        intArrayOf(Lit.make(zBit, false)) + orLits,
                    ),
                )
                for (orLit in orLits) {
                    factors.add(
                        Clause(
                            intArrayOf(
                                Lit.make(zBit, true),
                                Lit.negate(orLit),
                            ),
                        ),
                    )
                }
            }
        }
    }
}

/** `all_disjoint(arr)` — every pair of sets in `arr` has empty intersection. For each
 *  pair (Sᵢ, Sⱼ) and each element `e` shared between their universes, post the binary
 *  mutex clause `¬Sᵢ(e) ∨ ¬Sⱼ(e)`. */
internal fun FlatZincCompiler.emitAllDisjoint(c: FznConstraint) {
    require(c.args.size == 1)
    val sets = resolveSetVarArray(c.args[0])
    for (i in sets.indices) {
        for (j in i + 1 until sets.size) {
            val a = sets[i]
            val b = sets[j]
            for (ai in a.elements.indices) {
                val bi = b.elements.binarySearchInt(a.elements[ai])
                if (bi >= 0) {
                    factors.add(
                        Clause(
                            intArrayOf(
                                Lit.make(a.indicatorBoolIds[ai], false),
                                Lit.make(b.indicatorBoolIds[bi], false),
                            ),
                        ),
                    )
                }
            }
        }
    }
}

/** `set_partition_into(arr, U)` — sets in `arr` are pairwise disjoint AND their union
 *  equals U. Reuses `emitAllDisjoint`'s pairwise mutex; adds for each `e` in U's universe
 *  the clause `Uₑ ↔ ⋁ᵢ Sᵢ(e)` plus the universe-mismatch exclusions (elements outside
 *  U but in some Sᵢ's universe must be absent from Sᵢ).
 *
 *  When `U` is a set literal (not a var), treats it as a fully-determined universe: every
 *  element of `U` must be covered by exactly one set; elements outside `U` can't appear in
 *  any set. */
internal fun FlatZincCompiler.emitSetPartitionInto(c: FznConstraint) {
    require(c.args.size == 2)
    val sets = resolveSetVarArray(c.args[0])
    emitAllDisjoint(FznConstraint("all_disjoint", listOf(c.args[0]), emptyList()))
    val uExpr = c.args[1]
    val universe: IntArray = if (uExpr is FznExpr.Ident && setVarsByName.containsKey(uExpr.name)) {
        // U is a set var: cover & disjointness over U's universe; per-element `Uₑ ↔ ⋁ Sᵢ(e)`.
        val u = setVarsByName.getValue(uExpr.name)
        for (i in u.elements.indices) {
            val e = u.elements[i]
            val uBit = u.indicatorBoolIds[i]
            val parts = ArrayList<Int>()
            for (s in sets) {
                val si = s.elements.binarySearchInt(e)
                if (si >= 0) parts += Lit.make(s.indicatorBoolIds[si], true)
            }
            if (parts.isEmpty()) {
                // No set can contain e; force Uₑ = false.
                factors.add(Clause(intArrayOf(Lit.make(uBit, false))))
            } else {
                // (¬Uₑ ∨ S₁(e) ∨ ... ∨ Sₙ(e))
                factors.add(Clause(intArrayOf(Lit.make(uBit, false)) + parts.toIntArray()))
                // (Sᵢ(e) → Uₑ) for each part.
                for (p in parts) factors.add(Clause(intArrayOf(Lit.negate(p), Lit.make(uBit, true))))
            }
        }
        u.elements
    } else {
        // U is a constant set literal — cover exactly its elements; forbid extras.
        val uniq = resolveSetLiteral(uExpr)
        for (e in uniq) {
            // ⋁ᵢ Sᵢ(e) = true (since e must be in the partition).
            val parts = ArrayList<Int>()
            for (s in sets) {
                val si = s.elements.binarySearchInt(e)
                if (si >= 0) parts += Lit.make(s.indicatorBoolIds[si], true)
            }
            if (parts.isEmpty()) {
                failHere("set_partition_into: element $e in U has no set containing it")
            }
            factors.add(Clause(parts.toIntArray()))
        }
        uniq
    }
    // Elements in some Sᵢ's universe but not in U must be excluded from Sᵢ.
    for (s in sets) {
        for (i in s.elements.indices) {
            if (universe.binarySearchInt(s.elements[i]) < 0) {
                factors.add(Clause(intArrayOf(Lit.make(s.indicatorBoolIds[i], false))))
            }
        }
    }
}

/** Channel: `eqLit ↔ (S = T)`. Shared by `set_eq_reif` and `set_ne_reif`. Same lowering
 *  as `emitSetEq(reified=true)` but parameterised by the channel literal instead of
 *  pulling it from the constraint args. */
internal fun FlatZincCompiler.emitSetEqChannel(s: SetVarLayout, t: SetVarLayout, r: Int) {
    val auxes = ArrayList<Int>()
    val emitEqAux: (Int, Int, Int) -> Unit = { sBit, tBit, aux ->
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
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(tBit, false))))
            factors.add(Clause(intArrayOf(Lit.make(aux, true), Lit.make(tBit, true))))
        }
    }
    reifyAndOfLits(auxes.toIntArray(), r)
}

/** `set_card(S, n)`. Σ `indicator_S(e)` = n. n can be a constant or an int var; lowers to a
 *  pseudo-Boolean linear constraint either way. */
internal fun FlatZincCompiler.emitSetCard(c: FznConstraint) {
    require(c.args.size == 2)
    val s = resolveSetVar(c.args[0])
    val nExpr = c.args[1]
    when (nExpr) {
        is FznExpr.IntLit -> {
            // Σ Sᵢ = const → bool_lin_eq. PseudoBoolean takes literals (Lit-encoded), not
            // raw var ids; wrap each indicator as a positive literal.
            val coeffs = IntArray(s.indicatorBoolIds.size) { 1 }
            val lits = IntArray(s.indicatorBoolIds.size) { Lit.make(s.indicatorBoolIds[it], true) }
            factors.add(
                PseudoBoolean(
                    coeffs,
                    lits,
                    PbOp.EQ,
                    nExpr.value.toInt(),
                ),
            )
        }

        is FznExpr.Ident -> {
            // Σ Sᵢ = nVar → int_lin_eq([1...1, -1], [indicator channel ints..., nVar], 0).
            // We channel each bool indicator to a 0/1 int, then post the linear.
            val nVar = resolveIntVar(nExpr)
            val channels = IntArray(s.indicatorBoolIds.size) { i ->
                val ch = allocInt("__card_chan_${s.name}_${s.elements[i]}", 0, 1)
                factors.add(
                    ReifiedLinear(
                        auxBoolVar = s.indicatorBoolIds[i],
                        coeffs = intArrayOf(1),
                        vars = intArrayOf(ch),
                        op = LinearOp.EQ,
                        bound = 1,
                    ),
                )
                ch
            }
            val coefs = IntArray(channels.size + 1) { if (it < channels.size) 1 else -1 }
            val vars = IntArray(channels.size + 1) { if (it < channels.size) channels[it] else nVar }
            factors.add(Linear(coefs, vars, LinearOp.EQ, 0))
        }

        else -> failHere("set_card: second arg must be int var or constant, got ${nExpr::class.simpleName}")
    }
}

/** `set_union(S, T, U)`. For each element of U's universe: `Uᵢ ↔ (Sᵢ ∨ Tᵢ)`. Elements
 *  outside U's universe but in S or T's must not be in S/T. */
internal fun FlatZincCompiler.emitSetUnion(c: FznConstraint) {
    require(c.args.size == 3)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    val u = resolveSetVar(c.args[2])
    for (i in u.elements.indices) {
        val e = u.elements[i]
        val uBit = u.indicatorBoolIds[i]
        val sIdx = s.elements.binarySearchInt(e)
        val tIdx = t.elements.binarySearchInt(e)
        when {
            sIdx >= 0 && tIdx >= 0 -> {
                val sBit = s.indicatorBoolIds[sIdx]
                val tBit = t.indicatorBoolIds[tIdx]
                // Uᵢ ↔ (Sᵢ ∨ Tᵢ): three clauses.
                factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(uBit, true))))
                factors.add(Clause(intArrayOf(Lit.make(tBit, false), Lit.make(uBit, true))))
                factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(sBit, true), Lit.make(tBit, true))))
            }

            sIdx >= 0 -> {
                // Uᵢ ↔ Sᵢ
                val sBit = s.indicatorBoolIds[sIdx]
                factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(uBit, true))))
                factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(uBit, false))))
            }

            tIdx >= 0 -> {
                val tBit = t.indicatorBoolIds[tIdx]
                factors.add(Clause(intArrayOf(Lit.make(tBit, false), Lit.make(uBit, true))))
                factors.add(Clause(intArrayOf(Lit.make(tBit, true), Lit.make(uBit, false))))
            }

            else -> {
                // Element only in U's universe — neither S nor T can contribute, so Uᵢ = false.
                factors.add(Clause(intArrayOf(Lit.make(uBit, false))))
            }
        }
    }
    // Elements in S or T's universe but not U's must be excluded from S/T.
    for (i in s.elements.indices) {
        if (u.elements.binarySearchInt(s.elements[i]) < 0) {
            factors.add(Clause(intArrayOf(Lit.make(s.indicatorBoolIds[i], false))))
        }
    }
    for (i in t.elements.indices) {
        if (u.elements.binarySearchInt(t.elements[i]) < 0) {
            factors.add(Clause(intArrayOf(Lit.make(t.indicatorBoolIds[i], false))))
        }
    }
}

/** `set_intersect(S, T, U)`. For each element of U's universe: `Uᵢ ↔ (Sᵢ ∧ Tᵢ)`. Elements
 *  outside U but in both S and T's universes must not be in both (or unconstrained — we
 *  leave them unconstrained, since intersection only needs U to track the conjunction). */
internal fun FlatZincCompiler.emitSetIntersect(c: FznConstraint) {
    require(c.args.size == 3)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    val u = resolveSetVar(c.args[2])
    for (i in u.elements.indices) {
        val e = u.elements[i]
        val uBit = u.indicatorBoolIds[i]
        val sIdx = s.elements.binarySearchInt(e)
        val tIdx = t.elements.binarySearchInt(e)
        if (sIdx >= 0 && tIdx >= 0) {
            val sBit = s.indicatorBoolIds[sIdx]
            val tBit = t.indicatorBoolIds[tIdx]
            // Uᵢ ↔ (Sᵢ ∧ Tᵢ): three clauses.
            factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(sBit, true))))
            factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(tBit, true))))
            factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(tBit, false), Lit.make(uBit, true))))
        } else {
            // Element not in both S and T's universes → can't be in intersection.
            factors.add(Clause(intArrayOf(Lit.make(uBit, false))))
        }
    }
}

/** `set_diff(S, T, U)`. For each element of U's universe: `Uᵢ ↔ (Sᵢ ∧ ¬Tᵢ)`. */
internal fun FlatZincCompiler.emitSetDiff(c: FznConstraint) {
    require(c.args.size == 3)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    val u = resolveSetVar(c.args[2])
    for (i in u.elements.indices) {
        val e = u.elements[i]
        val uBit = u.indicatorBoolIds[i]
        val sIdx = s.elements.binarySearchInt(e)
        if (sIdx < 0) {
            // Element not in S → can't be in S \ T.
            factors.add(Clause(intArrayOf(Lit.make(uBit, false))))
            continue
        }
        val sBit = s.indicatorBoolIds[sIdx]
        val tIdx = t.elements.binarySearchInt(e)
        if (tIdx < 0) {
            // Element in S but not in T's universe → Uᵢ ↔ Sᵢ.
            factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(uBit, true))))
            factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(uBit, false))))
        } else {
            val tBit = t.indicatorBoolIds[tIdx]
            // Uᵢ ↔ (Sᵢ ∧ ¬Tᵢ): three clauses.
            factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(sBit, true))))
            factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(tBit, false))))
            factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(tBit, true), Lit.make(uBit, true))))
        }
    }
}
