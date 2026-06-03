package com.eignex.klause.ast

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One row of a klause schema. Variables and named constraints are siblings under
 * [com.eignex.skema.SchemaDef]'s entries map; the map key is the entry's name, so
 * spec types don't carry a redundant `name` field.
 */
@Serializable
sealed interface SchemaEntry

/** A schema entry that declares a decision variable (as opposed to a named constraint). */
@Serializable
sealed interface VarSpec : SchemaEntry

/** A single Boolean decision variable. */
@Serializable
@SerialName("bool")
data object BoolSpec : VarSpec

/**
 * Presence marker for an optional variable: a Boolean that gates the value variable named
 * [valueName]. Compiles to an ordinary Boolean var, but its dedicated type lets the optional
 * machinery (notably absent-value pinning) recognise the (presence, value) pairing
 * *explicitly* — by type and the carried [valueName] — rather than by a fragile name
 * convention that could misfire on an unrelated bool that merely shares a suffix.
 */
@Serializable
@SerialName("presence")
data class PresenceSpec(val valueName: String) : VarSpec

/** A categorical variable taking exactly one of the named [labels]. */
@Serializable
@SerialName("nominal")
data class NominalSpec(
    /** The mutually-exclusive category labels this variable can take. */
    val labels: List<String>,
) : VarSpec

/** An integer variable ranging over the inclusive interval `[min, max]`. */
@Serializable
@SerialName("int")
data class IntSpec(
    /** Inclusive lower bound of the domain. */
    val min: Int,
    /** Inclusive upper bound of the domain. */
    val max: Int,
) : VarSpec

/**
 * Float variable bucketed to [buckets] uniformly-spaced values across `[min, max]`. The
 * solver represents it as an int domain `[0, buckets-1]`; the compiler stores a decoder so
 * solutions can be read back as Double.
 */
@Serializable
@SerialName("float")
data class FloatSpec(
    /** Inclusive lower bound of the real interval. */
    val min: Double,
    /** Inclusive upper bound of the real interval. */
    val max: Double,
    /** Number of uniformly-spaced values the interval is discretised into (≥ 2). */
    val buckets: Int,
) : VarSpec {
    init {
        require(buckets >= 2) { "FloatSpec needs at least 2 buckets" }
    }

    /** Real-value step between adjacent buckets: `(max - min) / (buckets - 1)`. The single
     *  source of truth for the bucket-to-real affine map — decode and the float objectives
     *  all route through it, and downstream consumers should too rather than re-deriving it. */
    val scale: Double get() = (max - min) / (buckets - 1)

    /** Decode bucket index [bucket] to its real value: `min + scale * bucket`. */
    fun realValue(bucket: Int): Double = min + scale * bucket
}

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

/** At most [k] of [children] are true. */
@Serializable
@SerialName("atmost")
data class AtMost(
    /** The Boolean expressions being counted. */
    val children: List<BoolExpr>,
    /** Inclusive upper bound on the number that may be true. */
    val k: Int,
) : BoolExpr

/** At least [k] of [children] are true. */
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

/** Anything that can be coerced into an [IntExpr] inside the constraint DSL. */
interface IntTerm {
    /** Coerce this term into an [IntExpr] node. */
    fun toIntExpr(): IntExpr
}

/** An integer-valued node in the constraint AST. */
@Serializable
sealed interface IntExpr : IntTerm {
    override fun toIntExpr(): IntExpr = this
}

/** Reference to a named integer variable. */
@Serializable
@SerialName("intref")
data class IntRef(
    /** Name of the referenced integer variable. */
    val name: String,
) : IntExpr

/** Integer constant. */
@Serializable
@SerialName("intlit")
data class IntLit(
    /** The literal value. */
    val value: Int,
) : IntExpr

/** `coeff * child`. */
@Serializable
@SerialName("intscale")
data class IntScale(
    /** Multiplier applied to [child]. */
    val coeff: Int,
    /** The scaled sub-expression. */
    val child: IntExpr,
) : IntExpr

/** Sum of children. */
@Serializable
@SerialName("intsum")
data class IntSum(
    /** Summands; must be non-empty. */
    val children: List<IntExpr>,
) : IntExpr {
    init {
        require(children.isNotEmpty()) { "IntSum must have at least one child" }
    }
}

/** Minimum of [children]. */
@Serializable
@SerialName("intmin")
data class IntMin(
    /** Operands; must be non-empty. */
    val children: List<IntExpr>,
) : IntExpr {
    init {
        require(children.isNotEmpty()) { "IntMin must have at least one child" }
    }
}

