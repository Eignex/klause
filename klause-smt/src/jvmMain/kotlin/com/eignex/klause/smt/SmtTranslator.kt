package com.eignex.klause.smt

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.RealLinearConstraint
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.AllDifferentExcept
import com.eignex.klause.solver.factor.AllDifferentExceptZero
import com.eignex.klause.solver.factor.AllEqual
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
import com.eignex.klause.solver.factor.Geost
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.Knapsack
import com.eignex.klause.solver.factor.LexLess
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Mdd
import com.eignex.klause.solver.factor.Member
import com.eignex.klause.solver.factor.Monotone
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
import com.eignex.klause.solver.factor.SymmetricAllDifferent
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.factor.ValuePrecede
import com.eignex.klause.solver.factor.Xor
import org.sosy_lab.java_smt.api.BooleanFormula
import org.sosy_lab.java_smt.api.FormulaManager
import org.sosy_lab.java_smt.api.NumeralFormula.IntegerFormula
import org.sosy_lab.java_smt.api.NumeralFormula.RationalFormula

/**
 * Translated klause [Problem] in JavaSMT formulas. Mirrors the discontinued
 * `Z3Encoding` shape so the rest of the code reads similarly.
 */
internal class SmtEncoding(
    val fm: FormulaManager,
    val boolFormulas: Array<BooleanFormula>,
    val intFormulas: Array<IntegerFormula>,
    val realFormulas: Array<RationalFormula> = emptyArray(),
)

/**
 * Translation result: variable encoding plus the formulas to assert, split into
 * [auxiliary] (var domains, real-link bookkeeping — never appear in unsat cores) and
 * [factorFormulas] (parallel to
 * [com.eignex.klause.solver.Problem.factors] in id order). The split lets `solve`
 * track only factor-derived assertions when an unsat core is requested.
 */
internal class SmtTranslation(
    val encoding: SmtEncoding,
    val auxiliary: List<BooleanFormula>,
    val factorFormulas: List<BooleanFormula>,
) {
    fun allConstraints(): List<BooleanFormula> = auxiliary + factorFormulas
}

/**
 * Direct (non-bit-blasted) SMT translation of a klause [Problem] to JavaSMT formulas,
 * expressed against JavaSMT's solver-agnostic API so any compatible backend
 * (SMTInterpol default, Z3 / CVC5 / MathSAT5 / Bitwuzla / Yices2 if their natives are
 * present) can consume it.
 *
 * As a correctness/comparison reference the translator aims for *breadth*: every global
 * factor is decomposed into the quantifier-free linear-integer-arithmetic + ITE fragment
 * (plus `distinct`) that every JavaSMT backend understands. Decompositions favour clarity
 * and obvious soundness over propagation strength — the SMT core does the search.
 */
internal object SmtTranslator {

    fun translate(problem: Problem, fm: FormulaManager): SmtTranslation {
        val bmgr = fm.booleanFormulaManager
        val imgr = fm.integerFormulaManager
        val rmgr = fm.rationalFormulaManager

        val boolFormulas = Array(problem.numBoolVars) { i -> bmgr.makeVariable("b$i") }
        val intFormulas = Array(problem.numIntVars) { i -> imgr.makeVariable("i$i") }
        val meta = problem.floatMetadata
        val realFormulas =
            if (meta == null) {
                emptyArray()
            } else {
                Array(meta.numFloatVars) { i -> rmgr.makeVariable("r$i") }
            }

        val encoding = SmtEncoding(fm, boolFormulas, intFormulas, realFormulas)
        val auxiliary = ArrayList<BooleanFormula>()

        // Int-domain bounds — auxiliary, never load-bearing in user-facing unsat cores.
        for (i in 0 until problem.numIntVars) {
            val d = problem.intDomains[i]
            auxiliary.add(
                bmgr.and(
                    imgr.greaterOrEquals(intFormulas[i], imgr.makeNumber(d.min.toLong())),
                    imgr.lessOrEquals(intFormulas[i], imgr.makeNumber(d.max.toLong())),
                ),
            )
        }
        // Native-real domain constraints + bucket linkage — auxiliary.
        if (meta != null) {
            for (i in 0 until meta.numFloatVars) {
                val ivl = meta.intervals[i]
                auxiliary.add(
                    bmgr.and(
                        rmgr.greaterOrEquals(realFormulas[i], rmgr.makeNumber(ivl.lo)),
                        rmgr.lessOrEquals(realFormulas[i], rmgr.makeNumber(ivl.hi)),
                    ),
                )
                val buckets = meta.bucketCounts[i]
                val step = if (buckets > 1) (ivl.hi - ivl.lo) / (buckets - 1) else 0.0
                val intVar = intFormulas[meta.intVarByFloatVar[i]]
                // real = lo + step * bucket_index — anchors the bucket to a real value.
                val linked = rmgr.add(
                    rmgr.makeNumber(ivl.lo),
                    rmgr.multiply(rmgr.makeNumber(step), intVar),
                )
                auxiliary.add(rmgr.equal(realFormulas[i], linked))
            }
            for (c in meta.constraints) {
                auxiliary.add(translateRealLinear(c, encoding))
            }
        }
        val enc = FactorEncoder(problem, encoding)
        val factorFormulas = ArrayList<BooleanFormula>(problem.factors.size)
        for (factor in problem.factors) {
            factorFormulas.add(enc.translateFactor(factor))
        }
        return SmtTranslation(encoding, auxiliary, factorFormulas)
    }

