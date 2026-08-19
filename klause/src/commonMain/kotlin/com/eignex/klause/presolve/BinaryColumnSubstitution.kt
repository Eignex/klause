package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

/**
 * Substitute an integer column whose domain is exactly `{0, 1}` for a Boolean literal, so the rows over
 * such columns become [Clause] / [Cardinality] / [PseudoBoolean] instead of staying [Linear] over integer
 * columns.
 *
 * The gain is the lane, not the factor count: conflict analysis loads reasons only from the
 * pseudo-Boolean, cardinality and clause propagators
 * ([com.eignex.klause.propagation.PbConflictResolvent]), so as [Linear] rows these models get no
 * division-based learning, no at-most-one clique merging and no coefficient strengthening over literals.
 *
 * The column is substituted **outright** — a fresh Boolean variable carries its value and no channelling
 * factor is posted. Channelling instead (a reified `x = 1 ⟺ b` per column) would trade the [Linear] rows
 * for pseudo-Boolean rows *plus* a reified factor each, and reified single-variable indicators are
 * consumed by [ComparisonClauseFold], which strands them.
 *
 * A column is substituted only when *every* factor mentioning it is a row this can rewrite:
 *  - an integer-core [Linear] (no continuous term, no over-64-bit coefficient) whose operator is not `≠`
 *    — a `≠` row is not a pseudo-Boolean relation;
 *  - whose normalisation to positive weights fits [Long];
 *  - and **all** of whose columns are themselves substitutable: a row mixing a `{0, 1}` column with a
 *    genuine integer one still needs an integer term, so neither column can leave the integer lane. This
 *    is a fixpoint — disqualifying one column disqualifies its rows, and those rows' other columns with
 *    them.
 *
 * Every other factor kind reads its integer variables value-wise (a global, a reified row, a product), so
 * a column it mentions stays an integer column.
 *
 * A column the objective reads is never substituted: the objective names integer variable ids directly
 * and the engine optimises over the presolved problem, so substituting one would silently rewrite it.
 *
 * The substituted column is left mentioned by no factor and **pinned** to 0, rather than left free over
 * `{0, 1}`: search would otherwise branch over a column that constrains nothing, one extra decision per
 * substituted column. Pinning is why the transform stays a bijection on solutions — each solution of the
 * transformed problem has exactly one image, with the column's real value carried by its literal — so it
 * preserves the solution count and needs no solution-set-sensitivity gate. [Substitution.reconstruct]
 * writes the integer value back from the literal and drops the added Booleans, landing the sample in the
 * original variable space.
 */
internal object BinaryColumnSubstitution {

    /** The result of a firing substitution: the transformed [problem] (its Boolean namespace extended by
     *  one variable per substituted column), the [reconstruct] that lifts its solutions back to the input's
     *  variable space, and the number of [columns] substituted. */
    class Substitution(val problem: Problem, val reconstruct: (Sample) -> Sample, val columns: Int)

