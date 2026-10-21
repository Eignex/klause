package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.presolve.PresolveConfig
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.difference.DifferenceSystem
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.undoTo
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.pipeline.FiniteEngine
import com.eignex.klause.solver.pipeline.FinitePipeline
import com.eignex.klause.solver.pipeline.FinitePipelineRequest
import com.eignex.klause.solver.result.PresolvePreparationStage
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RealDifferenceFragmentsTest {
    @Test
    fun `finite real graph assembly should retain cost independently of elective passes`() {
        val budget = PresolveBudget(0L)
        val problem = Problem(
            0, 1, arrayOf(IntDomain(0, 1)),
            arrayOf(
                ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                    LinearOp.EQ, BigFraction.ONE),
                RealElement(0, 0, doubleArrayOf(0.0, 1.0), indexOffset = 0),
            ),
            numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )

        val preparation = FinitePipeline.prepare(
            FinitePipelineRequest(
                problem, FiniteEngine.BACKTRACK, presolveConfig = PresolveConfig.NONE,
                presolveBudget = budget,
            ),
        )

        val effort = assertNotNull(assertNotNull(preparation.presolve).effort)
        val graph = effort.preparationStages.single { it.stage == PresolvePreparationStage.REAL_DIFFERENCE }
        assertTrue(graph.nativeWork > 0L)
        assertEquals(0L, graph.work)
        assertEquals(1, graph.input.reals)
        assertEquals(graph.input.factors + 1, graph.output.factors)
        assertTrue(effort.invocations.isEmpty())
    }

    @Test
    fun `a strict real cycle should refute an open source guard`() {
        val problem = Problem(1, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ONE, BigFraction.ONE.negated()), LinearOp.LE, BigFraction.ZERO),
            ExactLinear.of(intArrayOf(Term.ofRealVar(1), Term.ofRealVar(0)),
                listOf(BigFraction.ONE, BigFraction.ONE.negated()), LinearOp.LE, BigFraction.ZERO,
                strict = true, activator = 0),
        ), numRealVars = 2, realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY }).bake()
            .withRealDifferenceSystem(Cancellation.Never)
        val state = PropagationState(problem, Assumptions.None)
        val id = problem.numFactors - 1

        assertTrue(PropagationProblem(problem).propagators[id].propagate(state, id))

        assertEquals(false, state.boolValues[0])
        assertEquals(emptyList(), state.boolAntecedents[0]?.toList())
    }

    @Test
    fun `a strict comparison complement should retain its equality boundary`() {
        val problem = Problem(1, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ZERO),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ZERO, strict = true, activator = 0),
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY)).bake()
            .withRealDifferenceSystem(Cancellation.Never)
        val state = PropagationState(problem, Assumptions.None)
        val id = problem.numFactors - 1
        val propagator = PropagationProblem(problem).propagators[id]

        assertTrue(propagator.propagate(state, id))
        assertTrue(propagator.propagate(state, id))

        assertEquals(false, state.boolValues[0])
    }

    @Test
    fun `a guarded real path should exclude its contradictory table selection`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ONE, BigFraction.ONE.negated()), LinearOp.LE, BigFraction.ZERO,
                strict = true, activator = 0),
            RealElement(0, 0, intArrayOf(1, 2), indexOffset = 0),
        ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY }).bake()
            .withRealDifferenceSystem(Cancellation.Never)
        val state = PropagationState(problem, Assumptions.None)
        assertTrue(state.pinLit(Lit.make(0, true)))
        val id = problem.numFactors - 1

        assertTrue(PropagationProblem(problem).propagators[id].propagate(state, id))

        assertEquals(IntDomain(1, 1), state.intDomains[0])
        val excluded = Lit.make(state.atomVarEq(0, 0), false)
        assertEquals(true, state.litTruth(excluded))
        assertEquals(listOf(Lit.make(0, false)),
            state.atoms.ant[Lit.variable(excluded) - problem.numBoolVars]?.toList())
    }

    @Test
    fun `a sibling should release a table exclusion and retract its selected edges`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 1)), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ONE, BigFraction.ONE.negated()), LinearOp.LE, BigFraction.ZERO,
                strict = true, activator = 0),
            RealElement(0, 0, intArrayOf(1, 2), indexOffset = 0),
        ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY }).bake()
            .withRealDifferenceSystem(Cancellation.Never)
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        val root = state.mark()
        state.currentLevel = 1
        assertTrue(state.pinLit(Lit.make(0, true)))
        val id = problem.numFactors - 1
        val propagator = PropagationProblem(problem).propagators[id]
        assertTrue(propagator.propagate(state, id))
        assertEquals(IntDomain(1, 1), state.intDomains[0])
        state.undoTo(root)
        state.currentLevel = 1
        assertTrue(state.pinLit(Lit.make(0, false)))
        assertTrue(state.pinLit(Lit.make(state.atomVarEq(0, 0), true)))

        assertTrue(propagator.propagate(state, id))

        assertEquals(IntDomain(0, 0), state.intDomains[0])
    }

    @Test
    fun `table atoms should belong to each receiving propagation state`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ONE),
            RealElement(0, 0, doubleArrayOf(0.0, 1.0), indexOffset = 0),
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY)).bake()
            .withRealDifferenceSystem(Cancellation.Never)
        val first = PropagationState(problem, Assumptions.None)
        val second = PropagationState(problem, Assumptions.None)
        second.atomVarEq(1, 0)
        val id = problem.numFactors - 1
        val propagator = PropagationProblem(problem).propagators[id]

        assertTrue(propagator.propagate(first, id))
        assertTrue(propagator.propagate(second, id))

        assertEquals(IntDomain(1, 1), first.intDomains[0])
        assertEquals(IntDomain(1, 1), second.intDomains[0])
        assertEquals(IntDomain(0, 1), second.intDomains[1])
    }

    @Test
    fun `integer remapping should leave private real graph vertices independent`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(2)), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ONE),
            RealElement(1, 2, doubleArrayOf(0.0, 1.0), indexOffset = 0),
        ), numRealVars = 3, realLower = DoubleArray(3) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(3) { Double.POSITIVE_INFINITY }).bake()
            .withRealDifferenceSystem(Cancellation.Never)
        val system = assertIs<DifferenceSystem>(problem.factors.last())
        val remapped = system.remap(VarRemap(intArrayOf(), intArrayOf(1, 0)))
        val projected = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(remapped))
        val state = PropagationState(projected, Assumptions.None)

        assertTrue(PropagationProblem(projected).propagators[0].propagate(state, 0))

        assertEquals(IntDomain(1, 1), state.intDomains[0])
        assertEquals(IntDomain(0, 1), state.intDomains[1])
        assertTrue(remapped.variables.reals.isEmpty())
    }

    @Test
    fun `unsupported real rows should supply no graph contradiction`() {
        val problem = Problem(0, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.LE, BigFraction.ONE.negated()),
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ONE, BigFraction.ONE.negated()), LinearOp.LE, BigFraction.ZERO),
        ), numRealVars = 2, realLower = doubleArrayOf(0.0, 0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)).bake()
            .withRealDifferenceSystem(Cancellation.Never)
        val state = PropagationState(problem, Assumptions.None)
        val id = problem.numFactors - 1

        assertTrue(PropagationProblem(problem).propagators[id].propagate(state, id))

        assertIs<DifferenceSystem>(problem.factors.last())
    }

    @Test
    fun `stopped real graph preparation should discard its prefix and retain spent work`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(
            RealElement(0, 0, doubleArrayOf(0.0, 1.0), indexOffset = 0),
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY)).bake()
        var work = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 4L
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }

        val result = problem.withRealDifferenceSystem(stop)

        assertSame(problem, result)
        assertTrue(work >= 4L)
        assertFalse(result.factors.any { it is DifferenceSystem })
    }

    @Test
    fun `a positive fractional cycle should retain feasible strict rows`() {
        val half = BigFraction.of(bigIntOf(1), bigIntOf(2))
        val problem = Problem(0, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ONE, BigFraction.ONE.negated()), LinearOp.LE, half, strict = true),
            ExactLinear.of(intArrayOf(Term.ofRealVar(1), Term.ofRealVar(0)),
                listOf(BigFraction.ONE, BigFraction.ONE.negated()), LinearOp.LE, BigFraction.ZERO),
        ), numRealVars = 2, realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY }).bake()
            .withRealDifferenceSystem(Cancellation.Never)
        val state = PropagationState(problem, Assumptions.None)
        val id = problem.numFactors - 1

        assertTrue(PropagationProblem(problem).propagators[id].propagate(state, id))

        assertIs<DifferenceSystem>(problem.factors.last())
    }

    @Test
    fun `an unconditional strict real cycle should explain a root contradiction`() {
        val problem = Problem(0, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0), Term.ofRealVar(1)),
                listOf(BigFraction.ONE, BigFraction.ONE.negated()), LinearOp.LE, BigFraction.ZERO, strict = true),
            ExactLinear.of(intArrayOf(Term.ofRealVar(1), Term.ofRealVar(0)),
                listOf(BigFraction.ONE, BigFraction.ONE.negated()), LinearOp.LE, BigFraction.ZERO),
        ), numRealVars = 2, realLower = DoubleArray(2) { Double.NEGATIVE_INFINITY },
            realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY }).bake()
            .withRealDifferenceSystem(Cancellation.Never)
        val state = PropagationState(problem, Assumptions.None)
        val id = problem.numFactors - 1
        val propagator = PropagationProblem(problem).propagators[id]

        assertFalse(propagator.propagate(state, id))

        assertEquals(emptyList(), propagator.conflictReason(state, id)?.toList())
    }

    @Test
    fun `finite preparation should retain source factors alongside real graph deductions`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.EQ, BigFraction.ONE),
            RealElement(0, 0, doubleArrayOf(0.0, 1.0), indexOffset = 0),
        ), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))

        val preparation = FinitePipeline.prepare(FinitePipelineRequest(problem, FiniteEngine.BACKTRACK,
            presolveConfig = PresolveConfig.NONE))
        val prepared = preparation.executableProblem()

        assertEquals(problem.numFactors + 1, prepared.numFactors)
        for (id in problem.factors.indices) assertSame(problem.factors[id], prepared.factors[id])
        assertIs<DifferenceSystem>(prepared.factors.last())
        val point = Sample(booleanArrayOf(), longArrayOf(1), doubleArrayOf(1.0))
        val restored = assertNotNull(preparation.sourceMapping).reconstructFrom(prepared, point)
        assertEquals(1.0, restored.reals.single())
        assertEquals(1L, restored.ints.single())
    }

    @Test
    fun `approximate source semantics should retain their arithmetic owner without an exact graph`() {
        val semantics = SourceSemantics(SourceFormat.IR,
            feasibilityTolerance = SourceTolerance(absolute = SemanticRational.ONE)).resolve()
        val problem = Problem(0, 0, emptyArray(), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ZERO),
        ), numRealVars = 1, realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(2.0),
            semantics = semantics).bake()

        val prepared = problem.withRealDifferenceSystem(Cancellation.Never)

        assertSame(problem, prepared)
        assertFalse(prepared.factors.any { it is DifferenceSystem })
    }
}