    private fun translateRealLinear(c: RealLinearConstraint, e: SmtEncoding): BooleanFormula {
        val rmgr = e.fm.rationalFormulaManager
        val terms = c.coeffs.mapIndexed { idx, coeff ->
            rmgr.multiply(rmgr.makeNumber(coeff), e.realFormulas[c.floatVarIds[idx]])
        }
        val sum = rmgr.sum(terms)
        val bound = rmgr.makeNumber(c.bound)
        return when (c.op) {
            // strict carries an original float `<` / `>` that LinearOp cannot represent (#83).
            LinearOp.LE -> if (c.strict) rmgr.lessThan(sum, bound) else rmgr.lessOrEquals(sum, bound)
            LinearOp.EQ -> rmgr.equal(sum, bound)
            LinearOp.GE -> if (c.strict) rmgr.greaterThan(sum, bound) else rmgr.greaterOrEquals(sum, bound)
            LinearOp.NE -> e.fm.booleanFormulaManager.not(rmgr.equal(sum, bound))
        }
    }

    /**
     * Per-translation encoder. Holds the [encoding] plus a fresh-variable counter so
     * decompositions that need auxiliary integers / booleans (Regular state vars, Circuit
     * order vars, …) can mint uniquely-named ones. Auxiliary vars are existentially
     * quantified simply by being free symbols the SMT core may assign freely.
     */
    private class FactorEncoder(val problem: Problem, val e: SmtEncoding) {
        private val bmgr = e.fm.booleanFormulaManager
        private val imgr = e.fm.integerFormulaManager
        private var freshId = 0

        private fun freshInt(): IntegerFormula = imgr.makeVariable("aux_i${freshId++}")
        private fun num(n: Int): IntegerFormula = imgr.makeNumber(n.toLong())
        private fun iv(id: Int): IntegerFormula = e.intFormulas[id]
        private fun eqN(x: IntegerFormula, n: Int): BooleanFormula = imgr.equal(x, num(n))
        private fun eqV(a: IntegerFormula, b: IntegerFormula): BooleanFormula = imgr.equal(a, b)

        /** `0/1` integer for a truth value, used to build linear counting sums. */
        private fun oneIf(cond: BooleanFormula): IntegerFormula = bmgr.ifThenElse(cond, num(1), num(0))

        /** `x ∈ values` as a disjunction of equalities. */
        private fun inValues(x: IntegerFormula, values: IntArray): BooleanFormula =
            if (values.isEmpty()) bmgr.makeFalse() else bmgr.or(values.map { eqN(x, it) })

        /** Strict less-than `a < b` (no native strict op): `¬(a ≥ b)`. */
        private fun lt(a: IntegerFormula, b: IntegerFormula): BooleanFormula = bmgr.not(imgr.greaterOrEquals(a, b))

