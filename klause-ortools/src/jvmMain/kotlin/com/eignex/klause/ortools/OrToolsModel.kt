package com.eignex.klause.ortools

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Diffn
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Inverse
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
import com.eignex.klause.solver.factor.Sort
import com.eignex.klause.solver.factor.Subcircuit
import com.eignex.klause.solver.factor.SymmetricAllDifferent
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.factor.Xor
import com.google.ortools.Loader
import com.google.ortools.sat.BoolVar
import com.google.ortools.sat.CpModel
import com.google.ortools.sat.IntVar
import com.google.ortools.sat.IntervalVar
import com.google.ortools.sat.LinearArgument
import com.google.ortools.sat.LinearExpr
import com.google.ortools.sat.Literal
import com.google.ortools.util.Domain

/**
 * Translates a klause [Problem] into an OR-Tools CP-SAT [CpModel]. As a correctness /
 * comparison reference, each factor maps to its native CP-SAT global constraint where one
 * exists (AllDifferent, Element, Inverse, Circuit, Cumulative, NoOverlap2D, Automaton,
 * Table, Min/Max) and to a faithful reified decomposition otherwise. Unsupported factors
 * raise [UnsupportedFactorException] so a missing translation is loud rather than silently
 * dropping a constraint.
 *
 * Reified linear / pseudo-Boolean factors are full-reified by enforcing the constraint under
 * `aux` and its operator-complement under `not(aux)` (CP-SAT half-reification via
 * `onlyEnforceIf`).
 */
class UnsupportedFactorException(
    /** The klause factor that has no OR-Tools translation. */
    val factor: Factor,
) : RuntimeException("klause-ortools: unsupported factor ${factor::class.simpleName}")

/**
 * A klause [Problem] translated into a ready-to-solve OR-Tools [CpModel], built via [build].
 */
