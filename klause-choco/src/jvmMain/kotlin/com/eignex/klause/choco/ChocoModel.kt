package com.eignex.klause.choco

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.AllDifferentExcept
import com.eignex.klause.solver.factor.Among
import com.eignex.klause.solver.factor.ArgMinMax
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.BinPacking
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Count
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Diffn
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.GaussianXor
import com.eignex.klause.solver.factor.Geost
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.Knapsack
import com.eignex.klause.solver.factor.LexLess
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Mdd
import com.eignex.klause.solver.factor.NValue
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Sequence
import com.eignex.klause.solver.factor.SetBitsetDisjoint
import com.eignex.klause.solver.factor.SetBitsetEq
import com.eignex.klause.solver.factor.SetBitsetSubset
import com.eignex.klause.solver.factor.Sort
import com.eignex.klause.solver.factor.Subcircuit
import com.eignex.klause.solver.factor.SubsetSumEq
import com.eignex.klause.solver.factor.SymmetricAllDifferent
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.factor.Xor
import org.chocosolver.solver.Model
import org.chocosolver.solver.SettingsBuilder
import org.chocosolver.solver.constraints.Constraint
import org.chocosolver.solver.constraints.extension.Tuples
import org.chocosolver.solver.constraints.nary.automata.FA.FiniteAutomaton
import org.chocosolver.solver.variables.BoolVar
import org.chocosolver.solver.variables.IntVar
import org.chocosolver.solver.variables.Task

/**
 * Translates a klause [Problem] into a Choco [Model]. As a complete-search correctness
 * reference, the adapter maps each klause factor to its closest Choco global constraint
 * (native propagator) where one exists, and to a faithful constraint-level decomposition
 * otherwise. Unsupported factors raise [UnsupportedFactorException] rather than silently
 * dropping a constraint — a reference solver that quietly ignores constraints would make
 * parity meaningless.
 */
class UnsupportedFactorException(
    /** The klause factor that has no Choco translation. */
    val factor: Factor,
) : RuntimeException("klause-choco: unsupported factor ${factor::class.simpleName}")

/**
 * A klause [Problem] translated into a ready-to-solve Choco [Model], built via [build].
 */
