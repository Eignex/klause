package com.eignex.klause.solver

import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver

/**
 * Connected-component decomposition (#321). Partitions the factor graph into groups of factors
 * that share no variables, solves each independent sub-problem on its own, and stitches the
 * per-component assignments back into one [Sample].
 *
 * Because the components are variable-disjoint, every variable is constrained by at most one
 * component, so the combined assignment satisfies every factor. Smaller independent searches
 * avoid the cross-component thrash a single monolithic search pays. Sound for **feasibility**
 * (CSP): each component must be satisfiable for the whole to be. (Optimisation would need the
 * objective to separate additively across components, so [DecomposingSolver] is feasibility
 * only — it is a [Solver], not an [Optimizer].)
 *
 * Each sub-problem keeps the full variable space ([Problem.numBoolVars] / [Problem.numIntVars]
 * and [Problem.intDomains]) and only its own factors — this needs no variable renumbering, so
 * it composes with any [Factor] without a remap hook. Variables absent from every factor keep a
 * default value (`false` / domain minimum).
 *
 * [solve] and [sample] decompose; [samples] and [enumerate] delegate to a single non-decomposed
 * [base] solver over the whole problem (a cartesian product over components is out of scope).
 */
class DecomposingSolver(
    override val problem: Problem,
    private val base: (Problem) -> Solver<BacktrackParams> = { BacktrackSolver(it) },
) : Solver<BacktrackParams> {

    private val componentFactors: List<List<Factor>> = partitionFactors(problem)
    private val whole = base(problem)

    override fun solve(params: BacktrackParams): SolveResult {
        if (componentFactors.size <= 1) return whole.solve(params)
        val bools = BooleanArray(problem.numBoolVars)
        val ints = IntArray(problem.numIntVars) { problem.intDomains[it].min }
        for (factors in componentFactors) {
            val sub = subProblem(factors)
            when (val r = base(sub).solve(params)) {
                is SolveResult.Sat -> copyOwnedVars(factors, r.assignment, bools, ints)
                is SolveResult.Unsat -> return r
                is SolveResult.Unknown -> return r
            }
        }
        return SolveResult.Sat(Sample(bools, ints))
    }

    override fun sample(params: BacktrackParams): SampleResult = when (val r = solve(params)) {
        is SolveResult.Sat -> SampleResult.Found(r.assignment)
        is SolveResult.Unsat -> SampleResult.Infeasible(r.core)
        is SolveResult.Unknown -> SampleResult.Unknown(r.reason)
    }

    override fun samples(params: BacktrackParams): Sequence<Sample> = whole.samples(params)

    override fun enumerate(params: BacktrackParams): Sequence<Sample> = whole.enumerate(params)

    private fun subProblem(factors: List<Factor>): Problem = Problem(
        numBoolVars = problem.numBoolVars,
        numIntVars = problem.numIntVars,
        intDomains = problem.intDomains.copyOf(),
        factors = factors,
    )

    /** Copy the variables a component constrains out of its [sample] into the combined arrays. */
    private fun copyOwnedVars(factors: List<Factor>, sample: Sample, bools: BooleanArray, ints: IntArray) {
        for (factor in factors) {
            for (v in factor.boolVars) bools[v] = sample.bools[v]
            for (v in factor.intVars) ints[v] = sample.ints[v]
        }
    }

    private companion object {
        /** Group factors into variable-disjoint connected components via union-find over the
         *  variables each factor touches. */
        fun partitionFactors(problem: Problem): List<List<Factor>> {
            val factors = problem.factors
            if (factors.isEmpty()) return emptyList()
            val parent = IntArray(factors.size) { it }

            fun find(x: Int): Int {
                var root = x
                while (parent[root] != root) root = parent[root]
                var cur = x
                while (parent[cur] != cur) {
                    val next = parent[cur]
                    parent[cur] = root
                    cur = next
                }
                return root
            }

            fun union(a: Int, b: Int) {
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[ra] = rb
            }

            val boolOwner = HashMap<Int, Int>()
            val intOwner = HashMap<Int, Int>()
            for (fi in factors.indices) {
                for (v in factors[fi].boolVars) boolOwner.put(v, fi)?.let { union(fi, it) }
                for (v in factors[fi].intVars) intOwner.put(v, fi)?.let { union(fi, it) }
            }

            val groups = HashMap<Int, MutableList<Factor>>()
            for (fi in factors.indices) groups.getOrPut(find(fi)) { ArrayList() }.add(factors[fi])
            return groups.values.toList()
        }
    }
}