        fun translateFactor(factor: Factor): BooleanFormula = when (factor) {
            is Clause -> bmgr.or(factor.literals.map { litFormula(it) })

            is Cardinality -> {
                val sum = sumOfLitInts(factor.literals)
                bmgr.and(
                    imgr.greaterOrEquals(sum, num(factor.min)),
                    imgr.lessOrEquals(sum, num(factor.max)),
                )
            }

            is Linear -> opLinear(weightedIntSum(factor.coeffs, factor.vars), factor.op, factor.bound)

            is PseudoBoolean -> opPb(weightedLitSum(factor.weights, factor.literals), factor.op, factor.bound)

            is Xor -> {
                var acc: BooleanFormula = bmgr.makeFalse()
                for (lit in factor.literals) acc = bmgr.xor(acc, litFormula(lit))
                if (factor.targetParity == 1) acc else bmgr.not(acc)
            }

            is AllDifferent -> imgr.distinct(factor.vars.map { iv(it) })

            is Product -> imgr.equal(iv(factor.result), imgr.multiply(iv(factor.a), iv(factor.b)))

            is ReifiedLinear ->
                bmgr.equivalence(
                    e.boolFormulas[factor.auxBoolVar],
                    opLinear(weightedIntSum(factor.coeffs, factor.vars), factor.op, factor.bound),
                )

            is ReifiedPseudoBoolean ->
                bmgr.equivalence(
                    e.boolFormulas[factor.auxBoolVar],
                    opPb(weightedLitSum(factor.weights, factor.literals), factor.op, factor.bound),
                )

            is ReifiedCardinality -> {
                val sum = sumOfLitInts(factor.literals)
                bmgr.equivalence(
                    e.boolFormulas[factor.auxBoolVar],
                    bmgr.and(
                        imgr.greaterOrEquals(sum, num(factor.min)),
                        imgr.lessOrEquals(sum, num(factor.max)),
                    ),
                )
            }

            // ---- expanded coverage ----
            is AllEqual -> {
                val first = iv(factor.xs[0])
                bmgr.and(factor.xs.drop(1).map { eqV(first, iv(it)) })
            }

            is AllDifferentExceptZero -> distinctExcept(factor.xs, intArrayOf(0))

            is AllDifferentExcept -> distinctExcept(factor.xs, factor.except)

            is Member -> bmgr.or(factor.xs.map { eqV(iv(factor.y), iv(it)) })

            is Among -> eqV(iv(factor.n), imgr.sum(factor.xs.map { oneIf(inValues(iv(it), factor.values)) }))

            is Count -> translateCount(factor)

            is Element -> translateElement(factor)

            is Inverse -> channel(factor.f, factor.g, factor.fOffset, factor.gOffset)

            is SymmetricAllDifferent -> channel(factor.xs, factor.xs, factor.indexOffset, factor.indexOffset)

            is LexLess -> lexLess(factor.xs, factor.ys, factor.strict)

            is Monotone -> monotone(factor)

            is NValue -> translateNValue(factor)

            is GlobalCardinality -> translateGcc(factor)

            is Table -> translateTable(factor)

            is ValuePrecede -> valuePrecede(factor)

            is ArrayMinMax -> arrayMinMax(factor)

            is ArgMinMax -> argMinMax(factor)

            is Knapsack -> bmgr.and(
                eqV(
                    iv(factor.w),
                    imgr.sum(factor.xs.indices.map { imgr.multiply(num(factor.weights[it]), iv(factor.xs[it])) }),
                ),
                eqV(
                    iv(factor.p),
                    imgr.sum(factor.xs.indices.map { imgr.multiply(num(factor.profits[it]), iv(factor.xs[it])) }),
                ),
            )

            is Cumulative -> cumulative(factor)

            is Diffn -> diffn(factor)

            is BinPacking -> binPacking(factor)

            is Sort -> sort(factor)

            is Sequence -> sequence(factor)

            is Regular -> regular(factor)

            is Circuit -> circuit(factor)

            is Subcircuit -> subcircuit(factor)

            is Geost -> geost(factor)

            is Mdd -> mdd(factor)

            is SetBitsetSubset -> setSubset(factor.leftBools, factor.rightBools)

            is SetBitsetDisjoint -> setDisjoint(factor.leftBools, factor.rightBools)

            is SetBitsetEq -> setEq(factor.leftBools, factor.rightBools)

            else -> error("SmtTranslator: unsupported factor type ${factor::class.simpleName}")
        }

        /** `distinct` over [xs] except that any pair where one side ∈ [except] is exempt. */
        private fun distinctExcept(xs: IntArray, except: IntArray): BooleanFormula {
            val conj = ArrayList<BooleanFormula>()
            for (i in xs.indices) {
                for (j in i + 1 until xs.size) {
                    conj.add(
                        bmgr.or(
                            inValues(iv(xs[i]), except),
                            inValues(iv(xs[j]), except),
                            bmgr.not(eqV(iv(xs[i]), iv(xs[j]))),
                        ),
                    )
                }
            }
            return if (conj.isEmpty()) bmgr.makeTrue() else bmgr.and(conj)
        }

