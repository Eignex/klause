package com.eignex.klause.ast

fun min(vararg xs: IntTerm): IntExpr {
    require(xs.isNotEmpty()) { "min(): need at least one argument" }
    return IntMin(xs.map { it.toIntExpr() })
}

fun max(vararg xs: IntTerm): IntExpr {
    require(xs.isNotEmpty()) { "max(): need at least one argument" }
    return IntMax(xs.map { it.toIntExpr() })
}

fun abs(x: IntTerm): IntExpr = IntAbs(x.toIntExpr())

fun ifThenElse(cond: BoolTerm, thenE: IntTerm, elseE: IntTerm): IntExpr =
    IntIfThenElse(cond.toExpr(), thenE.toIntExpr(), elseE.toIntExpr())

fun element(index: IntTerm, items: List<IntTerm>): IntExpr {
    require(items.isNotEmpty()) { "element(): items must not be empty" }
    return IntElement(index.toIntExpr(), items.map { it.toIntExpr() })
}

fun allDifferent(vararg xs: IntTerm): BoolExpr {
    require(xs.size >= 2) { "allDifferent(): need at least two terms" }
    // Pigeonhole guard: if all operands are bare schema handles, check that the union of their
    // domains is large enough to host one distinct value per term.
    val handles = xs.mapNotNull { it as? com.eignex.klause.schema.IntHandle }
    if (handles.size == xs.size) {
        val unionSize = unionDomainSize(handles.map { it.min to it.max })
        require(unionSize >= xs.size) {
            "allDifferent: union of operand domains has only $unionSize distinct values, " +
                "cannot host ${xs.size} all-different terms (pigeonhole UNSAT)."
        }
    }
    return AllDifferent(xs.map { it.toIntExpr() })
}

private fun unionDomainSize(ranges: List<Pair<Int, Int>>): Long {
    if (ranges.isEmpty()) return 0L
    val sorted = ranges.sortedBy { it.first }
    var total = 0L
    var curLo = sorted[0].first
    var curHi = sorted[0].second
    for (i in 1 until sorted.size) {
        val (lo, hi) = sorted[i]
        if (lo <= curHi + 1) {
            if (hi > curHi) curHi = hi
        } else {
            total += (curHi - curLo + 1).toLong()
            curLo = lo
            curHi = hi
        }
    }
    total += (curHi - curLo + 1).toLong()
    return total
}

/**
 * N-ary XOR. Evaluates to true iff an odd number of children are true.
 */
fun xor(vararg children: BoolTerm): BoolExpr {
    require(children.isNotEmpty()) { "xor: need at least one child" }
    return XorExpr(children.map { it.toExpr() })
}

/**
 * Pseudo-Boolean weighted-sum constraint: `Σ wᵢ * lᵢ ⟨op⟩ bound` over Boolean literals.
 * Each literal contributes its weight when true, 0 when false.
 */
fun pseudoBoolean(weights: List<Int>, lits: List<BoolTerm>, op: PbOp, bound: Int): BoolExpr {
    require(weights.size == lits.size) { "pseudoBoolean: weights/lits length mismatch" }
    require(lits.isNotEmpty()) { "pseudoBoolean: need at least one literal" }
    return PseudoBooleanExpr(weights, lits.map { it.toExpr() }, op, bound)
}

fun pbAtMost(weights: List<Int>, lits: List<BoolTerm>, k: Int): BoolExpr = pseudoBoolean(weights, lits, PbOp.LE, k)

fun pbAtLeast(weights: List<Int>, lits: List<BoolTerm>, k: Int): BoolExpr = pseudoBoolean(weights, lits, PbOp.GE, k)

fun pbExactly(weights: List<Int>, lits: List<BoolTerm>, k: Int): BoolExpr = pseudoBoolean(weights, lits, PbOp.EQ, k)

/**
 * Extensional positive table: `vars` must equal one of the listed `allowed` tuples.
 */
fun table(vars: List<IntTerm>, allowed: List<List<Int>>): BoolExpr {
    validateTableTuples(vars, allowed)
    return TableConstraint(vars.map { it.toIntExpr() }, allowed, negative = false)
}

/**
 * Extensional negative table: `vars` must not equal any of the listed `forbidden` tuples.
 */
fun notTable(vars: List<IntTerm>, forbidden: List<List<Int>>): BoolExpr {
    validateTableTuples(vars, forbidden)
    return TableConstraint(vars.map { it.toIntExpr() }, forbidden, negative = true)
}

