package com.eignex.klause.lowering.flatzinc

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.circuit.Circuit
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
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.flatzinc.*
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.packLayeredMdd
import com.eignex.klause.ir.values
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

internal fun FlatZincCompiler.emitAllDifferentExceptZero(c: FznConstraint) {
    expectArity(c, 1)
    val vars = evalIntVarArray(c.args[0])
    emitAllDifferentExcept(vars, longArrayOf(0))
}

private fun FlatZincCompiler.intVarUnionBounds(vars: IntArray): Pair<Long, Long>? {
    if (vars.isEmpty()) return null
    var lo = Long.MAX_VALUE
    var hi = Long.MIN_VALUE
    for (v in vars) {
        val d = intDomains[v]
        if (d.min < lo) lo = d.min
        if (d.max > hi) hi = d.max
    }
    return lo to hi
}

private fun FlatZincCompiler.emitAllDifferentExcept(vars: IntArray, except: LongArray) {
    emitAllDifferentCore(vars, exceptSet = except, boundsConsistent = false)
}

private fun FlatZincCompiler.emitAllDifferentCore(vars: IntArray, exceptSet: LongArray, boundsConsistent: Boolean) {
    if (vars.size < 2) return
    val (lo, hi) = checkNotNull(intVarUnionBounds(vars))
    // AllDifferent's value-indexed matching/occurrence scratch is sized by the Int-typed value span, so
    // a span beyond Int range (reachable for unbounded int vars) would truncate. With no excepted values
    // the constraint decomposes to pairwise != — sound at any magnitude; an except-set has no pairwise
    // form, so reject rather than mis-encode.
    val wide = lo < Int.MIN_VALUE.toLong() || hi > Int.MAX_VALUE.toLong() || hi - lo + 1 > Int.MAX_VALUE.toLong()
    if (wide) {
        require(exceptSet.isEmpty()) { "alldifferent_except over a value span exceeding 2^31 is unsupported" }
        for (a in vars.indices) {
            for (b in a + 1 until vars.size) {
                factors.add(Linear(intArrayOf(1, -1), intArrayOf(vars[a], vars[b]), LinearOp.NE, 0))
            }
        }
        return
    }
    factors.add(
        AllDifferent(
            vars = vars,
            domainMin = lo,
            domainSize = (hi - lo + 1).toInt(),
            exceptSet = exceptSet,
            boundsConsistent = boundsConsistent,
        ),
    )
}

internal fun FlatZincCompiler.emitAllEqual(c: FznConstraint) {
    expectArity(c, 1)
    val vars = evalIntVarArray(c.args[0])
    if (vars.size < 2) return
    for (i in 1 until vars.size) {
        factors.add(Linear(intArrayOf(1, -1), intArrayOf(vars[i], vars[0]), LinearOp.EQ, 0))
    }
}

internal fun FlatZincCompiler.emitMember(c: FznConstraint) {
    expectArity(c, 2)
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
    expectArity(c, 2)
    val xs = evalIntVarArray(c.args[0])
    val ys = evalIntVarArray(c.args[1])
    factors.add(Sort(xs, ys))
}

internal fun FlatZincCompiler.emitSymmetricAllDifferent(c: FznConstraint) {
    expectArity(c, 1)
    val xs = evalIntVarArray(c.args[0])
    factors.add(SymmetricAllDifferent(xs, indexOffset = 1))
}

