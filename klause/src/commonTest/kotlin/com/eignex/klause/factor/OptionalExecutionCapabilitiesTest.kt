package com.eignex.klause.factor

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.solver.Sample
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OptionalExecutionCapabilitiesTest {
    @Test
    fun `optional filtering retains every independently satisfying assignment`() {
        for ((family, variants) in cases()) {
            for ((index, variant) in variants.withIndex()) {
                val (problem, degree) = variant
                FactorPropagationOracle.assertSound(problem, "$family/$index") { degree(it) == 0 }
            }
        }
    }

    @Test
    fun `complete optional CP checks agree with independent source semantics`() {
        for ((family, variants) in cases()) {
            for ((index, variant) in variants.withIndex()) {
                val (problem, degree) = variant
                FactorPropagationOracle.assertCompleteChecks(problem, "$family/$index") { degree(it) == 0 }
            }
        }
    }

    @Test
    fun `optional invariant checks and scores agree with independent source degrees`() {
        for ((family, variants) in cases()) {
            for ((index, variant) in variants.withIndex()) {
                val (problem, degree) = variant
                FactorPropagationOracle.assertScoring(problem, "$family/$index", degree)
            }
        }
    }

    @Test
    fun `optional all different move scores agree with independent source degrees`() {
        for ((index, variant) in allDifferentCases().withIndex()) {
            FactorPropagationOracle.assertMoveScoring(variant.first, "allDifferent/$index", variant.second)
        }
    }

    @Test
    fun `optional global cardinality move scores agree with independent source degrees`() {
        for ((index, variant) in gccCases().withIndex()) {
            FactorPropagationOracle.assertMoveScoring(variant.first, "gcc/$index", variant.second)
        }
    }

    @Test
    fun `optional nvalue move scores agree with independent source degrees`() {
        for ((index, variant) in nValueCases().withIndex()) {
            FactorPropagationOracle.assertMoveScoring(variant.first, "nvalue/$index", variant.second)
        }
    }

    @Test
    fun `optional cumulative move scores agree with independent source degrees`() {
        for ((index, variant) in cumulativeCases().withIndex()) {
            FactorPropagationOracle.assertMoveScoring(variant.first, "cumulative/$index", variant.second)
        }
    }

    @Test
    fun `optional count constraints decline unconditional LP hulls`() {
        for ((problem, _) in gccCases() + nValueCases()) {
            val relaxation = CpToLpRelaxation(problem, null, gccCountHull = true, nValueHull = true)
                .build(RootDomains(problem))

            assertTrue(relaxation.hullFactorIds.isEmpty())
            assertEquals(0, relaxation.model.m)
        }
    }

    private fun cases(): Map<String, List<Pair<Problem, (Sample) -> Int>>> = linkedMapOf(
        "allDifferent" to allDifferentCases(),
        "gcc" to gccCases(),
        "nvalue" to nValueCases(),
        "cumulative" to cumulativeCases(),
    )

    private fun allDifferentCases(): List<Pair<Problem, (Sample) -> Int>> = listOf(false, true).map { exceptZero ->
        val factor = AllDifferent(
            intArrayOf(0, 1), 0, 2, presents = signedPresence(),
            exceptSet = if (exceptZero) longArrayOf(0) else longArrayOf(),
        )
        Problem(2, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(factor)) to { s: Sample ->
            if (s.bools[0] && !s.bools[1] && s.ints[0] == s.ints[1] &&
                (!exceptZero || s.ints[0] != 0L)
            ) 1 else 0
        }
    } + listOf(
        Problem(
            2, 2, Array(2) { IntDomain(0, 1) },
            arrayOf<Factor>(AllDifferent(
                intArrayOf(0, 0, 1), 0, 2,
                presents = intArrayOf(Lit.make(0, true), Lit.make(0, false), Lit.make(1, false)),
            )),
        ) to { s: Sample -> if (!s.bools[1] && s.ints[0] == s.ints[1]) 1 else 0 },
    )

    private fun gccCases(): List<Pair<Problem, (Sample) -> Int>> = listOf(false, true).map { closed ->
        val factor = GlobalCardinality(
            intArrayOf(0, 1), longArrayOf(0), countLow = intArrayOf(1), countHigh = intArrayOf(1),
            closed = closed, presents = signedPresence(),
        )
        Problem(2, 2, Array(2) { IntDomain(0, 1) }, arrayOf<Factor>(factor)) to { s: Sample ->
            val values = presentValues(s)
            abs(values.count { it == 0L } - 1) + if (closed) values.count { it != 0L } else 0
        }
    } + listOf(
        Problem(
            2, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2)),
            arrayOf<Factor>(GlobalCardinality(
                intArrayOf(0, 1), longArrayOf(0), countVars = intArrayOf(2), presents = signedPresence(),
            )),
        ) to { s: Sample -> abs(presentValues(s).count { it == 0L } - s.ints[2].toInt()) },
    )

    private fun nValueCases(): List<Pair<Problem, (Sample) -> Int>> = NValue.Mode.entries.map { mode ->
        val factor = NValue(2, intArrayOf(0, 1), mode, signedPresence())
        Problem(
            2, 3, arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 2)), arrayOf<Factor>(factor),
        ) to { s: Sample ->
            val distinct = presentValues(s).distinct().size
            val target = s.ints[2].toInt()
            when (mode) {
                NValue.Mode.Eq -> abs(distinct - target)
                NValue.Mode.AtLeast -> maxOf(0, target - distinct)
                NValue.Mode.AtMost -> maxOf(0, distinct - target)
            }
        }
    }

    private fun cumulativeCases(): List<Pair<Problem, (Sample) -> Int>> = listOf(
        Cumulative(intArrayOf(0, 1), longArrayOf(1, 2), longArrayOf(2, 1), 2, signedPresence()),
        Cumulative.unary(intArrayOf(0, 1), longArrayOf(1, 1), signedPresence()),
        Cumulative(intArrayOf(0, 1), longArrayOf(0, 2), longArrayOf(1, 1), 1, signedPresence()),
    ).map { factor ->
        Problem(2, 2, Array(2) { IntDomain(0, 2) }, arrayOf<Factor>(factor)) to { s: Sample ->
            var overflow = 0L
            for (time in 0L..3L) {
                var usage = 0L
                for (task in 0..1) {
                    val present = if (task == 0) s.bools[0] else !s.bools[1]
                    if (present && time >= s.ints[task] && time < s.ints[task] + factor.durations[task]) {
                        usage += factor.resources[task]
                    }
                }
                overflow += maxOf(0L, usage - factor.capacity)
            }
            overflow.toInt()
        }
    }

    private fun signedPresence(): IntArray = intArrayOf(Lit.make(0, true), Lit.make(1, false))

    private fun presentValues(sample: Sample): List<Long> = buildList {
        if (sample.bools[0]) add(sample.ints[0])
        if (!sample.bools[1]) add(sample.ints[1])
    }
}
