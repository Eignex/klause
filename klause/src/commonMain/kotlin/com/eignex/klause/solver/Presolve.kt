package com.eignex.klause.solver

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.Xor

/**
 * Problem-level presolve transforms. Each takes a [Problem] and returns an equivalent one with
 * a smaller / tighter formulation. Pure (no solving); the caller decides when to apply them.
 */
object Presolve {

    /**
     * GCD coefficient strengthening (#319) for [Linear] and [PseudoBoolean] constraints. If the
     * coefficients of an integer linear (or pseudo-Boolean) constraint share a common divisor
     * `g > 1`, dividing through by `g` shrinks the coefficients and, because the left-hand side
     * is then a multiple of `g`, lets the bound be tightened by flooring/ceiling:
     *  - `Σ aⱼxⱼ ≤ b`  ⟺  `Σ (aⱼ/g)xⱼ ≤ ⌊b/g⌋`
     *  - `Σ aⱼxⱼ ≥ b`  ⟺  `Σ (aⱼ/g)xⱼ ≥ ⌈b/g⌉`
     *  - `Σ aⱼxⱼ = b`: divisible ⟹ `Σ (aⱼ/g)xⱼ = b/g`; otherwise left unchanged (the search
     *    catches the infeasibility — no unsound rewrite here).
     *  - `Σ aⱼxⱼ ≠ b`: divisible ⟹ divide; otherwise the constraint is always true and is dropped.
     *
     * Exact (feasible-set-preserving) and it tightens the LP relaxation the bound participates in.
     * Other factor types pass through untouched.
     */
    fun strengthenCoefficients(problem: Problem): Problem {
        val out = ArrayList<Factor>(problem.factors.size)
        var changed = false
        for (factor in problem.factors) {
            val rewritten = when (factor) {
                is Linear -> strengthenLinear(factor)
                is PseudoBoolean -> strengthenPb(factor)
                else -> factor
            }
            if (rewritten !== factor) changed = true
            if (rewritten != null) out.add(rewritten)
        }
        if (!changed) return problem
        return Problem(
            numBoolVars = problem.numBoolVars,
            numIntVars = problem.numIntVars,
            intDomains = problem.intDomains.copyOf(),
            factors = out,
            floatMetadata = problem.floatMetadata,
            probeFailedLiterals = problem.probeFailedLiterals,
            probeIntBounds = problem.probeIntBounds,
            probeIntHoles = problem.probeIntHoles,
            probeBudgetPerVar = problem.probeBudgetPerVar,
            probeTotalBudget = problem.probeTotalBudget,
            probeSeed = problem.probeSeed,
        )
    }

    /**
     * Affine variable elimination (#318), contained slice. Eliminates an integer variable `x`
     * defined by a two-term equality `c_x·x + c_y·y = b` with `|c_x| = 1` **when `x` occurs in no
     * other factor** — then `x` is functionally determined (`x = c_x·b − c_x·c_y·y`) and need not
     * be searched. The equality is dropped and replaced by bounds on `y` that keep `x` inside its
     * declared domain; `x` stays in the variable space (now unconstrained) and is rebuilt from the
     * solution via [AffineElimination.reconstruct].
     *
     * The fully general substitution (rewriting `x` out of arbitrary factors) needs a `Factor`
     * variable-remap seam that does not yet exist; this slice deliberately only touches variables
     * that no other factor references, so no factor is rewritten. Feasible-set-preserving over the
     * remaining variables.
     */
    fun eliminateAffineSingletons(problem: Problem): AffineElimination {
        if (problem.numIntVars == 0) return AffineElimination(problem, emptyList())
        val occ = IntArray(problem.numIntVars)
        for (factor in problem.factors) for (v in factor.intVars) occ[v]++
        val eliminated = BooleanArray(problem.numIntVars)
        val subs = ArrayList<AffineSub>()
        val kept = ArrayList<Factor>(problem.factors.size)
        for (factor in problem.factors) {
            val xi = if (factor is Linear) eliminableIndex(factor, occ, eliminated) else -1
            if (factor !is Linear || xi < 0) {
                kept.add(factor)
                continue
            }
            val x = factor.vars[xi]
            val cx = factor.coeffs[xi]
            val y = factor.vars[1 - xi]
            val cy = factor.coeffs[1 - xi]
            eliminated[x] = true
            subs.add(AffineSub(x, cx, cy, y, factor.bound))
            kept.addAll(domainBoundsOnY(problem.intDomains[x], cx, cy, y, factor.bound))
        }
        if (subs.isEmpty()) return AffineElimination(problem, emptyList())
        return AffineElimination(rebuildProblem(problem, kept), subs)
    }