    /**
     * Substitute every eligible `{0, 1}` column of [problem] for a fresh Boolean literal, or return `null`
     * when no column qualifies (the common case, and always for a model with no integer columns).
     * [objectiveIntVars] are held back. [bakeConfig] is threaded into the rebuild so the root-bake probing
     * policy is re-derived over the rewritten factor set.
     */
    fun substitute(problem: Problem, objectiveIntVars: Set<Int>, bakeConfig: BakeConfig): Substitution? {
        val numInts = problem.numIntVars
        if (numInts == 0) return null
        val factors = problem.factors
        val substitutable = BooleanArray(numInts)
        var anyColumn = false
        for (v in 0 until numInts) {
            if (!problem.intDomains[v].isBinary() || v in objectiveIntVars) continue
            substitutable[v] = true
            anyColumn = true
        }
        if (!anyColumn) return null

        // The `≥`-form right-hand side of each row after positive-weight normalisation, computed once here
        // and reused by the rewrite; `null` marks a factor that cannot become a pseudo-Boolean relation.
        val normalizedBound = arrayOfNulls<Long>(factors.size)
        for (i in factors.indices) {
            val f = factors[i]
            if (f is Linear) normalizedBound[i] = normalizedBound(f)
        }
        val occurrence = intOccurrence(problem)
        disqualifyToFixpoint(problem, substitutable, normalizedBound, occurrence)

        // A column no factor mentions gains nothing from a literal (and would leave the Boolean free), so
        // only the columns some surviving row still reads are substituted.
        val boolOf = IntArray(numInts) { -1 }
        val columns = IntArrayList()
        var nextBool = problem.numBoolVars
        for (v in 0 until numInts) {
            if (!substitutable[v] || occurrence.count(v) == 0) continue
            boolOf[v] = nextBool++
            columns.add(v)
        }
        if (columns.isEmpty()) return null

        val out = ArrayList<Factor>(factors.size)
        for (i in factors.indices) {
            val f = factors[i]
            val bound = normalizedBound[i]
            if (bound == null || f !is Linear || !f.vars.all { boolOf[it] >= 0 }) {
                out.add(f)
                continue
            }
            out.addAll(lower(f, boolOf, bound))
        }
        val domains = Array(numInts) { problem.intDomains[it] }
        for (k in 0 until columns.size) domains[columns[k]] = IntDomain(0, 0)
        val rebuilt = PresolveShared.rebuildProblem(problem, out, domains, bakeConfig, numBoolVars = nextBool)
        val substituted = columns.toIntArray()
        val firstBool = problem.numBoolVars
        return Substitution(rebuilt, { sample -> restore(sample, substituted, firstBool) }, substituted.size)
    }

    /** Recover the substituted columns' integer values from their literals and drop the added Booleans, so
     *  the sample lands in the pre-substitution variable space. */
    private fun restore(sample: Sample, substituted: IntArray, firstBool: Int): Sample {
        val ints = sample.ints.copyOf()
        for (k in substituted.indices) {
            ints[substituted[k]] = if (sample.bools.getOrElse(firstBool + k) { false }) 1L else 0L
        }
        val bools = if (sample.bools.size > firstBool) sample.bools.copyOf(firstBool) else sample.bools
        return Sample(bools, ints, sample.reals)
    }

    /** Clear [substitutable] for every column a factor this cannot rewrite mentions, and — to a fixpoint —
     *  for the columns of the rows that lose a column with it. A row whose columns are not all substitutable
     *  is itself not rewritable, which is recorded by clearing its [normalizedBound] slot. */
    private fun disqualifyToFixpoint(
        problem: Problem,
        substitutable: BooleanArray,
        normalizedBound: Array<Long?>,
        occurrence: IntOccurrence,
    ) {
        val pending = IntArrayList()
        fun disqualify(v: Int) {
            if (!substitutable[v]) return
            substitutable[v] = false
            pending.add(v)
        }
        for (i in problem.factors.indices) {
            val f = problem.factors[i]
            if (normalizedBound[i] != null && f.intVars.all { substitutable[it] }) continue
            normalizedBound[i] = null
            for (v in f.intVars) disqualify(v)
        }
        while (!pending.isEmpty()) {
            val v = pending.last()
            pending.truncateTo(pending.size - 1)
            occurrence.forEach(v) { fid ->
                if (normalizedBound[fid] != null) {
                    normalizedBound[fid] = null
                    for (u in problem.factors[fid].intVars) disqualify(u)
                }
            }
        }
    }