        private fun translateCount(f: Count): BooleanFormula {
            // #{i : xs[i] = v} ⟨op⟩ n. Presence-aware when per-index literals are supplied.
            val terms = f.xs.indices.map { idx ->
                val matches = eqN(iv(f.xs[idx]), f.v)
                if (f.presents.isEmpty()) {
                    oneIf(matches)
                } else {
                    oneIf(bmgr.and(litFormula(f.presents[idx]), matches))
                }
            }
            val cnt = imgr.sum(terms)
            val n = num(f.n)
            return when (f.op) {
                Count.Op.Eq -> imgr.equal(cnt, n)
                Count.Op.Ne -> bmgr.not(imgr.equal(cnt, n))
                Count.Op.Le -> imgr.lessOrEquals(cnt, n)
                Count.Op.Lt -> imgr.lessOrEquals(cnt, num(f.n - 1))
                Count.Op.Ge -> imgr.greaterOrEquals(cnt, n)
                Count.Op.Gt -> imgr.greaterOrEquals(cnt, num(f.n + 1))
            }
        }

        private fun translateElement(f: Element): BooleanFormula {
            // result = arr[idx - indexOffset]; the disjunction also confines idx to a slot.
            val disj = f.arr.indices.map { p ->
                val cell = if (f.arrIsVars) iv(f.arr[p]) else num(f.arr[p])
                bmgr.and(eqN(iv(f.idx), p + f.indexOffset), eqV(iv(f.result), cell))
            }
            return bmgr.or(disj)
        }

        /** `f[i] = j+fOff ⟺ g[j] = i+gOff` for all i, j — channels [f] and [g] (Inverse). */
        private fun channel(f: IntArray, g: IntArray, fOff: Int, gOff: Int): BooleanFormula {
            val conj = ArrayList<BooleanFormula>()
            for (i in f.indices) {
                for (j in g.indices) {
                    conj.add(bmgr.equivalence(eqN(iv(f[i]), j + fOff), eqN(iv(g[j]), i + gOff)))
                }
            }
            return if (conj.isEmpty()) bmgr.makeTrue() else bmgr.and(conj)
        }

        private fun lexLess(xs: IntArray, ys: IntArray, strict: Boolean): BooleanFormula {
            val n = minOf(xs.size, ys.size)
            // Fold from the tail: acc(i) = (x_i < y_i) ∨ (x_i = y_i ∧ acc(i+1)).
            var acc: BooleanFormula = if (strict) bmgr.makeFalse() else bmgr.makeTrue()
            for (i in n - 1 downTo 0) {
                acc = bmgr.or(
                    lt(iv(xs[i]), iv(ys[i])),
                    bmgr.and(eqV(iv(xs[i]), iv(ys[i])), acc),
                )
            }
            return acc
        }

        private fun monotone(f: Monotone): BooleanFormula {
            val conj = ArrayList<BooleanFormula>()
            for (i in 0 until f.xs.size - 1) {
                val a = iv(f.xs[i])
                val b = iv(f.xs[i + 1])
                conj.add(
                    when (f.direction) {
                        Monotone.Direction.Increasing -> if (f.strict) lt(a, b) else imgr.lessOrEquals(a, b)
                        Monotone.Direction.Decreasing -> if (f.strict) lt(b, a) else imgr.greaterOrEquals(a, b)
                    },
                )
            }
            return if (conj.isEmpty()) bmgr.makeTrue() else bmgr.and(conj)
        }

        private fun translateNValue(f: NValue): BooleanFormula {
            // distinct-count = Σ_i [xs[i] is the first occurrence of its value].
            val terms = f.xs.indices.map { i ->
                val firstOcc = if (i == 0) {
                    bmgr.makeTrue()
                } else {
                    bmgr.and((0 until i).map { j -> bmgr.not(eqV(iv(f.xs[i]), iv(f.xs[j]))) })
                }
                oneIf(firstOcc)
            }
            val distinct = imgr.sum(terms)
            return when (f.mode) {
                NValue.Mode.Eq -> imgr.equal(iv(f.n), distinct)
                NValue.Mode.AtLeast -> imgr.lessOrEquals(iv(f.n), distinct)
                NValue.Mode.AtMost -> imgr.greaterOrEquals(iv(f.n), distinct)
            }
        }

