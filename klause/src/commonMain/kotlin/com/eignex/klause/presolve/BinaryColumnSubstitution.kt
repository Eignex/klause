package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList
import com.eignex.util.numbers.addExactOrNull

// Substitutes each `{0, 1}` integer column with a Boolean literal, so its rows become [Clause],
// [Cardinality] or [PseudoBoolean]. The gain is the lane: conflict analysis learns only from those
// propagators, so as [Linear] rows the model gets no division-based learning, clique merging or
// coefficient strengthening over literals.
// The column is substituted outright rather than channelled: a reified `x = 1 ⟺ b` per column would add a
// factor each, and comparison-clause folding consumes single-variable indicators, stranding them.
// A column qualifies only when every factor mentioning it is an integer-core non-`≠` [Linear] whose
// positive-weight normalisation fits [Long] and whose columns all qualify (a fixpoint), or a
// single-variable reified relation whose truth differs at 0 and 1, which becomes the literal itself.
// Objective columns stay protected unless [BinaryObjectiveView] gives an exact Boolean view.
// The substituted column is pinned to 0 so search never branches on it; that keeps the transform a
// bijection on solutions, so it needs no solution-set-sensitivity gate.
internal object BinaryColumnSubstitution {

    class Substitution(
        val problem: BakedProblem,
        val rebuild: SourceRebuilds,
        val columns: Int,
        val objective: LinearObjective? = null,
    ) {
        val reconstruct: (Sample) -> Sample = checkNotNull(rebuild.asSampleLift())
    }

    // Null when no column qualifies, the common case.
    fun substitute(
        problem: BakedProblem,
        objectiveIntVars: Set<Int>,
        bakeConfig: BakeConfig,
        objective: LinearObjective? = null,
        allowMixedObjective: Boolean = false,
    ): Substitution? {
        val view = objective?.let { BinaryObjectiveView.create(problem, it, allowMixedObjective) }
        val protected = when {
            view == null -> objectiveIntVars
            !allowMixedObjective -> emptySet()
            else -> buildSet {
                for (v in view.objective.intCoefficientValues.indices) {
                    if (v % 256 == 0 && problem.cancellation()) return null
                    if (view.objective.intCoefficientValues[v] != 0L) add(v)
                }
            }
        }
        return substituteColumns(problem, protected, bakeConfig, view)
    }

    private fun substituteColumns(
        problem: BakedProblem,
        objectiveIntVars: Set<Int>,
        bakeConfig: BakeConfig,
        view: BinaryObjectiveView?,
    ): Substitution? {
        val numInts = problem.numIntVars
        val factors = view?.factors?.toTypedArray() ?: problem.factors
        val numBools = view?.numBoolVars ?: problem.numBoolVars
        val seeds = if (view == null) PropagationResult.Implied.EMPTY else problem.rootDeductions
        fun viewOnly(): Substitution? {
            if (view == null) return null
            val rebuilt = PresolveShared.rebuildProblem(
                problem, view.factors, bakeConfig = bakeConfig, numBoolVars = numBools, seedDeductions = seeds,
            )
            return Substitution(
                rebuilt, SourceRebuilds(emptyList(), sourceBoolVars = problem.numBoolVars), 0, view.objective,
            )
        }
        val ranges = ColumnRanges.of(problem.rootIntDomainsInPlace)
        val eligibility = eligibility(factors, numInts, ranges, objectiveIntVars) ?: return viewOnly()
        val substitutable = eligibility.substitutable
        val rows = eligibility.rows
        val channels = eligibility.channels
        val normalizedBound = eligibility.normalizedBound
        val occurrence = eligibility.occurrence

        // A column no factor mentions gains nothing from a literal (and would leave the Boolean free), so
        // only the columns some surviving row still reads are substituted.
        val valueLiterals = IntArray(numInts) { Lit.NONE }
        for (i in factors.indices) {
            if (channels[i] == Lit.NONE) continue
            val v = factors[i].intVars.single()
            if (substitutable[v] && valueLiterals[v] == Lit.NONE) valueLiterals[v] = channels[i]
        }
        val columns = IntArrayList()
        var nextBool = numBools
        for (v in 0 until numInts) {
            if (!substitutable[v] || occurrence.count(v) == 0) continue
            if (valueLiterals[v] == Lit.NONE) valueLiterals[v] = Lit.make(nextBool++, true)
            columns.add(v)
        }
        if (columns.isEmpty()) return viewOnly()

        val out = ArrayList<Factor>(factors.size)
        for (i in factors.indices) {
            val f = factors[i]
            val bound = normalizedBound[i]
            val row = rows[i]
            if (bound == null || !f.intVars.all { valueLiterals[it] != Lit.NONE }) {
                out.add(f)
                continue
            }
            val channel = channels[i]
            if (channel != Lit.NONE) {
                val value = valueLiterals[f.intVars.single()]
                if (channel != value) {
                    out.add(Clause(intArrayOf(Lit.negate(channel), value)))
                    out.add(Clause(intArrayOf(channel, Lit.negate(value))))
                }
            } else if (row != null) {
                out.addAll(lower(row, valueLiterals, bound))
            } else {
                out.add(f)
            }
        }
        val domains = problem.rootIntDomains()
        for (k in 0 until columns.size) domains[columns[k]] = IntDomain(0, 0)
        val rebuilt = PresolveShared.rebuildProblem(
            problem, out, domains, bakeConfig, numBoolVars = nextBool, seedDeductions = seeds,
        )
        val substituted = columns.toIntArray()
        val rebuild = SourceRebuilds(
            substituted.map { RebuildStep.IntegerFromLiteral(it, valueLiterals[it]) },
            sourceBoolVars = problem.numBoolVars,
        )
        return Substitution(rebuilt, rebuild, substituted.size, view?.objective)
    }

