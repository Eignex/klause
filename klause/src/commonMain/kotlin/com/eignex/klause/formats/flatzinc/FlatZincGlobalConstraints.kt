package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.circuit.Subcircuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.factor.global.LexLess
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.global.Sort
import com.eignex.klause.factor.global.SymmetricAllDifferent
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.factor.scheduling.Disjunctive
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.EmptyIntArray

internal fun FlatZincCompiler.emitAllDifferentExceptZero(c: FznConstraint) {
    require(c.args.size == 1)
    val vars = evalIntVarArray(c.args[0])
    emitAllDifferentExcept(vars, intArrayOf(0))
}

private fun FlatZincCompiler.intVarUnionBounds(vars: IntArray): Pair<Int, Int>? {
    if (vars.isEmpty()) return null
    var lo = Int.MAX_VALUE
    var hi = Int.MIN_VALUE
    for (v in vars) {
        val d = intDomains[v]
        if (d.min < lo) lo = d.min
        if (d.max > hi) hi = d.max
    }
    return lo to hi
}

/** Emit `alldifferent_except(xs, except)`. */
private fun FlatZincCompiler.emitAllDifferentExcept(vars: IntArray, except: IntArray) {
    emitAllDifferentCore(vars, exceptSet = except, boundsConsistent = false)
}

private fun FlatZincCompiler.emitAllDifferentCore(vars: IntArray, exceptSet: IntArray, boundsConsistent: Boolean) {
    if (vars.size < 2) return
    val (lo, hi) = checkNotNull(intVarUnionBounds(vars))
    factors.add(
        AllDifferent(
            vars = vars,
            domainMin = lo,
            domainSize = hi - lo + 1,
            exceptSet = exceptSet,
            boundsConsistent = boundsConsistent,
        ),
    )
}

internal fun FlatZincCompiler.emitAllEqual(c: FznConstraint) {
    require(c.args.size == 1)
    val vars = evalIntVarArray(c.args[0])
    if (vars.size < 2) return
    for (i in 1 until vars.size) {
        factors.add(Linear(intArrayOf(1, -1), intArrayOf(vars[i], vars[0]), LinearOp.EQ, 0))
    }
}

internal fun FlatZincCompiler.emitMember(c: FznConstraint) {
    require(c.args.size == 2)
    val xs = evalIntVarArray(c.args[0])
    val y = resolveIntVar(c.args[1])
    val eqLits = IntArray(xs.size) {
        val aux = allocBool("__member_eq_$numBoolVars")
        factors.add(ReifiedLinear(aux, intArrayOf(1, -1), intArrayOf(xs[it], y), LinearOp.EQ, 0))
        Lit.make(aux, true)
    }
    factors.add(Cardinality(eqLits, min = 1, max = xs.size))
}

internal fun FlatZincCompiler.emitSort(c: FznConstraint) {
    require(c.args.size == 2)
    val xs = evalIntVarArray(c.args[0])
    val ys = evalIntVarArray(c.args[1])
    factors.add(Sort(xs, ys))
}

internal fun FlatZincCompiler.emitSymmetricAllDifferent(c: FznConstraint) {
    require(c.args.size == 1)
    val xs = evalIntVarArray(c.args[0])
    factors.add(SymmetricAllDifferent(xs, indexOffset = 1))
}

/** Emit `regular(seq, Q, S, d, q0, F)`. */
internal fun FlatZincCompiler.emitRegular(c: FznConstraint) {
    require(c.args.size == 6)
    val seq = evalIntVarArray(c.args[0])
    val numStates = evalIntConst(c.args[1]).toInt()
    val numSymbols = evalIntConst(c.args[2]).toInt()
    val transitions = evalIntConstArray(c.args[3])
    val q0 = evalIntConst(c.args[4]).toInt()
    val accepting: IntArray = when (val fSet = c.args[5]) {
        is FznExpr.IntSetLit -> IntArray(fSet.values.size) { fSet.values[it].toInt() }
        is FznExpr.IntRangeLit -> IntArray((fSet.hi - fSet.lo + 1).toInt()) { (fSet.lo + it).toInt() }
        else -> failHere("regular: expected set literal for F, got ${fSet::class.simpleName}")
    }
    factors.add(Regular(seq, numStates, numSymbols, transitions, q0, accepting))
}