/** Emit `regular(seq, Q, S, d, q0, F)`. */
internal fun FlatZincCompiler.emitRegular(c: FznConstraint) {
    expectArity(c, 6)
    val seq = evalIntVarArray(c.args[0])
    val numStates = evalIntConst(c.args[1]).toInt()
    val numSymbols = evalIntConst(c.args[2]).toInt()
    val transitions = evalIntConstArrayLong(c.args[3])
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
    expectArity(c, 7)
    val seq = evalIntVarArray(c.args[0])
    val n = seq.size
    val level = evalIntConstArray(c.args[2]) // level[node-1], 1-based, nodes 1..N
    val from = evalIntConstArray(c.args[4]) // edge source node (1..N)
    val to = evalIntConstArray(c.args[6]) // edge target node (0=terminal, else 1..N)

    fun setOfExpr(e: FznExpr): LongArray = when (e) {
        is FznExpr.IntSetLit -> e.values.copyOf()
        is FznExpr.IntRangeLit -> LongArray((e.hi - e.lo + 1).toInt()) { e.lo + it }
        else -> failHere("mdd: expected set literal label, got ${e::class.simpleName}")
    }
    val labels: List<LongArray> = when (val la = c.args[5]) {
        is FznExpr.ArrayLit -> la.elements.map(::setOfExpr)

        is FznExpr.Ident -> (arrays[la.name] as? FlatZincArray.IntSetParam)?.values
            ?.map { row -> LongArray(row.size) { row[it].toLong() } }
            ?: failHere("mdd: `${la.name}` is not a set-of-int parameter array")

        else -> failHere("mdd: unsupported label arg ${la::class.simpleName}")
    }
    // Dense node ids `0..N-1` for MDD nodes `1..N`, plus a synthetic terminal node `N` (edge target `0`);
    // nodes explicitly at the terminal level collapse onto local index 0, the terminal.
    val bigN = level.size
    val nodeLayer = IntArray(bigN + 1)
    for (node in 1..bigN) nodeLayer[node - 1] = level[node - 1] - 1
    nodeLayer[bigN] = n
    val localIdx = IntArray(bigN + 1)
    val countPerLayer = IntArray(n + 1)
    countPerLayer[n] = 1 // terminal
    for (node in 1..bigN) {
        val lyr = nodeLayer[node - 1]
        if (lyr in 0 until n) {
            localIdx[node - 1] = countPerLayer[lyr]
            countPerLayer[lyr]++
        }
        // lyr == n keeps local index 0 (collapsed onto the terminal); other layers are unreachable.
    }
    val edgeSrc = IntArrayList()
    val edgeSym = LongArrayList()
    val edgeDst = IntArrayList()
    for (e in from.indices) {
        if (nodeLayer[from[e] - 1] !in 0 until n) continue
        val src = from[e] - 1
        val dst = if (to[e] == 0) bigN else to[e] - 1
        for (v in labels[e]) {
            edgeSrc.add(src)
            edgeSym.add(v)
            edgeDst.add(dst)
        }
    }
    var initialNode = bigN
    for (node in 0..bigN) {
        if (nodeLayer[node] == 0) {
            initialNode = node
            break
        }
    }
    val data = packLayeredMdd(
        n, countPerLayer, localIdx, nodeLayer,
        edgeSrc.toIntArray(), edgeSym.toLongArray(), edgeDst.toIntArray(),
        initialNode, intArrayOf(bigN),
    )
    factors.add(data.toMdd(seq))
}

internal fun FlatZincCompiler.emitTable(c: FznConstraint) {
    expectArity(c, 2)
    val xs = evalIntVarArray(c.args[0])
    val tuples = evalIntConstArrayLong(c.args[1])
    factors.add(Table(xs, tuples))
}

internal fun FlatZincCompiler.emitDiffn(c: FznConstraint, nonStrict: Boolean) {
    expectArity(c, 4)
    val xs = evalIntVarArray(c.args[0])
    val ys = evalIntVarArray(c.args[1])
    val wConst = tryEvalIntConstArrayLong(c.args[2])
    val hConst = tryEvalIntConstArrayLong(c.args[3])
    val wVars = if (wConst == null) evalIntVarArray(c.args[2]) else null
    val hVars = if (hConst == null) evalIntVarArray(c.args[3]) else null
    factors.add(
        Diffn(
            xs = xs,
            ys = ys,
            widths = wConst ?: EmptyLongArray,
            heights = hConst ?: EmptyLongArray,
            widthVars = wVars,
            heightVars = hVars,
            nonStrict = nonStrict,
        ),
    )
}

internal fun FlatZincCompiler.emitValuePrecede(c: FznConstraint) {
    expectArity(c, 3)
    val s = evalIntConst(c.args[0])
    val t = evalIntConst(c.args[1])
    val xs = evalIntVarArray(c.args[2])
    if (xs.isNotEmpty()) factors.add(ValuePrecede(s, t, xs))
}

