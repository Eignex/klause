package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Abs
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.arrays.emptyLongArray
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.LongArrayList
import com.eignex.util.collections.MutableIntObjectMap

/**
 * Topologically ordered functional definitions over integer, Boolean, set-membership and element outputs. Sweeping
 * evaluates defined variables from free ones so local search repairs only the remaining constraints. Integer results
 * are clamped to their domains and an out-of-range element access leaves its output untouched, so affected factors
 * stay violated and the sweep cannot fabricate feasibility.
 */
class DefinitionalSweep internal constructor(
    // Every node's inputs are free vars or earlier nodes.
    private val nodes: List<SweepNode>,
) {
    private val maintainedBools = IntHashSet().also { defined ->
        for (node in nodes) if (node.outIsBool) defined.add(node.out)
    }

    private val unitBooleanLinks: MutableIntObjectMap<IntArray> = run {
        val links = MutableIntObjectMap<IntArrayList>()
        for (node in nodes) {
            if (node !is SweepNode.BoolFold || node.ins.size != 1) continue
            val source = Lit.variable(node.ins[0])
            links.getOrPut(node.out) { IntArrayList() }.add(source)
            links.getOrPut(source) { IntArrayList() }.add(node.out)
        }
        MutableIntObjectMap<IntArray>(links.size).also { result ->
            links.forEach { variable, neighbors -> result.put(variable, neighbors.toIntArray()) }
        }
    }

    internal fun expandUnitBooleanScope(variables: IntArray, cancellation: ExecutionControl): IntArray? {
        if (variables.isEmpty() || unitBooleanLinks.isEmpty()) return variables
        val seen = IntHashSet()
        val result = IntArrayList()
        var visits = 0
        for (variable in variables) {
            if (visits++ % INITIALIZATION_BATCH == 0 && cancellation()) return null
            if (seen.add(variable)) result.add(variable)
        }
        var index = 0
        while (index < result.size) {
            if (visits++ % INITIALIZATION_BATCH == 0 && cancellation()) return null
            val neighbors = unitBooleanLinks[result[index++]] ?: continue
            for (neighbor in neighbors) {
                if (visits++ % INITIALIZATION_BATCH == 0 && cancellation()) return null
                if (seen.add(neighbor)) result.add(neighbor)
            }
        }
        return result.toIntArray()
    }

    /** Factory for sweeps inferred from the factor IR. */
    companion object {
        /** Sweep from source definition [hints]; competing outputs, cyclic cones and outputs admitting
         *  [SweepNode.NO_WRITE] stay searched. Null when nothing applies or [cancellation] stops it. */
        fun fromSource(
            problem: Problem,
            hints: List<DefinitionHint>,
            cancellation: ExecutionControl = ExecutionControl.Never,
        ): DefinitionalSweep? = sourceDefinitionSweep(problem, hints, cancellation)

        /** Infers definitions from [problem], adding hinted literal channels, element results and div/mod
         *  outputs. Competing definitions and cyclic cones stay searched. */
        fun infer(
            problem: Problem,
            definedHints: IntArray = IntArray(0),
            boolFolds: List<BoolFoldDefinition> = emptyList(),
        ): DefinitionalSweep? = infer(problem, definedHints, boolFolds, ExecutionControl.Never)

        internal fun infer(
            problem: Problem,
            definedHints: IntArray,
            boolFolds: List<BoolFoldDefinition>,
            cancellation: ExecutionControl,
        ): DefinitionalSweep? {
            if (cancellation()) return null
            val base = infer(problem.factors, problem.numIntVars, definedHints, boolFolds, cancellation)
            if (cancellation()) return null
            val nodes = ArrayList(base?.nodes.orEmpty())
            nodes += elementResultDefinitions(problem, definedHints, cancellation)
            if (cancellation()) return null
            nodes += divModDefinitions(problem, cancellation)
            if (cancellation()) return null
            nodes += wideDivModDefinitions(problem, cancellation)
            if (cancellation()) return null
            val ordered = literalDefinitions(problem, definedHints, nodes, cancellation)
            return if (cancellation() || ordered.isEmpty()) null else DefinitionalSweep(ordered)
        }

        internal fun withSource(
            problem: Problem,
            inferred: DefinitionalSweep?,
            hints: List<DefinitionHint>,
            cancellation: ExecutionControl,
        ): DefinitionalSweep? {
            if (cancellation()) return null
            if (hints.isEmpty()) return inferred
            val source = fromSource(problem, hints, cancellation)
            if (cancellation()) return null
            val integerOutputs = IntHashSet()
            val booleanOutputs = IntHashSet()
            for (hint in hints) {
                if (cancellation.definitionStopped()) return null
                (if (hint is DefinitionHint.Boolean) booleanOutputs else integerOutputs).add(hint.output)
            }
            val nodes = ArrayList<SweepNode>()
            for (node in inferred?.nodes.orEmpty()) {
                if (cancellation.definitionStopped()) return null
                if (node.out !in (if (node.outIsBool) booleanOutputs else integerOutputs)) nodes += node
            }
            nodes.addAll(source?.nodes.orEmpty())
            val ordered = orderedDefinitions(problem, nodes, cancellation)
            return if (cancellation() || ordered.isEmpty()) null else DefinitionalSweep(ordered)
        }

        /**
         * Infers definitions from the factor IR: integer [Abs], `Product` and integer-base [Power] outputs, plus
         * hinted linear equalities, extrema and affine indexes. An unhinted equality is never oriented, since it
         * could derive a decision var to an infeasible value. A var claimed twice or by its own cone stays searched;
         * null when nothing is definable.
         */
        fun infer(
            factors: Array<Factor>,
            numIntVars: Int,
            definedHints: IntArray = IntArray(0),
            boolFolds: List<BoolFoldDefinition> = emptyList(),
        ): DefinitionalSweep? = infer(factors, numIntVars, definedHints, boolFolds, ExecutionControl.Never)

        internal fun infer(
            factors: Array<Factor>,
            numIntVars: Int,
            definedHints: IntArray,
            boolFolds: List<BoolFoldDefinition>,
            cancellation: ExecutionControl,
        ): DefinitionalSweep? {
            if (cancellation()) return null
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
            for (factor in factors) {
                if (cancellation.definitionStopped()) return null
                if (factor is Abs) claim(factor.result, factor, -1)
            }
            val isProductResult = BooleanArray(numIntVars)
            for (f in factors) {
                if (cancellation.definitionStopped()) return null
                if (f is Product) {
                    claim(f.result, f, -1)
                    if (f.result in 0 until numIntVars) isProductResult[f.result] = true
                }
            }
            for (f in factors) {
                if (cancellation.definitionStopped()) return null
                if (f is Power && f.base is Power.Coordinate.Integer) {
                    val output = (f.result as Power.Coordinate.Integer).id.index
                    claim(output, f, -1)
                    if (output in 0 until numIntVars) isProductResult[output] = true
                }
            }
            if (definedHints.isNotEmpty()) {
                val hinted = BooleanArray(numIntVars)
                for (v in definedHints) {
                    if (cancellation.definitionStopped()) return null
                    if (v in 0 until numIntVars) hinted[v] = true
                }
                // A hinted sum is derived only atop a product cone: its sole non-product occurrence is its definer
                // and every summand is a product result. Otherwise deriving it removes feasibility-critical
                // decision vars from search and stalls repair.
                val nonProductOcc = IntArray(numIntVars)
                for (f in factors) {
                    if (cancellation.definitionStopped()) return null
                    for (v in f.intVars) {
                        if (cancellation.definitionStopped()) return null
                        if (v in 0 until numIntVars) {
                            if (f !is Product && f !is Power) nonProductOcc[v]++
                        }
                    }
                }
                for (f in factors) {
                    if (cancellation.definitionStopped()) return null
                    if (f !is Linear || f.op != LinearOp.EQ) continue
                    val row = f.integerConstants ?: continue
                    val j = f.vars.indices.firstOrNull {
                        if (cancellation.definitionStopped()) return null
                        hinted[f.vars[it]] && (row.coeff(it) == 1L || row.coeff(it) == -1L) &&
                            nonProductOcc[f.vars[it]] == 1 &&
                            f.vars.indices.all { k ->
                                if (cancellation.definitionStopped()) return null
                                k == it || isProductResult[f.vars[k]]
                            }
                    }
                    if (j != null) claim(f.vars[j], f, j)
                }
                for (definition in affineIndexDefinitions(factors, numIntVars, definedHints, cancellation)) {
                    claim(definition.variable, definition.factor, definition.outputIndex)
                }
            }
            if (cancellation()) return null
            val extrema = extremumDefinitions(factors, numIntVars, definedHints, cancellation)
            if (cancellation()) return null
            val priorDefinitions = def.copyOf()
            val extremumOutputs = BooleanArray(numIntVars)
            for (definition in extrema) {
                if (cancellation.definitionStopped()) return null
                extremumOutputs[definition.variable] = true
                if (priorDefinitions[definition.variable] !== definition.factor) {
                    claim(definition.variable, definition.factor, definition.outputIndex)
                }
            }
            if (cancellation()) return null
            val nodes = ArrayList<SweepNode>()
            val state = ByteArray(numIntVars) // 0 unseen, 1 on-stack, 2 done
            val cyclic = BooleanArray(numIntVars)
            fun visit(v: Int) {
                if (cancellation.definitionStopped()) return
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
                    if (cancellation()) return
                    visit(f.b)
                    if (cancellation()) return
                    node = FunctionalObjective.Times(
                        v,
                        FunctionalObjective.Operand.v(f.a),
                        FunctionalObjective.Operand.v(f.b),
                    )
                    inputs = intArrayOf(f.a, f.b)
                } else if (f is Abs) {
                    visit(f.operand)
                    if (cancellation()) return
                    node = FunctionalObjective.Abs(v, FunctionalObjective.Operand.v(f.operand))
                    inputs = intArrayOf(f.operand)
                } else if (f is Power) {
                    val input = (f.base as Power.Coordinate.Integer).id.index
                    visit(input)
                    if (cancellation()) return
                    node = FunctionalObjective.Power(v, FunctionalObjective.Operand.v(input), f.exponent)
                    inputs = intArrayOf(input)
                } else if (f is ArrayMinMax) {
                    for (input in f.xs) {
                        if (cancellation.definitionStopped()) return
                        visit(input)
                    }
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
                    val inc = LongArrayList(lin.vars.size - 1)
                    for (k in lin.vars.indices) {
                        if (cancellation.definitionStopped()) return
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
                if (cancellation()) return
                if (inputs.any { it in cyclic.indices && cyclic[it] }) cyclic[v] = true
                if (!cyclic[v]) nodes.add(SweepNode.IntDef(node, inputs, extremumOutputs[v]))
                state[v] = 2
            }
            for (v in 0 until numIntVars) {
                if (cancellation.definitionStopped()) return null
                visit(v)
            }
            // Bool AND/OR definitions supplied by the front-end (e.g. OPB Tseitin product indicators),
            // which the factor IR alone can't recover from the reified clauses. Appended after the int
            // cone; callers list them so an input fold precedes any fold that reads it.
            for (bf in boolFolds) {
                if (cancellation.definitionStopped()) return null
                for (i in bf.lits.indices) if (cancellation.definitionStopped()) return null
                nodes.add(SweepNode.BoolFold(bf.out, bf.lits, bf.isAnd))
            }
            return if (cancellation() || nodes.isEmpty()) null else DefinitionalSweep(nodes)
        }
    }

    /** Incremental view of [objective] over this sweep's definitions, or null when none applies. */
    fun functionalObjective(objective: LinearObjective): IncrementalObjective? =
        functionalObjective(objective, ExecutionControl.Never)

    internal fun functionalObjective(
        objective: LinearObjective,
        cancellation: ExecutionControl,
        captureBooleanCosts: Boolean = false,
    ): IncrementalObjective? {
        if (cancellation()) return null
        for (coefficient in objective.realCoefficientValues) {
            cancellation.charge(1)
            if (cancellation() || coefficient != 0.0) return null
        }
        val integers = IntArrayList()
        val booleans = IntArrayList()
        for (id in objective.intCoefficientValues.indices) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (objective.intCoefficientValues[id] != 0L) integers.add(id)
        }
        for (id in objective.boolWeightValues.indices) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (objective.boolWeightValues[id] != 0L) booleans.add(id)
        }
        return functionalObjective(
            integers.toIntArray(),
            LongArray(integers.size) { objective.intCoefficientValues[integers[it]] },
            objective.constant,
            true,
            booleans.toIntArray(),
            LongArray(booleans.size) { objective.boolWeightValues[booleans[it]] },
            cancellation,
            captureBooleanCosts,
        )
    }

    /**
     * Objective `Σ termCoeffs·terms + Σ boolTermCoeffs·[boolTerm holds] + constant` over this sweep's cones, so
     * local search descends it on the leaf vars. Null when no term is defined here or a reachable definition has
     * no exact evaluator.
     */
    fun functionalObjective(
        terms: IntArray,
        termCoeffs: LongArray,
        constant: Long,
        minimize: Boolean,
        boolTerms: IntArray = emptyIntArray(),
        boolTermCoeffs: LongArray = emptyLongArray(),
    ): IncrementalObjective? = functionalObjective(
        terms,
        termCoeffs,
        constant,
        minimize,
        boolTerms,
        boolTermCoeffs,
        ExecutionControl.Never,
        false,
    )

    private fun functionalObjective(
        terms: IntArray,
        termCoeffs: LongArray,
        constant: Long,
        minimize: Boolean,
        boolTerms: IntArray,
        boolTermCoeffs: LongArray,
        cancellation: ExecutionControl,
        captureBooleanCosts: Boolean,
    ): IncrementalObjective? {
        if (cancellation()) return null
        val intDefinitions = MutableIntObjectMap<SweepNode>()
        val boolDefinitions = MutableIntObjectMap<SweepNode>()
        for (node in nodes) {
            cancellation.charge(1)
            if (cancellation()) return null
            (if (node.outIsBool) boolDefinitions else intDefinitions).put(node.out, node)
        }
        var defined = false
        for (term in terms) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (intDefinitions.containsKey(term)) defined = true
        }
        for (term in boolTerms) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (boolDefinitions.containsKey(term)) defined = true
        }
        if (!defined) return null
        val reachableInts = IntHashSet()
        val reachableBools = IntHashSet()
        val leaves = LinkedHashSet<Int>()
        val boolLeaves = LinkedHashSet<Int>()
        val work = ArrayDeque<Pair<Int, Boolean>>()
        for (term in terms) {
            cancellation.charge(1)
            if (cancellation()) return null
            work.addLast(term to false)
        }
        for (term in boolTerms) {
            cancellation.charge(1)
            if (cancellation()) return null
            work.addLast(term to true)
        }
        while (work.isNotEmpty()) {
            cancellation.charge(1)
            if (cancellation()) return null
            val (id, isBool) = work.removeLast()
            val definition = (if (isBool) boolDefinitions else intDefinitions)[id]
            if (definition == null) continue
            if (!(if (isBool) reachableBools else reachableInts).add(id)) continue
            if (definition !is SweepNode.IntDef && definition !is SweepNode.BoolFold &&
                definition !is LiteralIntDefinition && definition !is UnaryPredicateDefinition &&
                definition !is CapturedBooleanDefinition && definition !is ElementResultDefinition
            ) {
                return null
            }
            for (input in definition.intInputs) {
                cancellation.charge(1)
                if (cancellation()) return null
                work.addLast(input to false)
            }
            for (input in definition.boolInputs) {
                cancellation.charge(1)
                if (cancellation()) return null
                work.addLast(input to true)
            }
        }
        val cone = ArrayList<FunctionalObjective.Definition>()
        for (node in nodes) {
            cancellation.charge(1)
            if (cancellation()) return null
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

                is CapturedBooleanDefinition -> { intOf, _ -> if (node.holds(intOf)) 1L else 0L }

                is ElementResultDefinition -> { intOf, _ -> node.compute(intOf) }

                else -> return null
            }
            cone.add(FunctionalObjective.Definition(node.out, node.outIsBool, node,
                keepsOutputOnNoWrite = node is ElementResultDefinition, compute = compute))
            for (input in node.intInputs) {
                cancellation.charge(1)
                if (cancellation()) return null
                if (input !in reachableInts) leaves.add(input)
            }
            for (input in node.boolInputs) {
                cancellation.charge(1)
                if (cancellation()) return null
                if (input !in reachableBools) boolLeaves.add(input)
            }
        }
        for (term in terms) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (term !in reachableInts) leaves.add(term)
        }
        for (term in boolTerms) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (term !in reachableBools) boolLeaves.add(term)
        }
        val result = FunctionalObjective(
            terms, termCoeffs, constant, minimize, emptyList(), leaves.toIntArray(),
            boolTerms, boolTermCoeffs, emptyList(), boolLeaves.toIntArray(), cone,
            booleanCostSweep = if (captureBooleanCosts) {
                completeBooleanCostSweep(terms, boolTerms, boolTermCoeffs, cancellation)
            } else null,
        )
        return if (cancellation()) null else result
    }

    private fun completeBooleanCostSweep(
        terms: IntArray,
        boolTerms: IntArray,
        coefficients: LongArray,
        cancellation: ExecutionControl,
    ): DefinitionalSweep? {
        if (terms.isNotEmpty() || nodes.size !in 1..250_000 || boolTerms.size > 250_000) return null
        val priced = IntHashSet()
        for (i in boolTerms.indices) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (boolTerms[i] !in 0 until 1_000_000) return null
            if (coefficients[i] != 0L) priced.add(boolTerms[i])
        }
        val outputs = IntHashSet()
        for (node in nodes) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (node !is SweepNode.BoolFold || node.out !in priced || !outputs.add(node.out)) return null
        }
        if (outputs.size != priced.size) return null
        var readers = 0
        for (node in nodes) {
            val fold = node as SweepNode.BoolFold
            for (literal in fold.ins) {
                cancellation.charge(1)
                if (cancellation()) return null
                val input = Lit.variable(literal)
                if (input !in 0 until 1_000_000 || input in outputs || ++readers > 2_000_000) return null
            }
        }
        return this
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
         *  times / plus / power / lin_eq), domain-clamped. */
        class IntDef internal constructor(
            internal val node: FunctionalObjective.Node,
            override val intInputs: IntArray,
            internal val inverseRepair: Boolean = false,
        ) : SweepNode {
            override val out: Int get() = node.out
            override val outIsBool: Boolean get() = false
            override val boolInputs: IntArray get() = emptyIntArray()
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
            override val boolInputs: IntArray get() = emptyIntArray()
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
            override val boolInputs: IntArray get() = emptyIntArray()
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
            override val intInputs: IntArray get() = emptyIntArray()
            override val boolInputs: IntArray = intArrayOf(b)
            override fun eval(assignment: Assignment, domains: Array<IntDomain>): Long =
                domains[out].clamp(if (assignment.boolValue(b)) 1L else 0L)
        }

        /** `out ↔ ⋀ ins` or `out ↔ ⋁ ins` over polarity-encoded [Lit] literals. */
        class BoolFold(
            /** The defined output var id. */
            override val out: Int,
            internal val ins: IntArray,
            internal val isAnd: Boolean,
        ) : SweepNode {
            override val outIsBool: Boolean get() = true
            override val intInputs: IntArray get() = emptyIntArray()
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
            override val boolInputs: IntArray get() = emptyIntArray()
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

    internal fun searchingChannels(
        values: Boolean,
        predicates: Boolean,
        cancellation: ExecutionControl,
    ): DefinitionalSweep? {
        val retained = ArrayList<SweepNode>(nodes.size)
        for (node in nodes) {
            if (cancellation.definitionStopped()) return null
            val valueChannel = node is LiteralIntDefinition || node is SweepNode.Bool2Int ||
                node is ElementResultDefinition || node is SweepNode.ElementDef
            val predicateChannel = node is CapturedBooleanDefinition || node is UnaryPredicateDefinition ||
                node is SweepNode.CmpReif || node is SweepNode.SetInReif
            if (!((values && valueChannel) || (predicates && predicateChannel))) retained += node
        }
        return if (cancellation() || retained.isEmpty()) null else DefinitionalSweep(retained)
    }

    /** Build the per-move invariant index over these nodes. */
    fun network(numIntVars: Int, numBoolVars: Int): InvariantNetwork = InvariantNetwork(nodes, numIntVars, numBoolVars)

    internal fun networkPreparation(numIntVars: Int, numBoolVars: Int): Sequence<InvariantNetwork?> =
        InvariantNetwork.preparation(nodes, numIntVars, numBoolVars)

    /**
     * Evaluates every defined var bottom-up from [assignment], then sets each reified-linear aux Boolean in
     * [factors] that no node maintains and [frozenBool] leaves free to the truth of its row. Factors reading a
     * defined var stay searched; callers must recompute incremental solver state afterwards.
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