/** Emit `mdd(x, ...)` from FlatZinc's node/edge representation. */
internal fun FlatZincCompiler.emitMdd(c: FznConstraint) {
    require(c.args.size == 7)
    val seq = evalIntVarArray(c.args[0])
    val n = seq.size
    val level = evalIntConstArray(c.args[2]) // level[node-1], 1-based, nodes 1..N
    val from = evalIntConstArray(c.args[4]) // edge source node (1..N)
    val to = evalIntConstArray(c.args[6]) // edge target node (0=terminal, else 1..N)

    fun setOfExpr(e: FznExpr): IntArray = when (e) {
        is FznExpr.IntSetLit -> IntArray(e.values.size) { e.values[it].toInt() }
        is FznExpr.IntRangeLit -> IntArray((e.hi - e.lo + 1).toInt()) { (e.lo + it).toInt() }
        else -> failHere("mdd: expected set literal label, got ${e::class.simpleName}")
    }
    val labels: List<IntArray> = when (val la = c.args[5]) {
        is FznExpr.ArrayLit -> la.elements.map(::setOfExpr)

        is FznExpr.Ident -> (arrays[la.name] as? FlatZincArray.IntSetParam)?.values
            ?: failHere("mdd: `${la.name}` is not a set-of-int parameter array")

        else -> failHere("mdd: unsupported label arg ${la::class.simpleName}")
    }
    val numLayers = n + 1
    val localIdx = IntArray(level.size) { -1 }
    val countPerLayer = IntArray(numLayers)
    countPerLayer[n] = 1 // terminal
    for (node in 1..level.size) {
        val lyr = level[node - 1] - 1 // 0-based layer
        if (lyr in 0 until n) {
            localIdx[node - 1] = countPerLayer[lyr]
            countPerLayer[lyr]++
        } else if (lyr == n) {
            localIdx[node - 1] = 0 // a node explicitly at terminal level
        }
    }
    val perLayer = Array(numLayers) { ArrayList<Int>() }
    for (e in from.indices) {
        val lyr = level[from[e] - 1] - 1
        if (lyr !in 0 until n) continue
        val src = localIdx[from[e] - 1]
        val dst = if (to[e] == 0) 0 else localIdx[to[e] - 1]
        for (v in labels[e]) {
            perLayer[lyr].add(src)
            perLayer[lyr].add(v)
            perLayer[lyr].add(dst)
        }
    }
    val transitions = ArrayList<Int>()
    val layerStarts = IntArray(numLayers) // = n + 1
    for (lyr in 0 until n) {
        layerStarts[lyr] = transitions.size
        transitions.addAll(perLayer[lyr])
    }
    layerStarts[n] = transitions.size
    factors.add(
        Mdd(
            seq = seq,
            numStatesPerLayer = countPerLayer,
            layerStarts = layerStarts,
            transitions = transitions.toIntArray(),
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = 3,
        ),
    )
}

internal fun FlatZincCompiler.emitTable(c: FznConstraint) {
    require(c.args.size == 2)
    val xs = evalIntVarArray(c.args[0])
    val tuples = evalIntConstArray(c.args[1])
    factors.add(Table(xs, tuples))
}

internal fun FlatZincCompiler.emitDiffn(c: FznConstraint, nonStrict: Boolean) {
    require(c.args.size == 4)
    val xs = evalIntVarArray(c.args[0])
    val ys = evalIntVarArray(c.args[1])
    val wConst = tryEvalIntConstArray(c.args[2])
    val hConst = tryEvalIntConstArray(c.args[3])
    val wVars = if (wConst == null) evalIntVarArray(c.args[2]) else null
    val hVars = if (hConst == null) evalIntVarArray(c.args[3]) else null
    factors.add(
        Diffn(
            xs = xs,
            ys = ys,
            widths = wConst ?: IntArray(0),
            heights = hConst ?: IntArray(0),
            widthVars = wVars,
            heightVars = hVars,
            nonStrict = nonStrict,
        ),
    )
}

