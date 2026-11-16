package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.reifiedIntCompare
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.values
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.PbOp
import kotlin.random.Random

internal data class LocalSearchStateCase(val name: String, val problem: Problem)

internal fun localSearchStateCases(): List<LocalSearchStateCase> = listOf(
    boolHeavyCase(),
    intHeavyCase(),
    mixedReifiedCase(),
    permutationCase(),
)

internal fun randomPrimitiveLocalSearchMove(problem: Problem, state: LocalSearchState, rng: Random): Move? {
    val haveBool = problem.numBoolVars > 0
    val haveInt = problem.numIntVars > 0
    val pickBool = when {
        !haveBool -> false
        !haveInt -> true
        else -> rng.nextBoolean()
    }
    return if (pickBool) {
        Move.BoolFlip(rng.nextInt(problem.numBoolVars))
    } else {
        val v = rng.nextInt(problem.numIntVars)
        val d = state.rootDomains[v]
        val cur = state.assignment.intValue(v)
        var target = cur
        repeat(8) {
            val cand = d.values.valueAt(rng.nextInt(d.values.size))
            if (cand != cur) {
                target = cand
                return@repeat
            }
        }
        if (target == cur) null else Move.IntSet(v, target)
    }
}

internal fun copyLocalSearchAssignment(src: LocalSearchState, dst: LocalSearchState) {
    for (b in 0 until src.problem.numBoolVars) dst.assignment.setBool(b, src.assignment.boolValue(b))
    for (i in 0 until src.problem.numIntVars) dst.assignment.setInt(i, src.assignment.intValue(i))
}

private fun boolHeavyCase(): LocalSearchStateCase {
    val factors = arrayOf<Factor>(
        Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true))),
        Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true), Lit.make(4, true))),
        Clause(intArrayOf(Lit.make(0, false), Lit.make(4, false))),
        Cardinality(
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
            min = 1,
            max = 3,
        ),
        Xor(intArrayOf(Lit.make(0, true), Lit.make(2, true), Lit.make(4, true)), targetParity = 1),
        PseudoBoolean(
            weights = longArrayOf(2, 1, 3, 1),
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false), Lit.make(3, true)),
            op = PbOp.LE,
            bound = 4L,
        ),
    )
    return LocalSearchStateCase("boolHeavy", Problem(numBoolVars = 5, numIntVars = 0, intDomains = emptyArray(), factors = factors))
}

private fun intHeavyCase(): LocalSearchStateCase {
    val intDomains = arrayOf(IntDomain(-3, 3), IntDomain(-3, 3), IntDomain(0, 5))
    val factors = arrayOf<Factor>(
        Linear(coeffs = intArrayOf(2, -1, 1), vars = intArrayOf(0, 1, 2), op = LinearOp.LE, 4),
        Linear(coeffs = intArrayOf(1, 1, 1), vars = intArrayOf(0, 1, 2), op = LinearOp.GE, -1),
        Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 4),
        Linear(intArrayOf(1), intArrayOf(0), LinearOp.NE, 0),
    )
    return LocalSearchStateCase("intHeavy", Problem(numBoolVars = 0, numIntVars = 3, intDomains = intDomains, factors = factors))
}

private fun mixedReifiedCase(): LocalSearchStateCase {
    val intDomains = arrayOf(IntDomain(-2, 3), IntDomain(-2, 3))
    val factors = arrayOf<Factor>(
        ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(2, -1),
            vars = intArrayOf(0, 1),
            op = LinearOp.LE,
            bound = 3,
        ),
        ReifiedCardinality(
            auxBoolVar = 1,
            literals = intArrayOf(Lit.make(2, true), Lit.make(3, true), Lit.make(4, true)),
            min = 1,
            max = 2,
        ),
        reifiedIntCompare(auxBoolVar = 5, intVar = 0, op = IntCmpOp.GE, 0),
        Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(5, false))),
    )
    return LocalSearchStateCase(
        "mixedReified",
        Problem(numBoolVars = 6, numIntVars = 2, intDomains = intDomains, factors = factors),
    )
}

private fun permutationCase(): LocalSearchStateCase {
    val intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3))
    val factors = arrayOf<Factor>(
        AllDifferent(vars = intArrayOf(0, 1, 2, 3), domainMin = 0, domainSize = 4),
        Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2),
        Linear(intArrayOf(1), intArrayOf(3), LinearOp.GE, 1),
    )
    return LocalSearchStateCase("permutation", Problem(numBoolVars = 0, numIntVars = 4, intDomains = intDomains, factors = factors))
}