        private fun translateGcc(f: GlobalCardinality): BooleanFormula {
            val conj = ArrayList<BooleanFormula>()
            val countVars = f.countVars
            val countLow = f.countLow
            val countHigh = f.countHigh
            for (k in f.cover.indices) {
                val cnt = imgr.sum(f.xs.map { oneIf(eqN(iv(it), f.cover[k])) })
                when {
                    countVars != null -> conj.add(imgr.equal(iv(countVars[k]), cnt))

                    countLow != null && countHigh != null -> conj.add(
                        bmgr.and(
                            imgr.greaterOrEquals(cnt, num(countLow[k])),
                            imgr.lessOrEquals(cnt, num(countHigh[k])),
                        ),
                    )
                }
            }
            if (f.closed) for (x in f.xs) conj.add(inValues(iv(x), f.cover))
            return if (conj.isEmpty()) bmgr.makeTrue() else bmgr.and(conj)
        }

        private fun translateTable(f: Table): BooleanFormula {
            val rows = (0 until f.numTuples).map { r ->
                bmgr.and(f.xs.indices.map { c -> eqN(iv(f.xs[c]), f.tuples[r * f.arity + c]) })
            }
            return if (rows.isEmpty()) bmgr.makeFalse() else bmgr.or(rows)
        }

        private fun valuePrecede(f: ValuePrecede): BooleanFormula {
            // Whenever xs[i] = t, some earlier xs[j] = s. `seen` = "s appeared before i".
            val conj = ArrayList<BooleanFormula>()
            var seen: BooleanFormula = bmgr.makeFalse()
            for (i in f.xs.indices) {
                conj.add(bmgr.implication(eqN(iv(f.xs[i]), f.t), seen))
                seen = bmgr.or(seen, eqN(iv(f.xs[i]), f.s))
            }
            return bmgr.and(conj)
        }

        private fun arrayMinMax(f: ArrayMinMax): BooleanFormula {
            val r = iv(f.result)
            val bounds = f.xs.map { if (f.max) imgr.greaterOrEquals(r, iv(it)) else imgr.lessOrEquals(r, iv(it)) }
            val attained = bmgr.or(f.xs.map { eqV(r, iv(it)) })
            return bmgr.and(bmgr.and(bounds), attained)
        }

        private fun argMinMax(f: ArgMinMax): BooleanFormula {
            // idx = the lowest position p that attains the extreme value: extreme at p, and
            // strictly beaten by no earlier position (lowest-index tie-break).
            val disj = f.xs.indices.map { p ->
                val xp = iv(f.xs[p])
                val isExtreme = f.xs.indices.filter { it != p }.map {
                    if (f.max) imgr.greaterOrEquals(xp, iv(f.xs[it])) else imgr.lessOrEquals(xp, iv(f.xs[it]))
                }
                val strictlyFirst = (0 until p).map {
                    if (f.max) lt(iv(f.xs[it]), xp) else lt(xp, iv(f.xs[it]))
                }
                bmgr.and(listOf(eqN(iv(f.idx), p + f.indexOffset)) + isExtreme + strictlyFirst)
            }
            return bmgr.or(disj)
        }

        private fun cumulative(f: Cumulative): BooleanFormula {
            // Capacity respected at the start time of every task: Σ height_j over tasks j
            // running at start_i ≤ capacity.
            val conj = f.starts.indices.map { i ->
                val si = iv(f.starts[i])
                val load = imgr.sum(
                    f.starts.indices.map { j ->
                        val sj = iv(f.starts[j])
                        val dj = iv(f.durations[j])
                        val running = bmgr.and(imgr.lessOrEquals(sj, si), lt(si, imgr.add(sj, dj)))
                        bmgr.ifThenElse(running, iv(f.resources[j]), num(0))
                    },
                )
                imgr.lessOrEquals(load, num(f.capacity))
            }
            return bmgr.and(conj)
        }