internal fun FlatZincCompiler.emitValuePrecede(c: FznConstraint) {
    require(c.args.size == 3)
    val s = evalIntConst(c.args[0]).toInt()
    val t = evalIntConst(c.args[1]).toInt()
    val xs = evalIntVarArray(c.args[2])
    if (xs.isNotEmpty()) factors.add(ValuePrecede(s, t, xs))
}

internal fun FlatZincCompiler.emitValuePrecedeChain(c: FznConstraint) {
    require(c.args.size == 2)
    val values = evalIntConstArray(c.args[0])
    val xs = evalIntVarArray(c.args[1])
    if (xs.isEmpty()) return
    for (i in 0 until values.size - 1) factors.add(ValuePrecede(values[i], values[i + 1], xs))
}

internal fun FlatZincCompiler.emitLexLess(c: FznConstraint, strict: Boolean) {
    require(c.args.size == 2)
    val xs = evalIntVarArray(c.args[0])
    val ys = evalIntVarArray(c.args[1])
    factors.add(LexLess(xs, ys, strict))
}

internal fun FlatZincCompiler.emitNValue(c: FznConstraint, mode: NValue.Mode) {
    require(c.args.size == 2)
    val n = resolveIntVar(c.args[0])
    val xs = evalIntVarArray(c.args[1])
    factors.add(NValue(n, xs, mode))
}

internal fun FlatZincCompiler.emitInverse(c: FznConstraint, withOffsets: Boolean) {
    if (withOffsets) {
        require(c.args.size == 4)
        val f = evalIntVarArray(c.args[0])
        val fOff = evalIntConst(c.args[1]).toInt()
        val g = evalIntVarArray(c.args[2])
        val gOff = evalIntConst(c.args[3]).toInt()
        factors.add(Inverse(f, g, fOff, gOff))
    } else {
        require(c.args.size == 2)
        val f = evalIntVarArray(c.args[0])
        val g = evalIntVarArray(c.args[1])
        factors.add(Inverse(f, g, fOffset = 1, gOffset = 1))
    }
}

internal fun FlatZincCompiler.emitAllDifferent(c: FznConstraint) {
    require(c.args.size == 1)
    val vars = evalIntVarArray(c.args[0])
    val bc = c.annotations.any { it.name == "bounds" }
    emitAllDifferentCore(vars, exceptSet = EmptyIntArray, boundsConsistent = bc)
}

/** Emit `circuit` / `subcircuit`, channeling to 0-based values when needed. */
internal fun FlatZincCompiler.emitCircuit(c: FznConstraint, sub: Boolean) {
    require(c.args.size == 1)
    val srcIds = evalIntVarArray(c.args[0])
    val n = srcIds.size
    var offset = Int.MAX_VALUE
    for (v in srcIds) offset = minOf(offset, intDomains[v].min)
    if (offset == Int.MAX_VALUE) offset = 0
    val ids = if (offset == 0) {
        srcIds
    } else {
        IntArray(n) { i ->
            val auxName = "__circuit_aux_${i}_${factors.size}"
            val auxId = allocInt(auxName, 0, n - 1)
            factors.add(
                Linear(
                    coeffs = intArrayOf(1, -1),
                    vars = intArrayOf(srcIds[i], auxId),
                    op = LinearOp.EQ,
                    bound = offset,
                ),
            )
            auxId
        }
    }
    factors.add(if (sub) Subcircuit(succ = ids) else Circuit(succ = ids))
}

