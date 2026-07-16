package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.binarySearchInt

/** Resolve a set expression to a [SetVarLayout]. */
internal fun FlatZincCompiler.resolveSetVar(e: FznExpr): SetVarLayout = when (e) {
    is FznExpr.Ident -> setVarsByName[e.name] ?: run {
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

/** Lift a set literal into a pinned [SetVarLayout]. */
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

internal fun FlatZincCompiler.resolveSetLiteral(e: FznExpr): IntArray = when (e) {
    is FznExpr.IntSetLit -> e.values.map { it.toInt() }.toIntArray().also { it.sort() }

    is FznExpr.IntRangeLit -> IntArray((e.hi - e.lo + 1).toInt()) { (e.lo + it).toInt() }

    is FznExpr.Ident -> {
        val pv = params[e.name] ?: failHere("undefined set parameter `${e.name}`")
        when (pv) {
            is FlatZincCompiler.ParamValue.IntSet -> pv.values.map { it.toInt() }.toIntArray().also { it.sort() }
            else -> failHere("`${e.name}` is not a set parameter")
        }
    }

    else -> failHere("expected a set literal, got ${e::class.simpleName}")
}

/** Emit `set_in` and `set_in_reif`. */
internal fun FlatZincCompiler.emitSetIn(c: FznConstraint, reified: Boolean) {
    expectArity(c, if (reified) 3 else 2)
    val elem = c.args[0]
    val sExpr = c.args[1]
    val rExpr = if (reified) c.args[2] else null
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
    val xVar = resolveIntVar(elem)
    val dom = intDomains[xVar]
    emitSetInVarInt(xVar, dom.min.toInt(), dom.max.toInt(), layout, rExpr)
}

private fun FlatZincCompiler.emitSetInLiteral(elem: FznExpr, values: IntArray, rExpr: FznExpr?) {
    if (elem is FznExpr.IntLit) {
        val v = elem.value.toInt()
        val isMember = values.binarySearchInt(v) >= 0
        if (rExpr != null) {
            val r = resolveBoolLit(rExpr)
            factors.add(Clause(intArrayOf(if (isMember) r else Lit.negate(r))))
        } else {
            if (!isMember) failHere("set_in: element $v outside literal set ${values.contentToString()}")
        }
        return
    }
    val xVar = resolveIntVar(elem)
    val dom = intDomains[xVar]
    val membershipLits = IntArrayList()
    for (v in values) {
        // Skip hole values to avoid dead channels and unnecessary reifications.
        if (v.toLong() !in dom) continue
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
        membershipLits.add(Lit.make(chan, true))
    }
    if (rExpr == null) {
        if (membershipLits.isEmpty()) {
            postFalseFactor()
            return
        }
        factors.add(Cardinality.atLeastOne(membershipLits.toIntArray()))
        return
    }
    val r = resolveBoolLit(rExpr)
    if (membershipLits.isEmpty()) {
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
    if (!Lit.isPositive(r)) {
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
    val membershipLits = IntArrayList()
    for (v in xLo..xHi) {
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
            if (setIdx < 0) {
                factors.add(Clause(intArrayOf(Lit.make(chan, false))))
            } else {
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
            if (setIdx < 0) {
                continue
            }
            val ind = layout.indicatorBoolIds[setIdx]
            val aux = allocBool("__set_in_aux_${layout.name}_$v")
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(chan, true))))
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(ind, true))))
            factors.add(Clause(intArrayOf(Lit.make(aux, true), Lit.make(chan, false), Lit.make(ind, false))))
            membershipLits.add(Lit.make(aux, true))
        }
    }
    if (rExpr != null) {
        reifyOrOfLits(membershipLits.toIntArray(), resolveBoolLit(rExpr))
    }
}

internal fun FlatZincCompiler.emitSetSubset(c: FznConstraint, reified: Boolean) {
    expectArity(c, if (reified) 3 else 2)
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
    val auxes = IntArrayList(s.elements.size)
    for (i in s.elements.indices) {
        val sBit = s.indicatorBoolIds[i]
        val tIdx = t.elements.binarySearchInt(s.elements[i])
        val aux = allocBool("__subset_aux_${s.name}_${t.name}_${s.elements[i]}")
        auxes.add(Lit.make(aux, true))
        if (tIdx < 0) {
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(sBit, false))))
            factors.add(Clause(intArrayOf(Lit.make(aux, true), Lit.make(sBit, true))))
        } else {
            val tBit = t.indicatorBoolIds[tIdx]
            factors.add(Clause(intArrayOf(Lit.make(aux, false), Lit.make(sBit, false), Lit.make(tBit, true))))
            factors.add(Clause(intArrayOf(Lit.make(sBit, true), Lit.make(aux, true))))
            factors.add(Clause(intArrayOf(Lit.make(tBit, false), Lit.make(aux, true))))
        }
    }
    reifyAndOfLits(auxes.toIntArray(), r)
}

