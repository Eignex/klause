package com.eignex.klause.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Anything that can be coerced into a [BoolExpr] inside the constraint DSL. */
interface BoolTerm {
    /** Coerce this term into a [BoolExpr] node. */
    fun toExpr(): BoolExpr
}

/** A Boolean-valued node in the constraint AST. */
@Serializable
sealed interface BoolExpr : BoolTerm {
    override fun toExpr(): BoolExpr = this
}

/** Reference to a named Boolean variable, optionally [negated]. */
@Serializable
@SerialName("ref")
data class BoolRef(
    /** Name of the referenced Boolean variable. */
    val name: String,
    /** Whether the reference is logically negated. */
    val negated: Boolean = false,
) : BoolExpr

/** `name == label` for a nominal variable. */
@Serializable
@SerialName("nomeq")
data class NominalEq(
    /** Name of the nominal variable being tested. */
    val name: String,
    /** Label the variable is compared against. */
    val label: String,
) : BoolExpr

/** Logical negation of [child]. */
@Serializable
@SerialName("not")
data class Not(
    /** The negated sub-expression. */
    val child: BoolExpr,
) : BoolExpr

/** Conjunction of [children] (at least one). */
@Serializable
@SerialName("and")
data class And(
    /** Conjuncts; must be non-empty. */
    val children: List<BoolExpr>,
) : BoolExpr {
    init {
        require(children.isNotEmpty()) { "And must have at least one child" }
    }
}

/** Disjunction of [children] (at least one). */
@Serializable
@SerialName("or")
data class Or(
    /** Disjuncts; must be non-empty. */
    val children: List<BoolExpr>,
) : BoolExpr {
    init {
        require(children.isNotEmpty()) { "Or must have at least one child" }
    }
}

/** Material implication `left → right`. */
@Serializable
@SerialName("imp")
data class Implies(
    /** Antecedent. */
    val left: BoolExpr,
    /** Consequent. */
    val right: BoolExpr,
) : BoolExpr

/** Bi-implication `left ↔ right`. */
@Serializable
@SerialName("iff")
data class Iff(
    /** Left operand. */
    val left: BoolExpr,
    /** Right operand. */
    val right: BoolExpr,
) : BoolExpr

/** At most `k` of [children] are true. */
@Serializable
@SerialName("atmost")
data class AtMost(
    /** The Boolean expressions being counted. */
    val children: List<BoolExpr>,
    /** Inclusive upper bound on the number that may be true. */
    val k: Int,
) : BoolExpr

/** At least `k` of [children] are true. */
@Serializable
@SerialName("atleast")
data class AtLeast(
    /** The Boolean expressions being counted. */
    val children: List<BoolExpr>,
    /** Inclusive lower bound on the number that must be true. */
    val k: Int,
) : BoolExpr

/** Between [min] and [max] (inclusive) of [children] are true. */
@Serializable
@SerialName("card")
data class CardinalityExpr(
    /** The Boolean expressions being counted. */
    val children: List<BoolExpr>,
    /** Inclusive lower bound on the true count. */
    val min: Int,
    /** Inclusive upper bound on the true count. */
    val max: Int,
) : BoolExpr

/** `if cond then thenE else elseE`. */
@Serializable
@SerialName("intite")
data class IntIfThenElse(
    /** Boolean selector. */
    val cond: BoolExpr,
    /** Value when [cond] is true. */
    val thenE: IntExpr,
    /** Value when [cond] is false. */
    val elseE: IntExpr,
) : IntExpr

/** Integer comparison operators. */
@Serializable
enum class IntCmpOp {
    /** `≤`. */
    LE,

    /** `<`. */
    LT,

    /** `≥`. */
    GE,

    /** `>`. */
    GT,

    /** `=`. */
    EQ,

    /** `≠`. */
    NE,
}

/** `left ⟨op⟩ right` comparison. */
@Serializable
@SerialName("intcmp")
data class IntCompare(
    /** Left operand. */
    val left: IntExpr,
    /** The comparison operator. */
    val op: IntCmpOp,
    /** Right operand. */
    val right: IntExpr,
) : BoolExpr

/**
 * Schema-layer view of a real-valued linear comparison: `Σ coeffs[k] · varNames[k] ⟨op⟩ bound`.
 * Built by [com.eignex.klause.schema.FloatExpr] / [com.eignex.klause.schema.FloatHandle]
 * comparison operators. The compiler does two things with it:
 *
 * It buckets each float variable to an int variable in the factor system and emits a
 * scaled-integer [com.eignex.klause.solver.factor.arithmetic.Linear] factor — what every backend solves over.
 *
 * The AST node lives in [BoolExpr] so the constraint pipeline can carry it through
 * `And` / `Or` / `Implies` etc. before the compiler intercepts it at lowering time.
 */
