package com.eignex.klause.schema

import com.eignex.klause.model.AllDifferent
import com.eignex.klause.model.AllDifferentOpt
import com.eignex.klause.model.And
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.BoolTerm
import com.eignex.klause.model.CardinalityExpr
import com.eignex.klause.model.CircuitExpr
import com.eignex.klause.model.CostMddExpr
import com.eignex.klause.model.CostRegularExpr
import com.eignex.klause.model.CumulativeExpr
import com.eignex.klause.model.CumulativeExprOpt
import com.eignex.klause.model.DiffnExpr
import com.eignex.klause.model.DisjunctiveExpr
import com.eignex.klause.model.DisjunctiveExprOpt
import com.eignex.klause.model.GccExprOpt
import com.eignex.klause.model.Iff
import com.eignex.klause.model.IncreasingExpr
import com.eignex.klause.model.IntAbs
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.IntCompare
import com.eignex.klause.model.IntElement
import com.eignex.klause.model.IntExpr
import com.eignex.klause.model.IntIfThenElse
import com.eignex.klause.model.IntLit
import com.eignex.klause.model.IntMax
import com.eignex.klause.model.IntMin
import com.eignex.klause.model.IntTerm
import com.eignex.klause.model.InverseChannel
import com.eignex.klause.model.LexExpr
import com.eignex.klause.model.MddExpr
import com.eignex.klause.model.NValueExprOpt
import com.eignex.klause.model.NValueMode
import com.eignex.klause.model.PbOp
import com.eignex.klause.model.PseudoBooleanExpr
import com.eignex.klause.model.RegularExpr
import com.eignex.klause.model.SortExpr
import com.eignex.klause.model.SubcircuitExpr
import com.eignex.klause.model.SymmetricAllDifferent
import com.eignex.klause.model.TableConstraint
import com.eignex.klause.model.VariableCumulativeExpr
import com.eignex.klause.model.XorExpr

/**
 * Absolute value of this term.
 *
 * `abs`, `min`, `max` and `pow` are receiver helpers and conditionals are [ifThenElse], the
 * spellings Kumulant's binary64 expressions share, so portable fragments read the same in both.
 */
fun IntTerm.abs(): IntExpr = IntAbs(toIntExpr())

/** The smaller of this term and [other], as one two-operand node. */
fun IntTerm.min(other: IntTerm): IntExpr = IntMin(listOf(toIntExpr(), other.toIntExpr()))

/** The smaller of this term and the constant [c]. */
fun IntTerm.min(c: Int): IntExpr = IntMin(listOf(toIntExpr(), IntLit(c)))

/** The larger of this term and [other], as one two-operand node. */
fun IntTerm.max(other: IntTerm): IntExpr = IntMax(listOf(toIntExpr(), other.toIntExpr()))

/** The larger of this term and the constant [c]. */
fun IntTerm.max(c: Int): IntExpr = IntMax(listOf(toIntExpr(), IntLit(c)))

/** Minimum of [xs] (at least one argument) as one n-ary node, an integer-only extension of `min`. */
fun minOf(vararg xs: IntTerm): IntExpr {
    require(xs.isNotEmpty()) { "minOf(): need at least one argument" }
    return IntMin(xs.map { it.toIntExpr() })
}

/** Maximum of [xs] (at least one argument) as one n-ary node, an integer-only extension of `max`. */
fun maxOf(vararg xs: IntTerm): IntExpr {
    require(xs.isNotEmpty()) { "maxOf(): need at least one argument" }
    return IntMax(xs.map { it.toIntExpr() })
}

/** [then] when [cond] holds, else [otherwise], as an integer expression. */
fun ifThenElse(cond: BoolTerm, then: IntTerm, otherwise: IntTerm): IntExpr =
    IntIfThenElse(cond.toExpr(), then.toIntExpr(), otherwise.toIntExpr())

/** [then] when [cond] holds, else [otherwise], with a constant [then] branch. */
fun ifThenElse(cond: BoolTerm, then: Int, otherwise: IntTerm): IntExpr = ifThenElse(cond, IntLit(then), otherwise)

/** [then] when [cond] holds, else [otherwise], with a constant [otherwise] branch. */
fun ifThenElse(cond: BoolTerm, then: IntTerm, otherwise: Int): IntExpr = ifThenElse(cond, then, IntLit(otherwise))

/** [then] when [cond] holds, else [otherwise], with constant branches. */
fun ifThenElse(cond: BoolTerm, then: Int, otherwise: Int): IntExpr =
    ifThenElse(cond, IntLit(then), IntLit(otherwise))