/** Emit `r ↔ ⋀ lits`. */
internal fun FlatZincCompiler.reifyAndOfLits(lits: IntArray, r: Int) {
    factors.add(Clause(lits.map { Lit.negate(it) }.toIntArray() + intArrayOf(r)))
    for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(r), l)))
}

/** Emit `r ↔ ⋁ lits`. */
internal fun FlatZincCompiler.reifyOrOfLits(lits: IntArray, r: Int) {
    factors.add(Clause(intArrayOf(Lit.negate(r)) + lits))
    for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(l), r)))
}

internal fun FlatZincCompiler.emitSetEq(c: FznConstraint, reified: Boolean) {
    expectArity(c, if (reified) 3 else 2)
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
    emitSetEqChannel(s, t, resolveBoolLit(c.args[2]))
}

internal fun FlatZincCompiler.emitSetNe(c: FznConstraint, reified: Boolean) {
    expectArity(c, if (reified) 3 else 2)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    val eqLit = if (reified) {
        val r = resolveBoolLit(c.args[2])
        val eqAux = allocBool("__set_ne_eq_${s.name}_${t.name}")
        val eqLit = Lit.make(eqAux, true)
        factors.add(Clause(intArrayOf(r, eqLit)))
        factors.add(Clause(intArrayOf(Lit.negate(r), Lit.negate(eqLit))))
        eqLit
    } else {
        val eqAux = allocBool("__set_ne_eq_${s.name}_${t.name}")
        val eqLit = Lit.make(eqAux, true)
        factors.add(Clause(intArrayOf(Lit.negate(eqLit))))
        eqLit
    }
    emitSetEqChannel(s, t, eqLit)
}

/** Resolve an array expression to set layouts. */
internal fun FlatZincCompiler.resolveSetVarArray(e: FznExpr): List<SetVarLayout> = when (e) {
    is FznExpr.Ident -> when (val a = arrays[e.name]) {
        is FlatZincArray.SetVars -> a.layouts
        else -> failHere("`${e.name}` is not an array of set vars")
    }

    is FznExpr.ArrayLit -> e.elements.map { resolveSetVar(it) }

    else -> failHere("expected an array of set vars, got ${e::class.simpleName}")
}