    /** Index (0 or 1) of an eliminable variable in a 2-term `EQ` Linear, or -1. Eliminable: unit
     *  coefficient, occurs only in this factor, and neither it nor its partner already eliminated. */
    private fun eliminableIndex(factor: Linear, occ: IntArray, eliminated: BooleanArray): Int {
        if (factor.op != LinearOp.EQ || factor.vars.size != 2) return -1
        for (xi in 0..1) {
            val x = factor.vars[xi]
            val y = factor.vars[1 - xi]
            val unit = factor.coeffs[xi] == 1 || factor.coeffs[xi] == -1
            if (unit && occ[x] == 1 && !eliminated[x] && !eliminated[y] && x != y) return xi
        }
        return -1
    }

    /** Bounds on `y` enforcing that `x = c_x·b − c_x·c_y·y` stays within `x`'s domain [domX]. */
    private fun domainBoundsOnY(domX: IntDomain, cx: Int, cy: Int, y: Int, b: Int): List<Factor> {
        val coeff = -cx * cy // coefficient of y in the expression for x
        return listOf(
            Linear(intArrayOf(coeff), intArrayOf(y), LinearOp.LE, domX.max - cx * b),
            Linear(intArrayOf(coeff), intArrayOf(y), LinearOp.GE, domX.min - cx * b),
        )
    }

    private fun rebuildProblem(problem: Problem, factors: List<Factor>): Problem = Problem(
        numBoolVars = problem.numBoolVars,
        numIntVars = problem.numIntVars,
        intDomains = problem.intDomains.copyOf(),
        factors = factors,
        floatMetadata = problem.floatMetadata,
        probeFailedLiterals = problem.probeFailedLiterals,
        probeIntBounds = problem.probeIntBounds,
        probeIntHoles = problem.probeIntHoles,
        probeBudgetPerVar = problem.probeBudgetPerVar,
        probeTotalBudget = problem.probeTotalBudget,
        probeSeed = problem.probeSeed,
    )

    /** Relation common to [LinearOp] and [PbOp] so the bound rewrite is written once. */
    private enum class Rel { LE, GE, EQ, NE }

    private sealed interface Reduced {
        /** Divide through; the rewritten bound. */
        data class Bound(val bound: Int) : Reduced

        /** Constraint is always satisfied — drop it. */
        object Drop : Reduced

        /** Leave the constraint as-is (non-divisible equality). */
        object Unchanged : Reduced
    }

    private fun reduceBound(rel: Rel, bound: Int, g: Int): Reduced = when (rel) {
        Rel.LE -> Reduced.Bound(bound.floorDiv(g))
        Rel.GE -> Reduced.Bound(-((-bound).floorDiv(g)))
        Rel.EQ -> if (bound.mod(g) == 0) Reduced.Bound(bound / g) else Reduced.Unchanged
        Rel.NE -> if (bound.mod(g) == 0) Reduced.Bound(bound / g) else Reduced.Drop
    }

