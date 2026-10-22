package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.linearRows
import com.eignex.klause.lp.ExactWitness
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.model.PbOp
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.ColumnRanges
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.presolve.Presolver
import com.eignex.klause.presolve.asSourceDelta
import com.eignex.klause.presolve.structural.RedundantConstraints.SubsumeFactor
import com.eignex.klause.presolve.structural.RedundantConstraints.SubsumeIncremental
import com.eignex.klause.presolve.structural.RedundantConstraints.SubsumeMemo
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RedundantConstraintsTest {
    private fun isFeasible(problem: Problem, ints: LongArray, boolMask: Int = 0): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numIntVars) a = a.withInt(v, ints[v])
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, boolMask and (1 shl v) != 0)
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun feasibleCount(problem: Problem): Int {
        val n = problem.numIntVars
        val ints = LongArray(n) { problem.finiteIntDomain(it).min }
        var count = 0
        while (true) {
            if (isFeasible(problem, ints.copyOf())) count++
            var i = 0
            while (i < n) {
                ints[i]++
                if (ints[i] <= problem.finiteIntDomain(i).max) break
                ints[i] = problem.finiteIntDomain(i).min
                i++
            }
            if (i == n) break
        }
        return count
    }

    private fun checkPreserved(name: String, problem: Problem, expectDrop: Boolean): Problem {
        val baked = problem.bake()
        val delta = Presolve.removeRedundantConstraints(baked)
        val out = baked.withPassDelta(delta, BakeConfig.NONE)
        assertEquals(feasibleCount(problem), feasibleCount(out), "$name: feasible set changed")
        if (expectDrop) {
            assertTrue(out.factors.size < problem.factors.size, "$name: expected a constraint to be dropped")
        } else {
            assertTrue(delta.isEmpty, "$name: expected no change")
        }
        return out
    }

    @Test
    fun `duplicate clauses should preserve every assignment in source and finite presolve`() {
        for (shared in listOf(true, false)) {
            val clause = Clause(intArrayOf(pos(0), pos(1)))
            val duplicate = if (shared) clause else Clause(intArrayOf(pos(0), pos(1)))
            val problem = Problem(2, 0, emptyArray(), listOf(clause, duplicate))
            val config = PresolveConfig.parse("off,+subsume")

            val source = Presolver.runSource(problem, config).problem
            val finite = Presolver.run(problem.bake(), config).problem

            for (reduced in listOf(source, finite)) {
                for (mask in 0..3) {
                    assertEquals(mask != 0, isFeasible(reduced, longArrayOf(), mask), "shared=$shared mask=$mask")
                    assertEquals(isFeasible(problem, longArrayOf(), mask), isFeasible(reduced, longArrayOf(), mask))
                }
                assertEquals(1, reduced.factors.size)
            }
        }
    }

    private fun checkIncrementalAssignments(problem: Problem, incremental: SubsumeIncremental): Problem {
        val ranges = ColumnRanges.of(problem.intBounds)
        val full = RedundantConstraints.removeRedundantConstraints(problem, ranges)
        val delta = RedundantConstraints.removeRedundantConstraints(problem, ranges, incremental)
        val reduced = assertNotNull(problem.withSourcePassDelta(delta.asSourceDelta()))
        assertEquals(full.droppedIndices.toList(), delta.droppedIndices.toList())
        val values = if (problem.numIntVars == 0) 0..0L else 0..2L
        for (mask in 0 until (1 shl problem.numBoolVars)) {
            for (x in values) {
                for (y in values) {
                    val ints = longArrayOf(x, y)
                    assertEquals(
                        isFeasible(problem, ints, mask), isFeasible(reduced, ints, mask),
                        "mask=$mask ints=${ints.toList()}",
                    )
                }
            }
        }
        return reduced
    }

    @Test
    fun `mixed duplicate rows should preserve every assignment across retractions and readditions`() {
        for (shared in listOf(true, false)) {
            val clause = Clause(intArrayOf(pos(0), pos(1)))
            val loose = le(2, 0, 1, 1, 1)
            val tight = le(1, 0, 1, 1, 1)
            val memo = SubsumeMemo()
            val original = Problem(2, 2, dom(2, 2), listOf(
                clause, loose,
                if (shared) clause else Clause(intArrayOf(pos(0), pos(1))),
                if (shared) loose else le(2, 0, 1, 1, 1),
                tight,
            ))
            checkIncrementalAssignments(
                original, SubsumeIncremental(true, intArrayOf(0, 1, 2, 3, 4), emptyList(), intArrayOf(), memo),
            )
            val replaced = Problem(2, 2, dom(2, 2), listOf(clause, loose))
            checkIncrementalAssignments(
                replaced,
                SubsumeIncremental(false, intArrayOf(0, 5), listOf(SubsumeFactor(5, loose)), intArrayOf(4), memo),
            )
            checkIncrementalAssignments(
                replaced, SubsumeIncremental(false, intArrayOf(0, 5), emptyList(), intArrayOf(6), memo),
            )
            val added = Problem(2, 2, dom(2, 2), listOf(clause, loose, loose, tight))
            checkIncrementalAssignments(
                added,
                SubsumeIncremental(
                    false, intArrayOf(0, 5, 7, 8), listOf(SubsumeFactor(7, loose), SubsumeFactor(8, tight)),
                    intArrayOf(), memo,
                ),
            )
            val restored = Problem(2, 2, dom(2, 2), listOf(clause, loose))
            checkIncrementalAssignments(
                restored,
                SubsumeIncremental(
                    false, intArrayOf(0, 9), listOf(SubsumeFactor(9, loose)), intArrayOf(8), memo,
                ),
            )
        }
    }

    @Test
    fun `an interrupted duplicate rebuild should recover its occurrence index`() {
        val clause = Clause(intArrayOf(pos(0), pos(1)))
        val problem = Problem(2, 0, emptyArray(), listOf(clause, clause))
        val memo = SubsumeMemo()
        val incremental = SubsumeIncremental(true, intArrayOf(0, 1), emptyList(), intArrayOf(), memo)
        val ranges = ColumnRanges.of(problem.intBounds)
        val stopped = RedundantConstraints.removeRedundantConstraints(
            problem, ranges, incremental, Cancellation { true },
        )
        assertTrue(stopped.isEmpty)

        val recovered = checkIncrementalAssignments(
            problem, SubsumeIncremental(false, intArrayOf(0, 1), emptyList(), intArrayOf(), memo),
        )

        assertEquals(1, recovered.factors.size)
    }

    @Test
    fun `an unchanged duplicate memo should charge no repeated registration work`() {
        val clause = Clause(intArrayOf(pos(0), pos(1)))
        val problem = Problem(2, 0, emptyArray(), listOf(clause, clause))
        val memo = SubsumeMemo()
        val budget = PresolveBudget(Long.MAX_VALUE)
        val token = budget.orSpent(Cancellation.Never)
        val ranges = ColumnRanges.of(problem.intBounds)
        RedundantConstraints.removeRedundantConstraints(
            problem, ranges, SubsumeIncremental(true, intArrayOf(0, 1), emptyList(), intArrayOf(), memo), token,
        )
        val firstWork = budget.spent()
        val reduced = Problem(2, 0, emptyArray(), listOf(clause))

        val delta = RedundantConstraints.removeRedundantConstraints(
            reduced, ranges, SubsumeIncremental(false, intArrayOf(0), emptyList(), intArrayOf(), memo), token,
        )

        assertTrue(delta.isEmpty)
        assertEquals(60L, firstWork)
        assertEquals(firstWork, budget.spent())
    }

    private fun dom(n: Int, hi: Int) = Array(n) { IntDomain(0, hi.toLong()) }
    private fun le(b: Int, vararg vc: Int) =
        Linear(IntArray(vc.size / 2) { vc[2 * it + 1] }, IntArray(vc.size / 2) { vc[2 * it] }, LinearOp.LE, b)

    @Test
    fun `an exclusion triangle without an eligible knapsack should spend no clique work`() {
        val exclusions = listOf(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
        )
        for (extra in listOf(
            null,
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1),
            PseudoBoolean(longArrayOf(5, 0, 2), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 6),
            PseudoBoolean(longArrayOf(5, -2, 2), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 6),
            PseudoBoolean(longArrayOf(5, 2, 2), intArrayOf(pos(0), pos(1), pos(2)), PbOp.GE, 2),
            PseudoBoolean(longArrayOf(5, 2, 2), intArrayOf(pos(0), pos(1), pos(2)), PbOp.EQ, 2),
            Cardinality(intArrayOf(pos(0), pos(1), pos(2)), 0, 1),
        )) {
            val model = Problem(3, 2, dom(2, 1), exclusions + listOfNotNull(extra))
            val budget = PresolveBudget(1_000_000)

            val delta = Presolve.removeRedundantSourceConstraints(model, budget.orSpent(Cancellation.Never))

            assertTrue(delta.isEmpty, "unexpected reduction with $extra")
            assertEquals(0L, budget.spent(), "unexpected clique work with $extra")
        }
    }

    @Test
    fun `an eligible knapsack implied by an exclusion triangle should spend clique work and be dropped`() {
        val model = Problem(
            3,
            0,
            emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
                PseudoBoolean(longArrayOf(5, 2, 2), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 5),
            ),
        )
        val budget = PresolveBudget(1_000_000)

        val delta = Presolve.removeRedundantSourceConstraints(model, budget.orSpent(Cancellation.Never))

        assertEquals(listOf(3), delta.droppedIndices.toList())
        assertTrue(budget.spent() > 0L)
    }

    @Test
    fun `a declared knapsack implied by a clique should be dropped without finite domains`() {
        val source = PseudoBoolean(longArrayOf(5, 2, 2), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 7)
        val model = Problem(
            3,
            0,
            emptyArray(),
            listOf(Cardinality(intArrayOf(pos(1), pos(2)), 0, 1), object : Factor by source {}),
        )

        val delta = Presolve.removeRedundantSourceConstraints(model, Cancellation.Never)

        assertEquals(listOf(1), delta.droppedIndices.toList())
    }

    @Test
    fun `a clique cover should count every repeated literal coefficient`() {
        val model = Problem(
            2,
            0,
            emptyArray(),
            listOf(
                Cardinality(intArrayOf(pos(0), pos(1)), 0, 1),
                PseudoBoolean(longArrayOf(2, 2, 1), intArrayOf(pos(0), pos(0), pos(1)), PbOp.LE, 3),
            ),
        )

        val delta = Presolve.removeRedundantSourceConstraints(model, Cancellation.Never)

        assertTrue(1 !in delta.droppedIndices)
    }

    @Test
    fun `negated-equivalent inequalities should be deduplicated`() {
        val problem = Problem(
            0,
            2,
            dom(2, 3),
            listOf(
                le(3, 0, 1, 1, 1),
                Linear(intArrayOf(-1, -1), intArrayOf(0, 1), LinearOp.GE, -3),
            ),
        )
        val factorsBefore = problem.factors
        val out = checkPreserved("negated-equiv", problem, expectDrop = true)
        assertEquals(1, out.factors.size)
        assertTrue(out.factors.single() in factorsBefore)
    }

    @Test
    fun `proportional rows should be deduplicated via GCD normalization`() {
        val problem = Problem(0, 2, dom(2, 3), listOf(le(2, 0, 1, 1, 1), le(4, 0, 2, 1, 2)))
        val out = checkPreserved("proportional-dup", problem, expectDrop = true)
        assertEquals(1, out.factors.size)
    }

    @Test
    fun `a subset-dominated source row should be dropped`() {
        val problem = Problem(0, 3, dom(3, 3), listOf(le(2, 0, 1, 1, 1), le(5, 0, 1, 1, 1, 2, 1)))

        val delta = Presolve.removeRedundantSourceConstraints(problem, Cancellation.Never)

        assertEquals(listOf(1), delta.droppedIndices.toList())
    }

    @Test
    fun `unsafe subset activity arithmetic should preserve every source assignment`() {
        val large = 1L shl 62
        val cases = listOf(
            Triple(arrayOf(IntDomain(0, 1), IntDomain(0, 4)),
                listOf(Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, 0L),
                    Linear(longArrayOf(1, large), intArrayOf(0, 1), LinearOp.LE, 0L)), 1),
            Triple(arrayOf(IntDomain(0, 2), IntDomain(0, 1)),
                listOf(Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, large),
                    Linear(longArrayOf(4, 1), intArrayOf(0, 1), LinearOp.LE, 1L)), 2),
            Triple(arrayOf(IntDomain(0, 1), IntDomain(-4, 0)),
                listOf(Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, 0L),
                    Linear(longArrayOf(1, -large), intArrayOf(0, 1), LinearOp.LE, 0L)), 1),
            Triple(arrayOf(IntDomain(0, 1), IntDomain(Long.MIN_VALUE, Long.MIN_VALUE + 1)),
                listOf(Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, 0L),
                    Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 0L)), 0),
            Triple(arrayOf(IntDomain(0, 1), IntDomain(0, 4)),
                listOf(Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, Long.MAX_VALUE - 2),
                    Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1L)), 3),
            Triple(arrayOf(IntDomain(-2, 0), IntDomain(0, 1)),
                listOf(Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, -large),
                    Linear(longArrayOf(4, 1), intArrayOf(0, 1), LinearOp.LE, 1L)), 0),
            Triple(arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)),
                listOf(Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, 0L),
                    Linear(longArrayOf(1, large, large), intArrayOf(0, 1, 2), LinearOp.LE, 0L)), 1),
            Triple(arrayOf(IntDomain(0, 1), IntDomain(1, 1), IntDomain(1, 1)),
                listOf(Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, 0L),
                    Linear(longArrayOf(1, -large - 1, -large - 1), intArrayOf(0, 1, 2), LinearOp.LE, 0L)), 1),
        )
        for ((domains, factors, expectedCount) in cases) {
            val problem = Problem(0, domains.size, domains, factors)

            val delta = Presolve.removeRedundantSourceConstraints(problem, Cancellation.Never)
            val reduced = Presolver.runSource(problem, PresolveConfig.parse("off,+subsume")).problem

            assertTrue(delta.isEmpty)
            assertEquals(expectedCount, checkExactSubsetAssignments(problem, reduced))
        }
    }

    @Test
    fun `safe extreme subset activity should remove a genuinely dominated row`() {
        val large = 1L shl 62
        for (coefficient in listOf(large, -large)) {
            val domains = arrayOf(IntDomain(0, 1), if (coefficient > 0) IntDomain(-2, -1) else IntDomain(1, 2))
            val problem = Problem(0, 2, domains, listOf(
                Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, 0L),
                Linear(longArrayOf(1, coefficient), intArrayOf(0, 1), LinearOp.LE, 0L),
            ))

            val delta = Presolve.removeRedundantSourceConstraints(problem, Cancellation.Never)
            val reduced = Presolver.runSource(problem, PresolveConfig.parse("off,+subsume")).problem

            assertEquals(listOf(1), delta.droppedIndices.toList())
            assertEquals(2, checkExactSubsetAssignments(problem, reduced))
        }
    }

    private fun checkExactSubsetAssignments(source: Problem, reduced: Problem): Int {
        var count = 0
        val assignments = (0 until source.numIntVars).fold(listOf(emptyList<Long>())) { points, v ->
            val domain = source.finiteIntDomain(v)
            points.flatMap { point -> (domain.min..domain.max).map { point + it } }
        }
        for (values in assignments) {
            val witness = object : ExactWitness {
                override fun truth(boolVar: Int): Boolean = error("no Boolean columns")
                override fun at(column: Int): BigFraction = BigFraction.ofLong(values[column])
            }
            val before = source.factors.all { factor ->
                factor.linearRows.all { it.exactComparison(0, true, witness::truth).holdsAt(witness) }
            }
            val after = reduced.factors.all { factor ->
                factor.linearRows.all { it.exactComparison(0, true, witness::truth).holdsAt(witness) }
            }
            assertEquals(before, after, "values=$values")
            if (before) count++
        }
        return count
    }

    @Test
    fun `a row whose extra term is open should not be dominated`() {
        val openHi = Bits(3).also { it.set(2) }
        val problem = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0, 0, 0),
                longArrayOf(3, 3, 0),
                null,
                openHi,
            ),
            factors = arrayOf(le(2, 0, 1, 1, 1), le(5, 0, 1, 1, 1, 2, 1)),
        )

        val delta = Presolve.removeRedundantSourceConstraints(problem, Cancellation.Never)

        assertTrue(delta.droppedIndices.isEmpty())
    }

    @Test
    fun `a zero coefficient should carry no support`() {
        // A zero coefficient in the support map would make the dominance ratio check divide by zero.
        val problem = Problem(0, 2, dom(2, 3), listOf(le(2, 0, 0, 1, 1), le(5, 0, 1, 1, 1)))
        val out = checkPreserved("zero-coeff-subset", problem, expectDrop = true)
        assertEquals(1, out.factors.size)
    }

    @Test
    fun `a variable-subset row should be kept when extra activity exceeds the slack`() {
        val problem = Problem(0, 3, dom(3, 3), listOf(le(2, 0, 1, 1, 1), le(3, 0, 1, 1, 1, 2, 1)))
        checkPreserved("subset-not-dominated", problem, expectDrop = false)
    }

    private fun pos(v: Int) = Lit.make(v, true)

    private fun feasibleCountBools(problem: Problem): Int {
        val b = problem.numBoolVars
        var count = 0
        for (mask in 0 until (1 shl b)) {
            var a = Assumptions.None
            for (v in 0 until b) a = a.withBool(v, (mask shr v) and 1 == 1)
            if (problem.propagate(a) !is PropagationResult.Unsat) count++
        }
        return count
    }

    private fun checkPbPreserved(name: String, problem: Problem, expectDrop: Boolean): Problem {
        val baked = problem.bake()
        val delta = Presolve.removeRedundantConstraints(baked)
        val out = baked.withPassDelta(delta, BakeConfig.NONE)
        assertEquals(feasibleCountBools(problem), feasibleCountBools(out), "$name: feasible set changed")
        if (expectDrop) {
            assertTrue(out.factors.size < problem.factors.size, "$name: expected a constraint to be dropped")
        } else {
            assertTrue(delta.isEmpty, "$name: expected no change")
        }
        return out
    }

    @Test
    fun `a weighted knapsack implied by a partial clique cover should be dropped`() {
        // Clique-aware activity is 5 + max(2, 2) = 7.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(
                Cardinality(intArrayOf(pos(1), pos(2)), min = 0, max = 1),
                PseudoBoolean(longArrayOf(5, 2, 2), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 7L),
            ),
        )
        checkPbPreserved("clique-partial-cover", problem, expectDrop = true)
    }

    @Test
    fun `a knapsack not implied by the clique should be kept`() {
        // Clique-aware activity is 5 + 2 = 7 > 6.
        val problem = Problem(
            3,
            0,
            emptyArray(),
            listOf(
                Cardinality(intArrayOf(pos(1), pos(2)), min = 0, max = 1),
                PseudoBoolean(longArrayOf(5, 2, 2), intArrayOf(pos(0), pos(1), pos(2)), PbOp.LE, 6L),
            ),
        )
        checkPbPreserved("clique-not-implied", problem, expectDrop = false)
    }

    @Test
    fun `an all-different over overlapping domains should be kept`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            listOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 2)),
        )
        checkPreserved("real-alldiff", problem, expectDrop = false)
    }
}