private fun validateTableTuples(vars: List<IntTerm>, tuples: List<List<Int>>) {
    require(vars.isNotEmpty()) { "table: vars must not be empty" }
    require(tuples.isNotEmpty()) { "table: tuples must not be empty" }
    require(tuples.all { it.size == vars.size }) {
        "table: every tuple must have arity ${vars.size}"
    }
    for ((i, term) in vars.withIndex()) {
        val handle = term as? com.eignex.klause.schema.IntHandle ?: continue
        for ((tIdx, tup) in tuples.withIndex()) {
            val v = tup[i]
            require(v in handle.min..handle.max) {
                "table: tuple #$tIdx value $v at position $i is outside operand " +
                    "${handle.name}'s domain [${handle.min}..${handle.max}]"
            }
        }
    }
}

/**
 * Global cardinality constraint. For each `(value, range)` entry, the count of `vars` taking
 * that value must lie inside `range`. Decomposes into one [CardinalityExpr] per entry.
 */
fun gcc(vars: List<IntTerm>, valueCounts: Map<Int, IntRange>): BoolExpr {
    require(vars.isNotEmpty()) { "gcc(): vars must not be empty" }
    require(valueCounts.isNotEmpty()) { "gcc(): valueCounts must not be empty" }
    val parts = valueCounts.map { (value, range) ->
        require(range.first <= range.last) { "gcc: invalid range for value $value: $range" }
        require(range.first >= 0) { "gcc: count range first must be >= 0 for value $value: $range" }
        require(range.last <= vars.size) {
            "gcc: count range last ($range) exceeds number of vars (${vars.size}) for value $value"
        }
        val children = vars.map { v -> IntCompare(v.toIntExpr(), IntCmpOp.EQ, IntLit(value)) }
        CardinalityExpr(children, range.first, range.last)
    }
    return if (parts.size == 1) parts[0] else And(parts)
}

/**
 * Lexicographic less-or-equal: `a` is no greater than `b` when read left-to-right. Empty lists
 * are equal (true). Lists must be the same length.
 */
fun lexLeq(a: List<IntTerm>, b: List<IntTerm>): BoolExpr = lexChain(a, b, strict = false)

/** Strict lexicographic less-than. */
fun lexLt(a: List<IntTerm>, b: List<IntTerm>): BoolExpr = lexChain(a, b, strict = true)

private fun lexChain(a: List<IntTerm>, b: List<IntTerm>, strict: Boolean): BoolExpr {
    require(a.size == b.size) { "lex: lists must have equal length, got ${a.size} and ${b.size}" }
    require(a.isNotEmpty()) { "lex: empty lists are not supported" }
    val ax = a.map { it.toIntExpr() }
    val bx = b.map { it.toIntExpr() }
    // Tail recursion: lex(a, b) ⟺ a[0] < b[0] ∨ (a[0] = b[0] ∧ lex(a[1..], b[1..])).
    // Base case at the last index: <= becomes a[k] ≤ b[k], < becomes a[k] < b[k].
    var result: BoolExpr = if (strict) {
        IntCompare(ax.last(), IntCmpOp.LT, bx.last())
    } else {
        IntCompare(ax.last(), IntCmpOp.LE, bx.last())
    }
    for (i in ax.size - 2 downTo 0) {
        val less = IntCompare(ax[i], IntCmpOp.LT, bx[i])
        val equal = IntCompare(ax[i], IntCmpOp.EQ, bx[i])
        result = Or(listOf(less, And(listOf(equal, result))))
    }
    return result
}

/**
 * Hamiltonian-cycle constraint on a successor array. Default `valueOffset = 0` matches
 * klause's native 0-indexed convention. Pass `valueOffset = 1` to match MiniZinc/FlatZinc
 * 1-indexed inputs.
 */
fun circuit(succ: List<IntTerm>, valueOffset: Int = 0): BoolExpr {
    require(succ.size >= 2) { "circuit(): need at least two nodes" }
    return CircuitExpr(succ.map { it.toIntExpr() }, valueOffset)
}

/** Variadic convenience overload for [circuit]. */
fun circuit(vararg succ: IntTerm): BoolExpr = circuit(succ.toList(), valueOffset = 0)

/**
 * Subcircuit: like [circuit], but `succ[i] = i + valueOffset` marks node `i` excluded.
 * Included nodes form a single cycle.
 */
fun subcircuit(succ: List<IntTerm>, valueOffset: Int = 0): BoolExpr {
    require(succ.isNotEmpty()) { "subcircuit(): need at least one node" }
    return SubcircuitExpr(succ.map { it.toIntExpr() }, valueOffset)
}

/**
 * Cumulative scheduling constraint: `Σ {resources[i] : starts[i] ≤ t < starts[i] + durations[i]} ≤ capacity`
 * at every integer time point `t`. Durations / resources / capacity are constants.
 */
