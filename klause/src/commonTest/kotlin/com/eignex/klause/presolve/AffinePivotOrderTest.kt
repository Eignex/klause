package com.eignex.klause.presolve

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Move
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.linear.AffineSingletons
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.Assignment
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.pipeline.ExecutionOperation
import com.eignex.klause.solver.pipeline.ExecutionStrategy
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.SourceSetupRequest
import com.eignex.klause.solver.pipeline.heuristicHints
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AffinePivotOrderTest {
    @Test
    fun `rewrite cost should reconstruct an xcsp flow input used by a comparison objective`() {
        val parsed = Xcsp3.parse("""
            <instance format="XCSP3" type="COP">
              <variables>
                <var id="x">0..4</var><var id="a">0..4</var><var id="b">0..4</var>
              </variables>
              <constraints>
                <sum><list>a b x</list><coeffs>1 -1 -1</coeffs><condition>(eq,-2)</condition></sum>
              </constraints>
              <objectives>
                <minimize type="sum"><list>a b gt(x,0)</list><coeffs>1 1 7</coeffs></minimize>
              </objectives>
            </instance>
        """.trimIndent())
        val objective = assertNotNull(parsed.objective).toLinearObjective()

        val delta = AffineSingletons.eliminateSourceAffineSingletons(parsed.problem, objective = objective,
            pivotOrder = AffinePivotOrder.REWRITE_COST)
        val lift = assertNotNull(delta.rebuild.asSampleLift(),
            "${parsed.problem.factors.map { it::class.simpleName }} $objective")
        val rebuilt = lift(Sample(
            BooleanArray(parsed.problem.numBoolVars) { true },
            LongArray(parsed.problem.numIntVars) { if (it == parsed.intVarNames.getValue("x")) 0L else 1L },
        ))

        assertEquals(2L, rebuilt.ints[parsed.intVarNames.getValue("x")])
        var pins = Assumptions.None
        for (id in rebuilt.ints.indices) pins = pins.withInt(id, rebuilt.ints[id])
        for (id in rebuilt.bools.indices) pins = pins.withBool(id, rebuilt.bools[id])
        assertFalse(parsed.problem.propagate(pins) is PropagationResult.Unsat)
    }

    @Test
    fun `channel definitions and gradients should survive a multi input affine reconstruction`() {
        val source = Problem(1, 3, Array(3) { IntDomain(0, 4) }, listOf(
            Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
        ))
        val hint = DefinitionHint.Comparison(0, longArrayOf(1),
            arrayOf(DefinitionHint.Operand.Variable(0)), 2, LinearOp.GE)
        val objective = LinearObjective(boolWeights = longArrayOf(7), intCoefficients = longArrayOf(0, 1, 1))
        for (order in listOf(AffinePivotOrder.STRUCTURED, AffinePivotOrder.REWRITE_COST)) {
            val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, listOf(hint)),
                ExecutionOperation.OPTIMIZE, FiniteEngine.LOCAL_SEARCH, objective))
            val shape = invocation.finiteShape()
            val prepared = FinitePipeline.prepare(FinitePipelineRequest(
                shape.problem, FiniteEngine.LOCAL_SEARCH, shape.linearObjective,
                PresolveConfig.parse("off,+affine").withAffinePivotOrder(order),
                explicitPresolveConfig = true, preparedPlan = shape.preparedPlan,
            ))
            val view = assertNotNull(invocation.heuristicHints(prepared.problem, prepared.objective,
                prepared.sourceMapping, Cancellation.Never, setOf(ExecutionStrategy.LOCAL_SEARCH),
                assertNotNull(prepared.executionPlan).capabilities))
            val assignment = Assignment(prepared.problem.numBoolVars, prepared.problem.numIntVars)
            assignment.setInt(2, 1)

            assertNotNull(view.sweep).sweep(assignment, prepared.problem.finiteIntDomains())

            assertFalse(assignment.boolValue(0))
            assertEquals(8.0, assertNotNull(view.objective).deltaIfApplied(assignment, Move.IntSet(1, 1)))
        }
    }

    @Test
    fun `an inadmissible equality should leave rewrite scoring allowance for a later fold`() {
        var stopped = false
        val table = Table(intArrayOf(0, 1, 2), longArrayOf(0, 0, 4, 1, 1, 2, 2, 2, 0))
        val consumer = object : Factor by table {
            override val structuralKeyWeight: Int get() {
                stopped = true
                return table.structuralKeyWeight
            }
        }
        val source = Problem(0, 7, Array(7) { IntDomain(0, 4) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 4), consumer,
            Linear(intArrayOf(1, 1, 1), intArrayOf(3, 4, 5), LinearOp.EQ, 5),
            Linear(intArrayOf(1, 1), intArrayOf(3, 6), LinearOp.LE, 7),
            Linear(intArrayOf(1, -1), intArrayOf(3, 6), LinearOp.GE, -2),
        ))

        val delta = AffineSingletons.eliminateSourceAffineSingletons(source,
            cancellation = Cancellation { stopped }, pivotOrder = AffinePivotOrder.REWRITE_COST)

        assertFalse(delta.isEmpty)
    }

    @Test
    fun `an admitted pivot should remain available without scoring a protected objective payload`() {
        var stopped = false
        val table = Table(intArrayOf(0, 3), longArrayOf(0, 0, 1, 1, 2, 2))
        val consumer = object : Factor by table {
            override val structuralKeyWeight: Int get() {
                stopped = true
                return table.structuralKeyWeight
            }
        }
        val source = Problem(0, 4, Array(4) { IntDomain(0, 4) }, listOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 4), consumer,
        ))

        val delta = AffineSingletons.eliminateSourceAffineSingletons(source,
            cancellation = Cancellation { stopped }, pivotOrder = AffinePivotOrder.REWRITE_COST,
            objective = LinearObjective(intCoefficients = longArrayOf(1, 0, 0, 0)))

        assertFalse(delta.isEmpty)
    }

    @Test
    fun `rewrite cost should preserve propagated channel truth after eliminating its alias`() {
        val source = Problem(1, 3, arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(0, 2)), listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            Table(intArrayOf(0, 2), longArrayOf(1, 0, 1, 1, 1, 2, 2, 0, 2, 1, 2, 2, 3, 0, 3, 1, 3, 2)),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(1), LinearOp.EQ, 2),
        ))
        val reduced = Presolver.runSource(source,
            PresolveConfig.parse("off,+affine").withAffinePivotOrder(AffinePivotOrder.REWRITE_COST))
        assertNotNull(reduced.rebuild.asSampleLift())

        for (value in 1L..3L) {
            val pins = Assumptions.None.withInt(0, value).withBool(0, value == 2L)
            val wrongTruth = Assumptions.None.withInt(0, value).withBool(0, value != 2L)

            assertFalse(reduced.problem.propagate(pins) is PropagationResult.Unsat)
            assertTrue(reduced.problem.propagate(wrongTruth) is PropagationResult.Unsat)
        }
    }

    @Test
    fun `rewrite cost should avoid extra lp bound rows by eliminating a contained sum partner`() {
        val source = Problem(0, 4,
            arrayOf(IntDomain(0, 4), IntDomain(-4, 4), IntDomain(0, 4), IntDomain(0, 4)), listOf(
                Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 2, 3), LinearOp.LE, 4),
            ))
        val config = PresolveConfig.parse("off,+affine")
        val byFirst = Presolver.runSource(source, config.withAffinePivotOrder(AffinePivotOrder.MARKOWITZ))
        val byStructured = Presolver.runSource(source, config.withAffinePivotOrder(AffinePivotOrder.STRUCTURED))
        val byCost = Presolver.runSource(source, config.withAffinePivotOrder(AffinePivotOrder.REWRITE_COST))

        val firstRows = CpToLpRelaxation(byFirst.problem, null).build(RootDomains(byFirst.problem)).model.m
        val structuredRows = CpToLpRelaxation(byStructured.problem, null)
            .build(RootDomains(byStructured.problem)).model.m
        val costRows = CpToLpRelaxation(byCost.problem, null).build(RootDomains(byCost.problem)).model.m
        val lifted = assertNotNull(byCost.rebuild.asSampleLift())(Sample(BooleanArray(0), longArrayOf(1, 0, 3, 0)))

        assertEquals(3, firstRows)
        assertEquals(firstRows, structuredRows)
        assertEquals(1, costRows)
        assertEquals(-2L, lifted.ints[1])
    }

    @Test
    fun `interrupted rewrite scoring should leave the source unchanged`() {
        var stopped = false
        val table = Table(intArrayOf(0, 2), longArrayOf(1, 0, 2, 1, 3, 2))
        val consumer = object : Factor by table {
            override val structuralKeyWeight: Int get() {
                stopped = true
                return table.structuralKeyWeight
            }
        }
        val source = Problem(0, 3, arrayOf(IntDomain(1, 3), IntDomain(0, 2), IntDomain(0, 2)), listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1), consumer,
        ))

        val delta = AffineSingletons.eliminateSourceAffineSingletons(source,
            cancellation = Cancellation { stopped }, pivotOrder = AffinePivotOrder.REWRITE_COST)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `rewrite cost should preserve maintained element values and gradients after reversing its index definition`() {
        val domains = arrayOf(IntDomain(1, 3), IntDomain(0, 2), IntDomain(0, 2), IntDomain(2, 6))
        val source = Problem(0, 4, domains, listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
            Table(intArrayOf(0, 2), longArrayOf(1, 0, 1, 1, 1, 2, 2, 0, 2, 1, 2, 2, 3, 0, 3, 1, 3, 2)),
            Element(1, 3, longArrayOf(2, 4, 6), arrIsVars = false, indexOffset = 0),
        ))
        val objective = LinearObjective(intCoefficients = longArrayOf(0, 0, 0, 1))
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, intArrayOf(3), emptyList()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.LOCAL_SEARCH, objective,
        ))
        val shape = invocation.finiteShape()
        val preparation = FinitePipeline.prepare(FinitePipelineRequest(
            shape.problem, FiniteEngine.LOCAL_SEARCH, shape.linearObjective,
            PresolveConfig.parse("off,+affine").withAffinePivotOrder(AffinePivotOrder.REWRITE_COST),
            explicitPresolveConfig = true, preparedPlan = shape.preparedPlan,
        ))
        val view = assertNotNull(invocation.heuristicHints(preparation.problem, preparation.objective,
            preparation.sourceMapping, Cancellation.Never, setOf(ExecutionStrategy.LOCAL_SEARCH),
            assertNotNull(preparation.executionPlan).capabilities))
        val assignment = Assignment(preparation.problem.numBoolVars, preparation.problem.numIntVars)
        assignment.setInt(0, 3)

        assertNotNull(view.sweep).sweep(assignment, domains)

        assertEquals(6L, assignment.intValue(3))
        assertEquals(-4.0, assertNotNull(view.objective).deltaIfApplied(assignment, Move.IntSet(0, 1)))
    }

    @Test
    fun `rewrite cost should retain the larger table and reconstruct either affine direction through every host`() {
        for (lane in listOf("source", "incremental", "rebuilt")) {
            val table = Table(intArrayOf(0, 2), longArrayOf(1, 0, 1, 1, 1, 2, 2, 0, 2, 1, 2, 2, 3, 0, 3, 1, 3, 2))
            val source = Problem(0, 4,
                arrayOf(IntDomain(1, 3), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
                listOf(
                    Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
                    table,
                    Table(intArrayOf(1, 3), longArrayOf(0, 0, 1, 1, 2, 2)),
                ))
            val config = PresolveConfig.parse(if (lane == "rebuilt") "off,+affine,+bce" else "off,+affine")
                .withAffinePivotOrder(AffinePivotOrder.REWRITE_COST)
            val reduced: Problem
            val reconstruct: (Sample) -> Sample
            if (lane == "source") {
                val result = Presolver.runSource(source, config)
                reduced = result.problem
                reconstruct = result.rebuild.asSampleLift() ?: { it }
            } else {
                val result = Presolver.run(source.bake(), config)
                reduced = result.problem
                reconstruct = result.reconstruct
            }

            assertSame(table, reduced.factors.first { it === table }, lane)
            for (x in 1L..3L) {
                val sample = Sample(BooleanArray(0), longArrayOf(x, 0, 0, x - 1))
                val rebuilt = reconstruct(sample)
                assertEquals(x - 1, rebuilt.ints[1], lane)
                var pins = Assumptions.None
                for (v in rebuilt.ints.indices) pins = pins.withInt(v, rebuilt.ints[v])
                assertFalse(source.propagate(pins) is PropagationResult.Unsat, lane)
            }
        }
    }

    private fun sinkModel(sink: Int, wideFirst: Int): Problem {
        val perDef = 2
        val nVars = sink + sink * perDef + wideFirst
        val factors = ArrayList<Linear>()
        factors.add(Linear(LongArray(sink) { 1L }, IntArray(sink) { it }, LinearOp.LE, sink.toLong()))
        for (i in 0 until sink) {
            val p = sink + i * perDef
            factors.add(
                Linear(longArrayOf(1L, -1L, -1L), intArrayOf(i, p, p + 1), LinearOp.EQ, 0L),
            )
        }
        // Extra rows raise the fill cost of the lowest ids without changing their order.
        for (k in 0 until wideFirst) {
            val extra = sink + sink * perDef + k
            factors.add(Linear(longArrayOf(1L, 1L), intArrayOf(k, extra), LinearOp.LE, 8L))
        }
        return Problem(0, nVars, Array(nVars) { IntDomain(0, 4) }, factors)
    }

    private fun reduce(problem: Problem, order: AffinePivotOrder): Problem {
        val baked = problem.bake()
        return baked.withPassDelta(Presolve.eliminateAffineSingletons(baked, pivotOrder = order), BakeConfig.NONE)
    }

    private fun totalTerms(problem: Problem): Int = problem.factors.filterIsInstance<Linear>().sumOf { it.vars.size }

    @Test
    fun `ordering by fill should fold the cheap pivots that id order leaves behind`() {
        val problem = sinkModel(sink = 20, wideFirst = 10)

        val byId = reduce(problem, AffinePivotOrder.STABLE_ID)
        val byFill = reduce(problem, AffinePivotOrder.MARKOWITZ)

        assertTrue(
            totalTerms(byFill) < totalTerms(byId),
            "${totalTerms(byFill)} vs ${totalTerms(byId)}",
        )
    }
}