        private fun diffn(f: Diffn): BooleanFormula {
            val widthVars = f.widthVars
            val heightVars = f.heightVars
            fun w(i: Int): IntegerFormula = if (widthVars != null) iv(widthVars[i]) else num(f.widths[i])
            fun h(i: Int): IntegerFormula = if (heightVars != null) iv(heightVars[i]) else num(f.heights[i])
            val conj = ArrayList<BooleanFormula>()
            for (i in f.xs.indices) {
                for (j in i + 1 until f.xs.size) {
                    conj.add(
                        bmgr.or(
                            imgr.lessOrEquals(imgr.add(iv(f.xs[i]), w(i)), iv(f.xs[j])),
                            imgr.lessOrEquals(imgr.add(iv(f.xs[j]), w(j)), iv(f.xs[i])),
                            imgr.lessOrEquals(imgr.add(iv(f.ys[i]), h(i)), iv(f.ys[j])),
                            imgr.lessOrEquals(imgr.add(iv(f.ys[j]), h(j)), iv(f.ys[i])),
                        ),
                    )
                }
            }
            return if (conj.isEmpty()) bmgr.makeTrue() else bmgr.and(conj)
        }

        private fun binPacking(f: BinPacking): BooleanFormula {
            val conj = ArrayList<BooleanFormula>()
            for (b in 0 until f.numBins) {
                val load = imgr.sum(
                    f.bins.indices.map { i ->
                        bmgr.ifThenElse(eqN(iv(f.bins[i]), b + f.binOffset), num(f.weights[i]), num(0))
                    },
                )
                when (f.mode) {
                    BinPacking.Mode.LoadVars -> conj.add(imgr.equal(iv(requireNotNull(f.loadVars)[b]), load))

                    BinPacking.Mode.UniformCapacity -> conj.add(imgr.lessOrEquals(load, num(f.uniformCapacity)))

                    BinPacking.Mode.PerBinCapacity -> conj.add(
                        imgr.lessOrEquals(load, num(requireNotNull(f.capacities)[b])),
                    )
                }
            }
            return bmgr.and(conj)
        }

        private fun sort(f: Sort): BooleanFormula {
            val conj = ArrayList<BooleanFormula>()
            // ys non-decreasing.
            for (i in 0 until f.ys.size - 1) conj.add(imgr.lessOrEquals(iv(f.ys[i]), iv(f.ys[i + 1])))
            // ys is a permutation of xs: equal occurrence counts over the union of domains.
            val values = sortedSetOf<Int>()
            for (id in f.xs + f.ys) problem.intDomains[id].forEach { values.add(it) }
            for (v in values) {
                val cx = imgr.sum(f.xs.map { oneIf(eqN(iv(it), v)) })
                val cy = imgr.sum(f.ys.map { oneIf(eqN(iv(it), v)) })
                conj.add(imgr.equal(cx, cy))
            }
            return bmgr.and(conj)
        }

        private fun sequence(f: Sequence): BooleanFormula {
            val conj = ArrayList<BooleanFormula>()
            for (start in 0..f.xs.size - f.k) {
                val cnt = imgr.sum((start until start + f.k).map { oneIf(inValues(iv(f.xs[it]), f.values)) })
                conj.add(bmgr.and(imgr.greaterOrEquals(cnt, num(f.low)), imgr.lessOrEquals(cnt, num(f.high))))
            }
            return bmgr.and(conj)
        }

        private fun regular(f: Regular): BooleanFormula {
            // State trace q[0..n]; q[0] = q0; for each position pick a (state, symbol) pair
            // whose transition is live (≠ 0) and advance. q[n] must be accepting.
            // Symbols are 1-based: symbol s corresponds to seq value s.
            val n = f.seq.size
            val q = Array(n + 1) { freshInt() }
            val conj = ArrayList<BooleanFormula>()
            conj.add(eqN(q[0], f.q0))
            for (i in 0 until n) {
                val steps = ArrayList<BooleanFormula>()
                for (st in 1..f.numStates) {
                    for (sym in 1..f.alphabetSize) {
                        val target = f.transitions[(st - 1) * f.alphabetSize + (sym - 1)]
                        if (target == 0) continue
                        steps.add(bmgr.and(eqN(q[i], st), eqN(iv(f.seq[i]), sym), eqN(q[i + 1], target)))
                    }
                }
                conj.add(if (steps.isEmpty()) bmgr.makeFalse() else bmgr.or(steps))
            }
            conj.add(inValues(q[n], f.accepting))
            return bmgr.and(conj)
        }