/** `items[index]` — array element selection (items must be non-empty). */
fun element(index: IntTerm, items: List<IntTerm>): IntExpr {
    require(items.isNotEmpty()) { "element(): items must not be empty" }
    return IntElement(index.toIntExpr(), items.map { it.toIntExpr() })
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

/** Pseudo-Boolean `Σ wᵢ·lᵢ ≤ k`. */
fun pbAtMost(weights: List<Int>, lits: List<BoolTerm>, k: Int): BoolExpr = pseudoBoolean(weights, lits, PbOp.LE, k)

/** Pseudo-Boolean `Σ wᵢ·lᵢ ≥ k`. */
fun pbAtLeast(weights: List<Int>, lits: List<BoolTerm>, k: Int): BoolExpr = pseudoBoolean(weights, lits, PbOp.GE, k)

/** Pseudo-Boolean `Σ wᵢ·lᵢ = k`. */
fun pbExactly(weights: List<Int>, lits: List<BoolTerm>, k: Int): BoolExpr = pseudoBoolean(weights, lits, PbOp.EQ, k)

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
 * Lexicographic less-or-equal: `a` is no greater than `b` when read left-to-right. Lists must be
 * non-empty and of equal length. Asserted directly it posts one native lexicographic factor.
 */
fun lexLeq(a: List<IntTerm>, b: List<IntTerm>): BoolExpr =
    LexExpr(a.map { it.toIntExpr() }, b.map { it.toIntExpr() }, strict = false)

/** Strict lexicographic less-than. */
fun lexLt(a: List<IntTerm>, b: List<IntTerm>): BoolExpr =
    LexExpr(a.map { it.toIntExpr() }, b.map { it.toIntExpr() }, strict = true)

/**
 * Cumulative scheduling whose [durations], [demands] and [capacity] may be variables: at every time point
 * the demands of the running tasks stay within [capacity].
 */
fun cumulative(starts: List<IntTerm>, durations: List<IntTerm>, demands: List<IntTerm>, capacity: IntTerm): BoolExpr =
    VariableCumulativeExpr(
        starts.map { it.toIntExpr() },
        durations.map { it.toIntExpr() },
        demands.map { it.toIntExpr() },
        capacity.toIntExpr(),
    )

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
 * Subcircuit: like [circuit], but `succ(i) = i + valueOffset` marks node `i` excluded.
 * Included nodes form a single cycle.
 */
fun subcircuit(succ: List<IntTerm>, valueOffset: Int = 0): BoolExpr {
    require(succ.isNotEmpty()) { "subcircuit(): need at least one node" }
    return SubcircuitExpr(succ.map { it.toIntExpr() }, valueOffset)
}

/**
 * Cumulative scheduling constraint: `Σ {resources(i) : starts(i) ≤ t < starts(i) + durations(i)} ≤ capacity`
 * at every integer time point `t`. Durations / resources / capacity are constants.
 */
fun cumulative(starts: List<IntTerm>, durations: List<Int>, resources: List<Int>, capacity: Int): BoolExpr {
    require(starts.size == durations.size && starts.size == resources.size) {
        "cumulative(): starts/durations/resources must have the same length"
    }
    return CumulativeExpr(starts.map { it.toIntExpr() }, durations, resources, capacity)
}

/** Disjunctive (one-machine / no-overlap) scheduling constraint: no two tasks overlap. Durations are constants. */
fun disjunctive(starts: List<IntTerm>, durations: List<Int>): BoolExpr {
    require(starts.size == durations.size) {
        "disjunctive(): starts and durations must have the same length"
    }
    return DisjunctiveExpr(starts.map { it.toIntExpr() }, durations)
}

/** [ys] is the non-decreasing sorted permutation of [xs] (same multiset). */
fun sort(xs: List<IntTerm>, ys: List<IntTerm>): BoolExpr {
    require(xs.size == ys.size) { "sort(): xs and ys must have the same length" }
    return SortExpr(xs.map { it.toIntExpr() }, ys.map { it.toIntExpr() })
}

/** [xs] is a non-decreasing chain (`xs(0) ≤ xs(1) ≤ …`). */
fun increasing(xs: List<IntTerm>): BoolExpr = IncreasingExpr(xs.map { it.toIntExpr() }, strict = false)

/** [xs] is a strictly increasing chain (`xs(0) < xs(1) < …`). */
fun strictlyIncreasing(xs: List<IntTerm>): BoolExpr = IncreasingExpr(xs.map { it.toIntExpr() }, strict = true)

/** [xs] is a non-increasing chain (`xs(0) ≥ xs(1) ≥ …`), posted as the reversed increasing chain. */
fun decreasing(xs: List<IntTerm>): BoolExpr = IncreasingExpr(xs.reversed().map { it.toIntExpr() }, strict = false)

/** [xs] is a strictly decreasing chain (`xs(0) > xs(1) > …`). */
fun strictlyDecreasing(xs: List<IntTerm>): BoolExpr =
    IncreasingExpr(xs.reversed().map { it.toIntExpr() }, strict = true)

/**
 * Rectangles at `(x(i), y(i))` of size `widths(i) × heights(i)` do not overlap. Widths and heights
 * are constants.
 */
fun diffn(x: List<IntTerm>, y: List<IntTerm>, widths: List<Int>, heights: List<Int>): BoolExpr {
    require(x.size == y.size && x.size == widths.size && x.size == heights.size) {
        "diffn(): x, y, widths and heights must have the same length"
    }
    return DiffnExpr(x.map { it.toIntExpr() }, y.map { it.toIntExpr() }, widths, heights)
}

/**
 * [seq] is accepted by the DFA with states `1..numStates`, symbols `1..alphabetSize`, row-major [transitions] (0 = no
 * transition), start state [q0] and [accepting] states.
 */
fun regular(
    seq: List<IntTerm>,
    numStates: Int,
    alphabetSize: Int,
    transitions: List<Int>,
    q0: Int,
    accepting: List<Int>,
): BoolExpr {
    require(transitions.size == numStates * alphabetSize) {
        "regular(): transitions must have numStates * alphabetSize entries"
    }
    return RegularExpr(seq.map { it.toIntExpr() }, numStates, alphabetSize, transitions, q0, accepting)
}

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

/**
 * The map `i -> xs(i) − indexOffset` is a self-inverse permutation:
 * `xs(i) = j + indexOffset` iff `xs(j) = i + indexOffset`.
 */
fun symmetricAllDifferent(xs: List<IntTerm>, indexOffset: Int = 0): BoolExpr {
    require(xs.size >= 2) { "symmetricAllDifferent: need at least two terms" }
    return SymmetricAllDifferent(xs.map { it.toIntExpr() }, indexOffset)
}

/**
 * Mutual-inverse channeling: `f(i) = j + gOffset` iff `g(j) = i + fOffset`.
 */
fun inverse(f: List<IntTerm>, g: List<IntTerm>, fOffset: Int = 0, gOffset: Int = 0): BoolExpr {
    require(f.size == g.size) { "inverse: f and g must have equal length" }
    require(f.isNotEmpty()) { "inverse: arrays must be non-empty" }
    return InverseChannel(f.map { it.toIntExpr() }, g.map { it.toIntExpr() }, fOffset, gOffset)
}

/**
 * [seq] is accepted by a layered multi-valued decision diagram.
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

/**
 * [seq] is accepted by the DFA as in [regular], and [cost] is the sum of the per-`(state, symbol)`
 * [weights] along the accepted run.
 */
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

/** Like [mdd] with `(src, val, dst, weight)` rows; [cost] is the total weight of the accepted path. */
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

/** All of [xs] take pairwise-distinct values (with a pigeonhole UNSAT guard on bare handles). */
fun allDifferent(vararg xs: IntTerm): BoolExpr {
    require(xs.size >= 2) { "allDifferent(): need at least two terms" }
    // Pigeonhole guard: if all operands are bare schema handles, check that the union of their
    // domains is large enough to host one distinct value per term.
    val handles = xs.mapNotNull { it as? IntHandle }
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

/** Extensional positive table: `vars` must equal one of the listed `allowed` tuples. */
fun table(vars: List<IntTerm>, allowed: List<List<Int>>): BoolExpr {
    validateTableTuples(vars, allowed)
    return TableConstraint(vars.map { it.toIntExpr() }, allowed, negative = false)
}

/** Extensional negative table: `vars` must not equal any of the listed `forbidden` tuples. */
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
        val handle = term as? IntHandle ?: continue
        for ((tIdx, tup) in tuples.withIndex()) {
            val v = tup[i]
            require(v.toLong() in handle.min..handle.max) {
                "table: tuple #$tIdx value $v at position $i is outside operand " +
                    "${handle.name}'s domain [${handle.min}..${handle.max}]"
            }
        }
    }
}