class OrToolsModel private constructor(
    /** The source klause problem this model was translated from. */
    val problem: Problem,
    /** The underlying CP-SAT model holding the variables and posted constraints. */
    val model: CpModel,
    /** CP-SAT boolean variables indexed by klause boolean variable id. */
    val boolVars: Array<BoolVar>,
    /** CP-SAT integer variables indexed by klause integer variable id. */
    val intVars: Array<IntVar>,
) {
    private var auxCtr = 0
    private fun freshBool(): BoolVar = model.newBoolVar("aux_b${auxCtr++}")

    private fun lit(lit: Int): Literal = boolVars[Lit.variable(lit)].let { if (Lit.isPositive(lit)) it else it.not() }

    private fun litArgs(lits: IntArray): Array<LinearArgument> = Array(lits.size) { lit(lits[it]) as LinearArgument }
    private fun intArgs(ids: IntArray): Array<LinearArgument> = Array(ids.size) { intVars[ids[it]] as LinearArgument }
    private fun IntArray.longs(): LongArray = LongArray(size) { this[it].toLong() }

    /** Fresh literal `b ⟺ (x = value)`. */
    private fun reifyEq(x: LinearArgument, value: Int): Literal {
        val b = freshBool()
        model.addEquality(x, value.toLong()).onlyEnforceIf(b)
        model.addDifferent(x, value.toLong()).onlyEnforceIf(b.not())
        return b
    }

    /** Fresh literal `b ⟺ (a = c)`. */
    private fun reifyEqVar(a: LinearArgument, c: LinearArgument): Literal {
        val b = freshBool()
        model.addEquality(a, c).onlyEnforceIf(b)
        model.addDifferent(a, c).onlyEnforceIf(b.not())
        return b
    }
    private fun postFactor(f: Factor) {
        when (f) {
            is Clause -> model.addBoolOr(Array(f.literals.size) { lit(f.literals[it]) })

            is Cardinality -> {
                val sum = LinearExpr.sum(litArgs(f.literals))
                model.addLinearConstraint(sum, f.min.toLong(), f.max.toLong())
            }

            is Linear -> postLinearDomain(
                LinearExpr.weightedSum(intArgs(f.vars), f.coeffs.longs()),
                domainFor(f.op, f.bound),
            )

            is PseudoBoolean -> postLinearDomain(
                LinearExpr.weightedSum(litArgs(f.literals), f.weights.longs()),
                domainFor(f.op, f.bound),
            )

            is Xor -> {
                val sum = LinearExpr.sum(litArgs(f.literals))
                val allowed = (0..f.literals.size)
                    .filter { it % 2 == (f.targetParity and 1) }
                    .map { it.toLong() }
                    .toLongArray()
                model.addLinearExpressionInDomain(sum, Domain.fromValues(allowed))
            }

            is AllDifferent -> model.addAllDifferent(Array(f.vars.size) { intVars[f.vars[it]] })

            is Product -> model.addMultiplicationEquality(intVars[f.result], intVars[f.a], intVars[f.b])

            is ReifiedLinear -> reifyLinear(
                LinearExpr.weightedSum(intArgs(f.vars), f.coeffs.longs()),
                f.op,
                f.bound,
                boolVars[f.auxBoolVar],
            )

            is ReifiedPseudoBoolean -> reifyPb(
                LinearExpr.weightedSum(litArgs(f.literals), f.weights.longs()),
                f.op,
                f.bound,
                boolVars[f.auxBoolVar],
            )

            is ReifiedCardinality -> {
                val sum = LinearExpr.sum(litArgs(f.literals))
                val d = Domain(f.min.toLong(), f.max.toLong())
                val aux = boolVars[f.auxBoolVar]
                model.addLinearExpressionInDomain(sum, d).onlyEnforceIf(aux)
                model.addLinearExpressionInDomain(sum, d.complement()).onlyEnforceIf(aux.not())
            }

            is Element -> postElement(f)

            is Inverse -> postChannel(f.f, f.g, f.fOffset, f.gOffset)

            is SymmetricAllDifferent -> postChannel(f.xs, f.xs, f.indexOffset, f.indexOffset)

            is LexLess -> postLexLess(f)

            is NValue -> postNValue(f)

            is GlobalCardinality -> postGcc(f)

            is Table -> {
                val tc = model.addAllowedAssignments(intArgs(f.xs))
                for (r in 0 until f.numTuples) tc.addTuple(IntArray(f.arity) { c -> f.tuples[r * f.arity + c] })
            }

            is ArrayMinMax ->
                if (f.max) {
                    model.addMaxEquality(intVars[f.result], Array(f.xs.size) { intVars[f.xs[it]] })
                } else {
                    model.addMinEquality(intVars[f.result], Array(f.xs.size) { intVars[f.xs[it]] })
                }

            is Cumulative -> postCumulative(f)

            is Diffn -> postDiffn(f)

            is Sort -> postSort(f)

            is Regular -> postRegular(f)

            is Circuit -> postCircuit(f)

            is Subcircuit -> postSubcircuit(f)


            is Mdd -> postMdd(f)

            else -> throw UnsupportedFactorException(f)
        }
    }

    private fun postElement(f: Element) {
        // CP-SAT element is 0-based with no offset; shift the index expression.
        val idxExpr = LinearExpr.affine(intVars[f.idx], 1L, -f.indexOffset.toLong())
        if (f.arrIsVars) {
            model.addElement(idxExpr, intArgs(f.arr), intVars[f.result])
        } else {
            model.addElement(idxExpr, f.arr.longs(), intVars[f.result])
        }
    }

    /** `f[i] = j+fOff ⟺ g[j] = i+gOff` for all i, j (Inverse / SymmetricAllDifferent). */
    private fun postChannel(fArr: IntArray, gArr: IntArray, fOff: Int, gOff: Int) {
        if (fOff == 0 && gOff == 0 && fArr.size == gArr.size) {
            model.addInverse(Array(fArr.size) { intVars[fArr[it]] }, Array(gArr.size) { intVars[gArr[it]] })
            return
        }
        for (i in fArr.indices) {
            for (j in gArr.indices) {
                val a = reifyEq(intVars[fArr[i]], j + fOff)
                val b = reifyEq(intVars[gArr[j]], i + gOff)
                model.addImplication(a, b)
                model.addImplication(b, a)
            }
        }
    }

    private fun postLexLess(f: LexLess) {
        val n = minOf(f.xs.size, f.ys.size)
        val eq = Array(n) { reifyEqVar(intVars[f.xs[it]], intVars[f.ys[it]]) }
        for (i in 0 until n) {
            // Under "all earlier positions equal", require xs[i] <= ys[i]; the first strict
            // difference then decides the order.
            val c = model.addLessOrEqual(intVars[f.xs[i]], intVars[f.ys[i]])
            if (i > 0) c.onlyEnforceIf(Array(i) { eq[it] })
        }
        if (f.strict) model.addBoolOr(Array(n) { eq[it].not() })
    }

    private fun unionValues(ids: IntArray): IntArray {
        val s = sortedSetOf<Int>()
        for (id in ids) problem.intDomains[id].forEach { s.add(it) }
        return s.toIntArray()
    }

    private fun postNValue(f: NValue) {
        if (f.presents.isNotEmpty()) throw UnsupportedFactorException(f)
        val presentLits = ArrayList<LinearArgument>()
        for (v in unionValues(f.xs)) {
            val eqs = Array(f.xs.size) { reifyEq(intVars[f.xs[it]], v) }
            val present = freshBool()
            model.addBoolOr(eqs).onlyEnforceIf(present) // present ⇒ some xi = v
            for (e in eqs) model.addImplication(e, present) // some xi = v ⇒ present
            presentLits.add(present)
        }
        val distinct = LinearExpr.sum(presentLits.toTypedArray())
        when (f.mode) {
            NValue.Mode.Eq -> model.addEquality(intVars[f.n], distinct)
            NValue.Mode.AtLeast -> model.addLessOrEqual(intVars[f.n], distinct)
            NValue.Mode.AtMost -> model.addGreaterOrEqual(intVars[f.n], distinct)
        }
    }

    private fun postGcc(f: GlobalCardinality) {
        if (f.presents.isNotEmpty()) throw UnsupportedFactorException(f)
        val countVars = f.countVars
        val countLow = f.countLow
        val countHigh = f.countHigh
        for (k in f.cover.indices) {
            val matches = Array(f.xs.size) { reifyEq(intVars[f.xs[it]], f.cover[k]) as LinearArgument }
            val cnt = LinearExpr.sum(matches)
            when {
                countVars != null -> model.addEquality(intVars[countVars[k]], cnt)

                countLow != null && countHigh != null ->
                    model.addLinearConstraint(cnt, countLow[k].toLong(), countHigh[k].toLong())
            }
        }
        if (f.closed) {
            val d = Domain.fromValues(f.cover.longs())
            for (x in f.xs) model.addLinearExpressionInDomain(LinearExpr.term(intVars[x], 1), d)
        }
    }

    private fun postCumulative(f: Cumulative) {
        if (f.presents.isNotEmpty()) throw UnsupportedFactorException(f)
        // durations/resources/capacity are constants; their var forms live in the *Vars arrays
        // (empty = use the constant). Indexing intVars with the constant values is the #119 crash.
        val durVars = f.durationVars.takeIf { it.isNotEmpty() }
        val resVars = f.resourceVars.takeIf { it.isNotEmpty() }
        val capacity = if (f.capacityVar >= 0) intVars[f.capacityVar] else LinearExpr.constant(f.capacity.toLong())
        val cc = model.addCumulative(capacity)
        for (i in f.starts.indices) {
            val start = intVars[f.starts[i]]
            val sd = problem.intDomains[f.starts[i]]
            val interval = if (durVars != null) {
                val dur = intVars[durVars[i]]
                val dd = problem.intDomains[durVars[i]]
                val end = model.newIntVar((sd.min + dd.min).toLong(), (sd.max + dd.max).toLong(), "end$i")
                model.newIntervalVar(start, dur, end, "task$i")
            } else {
                val durConst = f.durations[i]
                val end = model.newIntVar((sd.min + durConst).toLong(), (sd.max + durConst).toLong(), "end$i")
                model.newIntervalVar(start, LinearExpr.constant(durConst.toLong()), end, "task$i")
            }
            val demand = if (resVars != null) intVars[resVars[i]] else LinearExpr.constant(f.resources[i].toLong())
            cc.addDemand(interval, demand)
        }
    }

    private fun postDiffn(f: Diffn) {
        val no = model.addNoOverlap2D()
        val widthVars = f.widthVars
        val heightVars = f.heightVars
        for (i in f.xs.indices) {
            val xInt = rectInterval(f.xs[i], widthVars?.get(i), f.widths.getOrElse(i) { 0 }, "x$i")
            val yInt = rectInterval(f.ys[i], heightVars?.get(i), f.heights.getOrElse(i) { 0 }, "y$i")
            no.addRectangle(xInt, yInt)
        }
    }

    /** Build an interval `[origin, origin+size)` where size is a var (if [sizeVarId]≠null)
     *  or the constant [sizeConst]. */
    private fun rectInterval(originId: Int, sizeVarId: Int?, sizeConst: Int, name: String): IntervalVar {
        val origin = intVars[originId]
        val od = problem.intDomains[originId]
        return if (sizeVarId != null) {
            val size = intVars[sizeVarId]
            val sd = problem.intDomains[sizeVarId]
            val end = model.newIntVar((od.min + sd.min).toLong(), (od.max + sd.max).toLong(), "${name}e")
            model.newIntervalVar(origin, size, end, name)
        } else {
            val end = model.newIntVar((od.min + sizeConst).toLong(), (od.max + sizeConst).toLong(), "${name}e")
            model.newIntervalVar(origin, LinearExpr.constant(sizeConst.toLong()), end, name)
        }
    }

    private fun postSort(f: Sort) {
        for (i in 0 until f.ys.size - 1) model.addLessOrEqual(intVars[f.ys[i]], intVars[f.ys[i + 1]])
        for (v in unionValues(f.xs + f.ys)) {
            val cx = LinearExpr.sum(Array(f.xs.size) { reifyEq(intVars[f.xs[it]], v) as LinearArgument })
            val cy = LinearExpr.sum(Array(f.ys.size) { reifyEq(intVars[f.ys[it]], v) as LinearArgument })
            model.addEquality(cx, cy)
        }
    }

    private fun postRegular(f: Regular) {
        // CP-SAT automaton: state ids and labels are arbitrary longs; klause states are
        // 1-based with a 0 "dead" sentinel and symbol == seq value.
        val ac = model.addAutomaton(intArgs(f.seq), f.q0.toLong(), f.accepting.longs())
        for (st in 1..f.numStates) {
            for (sym in 1..f.alphabetSize) {
                val target = f.transitions[(st - 1) * f.alphabetSize + (sym - 1)]
                if (target != 0) ac.addTransition(st, target, sym.toLong())
            }
        }
    }

    private fun postCircuit(f: Circuit) {
        val n = f.succ.size
        val cc = model.addCircuit()
        for (i in 0 until n) {
            if (n >= 2) model.addDifferent(intVars[f.succ[i]], i.toLong()) // no self-loop
            problem.intDomains[f.succ[i]].forEach { j ->
                if (j in 0 until n && j != i) cc.addArc(i, j, reifyEq(intVars[f.succ[i]], j))
            }
        }
    }

    private fun postSubcircuit(f: Subcircuit) {
        // Like Circuit but self-loop arcs are kept: succ[i] = i marks node i excluded, and
        // CP-SAT's circuit constraint treats a true self-loop literal as "node not in cycle".
        val n = f.succ.size
        val cc = model.addCircuit()
        for (i in 0 until n) {
            problem.intDomains[f.succ[i]].forEach { j ->
                if (j in 0 until n) cc.addArc(i, j, reifyEq(intVars[f.succ[i]], j))
            }
        }
    }


    private fun postMdd(f: Mdd) {
        val n = f.seq.size
        val states = sortedSetOf(f.initial)
        f.accepting.forEach { states.add(it) }
        var p = 0
        while (p < f.transitions.size) {
            states.add(f.transitions[p])
            states.add(f.transitions[p + 2])
            p += f.recordStride
        }
        val lo = states.first().toLong()
        val hi = states.last().toLong()
        val q = Array(n + 1) { model.newIntVar(lo, hi, "mddq$it") }
        model.addEquality(q[0], f.initial.toLong())
        val cost4 = f.recordStride == 4
        val wVars = if (cost4) ArrayList<LinearArgument>(n) else null
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
            val cols: Array<LinearArgument> =
                if (cost4) {
                    val w = model.newIntVar(wLo.toLong(), wHi.toLong(), "mddw$i")
                    requireNotNull(wVars).add(w)
                    arrayOf(q[i], intVars[f.seq[i]], q[i + 1], w)
                } else {
                    arrayOf(q[i], intVars[f.seq[i]], q[i + 1])
                }
            val tc = model.addAllowedAssignments(cols)
            var row = f.layerStarts[i]
            while (row < f.layerStarts[i + 1]) {
                tc.addTuple(
                    if (cost4) {
                        intArrayOf(
                            f.transitions[row],
                            f.transitions[row + 1],
                            f.transitions[row + 2],
                            f.transitions[row + 3],
                        )
                    } else {
                        intArrayOf(f.transitions[row], f.transitions[row + 1], f.transitions[row + 2])
                    },
                )
                row += f.recordStride
            }
        }
        model.addLinearExpressionInDomain(LinearExpr.term(q[n], 1), Domain.fromValues(f.accepting.longs()))
        if (f.cost >= 0 && wVars != null) model.addEquality(intVars[f.cost], LinearExpr.sum(wVars.toTypedArray()))
    }

    private fun postLinearDomain(expr: LinearExpr, domain: Domain) {
        model.addLinearExpressionInDomain(expr, domain)
    }

    private fun reifyLinear(expr: LinearExpr, op: LinearOp, bound: Int, aux: BoolVar) {
        val d = domainFor(op, bound)
        model.addLinearExpressionInDomain(expr, d).onlyEnforceIf(aux)
        model.addLinearExpressionInDomain(expr, d.complement()).onlyEnforceIf(aux.not())
    }

    private fun reifyPb(expr: LinearExpr, op: PbOp, bound: Int, aux: BoolVar) {
        val d = domainFor(op, bound)
        model.addLinearExpressionInDomain(expr, d).onlyEnforceIf(aux)
        model.addLinearExpressionInDomain(expr, d.complement()).onlyEnforceIf(aux.not())
    }

    /** Factory for building an [OrToolsModel] from a klause [Problem]. */
    companion object {
        private const val NEG = -1_000_000_000L
        private const val POS = 1_000_000_000L

        @Volatile private var loaded = false

        /** Load the OR-Tools JNI libraries once, before any native object is constructed. */
        fun ensureNativeLoaded() {
            if (!loaded) {
                synchronized(this) {
                    if (!loaded) {
                        Loader.loadNativeLibraries()
                        loaded = true
                    }
                }
            }
        }

        /** Translate [problem] into an [OrToolsModel] by posting every factor as a CP-SAT constraint. */
        fun build(problem: Problem): OrToolsModel {
            ensureNativeLoaded()
            val model = CpModel()
            val boolVars = Array(problem.numBoolVars) { model.newBoolVar("b$it") }
            val intVars = Array(problem.numIntVars) { i ->
                val d = problem.intDomains[i]
                if (d.size == d.max - d.min + 1) {
                    model.newIntVar(d.min.toLong(), d.max.toLong(), "i$i")
                } else {
                    val values = ArrayList<Long>(d.size)
                    d.forEach { values.add(it.toLong()) }
                    model.newIntVarFromDomain(Domain.fromValues(values.toLongArray()), "i$i")
                }
            }
            val m = OrToolsModel(problem, model, boolVars, intVars)
            for (f in problem.factors) m.postFactor(f)
            return m
        }

        private fun domainFor(op: LinearOp, bound: Int): Domain = when (op) {
            LinearOp.LE -> Domain(NEG, bound.toLong())
            LinearOp.GE -> Domain(bound.toLong(), POS)
            LinearOp.EQ -> Domain(bound.toLong(), bound.toLong())
            LinearOp.NE -> Domain(bound.toLong(), bound.toLong()).complement()
        }

        private fun domainFor(op: PbOp, bound: Int): Domain = when (op) {
            PbOp.LE -> Domain(NEG, bound.toLong())
            PbOp.GE -> Domain(bound.toLong(), POS)
            PbOp.EQ -> Domain(bound.toLong(), bound.toLong())
        }
    }
}