fun cumulative(starts: List<IntTerm>, durations: List<Int>, resources: List<Int>, capacity: Int): BoolExpr {
    require(starts.size == durations.size && starts.size == resources.size) {
        "cumulative(): starts/durations/resources must have the same length"
    }
    return CumulativeExpr(starts.map { it.toIntExpr() }, durations, resources, capacity)
}

/**
 * Disjunctive (one-machine / no-overlap) scheduling constraint. Durations are constants.
 * Tasks may not overlap in time.
 */
fun disjunctive(starts: List<IntTerm>, durations: List<Int>): BoolExpr {
    require(starts.size == durations.size) {
        "disjunctive(): starts and durations must have the same length"
    }
    return DisjunctiveExpr(starts.map { it.toIntExpr() }, durations)
}

// -----------------------------------------------------------------------------------
//  Optional-variable global builders
// -----------------------------------------------------------------------------------
// Each opt-* builder mirrors its non-opt sibling but takes a parallel [presents] list
// of Boolean expressions describing whether each position participates. Internally each
// lowers to its *Opt AST node; the compiler threads presence through to the factor's
// native `presents: IntArray`.

/** AllDifferent over presence-gated positions: only present positions must be pairwise
 *  distinct. Each `OptIntHandle.present` is the typical source for the presence list. */
fun allDifferentOpt(terms: List<IntTerm>, presents: List<BoolTerm>): BoolExpr {
    require(terms.size >= 2) { "allDifferentOpt: need at least two terms" }
    require(terms.size == presents.size) {
        "allDifferentOpt: presents must match terms arity"
    }
    return AllDifferentOpt(
        terms = terms.map { it.toIntExpr() },
        presents = presents.map { it.toExpr() },
    )
}

/** Cumulative with presence-gated tasks. Absent tasks contribute zero energy and don't
 *  appear in the mandatory profile or edge-finding's Θ-tree. */
fun cumulativeOpt(
    starts: List<IntTerm>,
    durations: List<Int>,
    resources: List<Int>,
    capacity: Int,
    presents: List<BoolTerm>,
): BoolExpr {
    require(starts.size == durations.size && starts.size == resources.size && starts.size == presents.size) {
        "cumulativeOpt: starts/durations/resources/presents must have the same length"
    }
    return CumulativeExprOpt(
        starts = starts.map { it.toIntExpr() },
        durations = durations,
        resources = resources,
        capacity = capacity,
        presents = presents.map { it.toExpr() },
    )
}

/** Disjunctive with presence-gated tasks. Absent tasks impose no no-overlap obligation. */
fun disjunctiveOpt(starts: List<IntTerm>, durations: List<Int>, presents: List<BoolTerm>): BoolExpr {
    require(starts.size == durations.size && starts.size == presents.size) {
        "disjunctiveOpt: starts/durations/presents must have the same length"
    }
    return DisjunctiveExprOpt(
        starts = starts.map { it.toIntExpr() },
        durations = durations,
        presents = presents.map { it.toExpr() },
    )
}

/** Count over a presence-gated subset: `n = #{i : xs[i] ⟨op⟩ v ∧ present[i]}`. */
fun countEqOpt(xs: List<IntTerm>, v: Int, n: IntTerm, presents: List<BoolTerm>): BoolExpr =
    countOptCommon(xs, v, n, presents, CountOp.EQ)

fun countNeOpt(xs: List<IntTerm>, v: Int, n: IntTerm, presents: List<BoolTerm>): BoolExpr =
    countOptCommon(xs, v, n, presents, CountOp.NE)

fun countLeOpt(xs: List<IntTerm>, v: Int, n: IntTerm, presents: List<BoolTerm>): BoolExpr =
    countOptCommon(xs, v, n, presents, CountOp.LE)

private fun countOptCommon(xs: List<IntTerm>, v: Int, n: IntTerm, presents: List<BoolTerm>, op: CountOp): BoolExpr {
    require(xs.isNotEmpty()) { "countOpt: xs must be non-empty" }
    require(presents.size == xs.size) { "countOpt: presents must match xs arity" }
    return CountExprOpt(
        xs = xs.map { it.toIntExpr() },
        v = v,
        op = op,
        n = n.toIntExpr(),
        presents = presents.map { it.toExpr() },
    )
}

