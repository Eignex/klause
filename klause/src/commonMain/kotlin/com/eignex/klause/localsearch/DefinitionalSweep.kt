package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.klause.util.toSortedIntArray

/**
 * A local-search acceleration structure (consumed by [LocalSearchSolver]): the topologically-
 * ordered functional definitions of a model — every functionally-defined variable whose shape a
 * [SweepNode] can mirror exactly, over the *whole* model rather than just the objective cone. The
 * class is engine-side and format-agnostic (it depends only on the factor / objective IR); the
 * front-end supplies the definitions — FlatZinc, via `:: defines_var(V)` annotations, is the only
 * builder today. Definitions cover both value spaces:
 * int DAGs (abs / min / linear aux chains) via [SweepNode.IntDef], and the bool-shaped
 * definitions decompositions lean on — comparison reifications, `bool2int` channels, literal
 * set membership, bool conjunction/disjunction — plus variable-index element access.
 *
 * Why this exists: MiniZinc decompositions routinely make most of a model definitional. A local
 * search treating those as ordinary hard constraints must hand-repair the DAG one move at a time,
 * needing millions of flips to walk a random assignment toward feasibility. Sweeping instead
 * *evaluates* every defined var bottom-up from the free (decision) variables, so a freshly
 * randomized assignment starts at the "only real constraints violated" frontier at the cost of one
 * pass. [network] extends the same nodes to **per-move** maintenance: an incremental one-way
 * invariant index the engine consults after every applied move.
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
    /** Factory for sweeps inferred from the factor IR (as opposed to front-end annotations). */
    companion object {
        /** Infer a sweep from the factor IR, so local search derives functionally-defined vars from the
         *  decision vars instead of searching them. Two sound sources:
         *  - every `Product(a, b, out)` defines `out = a·b` (a product always determines its output);
         *  - a `Linear` equality is oriented to define var v ONLY when v is in [definedHints] — the
         *    front-end's `defines_var` info (e.g. a `(eq, v)` sum). Orienting a bare equality without that
         *    hint could pick a decision var as the output and derive it to an infeasible value, so it is
         *    never done unhinted.
         *  A var claimed by more than one definition, or transitively by itself, is left searched. Nodes
         *  come out in topological order; returns null when nothing is definable. */
        fun infer(
            factors: Array<Factor>,
            numIntVars: Int,
            definedHints: IntArray = IntArray(0),
            boolFolds: List<BoolFoldDefinition> = emptyList(),
        ): DefinitionalSweep? {
            val def = arrayOfNulls<Factor>(numIntVars)
            val defOut = IntArray(numIntVars) { -1 } // for a Linear definer, the output var's index
            val overDefined = BooleanArray(numIntVars)
            fun claim(v: Int, f: Factor, outIdx: Int) {
                if (v !in 0 until numIntVars) return
                if (def[v] != null || overDefined[v]) {
                    def[v] = null
                    overDefined[v] = true
                } else {
                    def[v] = f
                    defOut[v] = outIdx
                }
            }
            val isProductResult = BooleanArray(numIntVars)
            for (f in factors) {
                if (f is Product) {
                    claim(f.result, f, -1)
                    if (f.result in 0 until numIntVars) isProductResult[f.result] = true
                }
            }
            if (definedHints.isNotEmpty()) {
                val hinted = BooleanArray(numIntVars)
                for (v in definedHints) if (v in 0 until numIntVars) hinted[v] = true
                // Deriving a hinted sum var is always sound but only pays off when it sits atop a functional
                // cone that bottoms out at lightly-constrained decision vars — the objective-decomposition
                // shape (e.g. `c = Σ x_i·x_j`, then `c²` in the objective). Two guards keep it there:
                //  - its sole non-`Product` occurrence is its own definer (it feeds nothing but the objective's
                //    product terms), and
                //  - every summand is itself a `Product` result (the sum caps a product cone, not a layer of
                //    feasibility-critical decision variables whose exclusion from search stalls repair).
                val nonProductOcc = IntArray(numIntVars)
                for (f in factors) {
                    if (f !is Product) {
                        for (v in f.intVars) {
                            if (v in 0 until numIntVars) nonProductOcc[v]++
                        }
                    }
                }
                for (f in factors) {
                    if (f !is Linear || f.op != LinearOp.EQ) continue
                    val row = f.integerConstants ?: continue
                    val j = f.vars.indices.firstOrNull {
                        hinted[f.vars[it]] && (row.coeff(it) == 1L || row.coeff(it) == -1L) &&
                            nonProductOcc[f.vars[it]] == 1 &&
                            f.vars.indices.all { k -> k == it || isProductResult[f.vars[k]] }
                    }
                    if (j != null) claim(f.vars[j], f, j)
                }
            }
            val nodes = ArrayList<SweepNode>()
            val state = ByteArray(numIntVars) // 0 unseen, 1 on-stack, 2 done
            val cyclic = BooleanArray(numIntVars)
            fun visit(v: Int) {
                if (v < 0 || v >= numIntVars || state[v].toInt() == 2) return
                if (state[v].toInt() == 1) {
                    cyclic[v] = true
                    return
                }
                val f = def[v] ?: return
                state[v] = 1
                val node: FunctionalObjective.Node
                val inputs: IntArray
                if (f is Product) {
                    visit(f.a)
                    visit(f.b)
                    node = FunctionalObjective.Times(
                        v,
                        FunctionalObjective.Operand.v(f.a),
                        FunctionalObjective.Operand.v(f.b),
                    )
                    inputs = intArrayOf(f.a, f.b)
                } else {
                    val lin = f as Linear
                    val linRow = checkNotNull(lin.integerConstants) { "a claimed definition is an integer row" }
                    val j = defOut[v]
                    val ins = ArrayList<FunctionalObjective.Operand>(lin.vars.size - 1)
                    val inc = ArrayList<Long>(lin.vars.size - 1)
                    for (k in lin.vars.indices) {
                        if (k != j) {
                            visit(lin.vars[k])
                            ins.add(FunctionalObjective.Operand.v(lin.vars[k]))
                            inc.add(linRow.coeff(k))
                        }
                    }
                    node = FunctionalObjective.Lin(
                        v,
                        linRow.coeff(j),
                        inc.toLongArray(),
                        ins.toTypedArray(),
                        linRow.bound,
                    )
                    inputs = IntArray(ins.size) { lin.vars[if (it < j) it else it + 1] }
                }
                if (!cyclic[v]) nodes.add(SweepNode.IntDef(node, inputs))
                state[v] = 2
            }
            for (v in 0 until numIntVars) visit(v)
            // Bool AND/OR definitions supplied by the front-end (e.g. OPB Tseitin product indicators),
            // which the factor IR alone can't recover from the reified clauses. Appended after the int
            // cone; callers list them so an input fold precedes any fold that reads it.
            for (bf in boolFolds) nodes.add(SweepNode.BoolFold(bf.out, bf.lits, bf.isAnd))
            return if (nodes.isEmpty()) null else DefinitionalSweep(nodes)
        }
    }

    /**
     * Build a [FunctionalObjective] `Σ termCoeffs·terms + Σ boolTermCoeffs·[boolTerm holds] + constant`
     * (already "lower is better") over this sweep's int and bool cones, so local search descends the
     * objective on the decision (leaf) vars rather than the functionally-defined ones. Returns null
     * when no term is defined here (a bare linear objective — a
     * [com.eignex.klause.solver.objective.LinearObjective] already suffices).
     */
    fun functionalObjective(
        terms: IntArray,
        termCoeffs: LongArray,
        constant: Long,
        minimize: Boolean,
        boolTerms: IntArray = EmptyIntArray,
        boolTermCoeffs: LongArray = EmptyLongArray,
    ): IncrementalObjective? {
        val defByOut = MutableIntObjectMap<SweepNode.IntDef>()
        for (n in nodes) if (n is SweepNode.IntDef) defByOut.put(n.out, n)
        val boolDefByOut = MutableIntObjectMap<SweepNode.BoolFold>()
        for (n in nodes) if (n is SweepNode.BoolFold) boolDefByOut.put(n.out, n)
        val anyIntDef = terms.any { defByOut.containsKey(it) }
        val anyBoolDef = boolTerms.any { boolDefByOut.containsKey(it) }
        if (!anyIntDef && !anyBoolDef) return null

        val reachable = IntHashSet()
        fun mark(id: Int) {
            val d = defByOut[id] ?: return
            if (id in reachable) return
            reachable.add(id)
            for (inId in d.intInputs) mark(inId)
        }
        for (t in terms) mark(t)
        // `nodes` is topological, so filtering preserves the inputs-before-outputs order the cone eval needs.
        val coneNodes = ArrayList<FunctionalObjective.Node>(reachable.size)
        val leaves = LinkedHashSet<Int>()
        for (n in nodes) {
            if (n !is SweepNode.IntDef || n.out !in reachable) continue
            coneNodes.add(n.node)
            for (inId in n.intInputs) if (inId !in reachable) leaves.add(inId)
        }

        val boolReachable = IntHashSet()
        fun markBool(id: Int) {
            val d = boolDefByOut[id] ?: return
            if (id in boolReachable) return
            boolReachable.add(id)
            for (lit in d.ins) markBool(Lit.variable(lit))
        }
        for (t in boolTerms) markBool(t)
        val boolConeNodes = ArrayList<FunctionalObjective.BoolFold>(boolReachable.size)
        val boolLeaves = LinkedHashSet<Int>()
        for (n in nodes) {
            if (n !is SweepNode.BoolFold || n.out !in boolReachable) continue
            boolConeNodes.add(FunctionalObjective.BoolFold(n.out, n.ins, n.isAnd))
            for (lit in n.ins) {
                val v = Lit.variable(lit)
                if (v !in boolReachable) boolLeaves.add(v)
            }
        }

        return FunctionalObjective(
            terms, termCoeffs, constant, minimize, coneNodes, leaves.toIntArray(),
            boolTerms, boolTermCoeffs, boolConeNodes, boolLeaves.toIntArray(),
        )
    }

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
            if (outIsBool) assignment.setBool(out, v != 0L) else assignment.setInt(out, v)
        }

        /** Shared [SweepNode] constants. */
        companion object {
            /** Sentinel: the definition cannot fire; leave the output untouched. */
            const val NO_WRITE: Long = Long.MIN_VALUE
        }

        /** An int definition from the functional-objective node algebra (abs / min / max /
         *  times / plus / lin_eq), domain-clamped. */
        class IntDef internal constructor(
            internal val node: FunctionalObjective.Node,
            override val intInputs: IntArray,
        ) : SweepNode {
            override val out: Int get() = node.out
            override val outIsBool: Boolean get() = false
            override val boolInputs: IntArray get() = EmptyIntArray
            override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long =
                domains[node.out].clamp(node.compute { id -> assignment.intValue(id) })
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
            private val values: LongArray,
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
                domains[out].clamp(if (assignment.boolValue(b)) 1L else 0L)
        }

        /** `out ↔ ⋀ ins` / `out ↔ ⋁ ins` over bool *literals* (polarity-encoded via [Lit], the
         *  FlatZinc compiler's bool-array element form — negated members are legal). */
        class BoolFold(
            /** The defined output var id. */
            override val out: Int,
            internal val ins: IntArray,
            internal val isAnd: Boolean,
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
            private val arrConsts: LongArray?,
            private val offset: Int,
        ) : SweepNode {
            override val outIsBool: Boolean get() = false
            override val intInputs: IntArray =
                if (arrVars != null) intArrayOf(idx) + arrVars else intArrayOf(idx)
            override val boolInputs: IntArray get() = EmptyIntArray
            override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long {
                // The array position is a domain value; only an in-range one indexes the array, so
                // narrow to Int after the range guard (an out-of-Int-range value is out of range).
                val pos = assignment.intValue(idx) - offset
                val vars = arrVars
                val consts = arrConsts
                val v = when {
                    vars != null && pos >= 0 && pos < vars.size -> assignment.intValue(vars[pos.toInt()])
                    consts != null && pos >= 0 && pos < consts.size -> consts[pos.toInt()]
                    else -> return NO_WRITE
                }
                return domains[out].clamp(v)
            }
        }
    }

    /** Number of swept definitions. */
    val size: Int get() = nodes.size

    /** Build the per-move invariant index over these nodes. */
    fun network(numIntVars: Int, numBoolVars: Int): InvariantNetwork = InvariantNetwork(nodes, numIntVars, numBoolVars)

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
            val row = f.integerConstants ?: continue
            var sum = 0L
            for (k in f.vars.indices) sum += row.coeff(k) * assignment.intValue(f.vars[k])
            val holds = when (f.op) {
                LinearOp.LE -> sum <= row.bound
                LinearOp.GE -> sum >= row.bound
                LinearOp.EQ -> sum == row.bound
                LinearOp.NE -> sum != row.bound
            }
            assignment.setBool(f.auxBoolVar, holds)
        }
    }
}