/** Maximum of [children]. */
@Serializable
@SerialName("intmax")
data class IntMax(
    /** Operands; must be non-empty. */
    val children: List<IntExpr>,
) : IntExpr {
    init {
        require(children.isNotEmpty()) { "IntMax must have at least one child" }
    }
}

/** Absolute value of [child]. */
@Serializable
@SerialName("intabs")
data class IntAbs(
    /** The operand. */
    val child: IntExpr,
) : IntExpr

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

/** `items[index]` — array element selection. */
@Serializable
@SerialName("intelem")
data class IntElement(
    /** Zero-based index expression. */
    val index: IntExpr,
    /** The indexable items; must be non-empty. */
    val items: List<IntExpr>,
) : IntExpr {
    init {
        require(items.isNotEmpty()) { "IntElement must have at least one item" }
    }
}

/** `left * right`. */
@Serializable
@SerialName("intmul")
data class IntMul(
    /** Left factor. */
    val left: IntExpr,
    /** Right factor. */
    val right: IntExpr,
) : IntExpr

/** Integer (truncating) division `num / den`. */
@Serializable
@SerialName("intdiv")
data class IntDiv(
    /** Dividend. */
    val num: IntExpr,
    /** Divisor. */
    val den: IntExpr,
) : IntExpr

/** Integer remainder `num % den`. */
@Serializable
@SerialName("intmod")
data class IntMod(
    /** Dividend. */
    val num: IntExpr,
    /** Divisor. */
    val den: IntExpr,
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
 *  1. Bucket each float variable to an int variable in the factor system and emit a
 *     scaled-integer [com.eignex.klause.solver.factor.Linear] factor — what every
 *     existing backend solves over.
 *  2. Record the original real-valued form on [com.eignex.klause.solver.FloatMetadata]
 *     so a native-real backend (Z3) can solve over reals directly.
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
 * Generalized alldifferent_except: every pair of distinct positions must take different
 * values, unless one of them takes a value in [except]. [except] is the set of "ignored"
 * sentinel values (e.g., {0} for the classic alldifferent_except_0).
 */
@Serializable
@SerialName("alldiff_except")
data class AllDifferentExceptExpr(
    /** The terms required to be pairwise distinct outside [except]. */
    val terms: List<IntExpr>,
    /** Sentinel values exempt from the distinctness requirement. */
    val except: List<Int>,
) : BoolExpr {
    init {
        require(terms.size >= 2) { "AllDifferentExcept needs at least two terms" }
    }
}

/**
 * `arg_sort(values, perm)` — `perm` is a permutation of `0..n-1` such that
 * `values[perm[0]] ≤ values[perm[1]] ≤ … ≤ values[perm[n-1]]`. Ties are broken by index.
 * [permOffset] is the integer that represents index 0 in `perm` (0 for klause native,
 * 1 for FlatZinc-style 1-indexed inputs).
 */
@Serializable
@SerialName("arg_sort")
data class ArgSortExpr(
    /** The values being ranked. */
    val values: List<IntExpr>,
    /** Output permutation of indices that sorts [values] ascending. */
    val perm: List<IntExpr>,
    /** Integer representing index 0 in [perm] (0 native, 1 for FlatZinc). */
    val permOffset: Int = 0,
) : BoolExpr {
    init {
        require(values.size == perm.size) { "arg_sort: values and perm must have the same length" }
        require(values.isNotEmpty()) { "arg_sort: values must be non-empty" }
    }
}

/**
 * `path(N, from, to, source, sink, edge_present, node_present)` — every selected node
 * lies on a simple directed path from [source] to [sink] using selected edges.
 * - `from[i]` / `to[i]` are tail/head of edge i (constants in [permOffset, permOffset+N-1]).
 * - `source` / `sink` are int variables (node indices, in `[permOffset, permOffset+N-1]`).
 * - `nodePresent[i]` is true iff node i is on the path.
 * - `edgePresent[j]` is true iff edge j is used.
 */
@Serializable
@SerialName("path_global")
data class PathExpr(
    /** Total number of nodes in the graph. */
    val numNodes: Int,
    /** Tail node of each edge, parallel to [to]. */
    val from: List<Int>,
    /** Head node of each edge, parallel to [from]. */
    val to: List<Int>,
    /** Source node of the path. */
    val source: IntExpr,
    /** Sink node of the path. */
    val sink: IntExpr,
    /** `nodePresent[i]` is true iff node i lies on the path. */
    val nodePresent: List<BoolExpr>,
    /** `edgePresent[j]` is true iff edge j is used. */
    val edgePresent: List<BoolExpr>,
    /** Integer representing node 0 (0 native, 1 for FlatZinc). */
    val nodeOffset: Int = 0,
) : BoolExpr {
    init {
        require(from.size == to.size) { "path: from/to length mismatch" }
        require(from.size == edgePresent.size) { "path: edgePresent length mismatch" }
        require(nodePresent.size == numNodes) { "path: nodePresent length mismatch" }
    }
}

/**
 * `tree(N, from, to, root, edge_present, node_present)` — selected nodes form a
 * directed in-tree rooted at [root]. Each selected non-root node has exactly one
 * incoming selected edge; the root has none. No cycles.
 */
@Serializable
@SerialName("tree_global")
data class TreeExpr(
    /** Total number of nodes in the graph. */
    val numNodes: Int,
    /** Tail node of each edge, parallel to [to]. */
    val from: List<Int>,
    /** Head node of each edge, parallel to [from]. */
    val to: List<Int>,
    /** Root node of the in-tree. */
    val root: IntExpr,
    /** `nodePresent[i]` is true iff node i is in the tree. */
    val nodePresent: List<BoolExpr>,
    /** `edgePresent[j]` is true iff edge j is used. */
    val edgePresent: List<BoolExpr>,
    /** Integer representing node 0 (0 native, 1 for FlatZinc). */
    val nodeOffset: Int = 0,
) : BoolExpr {
    init {
        require(from.size == to.size) { "tree: from/to length mismatch" }
        require(from.size == edgePresent.size) { "tree: edgePresent length mismatch" }
        require(nodePresent.size == numNodes) { "tree: nodePresent length mismatch" }
    }
}

/**
 * Network flow: for each node `n`, the sum of incoming arc flows minus the sum of outgoing
 * arc flows equals `balance[n]`. Arcs are described by parallel [arcFrom] / [arcTo] arrays
 * (constants) and [flow] variables (one per arc).
 *
 * `[balance]` is per-node supply/demand: positive = source, negative = sink, 0 = transit.
 */
@Serializable
@SerialName("network_flow")
data class NetworkFlowExpr(
    /** Total number of nodes. */
    val numNodes: Int,
    /** Tail node of each arc, parallel to [arcTo]. */
    val arcFrom: List<Int>,
    /** Head node of each arc, parallel to [arcFrom]. */
    val arcTo: List<Int>,
    /** Per-node supply (positive) / demand (negative) / transit (0). */
    val balance: List<Int>,
    /** Flow variable for each arc. */
    val flow: List<IntExpr>,
    /** Integer representing node 0 (0 native, 1 for FlatZinc). */
    val nodeOffset: Int = 0,
) : BoolExpr {
    init {
        require(arcFrom.size == arcTo.size && arcFrom.size == flow.size) {
            "network_flow: arcFrom/arcTo/flow length mismatch"
        }
        require(balance.size == numNodes) { "network_flow: balance length mismatch" }
    }
}

/**
 * Network flow with cost: like [NetworkFlowExpr] plus a total-cost variable [cost] that
 * equals `Σ weight[a] · flow[a]`.
 */
@Serializable
@SerialName("network_flow_cost")
data class NetworkFlowCostExpr(
    /** Total number of nodes. */
    val numNodes: Int,
    /** Tail node of each arc, parallel to [arcTo]. */
    val arcFrom: List<Int>,
    /** Head node of each arc, parallel to [arcFrom]. */
    val arcTo: List<Int>,
    /** Per-node supply (positive) / demand (negative) / transit (0). */
    val balance: List<Int>,
    /** Per-arc unit cost, parallel to [flow]. */
    val weight: List<Int>,
    /** Flow variable for each arc. */
    val flow: List<IntExpr>,
    /** Total-cost variable equal to `Σ weight[a]·flow[a]`. */
    val cost: IntExpr,
    /** Integer representing node 0 (0 native, 1 for FlatZinc). */
    val nodeOffset: Int = 0,
) : BoolExpr {
    init {
        require(arcFrom.size == arcTo.size && arcFrom.size == flow.size && arcFrom.size == weight.size) {
            "network_flow_cost: arcFrom/arcTo/flow/weight length mismatch"
        }
        require(balance.size == numNodes) { "network_flow_cost: balance length mismatch" }
    }
}

/**
 * geost — N-dimensional non-overlapping placement of axis-aligned boxes.
 * For each pair of objects (i, j) and at least one dimension `d`,
 * `origin[i][d] + length[i][d] ≤ origin[j][d]` or vice versa.
 *
 * [origin] is a flat row-major `[numObjects × numDims]` list of integer variables;
 * [length] is the matching constant size table.
 */
@Serializable
@SerialName("geost")
data class GeostExpr(
    /** Number of spatial dimensions. */
    val numDims: Int,
    /** Number of boxes to place. */
    val numObjects: Int,
    /** Flat row-major `[numObjects × numDims]` origin variables. */
    val origin: List<IntExpr>,
    /** Flat row-major `[numObjects × numDims]` constant box sizes. */
    val length: List<Int>,
) : BoolExpr {
    init {
        require(numDims >= 1) { "geost: numDims must be ≥ 1" }
        require(origin.size == numObjects * numDims) { "geost: origin shape mismatch" }
        require(length.size == numObjects * numDims) { "geost: length shape mismatch" }
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

/** Set variable over an integer universe. The universe is fixed at schema-construction
 *  time and need not be contiguous, though contiguous ranges are the common case. */
@Serializable
@SerialName("set")
data class SetSpec(
    /** The (fixed, not necessarily contiguous) integer universe the set draws from. */
    val universe: List<Int>,
) : VarSpec {
    init {
        require(universe.isNotEmpty()) { "SetSpec needs a non-empty universe" }
    }
}

/** Set variable over a nominal universe of [labels]. Internally lowers to an indicator
 *  bool per label, mirroring the encoding of [SetSpec] but typed against strings on the
 *  decoder side. */
@Serializable
@SerialName("multiple")
data class MultipleSpec(
    /** The nominal label universe the set draws from. */
    val labels: List<String>,
) : VarSpec {
    init {
        require(labels.isNotEmpty()) { "MultipleSpec needs at least one label" }
    }
}

/** Anything that can be coerced into a [SetExpr] inside the constraint DSL — the
 *  set-side analogue of [IntTerm] / [BoolTerm]. */
interface SetTerm {
    /** Coerce this term into a [SetExpr] node. */
    fun toSetExpr(): SetExpr
}

/** A set-valued node in the constraint AST. */
@Serializable
sealed interface SetExpr : SetTerm {
    override fun toSetExpr(): SetExpr = this
}

/** Reference to a named set variable. */
@Serializable
@SerialName("setref")
data class SetRef(
    /** Name of the referenced set variable. */
    val name: String,
) : SetExpr

/** Concrete set literal over an integer universe. */
@Serializable
@SerialName("setlit")
data class SetLiteral(
    /** The literal set's elements. */
    val elements: List<Int>,
) : SetExpr

/** Concrete set literal over a nominal universe. The compiler resolves [labels] against
 *  the operand's nominal universe at lowering time. */
@Serializable
@SerialName("setlitnom")
data class SetNominalLiteral(
    /** The literal set's nominal labels, resolved against the operand's universe. */
    val labels: List<String>,
) : SetExpr

/** Set union `left ∪ right`. */
@Serializable
@SerialName("setunion")
data class SetUnion(
    /** Left operand. */
    val left: SetExpr,
    /** Right operand. */
    val right: SetExpr,
) : SetExpr

/** Set intersection `left ∩ right`. */
@Serializable
@SerialName("setisect")
data class SetIntersect(
    /** Left operand. */
    val left: SetExpr,
    /** Right operand. */
    val right: SetExpr,
) : SetExpr

/** Set difference `left \ right`. */
@Serializable
@SerialName("setdiff")
data class SetDiff(
    /** Left operand. */
    val left: SetExpr,
    /** Right operand subtracted from [left]. */
    val right: SetExpr,
) : SetExpr

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

/** Cardinality `|setExpr|` — returns the count of universe elements indicated true. */
@Serializable
@SerialName("setcard")
data class SetCard(
    /** The set whose cardinality is taken. */
    val set: SetExpr,
) : IntExpr

// -----------------------------------------------------------------------------------
//  Optional-variable globals
// -----------------------------------------------------------------------------------
// Each *Opt node mirrors its non-opt sibling but carries a parallel [presents] list of
// Boolean expressions. The compiler reads each [BoolExpr] as a presence literal, threads
// it into the corresponding factor's `presents: IntArray`, and the factor handles the
// rest natively (see [com.eignex.klause.solver.factor.OptPresence]).
//
// AllDifferentOpt over zero or one present element is trivially true and emits no factor;
// the constructor still requires `terms.size >= 2` because the compiler uses the same
// pair-by-pair pigeonhole guard as the non-opt form for non-empty cases.

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

/** count_⟨op⟩(xs, v, n) over a presence-gated subset of xs. */
@Serializable
@SerialName("countopt")
data class CountExprOpt(
    /** The variables being counted over. */
    val xs: List<IntExpr>,
    /** The value being counted. */
    val v: Int,
    /** Comparison relating the count to [n]. */
    val op: CountOp,
    /** Target count expression. */
    val n: IntExpr,
    /** Presence literal per element, parallel to [xs]. */
    val presents: List<BoolExpr>,
) : BoolExpr {
    init {
        require(xs.isNotEmpty()) { "CountExprOpt: xs must be non-empty" }
        require(presents.size == xs.size) { "CountExprOpt: presents must match xs arity" }
    }
}

/** Comparison operator for a count constraint. */
@Serializable
enum class CountOp {
    /** `=`. */
    EQ,

    /** `≠`. */
    NE,

    /** `≤`. */
    LE,

    /** `<`. */
    LT,

    /** `≥`. */
    GE,

    /** `>`. */
    GT,
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

/** A named constraint schema entry wrapping a Boolean expression. */
@Serializable
@SerialName("constraint")
data class NamedConstraint(
    /** The constraint expression. */
    val expr: BoolExpr,
) : SchemaEntry

// -----------------------------------------------------------------------------------
//  Search annotations
// -----------------------------------------------------------------------------------
// MiniZinc-style search annotations promoted to a Schema entry, so a klause user can
// declare branching strategy alongside variables and constraints (same surface a
// `solve :: int_search(..., var_strategy, value_strategy)` annotation gives MiniZinc).
// The compiler reads the (at most one) [SearchAnnotation] entry and bundles it into
// `CompiledProblem.defaultBacktrackParams`, which `BacktrackSolver` can then pick up.

/** Variable selection strategy. The MiniZinc-named subset klause recognises today; richer
 *  klause-specific options (VSIDS, dom/wdeg, activity-based, conflict-ordering) require
 *  parameters and so are configured by passing the [com.eignex.klause.solver.backtrack.VariableHeuristic]
 *  object directly to `BacktrackParams.variableHeuristic` instead of via the schema DSL. */
@Serializable
enum class VarSearchStrategy {
    /** Engine default — `RandomVariable`. Picks an unassigned var uniformly at random. */
    Default,

    /** First unpinned bool, else first int with domain size > 1, in id order. */
    InputOrder,

    /** "First-fail": smallest current domain wins. The classical CSP default. */
    SmallestDomain,

    /** Largest current domain — biases the search to delay branching on tight vars. */
    LargestDomain,

    /** Uniform random across unassigned variables. */
    Random,
}

/** Value selection strategy. Mirrors MiniZinc's `indomain_*` family. */
@Serializable
enum class ValSearchStrategy {
    /** Engine default — `IndomainRandom`. */
    Default,

    /** Smallest value first. */
    Min,

    /** Largest value first. */
    Max,

    /** Middle value first (binary-split style). */
    Middle,

    /** Uniform random value selection. */
    Random,
}

/** One schema-level search annotation. At most one per schema; multiple calls to
 *  [com.eignex.klause.schema.VariableSchema]'s `search { ... }` overwrite the previous. */
@Serializable
@SerialName("search")
data class SearchAnnotation(
    /** Which variable to branch on next. */
    val variableStrategy: VarSearchStrategy = VarSearchStrategy.Default,
    /** Which value to try first for the chosen variable. */
    val valueStrategy: ValSearchStrategy = ValSearchStrategy.Default,
    /** Cache the last value committed per variable across backtracks / restarts and try
     *  it first on the next descent. Standard CDCL-style heuristic; pairs naturally with
     *  [lubyRestartBase]. */
    val phaseSaving: Boolean = false,
    /** Luby restart base — see [com.eignex.klause.solver.backtrack.BacktrackParams.lubyRestartBase].
     *  `null` disables restarts. */
    val lubyRestartBase: Long? = null,
    /** Hard cap on decisions explored. `Long.MAX_VALUE` = unbounded. */
    val maxDecisions: Long = Long.MAX_VALUE,
) : SchemaEntry