@Serializable
@SerialName("floatlin")
data class FloatLinearConstraint(
    /** Real coefficients, parallel to [varNames]. */
    val coeffs: DoubleArray,
    /** Float variable names, parallel to [coeffs]. */
    val varNames: List<String>,
    /** Comparison relating the weighted sum to [bound]. */
    val op: IntCmpOp,
    /** Right-hand-side bound. */
    val bound: Double,
) : BoolExpr {
    init {
        require(coeffs.size == varNames.size) {
            "coeffs/varNames length mismatch: ${coeffs.size} vs ${varNames.size}"
        }
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FloatLinearConstraint) return false
        return coeffs.contentEquals(other.coeffs) &&
            varNames == other.varNames && op == other.op && bound == other.bound
    }
    override fun hashCode(): Int {
        var h = coeffs.contentHashCode()
        h = 31 * h + varNames.hashCode()
        h = 31 * h + op.hashCode()
        h = 31 * h + bound.hashCode()
        return h
    }
}

/** All of [terms] take pairwise-distinct values (at least two terms). */
@Serializable
@SerialName("alldiff")
data class AllDifferent(
    /** The terms required to be pairwise distinct. */
    val terms: List<IntExpr>,
) : BoolExpr {
    init {
        require(terms.size >= 2) { "AllDifferent needs at least two terms" }
    }
}

/**
 * `symmetric_all_different(xs)`: the assignment `i -> xs[i] - indexOffset` is an involution —
 * `xs[i] = j + indexOffset` iff `xs[j] = i + indexOffset`. A self-inverse permutation; each
 * term is also pairwise distinct. Terms must be bare integer handles.
 */
@Serializable
@SerialName("sym_alldiff")
data class SymmetricAllDifferent(
    /** The permutation terms. */
    val terms: List<IntExpr>,
    /** Value `xs[i] = i + indexOffset` denotes the self-paired position i. */
    val indexOffset: Int = 0,
) : BoolExpr {
    init {
        require(terms.size >= 2) { "SymmetricAllDifferent needs at least two terms" }
    }
}

/**
 * `inverse(f, g)`: the two index arrays are mutual inverses — `f[i] = j + gOffset` iff
 * `g[j] = i + fOffset`. Channels a permutation against its inverse.
 */
@Serializable
@SerialName("inverse")
data class InverseChannel(
    /** The forward array. */
    val f: List<IntExpr>,
    /** The inverse array. */
    val g: List<IntExpr>,
    /** Offset of values stored in [f] (the base index of [g]). */
    val fOffset: Int = 0,
    /** Offset of values stored in [g] (the base index of [f]). */
    val gOffset: Int = 0,
) : BoolExpr {
    init {
        require(f.size == g.size) { "Inverse needs f and g of equal length" }
    }
}

/**
 * MDD — sequence acceptance by a layered multi-valued decision diagram. Layer `i` of
 * the MDD restricts `seq[i]`. [transitions] is a row-major list of triples
 * `(srcState, value, dstState)` per layer; [layerStarts] is the prefix-sum index into
 * [transitions] (layer `i` uses `transitions[layerStarts[i] until layerStarts[i+1]]`).
 * [initial] is the start state; [accepting] is the set of accepting states at the final
 * layer.
 */
@Serializable
@SerialName("mdd")
data class MddExpr(
    /** The sequence of variables, one per layer. */
    val seq: List<IntExpr>,
    /** Number of states in each layer (length `seq.size + 1`). */
    val numStatesPerLayer: List<Int>,
    /** Prefix-sum index into [transitions] per layer. */
    val layerStarts: List<Int>,
    /** Flat `(srcState, value, dstState)` triples per layer. */
    val transitions: List<Int>,
    /** Start state. */
    val initial: Int,
    /** Accepting states at the final layer. */
    val accepting: List<Int>,
) : BoolExpr {
    init {
        require(seq.isNotEmpty()) { "mdd: seq must be non-empty" }
        require(layerStarts.size == seq.size + 1) { "mdd: layerStarts must have length seq.size+1" }
        require(transitions.size % 3 == 0) { "mdd: transitions length must be a multiple of 3" }
        require(numStatesPerLayer.size == seq.size + 1) {
            "mdd: numStatesPerLayer must have length seq.size+1"
        }
    }
}