        private fun circuit(f: Circuit): BooleanFormula {
            // succ[i] = j means node j follows node i (0-based, values in [0,n)). A single
            // Hamiltonian cycle = all-different successors + MTZ order labels forbidding
            // subtours: order[0]=0 and succ[i]=j (j≠0) ⇒ order[j]=order[i]+1.
            val n = f.succ.size
            val conj = ArrayList<BooleanFormula>()
            conj.add(imgr.distinct(f.succ.map { iv(it) }))
            if (n >= 2) for (i in 0 until n) conj.add(bmgr.not(eqN(iv(f.succ[i]), i)))
            val order = Array(n) { freshInt() }
            conj.add(eqN(order[0], 0))
            for (i in 0 until n) {
                conj.add(imgr.greaterOrEquals(order[i], num(0)))
                conj.add(imgr.lessOrEquals(order[i], num(n - 1)))
            }
            for (i in 0 until n) {
                for (j in 1 until n) {
                    conj.add(bmgr.implication(eqN(iv(f.succ[i]), j), imgr.equal(order[j], imgr.add(order[i], num(1)))))
                }
            }
            return bmgr.and(conj)
        }

        private fun freshBool(): BooleanFormula = bmgr.makeVariable("aux_b${freshId++}")
        private fun bvar(id: Int): BooleanFormula = e.boolFormulas[id]

        private fun subcircuit(f: Subcircuit): BooleanFormula {
            // succ is always a permutation (each node has one successor; excluded nodes
            // self-loop). The included nodes (succ[i] ≠ i) must form a single cycle. MTZ with
            // a chosen root: exactly one root among the included nodes has order 0, every other
            // included node's order is its predecessor's +1, and the sole back-edge into the
            // root is the only place the increasing chain may close — so no sub-cycle can form.
            val n = f.succ.size
            val conj = ArrayList<BooleanFormula>()
            conj.add(imgr.distinct(f.succ.map { iv(it) }))
            val incl = Array(n) { i -> bmgr.not(eqN(iv(f.succ[i]), i)) }
            val isRoot = Array(n) { freshBool() }
            val order = Array(n) { freshInt() }
            for (i in 0 until n) {
                conj.add(bmgr.implication(isRoot[i], incl[i]))
                conj.add(bmgr.implication(isRoot[i], eqN(order[i], 0)))
                conj.add(imgr.greaterOrEquals(order[i], num(0)))
                conj.add(imgr.lessOrEquals(order[i], num(n - 1)))
            }
            for (i in 0 until n) for (j in i + 1 until n) conj.add(bmgr.or(bmgr.not(isRoot[i]), bmgr.not(isRoot[j])))
            val anyRoot = bmgr.or((0 until n).map { isRoot[it] })
            for (i in 0 until n) {
                conj.add(bmgr.implication(incl[i], anyRoot))
                conj.add(
                    bmgr.implication(bmgr.and(incl[i], bmgr.not(isRoot[i])), imgr.greaterOrEquals(order[i], num(1))),
                )
                for (j in 0 until n) {
                    if (j != i) {
                        val cond = bmgr.and(incl[i], eqN(iv(f.succ[i]), j), bmgr.not(isRoot[j]))
                        conj.add(bmgr.implication(cond, imgr.equal(order[j], imgr.add(order[i], num(1)))))
                    }
                }
            }
            return bmgr.and(conj)
        }

        private fun geost(f: Geost): BooleanFormula {
            // Axis-aligned boxes pairwise separated in at least one dimension.
            val conj = ArrayList<BooleanFormula>()
            for (i in 0 until f.numObjects) {
                for (j in i + 1 until f.numObjects) {
                    val opts = ArrayList<BooleanFormula>()
                    for (d in 0 until f.numDims) {
                        val oi = iv(f.origin[i * f.numDims + d])
                        val oj = iv(f.origin[j * f.numDims + d])
                        val si = f.length[i * f.numDims + d]
                        val sj = f.length[j * f.numDims + d]
                        opts.add(imgr.lessOrEquals(imgr.add(oi, num(si)), oj))
                        opts.add(imgr.lessOrEquals(imgr.add(oj, num(sj)), oi))
                    }
                    conj.add(bmgr.or(opts))
                }
            }
            return if (conj.isEmpty()) bmgr.makeTrue() else bmgr.and(conj)
        }

