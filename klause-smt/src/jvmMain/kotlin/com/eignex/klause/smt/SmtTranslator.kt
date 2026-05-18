package com.eignex.klause.smt

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.RealLinearConstraint
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
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
 * Translation result mirroring [com.eignex.klause.z3.Z3Translation]: variable encoding
 * plus the formulas to assert, split into [auxiliary] (var domains, real-link
 * bookkeeping — never appear in unsat cores) and [factorFormulas] (parallel to
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
 * Direct (non-bit-blasted) SMT translation of a klause [Problem] to JavaSMT formulas.
 * Same factor-by-factor mapping as the discontinued `klause-z3` path, but expressed
 * against JavaSMT's solver-agnostic API so any compatible backend (SMTInterpol default,
 * Z3 / CVC5 / MathSAT5 / Bitwuzla / Yices2 if their natives are present) can consume it.
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
            if (meta == null) emptyArray()
            else Array(meta.numFloatVars) { i -> rmgr.makeVariable("r$i") }

        val encoding = SmtEncoding(fm, boolFormulas, intFormulas, realFormulas)
        val auxiliary = ArrayList<BooleanFormula>()

        // Int-domain bounds — auxiliary, never load-bearing in user-facing unsat cores.
        for (i in 0 until problem.numIntVars) {
            val d = problem.intDomains[i]
            auxiliary.add(bmgr.and(
                imgr.greaterOrEquals(intFormulas[i], imgr.makeNumber(d.min.toLong())),
                imgr.lessOrEquals(intFormulas[i], imgr.makeNumber(d.max.toLong())),
            ))
        }
        // Native-real domain constraints + bucket linkage — auxiliary.
        if (meta != null) {
            for (i in 0 until meta.numFloatVars) {
                val ivl = meta.intervals[i]
                auxiliary.add(bmgr.and(
                    rmgr.greaterOrEquals(realFormulas[i], rmgr.makeNumber(ivl.lo)),
                    rmgr.lessOrEquals(realFormulas[i], rmgr.makeNumber(ivl.hi)),
                ))
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
        val factorFormulas = ArrayList<BooleanFormula>(problem.factors.size)
        for (factor in problem.factors) {
            factorFormulas.add(translateFactor(factor, encoding))
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
            LinearOp.LE -> rmgr.lessOrEquals(sum, bound)
            LinearOp.EQ -> rmgr.equal(sum, bound)
            LinearOp.GE -> rmgr.greaterOrEquals(sum, bound)
            LinearOp.NE -> e.fm.booleanFormulaManager.not(rmgr.equal(sum, bound))
        }
    }

    private fun translateFactor(factor: Factor, e: SmtEncoding): BooleanFormula {
        val bmgr = e.fm.booleanFormulaManager
        val imgr = e.fm.integerFormulaManager
        return when (factor) {
            is Clause -> bmgr.or(factor.literals.map { litFormula(it, e) })
            is Cardinality -> {
                val sum = sumOfLitInts(factor.literals, e)
                bmgr.and(
                    imgr.greaterOrEquals(sum, imgr.makeNumber(factor.min.toLong())),
                    imgr.lessOrEquals(sum, imgr.makeNumber(factor.max.toLong())),
                )
            }
            is Linear -> {
                val sum = weightedIntSum(factor.coeffs, factor.vars, e)
                opLinear(sum, factor.op, factor.bound, e)
            }
            is PseudoBoolean -> {
                val sum = weightedLitSum(factor.weights, factor.literals, e)
                opPb(sum, factor.op, factor.bound, e)
            }
            is Xor -> {
                // XOR is binary in JavaSMT; fold over the literals.
                var acc: BooleanFormula = bmgr.makeFalse()
                for (lit in factor.literals) acc = bmgr.xor(acc, litFormula(lit, e))
                if (factor.targetParity == 1) acc else bmgr.not(acc)
            }
            is AllDifferent -> imgr.distinct(factor.vars.map { e.intFormulas[it] })
            is Product -> imgr.equal(
                e.intFormulas[factor.result],
                imgr.multiply(e.intFormulas[factor.a], e.intFormulas[factor.b]),
            )
            is ReifiedLinear -> {
                val sum = weightedIntSum(factor.coeffs, factor.vars, e)
                bmgr.equivalence(e.boolFormulas[factor.auxBoolVar], opLinear(sum, factor.op, factor.bound, e))
            }
            is ReifiedPseudoBoolean -> {
                val sum = weightedLitSum(factor.weights, factor.literals, e)
                bmgr.equivalence(e.boolFormulas[factor.auxBoolVar], opPb(sum, factor.op, factor.bound, e))
            }
            is ReifiedCardinality -> {
                val sum = sumOfLitInts(factor.literals, e)
                val pred = bmgr.and(
                    imgr.greaterOrEquals(sum, imgr.makeNumber(factor.min.toLong())),
                    imgr.lessOrEquals(sum, imgr.makeNumber(factor.max.toLong())),
                )
                bmgr.equivalence(e.boolFormulas[factor.auxBoolVar], pred)
            }
            else -> error("SmtTranslator: unsupported factor type ${factor::class.simpleName}")
        }
    }

    private fun litFormula(lit: Int, e: SmtEncoding): BooleanFormula {
        val v = e.boolFormulas[Lit.variable(lit)]
        return if (Lit.isPositive(lit)) v else e.fm.booleanFormulaManager.not(v)
    }

    /** Σ over literals as 0/1 IntegerFormulas. */
    private fun sumOfLitInts(literals: IntArray, e: SmtEncoding): IntegerFormula {
        val imgr = e.fm.integerFormulaManager
        if (literals.isEmpty()) return imgr.makeNumber(0)
        return imgr.sum(literals.map { litToInt(it, e) })
    }

    private fun weightedIntSum(coeffs: IntArray, vars: IntArray, e: SmtEncoding): IntegerFormula {
        val imgr = e.fm.integerFormulaManager
        return imgr.sum(coeffs.indices.map { i ->
            imgr.multiply(imgr.makeNumber(coeffs[i].toLong()), e.intFormulas[vars[i]])
        })
    }

    private fun weightedLitSum(weights: IntArray, literals: IntArray, e: SmtEncoding): IntegerFormula {
        val imgr = e.fm.integerFormulaManager
        return imgr.sum(weights.indices.map { i ->
            imgr.multiply(imgr.makeNumber(weights[i].toLong()), litToInt(literals[i], e))
        })
    }

    private fun litToInt(lit: Int, e: SmtEncoding): IntegerFormula {
        val imgr = e.fm.integerFormulaManager
        val bmgr = e.fm.booleanFormulaManager
        val bool = litFormula(lit, e)
        return bmgr.ifThenElse(bool, imgr.makeNumber(1), imgr.makeNumber(0))
    }

    private fun opLinear(sum: IntegerFormula, op: LinearOp, bound: Int, e: SmtEncoding): BooleanFormula {
        val imgr = e.fm.integerFormulaManager
        val b = imgr.makeNumber(bound.toLong())
        return when (op) {
            LinearOp.LE -> imgr.lessOrEquals(sum, b)
            LinearOp.EQ -> imgr.equal(sum, b)
            LinearOp.GE -> imgr.greaterOrEquals(sum, b)
            LinearOp.NE -> e.fm.booleanFormulaManager.not(imgr.equal(sum, b))
        }
    }

    private fun opPb(sum: IntegerFormula, op: PbOp, bound: Int, e: SmtEncoding): BooleanFormula {
        val imgr = e.fm.integerFormulaManager
        val b = imgr.makeNumber(bound.toLong())
        return when (op) {
            PbOp.LE -> imgr.lessOrEquals(sum, b)
            PbOp.GE -> imgr.greaterOrEquals(sum, b)
            PbOp.EQ -> imgr.equal(sum, b)
        }
    }
}