    fun substitutableColumns(
        factors: Array<Factor>,
        numIntVars: Int,
        ranges: ColumnRanges,
        objectiveIntVars: Set<Int>,
    ): BooleanArray? = eligibility(factors, numIntVars, ranges, objectiveIntVars)?.substitutable

    private class Eligibility(
        val substitutable: BooleanArray,
        val rows: Array<Linear?>,
        val channels: IntArray,
        val normalizedBound: Array<Long?>,
        val occurrence: IntOccurrence,
    )

    private fun eligibility(
        factors: Array<Factor>,
        numIntVars: Int,
        ranges: ColumnRanges,
        objectiveIntVars: Set<Int>,
    ): Eligibility? {
        val substitutable = BooleanArray(numIntVars) { ranges.isBinary(it) && it !in objectiveIntVars }
        if (substitutable.none { it }) return null

        // The `≥`-form right-hand side of each row after positive-weight normalisation, computed once here
        // and reused by the rewrite; `null` marks a factor that cannot become a pseudo-Boolean relation.
        val rows = Array(factors.size) { factors[it].equivalentLinear() }
        val channels = IntArray(factors.size) { factors[it].binaryChannelLiteral() }
        val normalizedBound = arrayOfNulls<Long>(factors.size)
        for (i in factors.indices) {
            val row = rows[i]
            if (row != null) normalizedBound[i] = normalizedBound(row)
            if (channels[i] != Lit.NONE) normalizedBound[i] = 0L
        }
        val occurrence = intOccurrence(factors, numIntVars)
        disqualifyToFixpoint(factors, substitutable, normalizedBound, occurrence)
        return Eligibility(substitutable, rows, channels, normalizedBound, occurrence)
    }

    // A cleared [normalizedBound] slot marks a row as not rewritable.
    private fun disqualifyToFixpoint(
        factors: Array<Factor>,
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
        for (i in factors.indices) {
            val f = factors[i]
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
                    for (u in factors[fid].intVars) disqualify(u)
                }
            }
        }
    }

    // An infeasible row is emitted as a [PseudoBoolean] that propagation refutes. A row with all-zero
    // coefficients is kept as is: its columns are pinned to 0, reproducing the constant relation it was.
    private fun lower(f: Linear, valueLiterals: IntArray, bound: Long): List<Factor> {
        val row = f.integerConstants ?: return listOf(f)
        val flip = f.op == LinearOp.LE // ≤ → ≥ negates both sides
        val weights = LongArrayList(f.vars.size)
        val literals = IntArrayList(f.vars.size)
        for (i in f.vars.indices) {
            val raw = row.coeff(i)
            val a = if (flip) -raw else raw
            if (a == 0L) continue
            weights.add(if (a < 0L) -a else a)
            val value = valueLiterals[f.vars[i]]
            literals.add(if (a > 0L) value else Lit.negate(value))
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

            // `Σ l ≥ n − 1` is `Σ ¬l ≤ 1`, the `max == 1` shape the at-most-one clique detector reads.
            unit && bound == n - 1L -> listOf(Cardinality(IntArray(n) { Lit.negate(lits[it]) }, 0, 1))

            unit && bound <= n.toLong() -> listOf(Cardinality(lits, bound.toInt(), n))

            else -> listOf(PseudoBoolean(ws, lits, PbOp.GE, bound))
        }
    }

    // The bound of `Σ wᵢ·litᵢ ⟨≥ | =⟩ bound` with every `wᵢ > 0`: a `≤` row is negated, and `−w·x` becomes
    // `w·¬x − w`. Null when the row is not rewritable or normalisation would leave the [Long] range.
    private fun normalizedBound(f: Linear): Long? {
        val row = f.integerConstants ?: return null
        if (f.op == LinearOp.NE || f.vars.isEmpty()) return null
        val flip = f.op == LinearOp.LE
        var bound = if (flip) negateOrNull(row.bound) ?: return null else row.bound
        for (i in f.vars.indices) {
            val raw = row.coeff(i)
            val a = if (flip) negateOrNull(raw) ?: return null else raw
            if (a < 0L) bound = addExactOrNull(bound, negateOrNull(a) ?: return null) ?: return null
        }
        return bound
    }

    private fun intOccurrence(factors: Array<Factor>, numIntVars: Int): IntOccurrence {
        val offsets = IntArray(numIntVars + 1)
        for (f in factors) for (v in f.intVars) offsets[v + 1]++
        for (v in 0 until numIntVars) offsets[v + 1] += offsets[v]
        val cursor = offsets.copyOf()
        val flat = IntArray(offsets[numIntVars])
        for (i in factors.indices) for (v in factors[i].intVars) flat[cursor[v]++] = i
        return IntOccurrence(offsets, flat)
    }

    private class IntOccurrence(val offsets: IntArray, val flat: IntArray) {
        fun count(v: Int): Int = offsets[v + 1] - offsets[v]

        inline fun forEach(v: Int, action: (Int) -> Unit) {
            for (i in offsets[v] until offsets[v + 1]) action(flat[i])
        }
    }

    private fun negateOrNull(a: Long): Long? = if (a == Long.MIN_VALUE) null else -a
}
