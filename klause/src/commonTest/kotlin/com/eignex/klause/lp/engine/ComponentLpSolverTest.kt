package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ComponentLpSolverTest {
    @Test
    fun `empty structural rows retain their logical objective in a separate child`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        builder.addRow(intArrayOf(), longArrayOf(), Relation.LE, 2L)
        val legacy = builder.build(Sense.MINIMIZE)
        legacy.cost[legacy.slackCol(2)] = 3L
        val model = assertNotNull(LpExactState(assertNotNull(legacy.authoritativeModel())).ownerWorkingModel())

        val result = solveAndCertify(model)

        assertEquals(3, result.float?.blocks)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(BigFraction.ofLong(8L), result.lowerBound)
    }

    @Test
    fun `an empty structural row can certify the component collection infeasible`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        builder.addRow(intArrayOf(), longArrayOf(), Relation.GE, 1L)
        val model = assertNotNull(
            LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())).ownerWorkingModel(),
        )

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.INFEASIBLE, result.verdict)
        assertNull(result.witness)
    }

    @Test
    fun `component preparation reserves its grant before constructing children`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val model = builder.build(Sense.MINIMIZE)
        for (projection in listOf(LpProjectionMeter(workLimit = 50L), LpProjectionMeter(allocationLimit = 64L))) {
            var constructed = 0

            assertFailsWith<LpProjectionStop> {
                componentLpSolverOrNull(model, Cancellation.Never, { part, token ->
                    constructed++
                    RevisedSimplex(part, token)
                }, ::ComponentLpSolver, projection)
            }

            assertEquals(0, constructed)
            assertTrue(projection.work in 1L..50L)
            assertTrue(projection.allocation > 0L)
        }
    }

    @Test
    fun `tableau component queries should share their pivot allowance`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        newComponentTableauCutSolver(builder.build(Sense.MINIMIZE), iterationLimit = 1).use { owner ->
            val result = owner.solve()

            assertNull(result)
            assertEquals(LpFloatTermination.PIVOTS, owner.lastTermination)
            assertEquals(1, owner.lastPivots)
            assertEquals(1, owner.lastMetrics.componentSolves)
        }
    }

    @Test
    fun `a bound edit should solve only its owning component`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val solves = IntArray(2)
        var acquired = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel, cancellation: Cancellation, refactorUpdateLimit: Int,
                iterationLimit: Int, workLimit: Long, trackDegeneracy: Boolean, pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val index = acquired++
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model, cancellation, refactorUpdateLimit, iterationLimit, workLimit, trackDegeneracy, pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun solve(warm: Basis?): FloatLpResult? {
                        solves[index]++
                        return delegate.solve(warm)
                    }
                    override fun resolveBounds(allowance: LpFloatAllowance?): FloatLpResult? {
                        solves[index]++
                        return delegate.resolveBounds(allowance)
                    }
                }
            }
        }
        newRetainedLpSolver(assertNotNull(trail.state.ownerWorkingModel()), factory = factory).use { owner ->
            assertNotNull(owner.solve())
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 7L))
            assertTrue(owner.adopt(trail.state))

            val raw = assertNotNull(owner.resolveBounds())

            assertEquals(listOf(2, 1), solves.toList())
            assertEquals(BigFraction.ofLong(4L), assertNotNull(
                (owner as ComponentLpSolverCapability).exactBound(),
            ).value)
            assertEquals(listOf(3.0, 1.0), raw.primal.toList())
        }
    }

    @Test
    fun `unchanged children should reuse checked bounds and charge no numerical work`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val owner = assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(state.ownerWorkingModel())))
        owner.use {
            assertNotNull(it.solve())
            val expected = assertNotNull(it.exactBound()).value
            var checks = 0
            val observer = object : LpCertificationObserver {
                override fun observe(certifier: LpCertifier, success: Boolean, cost: LpCertifierCost) { checks++ }
                override fun observeExactInput(accepted: Boolean) = Unit
                override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) = Unit
            }
            assertTrue(it.adopt(state))

            val raw = assertNotNull(it.resolveBounds())
            val bound = assertNotNull(it.exactBound(observer))

            assertEquals(expected, bound.value)
            assertEquals(0, checks)
            assertEquals(0L, it.lastMetrics.workOps)
            assertEquals(0, raw.pivots)
            assertEquals(0, raw.refactorizations)
        }
    }

    @Test
    fun `a reused component bound should cite the current parent bound witness`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        assertTrue(trail.push())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 7L))
        val owner = assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(trail.state.ownerWorkingModel())))
        owner.use {
            assertNotNull(it.solve())
            assertNotNull(it.exactBound())
            assertTrue(trail.pop(0))
            assertTrue(trail.push())
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 8L))
            assertTrue(it.adopt(trail.state))
            assertNotNull(it.resolveBounds())

            val support = assertNotNull(assertNotNull(it.exactBound()).support)

            assertSame(trail.state, support.state)
            assertEquals(8L, support.sides.single { side -> side.column == 0 && !side.upper }.witness)
        }
    }

    @Test
    fun `tableau cuts should lift only their owning rows and structural columns`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(2L), Relation.GE, 1L)
        }
        val model = builder.build(Sense.MINIMIZE)
        newComponentTableauCutSolver(model).use { owner ->
            assertEquals(2, assertNotNull(owner.solve()).blocks)

            val cuts = owner.gomoryCuts(2)

            assertEquals(2, cuts.size)
            for ((index, cut) in cuts.withIndex()) {
                assertEquals(listOf(index), cut.cols.toList())
                val proof = assertNotNull(cut.tableau)
                assertSame(model, proof.model)
                assertEquals(listOf(index), proof.rows.map { it.index })
                assertEquals(listOf(index), proof.columns.map { it.column })
                assertEquals(listOf(index), proof.rows.single().columns.toList())
                assertFalse(cut.global)
            }
        }
    }

    @Test
    fun `checked component bounds should not bypass a different acceptance policy`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val state = LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val owner = assertIs<ComponentLpSolver>(newRetainedLpSolver(assertNotNull(state.ownerWorkingModel())))
        owner.use {
            assertNotNull(it.solve())
            assertNotNull(it.exactBound())

            val rejected = it.exactBound(policy = LpCertificationPolicy { _, _ -> false })

            assertNull(rejected)
        }
    }

    @Test
    fun `a rounded component optimum should preserve its exact fractional contradiction`() {
        val builder = LpBuilder()
        val x = builder.addRealVar(0.0, 1.0)
        val y = builder.addRealVar(0.0, 3.0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(1.0), Relation.EQ, 0.1)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(0.1), Relation.EQ, 0.010000000000000002)
        builder.addRealRow(intArrayOf(y), doubleArrayOf(1.0), Relation.GE, 1.0)
        val model = assertNotNull(
            LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                .ownerWorkingModel(),
        )

        val result = assertIs<RetainedComponentLpSolverCapability>(newRetainedLpSolver(model)).use { solver ->
            val raw = solver.solve()
            certifyLpResult(model, solver, raw)
        }

        assertEquals(LpVerdict.INFEASIBLE, result.verdict, "continuation=${result.continuation}")
        assertTrue(assertNotNull(result.conflictSupport).rows.any { it.first == 1 })
    }

    @Test
    fun `retained components should preserve fresh exact bounds across nested scopes and objective changes`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val factory = RecordingLpEngineFactory()
        val owner = assertIs<RetainedComponentLpSolverCapability>(
            newRetainedLpSolver(
                assertNotNull(trail.state.ownerWorkingModel()),
                factory = factory,
            ),
        )
        owner.use {
            assertNotNull(it.solve())
            assertTrue(trail.push())
            assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 7L))
            assertTrue(trail.push())
            assertTrue(trail.assertBound(1, true, ExactLpSide(ExactLpNumber.of(4L)), 8L))
            for (depth in listOf(2, 1, 0)) {
                if (depth < 2) assertTrue(trail.pop(depth))
                val state = trail.state
                assertTrue(it.adopt(state))
                val raw = assertNotNull(it.resolveBounds())
                val retained = certifyLpResult(assertNotNull(state.ownerWorkingModel()), it, raw)
                val fresh = solveAndCertify(assertNotNull(state.ownerWorkingModel()))
                assertEquals(fresh.lowerBound, retained.lowerBound)
                assertEquals(fresh.verdict, retained.verdict)
                assertSame(state, raw.exactState)
                assertEquals(0, raw.refactorizations)
                assertTrue(raw.warmStarted)
            }
            val zero = ExactLpNumber.of(0L)
            assertTrue(trail.replaceObjective(ExactLpObjective(listOf(ExactLpNumber.of(-1L), zero, zero, zero))))
            assertTrue(it.adopt(trail.state))
            val raw = assertNotNull(it.resolveBounds())
            assertEquals(BigFraction.ofLong(-5L), assertNotNull(it.exactBound()).value)
            assertEquals(0, raw.refactorizations)
        }
        assertEquals(2, factory.calls.count { it.kind == EngineConstruction.PERSISTENT })
        assertEquals(1, factory.calls.count { it.kind == EngineConstruction.COMPONENT })
    }

    @Test
    fun `partial component adoption should retire every child and clear old proof evidence`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 5L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        var acquired = 0
        var closed = 0
        val factory = object : LpEngineFactory by ProductionLpEngineFactory {
            override fun newPersistentSolver(
                model: LpModel,
                cancellation: Cancellation,
                refactorUpdateLimit: Int,
                iterationLimit: Int,
                workLimit: Long,
                trackDegeneracy: Boolean,
                pricing: LpPricingOptions,
            ): PersistentLpSolver {
                val decline = acquired++ == 1
                val delegate = ProductionLpEngineFactory.newPersistentSolver(
                    model,
                    cancellation,
                    refactorUpdateLimit,
                    iterationLimit,
                    workLimit,
                    trackDegeneracy,
                    pricing,
                )
                return object : PersistentLpSolver by delegate {
                    override fun adopt(state: LpExactState, token: Cancellation): Boolean =
                        !decline && delegate.adopt(state, token)
                    override fun close() {
                        closed++
                        delegate.close()
                    }
                }
            }
        }
        val owner = assertIs<RetainedComponentLpSolverCapability>(
            newRetainedLpSolver(
                assertNotNull(trail.state.ownerWorkingModel()),
                factory = factory,
            ),
        )
        assertNotNull(owner.solve())
        assertTrue(trail.assertBound(0, false, ExactLpSide(ExactLpNumber.of(3L)), 7L))
        assertTrue(trail.assertBound(1, false, ExactLpSide(ExactLpNumber.of(3L)), 8L))

        assertFalse(owner.adopt(trail.state))

        assertEquals(2, closed)
        assertNull(owner.solvedExactState)
        assertNull(owner.exactBound())
        assertFailsWith<IllegalStateException> { owner.resolveBounds() }
        owner.close()
        assertEquals(2, closed)
    }

    @Test
    fun `an unbounded isolated objective should retain an exact source direction`() {
        val builder = LpBuilder()
        repeat(2) {
            val x = builder.addVar(0L, 3L)
            builder.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
        }
        val isolated = builder.addFreeVar(4L, null, cost = -1L)
        val model = assertNotNull(
            LpExactState(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                .ownerWorkingModel(),
        )

        val result = solveAndCertify(model)

        assertEquals(LpVerdict.UNBOUNDED, result.verdict)
        assertTrue(assertNotNull(result.unboundedness).direction[isolated] > BigFraction.ZERO)
    }

    @Test
    fun `inactive component rows should remain free and not supply certificate premises`() {
        val builder = LpBuilder()
        val x = builder.addVar(0L, 5L, cost = 1L)
        val y = builder.addVar(0L, 5L, cost = 1L)
        builder.addRow(
            intArrayOf(x),
            longArrayOf(1L),
            Relation.GE,
            4L,
            global = false,
            premises = LpRowPremises(intArrayOf(7), booleanArrayOf(false), longArrayOf(4L)),
        )
        builder.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 2L)
        val source = assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel())
        val state = LpExactState(source, rows = LpScopedRows.initial(2).deactivate(setOf(0)))
        val model = assertNotNull(state.ownerWorkingModel())

        val result = assertIs<ComponentLpSolver>(newLpSolver(model)).use { solver ->
            val raw = assertNotNull(solver.solve())
            val support = assertNotNull(assertNotNull(solver.exactBound()).support)
            assertTrue(support.rows.none { it.first == 0 })
            assertTrue(support.sides.none { it.column == model.n })
            certifyLpResult(model, solver, raw)
        }

        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertEquals(BigFraction.ofLong(2L), result.lowerBound)
        assertEquals(BigFraction.ZERO, result.exactPrimal?.get(x))
    }

    @Test
    fun `zero cost isolated columns should receive a witness inside strict one sided bounds`() {
        val one = ExactLpNumber.of(1L)
        val zero = ExactLpNumber.of(0L)
        val source = ExactLpModel(
            listOf(listOf(ExactLpEntry(0, one)), listOf(ExactLpEntry(1, one)), emptyList()),
            listOf(one, one),
            listOf(
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds()),
                ExactLpColumn(ExactLpBounds(lower = ExactLpSide(one, strict = true))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
                ExactLpColumn(ExactLpBounds(ExactLpSide(zero), ExactLpSide(zero))),
            ),
            listOf(ExactLpRow(), ExactLpRow()),
            ExactLpObjective(List(5) { zero }),
        )
        val model = assertNotNull(LpExactState(source).ownerWorkingModel())

        val result = solveAndCertify(model)

        assertEquals(2, result.float?.blocks)
        assertEquals(LpVerdict.ATTAINED_OPTIMUM, result.verdict)
        assertTrue(assertNotNull(result.exactPrimal)[2] > one.value)
    }

    @Test
    fun `a separable model should stitch to the monolithic optimum`() {
        val b = LpBuilder()
        val x0 = b.addVar(0L, 10L, cost = 1L)
        val x1 = b.addVar(0L, 10L, cost = -1L)
        val y0 = b.addVar(0L, 10L, cost = 2L)
        b.addVar(0L, 4L, cost = -3L) // isolated: rides to its upper bound
        b.addRow(intArrayOf(x0, x1), longArrayOf(1L, 1L), Relation.GE, 6L)
        b.addRow(intArrayOf(y0), longArrayOf(1L), Relation.GE, 3L)
        val model = b.build(Sense.MINIMIZE)

        val split = newLpSolver(model).solvePrimal(null)
        val mono = newLpSolver(model, componentSplit = false).solvePrimal(null)
        assertNotNull(split)
        assertNotNull(mono)
        assertEquals(mono.objective, split.objective, 1e-9)
        for (j in 0 until model.n) assertEquals(mono.primal[j], split.primal[j], 1e-9, "primal[$j]")
    }

    @Test
    fun `component solve should preserve a child work stop`() {
        val builder = LpBuilder()
        repeat(2) {
            val column = builder.addVar(0L, 4L, cost = 1L)
            builder.addRow(intArrayOf(column), longArrayOf(1L), Relation.GE, 1L)
        }
        var part = 0
        val solver = assertNotNull(
            componentLpSolverOrNull(
                builder.build(Sense.MINIMIZE),
                com.eignex.klause.util.Cancellation.Never,
                { model, _ -> RevisedSimplex(model, workLimit = if (part++ == 0) 0L else 1L) },
            ),
        )
        solver.use {
            assertNull(it.solve())

            assertEquals(LpFloatTermination.WORK, it.lastTermination)
            assertTrue(it.lastMetrics.workOps > 0L)
            assertNull(it.infeasibleRay)
        }
    }

    @Test
    fun `an infeasible block should certify the whole model infeasible`() {
        val b = LpBuilder()
        val ok = b.addVar(0L, 10L)
        val bad = b.addVar(0L, 10L)
        b.addRow(intArrayOf(ok), longArrayOf(1L), Relation.LE, 9L)
        b.addRow(intArrayOf(bad), longArrayOf(1L), Relation.GE, 5L)
        b.addRow(intArrayOf(bad), longArrayOf(1L), Relation.LE, 2L)
        val model = b.build(Sense.MINIMIZE)
        val solver = newLpSolver(model)
        assertIs<ComponentLpSolver>(solver)
        assertNull(solver.solve(null))
        assertEquals(LpFloatTermination.INFEASIBLE_CANDIDATE, solver.lastTermination)
        val ray = solver.infeasibleRay
        assertNotNull(ray)
        assertEquals(model.m, ray.size)
        assertNotNull(integerFarkasRay(model, ray))
    }

    @Test
    fun `stitched duals should certify the objective bound on the full model`() {
        val b = LpBuilder()
        val x = b.addVar(0L, 10L, cost = 3L)
        val y = b.addVar(0L, 10L, cost = 2L)
        b.addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 4L)
        b.addRow(intArrayOf(y), longArrayOf(1L), Relation.GE, 5L)
        val model = b.build(Sense.MINIMIZE)
        val solver = newLpSolver(model)
        assertIs<ComponentLpSolver>(solver)
        val result = assertNotNull(solver.solve(null))
        assertTrue(abs(result.objective - 22.0) < 1e-9)
        val ceil = integerDualLowerBoundCeil(model, result.duals)
        assertEquals(22L, ceil)
    }

    @Test
    fun `component certificates should sum before rounding the objective`() {
        val builder = LpBuilder()
        val first = builder.addVar(0L, 1L, cost = 1L)
        val second = builder.addVar(0L, 1L, cost = 1L)
        builder.addRow(intArrayOf(first), longArrayOf(2L), Relation.GE, 1L)
        builder.addRow(intArrayOf(second), longArrayOf(2L), Relation.GE, 1L)
        val model = builder.build(Sense.MINIMIZE)
        val solver = assertIs<ComponentLpSolver>(newLpSolver(model))

        assertNotNull(solver.solve())
        assertEquals(BigFraction.ONE, solver.exactBound()?.value)
    }

    @Test
    fun `an interrupted component should not promote a mixed proof to an attained optimum`() {
        val model = LpBuilder().apply {
            repeat(2) {
                val x = addVar(0L, 3L, cost = 1L)
                addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
            }
        }.build(Sense.MINIMIZE)
        var index = 0
        val solver = assertNotNull(
            componentLpSolverOrNull(
                model,
                com.eignex.klause.util.Cancellation.Never,
                { part, token ->
                    if (index++ == 0) {
                        ProductionLpEngineFactory.newGeneralSolver(part, token, 0L, LpPricingOptions())
                    } else {
                        object : LpSolver {
                            override val infeasibleRay: DoubleArray? = null
                            override fun solve(warm: Basis?) = FloatLpResult(
                                Basis(intArrayOf(1), arrayOf(VarStatus.AT_UPPER, VarStatus.BASIC)),
                                3.0,
                                doubleArrayOf(0.0),
                                doubleArrayOf(3.0),
                                optimal = false,
                            )
                            override fun solvePrimal(warm: Basis?) = solve(warm)
                        }
                    }
                },
            ),
        )

        solver.use {
            val hint = assertNotNull(it.solve())
            assertEquals(false, hint.optimal)
            assertNull(it.lastTermination)
            val result = certifyLpResult(model, it, hint)
            assertEquals(LpVerdict.FEASIBLE, result.verdict)
            assertEquals(listOf(BigFraction.ONE, BigFraction.ofLong(3L)), result.exactPrimal)
            assertEquals(BigFraction.ofLong(4L), result.witness?.objective)
            assertEquals(BigFraction.ONE, result.lowerBound)
        }
    }

    @Test
    fun `component bounds should include logical costs in the full objective`() {
        val model = LpBuilder().apply {
            repeat(2) {
                val x = addVar(0L, 3L)
                addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
            }
        }.build(Sense.MINIMIZE)
        for (column in model.n until model.numVars) model.cost[column] = -1L

        assertIs<ComponentLpSolver>(newLpSolver(model)).use { solver ->
            assertNotNull(solver.solve())
            assertEquals(BigFraction.ofLong(-4L), assertNotNull(solver.exactBound()).value)
            val point = assertNotNull(checkedLpWitness(model, List(2) { BigFraction.ofLong(3L) }))
            assertEquals(BigFraction.ofLong(-4L), point.objective)
        }
    }

    @Test
    fun `a component bound should lose authority when the shared objective changes`() {
        val model = LpBuilder().apply {
            repeat(2) {
                val x = addVar(0L, 3L, cost = 1L)
                addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 1L)
            }
        }.build(Sense.MINIMIZE)

        assertIs<ComponentLpSolver>(newLpSolver(model)).use { solver ->
            assertNotNull(solver.solve())
            assertEquals(BigFraction.ofLong(2L), solver.exactBound()?.value)
            model.cost[0] = -1L
            assertEquals(null, solver.exactBound())
        }
        assertEquals(BigFraction.ofLong(-2L), solveAndCertify(model).lowerBound)
    }
}
