package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object IntegerDifferenceSiblingShape {
    private data class Side(val factorIndex: Int, val row: ReifiedLinear)
    private data class Split(val target: Int, val atMost: Int, val atLeast: Int)

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun inspect(model: Problem, stop: Cancellation): JsonObject = buildJsonObject {
        checkpoint(stop)
        require(model.numRealVars == 0) { "integer sibling inspection requires a pure integer source" }
        val sides = HashMap<Int, Side>()
        val ambiguous = HashSet<Int>()
        val binary = HashMap<Long, Int>()
        var eligibleSides = 0
        for ((index, factor) in model.factors.withIndex()) {
            checkpoint(stop)
            if (factor is Clause && factor.literals.size == 2) {
                binary.putIfAbsent(pair(factor.literals[0], factor.literals[1]), index)
            }
            val row = factor as? ReifiedLinear ?: continue
            if (!eligible(row, model)) continue
            eligibleSides++
            if (sides.put(row.auxBoolVar, Side(index, row)) != null) ambiguous += row.auxBoolVar
        }
        for (guard in ambiguous) sides.remove(guard)
        val found = HashSet<Split>()
        val examples = ArrayList<JsonObject>()
        var covered = 0
        for ((index, factor) in model.factors.withIndex()) {
            checkpoint(stop)
            val clause = factor as? Clause ?: continue
            if (clause.literals.size != 3) continue
            for (targetIndex in 0..2) {
                checkpoint(stop)
                val target = clause.literals[targetIndex]
                val left = clause.literals[(targetIndex + 1) % 3]
                val right = clause.literals[(targetIndex + 2) % 3]
                if (left and 1 != 1 || right and 1 != 1) continue
                val first = sides[Lit.variable(left)] ?: continue
                val second = sides[Lit.variable(right)] ?: continue
                if (!sameExpression(first.row, second.row) || first.row.op == second.row.op) continue
                val atMost = if (first.row.op == LinearOp.LE) first else second
                val atLeast = if (first.row.op == LinearOp.GE) first else second
                val mostLiteral = Lit.make(atMost.row.auxBoolVar, true)
                val leastLiteral = Lit.make(atLeast.row.auxBoolVar, true)
                val mostForward = binary[pair(Lit.negate(target), mostLiteral)] ?: continue
                val leastForward = binary[pair(Lit.negate(target), leastLiteral)] ?: continue
                if (!found.add(Split(target, mostLiteral, leastLiteral))) continue
                val coverage = binary[pair(mostLiteral, leastLiteral)]
                if (coverage != null) covered++
                if (examples.size < MAX_EXAMPLES) examples += buildJsonObject {
                    put("targetLiteral", target)
                    put("atMostLiteral", mostLiteral)
                    put("atLeastLiteral", leastLiteral)
                    put("atMostFactor", atMost.factorIndex)
                    put("atLeastFactor", atLeast.factorIndex)
                    put("atMostForwardClause", mostForward)
                    put("atLeastForwardClause", leastForward)
                    put("backwardClause", index)
                    put("coverageClause", coverage)
                    put("bound", requireNotNull(atMost.row.integerConstants).bound.toString())
                }
            }
        }
        checkpoint(stop)
        put("eligibleSideFactors", eligibleSides)
        put("ambiguousSideGuards", ambiguous.size)
        put("matchedSplits", found.size)
        put("coveredSplits", covered)
        put("uncoveredSplits", found.size - covered)
        put("examples", buildJsonArray { examples.forEach(::add) })
        put("normalizationPerformed", false)
        put("solverExecuted", false)
        put("preparationExecuted", false)
        put("sourceVerdictClaim", false)
    }

    private fun eligible(row: ReifiedLinear, model: Problem): Boolean {
        val constants = row.integerConstants ?: return false
        return row.auxBoolVar in 0 until model.numBoolVars && row.vars.size == 2 &&
            row.vars.all { it in 0 until model.numIntVars } &&
            (row.op == LinearOp.LE || row.op == LinearOp.GE) &&
            (constants.coeff(0) == 1L || constants.coeff(0) == -1L) &&
            constants.coeff(1) == -constants.coeff(0)
    }

    private fun sameExpression(left: ReifiedLinear, right: ReifiedLinear): Boolean {
        val a = requireNotNull(left.integerConstants)
        val b = requireNotNull(right.integerConstants)
        return left.vars.contentEquals(right.vars) && a.bound == b.bound &&
            a.coeff(0) == b.coeff(0) && a.coeff(1) == b.coeff(1)
    }

    private fun pair(left: Int, right: Int): Long =
        (minOf(left, right).toLong() shl 32) or (maxOf(left, right).toLong() and 0xffff_ffffL)

    private fun checkpoint(stop: Cancellation) {
        stop.charge(1L)
        if (stop()) throw CancellationException("integer sibling inspection interrupted")
    }

    private const val MAX_EXAMPLES = 8
}