internal fun FlatZincCompiler.emitCumulative(c: FznConstraint) {
    require(c.args.size == 4) { "cumulative expects 4 args, got ${c.args.size}" }
    val starts = evalIntVarArray(c.args[0])
    val (durations, durationVars) = resolveIntArrayConstOrVars(c.args[1])
    val (resources, resourceVars) = resolveIntArrayConstOrVars(c.args[2])
    val (capacity, capacityVar) = resolveIntConstOrVar(c.args[3])
    factors.add(
        Cumulative(
            starts = starts,
            durations = durations,
            resources = resources,
            capacity = capacity,
            durationVars = durationVars,
            resourceVars = resourceVars,
            capacityVar = capacityVar,
        ),
    )
}

internal fun FlatZincCompiler.emitSlidingSum(c: FznConstraint) {
    require(c.args.size == 4) { "sliding_sum expects 4 args (low,up,seq,vs), got ${c.args.size}" }
    val low = evalIntConst(c.args[0]).toInt()
    val up = evalIntConst(c.args[1]).toInt()
    val seq = evalIntConst(c.args[2]).toInt()
    val vs = evalIntVarArray(c.args[3])
    for (w in 0..vs.size - seq) {
        val window = IntArray(seq) { vs[w + it] }
        factors.add(Linear(IntArray(seq) { 1 }, window.copyOf(), LinearOp.GE, low))
        factors.add(Linear(IntArray(seq) { 1 }, window, LinearOp.LE, up))
    }
}

internal fun FlatZincCompiler.emitDisjunctive(c: FznConstraint) {
    require(c.args.size == 2) { "disjunctive expects 2 args, got ${c.args.size}" }
    val starts = evalIntVarArray(c.args[0])
    val (durations, durationVars) = resolveIntArrayConstOrVars(c.args[1])
    factors.add(Disjunctive(starts = starts, durations = durations, durationVars = durationVars))
}

/** Returns `(constOrUbValues, vars)` for int arrays. */
private fun FlatZincCompiler.resolveIntArrayConstOrVars(e: FznExpr): Pair<IntArray, IntArray> {
    val asConst = tryEvalIntConstArray(e)
    if (asConst != null) return asConst to EmptyIntArray
    val vars = evalIntVarArray(e)
    val ubs = IntArray(vars.size) { intDomains[vars[it]].max }
    return ubs to vars
}

/** Returns `(constOrUb, varId)` for int scalar arguments. */
private fun FlatZincCompiler.resolveIntConstOrVar(e: FznExpr): Pair<Int, Int> {
    val asConst = evalIntConstOrNull(e)
    if (asConst != null) return asConst.toInt() to -1
    val varId = resolveIntVar(e)
    return intDomains[varId].max to varId
}

/** Shared body for `{exactly,at_least,at_most}_int`. */
private fun FlatZincCompiler.emitCountComparison(
    c: FznConstraint,
    tag: String,
    bounds: (n: Int, count: Int) -> Pair<Int, Int>,
) {
    require(c.args.size == 3)
    val n = evalIntConst(c.args[0]).toInt()
    val xs = evalIntVarArray(c.args[1])
    val v = evalIntConst(c.args[2]).toInt()
    val lits = IntArray(xs.size) { i ->
        val aux = allocBool("__${tag}_${xs[i]}_eq_$v")
        factors.add(ReifiedLinear(aux, intArrayOf(1), intArrayOf(xs[i]), LinearOp.EQ, v))
        Lit.make(aux, true)
    }
    val (min, max) = bounds(n, lits.size)
    factors.add(Cardinality(lits, min = min, max = max))
}

internal fun FlatZincCompiler.emitExactly(c: FznConstraint) = emitCountComparison(c, "exactly") { n, _ -> n to n }

internal fun FlatZincCompiler.emitAtLeast(c: FznConstraint) =
    emitCountComparison(c, "atleast") { n, count -> n to count }

internal fun FlatZincCompiler.emitAtMost(c: FznConstraint) = emitCountComparison(c, "atmost") { n, _ -> 0 to n }