class ChocoModel private constructor(
    /** The source klause problem this model was translated from. */
    val problem: Problem,
    /** The underlying Choco model holding the variables and posted constraints. */
    val model: Model,
    /** Choco boolean variables indexed by klause boolean variable id. */
    val boolVars: Array<BoolVar>,
    /** Choco integer variables indexed by klause integer variable id. */
    val intVars: Array<IntVar>,
) {
    /** Resolve a klause literal to its Choco view (the bool var, or its negation). */
    private fun litVar(lit: Int): BoolVar {
        val v = boolVars[Lit.variable(lit)]
        return if (Lit.isPositive(lit)) v else v.not()
    }

    private fun litVars(lits: IntArray): Array<IntVar> = Array(lits.size) { litVar(lits[it]) }
    private fun intVarsOf(ids: IntArray): Array<IntVar> = Array(ids.size) { intVars[ids[it]] }

    @Suppress("SpreadOperator") // spreads feed Choco's vararg constraint API
    private fun postFactor(f: Factor) {
        when (f) {
            is Clause -> model.or(*Array(f.literals.size) { litVar(f.literals[it]) }).post()

            is Cardinality -> postCount(litVars(f.literals), f.min, f.max)

            is Linear -> {
                requireScalarRepresentable(f, intScalarRange(f.vars, f.coeffs))
                model.scalar(intVarsOf(f.vars), f.coeffs, opStr(f.op), f.bound).post()
            }

            is PseudoBoolean -> {
                requireScalarRepresentable(f, weightScalarRange(f.weights))
                model.scalar(litVars(f.literals), f.weights, pbStr(f.op), f.bound).post()
            }

            is Xor -> postParity(litVars(f.literals), f.targetParity)

            // Redundant klause-internal propagators: each is fully implied by the Linear
            // (SubsetSumEq) or per-row Xor factors (GaussianXor) klause posts alongside it,
            // so the Choco model already enforces the constraint and skips these.
            is SubsetSumEq -> {}

            is GaussianXor -> {}

            is AllDifferent -> model.allDifferent(*intVarsOf(f.vars)).post()

            is Product -> model.times(intVars[f.a], intVars[f.b], intVars[f.result]).post()

            is ReifiedLinear -> {
                requireScalarRepresentable(f, intScalarRange(f.vars, f.coeffs))
                model.scalar(intVarsOf(f.vars), f.coeffs, opStr(f.op), f.bound).reifyWith(boolVars[f.auxBoolVar])
            }

            is ReifiedPseudoBoolean -> {
                requireScalarRepresentable(f, weightScalarRange(f.weights))
                model.scalar(litVars(f.literals), f.weights, pbStr(f.op), f.bound).reifyWith(boolVars[f.auxBoolVar])
            }

            is ReifiedCardinality ->
                countConstraint(litVars(f.literals), f.min, f.max).reifyWith(boolVars[f.auxBoolVar])

            is AllDifferentExcept -> postAllDifferentExcept(f)

            is Among -> model.among(intVars[f.n], intVarsOf(f.xs), f.values).post()

            is Count -> postCountFactor(f)

            is Element -> // Choco: element(VALUE, table, INDEX, offset) ⇒ value = table[index - offset].
                if (f.arrIsVars) {
                    model.element(intVars[f.result], intVarsOf(f.arr), intVars[f.idx], f.indexOffset).post()
                } else {
                    model.element(intVars[f.result], f.arr, intVars[f.idx], f.indexOffset).post()
                }

            is Inverse -> model.inverseChanneling(intVarsOf(f.f), intVarsOf(f.g), f.fOffset, f.gOffset).post()

            is SymmetricAllDifferent ->
                model.inverseChanneling(intVarsOf(f.xs), intVarsOf(f.xs), f.indexOffset, f.indexOffset).post()

            is LexLess ->
                (
                    if (f.strict) {
                        model.lexLess(intVarsOf(f.xs), intVarsOf(f.ys))
                    } else {
                        model.lexLessEq(intVarsOf(f.xs), intVarsOf(f.ys))
                    }
                    ).post()

            is NValue -> postNValue(f)

            is GlobalCardinality -> postGcc(f)

            is Table -> model.table(intVarsOf(f.xs), tuplesOf(f)).post()

            is ArgMinMax ->
                (
                    if (f.max) {
                        model.argmax(intVars[f.idx], f.indexOffset, intVarsOf(f.xs))
                    } else {
                        model.argmin(intVars[f.idx], f.indexOffset, intVarsOf(f.xs))
                    }
                    ).post()

            is ArrayMinMax ->
                (
                    if (f.max) {
                        model.max(
                            intVars[f.result],
                            intVarsOf(f.xs),
                        )
                    } else {
                        model.min(intVars[f.result], intVarsOf(f.xs))
                    }
                    ).post()

            is Knapsack ->
                model.knapsack(intVarsOf(f.xs), intVars[f.w], intVars[f.p], f.weights, f.profits).post()

            is Cumulative -> postCumulative(f)

            is Diffn -> postDiffn(f)

            is BinPacking -> postBinPacking(f)

            is Sort -> model.sort(intVarsOf(f.xs), intVarsOf(f.ys)).post()

            is Sequence -> postSequence(f)

            is Regular -> model.regular(intVarsOf(f.seq), automatonOf(f)).post()

            is Circuit -> model.circuit(intVarsOf(f.succ), 0).post()

            is Subcircuit -> model.subCircuit(intVarsOf(f.succ), 0, model.intVar(0, f.succ.size)).post()

            is Geost -> postGeost(f)

            is Mdd -> postMdd(f)

            is SetBitsetSubset -> postSetSubset(f.leftBools, f.rightBools)

            is SetBitsetDisjoint -> postSetDisjoint(f.leftBools, f.rightBools)

            is SetBitsetEq -> postSetEq(f.leftBools, f.rightBools)

            else -> throw UnsupportedFactorException(f)
        }
    }

    private fun postCount(vars: Array<IntVar>, min: Int, max: Int) {
        if (min > 0) model.sum(vars, ">=", min).post()
        if (max < vars.size) model.sum(vars, "<=", max).post()
    }

    /** Reachable `[lo, hi]` of `sum(coeffs[i] * intVars[vars[i]])` over the variables' domains. */
    private fun intScalarRange(vars: IntArray, coeffs: IntArray): LongRange {
        var lo = 0L
        var hi = 0L
        for (k in vars.indices) {
            val d = problem.intDomains[vars[k]]
            val c = coeffs[k].toLong()
            val a = c * d.min
            val b = c * d.max
            lo += minOf(a, b)
            hi += maxOf(a, b)
        }
        return lo..hi
    }

    /** Reachable `[lo, hi]` of a pseudo-Boolean weighted sum (each literal contributes 0 or its weight). */
    private fun weightScalarRange(weights: IntArray): LongRange {
        var lo = 0L
        var hi = 0L
        for (w in weights) {
            if (w >= 0) hi += w.toLong() else lo += w.toLong()
        }
        return lo..hi
    }

    /** Choco materializes a scalar/lin-comb through an intermediate int var; it rejects any var
     *  whose bound hits the int extremes or whose span reaches `Integer.MAX_VALUE` (#120). When
     *  the reachable sum range exceeds that, the reference cannot faithfully model the factor, so
     *  raise the explicit unsupported signal rather than letting Choco throw mid-build. */
    private fun requireScalarRepresentable(f: Factor, range: LongRange) {
        val limit = Int.MAX_VALUE.toLong()
        if (range.first <= Int.MIN_VALUE.toLong() || range.last >= limit || range.last - range.first >= limit) {
            throw UnsupportedFactorException(f)
        }
    }

    /** A single reifiable constraint capturing `min <= sum(vars) <= max`, via a sum var. */
    private fun countConstraint(vars: Array<IntVar>, min: Int, max: Int) = model.intVar(0, vars.size).let { s ->
        model.sum(vars, "=", s).post()
        model.member(s, min, max)
    }

    private fun postParity(vars: Array<IntVar>, targetParity: Int) {
        val s = model.intVar(0, vars.size)
        model.sum(vars, "=", s).post()
        val allowed = (0..vars.size).filter { it % 2 == (targetParity and 1) }.toIntArray()
        model.member(s, allowed).post()
    }

    private fun postAllDifferentExcept(f: AllDifferentExcept) {
        // No native general-except propagator: pairwise (xi ∈ except) ∨ (xj ∈ except) ∨ (xi ≠ xj).
        for (i in f.xs.indices) {
            for (j in i + 1 until f.xs.size) {
                model.or(
                    model.member(intVars[f.xs[i]], f.except),
                    model.member(intVars[f.xs[j]], f.except),
                    model.arithm(intVars[f.xs[i]], "!=", intVars[f.xs[j]]),
                ).post()
            }
        }
    }

    private fun postCountFactor(f: Count) {
        if (f.presents.isNotEmpty()) throw UnsupportedFactorException(f)
        // `n = #{i : xs[i] ⟨op⟩ v}` with `n` the count *variable* (intVars[f.n]). `op` is the
        // per-element match predicate, not the count-vs-n relation. Choco's `count` only counts
        // equality, so reify each element's match and sum the indicators into the count var —
        // uniform across all ops. (The earlier `count(eq)` + `arithm(limit, op, f.n)` form was
        // wrong twice: it ignored non-Eq match ops, and compared against the raw id `f.n` as a
        // constant rather than the variable, fabricating false UNSATs when `f.n > xs.size`.)
        val matches = Array(f.xs.size) { i ->
            model.arithm(intVars[f.xs[i]], cmpStr(f.op), f.v).reify()
        }
        model.sum(matches, "=", intVars[f.n]).post()
    }

    private fun postNValue(f: NValue) {
        if (f.presents.isNotEmpty()) throw UnsupportedFactorException(f)
        val nVar = intVars[f.n]
        when (f.mode) {
            NValue.Mode.Eq -> model.nValues(intVarsOf(f.xs), nVar).post()
            NValue.Mode.AtLeast -> model.atLeastNValues(intVarsOf(f.xs), nVar, true).post()
            NValue.Mode.AtMost -> model.atMostNValues(intVarsOf(f.xs), nVar, true).post()
        }
    }

    private fun postGcc(f: GlobalCardinality) {
        if (f.presents.isNotEmpty()) throw UnsupportedFactorException(f)
        val countVars = f.countVars
        val countLow = f.countLow
        val countHigh = f.countHigh
        val occ: Array<IntVar> = when {
            countVars != null -> intVarsOf(countVars)

            countLow != null && countHigh != null ->
                Array(f.cover.size) { model.intVar(countLow[it], countHigh[it]) }

            else -> Array(f.cover.size) { model.intVar(0, f.xs.size) }
        }
        model.globalCardinality(intVarsOf(f.xs), f.cover, occ, f.closed).post()
    }

    private fun postCumulative(f: Cumulative) {
        if (f.presents.isNotEmpty()) throw UnsupportedFactorException(f)
        // durations/resources/capacity are constants; the var forms live in the *Vars arrays
        // (empty = use the constant). Indexing intVars with the constant values is the #119 crash.
        val durVars = f.durationVars.takeIf { it.isNotEmpty() }
        val resVars = f.resourceVars.takeIf { it.isNotEmpty() }
        val tasks = Array(f.starts.size) { i ->
            val start = intVars[f.starts[i]]
            val dur = if (durVars != null) intVars[durVars[i]] else model.intVar(f.durations[i])
            val end = model.intVar(start.lb + dur.lb, start.ub + dur.ub)
            Task(start, dur, end)
        }
        val heights = if (resVars != null) {
            intVarsOf(
                resVars,
            )
        } else {
            Array(f.resources.size) { model.intVar(f.resources[it]) }
        }
        val capacity = if (f.capacityVar >= 0) intVars[f.capacityVar] else model.intVar(f.capacity)
        model.cumulative(tasks, heights, capacity).post()
    }

    private fun postDiffn(f: Diffn) {
        val widthVars = f.widthVars
        val heightVars = f.heightVars
        val w = if (widthVars != null) intVarsOf(widthVars) else Array(f.widths.size) { model.intVar(f.widths[it]) }
        val h = if (heightVars != null) intVarsOf(heightVars) else Array(f.heights.size) { model.intVar(f.heights[it]) }
        model.diffN(intVarsOf(f.xs), intVarsOf(f.ys), w, h, true).post()
    }

    private fun postBinPacking(f: BinPacking) {
        val loads: Array<IntVar> = when (f.mode) {
            BinPacking.Mode.LoadVars -> intVarsOf(requireNotNull(f.loadVars))
            BinPacking.Mode.UniformCapacity -> Array(f.numBins) { model.intVar(0, f.uniformCapacity) }
            BinPacking.Mode.PerBinCapacity -> Array(f.numBins) { model.intVar(0, requireNotNull(f.capacities)[it]) }
        }
        model.binPacking(intVarsOf(f.bins), f.weights, loads, f.binOffset).post()
    }

    private fun postSequence(f: Sequence) {
        // |xs| - k + 1 sliding windows, each an `among` with the count bounded to [low, high].
        for (start in 0..f.xs.size - f.k) {
            val window = Array(f.k) { intVars[f.xs[start + it]] }
            val nb = model.intVar(f.low, f.high)
            model.among(nb, window, f.values).post()
        }
    }

    @Suppress("SpreadOperator") // spreads feed Choco's vararg constraint API
    private fun postGeost(f: Geost) {
        // Pairwise separation in at least one dimension: oi+si ≤ oj  ∨  oj+sj ≤ oi (per dim).
        for (i in 0 until f.numObjects) {
            for (j in i + 1 until f.numObjects) {
                val opts = ArrayList<Constraint>()
                for (d in 0 until f.numDims) {
                    val oi = intVars[f.origin[i * f.numDims + d]]
                    val oj = intVars[f.origin[j * f.numDims + d]]
                    val si = f.length[i * f.numDims + d]
                    val sj = f.length[j * f.numDims + d]
                    opts.add(model.scalar(arrayOf(oi, oj), intArrayOf(1, -1), "<=", -si)) // oi + si ≤ oj
                    opts.add(model.scalar(arrayOf(oj, oi), intArrayOf(1, -1), "<=", -sj)) // oj + sj ≤ oi
                }
                model.or(*opts.toTypedArray()).post()
            }
        }
    }

    private fun postMdd(f: Mdd) {
        // Each layer is a transition table over (q[i], seq[i], q[i+1][, weight]); chain the
        // state trace through them, fix q[0] = initial and require q[n] accepting.
        val n = f.seq.size
        val states = sortedSetOf(f.initial)
        f.accepting.forEach { states.add(it) }
        var p = 0
        while (p < f.transitions.size) {
            states.add(
                f.transitions[p],
            )
            states.add(f.transitions[p + 2])
            p += f.recordStride
        }
        val lo = states.first()
        val hi = states.last()
        val q = Array(n + 1) { model.intVar("mddq$it", lo, hi) }
        model.arithm(q[0], "=", f.initial).post()
        val cost4 = f.recordStride == 4
        val wVars = if (cost4) ArrayList<IntVar>(n) else null
        var wLo = 0
        var wHi = 0
        if (cost4) {
            var r = 0
            while (r < f.transitions.size) {
                val w = f.transitions[r + 3]
                if (w < wLo) wLo = w
                if (w > wHi) wHi = w
                r += f.recordStride
            }
        }
        for (i in 0 until n) {
            val tuples = Tuples(true)
            var row = f.layerStarts[i]
            while (row < f.layerStarts[i + 1]) {
                if (cost4) {
                    tuples.add(
                        intArrayOf(
                            f.transitions[row],
                            f.transitions[row + 1],
                            f.transitions[row + 2],
                            f.transitions[row + 3],
                        ),
                    )
                } else {
                    tuples.add(intArrayOf(f.transitions[row], f.transitions[row + 1], f.transitions[row + 2]))
                }
                row += f.recordStride
            }
            if (cost4) {
                val w = model.intVar("mddw$i", wLo, wHi)
                requireNotNull(wVars).add(w)
                model.table(arrayOf(q[i], intVars[f.seq[i]], q[i + 1], w), tuples).post()
            } else {
                model.table(arrayOf(q[i], intVars[f.seq[i]], q[i + 1]), tuples).post()
            }
        }
        model.member(q[n], f.accepting).post()
        if (f.cost >= 0 && wVars != null) model.sum(wVars.toTypedArray(), "=", intVars[f.cost]).post()
    }

    private fun postSetSubset(left: IntArray, right: IntArray) {
        for (i in left.indices) {
            val l = left[i]
            val r = right[i]
            if (l < 0) continue
            if (r < 0) {
                model.arithm(boolVars[l], "=", 0).post()
            } else {
                model.arithm(boolVars[l], "<=", boolVars[r]).post()
            }
        }
    }

    private fun postSetDisjoint(left: IntArray, right: IntArray) {
        for (i in left.indices) {
            val l = left[i]
            val r = right[i]
            if (l >= 0 && r >= 0) model.arithm(boolVars[l], "+", boolVars[r], "<=", 1).post()
        }
    }

    private fun postSetEq(left: IntArray, right: IntArray) {
        for (i in left.indices) {
            val l = left[i]
            val r = right[i]
            when {
                l >= 0 && r >= 0 -> model.arithm(boolVars[l], "=", boolVars[r]).post()
                l >= 0 -> model.arithm(boolVars[l], "=", 0).post()
                r >= 0 -> model.arithm(boolVars[r], "=", 0).post()
            }
        }
    }

    private fun tuplesOf(f: Table): Tuples {
        val t = Tuples(true)
        for (r in 0 until f.numTuples) {
            t.add(IntArray(f.arity) { c -> f.tuples[r * f.arity + c] })
        }
        return t
    }

    private fun automatonOf(f: Regular): FiniteAutomaton {
        // klause states are 1-based with a 0 "dead" sentinel; symbols are 1-based and equal
        // to the seq variable value. Map klause state k → Choco state index (k-1).
        val auto = FiniteAutomaton()
        val states = IntArray(f.numStates) { auto.addState() }
        auto.setInitialState(states[f.q0 - 1])
        for (a in f.accepting) auto.setFinal(states[a - 1])
        for (st in 1..f.numStates) {
            for (sym in 1..f.alphabetSize) {
                val target = f.transitions[(st - 1) * f.alphabetSize + (sym - 1)]
                if (target != 0) auto.addTransition(states[st - 1], states[target - 1], sym)
            }
        }
        return auto
    }

    /** Factory for building a [ChocoModel] from a klause [Problem]. */
    companion object {
        /** Contiguous int domains at least this wide are built as bounded (interval) Choco vars
         *  rather than enumerated bitsets, which Choco rejects past a few tens of thousands. */
        private const val MAX_ENUMERATED_SPAN = 1 shl 16

        /** Translate [problem] into a [ChocoModel] by posting every factor as a Choco constraint. */
        fun build(problem: Problem, lcg: Boolean = false): ChocoModel {
            // lcg = Choco's lazy-clause-generation engine (the "Choco CP-SAT" competition
            // entry's architecture): bound/value literals + clause learning instead of the
            // classic CP kernel. The architecture-matched reference for klause.
            val model = if (lcg) {
                Model("klause-choco", SettingsBuilder().setLCG(true).build())
            } else {
                Model("klause-choco")
            }
            val boolVars = Array(problem.numBoolVars) { model.boolVar("b$it") }
            val intVars = Array(problem.numIntVars) { i ->
                val d = problem.intDomains[i]
                // Use explicit value enumeration when the domain has interior holes.
                if (d.size == d.max - d.min + 1) {
                    // A wide contiguous range as an enumerated bitset trips Choco's "too large
                    // domain" guard (#120). Use the bounded (interval) representation instead —
                    // sound here because there are no holes to track.
                    val bounded = d.max.toLong() - d.min.toLong() >= MAX_ENUMERATED_SPAN
                    model.intVar("i$i", d.min, d.max, bounded)
                } else {
                    val values = ArrayList<Int>(d.size)
                    d.forEach { values.add(it) }
                    model.intVar("i$i", values.toIntArray())
                }
            }
            val cm = ChocoModel(problem, model, boolVars, intVars)
            for (f in problem.factors) cm.postFactor(f)
            return cm
        }

        private fun opStr(op: LinearOp): String = when (op) {
            LinearOp.LE -> "<="
            LinearOp.EQ -> "="
            LinearOp.GE -> ">="
            LinearOp.NE -> "!="
        }

        private fun pbStr(op: PbOp): String = when (op) {
            PbOp.LE -> "<="
            PbOp.EQ -> "="
            PbOp.GE -> ">="
        }

        private fun cmpStr(op: Count.Op): String = when (op) {
            Count.Op.Eq -> "="
            Count.Op.Ne -> "!="
            Count.Op.Le -> "<="
            Count.Op.Lt -> "<"
            Count.Op.Ge -> ">="
            Count.Op.Gt -> ">"
        }
    }
}
