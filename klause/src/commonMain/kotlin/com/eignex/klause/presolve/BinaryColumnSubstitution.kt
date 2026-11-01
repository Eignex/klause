package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
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
 * The column is substituted **outright** — an existing unary indicator or a fresh Boolean carries its
 * value without a channelling factor. Channelling instead (a reified `x = 1 ⟺ b` per column) would trade
 * the [Linear] rows
 * for pseudo-Boolean rows *plus* a reified factor each, and reified single-variable indicators are
 * consumed by comparison-clause folding, which strands them.
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
 * A single-variable reified relation whose truth differs at 0 and 1 becomes a Boolean
 * literal carrying the integer value. Additional indicators become Boolean equivalences to that literal.
 * Other factor kinds read their integer variables value-wise (a global, a general reified
 * row, a product), so a column they mention stays an integer column.
 *
 * An exact binary objective can use [BinaryObjectiveView] before substitution. Other objective columns
 * remain protected, since their costs still address integer coordinates.
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

    /** The transformed [problem], its typed [rebuild] into the input's variable space, and the number of
     *  [columns] substituted. [reconstruct] adapts the rebuild to finite samples. */
    class Substitution(
        val problem: BakedProblem,
        val rebuild: SourceRebuilds,
        val columns: Int,
        val objective: LinearObjective? = null,
    ) {
        val reconstruct: (Sample) -> Sample = checkNotNull(rebuild.asSampleLift())
    }

    /**
     * Substitute every eligible `{0, 1}` column of [problem] for a Boolean literal, or return `null`
     * when no column qualifies (the common case, and always for a model with no integer columns).
     * [objectiveIntVars] are held back unless [objective] admits an exact Boolean view.
     * [bakeConfig] is threaded into the rebuild so the root-bake probing
     * policy is re-derived over the rewritten factor set.
     * [requireMixedObjectiveSubstitution] withholds mixed cost projection unless a cost column is eliminated.
     */
    fun substitute(
        problem: BakedProblem,
        objectiveIntVars: Set<Int>,
        bakeConfig: BakeConfig,
        objective: LinearObjective? = null,
        allowMixedObjective: Boolean = false,
        requireMixedObjectiveSubstitution: Boolean = false,
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
        return substituteColumns(problem, protected, bakeConfig, view,
            objective?.takeIf { requireMixedObjectiveSubstitution && view?.hasNonbinaryCost == true })
    }

    private fun substituteColumns(
        problem: BakedProblem,
        objectiveIntVars: Set<Int>,
        bakeConfig: BakeConfig,
        view: BinaryObjectiveView?,
        requiredMixedObjective: LinearObjective? = null,
    ): Substitution? {
        val numInts = problem.numIntVars
        val factors = view?.factors?.toTypedArray() ?: problem.factors
        val numBools = view?.numBoolVars ?: problem.numBoolVars
        val seeds = if (view == null) PropagationResult.Implied.EMPTY else problem.rootDeductions
        fun unprojected(): Substitution? {
            val token = problem.cancellation
            val units = PresolveEntrySize(token.presolveBudget()).units(problem, token)
            token.charge(PresolvePass.SUBSTITUTE_BINARY_COLUMNS.baseWorkWeight * units)
            if (token()) return null
            val protected = buildSet {
                addAll(objectiveIntVars)
                checkNotNull(requiredMixedObjective).intCoefficientValues.forEachIndexed { v, coefficient ->
                    if (v % 256 == 0 && token()) return null
                    if (coefficient != 0L) add(v)
                }
            }
            return substituteColumns(problem, protected, bakeConfig, null)
        }
        fun viewOnly(): Substitution? {
            // Additional objective protection cannot create an eligible integer column.
            if (view == null || requiredMixedObjective != null) return null
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
        if (requiredMixedObjective != null && (0 until columns.size).none {
            if (it % 256 == 0 && problem.cancellation()) return null
            requiredMixedObjective.intCoefficientValues.getOrElse(columns[it]) { 0L } != 0L
        }) return unprojected()

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

    /**
     * The columns [substitute] turns into literals when it meets [factors] with [ranges]: `{0, 1}`, outside
     * [objectiveIntVars], and read only by rows it can rewrite. `null` when no column qualifies.
     */
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

    /** Clear [substitutable] for every column a factor this cannot rewrite mentions, and — to a fixpoint —
     *  for the columns of the rows that lose a column with it. A row whose columns are not all substitutable
     *  is itself not rewritable, which is recorded by clearing its [normalizedBound] slot. */
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

    /**
     * The pseudo-Boolean form of row [f] over the literals [valueLiterals] assigns its columns, given its
     * pre-computed normalised [bound]. Emits the tightest lane: a [Clause] for an at-least-one, a
     * [Cardinality] for unit weights, else a [PseudoBoolean]. A vacuous row drops; an infeasible one is
     * emitted as a [PseudoBoolean] whose bound propagation refutes. A row whose every coefficient is zero
     * has no literals to carry it, so it is kept as it came — its columns are pinned to 0, which reproduces
     * exactly the constant relation it already was.
     */
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

            // `Σ l ≥ n − 1` is `Σ ¬l ≤ 1`, and it is the `max == 1` form the at-most-one clique detector
            // reads ([com.eignex.klause.factor.bool.internals.amoCliques]) — the same constraint, in the
            // shape its consumers see.
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
        val row = f.integerConstants ?: return null
        if (f.op == LinearOp.NE || f.vars.isEmpty()) return null
        val flip = f.op == LinearOp.LE
        var bound = if (flip) negateOrNull(row.bound) ?: return null else row.bound
        for (i in f.vars.indices) {
            val raw = row.coeff(i)
            val a = if (flip) negateOrNull(raw) ?: return null else raw
            if (a < 0L) bound = addOrNull(bound, negateOrNull(a) ?: return null) ?: return null
        }
        return bound
    }

    /** Int-variable occurrence index over [factors], as a CSR of factor indices per variable. */
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

    private fun addOrNull(a: Long, b: Long): Long? {
        val r = a + b
        return if (((a xor r) and (b xor r)) < 0L) null else r
    }
}