/** Emit `set_le` / `set_lt` and reified variants. */
internal fun FlatZincCompiler.emitSetLex(c: FznConstraint, strict: Boolean, reified: Boolean) {
    expectArity(c, if (reified) 3 else 2)
    val s = resolveSetVar(c.args[0])
    val t = resolveSetVar(c.args[1])
    val universe = (s.elements.toSet() + t.elements.toSet()).sorted().toIntArray()
    if (universe.isEmpty()) {
        if (reified) {
            val r = resolveBoolLit(c.args[2])
            val lit = if (strict) Lit.negate(r) else r
            factors.add(Clause(intArrayOf(lit)))
        } else if (strict) {
            postFalseFactor()
        }
        return
    }
    val lo = universe.first() - 1
    val hi = universe.last()
    fun maxOf(set: SetVarLayout, label: String): Int {
        val channels = IntArray(set.elements.size)
        for (i in set.elements.indices) {
            val elem = set.elements[i]
            val ind = set.indicatorBoolIds[i]
            val ch = allocInt("__setlex_${label}_${set.name}_$elem", lo.toLong(), hi.toLong())
            channels[i] = ch
            factors.add(
                ReifiedLinear(
                    ind,
                    coeffs = intArrayOf(1),
                    vars = intArrayOf(ch),
                    op = LinearOp.EQ,
                    bound = elem,
                ),
            )
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
        val maxVar = allocInt("__setlex_${label}max_${set.name}_${factors.size}", lo.toLong(), hi.toLong())
        if (channels.isEmpty()) {
            factors.add(Linear(intArrayOf(1), intArrayOf(maxVar), LinearOp.EQ, lo))
        } else {
            factors.add(ArrayMinMax(maxVar, channels, max = true))
        }
        return maxVar
    }
    val xmax = maxOf(s, "x")
    val ymax = maxOf(t, "y")
    val b = IntArray(universe.size) { allocBool("__setlex_b_${s.name}_${t.name}_${universe[it]}_${factors.size}") }
    val emptyLit = Lit

    fun indicator(set: SetVarLayout, elem: Int): Int? {
        val idx = set.elements.binarySearchInt(elem)
        return if (idx < 0) null else set.indicatorBoolIds[idx]
    }
    run {
        val last = universe.size - 1
        val sLit = indicator(s, universe[last])
        val tLit = indicator(t, universe[last])
        val sHas = if (sLit != null) Lit.make(sLit, true) else null
        val tHas = if (tLit != null) Lit.make(tLit, true) else null
        when {
            sHas == null && tHas == null -> {
                factors.add(Clause(intArrayOf(emptyLit.make(b[last], true))))
            }

            sHas == null -> {
                factors.add(Clause(intArrayOf(emptyLit.make(b[last], true))))
            }

            tHas == null -> {
                factors.add(Clause(intArrayOf(emptyLit.make(b[last], true), sHas)))
                factors.add(
                    Clause(
                        intArrayOf(emptyLit.make(b[last], false), emptyLit.negate(sHas)),
                    ),
                )
            }

            else -> {
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
    for (i in universe.size - 2 downTo 0) {
        val elem = universe[i]
        val sLit = indicator(s, elem)
        val tLit = indicator(t, elem)
        val xmaxLessLit: Int by lazy {
            val aux = allocBool("__setlex_xmaxlt_${elem}_${factors.size}")
            factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(xmax), LinearOp.LE, elem - 1))
            emptyLit.make(aux, true)
        }
        val ymaxGreaterLit: Int by lazy {
            val aux = allocBool("__setlex_ymaxgt_${elem}_${factors.size}")
            factors.add(ReifiedLinear(aux, intArrayOf(-1), intArrayOf(ymax), LinearOp.LE, -(elem + 1)))
            emptyLit.make(aux, true)
        }
        val bi = emptyLit.make(b[i], true)
        val nbi = emptyLit.make(b[i], false)
        val bn = emptyLit.make(b[i + 1], true)
        val nbn = emptyLit.make(b[i + 1], false)
        val sHas = if (sLit != null) emptyLit.make(sLit, true) else null
        val tHas = if (tLit != null) emptyLit.make(tLit, true) else null
        when {
            sHas == null && tHas == null -> {
                factors.add(Clause(intArrayOf(nbi, bn)))
                factors.add(Clause(intArrayOf(bi, nbn)))
            }

            sHas == null -> {
                factors.add(Clause(intArrayOf(requireNotNull(tHas), nbi, bn)))
                factors.add(Clause(intArrayOf(tHas, bi, nbn)))
                factors.add(Clause(intArrayOf(emptyLit.negate(tHas), nbi, xmaxLessLit)))
                factors.add(
                    Clause(
                        intArrayOf(emptyLit.negate(tHas), bi, emptyLit.negate(xmaxLessLit)),
                    ),
                )
            }

            tHas == null -> {
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
                factors.add(Clause(intArrayOf(sHas, tHas, nbi, bn)))
                factors.add(Clause(intArrayOf(sHas, tHas, bi, nbn)))
                factors.add(
                    Clause(intArrayOf(sHas, emptyLit.negate(tHas), nbi, xmaxLessLit)),
                )
                factors.add(
                    Clause(
                        intArrayOf(sHas, emptyLit.negate(tHas), bi, emptyLit.negate(xmaxLessLit)),
                    ),
                )
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
    val verdict = b[0]
    if (!strict && !reified) {
        factors.add(Clause(intArrayOf(emptyLit.make(verdict, true))))
    } else if (!strict && reified) {
        val r = resolveBoolLit(c.args[2])
        factors.add(Clause(intArrayOf(r, emptyLit.make(verdict, false))))
        factors.add(
            Clause(intArrayOf(emptyLit.negate(r), emptyLit.make(verdict, true))),
        )
    } else {
        val eqAux = allocBool("__setlex_eq_${s.name}_${t.name}_${factors.size}")
        emitSetEqChannel(s, t, emptyLit.make(eqAux, true))
        val strictAux = allocBool("__setlex_strict_${factors.size}")
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