/**
 * Cost-regular: regular-DFA acceptance with edge weights accumulating into [cost].
 * Compatible with klause's existing Regular factor on (seq, Q, S, transitions, q0, accepting)
 * plus a parallel weights array indexed by (state, symbol).
 */
@Serializable
@SerialName("cost_regular")
data class CostRegularExpr(
    /** The input symbol sequence. */
    val seq: List<IntExpr>,
    /** Number of DFA states. */
    val numStates: Int,
    /** Number of input symbols. */
    val numSymbols: Int,
    /** `Q × S` row-major transition table; 0 means no transition. */
    val transitions: List<Int>,
    /** `Q × S` row-major edge weights. */
    val weights: List<Int>,
    /** Initial state. */
    val initial: Int,
    /** Accepting states. */
    val accepting: List<Int>,
    /** Total-cost variable accumulating edge weights. */
    val cost: IntExpr,
    /** Integer representing symbol 0 (1 by default). */
    val symbolOffset: Int = 1,
) : BoolExpr {
    init {
        require(transitions.size == numStates * numSymbols) {
            "cost_regular: transitions must be Q×S"
        }
        require(weights.size == numStates * numSymbols) {
            "cost_regular: weights must be Q×S"
        }
    }
}

/** Cost-MDD: like [MddExpr] but transition rows are (src, val, dst, weight). */
@Serializable
@SerialName("cost_mdd")
data class CostMddExpr(
    /** The sequence of variables, one per layer. */
    val seq: List<IntExpr>,
    /** Number of states in each layer (length `seq.size + 1`). */
    val numStatesPerLayer: List<Int>,
    /** Prefix-sum index into [transitions] per layer. */
    val layerStarts: List<Int>,
    /** Flat `(src, val, dst, weight)` quadruples per layer. */
    val transitions: List<Int>,
    /** Start state. */
    val initial: Int,
    /** Accepting states at the final layer. */
    val accepting: List<Int>,
    /** Total-cost variable accumulating edge weights. */
    val cost: IntExpr,
) : BoolExpr {
    init {
        require(seq.isNotEmpty()) { "cost_mdd: seq must be non-empty" }
        require(layerStarts.size == seq.size + 1) { "cost_mdd: layerStarts must have length seq.size+1" }
        require(transitions.size % 4 == 0) { "cost_mdd: transitions length must be a multiple of 4" }
    }
}

/**
 * Hamiltonian-cycle constraint over a successor array. `succ[i]` is the index of node `i`'s
 * successor; the assignment must form a single cycle visiting every node. [valueOffset] is
 * the integer that represents node 0 — `0` for klause's native 0-indexed form, `1` for
 * FlatZinc-style 1-indexed inputs.
 */
@Serializable
@SerialName("circuit")
data class CircuitExpr(
    /** Successor variable per node; the assignment must form a single Hamiltonian cycle. */
    val succ: List<IntExpr>,
    /** Integer representing node 0 (0 native, 1 for FlatZinc). */
    val valueOffset: Int = 0,
) : BoolExpr {
    init {
        require(succ.size >= 2) { "Circuit needs at least two nodes" }
    }
}

/**
 * Subcircuit — like [CircuitExpr] but `succ[i] = i + valueOffset` marks node `i` as
 * excluded; the included nodes (non-self-loops) must form a single cycle.
 */
@Serializable
@SerialName("subcircuit")
data class SubcircuitExpr(
    /** Successor per node; `succ[i] = i + valueOffset` excludes node i, the rest form one cycle. */
    val succ: List<IntExpr>,
    /** Integer representing node 0 (0 native, 1 for FlatZinc). */
    val valueOffset: Int = 0,
) : BoolExpr {
    init {
        require(succ.isNotEmpty()) { "Subcircuit needs at least one node" }
    }
}

/**
 * Cumulative scheduling: at every integer time point the sum of resource use of tasks
 * running at that point stays under [capacity]. [starts] are integer-variable expressions;
 * [durations] and [resources] are constants (variable-duration / variable-resource
 * cumulative is not supported by the current klause factor).
 */