/**
 * Per-move one-way invariant index over a model's definitional nodes: given the
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
        val intLists = Array(numIntVars) { IntArrayList(0) }
        val boolLists = Array(numBoolVars) { IntArrayList(0) }
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

    /** True iff int var `v` is definitionally determined (should not be searched). */
    fun isDefinedInt(v: Int): Boolean = definedInt[v]

    /** True iff bool var `v` is definitionally determined (should not be searched). */
    fun isDefinedBool(v: Int): Boolean = definedBool[v]

    /** The node at [index] (indexes ascend in topological order). */
    fun node(index: Int): DefinitionalSweep.SweepNode = nodeArr[index]

    /**
     * Topologically-ordered node indexes affected by writes to the seed vars: the transitive
     * read-cone, computed by a marked worklist walk. Negative var ids are ignored so callers
     * can reuse fixed-size scratch arrays.
     */
    fun affectedNodes(seedInts: IntArray, seedBools: IntArray): IntArray {
        val marked = IntHashSet()
        val work = IntArrayDeque()
        fun seedInt(v: Int) {
            if (v >= 0) for (i in intReaders[v]) if (marked.add(i)) work.addLast(i)
        }
        fun seedBool(v: Int) {
            if (v >= 0) for (i in boolReaders[v]) if (marked.add(i)) work.addLast(i)
        }
        for (v in seedInts) seedInt(v)
        for (v in seedBools) seedBool(v)
        while (work.isNotEmpty()) {
            val i = work.removeFirst()
            val n = nodeArr[i]
            if (n.outIsBool) seedBool(n.out) else seedInt(n.out)
        }
        return marked.toSortedIntArray()
    }
}
