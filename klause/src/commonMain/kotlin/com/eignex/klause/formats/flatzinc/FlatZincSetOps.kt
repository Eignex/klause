package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.formats.channelBoolTo01
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.binarySearchInt

/** Emit `array_set_element` and `array_var_set_element`. */
internal fun FlatZincCompiler.emitArraySetElement(c: FznConstraint, varArray: Boolean) {
    require(c.args.size == 3)
    val x = resolveIntVar(c.args[0])
    val z = resolveSetVar(c.args[2])
    val xDom = intDomains[x]
    if (varArray) {
        val ys = resolveSetVarArray(c.args[1])
        for (vi in xDom.min..xDom.max) {
            val yIdx = vi - xDom.min
            if (yIdx !in ys.indices) {
                factors.add(Clause(intArrayOf()))
                continue
            }
            val ySet = ys[yIdx]
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
            for (zi in z.elements.indices) {
                val k = z.elements[zi]
                val zBit = z.indicatorBoolIds[zi]
                val yIdxInSet = ySet.elements.binarySearchInt(k)
                if (yIdxInSet < 0) {
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
    val arrName = (c.args[1] as? FznExpr.Ident)?.name
        ?: failHere("array_set_element: second arg must be an array identifier")
    val arr = arrays[arrName] ?: failHere("array_set_element: unknown array `$arrName`")
    val rows: List<IntArray> = when (arr) {
        is FlatZincArray.IntSetParam -> arr.values
        else -> failHere("array_set_element: expected array of set-of-int param, got ${arr::class.simpleName}")
    }
    for (zi in z.elements.indices) {
        val k = z.elements[zi]
        val zBit = z.indicatorBoolIds[zi]
        val pick = IntArrayList()
        for ((rowIdx, row) in rows.withIndex()) {
            if (row.binarySearchInt(k) >= 0) pick.add(rowIdx + 1)
        }
        when {
            pick.isEmpty() -> {
                factors.add(
                    Clause(
                        intArrayOf(
                            Lit.make(zBit, false),
                        ),
                    ),
                )
            }

            pick.size == rows.size -> {
                factors.add(
                    Clause(
                        intArrayOf(
                            Lit.make(zBit, true),
                        ),
                    ),
                )
            }

            else -> {
                val orLits = IntArray(pick.size)
                for (idx in 0 until pick.size) {
                    val v = pick[idx]
                    val aux = allocBool("__aseelem_${arrName}_${k}_x${v}_${factors.size}")
                    factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(x), LinearOp.EQ, v))
                    orLits[idx] = Lit.make(aux, true)
                }
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

/** Emit `set_partition_into(arr, U)`. */
internal fun FlatZincCompiler.emitSetPartitionInto(c: FznConstraint) {
    require(c.args.size == 2)
    val sets = resolveSetVarArray(c.args[0])
    emitAllDisjoint(FznConstraint("all_disjoint", listOf(c.args[0]), emptyList()))
    val uExpr = c.args[1]
    val universe: IntArray = if (uExpr is FznExpr.Ident && setVarsByName.containsKey(uExpr.name)) {
        val u = setVarsByName.getValue(uExpr.name)
        for (i in u.elements.indices) {
            val e = u.elements[i]
            val uBit = u.indicatorBoolIds[i]
            val parts = IntArrayList()
            for (s in sets) {
                val si = s.elements.binarySearchInt(e)
                if (si >= 0) parts.add(Lit.make(s.indicatorBoolIds[si], true))
            }
            if (parts.isEmpty()) {
                factors.add(Clause(intArrayOf(Lit.make(uBit, false))))
            } else {
                factors.add(Clause(intArrayOf(Lit.make(uBit, false)) + parts.toIntArray()))
                parts.forEach { p -> factors.add(Clause(intArrayOf(Lit.negate(p), Lit.make(uBit, true)))) }
            }
        }
        u.elements
    } else {
        val uniq = resolveSetLiteral(uExpr)
        for (e in uniq) {
            val parts = IntArrayList()
            for (s in sets) {
                val si = s.elements.binarySearchInt(e)
                if (si >= 0) parts.add(Lit.make(s.indicatorBoolIds[si], true))
            }
            if (parts.isEmpty()) {
                failHere("set_partition_into: element $e in U has no set containing it")
            }
            factors.add(Clause(parts.toIntArray()))
        }
        uniq
    }
    for (s in sets) {
        for (i in s.elements.indices) {
            if (universe.binarySearchInt(s.elements[i]) < 0) {
                factors.add(Clause(intArrayOf(Lit.make(s.indicatorBoolIds[i], false))))
            }
        }
    }
}

/** Shared `eqLit ↔ (S = T)` channel used by set equality/inequality emitters. */
internal fun FlatZincCompiler.emitSetEqChannel(s: SetVarLayout, t: SetVarLayout, r: Int) {
    val auxes = IntArrayList()
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

internal fun FlatZincCompiler.emitSetCard(c: FznConstraint) {
    require(c.args.size == 2)
    val s = resolveSetVar(c.args[0])
    when (val nExpr = c.args[1]) {
        is FznExpr.IntLit -> {
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
            val nVar = resolveIntVar(nExpr)
            val channels = IntArray(s.indicatorBoolIds.size) { i ->
                val ch = allocInt("__card_chan_${s.name}_${s.elements[i]}", 0, 1)
                channelBoolTo01(factors, s.indicatorBoolIds[i], ch)
                ch
            }
            val coefs = IntArray(channels.size + 1) { if (it < channels.size) 1 else -1 }
            val vars = IntArray(channels.size + 1) { if (it < channels.size) channels[it] else nVar }
            factors.add(Linear(coefs, vars, LinearOp.EQ, 0))
        }

        else -> failHere("set_card: second arg must be int var or constant, got ${nExpr::class.simpleName}")
    }
}

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
                factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(uBit, true))))
                factors.add(Clause(intArrayOf(Lit.make(tBit, false), Lit.make(uBit, true))))
                factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(sBit, true), Lit.make(tBit, true))))
            }

            sIdx >= 0 -> {
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
                factors.add(Clause(intArrayOf(Lit.make(uBit, false))))
            }
        }
    }
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
            emitIntersectUImpliesInputs(uBit, sBit, tBit)
            factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(tBit, false), Lit.make(uBit, true))))
        } else {
            factors.add(Clause(intArrayOf(Lit.make(uBit, false))))
        }
    }
}

private fun FlatZincCompiler.emitIntersectUImpliesInputs(uBit: Int, sBit: Int, tBit: Int) {
    factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(sBit, true))))
    factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(tBit, true))))
}

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
            factors.add(Clause(intArrayOf(Lit.make(uBit, false))))
            continue
        }
        val sBit = s.indicatorBoolIds[sIdx]
        val tIdx = t.elements.binarySearchInt(e)
        if (tIdx < 0) {
            factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(uBit, true))))
            factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(uBit, false))))
        } else {
            val tBit = t.indicatorBoolIds[tIdx]
            factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(sBit, true))))
            factors.add(Clause(intArrayOf(Lit.make(uBit, false), Lit.make(tBit, false))))
            factors.add(Clause(intArrayOf(Lit.make(sBit, false), Lit.make(tBit, true), Lit.make(uBit, true))))
        }
    }
}