/** Number of distinct values among the presence-gated subset of xs. */
fun nValueOpt(n: IntTerm, xs: List<IntTerm>, presents: List<BoolTerm>, mode: NValueMode = NValueMode.EQ): BoolExpr {
    require(xs.isNotEmpty()) { "nValueOpt: xs must be non-empty" }
    require(presents.size == xs.size) { "nValueOpt: presents must match xs arity" }
    return NValueExprOpt(
        n = n.toIntExpr(),
        xs = xs.map { it.toIntExpr() },
        mode = mode,
        presents = presents.map { it.toExpr() },
    )
}

/** Global Cardinality with presence-gated counting: for each (value, range), the count
 *  of *present* xs entries taking that value must lie in the range. */
fun gccOpt(
    xs: List<IntTerm>,
    valueCounts: Map<Int, IntRange>,
    presents: List<BoolTerm>,
    closed: Boolean = false,
): BoolExpr {
    require(xs.isNotEmpty()) { "gccOpt: xs must be non-empty" }
    require(valueCounts.isNotEmpty()) { "gccOpt: valueCounts must be non-empty" }
    require(presents.size == xs.size) { "gccOpt: presents must match xs arity" }
    val cover = valueCounts.keys.toList()
    val low = cover.map { valueCounts.getValue(it).first }
    val high = cover.map { valueCounts.getValue(it).last }
    for (i in cover.indices) {
        require(low[i] >= 0 && low[i] <= high[i] && high[i] <= xs.size) {
            "gccOpt: bad range ${low[i]}..${high[i]} for value ${cover[i]} (xs.size=${xs.size})"
        }
    }
    return GccExprOpt(
        xs = xs.map { it.toIntExpr() },
        cover = cover,
        low = low,
        high = high,
        closed = closed,
        presents = presents.map { it.toExpr() },
    )
}

// -----------------------------------------------------------------------------------
//  Newer globals — decomposed in the compiler to existing primitives (Linear / Clause /
//  ReifiedLinear). All bitblastable through the standard path.
// -----------------------------------------------------------------------------------

/**
 * Generalised `alldifferent_except`: pairs of distinct positions must take different values
 * unless one of them takes a value in [except]. `except.isEmpty()` collapses to [allDifferent].
 */
fun allDifferentExcept(xs: List<IntTerm>, except: Set<Int>): BoolExpr {
    require(xs.size >= 2) { "allDifferentExcept: need at least two terms" }
    if (except.isEmpty()) return allDifferent(*xs.toTypedArray())
    return AllDifferentExceptExpr(xs.map { it.toIntExpr() }, except.sorted())
}

/**
 * `arg_sort(values, perm)` — perm is a permutation of `[permOffset, permOffset + n − 1]`
 * such that `values[perm[i] − permOffset]` is non-decreasing. Ties broken by index.
 */
fun argSort(values: List<IntTerm>, perm: List<IntTerm>, permOffset: Int = 0): BoolExpr {
    require(values.size == perm.size) { "argSort: values and perm length mismatch" }
    require(values.isNotEmpty()) { "argSort: values must be non-empty" }
    return ArgSortExpr(
        values = values.map { it.toIntExpr() },
        perm = perm.map { it.toIntExpr() },
        permOffset = permOffset,
    )
}

/**
 * `path` over a static graph described by parallel `from` / `to` arc arrays. Selected
 * nodes/edges must form a simple directed path from [source] to [sink].
 */
fun path(
    numNodes: Int,
    from: List<Int>,
    to: List<Int>,
    source: IntTerm,
    sink: IntTerm,
    nodePresent: List<BoolTerm>,
    edgePresent: List<BoolTerm>,
    nodeOffset: Int = 0,
): BoolExpr = PathExpr(
    numNodes = numNodes,
    from = from,
    to = to,
    source = source.toIntExpr(),
    sink = sink.toIntExpr(),
    nodePresent = nodePresent.map { it.toExpr() },
    edgePresent = edgePresent.map { it.toExpr() },
    nodeOffset = nodeOffset,
)

/** `tree`: selected nodes/edges form a directed in-tree rooted at [root]. */
fun tree(
    numNodes: Int,
    from: List<Int>,
    to: List<Int>,
    root: IntTerm,
    nodePresent: List<BoolTerm>,
    edgePresent: List<BoolTerm>,
    nodeOffset: Int = 0,
): BoolExpr = TreeExpr(
    numNodes = numNodes,
    from = from,
    to = to,
    root = root.toIntExpr(),
    nodePresent = nodePresent.map { it.toExpr() },
    edgePresent = edgePresent.map { it.toExpr() },
    nodeOffset = nodeOffset,
)

