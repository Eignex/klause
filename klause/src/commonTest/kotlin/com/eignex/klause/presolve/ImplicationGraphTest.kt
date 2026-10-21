package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.model.PbOp
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImplicationGraphTest {
    private val cap = 1024

    @Test
    fun `source equivalence normalization should preserve every arithmetic assignment`() {
        val problem = Problem(
            3, 1, arrayOf(IntDomain(0, 5)),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(0, true))),
                ReifiedLinear(2, intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
            ),
        )

        val delta = ImplicationGraph.normalizeSourceEquivalences(problem, Cancellation.Never)
        val normalized = assertNotNull(problem.withSourcePassDelta(delta))

        assertTrue(delta.rebuild.isEmpty)
        assertEquals(0, normalized.factors.filterIsInstance<ReifiedLinear>().single().boolVars.single())
        for (mask in 0 until 8) {
            for (value in 0L..5L) {
                val sample = Sample(BooleanArray(3) { mask and (1 shl it) != 0 }, longArrayOf(value))
                assertEquals(isFeasible(problem, sample), isFeasible(normalized, sample), "$mask/$value")
            }
        }
    }

    @Test
    fun `normalized source equivalences should not cause another rebuild`() {
        val problem = Problem(
            4, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
            ),
        )
        val first = ImplicationGraph.normalizeSourceEquivalences(problem, Cancellation.Never)
        val normalized = assertNotNull(problem.withSourcePassDelta(first))

        val repeated = ImplicationGraph.normalizeSourceEquivalences(normalized, Cancellation.Never)

        assertFalse(first.isEmpty)
        assertTrue(repeated.isEmpty)
    }

    @Test
    fun `source normalization should keep opposite polarity aliases distinct`() {
        val problem = Problem(
            2, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )

        val delta = ImplicationGraph.normalizeSourceEquivalences(problem, Cancellation.Never)

        assertTrue(delta.isEmpty)
        assertFalse(delta.infeasible)
    }

    @Test
    fun `a contradictory completed binary source component should refute preparation`() {
        val problem = Problem(
            2, 0, emptyArray(),
            listOf(true, false).flatMap { left ->
                listOf(true, false).map { right ->
                    Clause(intArrayOf(Lit.make(0, left), Lit.make(1, right)))
                }
            },
        )

        val prepared = Presolver.runSource(problem, PresolveConfig.parse("off,+bool-equivalences"))

        assertTrue(prepared.infeasible)
    }

    @Test
    fun `source alias links should preserve objective coordinates in counting queries`() {
        val problem = Problem(
            2, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(1, true))),
            ),
        )

        val prepared = Presolver.runSource(
            problem,
            PresolveConfig.parse("off,+bool-equivalences"),
            PresolveContext(solutionSetSensitive = true, objectiveBoolCoeffs = mapOf(1 to -7L)),
        )

        assertEquals(2, prepared.problem.numBoolVars)
        assertTrue(prepared.rebuild.isEmpty)
        for (mask in 0 until 4) {
            val sample = Sample(booleanArrayOf(mask and 1 != 0, mask and 2 != 0), longArrayOf())
            assertEquals(isFeasible(problem, sample), isFeasible(prepared.problem, sample), "mask=$mask")
        }
    }

    @Test
    fun `interrupted source normalization should publish no partial alias rewrite`() {
        val problem = Problem(
            128, 0, emptyArray(),
            (0 until 128).map { variable ->
                Clause(intArrayOf(Lit.make(variable, false), Lit.make((variable + 1) % 128, true)))
            },
        )
        for (stopAt in listOf(1, 5, 150, 400)) {
            var polls = 0

            val delta = ImplicationGraph.normalizeSourceEquivalences(problem, Cancellation { ++polls == stopAt })

            assertTrue(delta.isEmpty, "stopAt=$stopAt")
            assertTrue(delta.rebuild.isEmpty)
            assertFalse(delta.infeasible)
        }
    }

    @Test
    fun `source normalization should spend its containing work allowance`() {
        val problem = Problem(
            128, 0, emptyArray(),
            (0 until 128).map { variable ->
                Clause(intArrayOf(Lit.make(variable, false), Lit.make((variable + 1) % 128, true)))
            },
        )
        for (allowance in listOf(1L, 50_000L)) {
            val budget = PresolveBudget(allowance)

            val delta = ImplicationGraph.normalizeSourceEquivalences(problem, budget.orSpent(Cancellation.Never))

            assertTrue(delta.isEmpty, "allowance=$allowance")
            assertTrue(budget.spent() >= allowance, "allowance=$allowance")
            assertFalse(delta.infeasible)
        }
    }

    @Test
    fun `source normalization should preserve reified auxiliary separation`() {
        val problem = Problem(
            2, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
                ReifiedPseudoBoolean(1, longArrayOf(1L), intArrayOf(Lit.make(0, true)), PbOp.GE, 1L),
            ),
        )

        val delta = ImplicationGraph.normalizeSourceEquivalences(problem, Cancellation.Never)

        assertTrue(delta.isEmpty)
        assertFalse(delta.infeasible)
    }

    @Test
    fun `default source preparation should normalize arithmetic Boolean equivalences`() {
        val problem = Problem(
            2, 1, arrayOf(IntDomain(0, 5)),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
            ),
        )

        val prepared = Presolver.runSource(problem, PresolveConfig.parse("default"))

        assertTrue(PresolvePass.NORMALIZE_BOOLEAN_EQUIVALENCES in prepared.passesFired)
        assertFalse(prepared.infeasible)
    }

    @Test
    fun `finite presolve should not repeat source equivalence analysis`() {
        val problem = Problem(
            2, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
            ),
        )
        val budget = PresolveBudget(10_000L)

        Presolver.run(
            problem.bake(),
            PresolveConfig.parse("off,+bool-equivalences"),
            PresolveContext(presolveBudget = budget),
            budget.orSpent(Cancellation.Never),
        )

        assertTrue(budget.invocations.isEmpty())
        assertEquals(0L, budget.spent())
    }

    private fun isFeasible(problem: Problem, sample: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, sample.bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, sample.ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun reduced(problem: Problem, objectiveBoolVars: Set<Int> = emptySet()): Problem {
        val baked = problem.bake()
        val delta = Presolve.reduceImplicationGraph(baked, cap, objectiveBoolVars = objectiveBoolVars)
        return baked.withPassDelta(delta, BakeConfig.NONE)
    }

    @Test
    fun `merging should keep equivalent cardinality literals distinct`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
                Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true)), min = 0, max = 1),
            ),
        )

        val cardinality = reduced(problem).factors.filterIsInstance<Cardinality>().single()
        assertEquals(intArrayOf(0, 1).toList(), cardinality.boolVars.toList())
    }

    @Test
    fun `anti-equivalent variables should not be merged`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
            ),
        )
        assertTrue(reduced(problem).factors.any { 1 in it.boolVars })
    }

    @Test
    fun `an objective variable should never be merged`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
            ),
        )
        assertTrue(reduced(problem, objectiveBoolVars = setOf(1)).factors.any { 1 in it.boolVars })
    }

    @Test
    fun `an unmerged equivalence cycle should keep the binaries that state it`() {
        val pairs = listOf(0 to 1, 1 to 0, 1 to 2, 2 to 1, 0 to 2, 2 to 0)
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = pairs.map { (a, b) -> Clause(intArrayOf(Lit.make(a, false), Lit.make(b, true))) },
        )

        val reduced = reduced(problem, objectiveBoolVars = setOf(0, 1, 2))

        val split = Sample(booleanArrayOf(true, false, false), LongArray(0))
        assertTrue(!isFeasible(reduced, split))
    }

    @Test
    fun `an equivalence a row reads should be renamed on a model with an open column`() {
        val open = Bits(1).also { it.set(0) }
        val problem = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, open),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
            ),
        )

        val delta = Presolve.reduceSourceImplicationGraph(problem, Cancellation.Never, emptySet())

        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        assertTrue(reduced.factors.none { 1 in it.boolVars })
        val lifted = booleanArrayOf(true, false).also { delta.rebuild.rebuildInto(it) }
        assertEquals(listOf(true, true), lifted.toList())
    }
    @Test
    fun `unused and root fixed columns should not starve equivalence harvesting`() {
        val problem = Problem(
            8, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(4, true))),
                Clause(intArrayOf(Lit.make(4, false), Lit.make(5, true))),
                Clause(intArrayOf(Lit.make(6, false), Lit.make(7, true))),
                Clause(intArrayOf(Lit.make(7, false), Lit.make(6, true))),
            ),
        ).bake()

        val delta = Presolve.reduceImplicationGraph(problem, 2)

        assertNotNull(delta.reconstruct)
        assertTrue(problem.withPassDelta(delta, BakeConfig.NONE).factors.none { 7 in it.boolVars })
    }

    @Test
    fun `unused columns should cause no implication probe construction`() {
        val problem = Problem(16, 0, emptyArray(), emptyList()).bake()
        val budget = PresolveBudget(Long.MAX_VALUE)

        val delta = Presolve.reduceImplicationGraph(problem, 4, budget.orSpent(Cancellation.Never))

        assertTrue(delta.isEmpty)
        assertEquals(0L, budget.rootConstructionEntries)
    }

    @Test
    fun `graph construction and SCC discovery should stop at the next metered visit`() {
        val pairs = listOf(0 to 1, 1 to 0) + (2 until 16).map { 2 to (it + 1) }
        val problem = Problem(
            17, 0, emptyArray(),
            pairs.map { (a, b) -> Clause(intArrayOf(Lit.make(a, false), Lit.make(b, true))) },
        )
        for (allowance in listOf(1L, 31L, 491L, 681L, 1001L)) {
            val budget = PresolveBudget(allowance)

            val delta = Presolve.reduceSourceImplicationGraph(problem, budget.orSpent(Cancellation.Never), emptySet())

            assertTrue(delta.isEmpty, "allowance=$allowance")
            assertTrue(delta.rebuild.isEmpty)
            assertTrue(budget.spent() in allowance..allowance + 9, "spent=${budget.spent()}")
        }
    }

    @Test
    fun `interrupted reachability should keep the candidate clause and its implications`() {
        val pairs = listOf(0 to 9) + (0 until 8).map { it to (it + 1) }
        val problem = Problem(
            10, 0, emptyArray(),
            pairs.map { (a, b) -> Clause(intArrayOf(Lit.make(a, false), Lit.make(b, true))) },
        )
        for (allowance in listOf(1851L, 1901L, 1951L)) {
            val budget = PresolveBudget(allowance)

            val delta = Presolve.reduceSourceImplicationGraph(
                problem, budget.orSpent(Cancellation.Never), (0 until 10).toSet(),
            )

            assertTrue(delta.isEmpty, "allowance=$allowance")
            assertTrue(budget.spent() in allowance..allowance + 9, "spent=${budget.spent()}")
            val retained = assertNotNull(problem.withSourcePassDelta(delta))
            val forced = retained.propagate(Assumptions.None.withBool(0, true)) as PropagationResult.Implied
            assertEquals(true, forced.boolValueOrNull(9))
        }
    }

    @Test
    fun `external cancellation during graph analysis should publish no incomplete merges`() {
        val problem = Problem(
            128, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(0, true))),
            ),
        )
        var polls = 0
        val token = Cancellation { ++polls == 5 }

        val delta = Presolve.reduceSourceImplicationGraph(problem, token, emptySet())

        assertTrue(delta.isEmpty)
        assertTrue(delta.rebuild.isEmpty)
        assertEquals(5, polls)
    }
}