    private fun strengthenLinear(factor: Linear): Factor? {
        val g = gcdOf(factor.coeffs)
        if (g <= 1) return factor
        return when (val reduced = reduceBound(toRel(factor.op), factor.bound, g)) {
            is Reduced.Bound -> Linear(divAll(factor.coeffs, g), factor.vars.copyOf(), factor.op, reduced.bound)
            Reduced.Drop -> null
            Reduced.Unchanged -> factor
        }
    }

    private fun strengthenPb(factor: PseudoBoolean): Factor? {
        val g = gcdOf(factor.weights)
        if (g <= 1) return factor
        return when (val reduced = reduceBound(toRel(factor.op), factor.bound, g)) {
            is Reduced.Bound -> PseudoBoolean(
                divAll(factor.weights, g),
                factor.literals.copyOf(),
                factor.op,
                reduced.bound,
            )

            Reduced.Drop -> null

            Reduced.Unchanged -> factor
        }
    }

    private fun toRel(op: LinearOp): Rel = when (op) {
        LinearOp.LE -> Rel.LE
        LinearOp.GE -> Rel.GE
        LinearOp.EQ -> Rel.EQ
        LinearOp.NE -> Rel.NE
    }

    private fun toRel(op: PbOp): Rel = when (op) {
        PbOp.LE -> Rel.LE
        PbOp.GE -> Rel.GE
        PbOp.EQ -> Rel.EQ
    }

    private fun gcdOf(xs: IntArray): Int {
        var g = 0
        for (x in xs) g = gcd(g, x)
        return g
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = if (a < 0) -a else a
        var y = if (b < 0) -b else b
        while (y != 0) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }

    private fun divAll(xs: IntArray, g: Int): IntArray = IntArray(xs.size) { xs[it] / g }

    /**
     * Symmetry breaking by detecting interchangeable variables (#317). Two variables are
     * *provably* interchangeable when they occur in exactly the same set of factors and play a
     * symmetric role in each — equal coefficient in a [Linear], both arguments of an
     * [AllDifferent], or same-polarity / same-weight literals in a [Clause] / [Cardinality] /
     * [Xor] / [PseudoBoolean]. These factor types are all order-insensitive (sum / set / parity),
     * so any permutation of such a group is a genuine automorphism, and ordering the group
     * (`x₀ ≤ x₁ ≤ …` for ints, `¬gⱼ ∨ gⱼ₊₁` for bools) keeps exactly one representative per orbit
     * — sound (never removes the last solution of an orbit).
     *
     * The "same factor set" requirement is what makes this sound: a variable appearing in a
     * factor its candidate-partner does not is *not* interchangeable. Variables touched by any
     * other factor type are conservatively excluded. Variables in [objectiveIntVars] /
     * [objectiveBoolVars] are excluded so an asymmetric objective can't be cut — keep those sets
     * empty for pure feasibility. (Per the issue policy this runs by default except in a pure
     * local-search portfolio.)
     *
     * Scope: this catches interchangeable *variables*; matrix-row/column and value symmetries and
     * full graph-automorphism detection remain follow-ups.
     */
    fun breakSymmetries(
        problem: Problem,
        objectiveIntVars: Set<Int> = emptySet(),
        objectiveBoolVars: Set<Int> = emptySet(),
    ): Problem {
        val intGroups = interchangeableIntGroups(problem, objectiveIntVars)
        val boolGroups = interchangeableBoolGroups(problem, objectiveBoolVars)
        if (intGroups.isEmpty() && boolGroups.isEmpty()) return problem
        val extra = ArrayList<Factor>()
        for (group in intGroups) {
            for (j in 0 until group.size - 1) {
                extra.add(Linear(intArrayOf(1, -1), intArrayOf(group[j], group[j + 1]), LinearOp.LE, 0))
            }
        }
        for (group in boolGroups) {
            for (j in 0 until group.size - 1) {
                extra.add(Clause(intArrayOf(Lit.make(group[j], false), Lit.make(group[j + 1], true))))
            }
        }
        return rebuildProblem(problem, problem.factors.toList() + extra)
    }

