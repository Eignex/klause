package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.ir.Problem

/** What kind of model a solve faces, the one classification every engine choice and portfolio pool reads. */
enum class ProblemClass {
    /** Boolean columns under clauses only. */
    Sat,

    /** Boolean columns only, under constraints other than clauses: pseudo-Boolean and cardinality rows. */
    PseudoBoolean,

    /** Finite integer columns, possibly with Boolean ones, and no continuous columns. */
    FiniteCp,

    /** Finite integer or Boolean columns together with continuous ones. */
    MixedInteger,

    /** Continuous columns only. */
    Continuous,

    /** An integer column with an unstated side: the open route's model. */
    Open,
}

/**
 * The classification of one model: its [problemClass], whether it [optimizing], and two traits that cut across the
 * classes. [wide] marks values or row constants past the 32-bit range, where some engines change arithmetic or
 * search windows; [scheduling] marks resource or placement constraints.
 *
 * Computed by [of] on whichever model is at hand: the source model when a route is chosen, the presolved one when a
 * portfolio is built, since presolve can change a model's shape.
 */
data class ProblemProfile(
    /** The model's class. */
    val problemClass: ProblemClass,
    /** Whether the model has an objective. */
    val optimizing: Boolean,
    /** Whether a value or row constant leaves the 32-bit range. */
    val wide: Boolean,
    /** Whether a cumulative or placement constraint is present. */
    val scheduling: Boolean,
) {
    /** Whether the model has continuous columns. */
    val realColumns: Boolean
        get() = problemClass == ProblemClass.MixedInteger || problemClass == ProblemClass.Continuous

    /** The classifier. */
    companion object {
        /** The profile of [problem], [optimizing] when the solve has an objective. */
        fun of(problem: Problem, optimizing: Boolean): ProblemProfile =
            ProblemProfile(classOf(problem), optimizing, isWide(problem), hasScheduling(problem))

        /** The class of [problem]. */
        fun classOf(problem: Problem): ProblemClass = when {
            !problem.hasFiniteIntegerRanges() -> ProblemClass.Open
            problem.numRealVars > 0 && problem.numIntVars == 0 && problem.numBoolVars == 0 -> ProblemClass.Continuous
            problem.numRealVars > 0 -> ProblemClass.MixedInteger
            problem.numIntVars > 0 -> ProblemClass.FiniteCp
            problem.isClausal() -> ProblemClass.Sat
            else -> ProblemClass.PseudoBoolean
        }

        private fun isWide(problem: Problem): Boolean {
            val bounds = problem.intBounds
            for (v in 0 until problem.numIntVars) {
                if (!bounds.hasLower(v) || !bounds.hasUpper(v)) return true
                if (bounds.lower(v) < Int.MIN_VALUE || bounds.upper(v) > Int.MAX_VALUE) return true
            }
            return problem.factors.any {
                (it is Linear && it.wideConstants != null) || (it is ReifiedLinear && it.wideConstants != null)
            }
        }

        private fun hasScheduling(problem: Problem): Boolean = problem.factors.any { it is Cumulative || it is Diffn }
    }
}

/** Whether every integer column states both of its sides. */
fun Problem.hasFiniteIntegerRanges(): Boolean =
    (0 until numIntVars).all { intBounds.hasLower(it) && intBounds.hasUpper(it) }

/** Whether [this] is Boolean columns under clauses alone, at least one of each: the native-SAT lane's model. */
fun Problem.isClausal(): Boolean =
    numIntVars == 0 && numRealVars == 0 && numBoolVars > 0 && factors.isNotEmpty() && factors.all { it is Clause }
