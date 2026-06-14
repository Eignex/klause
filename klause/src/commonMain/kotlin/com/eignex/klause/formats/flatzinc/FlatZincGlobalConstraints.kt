package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Diffn
import com.eignex.klause.solver.factor.Disjunctive
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.LexLess
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Mdd
import com.eignex.klause.solver.factor.NValue
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Sort
import com.eignex.klause.solver.factor.Subcircuit
import com.eignex.klause.solver.factor.SymmetricAllDifferent
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.factor.ValuePrecede

internal fun FlatZincCompiler.emitAllDifferentExceptZero(c: FznConstraint) {
    require(c.args.size == 1)
    val vars = evalIntVarArray(c.args[0])
    emitAllDifferentExcept(vars, intArrayOf(0))
}

/** Native `alldifferent_except(xs, except)` — `xs(i) != xs(j)` for every pair unless one of the
 *  two values is in [except]. Emits the [AllDifferent] factor with [AllDifferent.exceptSet]: the
 *  excepted values are modelled inside the shared `reginFilter` as capacity-n value copies, so this gets
 *  full Régin matching / Hall propagation — far stronger than the O(n²) reified gated-pairwise-NE
 *  decomposition this replaced (#433). 0 or 1 vars are trivially distinct; the factor requires ≥2
 *  (matching the std decomposition's empty `forall(i<j)`). */
private fun FlatZincCompiler.emitAllDifferentExcept(vars: IntArray, except: IntArray) {
    if (vars.size < 2) return
    var lo = Int.MAX_VALUE
    var hi = Int.MIN_VALUE
    for (v in vars) {
        val d = intDomains[v]
        if (d.min < lo) lo = d.min
        if (d.max > hi) hi = d.max
    }
    factors.add(AllDifferent(vars = vars, domainMin = lo, domainSize = hi - lo + 1, exceptSet = except))
}

internal fun FlatZincCompiler.emitAllEqual(c: FznConstraint) {
    require(c.args.size == 1)
    val vars = evalIntVarArray(c.args[0])
    if (vars.size < 2) return
    // all_equal(xs) → xs(i) = xs(0) for i = 1..n-1 as a Linear EQ chain; equality is
    // propagation-complete so the chain matches the global.
    for (i in 1 until vars.size) {
        factors.add(Linear(intArrayOf(1, -1), intArrayOf(vars[i], vars[0]), LinearOp.EQ, 0))
    }
}

/** `member_int(xs, y)` → `eq(i) ↔ (xs(i) = y)` reified, then `Σ eq(i) ≥ 1` as a Cardinality. */
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

/** `symmetric_all_different(xs)` — self-inverse permutation. */
internal fun FlatZincCompiler.emitSymmetricAllDifferent(c: FznConstraint) {
    require(c.args.size == 1)
    val xs = evalIntVarArray(c.args[0])
    // Self-inverse permutation over FlatZinc array index sets (always 1-based via MiniZinc's
    // `index2int`), so the index base is structurally 1 — not something to read off a variable's
    // domain. Inferring it from `intDomains[xs[0]].min` is wrong once MiniZinc tightens the first
    // element's domain, the same root false-UNSAT bug fixed for inverse (#389).
    factors.add(SymmetricAllDifferent(xs, indexOffset = 1))
}

/**
 * `regular(seq, Q, S, d, q0, F)` — DFA acceptance.
 *  - `seq`: var int array
 *  - `Q`: int (state count)
 *  - `S`: int (alphabet size)
 *  - `d`: 2D int array (Q × S), flattened
 *  - `q0`: int (initial state)
 *  - `F`: set of int (accepting states)
 */