        private fun mdd(f: Mdd): BooleanFormula {
            // Layered MDD acceptance: pick one transition row per layer that links the state
            // trace and matches the symbol; q[n] must be accepting. Cost = Σ chosen weights.
            val n = f.seq.size
            val q = Array(n + 1) { freshInt() }
            val conj = ArrayList<BooleanFormula>()
            conj.add(eqN(q[0], f.initial))
            val costTerms = ArrayList<IntegerFormula>()
            for (i in 0 until n) {
                val disj = ArrayList<BooleanFormula>()
                val wsel = if (f.recordStride == 4) freshInt().also { costTerms.add(it) } else null
                var p = f.layerStarts[i]
                while (p < f.layerStarts[i + 1]) {
                    val parts = arrayListOf(
                        eqN(q[i], f.transitions[p]),
                        eqN(iv(f.seq[i]), f.transitions[p + 1]),
                        eqN(q[i + 1], f.transitions[p + 2]),
                    )
                    if (wsel != null) parts.add(imgr.equal(wsel, num(f.transitions[p + 3])))
                    disj.add(bmgr.and(parts))
                    p += f.recordStride
                }
                conj.add(if (disj.isEmpty()) bmgr.makeFalse() else bmgr.or(disj))
            }
            conj.add(inValues(q[n], f.accepting))
            if (f.cost >= 0) conj.add(imgr.equal(iv(f.cost), if (costTerms.isEmpty()) num(0) else imgr.sum(costTerms)))
            return bmgr.and(conj)
        }

        private fun setSubset(left: IntArray, right: IntArray): BooleanFormula {
            val conj = ArrayList<BooleanFormula>()
            for (i in left.indices) {
                val l = left[i]
                val r = right[i]
                if (l < 0) continue
                conj.add(if (r < 0) bmgr.not(bvar(l)) else bmgr.implication(bvar(l), bvar(r)))
            }
            return if (conj.isEmpty()) bmgr.makeTrue() else bmgr.and(conj)
        }

        private fun setDisjoint(left: IntArray, right: IntArray): BooleanFormula {
            val conj = ArrayList<BooleanFormula>()
            for (i in left.indices) {
                val l = left[i]
                val r = right[i]
                if (l >= 0 && r >= 0) conj.add(bmgr.or(bmgr.not(bvar(l)), bmgr.not(bvar(r))))
            }
            return if (conj.isEmpty()) bmgr.makeTrue() else bmgr.and(conj)
        }

        private fun setEq(left: IntArray, right: IntArray): BooleanFormula {
            val conj = ArrayList<BooleanFormula>()
            for (i in left.indices) {
                val l = left[i]
                val r = right[i]
                when {
                    l >= 0 && r >= 0 -> conj.add(bmgr.equivalence(bvar(l), bvar(r)))
                    l >= 0 -> conj.add(bmgr.not(bvar(l)))
                    r >= 0 -> conj.add(bmgr.not(bvar(r)))
                }
            }
            return if (conj.isEmpty()) bmgr.makeTrue() else bmgr.and(conj)
        }

        private fun litFormula(lit: Int): BooleanFormula {
            val v = e.boolFormulas[Lit.variable(lit)]
            return if (Lit.isPositive(lit)) v else bmgr.not(v)
        }

        private fun sumOfLitInts(literals: IntArray): IntegerFormula {
            if (literals.isEmpty()) return num(0)
            return imgr.sum(literals.map { litToInt(it) })
        }

        private fun weightedIntSum(coeffs: IntArray, vars: IntArray): IntegerFormula =
            imgr.sum(coeffs.indices.map { i -> imgr.multiply(num(coeffs[i]), iv(vars[i])) })

        private fun weightedLitSum(weights: IntArray, literals: IntArray): IntegerFormula =
            imgr.sum(weights.indices.map { i -> imgr.multiply(num(weights[i]), litToInt(literals[i])) })

        private fun litToInt(lit: Int): IntegerFormula = oneIf(litFormula(lit))

        private fun opLinear(sum: IntegerFormula, op: LinearOp, bound: Int): BooleanFormula {
            val b = num(bound)
            return when (op) {
                LinearOp.LE -> imgr.lessOrEquals(sum, b)
                LinearOp.EQ -> imgr.equal(sum, b)
                LinearOp.GE -> imgr.greaterOrEquals(sum, b)
                LinearOp.NE -> bmgr.not(imgr.equal(sum, b))
            }
        }

        private fun opPb(sum: IntegerFormula, op: PbOp, bound: Int): BooleanFormula {
            val b = num(bound)
            return when (op) {
                PbOp.LE -> imgr.lessOrEquals(sum, b)
                PbOp.GE -> imgr.greaterOrEquals(sum, b)
                PbOp.EQ -> imgr.equal(sum, b)
            }
        }
    }
}
