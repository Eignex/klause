package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Assignment
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
 * Topologically ordered functional definitions consumed by [LocalSearchSolver]. The engine-side,
 * format-agnostic DAG covers the whole model: integer definitions through [SweepNode.IntDef],
 * Boolean reifications and channels, set membership, conjunction/disjunction, and variable-index
 * element access. Frontends supply definition hints or source-annotation nodes.
 *
 * Sweeping evaluates defined variables from free variables so local search need only repair the
 * remaining constraints. [network] maintains those definitions incrementally after each move.
 * Integer results are clamped to their domains; out-of-range element accesses leave outputs
 * untouched. Affected factors remain violated, so the sweep cannot fabricate feasibility.
 */
class DefinitionalSweep internal constructor(
    /** Defining nodes in topological order — every node's inputs are free vars or earlier nodes. */
    private val nodes: List<SweepNode>,
) {
    private val maintainedBools = IntHashSet().also { defined ->
        for (node in nodes) if (node.outIsBool) defined.add(node.out)
    }

    /** Factory for sweeps inferred from the factor IR (as opposed to front-end annotations). */
    companion object {
        /** Infer definitions using the source domains to recognize hinted binary literal channels.
         * Unique unit unary predicates used only by those channels are maintained with them.
         * Hinted native [com.eignex.klause.factor.table.Element] results follow their indexes and selected cells.
         * Competing definitions and cyclic cones remain searched. */
        fun infer(
            problem: Problem,
            definedHints: IntArray = IntArray(0),
            boolFolds: List<BoolFoldDefinition> = emptyList(),
        ): DefinitionalSweep? {
            val base = infer(problem.factors, problem.numIntVars, definedHints, boolFolds)
            val nodes = base?.nodes.orEmpty() + elementResultDefinitions(problem, definedHints)
            val ordered = literalDefinitions(problem, definedHints, nodes)
            return if (ordered.isEmpty()) null else DefinitionalSweep(ordered)
        }

        /** Infer a sweep from the factor IR, so local search derives functionally-defined vars from the
         *  decision vars instead of searching them. Supported sources:
         *  - every `Product(a, b, out)` defines `out = a·b` (a product always determines its output);
         *  - a `Linear` equality is oriented to define var v ONLY when v is in [definedHints] — the
         *    front-end's `defines_var` info (e.g. a `(eq, v)` sum). Orienting a bare equality without that
         *    hint could pick a decision var as the output and derive it to an infeasible value, so it is
         *    never done unhinted.
         *  - hinted min/max expression cones and their affine operands and aliases.
         *  - hinted affine indexes read by Elements or unit unary equality predicates.
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
                    for (v in f.intVars) {
                        if (v in 0 until numIntVars) {
                            if (f !is Product) nonProductOcc[v]++
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
                for (definition in affineIndexDefinitions(factors, numIntVars, definedHints)) {
                    claim(definition.variable, definition.factor, definition.outputIndex)
                }
            }
            val extrema = extremumDefinitions(factors, numIntVars, definedHints)
            val priorDefinitions = def.copyOf()
            val extremumOutputs = BooleanArray(numIntVars)
            for (definition in extrema) {
                extremumOutputs[definition.variable] = true
                if (priorDefinitions[definition.variable] !== definition.factor) {
                    claim(definition.variable, definition.factor, definition.outputIndex)
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
                } else if (f is ArrayMinMax) {
                    for (input in f.xs) visit(input)
                    node = FunctionalObjective.Extreme(
                        v,
                        Array(f.xs.size) { FunctionalObjective.Operand.v(f.xs[it]) },
                        f.max,
                    )
                    inputs = f.xs
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
                if (inputs.any { it in cyclic.indices && cyclic[it] }) cyclic[v] = true
                if (!cyclic[v]) nodes.add(SweepNode.IntDef(node, inputs, extremumOutputs[v]))
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
     * when no term is defined here or a reachable definition has no exact objective evaluator.
     */
    fun functionalObjective(
        terms: IntArray,
        termCoeffs: LongArray,
        constant: Long,
        minimize: Boolean,
        boolTerms: IntArray = EmptyIntArray,
        boolTermCoeffs: LongArray = EmptyLongArray,
    ): IncrementalObjective? {
        val intDefinitions = MutableIntObjectMap<SweepNode>()
        val boolDefinitions = MutableIntObjectMap<SweepNode>()
        for (node in nodes) {
            (if (node.outIsBool) boolDefinitions else intDefinitions).put(node.out, node)
        }
        if (terms.none { intDefinitions.containsKey(it) } && boolTerms.none { boolDefinitions.containsKey(it) }) {
            return null
        }
        val reachableInts = IntHashSet()
        val reachableBools = IntHashSet()
        val leaves = LinkedHashSet<Int>()
        val boolLeaves = LinkedHashSet<Int>()
        val work = ArrayDeque<Pair<Int, Boolean>>()
        for (term in terms) work.addLast(term to false)
        for (term in boolTerms) work.addLast(term to true)
        while (work.isNotEmpty()) {
            val (id, isBool) = work.removeLast()
            val definition = (if (isBool) boolDefinitions else intDefinitions)[id]
            if (definition == null) continue
            if (!(if (isBool) reachableBools else reachableInts).add(id)) continue
            if (definition !is SweepNode.IntDef && definition !is SweepNode.BoolFold &&
                definition !is LiteralIntDefinition && definition !is UnaryPredicateDefinition
            ) return null
            for (input in definition.intInputs) work.addLast(input to false)
            for (input in definition.boolInputs) work.addLast(input to true)
        }
        val cone = ArrayList<FunctionalObjective.Definition>()
        for (node in nodes) {
            if (node.out !in (if (node.outIsBool) reachableBools else reachableInts)) continue
            val compute: ((Int) -> Long, (Int) -> Boolean) -> Long = when (node) {
                is SweepNode.IntDef -> { intOf, _ -> node.node.compute(intOf) }
                is SweepNode.BoolFold -> {
                    val fold = FunctionalObjective.BoolFold(node.out, node.ins, node.isAnd)
                    val evaluator: ((Int) -> Long, (Int) -> Boolean) -> Long =
                        { _, boolOf -> if (fold.compute(boolOf)) 1L else 0L }
                    evaluator
                }
                is LiteralIntDefinition -> { _, boolOf ->
                    if (boolOf(Lit.variable(node.literal)) == Lit.isPositive(node.literal)) 1L else 0L
                }
                is UnaryPredicateDefinition -> { intOf, _ -> if (node.holds(intOf(node.input))) 1L else 0L }
                else -> return null
            }
            cone.add(FunctionalObjective.Definition(node.out, node.outIsBool, compute))
            for (input in node.intInputs) if (input !in reachableInts) leaves.add(input)
            for (input in node.boolInputs) if (input !in reachableBools) boolLeaves.add(input)
        }
        for (term in terms) if (term !in reachableInts) leaves.add(term)
        for (term in boolTerms) if (term !in reachableBools) boolLeaves.add(term)
        return FunctionalObjective(
            terms, termCoeffs, constant, minimize, emptyList(), leaves.toIntArray(),
            boolTerms, boolTermCoeffs, emptyList(), boolLeaves.toIntArray(), cone,
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
            internal val inverseRepair: Boolean = false,
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

    internal fun sweepPinned(
        assignment: Assignment,
        domains: Array<IntDomain>,
        factors: Array<out Factor>,
        assumptions: Assumptions,
    ) {
        pinnedSteps(assignment, domains, factors, assumptions).forEach { }
    }

    internal fun pinnedSteps(
        assignment: Assignment,
        domains: Array<IntDomain>,
        factors: Array<out Factor>,
        assumptions: Assumptions,
    ): Sequence<Unit> = sequence {
        val effective = if (assumptions.intKeys.isEmpty()) domains else domains.copyOf()
        yield(Unit)
        for (i in assumptions.intKeys.indices) {
            val value = assumptions.intValues[i]
            effective[assumptions.intKeys[i]] = IntDomain(value, value)
            if ((i + 1) % INITIALIZATION_BATCH == 0) yield(Unit)
        }
        yieldAll(sweepSteps(assignment, effective, factors) { assumptions.isFrozenBool(it) })
    }

    /** Number of swept definitions. */
    val size: Int get() = nodes.size

    /** Build the per-move invariant index over these nodes. */
    fun network(numIntVars: Int, numBoolVars: Int): InvariantNetwork = InvariantNetwork(nodes, numIntVars, numBoolVars)

    internal fun networkPreparation(numIntVars: Int, numBoolVars: Int): Sequence<InvariantNetwork?> =
        InvariantNetwork.preparation(nodes, numIntVars, numBoolVars)

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
        sweepSteps(assignment, domains, factors, frozenBool).forEach { }
    }

    private fun sweepSteps(
        assignment: Assignment,
        domains: Array<IntDomain>,
        factors: Array<out Factor>,
        frozenBool: (Int) -> Boolean,
    ): Sequence<Unit> = sequence {
        for (i in nodes.indices) {
            if (i % INITIALIZATION_BATCH == 0) yield(Unit)
            val n = nodes[i]
            if (n.outIsBool && frozenBool(n.out)) continue
            n.apply(assignment, domains)
        }
        for (i in factors.indices) {
            if (i % INITIALIZATION_BATCH == 0) yield(Unit)
            val f = factors[i]
            if (f !is ReifiedLinear) continue
            if (f.auxBoolVar in maintainedBools) continue
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
class InvariantNetwork private constructor(data: DefinitionIndex) {
    internal constructor(nodes: List<DefinitionalSweep.SweepNode>, numIntVars: Int, numBoolVars: Int) :
        this(indexPreparation(nodes, numIntVars, numBoolVars).filterNotNull().first())

    private val nodeArr: Array<DefinitionalSweep.SweepNode> = data.nodeArr
    private val definedInt: BooleanArray = data.definedInt
    private val definedBool: BooleanArray = data.definedBool
    private val intDefinitions: MutableIntObjectMap<FunctionalObjective.Node> = data.intDefinitions
    private val literalChannels: MutableIntObjectMap<LiteralIntDefinition> = data.literalChannels
    private val unaryPredicates: MutableIntObjectMap<UnaryPredicateDefinition> = data.unaryPredicates
    private val elementResults: MutableIntObjectMap<ElementResultDefinition> = data.elementResults
    private val extremumRepairs: BooleanArray = data.extremumRepairs
    private val intReaders: Array<IntArray> = data.intReaders
    private val boolReaders: Array<IntArray> = data.boolReaders

    internal companion object {
        fun preparation(
            nodes: List<DefinitionalSweep.SweepNode>,
            numIntVars: Int,
            numBoolVars: Int,
        ): Sequence<InvariantNetwork?> = indexPreparation(nodes, numIntVars, numBoolVars).map {
            it?.let { data -> InvariantNetwork(data) }
        }

        private fun indexPreparation(
            nodes: List<DefinitionalSweep.SweepNode>,
            numIntVars: Int,
            numBoolVars: Int,
        ): Sequence<DefinitionIndex?> = sequence {
            val nodeArr = nodes.toTypedArray()
            yield(null)
            val definedInt = BooleanArray(numIntVars)
            yield(null)
            val definedBool = BooleanArray(numBoolVars)
            yield(null)
            val extremumRepairs = BooleanArray(numIntVars)
            yield(null)
            val intDefinitions = MutableIntObjectMap<FunctionalObjective.Node>()
            val literalChannels = MutableIntObjectMap<LiteralIntDefinition>()
            val unaryPredicates = MutableIntObjectMap<UnaryPredicateDefinition>()
            val elementResults = MutableIntObjectMap<ElementResultDefinition>()
            for (i in nodeArr.indices) {
                if (i % INITIALIZATION_BATCH == 0) yield(null)
                val n = nodeArr[i]
                if (n.outIsBool) definedBool[n.out] = true else definedInt[n.out] = true
                val intDef = n as? DefinitionalSweep.SweepNode.IntDef
                if (intDef != null) {
                    intDefinitions.put(n.out, intDef.node)
                    extremumRepairs[n.out] = intDef.inverseRepair
                }
                if (n is LiteralIntDefinition) literalChannels.put(n.out, n)
                if (n is UnaryPredicateDefinition) unaryPredicates.put(n.out, n)
                if (n is ElementResultDefinition) elementResults.put(n.out, n)
            }
            var intReaders: Array<IntArray>? = null
            for (part in readerPreparation(nodeArr, numIntVars) { it.intInputs }) {
                if (part == null) yield(null) else intReaders = part
            }
            yield(null)
            var boolReaders: Array<IntArray>? = null
            for (part in readerPreparation(nodeArr, numBoolVars) { it.boolInputs }) {
                if (part == null) yield(null) else boolReaders = part
            }
            yield(DefinitionIndex(
                nodeArr, definedInt, definedBool, intDefinitions, literalChannels,
                unaryPredicates, elementResults, extremumRepairs,
                checkNotNull(intReaders), checkNotNull(boolReaders),
            ))
        }

        private fun readerPreparation(
            nodes: Array<DefinitionalSweep.SweepNode>,
            slots: Int,
            inputs: (DefinitionalSweep.SweepNode) -> IntArray,
        ): Sequence<Array<IntArray>?> = sequence {
            val counts = IntArray(slots)
            yield(null)
            var visits = 0
            for (node in nodes) {
                if (++visits % INITIALIZATION_BATCH == 0) yield(null)
                for (v in inputs(node)) {
                    counts[v]++
                    if (++visits % INITIALIZATION_BATCH == 0) yield(null)
                }
            }
            val result = arrayOfNulls<IntArray>(slots)
            yield(null)
            for (v in result.indices) {
                result[v] = if (counts[v] == 0) EmptyIntArray else IntArray(counts[v])
                counts[v] = 0
                if ((v + 1) % INITIALIZATION_BATCH == 0) yield(null)
            }
            for (i in nodes.indices) {
                if (++visits % INITIALIZATION_BATCH == 0) yield(null)
                for (v in inputs(nodes[i])) {
                    checkNotNull(result[v])[counts[v]++] = i
                    if (++visits % INITIALIZATION_BATCH == 0) yield(null)
                }
            }
            @Suppress("UNCHECKED_CAST")
            yield(result as Array<IntArray>)
        }
    }

    /** Number of indexed definitions. */
    val size: Int get() = nodeArr.size

    /** True iff int var `v` is definitionally determined (should not be searched). */
    fun isDefinedInt(v: Int): Boolean = definedInt[v]

    /** True iff bool var `v` is definitionally determined (should not be searched). */
    fun isDefinedBool(v: Int): Boolean = definedBool[v]

    internal fun intDefinition(v: Int): FunctionalObjective.Node? = intDefinitions[v]

    internal fun literalChannel(v: Int): LiteralIntDefinition? = literalChannels[v]

    internal fun equalityPredicate(v: Int): EqualityPredicateDefinition? =
        unaryPredicates[v] as? EqualityPredicateDefinition

    internal fun unaryPredicate(v: Int): UnaryPredicateDefinition? = unaryPredicates[v]

    internal fun elementResultDefinition(v: Int): ElementResultDefinition? = elementResults[v]

    internal fun hasExtremumRepair(v: Int): Boolean = extremumRepairs[v]

    internal fun readsInt(v: Int): Boolean = intReaders[v].isNotEmpty()

    internal fun readsBool(v: Int): Boolean = boolReaders[v].isNotEmpty()

    internal fun linearDefinition(v: Int): FunctionalObjective.Lin? = intDefinitions[v] as? FunctionalObjective.Lin

    /** The node at [index] (indexes ascend in topological order). */
    fun node(index: Int): DefinitionalSweep.SweepNode = nodeArr[index]

    /**
     * Topologically-ordered node indexes affected by writes to the seed vars: the transitive
     * read-cone, computed by a marked worklist walk. Negative var ids are ignored so callers
     * can reuse fixed-size scratch arrays.
     */
    fun affectedNodes(seedInts: IntArray, seedBools: IntArray): IntArray =
        findAffectedNodes(seedInts, seedBools, null)

    internal fun affectedNodes(seedInts: IntArray, seedBools: IntArray, assignment: Assignment): IntArray =
        findAffectedNodes(seedInts, seedBools, assignment)

    private fun findAffectedNodes(seedInts: IntArray, seedBools: IntArray, assignment: Assignment?): IntArray {
        val marked = IntHashSet()
        val work = IntArrayDeque()
        fun seedInt(v: Int) {
            if (v < 0) return
            for (i in intReaders[v]) {
                val node = nodeArr[i]
                if (assignment != null && node is ElementResultDefinition && !node.reads(v, assignment)) continue
                if (marked.add(i)) work.addLast(i)
            }
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

    internal fun affectedNodes(
        seedInts: IntArrayList,
        seedBools: IntArrayList,
        assignment: Assignment,
        traversal: DefinitionTraversal,
    ): IntArrayList = findAffectedNodes(
        seedInts.backingData, seedInts.size, seedBools.backingData, seedBools.size, assignment, traversal,
    )

    private fun findAffectedNodes(
        seedInts: IntArray,
        intCount: Int,
        seedBools: IntArray,
        boolCount: Int,
        assignment: Assignment?,
        traversal: DefinitionTraversal,
    ): IntArrayList {
        check(traversal.network === this) { "definition traversal belongs to another network" }
        traversal.clear()
        val work = traversal.nodes
        fun seedInt(v: Int) {
            if (v < 0) return
            for (i in intReaders[v]) {
                val node = nodeArr[i]
                if (assignment != null && node is ElementResultDefinition && !node.reads(v, assignment)) continue
                traversal.add(i)
            }
        }
        fun seedBool(v: Int) {
            if (v >= 0) for (i in boolReaders[v]) traversal.add(i)
        }
        for (p in 0 until intCount) seedInt(seedInts[p])
        for (p in 0 until boolCount) seedBool(seedBools[p])
        var position = 0
        while (position < work.size) {
            val n = nodeArr[work[position++]]
            if (n.outIsBool) seedBool(n.out) else seedInt(n.out)
        }
        work.backingData.sort(0, work.size)
        return work
    }

}

private class DefinitionIndex(
    val nodeArr: Array<DefinitionalSweep.SweepNode>,
    val definedInt: BooleanArray,
    val definedBool: BooleanArray,
    val intDefinitions: MutableIntObjectMap<FunctionalObjective.Node>,
    val literalChannels: MutableIntObjectMap<LiteralIntDefinition>,
    val unaryPredicates: MutableIntObjectMap<UnaryPredicateDefinition>,
    val elementResults: MutableIntObjectMap<ElementResultDefinition>,
    val extremumRepairs: BooleanArray,
    val intReaders: Array<IntArray>,
    val boolReaders: Array<IntArray>,
)