@Serializable
@SerialName("cumulative")
data class CumulativeExpr(
    /** Task start-time variables. */
    val starts: List<IntExpr>,
    /** Constant task durations, parallel to [starts]. */
    val durations: List<Int>,
    /** Constant per-task resource use, parallel to [starts]. */
    val resources: List<Int>,
    /** Resource capacity available at every time point. */
    val capacity: Int,
) : BoolExpr {
    init {
        require(starts.size == durations.size && starts.size == resources.size) {
            "CumulativeExpr: starts/durations/resources must have the same length"
        }
        require(capacity >= 0) { "CumulativeExpr capacity must be ≥ 0, got $capacity" }
        for (i in durations.indices) {
            require(durations[i] >= 0) { "CumulativeExpr durations[$i] must be ≥ 0" }
            require(resources[i] >= 0) { "CumulativeExpr resources[$i] must be ≥ 0" }
        }
    }
}

/**
 * Disjunctive (one-machine / unary-resource) constraint. Tasks may not overlap.
 * Special case of [CumulativeExpr] with all-1 resources and capacity = 1, but ships its own
 * stronger propagator (time-tabling + detectable precedences + edge-finding).
 */
@Serializable
@SerialName("disjunctive")
data class DisjunctiveExpr(
    /** Task start-time variables. */
    val starts: List<IntExpr>,
    /** Constant task durations, parallel to [starts]. */
    val durations: List<Int>,
) : BoolExpr {
    init {
        require(starts.size == durations.size) {
            "DisjunctiveExpr: starts and durations must have the same length"
        }
        for (i in durations.indices) {
            require(durations[i] >= 0) { "DisjunctiveExpr durations[$i] must be ≥ 0" }
        }
    }
}

/** `sort`: [ys] is the non-decreasing sorted permutation of [xs] (same multiset of values). */
@Serializable
@SerialName("sort_global")
data class SortExpr(
    /** Input variables. */
    val xs: List<IntExpr>,
    /** Sorted (ascending) permutation of [xs]. */
    val ys: List<IntExpr>,
) : BoolExpr {
    init {
        require(xs.size == ys.size) { "SortExpr: xs/ys size mismatch" }
        require(xs.isNotEmpty()) { "SortExpr: empty arrays" }
    }
}

/**
 * `increasing`: [xs] forms an ordered integer chain — non-decreasing (`≤`), or strictly increasing
 * (`<`) when [strict]. `decreasing` is the same constraint on the reversed sequence, so the DSL
 * builders post it by reversing [xs]; this node only represents the ascending orientation.
 */
@Serializable
@SerialName("increasing_global")
data class IncreasingExpr(
    /** Chain variables, in order. */
    val xs: List<IntExpr>,
    /** When true the chain is strictly increasing (`<`); otherwise non-decreasing (`≤`). */
    val strict: Boolean = false,
) : BoolExpr {
    init {
        require(xs.isNotEmpty()) { "IncreasingExpr: empty array" }
    }
}

/**
 * `diffn`: rectangles at `(xs[i], ys[i])` of size `widths[i] × heights[i]` do not overlap.
 * Widths and heights are constants.
 */
@Serializable
@SerialName("diffn_global")
data class DiffnExpr(
    /** X (left) coordinates of each rectangle. */
    val xs: List<IntExpr>,
    /** Y (bottom) coordinates of each rectangle. */
    val ys: List<IntExpr>,
    /** Constant widths, parallel to [xs]. */
    val widths: List<Int>,
    /** Constant heights, parallel to [xs]. */
    val heights: List<Int>,
) : BoolExpr {
    init {
        require(xs.size == ys.size) { "DiffnExpr: xs/ys size mismatch" }
        require(xs.size == widths.size) { "DiffnExpr: xs/widths size mismatch" }
        require(xs.size == heights.size) { "DiffnExpr: xs/heights size mismatch" }
    }
}

/**
 * `regular`: [seq] is accepted by the DFA with states `1..numStates`, symbols `1..alphabetSize`,
 * row-major [transitions] (0 = no transition), start state [q0] and [accepting] states.
 */
@Serializable
@SerialName("regular_global")
data class RegularExpr(
    /** Input symbol sequence. */
    val seq: List<IntExpr>,
    /** Number of DFA states. */
    val numStates: Int,
    /** Number of input symbols. */
    val alphabetSize: Int,
    /** `numStates × alphabetSize` row-major transition table; 0 means no transition. */
    val transitions: List<Int>,
    /** Initial state. */
    val q0: Int,
    /** Accepting states. */
    val accepting: List<Int>,
) : BoolExpr {
    init {
        require(seq.isNotEmpty()) { "RegularExpr: empty seq" }
        require(numStates >= 1) { "RegularExpr: numStates ≥ 1" }
        require(alphabetSize >= 1) { "RegularExpr: alphabetSize ≥ 1" }
        require(transitions.size == numStates * alphabetSize) {
            "RegularExpr: transitions must be Q*S = ${numStates * alphabetSize} entries"
        }
        require(q0 in 1..numStates) { "RegularExpr: q0 ($q0) out of [1, $numStates]" }
    }
}