internal fun FlatZincCompiler.emitValuePrecedeChain(c: FznConstraint) {
    expectArity(c, 2)
    val values = evalIntConstArrayLong(c.args[0])
    val xs = evalIntVarArray(c.args[1])
    if (xs.isEmpty()) return
    for (i in 0 until values.size - 1) factors.add(ValuePrecede(values[i], values[i + 1], xs))
}

internal fun FlatZincCompiler.emitLexLess(c: FznConstraint, strict: Boolean) {
    expectArity(c, 2)
    val xs = evalIntVarArray(c.args[0])
    val ys = evalIntVarArray(c.args[1])
    factors.add(LexLess(xs, ys, strict))
}

internal fun FlatZincCompiler.emitNValue(c: FznConstraint, mode: NValue.Mode) {
    expectArity(c, 2)
    val n = resolveIntVar(c.args[0])
    val xs = evalIntVarArray(c.args[1])
    factors.add(NValue(n, xs, mode))
}

internal fun FlatZincCompiler.emitInverse(c: FznConstraint, withOffsets: Boolean) {
    if (withOffsets) {
        expectArity(c, 4)
        val f = evalIntVarArray(c.args[0])
        val fOff = evalIntConst(c.args[1]).toInt()
        val g = evalIntVarArray(c.args[2])
        val gOff = evalIntConst(c.args[3]).toInt()
        factors.add(Inverse(f, g, fOff, gOff))
    } else {
        expectArity(c, 2)
        val f = evalIntVarArray(c.args[0])
        val g = evalIntVarArray(c.args[1])
        factors.add(Inverse(f, g, fOffset = 1, gOffset = 1))
    }
}

internal fun FlatZincCompiler.emitAllDifferent(c: FznConstraint) {
    expectArity(c, 1)
    val vars = evalIntVarArray(c.args[0])
    val bc = c.annotations.any { it.name == "bounds" }
    emitAllDifferentCore(vars, exceptSet = EmptyLongArray, boundsConsistent = bc)
}

/** Emit `circuit` / `subcircuit`, channeling to 0-based values when needed. */
internal fun FlatZincCompiler.emitCircuit(c: FznConstraint, sub: Boolean) {
    expectArity(c, 1)
    val srcIds = evalIntVarArray(c.args[0])
    val n = srcIds.size
    var offset = Long.MAX_VALUE
    for (v in srcIds) offset = minOf(offset, intDomains[v].min)
    if (offset == Long.MAX_VALUE) offset = 0L
    val ids = if (offset == 0L) {
        srcIds
    } else {
        IntArray(n) { i ->
            val auxName = "__circuit_aux_${i}_${factors.size}"
            val auxId = allocInt(auxName, 0L, (n - 1).toLong())
            factors.add(
                Linear(
                    coeffs = intArrayOf(1, -1),
                    vars = intArrayOf(srcIds[i], auxId),
                    op = LinearOp.EQ,
                    bound = offset.toInt(),
                ),
            )
            auxId
        }
    }
    factors.add(Circuit(succ = ids, subcircuit = sub))
}

internal fun FlatZincCompiler.emitCumulative(c: FznConstraint) {
    expectArity(c, 4)
    val starts = evalIntVarArray(c.args[0])
    val (durations, durationVars) = resolveIntArrayConstOrVars(c.args[1])
    val (resources, resourceVars) = resolveIntArrayConstOrVars(c.args[2])
    val (capacity, capacityVar) = resolveLongConstOrVar(c.args[3])
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
    expectArity(c, 4)
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
    expectArity(c, 2)
    val starts = evalIntVarArray(c.args[0])
    val (durations, durationVars) = resolveIntArrayConstOrVars(c.args[1])
    factors.add(Cumulative.unary(starts = starts, durations = durations, durationVars = durationVars))
}