/** Network flow: per-node `inflow − outflow = balance[n]`. */
fun networkFlow(
    numNodes: Int,
    arcFrom: List<Int>,
    arcTo: List<Int>,
    balance: List<Int>,
    flow: List<IntTerm>,
    nodeOffset: Int = 0,
): BoolExpr = NetworkFlowExpr(
    numNodes = numNodes,
    arcFrom = arcFrom,
    arcTo = arcTo,
    balance = balance,
    flow = flow.map { it.toIntExpr() },
    nodeOffset = nodeOffset,
)

/** Network flow with cost = Σ weight[a] · flow[a]. */
fun networkFlowCost(
    numNodes: Int,
    arcFrom: List<Int>,
    arcTo: List<Int>,
    balance: List<Int>,
    weight: List<Int>,
    flow: List<IntTerm>,
    cost: IntTerm,
    nodeOffset: Int = 0,
): BoolExpr = NetworkFlowCostExpr(
    numNodes = numNodes,
    arcFrom = arcFrom,
    arcTo = arcTo,
    balance = balance,
    weight = weight,
    flow = flow.map { it.toIntExpr() },
    cost = cost.toIntExpr(),
    nodeOffset = nodeOffset,
)

/**
 * `geost(numDims, origins, sizes)` — N-dimensional non-overlapping axis-aligned boxes.
 * `origins` is row-major `[numObjects × numDims]`; `sizes` matches.
 */
fun geost(numDims: Int, origins: List<IntTerm>, sizes: List<Int>): BoolExpr {
    require(numDims >= 1) { "geost: numDims must be ≥ 1" }
    require(origins.size == sizes.size) { "geost: origins and sizes length mismatch" }
    require(origins.size % numDims == 0) { "geost: origins length must be a multiple of numDims" }
    val nObj = origins.size / numDims
    return GeostExpr(
        numDims = numDims,
        numObjects = nObj,
        origin = origins.map { it.toIntExpr() },
        length = sizes,
    )
}

/**
 * `mdd(seq, layers)` — sequence-acceptance through a layered multi-valued decision diagram.
 * The `transitions` list is a flat row-major sequence of `(src, value, dst)` triples per
 * layer; [layerStarts] holds the start index per layer (with sentinel for the end).
 */
fun mdd(
    seq: List<IntTerm>,
    numStatesPerLayer: List<Int>,
    layerStarts: List<Int>,
    transitions: List<Int>,
    initial: Int,
    accepting: List<Int>,
): BoolExpr = MddExpr(
    seq = seq.map { it.toIntExpr() },
    numStatesPerLayer = numStatesPerLayer,
    layerStarts = layerStarts,
    transitions = transitions,
    initial = initial,
    accepting = accepting,
)

/** `cost_regular(seq, Q, S, transitions, weights, q0, F, cost)`. */
fun costRegular(
    seq: List<IntTerm>,
    numStates: Int,
    numSymbols: Int,
    transitions: List<Int>,
    weights: List<Int>,
    initial: Int,
    accepting: List<Int>,
    cost: IntTerm,
    symbolOffset: Int = 1,
): BoolExpr = CostRegularExpr(
    seq = seq.map { it.toIntExpr() },
    numStates = numStates,
    numSymbols = numSymbols,
    transitions = transitions,
    weights = weights,
    initial = initial,
    accepting = accepting,
    cost = cost.toIntExpr(),
    symbolOffset = symbolOffset,
)

/** `cost_mdd(seq, ..., cost)` — like [mdd] with `(src, val, dst, weight)` rows. */
fun costMdd(
    seq: List<IntTerm>,
    numStatesPerLayer: List<Int>,
    layerStarts: List<Int>,
    transitions: List<Int>,
    initial: Int,
    accepting: List<Int>,
    cost: IntTerm,
): BoolExpr = CostMddExpr(
    seq = seq.map { it.toIntExpr() },
    numStatesPerLayer = numStatesPerLayer,
    layerStarts = layerStarts,
    transitions = transitions,
    initial = initial,
    accepting = accepting,
    cost = cost.toIntExpr(),
)

/**
 * Channel an integer variable to a list of Boolean indicators: `bools[i] iff (intVar = offset+i)`
 * for every index `i`. Useful for switching between an integer and a one-hot Boolean encoding.
 */
fun channel(intVar: IntTerm, bools: List<BoolTerm>, offset: Int = 0): BoolExpr {
    require(bools.isNotEmpty()) { "channel(): need at least one indicator" }
    val intExpr = intVar.toIntExpr()
    val pieces = bools.mapIndexed { i, b ->
        Iff(b.toExpr(), IntCompare(intExpr, IntCmpOp.EQ, IntLit(offset + i)))
    }
    return if (pieces.size == 1) pieces[0] else And(pieces)
}
