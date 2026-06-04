package com.eignex.klause.solver

import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedLinear

/**
 * The topologically-ordered functional definitions of a FlatZinc model — every
 * `:: defines_var(V)`-annotated constraint whose shape a [SweepNode] can mirror exactly, over
 * the *whole* model rather than just the objective cone. Definitions cover both value spaces:
 * int DAGs (abs / min / linear aux chains) via [SweepNode.IntDef], and the bool-shaped
 * definitions decompositions lean on — comparison reifications, `bool2int` channels, literal
 * set membership, bool conjunction/disjunction — plus variable-index element access.
 *
 * Why this exists: MiniZinc decompositions routinely make most of a model definitional
 * (fast-food/ff1: 229 of 230 constraints; prize-collecting: 596 of 668). A local search that
 * treats those as ordinary hard constraints must hand-repair the DAG one move at a time:
 * measured on both instances, CBLS needs ~2M flips to walk a random assignment to within one
 * violated factor of feasible — far beyond a competition wall-clock budget. Sweeping instead
 * *evaluates* every defined var bottom-up from the free (decision) variables, so a freshly
 * randomized assignment starts at the "only real constraints violated" frontier at the cost of
 * one pass. [network] extends the same nodes to **per-move** maintenance (issue #153): an
 * incremental one-way invariant index the engine consults after every applied move.
 *
 * Soundness: computed int values are clamped into the variable's domain, and an element access
 * with an out-of-range index leaves the output untouched. In both cases the affected
 * definitional factor simply remains violated and the search repairs it locally — the sweep
 * never fabricates feasibility, it only fast-forwards the part of the repair the definitions
 * determine.
 */
