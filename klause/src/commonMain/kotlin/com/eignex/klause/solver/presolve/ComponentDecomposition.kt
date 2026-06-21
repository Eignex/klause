package com.eignex.klause.solver.presolve

import com.eignex.klause.solver.Problem
import com.eignex.klause.util.IntDisjointSet

/**
 * The partition of a [Problem] into independent subproblems by the variable↔factor incidence graph.
 * Two variables share a component iff they are linked by a chain of factors that pairwise co-occur:
 * a factor unions every variable it touches, and connectivity is the transitive closure of that.
 * Variables in no factor are each their own singleton component; factors touching no variable belong
 * to no component (they are recorded in [factorlessFactors]).
 *
 * The partition is purely structural — it labels the model, it never rewrites it — so it can never
 * change satisfiability. A solution to the whole problem is exactly an independent choice per
 * component, which is what downstream per-component solving exploits; this result only produces and
 * exposes the labelling.
 *
 * Boolean and integer variables share the labelling: [componentOfBool] / [componentOfInt] map a
 * variable to its component id, and [componentFactors] lists the factor ids in each component. The
 * objective is not consulted — it reads variables but does not connect them, so it cannot merge
 * components; a per-component solve simply optimises each component's slice of the objective.
 */
class ProblemComponents internal constructor(
    /** Component id of each Boolean variable, indexed by bool var id; values are `0 until [count]`. */
    val componentOfBool: IntArray,
    /** Component id of each integer variable, indexed by int var id; values are `0 until [count]`. */
    val componentOfInt: IntArray,
    /** Factor ids grouped by component, indexed by component id. A factorless factor appears in none. */
    val componentFactors: Array<IntArray>,
    /** Factor ids that touch no variable, so they belong to no component. */
    val factorlessFactors: IntArray,
) {
    /** Number of components. */
    val count: Int get() = componentFactors.size

    /** Whether the problem is a single connected block — no decomposition is available. A problem with
     *  one variable and no factors is also "trivially decomposed" rather than connected, so [count] `> 1`
     *  is the precise "splits into independent pieces" test. */
    val isConnected: Boolean get() = count == 1
}

/**
 * Connected-component decomposition over the variable↔factor incidence: union every variable a
 * factor touches, then read off the partition. See [ProblemComponents].
 */
internal object ComponentDecomposition {

    /**
     * Partition [problem] into its connected components. Boolean and integer variables are unified
     * into one disjoint-set index space — bools occupy `0 until numBoolVars`, ints the slots above —
     * so a reified factor mixing both kinds links them like any other co-occurrence. Building the
     * unions is `O(Σ |vars(factor)|)`: each factor stars its variables onto its first variable, which
     * generates the same partition as the full pairwise clique.
     */
    fun decompose(problem: Problem): ProblemComponents {
        val numBool = problem.numBoolVars
        val numInt = problem.numIntVars
        val sets = IntDisjointSet(numBool + numInt)
        for (f in problem.factors) {
            unionVars(sets, f.boolVars, f.intVars, numBool)
        }

        val componentOfBool = IntArray(numBool)
        val componentOfInt = IntArray(numInt)
        val componentByRoot = HashMap<Int, Int>()
        fun componentOf(node: Int): Int = componentByRoot.getOrPut(sets.find(node)) { componentByRoot.size }

        for (v in 0 until numBool) componentOfBool[v] = componentOf(v)
        for (v in 0 until numInt) componentOfInt[v] = componentOf(numBool + v)

        val factorsByComponent = Array(componentByRoot.size) { ArrayList<Int>() }
        val factorless = ArrayList<Int>()
        for (fid in problem.factors.indices) {
            val anchor = anchorNode(problem.factors[fid].boolVars, problem.factors[fid].intVars, numBool)
            if (anchor < 0) {
                factorless.add(fid)
            } else {
                factorsByComponent[componentOf(anchor)].add(fid)
            }
        }
        return ProblemComponents(
            componentOfBool,
            componentOfInt,
            Array(factorsByComponent.size) { factorsByComponent[it].toIntArray() },
            factorless.toIntArray(),
        )
    }

    /** Union every variable of a factor onto its first variable — a star that yields the same
     *  connected component as the full pairwise clique at linear cost. */
    private fun unionVars(sets: IntDisjointSet, boolVars: IntArray, intVars: IntArray, numBool: Int) {
        val anchor = anchorNode(boolVars, intVars, numBool)
        if (anchor < 0) return
        for (b in boolVars) sets.union(anchor, b)
        for (i in intVars) sets.union(anchor, numBool + i)
    }

    /** The disjoint-set node of a factor's first variable (a bool, else an int offset by [numBool]),
     *  or `-1` when the factor touches no variable. */
    private fun anchorNode(boolVars: IntArray, intVars: IntArray, numBool: Int): Int = when {
        boolVars.isNotEmpty() -> boolVars[0]
        intVars.isNotEmpty() -> numBool + intVars[0]
        else -> -1
    }
}
