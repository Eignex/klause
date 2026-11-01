package com.eignex.klause.bench.profile

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.propagation.BakedProblem
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ArithmeticLearningSourceShape {
    private const val ENTRY_LIMIT = 65_536
    private const val PAIR_VISIT_LIMIT = 1_048_576
    private const val RESULT_TERM_LIMIT = 16

    fun inspect(model: BakedProblem) = buildJsonObject {
        val sources = mutableListOf<UnitSource>()
        val widths = mutableListOf<Pair<Int, Int>>()
        var entries = 0
        var unsupported = 0
        var inputComplete = true
        outer@ for ((factor, value) in model.factors.withIndex()) {
            val form = value.linearForm as? LinearForm.Conjunction ?: continue
            for (row in form.rows) {
                if (widths.size >= ENTRY_LIMIT || row.size > ENTRY_LIMIT - entries) {
                    inputComplete = false
                    break@outer
                }
                widths.add(factor to row.size)
                entries += row.size
                if (!row.isLongUnconditional || !row.isIntegerOnly || row.strict || row.size == 0 ||
                    row.relation == LinearOp.NE) {
                    unsupported++
                    continue
                }
                val first = row.coeff(0)
                if (first == 0L || first == Long.MIN_VALUE) {
                    unsupported++
                    continue
                }
                val magnitude = if (first < 0L) -first else first
                if ((0 until row.size).any { row.coeff(it) != magnitude && row.coeff(it) != -magnitude }) {
                    unsupported++
                    continue
                }
                val terms = (0 until row.size).map { row.ref(it) to if (row.coeff(it) > 0L) 1 else -1 }
                    .sortedBy { it.first }
                if (terms.zipWithNext().any { it.first.first == it.second.first }) {
                    unsupported++
                    continue
                }
                val directions = when (row.relation) {
                    LinearOp.LE -> listOf(1)
                    LinearOp.GE -> listOf(-1)
                    LinearOp.EQ -> listOf(1, -1)
                    LinearOp.NE -> emptyList()
                }
                for (direction in directions) sources.add(UnitSource(
                    factor,
                    direction,
                    IntArray(terms.size) { terms[it].first },
                    IntArray(terms.size) { terms[it].second * direction },
                ))
            }
        }
        var visits = 0
        var pairs = 0
        var resolvable = 0
        var compact = 0
        var wideCompact = 0
        var minimum: Int? = null
        var pairsComplete = true
        val examples = mutableListOf<Triple<UnitSource, UnitSource, Int>>()
        val wideExamples = mutableListOf<Triple<UnitSource, UnitSource, Int>>()
        outer@ for (i in sources.indices) {
            val left = sources[i]
            for (j in 0 until i) {
                val right = sources[j]
                if (left.factor == right.factor) continue
                var a = 0
                var b = 0
                var residual = 0
                var opposite = false
                while (a < left.refs.size || b < right.refs.size) {
                    if (visits >= PAIR_VISIT_LIMIT) {
                        pairsComplete = false
                        break@outer
                    }
                    visits++
                    val x = left.refs.getOrNull(a)
                    val y = right.refs.getOrNull(b)
                    when {
                        x == null -> { residual++; b++ }
                        y == null -> { residual++; a++ }
                        x < y -> { residual++; a++ }
                        y < x -> { residual++; b++ }
                        else -> {
                            if (left.signs[a] == right.signs[b]) residual++ else opposite = true
                            a++
                            b++
                        }
                    }
                }
                pairs++
                if (!opposite) continue
                resolvable++
                if (minimum == null || residual < minimum) minimum = residual
                if (residual !in 1..RESULT_TERM_LIMIT) continue
                compact++
                if (examples.size < 8) examples.add(Triple(left, right, residual))
                if (left.refs.size > RESULT_TERM_LIMIT || right.refs.size > RESULT_TERM_LIMIT) {
                    wideCompact++
                    if (wideExamples.size < 8) wideExamples.add(Triple(left, right, residual))
                }
            }
        }
        put("scope", "conjunctive unconditional word integer unit-coefficient rows")
        put("coefficientShapeOnly", true)
        put("boundOrAssertionClaim", false)
        put("entryLimit", ENTRY_LIMIT)
        put("entries", entries)
        put("inputComplete", inputComplete)
        put("unsupportedRows", unsupported)
        put("unitSourceDirections", sources.size)
        put("pairVisitLimit", PAIR_VISIT_LIMIT)
        put("pairVisits", visits)
        put("pairsComplete", pairsComplete)
        put("pairs", pairs)
        put("oppositeSignPairs", resolvable)
        put("compactCoefficientPairs", compact)
        put("wideCompactCoefficientPairs", wideCompact)
        put("minimumResidualTerms", minimum)
        put("sourceWidths", buildJsonArray {
            widths.forEach { (factor, terms) -> add(buildJsonObject {
                put("factor", factor)
                put("terms", terms)
            }) }
        })
        put("examples", exportPairs(examples))
        put("wideExamples", exportPairs(wideExamples))
    }

    private fun exportPairs(pairs: List<Triple<UnitSource, UnitSource, Int>>) = buildJsonArray {
        pairs.forEach { (left, right, residual) -> add(buildJsonObject {
            put("leftFactor", left.factor)
            put("leftDirection", left.direction)
            put("rightFactor", right.factor)
            put("rightDirection", right.direction)
            put("residualTerms", residual)
            put("sourceTerms", buildJsonArray {
                add(JsonPrimitive(left.refs.size))
                add(JsonPrimitive(right.refs.size))
            })
        }) }
    }

    private class UnitSource(val factor: Int, val direction: Int, val refs: IntArray, val signs: IntArray)
}
