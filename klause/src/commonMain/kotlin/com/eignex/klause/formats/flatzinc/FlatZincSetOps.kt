package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.channelBoolTo01
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.binarySearchInt

internal fun FlatZincCompiler.emitArraySetElement(c: FznConstraint, varArray: Boolean) =
    emitSetSelection(c, varArray)

internal fun FlatZincCompiler.emitAllDisjoint(c: FznConstraint) {
    expectArity(c, 1)
    val sets = resolveSetVarArray(c.args[0])
    for (i in sets.indices) {
        for (j in i + 1 until sets.size) {
            val a = sets[i]
            val b = sets[j]
            for (ai in a.elements.indices) {
                val bi = b.layout.indexOf(a.elements[ai])
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
    expectArity(c, 2)
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
                val si = s.layout.indexOf(e)
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
                val si = s.layout.indexOf(e)
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
        val tIdx = t.layout.indexOf(s.elements[i])
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
        if (s.layout.indexOf(t.elements[i]) < 0) {
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
    expectArity(c, 2)
    val s = resolveSetVar(c.args[0])
    when (val nExpr = c.args[1]) {
        is FznExpr.IntLit -> {
            val coeffs = LongArray(s.indicatorBoolIds.size) { 1L }
            val lits = IntArray(s.indicatorBoolIds.size) { Lit.make(s.indicatorBoolIds[it], true) }
            factors.add(
                PseudoBoolean(
                    coeffs,
                    lits,
                    PbOp.EQ,
                    nExpr.value,
                ),
            )
        }

        is FznExpr.Ident -> {
            val nVar = resolveIntVar(nExpr)
            val channels = IntArray(s.indicatorBoolIds.size) { i ->
                val ch = allocInt("__card_chan_${s.name}_${s.elements[i]}", 0L, 1L)
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
    expectArity(c, 3)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    val u = resolveSetVar(c.args[2])
    for (i in u.elements.indices) {
        val e = u.elements[i]
        val uBit = u.indicatorBoolIds[i]
        val sIdx = s.layout.indexOf(e)
        val tIdx = t.layout.indexOf(e)
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
        if (u.layout.indexOf(s.elements[i]) < 0) {
            factors.add(Clause(intArrayOf(Lit.make(s.indicatorBoolIds[i], false))))
        }
    }
    for (i in t.elements.indices) {
        if (u.layout.indexOf(t.elements[i]) < 0) {
            factors.add(Clause(intArrayOf(Lit.make(t.indicatorBoolIds[i], false))))
        }
    }
}

internal fun FlatZincCompiler.emitSetIntersect(c: FznConstraint) {
    expectArity(c, 3)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    val u = resolveSetVar(c.args[2])
    for (i in u.elements.indices) {
        val e = u.elements[i]
        val uBit = u.indicatorBoolIds[i]
        val sIdx = s.layout.indexOf(e)
        val tIdx = t.layout.indexOf(e)
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
    expectArity(c, 3)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    val u = resolveSetVar(c.args[2])
    for (i in u.elements.indices) {
        val e = u.elements[i]
        val uBit = u.indicatorBoolIds[i]
        val sIdx = s.layout.indexOf(e)
        if (sIdx < 0) {
            factors.add(Clause(intArrayOf(Lit.make(uBit, false))))
            continue
        }
        val sBit = s.indicatorBoolIds[sIdx]
        val tIdx = t.layout.indexOf(e)
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