internal fun FlatZincCompiler.emitGcc(c: FznConstraint, variant: GccVariant) {
    require(c.args.size == if (variant.lowUp) 4 else 3)
    val xs = evalIntVarArray(c.args[0])
    val cover = evalIntConstArray(c.args[1])
    if (variant.lowUp) {
        val lo = evalIntConstArray(c.args[2])
        val up = evalIntConstArray(c.args[3])
        factors.add(
            GlobalCardinality(
                xs = xs,
                cover = cover,
                countLow = lo,
                countHigh = up,
                closed = variant.closed,
            ),
        )
    } else {
        val countsAsConst = tryEvalIntConstArray(c.args[2])
        if (countsAsConst != null) {
            factors.add(
                GlobalCardinality(
                    xs = xs,
                    cover = cover,
                    countLow = countsAsConst,
                    countHigh = countsAsConst,
                    closed = variant.closed,
                ),
            )
            return
        }
        val countVars = evalIntVarArray(c.args[2])
        factors.add(
            GlobalCardinality(
                xs = xs,
                cover = cover,
                countVars = countVars,
                closed = variant.closed,
            ),
        )
    }
}

internal fun FlatZincCompiler.emitDistribute(c: FznConstraint) {
    require(c.args.size == 3)
    val card = evalIntVarArray(c.args[0])
    val value = evalIntConstArray(c.args[1])
    val base = evalIntVarArray(c.args[2])
    factors.add(GlobalCardinality(xs = base, cover = value, countVars = card))
}

/** Emit `klause_count_eq(x, y, c)` for constant `y` through GCC. */
internal fun FlatZincCompiler.emitCountEq(c: FznConstraint) {
    require(c.args.size == 3)
    val xs = evalIntVarArray(c.args[0])
    val value = evalIntConst(c.args[1]).toInt()
    val (countConst, countVar) = resolveIntConstOrVar(c.args[2])
    if (xs.isEmpty()) {
        if (countVar >= 0) factors.add(Linear(intArrayOf(1), intArrayOf(countVar), LinearOp.EQ, 0))
        return
    }
    if (countVar >= 0) {
        factors.add(GlobalCardinality(xs = xs, cover = intArrayOf(value), countVars = intArrayOf(countVar)))
    } else {
        factors.add(
            GlobalCardinality(
                xs = xs,
                cover = intArrayOf(value),
                countLow = intArrayOf(countConst),
                countHigh = intArrayOf(countConst),
            ),
        )
    }
}

/** Emit `among(n, x, v)` through GCC counts plus a sum constraint. */
internal fun FlatZincCompiler.emitAmong(c: FznConstraint) {
    require(c.args.size == 3)
    val n = resolveIntVar(c.args[0])
    val xs = evalIntVarArray(c.args[1])
    val setValues = resolveSetLiteral(c.args[2])
    val cover = if (xs.isEmpty()) {
        IntArray(0)
    } else {
        val (lo, hi) = checkNotNull(intVarUnionBounds(xs))
        setValues.filter { it in lo..hi }.toIntArray()
    }
    if (cover.isEmpty()) {
        factors.add(Linear(intArrayOf(1), intArrayOf(n), LinearOp.EQ, 0))
        return
    }
    val counts = IntArray(cover.size) { allocInt("__among_cnt_${cover[it]}_${factors.size}", 0, xs.size) }
    factors.add(GlobalCardinality(xs = xs, cover = cover, countVars = counts))
    val coeffs = IntArray(cover.size + 1) { if (it < cover.size) 1 else -1 }
    factors.add(Linear(coeffs = coeffs, vars = counts + n, op = LinearOp.EQ, bound = 0))
}

internal fun FlatZincCompiler.emitAnnotationConstraint(c: FznConstraint) {
    if (forLocalSearch) return
    require(c.args.size == 1) { "${c.name} expects 1 arg" }
    val lit = resolveBoolLit(c.args[0])
    factors.add(Clause(intArrayOf(lit)))
}
