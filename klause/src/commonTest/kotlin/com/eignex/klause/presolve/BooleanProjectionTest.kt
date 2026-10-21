package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.pipeline.OpenTheoryExecution
import com.eignex.klause.solver.pipeline.OpenTheoryPipeline
import com.eignex.klause.solver.pipeline.OpenTheoryRequest
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BooleanProjectionTest {
    @Test
    fun `all pairs of three variable clauses should preserve existential values and reconstruct witnesses`() {
        val clauses = (1 until 27).map { code ->
            var digits = code
            val literals = ArrayList<Int>()
            for (variable in 0 until 3) {
                when (digits % 3) {
                    1 -> literals.add(Lit.make(variable, true))
                    2 -> literals.add(Lit.make(variable, false))
                }
                digits /= 3
            }
            Clause(literals.toIntArray())
        }
        for (first in clauses) {
            for (second in clauses) {
                val problem = Problem(3, 0, emptyArray(), listOf(first, second))

                val delta = BooleanProjection.projectSource(problem, setOf(1, 2), Cancellation.Never)
                val reduced = if (delta.infeasible) null else assertNotNull(problem.withSourcePassDelta(delta))

                if (reduced != null) assertTrue(reduced.numFactors <= problem.numFactors)
                for (retained in 0 until 4) {
                    var before = false
                    var after = false
                    for (value in listOf(false, true)) {
                        val point = booleanArrayOf(value, retained and 1 != 0, retained and 2 != 0)
                        before = before || listOf(first, second).all { clause ->
                            clause.literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
                        }
                        val satisfies = reduced?.factors?.filterIsInstance<Clause>()?.all { clause ->
                            clause.literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
                        } ?: false
                        after = after || satisfies
                        if (satisfies) {
                            val lifted = point.copyOf()
                            delta.rebuild.rebuildInto(lifted)
                            assertTrue(listOf(first, second).all { clause ->
                                clause.literals.any { Lit.evaluate(it, lifted[Lit.variable(it)]) }
                            })
                            assertEquals(point.drop(1), lifted.drop(1))
                        }
                    }
                    assertEquals(before, after)
                }
            }
        }
    }

    @Test
    fun `a pure variable should recover its original polarity from a false representative`() {
        for (positive in listOf(false, true)) {
            val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(Lit.make(0, positive), 2))))
            val budget = PresolveBudget(Long.MAX_VALUE)

            val delta = BooleanProjection.projectSource(problem, setOf(1), budget.orSpent(Cancellation.Never))
            val point = booleanArrayOf(false, false)
            delta.rebuild.rebuildInto(point)

            assertEquals(listOf(1), (delta.addedFactors.single() as Clause).literals.toList())
            assertEquals(positive, point[0])
            assertFalse(point[1])
            assertEquals(1L, budget.sourceBooleansProjected)
        }
    }

    @Test
    fun `both polarities should retain the required resolvent beside the representative`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 2))))

        val delta = BooleanProjection.projectSource(problem, setOf(1), Cancellation.Never)

        assertEquals(setOf(listOf(1), listOf(2)), delta.addedFactors.map { (it as Clause).literals.toList() }.toSet())
        assertEquals(1, delta.rebuild.steps.size)
    }

    @Test
    fun `tautological resolvents should impose no retained variable restriction`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 3))))

        val delta = BooleanProjection.projectSource(problem, setOf(1), Cancellation.Never)

        assertEquals(listOf(1), (delta.addedFactors.single() as Clause).literals.toList())
        for (retained in listOf(false, true)) {
            val point = booleanArrayOf(false, retained)
            delta.rebuild.rebuildInto(point)
            assertEquals(!retained, point[0])
            assertEquals(retained, point[1])
        }
    }

    @Test
    fun `duplicate resolvents should contribute only one output clause`() {
        val positive = Clause(intArrayOf(0, 2))
        val negative = Clause(intArrayOf(1, 2))
        val problem = Problem(2, 0, emptyArray(), listOf(positive, positive, negative, negative))

        val delta = BooleanProjection.projectSource(problem, setOf(1), Cancellation.Never)

        assertEquals(4, delta.droppedIndices.size)
        assertEquals(2, delta.addedFactors.size)
        assertEquals(1, delta.addedFactors.count { (it as Clause).literals.contentEquals(intArrayOf(2)) })
    }

    @Test
    fun `an input tautology should drop without becoming reconstruction authority`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(0, 1)), Clause(intArrayOf(0, 2))))

        val delta = BooleanProjection.projectSource(problem, setOf(1), Cancellation.Never)
        val point = booleanArrayOf(false, false)
        delta.rebuild.rebuildInto(point)

        assertEquals(2, delta.droppedIndices.size)
        assertEquals(listOf(1), (delta.addedFactors.single() as Clause).literals.toList())
        assertEquals(1, delta.rebuild.steps.size)
        assertTrue(point[0])
    }

    @Test
    fun `non-clause Boolean readers should protect their variables on an open source`() {
        val open = Bits(1).also { it.set(0) }
        val readers = listOf(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 7),
            Cardinality(intArrayOf(0), min = 1, max = 1),
        )
        for (reader in readers) {
            val problem = Problem(
                numBoolVars = 2,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, open),
                factors = arrayOf<Factor>(reader, Clause(intArrayOf(0, 2))),
            )

            val delta = BooleanProjection.projectSource(problem, setOf(1), Cancellation.Never)

            assertTrue(delta.isEmpty)
            assertSame(problem, problem.withSourcePassDelta(delta))
        }
    }

    @Test
    fun `objective variables should retain their original values`() {
        val reader = ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 7)
        val problem = Problem(2, 1, arrayOf(com.eignex.klause.ir.IntDomain(0, 10)),
            listOf(reader, Clause(intArrayOf(0, 2))))
        val context = PresolveContext(objectiveBoolCoeffs = mapOf(0 to 3L))

        val delta = PresolvePass.PROJECT_SOURCE_BOOLEANS.applySource(problem, context)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `solution-set-sensitive queries should exclude projection under every override`() {
        val configs = listOf(PresolveConfig.AUTO, PresolveConfig.parse("project-booleans"), PresolveConfig.parse("all"))
        for (config in configs) {
            val context = PresolveContext(solutionSetSensitive = true)

            val passes = config.problemPasses(context, PresolvePass.Capability.SOURCE)

            assertFalse(PresolvePass.PROJECT_SOURCE_BOOLEANS in passes)
            assertFalse(config.resolved(PresolvePass.PROJECT_SOURCE_BOOLEANS, context))
        }
    }

    @Test
    fun `projection should run only in the canonical source schedule`() {
        val config = PresolveConfig.parse("project-booleans")

        val source = config.problemPasses(PresolveContext.EMPTY, PresolvePass.Capability.SOURCE)
        val finite = config.problemPasses(PresolveContext.EMPTY, PresolvePass.Capability.FINITE)

        assertEquals(listOf(PresolvePass.PROJECT_SOURCE_BOOLEANS), source)
        assertTrue(finite.isEmpty())
    }

    @Test
    fun `unused slots and an already fixed unit should remain unchanged`() {
        val problem = Problem(3, 0, emptyArray(), listOf(Clause(intArrayOf(0))))

        val delta = BooleanProjection.projectSource(problem, emptySet(), Cancellation.Never)

        assertTrue(delta.isEmpty)
        assertFalse(delta.infeasible)
    }

    @Test
    fun `chained projections should reconstruct later eliminated values first`() {
        val problem = Problem(3, 0, emptyArray(), listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 4))))

        val delta = BooleanProjection.projectSource(problem, setOf(2), Cancellation.Never)
        val point = booleanArrayOf(false, false, false)
        delta.rebuild.rebuildInto(point)

        assertEquals(listOf(false, true, false), point.toList())
        assertEquals(setOf(listOf(1), listOf(3)), delta.addedFactors.map { (it as Clause).literals.toList() }.toSet())
        assertTrue(problem.factors.filterIsInstance<Clause>().all { clause ->
            clause.literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
        })
    }

    @Test
    fun `transformed representatives should be forced at the propagation root`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(0, 2))))
        val delta = BooleanProjection.projectSource(problem, setOf(1), Cancellation.Never)

        val reduced = assertNotNull(problem.withSourcePassDelta(delta)).bake()
        val implied = assertIs<PropagationResult.Implied>(reduced.rootDeductions)

        assertEquals(false, implied.bools[0])
    }

    @Test
    fun `opposing units should refute the complete source`() {
        val problem = Problem(1, 0, emptyArray(), listOf(Clause(intArrayOf(0)), Clause(intArrayOf(1))))

        val delta = BooleanProjection.projectSource(problem, emptySet(), Cancellation.Never)

        assertTrue(delta.infeasible)
    }

    @Test
    fun `the representative clause should count against the bounded projection rule`() {
        val problem = Problem(5, 0, emptyArray(), listOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 4)),
            Clause(intArrayOf(1, 6)), Clause(intArrayOf(1, 8)),
        ))

        val delta = BooleanProjection.projectSource(problem, setOf(1, 2, 3, 4), Cancellation.Never)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `a resolution product above the existing guard should leave the variable intact`() {
        val factors = buildList {
            for (variable in 1..65) add(Clause(intArrayOf(0, Lit.make(variable, true))))
            for (variable in 66..130) add(Clause(intArrayOf(1, Lit.make(variable, true))))
        }
        val problem = Problem(131, 0, emptyArray(), factors)

        val delta = BooleanProjection.projectSource(problem, (1..130).toSet(), Cancellation.Never)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `a complete open witness should recover an original true value from a false slot`() {
        val open = Bits(1).also { it.set(0) }
        val problem = Problem(
            numBoolVars = 2,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, open),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(3)),
                ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 7),
            ),
        )
        val budget = PresolveBudget(Long.MAX_VALUE)
        val request = OpenTheoryRequest(problem).withPresolve(
            PresolveConfig.parse("project-booleans"), budget = budget,
        )

        val execution = assertIs<OpenTheoryExecution.Satisfy>(OpenTheoryPipeline.execute(request))
        val result = assertIs<OpenTheoryResult.Sat>(execution.result)

        assertTrue(result.assignment.boolValue(0))
        assertFalse(result.assignment.boolValue(1))
        assertEquals(1L, assertNotNull(result.stats.presolve).sourceBooleansProjected)
    }

    @Test
    fun `opposite source literal conditions should retain their original verdicts`() {
        for (value in listOf(true, false)) {
            val open = Bits(1).also { it.set(0) }
            val problem = Problem(
                numBoolVars = 2,
                intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, open),
                factors = arrayOf<Factor>(
                    Clause(intArrayOf(0, 2)), Clause(intArrayOf(3)),
                    Clause(intArrayOf(Lit.make(0, value))),
                    ReifiedLinear(1, intArrayOf(1), intArrayOf(0), LinearOp.LE, 7),
                ),
            )
            val request = OpenTheoryRequest(problem).withPresolve(PresolveConfig.parse("project-booleans"))

            val execution = assertIs<OpenTheoryExecution.Satisfy>(OpenTheoryPipeline.execute(request))

            if (value) assertTrue(assertIs<OpenTheoryResult.Sat>(execution.result).assignment.boolValue(0))
            else assertIs<OpenTheoryResult.Unsat>(execution.result)
        }
    }

    @Test
    fun `an already fired token should publish nothing and retain zero work`() {
        val problem = Problem(2, 0, emptyArray(), listOf(Clause(intArrayOf(0, 2))))
        val budget = PresolveBudget(Long.MAX_VALUE)

        val delta = BooleanProjection.projectSource(problem, emptySet(), budget.orSpent(Cancellation { true }))

        assertTrue(delta.isEmpty)
        assertEquals(0L, budget.spent())
        assertEquals(0L, budget.sourceBooleansProjected)
    }

    @Test
    fun `every work stop should retain only reconstructible transactions and spent debt`() {
        val problem = Problem(3, 0, emptyArray(), listOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 4)),
        ))
        val full = PresolveBudget(Long.MAX_VALUE)
        BooleanProjection.projectSource(problem, setOf(2), full.orSpent(Cancellation.Never))
        var partial = false
        for (allowance in 0..full.spent()) {
            val budget = PresolveBudget(allowance)

            val delta = BooleanProjection.projectSource(problem, setOf(2), budget.orSpent(Cancellation.Never))
            val reduced = assertNotNull(problem.withSourcePassDelta(delta))

            assertFalse(delta.infeasible)
            assertTrue(budget.spent() >= allowance)
            assertEquals(delta.rebuild.steps.size.toLong(), budget.sourceBooleansProjected)
            assertTrue(reduced.numFactors <= problem.numFactors)
            if (budget.sourceBooleansProjected == 1L) partial = true
            for (mask in 0 until 8) {
                val point = BooleanArray(3) { mask and (1 shl it) != 0 }
                if (reduced.factors.filterIsInstance<Clause>().all { clause ->
                        clause.literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
                    }
                ) {
                    delta.rebuild.rebuildInto(point)
                    assertTrue(problem.factors.filterIsInstance<Clause>().all { clause ->
                        clause.literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
                    }, "allowance=$allowance mask=$mask")
                }
            }
        }
        assertTrue(partial)
    }

    @Test
    fun `cancellation at each poll should discard unfinished variable transactions`() {
        val problem = Problem(3, 0, emptyArray(), listOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 4)),
        ))
        var fullPolls = 0
        BooleanProjection.projectSource(problem, setOf(2), Cancellation { fullPolls++; false })
        var partial = false
        for (stop in 1..fullPolls) {
            var polls = 0
            val budget = PresolveBudget(Long.MAX_VALUE)

            val delta = BooleanProjection.projectSource(
                problem, setOf(2), budget.orSpent(Cancellation { ++polls >= stop }),
            )
            val reduced = assertNotNull(problem.withSourcePassDelta(delta))

            assertFalse(delta.infeasible)
            assertEquals(delta.rebuild.steps.size.toLong(), budget.sourceBooleansProjected)
            if (budget.sourceBooleansProjected == 1L) partial = true
            for (mask in 0 until 8) {
                val point = BooleanArray(3) { mask and (1 shl it) != 0 }
                if (reduced.factors.filterIsInstance<Clause>().all { clause ->
                        clause.literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
                    }
                ) {
                    delta.rebuild.rebuildInto(point)
                    assertTrue(problem.factors.filterIsInstance<Clause>().all { clause ->
                        clause.literals.any { Lit.evaluate(it, point[Lit.variable(it)]) }
                    }, "stop=$stop mask=$mask")
                }
            }
        }
        assertTrue(partial)
    }
}