/** Extensional (table) constraint: the tuple of [terms] must (or must not, if [negative]) appear in [tuples]. */
@Serializable
@SerialName("table")
data class TableConstraint(
    /** The variables forming each candidate tuple. */
    val terms: List<IntExpr>,
    /** Allowed (or, if [negative], forbidden) value tuples; each matches the arity of [terms]. */
    val tuples: List<List<Int>>,
    /** When true, [tuples] lists forbidden tuples rather than allowed ones. */
    val negative: Boolean = false,
) : BoolExpr {
    init {
        require(terms.isNotEmpty()) { "TableConstraint must have at least one term" }
        require(tuples.isNotEmpty()) { "TableConstraint must have at least one tuple" }
        require(tuples.all { it.size == terms.size }) {
            "TableConstraint: every tuple must match arity ${terms.size}"
        }
    }
}

/** Comparison operator for a pseudo-Boolean constraint. */
@Serializable
enum class PbOp {
    /** `≤`. */
    LE,

    /** `≥`. */
    GE,

    /** `=`. */
    EQ,
}

/** Odd-parity (exclusive-or) over [children]. */
@Serializable
@SerialName("xor")
data class XorExpr(
    /** The Boolean expressions whose parity must be odd. */
    val children: List<BoolExpr>,
) : BoolExpr {
    init {
        require(children.isNotEmpty()) { "XorExpr needs at least one child" }
    }
}

/** Pseudo-Boolean constraint `Σ weights[k]·lits[k] ⟨op⟩ bound`. */
@Serializable
@SerialName("pb")
data class PseudoBooleanExpr(
    /** Integer weights, parallel to [lits]. */
    val weights: List<Int>,
    /** Boolean literals contributing their weight when true. */
    val lits: List<BoolExpr>,
    /** Comparison relating the weighted sum to [bound]. */
    val op: PbOp,
    /** Right-hand-side bound. */
    val bound: Int,
) : BoolExpr {
    init {
        require(weights.size == lits.size) { "PseudoBooleanExpr: weights/lits length mismatch" }
        require(lits.isNotEmpty()) { "PseudoBooleanExpr: need at least one literal" }
    }
}

// -----------------------------------------------------------------------------------
//  Set variables
// -----------------------------------------------------------------------------------
// Set variables ship as a `(universe, indicator-bool array)` pair. The compiler allocates
// one Boolean variable per universe element; the schema-level [SetSpec] carries the
// universe so callers can later materialise unions / intersections / set literals over the
// same element domain. A [MultipleSpec] is a set over a nominal universe of labels — the
// nominal one-hot layer is materialised once and reused; both [SetSpec] and [MultipleSpec]
// share the underlying indicator-bool encoding so set-expressions and -constraints work
// uniformly on either kind.

/** Membership: `elem ∈ setExpr`. */
@Serializable
@SerialName("setin")
data class SetIn(
    /** The element tested for membership. */
    val elem: IntExpr,
    /** The set tested against. */
    val set: SetExpr,
) : BoolExpr

/** Nominal-label membership: `label ∈ setExpr`. Distinct AST node so the compiler can
 *  route through the nominal universe lookup rather than treating it as an int. */
@Serializable
@SerialName("setinnom")
data class SetNominalIn(
    /** The nominal label tested for membership. */
    val label: String,
    /** The set tested against. */
    val set: SetExpr,
) : BoolExpr

/** `left ⊆ right`. */
@Serializable
@SerialName("setsub")
data class SetSubsetOf(
    /** Candidate subset. */
    val left: SetExpr,
    /** Candidate superset. */
    val right: SetExpr,
) : BoolExpr

/** `left ∩ right = ∅`. */
@Serializable
@SerialName("setdis")
data class SetDisjoint(
    /** Left operand. */
    val left: SetExpr,
    /** Right operand. */
    val right: SetExpr,
) : BoolExpr

/** `left = right` over sets. */
@Serializable
@SerialName("seteq")
data class SetEq(
    /** Left operand. */
    val left: SetExpr,
    /** Right operand. */
    val right: SetExpr,
) : BoolExpr

