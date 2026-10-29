package com.eignex.klause.lp.engine

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toProblem
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.lp.openLpInfeasible
import com.eignex.klause.lp.relaxation.LeafRealSolver
import com.eignex.klause.lp.relaxation.leafRealFeasibility
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toLinearObjective
import com.eignex.klause.solver.result.LpRoute
import com.eignex.klause.solver.result.LpStatsSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpClaimTest {
    @Test
    fun `bound requests should avoid primal reconstruction and continuation`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 3.0, cost = 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
        val sink = LpStatsSink(LpRoute.STANDALONE)

        val result = solveAndCertify(builder.build(Sense.MINIMIZE), claim = LpClaim.BOUND,
            observer = sink.certificationObserver())

        assertEquals(LpVerdict.CERTIFIED_BOUND, result.verdict)
        assertEquals(BigFraction.ONE, result.lowerBound)
        assertNull(result.witness)
        assertNull(result.continuation)
        assertNull(result.refinement)
        assertEquals(0, result.reconstruction?.pointChecks ?: 0)
        val request = assertNotNull(sink.snapshot().phases["CLAIM_BOUND_STANDALONE"])
        assertEquals(mapOf("SATISFIED" to 1L), request.outcomes)
        assertTrue(request.work > 0L)
    }

    @Test
    fun `a checked bound should not satisfy an attained optimum request`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 3.0, cost = 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())
        val sink = LpStatsSink(LpRoute.STANDALONE)

        LpSolveSession().use { owner ->
            assertNull(owner.solve(model, claim = LpClaim.BOUND).witness)
            val optimum = owner.solve(model, claim = LpClaim.ATTAINED_OPTIMUM,
                observer = sink.certificationObserver())
            val bound = owner.solve(model, claim = LpClaim.BOUND, observer = sink.certificationObserver())

            assertEquals(LpVerdict.ATTAINED_OPTIMUM, optimum.verdict)
            assertFalse(optimum.certificationReused)
            assertTrue(bound.certificationReused)
            val reused = assertNotNull(sink.snapshot().phases["CLAIM_BOUND_STANDALONE"])
            assertEquals(mapOf("REUSED" to 1L), reused.outcomes)
            assertEquals(0L, reused.work)
        }
    }

    @Test
    fun `scoped edits should invalidate checked packages before consumption`() {
        val zero = ExactLpNumber.of(0L)
        val edits = listOf<(LpScopedSolver) -> Boolean>(
            { it.assertBound(0, false, ExactLpSide(ExactLpNumber.of(2L)), 1L) },
            { it.replaceObjective(ExactLpObjective(listOf(ExactLpNumber.of(2L), zero))) },
            { it.deactivate(0L) },
            { it.push() },
            { it.assertBound(0, false, ExactLpSide(ExactLpNumber.of(1L), strict = true), 1L) },
            { it.append(LpScopedRow(1L, listOf(0 to ExactLpNumber.of(-1L)), ExactLpNumber.of(-2L),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero)))), false) },
        )
        for (edit in edits) {
            val builder = LpBuilder()
            val x = builder.addRealVar(0.0, 3.0, cost = 1.0)
            builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.GE, 1.0)
            val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            LpScopedSolver(state).use { owner ->
                assertNotNull(owner.solve(claim = LpClaim.BOUND)?.bound)
                assertTrue(assertNotNull(owner.solve(claim = LpClaim.BOUND)).certificationReused)

                assertTrue(edit(owner))
                val changed = assertNotNull(owner.solve(claim = LpClaim.BOUND))

                assertFalse(changed.certificationReused)
                assertNotNull(changed.bound)
            }
        }
    }

    @Test
    fun `checked packages should remain unavailable under another acceptance policy`() {
        val builder = LpBuilder()
        builder.addVar(0L, 3L, cost = 1L)
        val model = assertNotNull(LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            .ownerWorkingModel())
        val cache = LpCounterResults()
        assertNotNull(solveAndCertify(model, claim = LpClaim.BOUND, counterResults = cache).bound)
        val refused = LpCertificationPolicy { _, _ -> false }

        val result = solveAndCertify(model, claim = LpClaim.BOUND, counterResults = cache,
            context = LpSolveContext(certificationPolicy = refused))

        assertNull(result.bound)
        assertFalse(result.certificationReused)
    }

    @Test
    fun `refused bound checks should retain their requested claim and spent work`() {
        val builder = LpBuilder()
        builder.addRealVar(0.0, 3.0, cost = 1.0)
        val sink = LpStatsSink(LpRoute.STANDALONE)

        val result = certifyLpBoundOnly(builder.build(Sense.MINIMIZE), doubleArrayOf(),
            ReconstructionLimits(maxWork = 1L), observer = sink.certificationObserver())

        assertNull(result.bound)
        assertEquals(ReconstructionDecline.WORK, result.metrics.decline)
        val request = assertNotNull(sink.snapshot().phases["CLAIM_BOUND_STANDALONE"])
        assertEquals(mapOf("WORK" to 1L), request.outcomes)
        assertEquals(result.metrics.work, request.work)
        assertTrue(request.work > 0L)
    }

    @Test
    fun `bound refusals should preserve missing sides and scalar limits`() {
        for (limited in listOf(false, true)) {
            val builder = LpBuilder()
            if (limited) builder.addVar(0L, Long.MAX_VALUE, cost = 1L) else builder.addFreeVar(null, null, cost = 1L)
            val sink = LpStatsSink(LpRoute.STANDALONE)
            val limits = if (limited) ReconstructionLimits(maxBits = 24) else ReconstructionLimits()

            val result = certifyLpBoundOnly(builder.build(Sense.MINIMIZE), doubleArrayOf(), limits,
                observer = sink.certificationObserver())

            assertNull(result.bound)
            val request = assertNotNull(sink.snapshot().phases["CLAIM_BOUND_STANDALONE"])
            assertEquals(mapOf((if (limited) "BITS" else "MISSING_BOUND_SIDE") to 1L), request.outcomes)
        }
    }

    @Test
    fun `contradiction requests should check a floating feasible candidate exactly`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 0.1)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(0.1), Relation.EQ, 0.010000000000000002)

        val result = solveAndCertify(builder.build(Sense.MINIMIZE), claim = LpClaim.CONTRADICTION)

        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertNull(result.witness)
    }

    @Test
    fun `open refutation should request and consume a contradiction`() {
        val rows = listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 2),
        )
        val sink = LpStatsSink(LpRoute.ROOT)

        val refuted = openLpInfeasible(arrayOf(OpenIntBounds(null, null)), rows,
            observer = sink.certificationObserver(LpRoute.ROOT))

        assertTrue(refuted)
        val phases = sink.snapshot().phases
        assertEquals(mapOf("SATISFIED" to 1L), assertNotNull(phases["CLAIM_CONTRADICTION_ROOT"]).outcomes)
        assertEquals(mapOf("CONTRADICTION" to 1L), assertNotNull(phases["CLAIM_CONSUMPTION_ROOT"]).outcomes)
    }

    @Test
    fun `unchanged real leaves should consume a compatible checked optimum`() {
        val compiled = Mps.parse(
            "NAME CLAIM\nROWS\n N COST\n E ROW\nCOLUMNS\n X COST 1 ROW 2\n" +
                "RHS\n R ROW 3\nBOUNDS\n UP B X 10\nENDATA",
        ).toProblem()
        val problem = compiled.model
        val objective = assertNotNull(compiled.objective).toLinearObjective()
        val sink = LpStatsSink(LpRoute.STANDALONE)
        val sample = Sample(booleanArrayOf(), longArrayOf())

        LeafRealSolver(problem, objective, sink = sink).use { owner ->
            repeat(2) {
                val result = owner.solve(sample)
                assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
                assertEquals(BigFraction.ofLong(3L) * BigFraction.ofLong(2L).reciprocal(),
                    compiled.sourceWitness(sample.ints, assertNotNull(result.exactReals)).values.single())
            }
        }

        val phases = sink.snapshot().phases
        assertEquals(1L, assertNotNull(phases["CLAIM_ATTAINED_OPTIMUM_STANDALONE"]).outcomes["REUSED"])
        assertEquals(mapOf("ATTAINED_OPTIMUM" to 2L),
            assertNotNull(phases["CLAIM_CONSUMPTION_STANDALONE"]).outcomes)
    }

    @Test
    fun `real leaf consumers should request completion or attainment explicitly`() {
        for (objective in listOf(null, LinearObjective(realCoefficients = doubleArrayOf(1.0)))) {
            val row = Linear(longArrayOf(), intArrayOf(), doubleArrayOf(2.0), intArrayOf(0), LinearOp.EQ, 3L)
            val problem = Problem(numBoolVars = 0, numIntVars = 0, intDomains = emptyArray(),
                factors = arrayOf<Factor>(row), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(10.0))
            val sink = LpStatsSink(LpRoute.STANDALONE)
            val claim = if (objective == null) LpClaim.FEASIBLE_COMPLETION else LpClaim.ATTAINED_OPTIMUM

            val result = leafRealFeasibility(problem, objective, Sample(booleanArrayOf(), longArrayOf()), sink = sink)

            assertEquals(BigFraction.ofLong(3L) * BigFraction.ofLong(2L).reciprocal(),
                assertNotNull(result.exactReals).single())
            assertEquals(mapOf("SATISFIED" to 1L),
                assertNotNull(sink.snapshot().phases["${claim.phase}_STANDALONE"]).outcomes)
            assertNotNull(sink.snapshot().phases["CLAIM_CONSUMPTION_STANDALONE"])
        }
    }
}