private fun FlatZincCompiler.resolveIntArrayConstOrVars(e: FznExpr): Pair<LongArray, IntArray> {
    val asConst = tryEvalIntConstArrayLong(e)
    if (asConst != null) return asConst to EmptyIntArray
    val vars = evalIntVarArray(e)
    val ubs = LongArray(vars.size) { intDomains[vars[it]].max }
    return ubs to vars
}

private fun FlatZincCompiler.resolveIntConstOrVar(e: FznExpr): Pair<Int, Int> {
    val asConst = evalIntConstOrNull(e)
    if (asConst != null) return asConst.toInt() to -1
    val varId = resolveIntVar(e)
    return intDomains[varId].max.toInt() to varId
}

// Returns `(constOrUb, varId)` for int scalar arguments whose value may exceed 32-bit range.
private fun FlatZincCompiler.resolveLongConstOrVar(e: FznExpr): Pair<Long, Int> {
    val asConst = evalIntConstOrNull(e)
    if (asConst != null) return asConst to -1
    val varId = resolveIntVar(e)
    return intDomains[varId].max to varId
}

// Shared body for `{exactly,at_least,at_most}_int`.
private fun FlatZincCompiler.emitCountComparison(
    c: FznConstraint,
    tag: String,
    bounds: (n: Int, count: Int) -> Pair<Int, Int>,
) {
    expectArity(c, 3)
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
    expectArity(c, if (variant.lowUp) 4 else 3)
    val xs = evalIntVarArray(c.args[0])
    val cover = evalIntConstArrayLong(c.args[1])
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
    expectArity(c, 3)
    val card = evalIntVarArray(c.args[0])
    val value = evalIntConstArrayLong(c.args[1])
    val base = evalIntVarArray(c.args[2])
    factors.add(GlobalCardinality(xs = base, cover = value, countVars = card))
}

/** Emit `klause_count_eq(x, y, c)` for constant `y` through GCC. */
internal fun FlatZincCompiler.emitCountEq(c: FznConstraint) {
    expectArity(c, 3)
    val xs = evalIntVarArray(c.args[0])
    val value = evalIntConst(c.args[1])
    val (countConst, countVar) = resolveIntConstOrVar(c.args[2])
    if (xs.isEmpty()) {
        if (countVar >= 0) factors.add(Linear(intArrayOf(1), intArrayOf(countVar), LinearOp.EQ, 0))
        return
    }
    if (countVar >= 0) {
        factors.add(GlobalCardinality(xs = xs, cover = longArrayOf(value), countVars = intArrayOf(countVar)))
    } else {
        factors.add(
            GlobalCardinality(
                xs = xs,
                cover = longArrayOf(value),
                countLow = intArrayOf(countConst),
                countHigh = intArrayOf(countConst),
            ),
        )
    }
}

/** Emit `among(n, x, v)` through GCC counts plus a sum constraint. */
internal fun FlatZincCompiler.emitAmong(c: FznConstraint) {
    expectArity(c, 3)
    val n = resolveIntVar(c.args[0])
    val xs = evalIntVarArray(c.args[1])
    val setValues = resolveSetLiteral(c.args[2])
    val cover = if (xs.isEmpty()) {
        EmptyLongArray
    } else {
        val (lo, hi) = checkNotNull(intVarUnionBounds(xs))
        setValues.filter { it in lo..hi }.map { it.toLong() }.toLongArray()
    }
    if (cover.isEmpty()) {
        factors.add(Linear(intArrayOf(1), intArrayOf(n), LinearOp.EQ, 0))
        return
    }
    val counts = IntArray(cover.size) { allocInt("__among_cnt_${cover[it]}_${factors.size}", 0L, xs.size.toLong()) }
    factors.add(GlobalCardinality(xs = xs, cover = cover, countVars = counts))
    val coeffs = IntArray(cover.size + 1) { if (it < cover.size) 1 else -1 }
    factors.add(Linear(coeffs = coeffs, vars = counts + n, op = LinearOp.EQ, bound = 0))
}

internal fun FlatZincCompiler.emitAnnotationConstraint(c: FznConstraint) {
    if (forLocalSearch) return
    expectArity(c, 1)
    val lit = resolveBoolLit(c.args[0])
    factors.add(Clause(intArrayOf(lit)))
}