/** Presence-gated [AllDifferent]: only present terms must be pairwise distinct. */
@Serializable
@SerialName("alldiffopt")
data class AllDifferentOpt(
    /** The terms required to be pairwise distinct when present. */
    val terms: List<IntExpr>,
    /** Presence literal per term, parallel to [terms]. */
    val presents: List<BoolExpr>,
) : BoolExpr {
    init {
        require(terms.size >= 2) { "AllDifferentOpt needs at least two terms" }
        require(presents.size == terms.size) {
            "AllDifferentOpt: presents must match terms arity"
        }
    }
}

/** Presence-gated [CumulativeExpr]: only present tasks consume resources. */
@Serializable
@SerialName("cumulativeopt")
data class CumulativeExprOpt(
    /** Task start-time variables. */
    val starts: List<IntExpr>,
    /** Constant task durations, parallel to [starts]. */
    val durations: List<Int>,
    /** Constant per-task resource use, parallel to [starts]. */
    val resources: List<Int>,
    /** Resource capacity available at every time point. */
    val capacity: Int,
    /** Presence literal per task, parallel to [starts]. */
    val presents: List<BoolExpr>,
) : BoolExpr {
    init {
        require(starts.size == durations.size && starts.size == resources.size && starts.size == presents.size) {
            "CumulativeExprOpt: starts/durations/resources/presents must have the same length"
        }
        require(capacity >= 0) { "CumulativeExprOpt capacity must be ≥ 0, got $capacity" }
        for (i in durations.indices) {
            require(durations[i] >= 0) { "CumulativeExprOpt durations[$i] must be ≥ 0" }
            require(resources[i] >= 0) { "CumulativeExprOpt resources[$i] must be ≥ 0" }
        }
    }
}

/** Presence-gated [DisjunctiveExpr]: only present tasks must not overlap. */
@Serializable
@SerialName("disjunctiveopt")
data class DisjunctiveExprOpt(
    /** Task start-time variables. */
    val starts: List<IntExpr>,
    /** Constant task durations, parallel to [starts]. */
    val durations: List<Int>,
    /** Presence literal per task, parallel to [starts]. */
    val presents: List<BoolExpr>,
) : BoolExpr {
    init {
        require(starts.size == durations.size && starts.size == presents.size) {
            "DisjunctiveExprOpt: starts/durations/presents must have the same length"
        }
        for (i in durations.indices) {
            require(durations[i] >= 0) { "DisjunctiveExprOpt durations[$i] must be ≥ 0" }
        }
    }
}

/** nvalue over a presence-gated subset of xs. */
@Serializable
@SerialName("nvalueopt")
data class NValueExprOpt(
    /** The number-of-distinct-values target. */
    val n: IntExpr,
    /** The variables whose distinct present values are counted. */
    val xs: List<IntExpr>,
    /** How [n] relates to the actual distinct-value count. */
    val mode: NValueMode = NValueMode.EQ,
    /** Presence literal per element, parallel to [xs]. */
    val presents: List<BoolExpr>,
) : BoolExpr {
    init {
        require(xs.isNotEmpty()) { "NValueExprOpt: xs must be non-empty" }
        require(presents.size == xs.size) { "NValueExprOpt: presents must match xs arity" }
    }
}

/** How an `nvalue` constraint's target relates to the actual distinct-value count. */
@Serializable
enum class NValueMode {
    /** Distinct count equals the target. */
    EQ,

    /** Distinct count is at least the target. */
    AT_LEAST,

    /** Distinct count is at most the target. */
    AT_MOST,
}

/** Global Cardinality Constraint over a presence-gated subset of xs. */
@Serializable
@SerialName("gccopt")
data class GccExprOpt(
    /** The variables whose value occurrences are counted. */
    val xs: List<IntExpr>,
    /** The values whose occurrence counts are bounded. */
    val cover: List<Int>,
    /** Per-cover-value low bound. */
    val low: List<Int>,
    /** Per-cover-value high bound. */
    val high: List<Int>,
    /** When true, present variables may only take values in [cover]. */
    val closed: Boolean,
    /** Presence literal per element, parallel to [xs]. */
    val presents: List<BoolExpr>,
) : BoolExpr {
    init {
        require(xs.isNotEmpty()) { "GccExprOpt: xs must be non-empty" }
        require(cover.isNotEmpty()) { "GccExprOpt: cover must be non-empty" }
        require(low.size == cover.size && high.size == cover.size) {
            "GccExprOpt: low/high must match cover arity"
        }
        require(presents.size == xs.size) { "GccExprOpt: presents must match xs arity" }
    }
}