    /** Domain signature so only variables with the *same* domain (bounds and holes) can group. */
    private fun domainKey(d: IntDomain): String = "${d.min}:${d.max}:${d.holes?.joinToString("-").orEmpty()}"

    private fun interchangeableIntGroups(problem: Problem, objectiveVars: Set<Int>): List<IntArray> {
        val n = problem.numIntVars
        if (n == 0) return emptyList()
        val eligible = BooleanArray(n) { true }
        val roles = Array(n) { ArrayList<String>() }
        for (fi in problem.factors.indices) {
            when (val f = problem.factors[fi]) {
                is Linear -> for (i in f.vars.indices) roles[f.vars[i]].add("$fi:lin:${f.coeffs[i]}")
                is AllDifferent -> for (v in f.vars) roles[v].add("$fi:ad")
                else -> for (v in f.intVars) eligible[v] = false // unsupported factor type
            }
        }
        val groups = HashMap<String, MutableList<Int>>()
        for (v in 0 until n) {
            if (!eligible[v] || v in objectiveVars) continue
            roles[v].sort()
            groups.getOrPut("${domainKey(problem.intDomains[v])}|${roles[v].joinToString(",")}") { ArrayList() }.add(v)
        }
        return groups.values.filter { it.size >= 2 }.map { it.toIntArray() }
    }

    private fun interchangeableBoolGroups(problem: Problem, objectiveVars: Set<Int>): List<IntArray> {
        val n = problem.numBoolVars
        if (n == 0) return emptyList()
        val eligible = BooleanArray(n) { true }
        val roles = Array(n) { ArrayList<String>() }
        for (fi in problem.factors.indices) {
            when (val f = problem.factors[fi]) {
                is Clause -> for (l in f.literals) roles[Lit.variable(l)].add("$fi:cl:${Lit.isPositive(l)}")

                is Cardinality -> for (l in f.literals) roles[Lit.variable(l)].add("$fi:card:${Lit.isPositive(l)}")

                is Xor -> for (l in f.literals) roles[Lit.variable(l)].add("$fi:xor:${Lit.isPositive(l)}")

                is PseudoBoolean -> for (i in f.literals.indices) {
                    roles[Lit.variable(f.literals[i])].add("$fi:pb:${f.weights[i]}:${Lit.isPositive(f.literals[i])}")
                }

                else -> for (v in f.boolVars) eligible[v] = false // unsupported factor type
            }
        }
        val groups = HashMap<String, MutableList<Int>>()
        for (v in 0 until n) {
            if (!eligible[v] || v in objectiveVars) continue
            roles[v].sort()
            groups.getOrPut(roles[v].joinToString(",")) { ArrayList() }.add(v)
        }
        return groups.values.filter { it.size >= 2 }.map { it.toIntArray() }
    }
}

/** A single affine elimination `x = c_x·b − c_x·c_y·y` recorded by [Presolve.eliminateAffineSingletons]. */
internal data class AffineSub(val x: Int, val cx: Int, val cy: Int, val y: Int, val bound: Int)

/**
 * Reduced problem from [Presolve.eliminateAffineSingletons] plus the data to rebuild the
 * eliminated variables. Solve [problem], then pass the solution through [reconstruct] to recover
 * a solution of the original problem.
 */
class AffineElimination internal constructor(
    /** The problem with affine-defined variables eliminated. */
    val problem: Problem,
    private val subs: List<AffineSub>,
) {
    /** Recover the eliminated variables in a solution [sample] of [problem]. Each eliminated `x`
     *  reads its single defining `y`, which is never itself eliminated, so the order is irrelevant. */
    fun reconstruct(sample: Sample): Sample {
        if (subs.isEmpty()) return sample
        val ints = sample.ints.copyOf()
        for (s in subs) ints[s.x] = s.cx * s.bound - s.cx * s.cy * ints[s.y]
        return Sample(sample.bools, ints)
    }
}