    /**
     * The pseudo-Boolean form of row [f] over the literals [boolOf] assigns its columns, given its
     * pre-computed normalised [bound]. Emits the tightest lane: a [Clause] for an at-least-one, a
     * [Cardinality] for unit weights, else a [PseudoBoolean]. A vacuous row drops; an infeasible one is
     * emitted as a [PseudoBoolean] whose bound propagation refutes. A row whose every coefficient is zero
     * has no literals to carry it, so it is kept as it came — its columns are pinned to 0, which reproduces
     * exactly the constant relation it already was.
     */
    private fun lower(f: Linear, boolOf: IntArray, bound: Long): List<Factor> {
        val flip = f.op == LinearOp.LE // ≤ → ≥ negates both sides
        val weights = LongArrayList(f.vars.size)
        val literals = IntArrayList(f.vars.size)
        for (i in f.vars.indices) {
            val raw = f.coeff(i)
            val a = if (flip) -raw else raw
            if (a == 0L) continue
            weights.add(if (a < 0L) -a else a)
            literals.add(Lit.make(boolOf[f.vars[i]], positive = a > 0L))
        }
        if (literals.isEmpty()) return listOf(f)
        val lits = literals.toIntArray()
        val ws = weights.toLongArray()
        val n = lits.size
        val unit = ws.all { it == 1L }
        return when {
            f.op == LinearOp.EQ -> when {
                unit && bound in 0..n.toLong() -> listOf(Cardinality(lits, bound.toInt(), bound.toInt()))
                else -> listOf(PseudoBoolean(ws, lits, PbOp.EQ, bound))
            }

            // Positive weights, so a non-positive bound holds under every assignment.
            bound <= 0L -> emptyList()

            unit && bound == 1L -> listOf(Clause(lits))

            // `Σ l ≥ n − 1` is `Σ ¬l ≤ 1`, and it is the `max == 1` form the at-most-one clique detector
            // reads ([PresolveShared.amoCliques]) — the same constraint, in the shape its consumers see.
            unit && bound == n - 1L -> listOf(Cardinality(IntArray(n) { Lit.negate(lits[it]) }, 0, 1))

            unit && bound <= n.toLong() -> listOf(Cardinality(lits, bound.toInt(), n))

            else -> listOf(PseudoBoolean(ws, lits, PbOp.GE, bound))
        }
    }

    /**
     * The right-hand side of [f] rewritten as `Σ wᵢ·litᵢ ⟨≥ | =⟩ bound` with every `wᵢ > 0`, or `null` when
     * [f] is not a row this can rewrite at all. A `≤` row negates both sides to `≥`; a negative coefficient
     * `−w·x` becomes `w·¬x − w`, moving `w` onto the bound. Each step is checked, so a row whose
     * normalisation leaves the [Long] range is reported unrewritable rather than wrapped.
     */
    private fun normalizedBound(f: Linear): Long? {
        if (!f.isIntegerCore || f.op == LinearOp.NE || f.vars.isEmpty()) return null
        val flip = f.op == LinearOp.LE
        var bound = if (flip) negateOrNull(f.bound) ?: return null else f.bound
        for (i in f.vars.indices) {
            val raw = f.coeff(i)
            val a = if (flip) negateOrNull(raw) ?: return null else raw
            if (a < 0L) bound = addOrNull(bound, negateOrNull(a) ?: return null) ?: return null
        }
        return bound
    }

    /** Int-variable occurrence index over [problem]'s factors, as a CSR of factor indices per variable. */
    private fun intOccurrence(problem: Problem): IntOccurrence {
        val offsets = IntArray(problem.numIntVars + 1)
        for (f in problem.factors) for (v in f.intVars) offsets[v + 1]++
        for (v in 0 until problem.numIntVars) offsets[v + 1] += offsets[v]
        val cursor = offsets.copyOf()
        val flat = IntArray(offsets[problem.numIntVars])
        for (i in problem.factors.indices) for (v in problem.factors[i].intVars) flat[cursor[v]++] = i
        return IntOccurrence(offsets, flat)
    }

    private class IntOccurrence(val offsets: IntArray, val flat: IntArray) {
        fun count(v: Int): Int = offsets[v + 1] - offsets[v]

        inline fun forEach(v: Int, action: (Int) -> Unit) {
            for (i in offsets[v] until offsets[v + 1]) action(flat[i])
        }
    }

    private fun IntDomain.isBinary(): Boolean = min == 0L && max == 1L && size == 2

    private fun negateOrNull(a: Long): Long? = if (a == Long.MIN_VALUE) null else -a

    private fun addOrNull(a: Long, b: Long): Long? {
        val r = a + b
        return if (((a xor r) and (b xor r)) < 0L) null else r
    }
}