class DefinitionalSweep internal constructor(
    /** Defining nodes in topological order — every node's inputs are free vars or earlier nodes. */
    private val nodes: List<SweepNode>,
) {
    /** One evaluable definition `out = f(inputs)` with its read-set exposed for cone indexing. */
    sealed interface SweepNode {
        /** The defined output var id ([outIsBool] selects the value space). */
        val out: Int

        /** Whether [out] lives in the bool var space. */
        val outIsBool: Boolean

        /** Int vars read by [eval] (cone-index edges). */
        val intInputs: IntArray

        /** Bool vars read by [eval] (cone-index edges). */
        val boolInputs: IntArray

        /** Compute the defined value from current values — bools encoded 0/1, int results
         *  domain-clamped — or [NO_WRITE] when the definition cannot fire (element index out
         *  of range). */
        fun eval(assignment: Assignment, domains: Array<IntDomain>): Long

        /** Evaluate and write the defined var. */
        fun apply(assignment: Assignment, domains: Array<IntDomain>) {
            val v = eval(assignment, domains)
            if (v == NO_WRITE) return
            if (outIsBool) assignment.setBool(out, v != 0L) else assignment.setInt(out, v.toInt())
        }

        companion object {
            /** Sentinel: the definition cannot fire; leave the output untouched. */
            const val NO_WRITE: Long = Long.MIN_VALUE
        }

        /** An int definition from the functional-objective node algebra (abs / min / max /
         *  times / plus / lin_eq), domain-clamped. */
        class IntDef internal constructor(
            private val node: FunctionalObjective.Node,
            override val intInputs: IntArray,
        ) : SweepNode {
            override val out: Int get() = node.out
            override val outIsBool: Boolean get() = false
            override val boolInputs: IntArray get() = EmptyIntArray
            override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long =
                domains[node.out].clampLong(node.compute { id -> assignment.intValue(id).toLong() }).toLong()
        }

        /** `out ↔ (Σ coeffs·vars ⟨op⟩ rhs)` — every `int_*_reif` / `int_lin_*_reif` shape as a
         *  linear comparison over int vars; strict forms are normalized into LE/GE at build. */
        class CmpReif(
            /** The defined output var id. */
            override val out: Int,
            private val coeffs: LongArray,
            private val vars: IntArray,
            private val rhs: Long,
            private val op: LinearOp,
        ) : SweepNode {
            override val outIsBool: Boolean get() = true
            override val intInputs: IntArray get() = vars
            override val boolInputs: IntArray get() = EmptyIntArray
            override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long {
                var sum = 0L
                for (k in vars.indices) sum += coeffs[k] * assignment.intValue(vars[k])
                val holds = when (op) {
                    LinearOp.LE -> sum <= rhs
                    LinearOp.GE -> sum >= rhs
                    LinearOp.EQ -> sum == rhs
                    LinearOp.NE -> sum != rhs
                }
                return if (holds) 1L else 0L
            }
        }

        /** `out ↔ (x ∈ values)` for a constant literal set ([values] sorted ascending). */
        class SetInReif(
            /** The defined output var id. */
            override val out: Int,
            private val x: Int,
            private val values: IntArray,
        ) : SweepNode {
            override val outIsBool: Boolean get() = true
            override val intInputs: IntArray = intArrayOf(x)
            override val boolInputs: IntArray get() = EmptyIntArray
            override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long {
                val v = assignment.intValue(x)
                var lo = 0
                var hi = values.size - 1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val m = values[mid]
                    when {
                        m == v -> return 1L
                        m < v -> lo = mid + 1
                        else -> hi = mid - 1
                    }
                }
                return 0L
            }
        }

        /** `out = bool2int(b)`. */
        class Bool2Int(
            /** The defined output var id. */
            override val out: Int,
            private val b: Int,
        ) : SweepNode {
            override val outIsBool: Boolean get() = false
            override val intInputs: IntArray get() = EmptyIntArray
            override val boolInputs: IntArray = intArrayOf(b)
            override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long =
                domains[out].clampLong(if (assignment.boolValue(b)) 1L else 0L).toLong()
        }

        /** `out ↔ ⋀ ins` / `out ↔ ⋁ ins` over bool *literals* (polarity-encoded via [Lit], the
         *  FlatZinc compiler's bool-array element form — negated members are legal). */
        class BoolFold(
            /** The defined output var id. */
            override val out: Int,
            private val ins: IntArray,
            private val isAnd: Boolean,
        ) : SweepNode {
            override val outIsBool: Boolean get() = true
            override val intInputs: IntArray get() = EmptyIntArray
            override val boolInputs: IntArray = IntArray(ins.size) { Lit.variable(ins[it]) }
            override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long {
                var acc = isAnd
                for (lit in ins) {
                    val raw = assignment.boolValue(Lit.variable(lit))
                    val v = if (Lit.isPositive(lit)) raw else !raw
                    acc = if (isAnd) acc && v else acc || v
                    if (acc != isAnd) break
                }
                return if (acc) 1L else 0L
            }
        }

        /** `out = arr[idx − offset]` over a var array ([arrVars]) or constant table ([arrConsts]).
         *  An out-of-range index yields [NO_WRITE] (the factor stays violated). */
        class ElementDef(
            /** The defined output var id. */
            override val out: Int,
            private val idx: Int,
            private val arrVars: IntArray?,
            private val arrConsts: IntArray?,
            private val offset: Int,
        ) : SweepNode {
            override val outIsBool: Boolean get() = false
            override val intInputs: IntArray =
                if (arrVars != null) intArrayOf(idx) + arrVars else intArrayOf(idx)
            override val boolInputs: IntArray get() = EmptyIntArray
            override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long {
                val pos = assignment.intValue(idx) - offset
                val vars = arrVars
                val consts = arrConsts
                val v = when {
                    vars != null && pos in vars.indices -> assignment.intValue(vars[pos]).toLong()
                    consts != null && pos >= 0 && pos < consts.size -> consts[pos].toLong()
                    else -> return NO_WRITE
                }
                return domains[out].clampLong(v).toLong()
            }
        }
    }

    /** Number of swept definitions. */
    val size: Int get() = nodes.size

    /** Build the per-move invariant index over these nodes (issue #153). */
    fun network(numIntVars: Int, numBoolVars: Int): InvariantNetwork =
        InvariantNetwork(nodes, numIntVars, numBoolVars)

    /**
     * Evaluate every defined var bottom-up from the current values in [assignment] — then
     * evaluate the remaining **reification aux bools**: klause's own lowering introduces
     * `aux ↔ (Σ c·x op b)` Tseitin factors outside the annotation graph, and the aux is just as
     * definitional, so each aux in [factors] is set to the actual truth of its linear (skipping
     * [frozenBool] vars; consistent with any [SweepNode.CmpReif] result since both evaluate the
     * same final int values). Pure evaluation on both halves; factors *reading* a defined var
     * stay subject to ordinary search. Callers must recompute incremental solver state
     * afterwards.
     */
    fun sweep(
        assignment: Assignment,
        domains: Array<IntDomain>,
        factors: Array<out Factor> = emptyArray(),
        frozenBool: (Int) -> Boolean = { false },
    ) {
        for (n in nodes) n.apply(assignment, domains)
        for (f in factors) {
            if (f !is ReifiedLinear) continue
            if (frozenBool(f.auxBoolVar)) continue
            var sum = 0L
            for (k in f.vars.indices) sum += f.coeffs[k].toLong() * assignment.intValue(f.vars[k])
            val holds = when (f.op) {
                LinearOp.LE -> sum <= f.bound
                LinearOp.GE -> sum >= f.bound
                LinearOp.EQ -> sum == f.bound.toLong()
                LinearOp.NE -> sum != f.bound.toLong()
            }
            assignment.setBool(f.auxBoolVar, holds)
        }
    }
}