internal fun FlatZincCompiler.emitRegular(c: FznConstraint) {
    require(c.args.size == 6)
    val seq = evalIntVarArray(c.args[0])
    val numStates = evalIntConst(c.args[1]).toInt()
    val numSymbols = evalIntConst(c.args[2]).toInt()
    val transitions = evalIntConstArray(c.args[3])
    val q0 = evalIntConst(c.args[4]).toInt()
    val fSet = c.args[5]
    val accepting: IntArray = when (fSet) {
        is FznExpr.IntSetLit -> IntArray(fSet.values.size) { fSet.values[it].toInt() }
        is FznExpr.IntRangeLit -> IntArray((fSet.hi - fSet.lo + 1).toInt()) { (fSet.lo + it).toInt() }
        else -> failHere("regular: expected set literal for F, got ${fSet::class.simpleName}")
    }
    factors.add(Regular(seq, numStates, numSymbols, transitions, q0, accepting))
}

/**
 * `mdd(x, N, level, E, from, label, to)` — layered multi-valued decision diagram acceptance.
 * MiniZinc's node/level/edge DAG form: nodes `1..N` with `level(node)` (root = node 1 at
 * level 1), edges `(from(e), label(e), to(e))` with `to = 0` denoting the terminal at level
 * `|x|+1`. Translated to klause's layered [Mdd]: per-level
 * local state renumbering, with the terminal as the single accepting state of the last layer.
 */
internal fun FlatZincCompiler.emitMdd(c: FznConstraint) {
    require(c.args.size == 7)
    val seq = evalIntVarArray(c.args[0])
    val n = seq.size
    val level = evalIntConstArray(c.args[2]) // level[node-1], 1-based, nodes 1..N
    val from = evalIntConstArray(c.args[4]) // edge source node (1..N)
    val to = evalIntConstArray(c.args[6]) // edge target node (0=terminal, else 1..N)

    // `label` is an `array of set of int` — either an inline literal or a named param array.
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
    // Local index per node within its layer. Layer i (0-based) ↔ level i+1. Terminal occupies
    // the last layer (index n) as local state 0.
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
    // Bucket transitions by layer, then flatten with layerStarts.
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
    // n decision layers (0..n-1) carry transitions; layerStarts has n+1 entries delimiting
    // them (the terminal layer n has no outgoing transitions).
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

/**
 * `table_int(xs, tuples)`. The `tuples` arg is a row-major 2D-array literal; we flatten it
 * to a 1D `IntArray` and feed the [Table] factor along with the arity inferred from xs.
 */
internal fun FlatZincCompiler.emitTable(c: FznConstraint) {
    require(c.args.size == 2)
    val xs = evalIntVarArray(c.args[0])
    val tuples = evalIntConstArray(c.args[1])
    factors.add(Table(xs, tuples))
}

/** `diffn(xs, ys, widths, heights)` / `diffn_nonstrict(...)` — 2D rectangle non-overlap. */
internal fun FlatZincCompiler.emitDiffn(c: FznConstraint, nonStrict: Boolean) {
    require(c.args.size == 4)
    val xs = evalIntVarArray(c.args[0])
    val ys = evalIntVarArray(c.args[1])
    // Dimensions may be constant or variable (each axis independently): try const first,
    // fall back to var ids. The native Diffn reads var sizes from the assignment.
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

/** `value_precede(s, t, xs)`: t may only appear in xs after s has — the native [ValuePrecede]
 *  GAC factor (#432), replacing the sub-GAC reified-equality + prefix-OR clause decomposition. */
internal fun FlatZincCompiler.emitValuePrecede(c: FznConstraint) {
    require(c.args.size == 3)
    val s = evalIntConst(c.args[0]).toInt()
    val t = evalIntConst(c.args[1]).toInt()
    val xs = evalIntVarArray(c.args[2])
    if (xs.isNotEmpty()) factors.add(ValuePrecede(s, t, xs))
}

/** `value_precede_chain_int(values, xs)` — one native [ValuePrecede] per consecutive
 *  `(values(i), values(i+1))` pair. */
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

/** `nvalue(n, xs)` / `atleast_nvalues(n, xs)` / `atmost_nvalues(n, xs)`. */
internal fun FlatZincCompiler.emitNValue(c: FznConstraint, mode: NValue.Mode) {
    require(c.args.size == 2)
    val n = resolveIntVar(c.args[0])
    val xs = evalIntVarArray(c.args[1])
    factors.add(NValue(n, xs, mode))
}

/** `inverse(f, g)` (2 args) and `inverse_offsets(f, fOff, g, gOff)` (4 args). */
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
        // The bare `fzn_inverse(f, invf)` is `f[i] ∈ index_set(invf) ∧ invf[f[i]] = i` (and
        // symmetrically), over FlatZinc array index sets — which are always 1-based, since
        // MiniZinc's `index2int` normalises them before emitting. So the index base (offset) is
        // structurally 1, NOT something to infer from a variable's domain: inferring it from
        // `intDomains[f[0]].min` is wrong whenever MiniZinc has tightened the first element's
        // domain (e.g. a `row[t] in 1..7` group split), which produced a root false-UNSAT on
        // elitserien/handball (#389). The explicit-offset `inverse_offsets` form carries real
        // offsets and takes the branch above.
        factors.add(Inverse(f, g, fOffset = 1, gOffset = 1))
    }
}

internal fun FlatZincCompiler.emitAllDifferent(c: FznConstraint) {
    require(c.args.size == 1)
    val vars = evalIntVarArray(c.args[0])
    // 0 or 1 variables are trivially all-different; the native AllDifferent factor requires
    // ≥2, so skip it (matches the std decomposition's empty `forall(i<j)`).
    if (vars.size < 2) return
    // Find the union of all involved int domains to size AllDifferent.
    var lo = Int.MAX_VALUE
    var hi = Int.MIN_VALUE
    for (v in vars) {
        val d = intDomains[v]
        if (d.min < lo) lo = d.min
        if (d.max > hi) hi = d.max
    }
    factors.add(AllDifferent(vars = vars, domainMin = lo, domainSize = hi - lo + 1))
}

/**
 * `circuit(succ)` / `subcircuit(succ)`. FlatZinc emits these with the array's *declared*
 * index base — typically `1..n` from MiniZinc, but the index base is implicit in the
 * succ vars' domains. The klause [Circuit] / [Subcircuit] factors are 0-indexed; if the
 * succ domains' minimum is nonzero, we channel through aux 0-indexed vars via Linear
 * factors so the factor itself stays canonical.
 */
internal fun FlatZincCompiler.emitCircuit(c: FznConstraint, sub: Boolean) {
    require(c.args.size == 1)
    val srcIds = evalIntVarArray(c.args[0])
    val n = srcIds.size
    // Infer value-offset from the domains: MiniZinc's standard `circuit` uses 1-based
    // node indexing, so domain min is usually 1. We use the smallest domain.min seen.
    var offset = Int.MAX_VALUE
    for (v in srcIds) offset = minOf(offset, intDomains[v].min)
    if (offset == Int.MAX_VALUE) offset = 0
    val ids = if (offset == 0) {
        srcIds
    } else {
        IntArray(n) { i ->
            val auxName = "__circuit_aux_${i}_${factors.size}"
            val auxId = allocInt(auxName, 0, n - 1)
            // src(i) − aux(i) = offset.
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

/**
 * `cumulative(starts, durations, resources, capacity)`. Durations, resources, and
 * capacity may each be either constants or variables; the factor reads current values
 * via the var arrays at solve time and falls back to the const fast path when all are
 * fixed.
 */
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

/**
 * `fzn_sliding_sum(low, up, seq, vs)` — every length-`seq` window of `vs` sums into
 * `[low, up]`. Lowered to a pair of Linear range bounds per window.
 */
internal fun FlatZincCompiler.emitSlidingSum(c: FznConstraint) {
    require(c.args.size == 4) { "sliding_sum expects 4 args (low,up,seq,vs), got ${c.args.size}" }
    val low = evalIntConst(c.args[0]).toInt()
    val up = evalIntConst(c.args[1]).toInt()
    val seq = evalIntConst(c.args[2]).toInt()
    val vs = evalIntVarArray(c.args[3])
    // Every contiguous window of seq elements sums to [low, up] → a pair of Linear range
    // bounds per window. (Empty when seq > vs.size: the range 0..(size-seq) is empty.)
    for (w in 0..vs.size - seq) {
        val window = IntArray(seq) { vs[w + it] }
        factors.add(Linear(IntArray(seq) { 1 }, window.copyOf(), LinearOp.GE, low))
        factors.add(Linear(IntArray(seq) { 1 }, window, LinearOp.LE, up))
    }
}

/** `disjunctive(starts, durations)` / `disjunctive_strict(...)`. Durations may be var. */
internal fun FlatZincCompiler.emitDisjunctive(c: FznConstraint) {
    require(c.args.size == 2) { "disjunctive expects 2 args, got ${c.args.size}" }
    val starts = evalIntVarArray(c.args[0])
    val (durations, durationVars) = resolveIntArrayConstOrVars(c.args[1])
    factors.add(Disjunctive(starts = starts, durations = durations, durationVars = durationVars))
}

/** Returns (values, vars). When `e` is all-constant, `vars` is empty and `values` holds
 *  the constants. When `e` is a var array, `vars` holds the var ids and `values` holds
 *  each var's current domain ub — the factor uses these as worst-case bounds for horizon
 *  sizing and reads the live values via the var ids at solve time. */
private fun FlatZincCompiler.resolveIntArrayConstOrVars(e: FznExpr): Pair<IntArray, IntArray> {
    val asConst = tryEvalIntConstArray(e)
    if (asConst != null) return asConst to EmptyIntArray
    val vars = evalIntVarArray(e)
    val ubs = IntArray(vars.size) { intDomains[vars[it]].max }
    return ubs to vars
}

/** Returns (constOrUb, varId). When `e` is an int literal/param, varId = -1 and the int
 *  is the value. When `e` is a var, varId is set and the int is the var's domain ub. */
private fun FlatZincCompiler.resolveIntConstOrVar(e: FznExpr): Pair<Int, Int> {
    val asConst = evalIntConstOrNull(e)
    if (asConst != null) return asConst.toInt() to -1
    val varId = resolveIntVar(e)
    return intDomains[varId].max to varId
}

/**
 * Shared body of the `{exactly,at_least,at_most}_int(n, xs, v)` count comparisons: reify
 * `xs(i) = v` at each position under an aux named `__${tag}_…`, then pin the number of true
 * positions with a [Cardinality]. The bounds differ per builtin and are computed by [bounds]
 * from the threshold `n` and the position count.
 */
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

/** `exactly_int(n, xs, v)` — n equals `#{i : xs(i) = v}`. */
internal fun FlatZincCompiler.emitExactly(c: FznConstraint) = emitCountComparison(c, "exactly") { n, _ -> n to n }

/** `at_least_int(n, xs, v)` — at least n of `xs(i) = v`. */
internal fun FlatZincCompiler.emitAtLeast(c: FznConstraint) =
    emitCountComparison(c, "atleast") { n, count -> n to count }

/** `at_most_int(n, xs, v)` — at most n of `xs(i) = v`. */
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
        // MZN allows the `counts` argument to be either an int-var array (the standard
        // form) or a constant int array (count must equal that fixed value). Detect the
        // constant form first and route to the low_up path with lo(i) = up(i) = counts(i);
        // the var form falls through to the GlobalCardinality(countVars) constructor.
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

/** `distribute(card(), value(), base())` — alias for `gcc(base, value, card)` (older
 *  MiniZinc syntax; equivalent semantics, parameter order shuffled). */
internal fun FlatZincCompiler.emitDistribute(c: FznConstraint) {
    require(c.args.size == 3)
    val card = evalIntVarArray(c.args[0])
    val value = evalIntConstArray(c.args[1])
    val base = evalIntVarArray(c.args[2])
    factors.add(GlobalCardinality(xs = base, cover = value, countVars = card))
}

internal fun FlatZincCompiler.emitAnnotationConstraint(c: FznConstraint) {
    if (forLocalSearch) return
    require(c.args.size == 1) { "${c.name} expects 1 arg" }
    val lit = resolveBoolLit(c.args[0])
    factors.add(Clause(intArrayOf(lit)))
}