/**
 * Per-move one-way invariant index over a model's definitional nodes (issue #153): given the
 * vars a move touched, [affectedNodes] returns the cone of definitions to re-evaluate in
 * topological order. The local-search state drives the actual writes through its incremental
 * apply path so factor payloads and break/make counts stay maintained — this class is a passive
 * index. [isDefinedInt] / [isDefinedBool] let move generation exclude defined vars from the
 * search space entirely (they are determined, not searched).
 */
class InvariantNetwork internal constructor(
    nodes: List<DefinitionalSweep.SweepNode>,
    numIntVars: Int,
    numBoolVars: Int,
) {
    private val nodeArr: Array<DefinitionalSweep.SweepNode> = nodes.toTypedArray()
    private val definedInt = BooleanArray(numIntVars)
    private val definedBool = BooleanArray(numBoolVars)

    /** Node indexes reading each int var. */
    private val intReaders: Array<IntArray>

    /** Node indexes reading each bool var. */
    private val boolReaders: Array<IntArray>

    init {
        val intLists = Array(numIntVars) { ArrayList<Int>(0) }
        val boolLists = Array(numBoolVars) { ArrayList<Int>(0) }
        for (i in nodeArr.indices) {
            val n = nodeArr[i]
            if (n.outIsBool) definedBool[n.out] = true else definedInt[n.out] = true
            for (v in n.intInputs) intLists[v].add(i)
            for (v in n.boolInputs) boolLists[v].add(i)
        }
        intReaders = Array(numIntVars) { intLists[it].toIntArray() }
        boolReaders = Array(numBoolVars) { boolLists[it].toIntArray() }
    }

    /** Number of indexed definitions. */
    val size: Int get() = nodeArr.size

    /** True iff int var [v] is definitionally determined (should not be searched). */
    fun isDefinedInt(v: Int): Boolean = definedInt[v]

    /** True iff bool var [v] is definitionally determined (should not be searched). */
    fun isDefinedBool(v: Int): Boolean = definedBool[v]

    /** The node at [index] (indexes ascend in topological order). */
    fun node(index: Int): DefinitionalSweep.SweepNode = nodeArr[index]

    /**
     * Topologically-ordered node indexes affected by writes to the seed vars: the transitive
     * read-cone, computed by a marked worklist walk. Negative var ids are ignored so callers
     * can reuse fixed-size scratch arrays.
     */
    fun affectedNodes(seedInts: IntArray, seedBools: IntArray): IntArray {
        val marked = HashSet<Int>()
        val work = ArrayDeque<Int>()
        fun seedInt(v: Int) {
            if (v >= 0) for (i in intReaders[v]) if (marked.add(i)) work.add(i)
        }
        fun seedBool(v: Int) {
            if (v >= 0) for (i in boolReaders[v]) if (marked.add(i)) work.add(i)
        }
        for (v in seedInts) seedInt(v)
        for (v in seedBools) seedBool(v)
        while (work.isNotEmpty()) {
            val i = work.removeFirst()
            val n = nodeArr[i]
            if (n.outIsBool) seedBool(n.out) else seedInt(n.out)
        }
        return marked.toIntArray().also { it.sort() }
    }
}
