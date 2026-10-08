package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.cut.AllDifferentSeparator
import com.eignex.klause.lp.cut.CutContext
import com.eignex.klause.lp.cut.SourceCut
import com.eignex.klause.lp.cut.orNull
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutInputRow
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpEngineFactory
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpPricingOptions
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.lp.engine.PersistentLpSolver
import com.eignex.klause.lp.engine.ProductionLpEngineFactory
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.TableauCutProvenance
import com.eignex.klause.lp.engine.TableauCutSolver
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.exactConstant
import com.eignex.klause.lp.engine.exactCost
import com.eignex.klause.lp.engine.exactRhs
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.lp.engine.forEachRationalColumn
import com.eignex.klause.lp.engine.integerCertify
import com.eignex.klause.lp.engine.integerFarkasRay
import com.eignex.klause.lp.engine.sourceObjective
import com.eignex.klause.lp.relaxation.LpExplanation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.cpCutSources
import com.eignex.klause.lp.relaxation.withCpBounds
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CpLpAdapterTest {
    @Test
    fun `CP decisions without an LP pass do not spend retained LP edit work`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 9)), emptyArray())
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink(backend = "deferred-scopes")).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val before = engine.totalSolveWork()

            shared.push(SearchDecision.IntAtLeast(0, 3))

            assertEquals(3L, cp.session.intDomain(0).min)
            assertEquals(before, engine.totalSolveWork())
        }
    }

    @Test
    fun `an LP pass catches up skipped CP scopes and restores ancestor bounds`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 9)), emptyArray())
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink(backend = "deferred-scopes")).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            shared.push(SearchDecision.IntAtLeast(0, 3))
            shared.push(SearchDecision.IntAtLeast(0, 5))

            val child = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val childValue = assertNotNull(engine.solveNode(child.model, null, Cancellation.Never)?.second).objective
            shared.popTo(1)
            val ancestor = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val ancestorValue = assertNotNull(engine.solveNode(ancestor.model, null, Cancellation.Never)?.second).objective
            shared.popTo(0)
            val root = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val rootValue = assertNotNull(engine.solveNode(root.model, null, Cancellation.Never)?.second).objective

            assertEquals(5.0, childValue)
            assertEquals(3.0, ancestorValue)
            assertEquals(0.0, rootValue)
        }
    }

    @Test
    fun `fixed node relaxations reuse their owner projection without changing source origins`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(3, 9)), emptyArray())
        LpEngine(
            problem, LinearObjective(intCoefficients = longArrayOf(1L)),
            LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink(backend = "retained-projection"),
        ).use { engine ->
            val session = PropagationSession(problem)
            val relaxer = assertNotNull(engine.lpRelaxer)
            val root = assertNotNull(engine.nodeRelaxation(relaxer, session))
            assertEquals(3.0, assertNotNull(engine.solveNode(root.model, null, Cancellation.Never)?.second).objective)
            assertSame(engine.propagator.state?.ownerWorkingModel(), root.model)
            assertSame(root.model, assertNotNull(engine.nodeRelaxation(relaxer, session)).model)
            session.pinIntAtLeast(0, 5L)

            val child = assertNotNull(engine.nodeRelaxation(relaxer, session))

            assertEquals(5.0, assertNotNull(engine.solveNode(child.model, null, Cancellation.Never)?.second).objective)
            assertSame(engine.propagator.state?.ownerWorkingModel(), child.model)
            assertSame(child.model, assertNotNull(engine.nodeRelaxation(relaxer, session)).model)
            assertEquals(BigFraction.ofLong(3L), root.model.exactShift(0))
            assertEquals(BigFraction.ofLong(3L), child.model.exactShift(0))
            assertEquals(3L, assertNotNull(integerCertify(root.model, doubleArrayOf())).objectiveBoundCeil(0L))
            assertEquals(5L, assertNotNull(integerCertify(child.model, doubleArrayOf())).objectiveBoundCeil(0L))
            assertEquals(1L, engine.propagator.metrics?.createdOwners)
        }
    }

    @Test
    fun `source nodes reclaim discarded rows and restore fresh bounds on nested pop`() {
        val problem = Problem(1, 3, Array(3) { IntDomain(0, 128) },
            arrayOf(ArrayMinMax(result = 0, xs = intArrayOf(1, 2), max = true),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 10)))
        LpEngine(problem, LinearObjective(intCoefficients = longArrayOf(1, 0, 0)),
            LpParams(lpPlan = LpPlan(bounding = true, linMaxTightFace = true)),
            SolveStatsSink(backend = "source-compaction")).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            val root = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val rootRows = root.model.m
            val rootColumns = root.model.n
            var previousRows = rootRows
            var reclaimed = false
            for (lower in 1L..96L) {
                if (lower == 1L) {
                    shared.push(SearchDecision.IntAtLeast(1, lower))
                } else {
                    cp.session.implyIntAtLeast(1, lower)
                }
                val current = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
                reclaimed = reclaimed || current.model.m < previousRows
                if (lower <= 8L || reclaimed) {
                    val result = assertNotNull(engine.solveNode(current.model, null, Cancellation.Never)?.second)
                    val fresh = relaxer.build(cp.session)
                    val expected = RevisedSimplex(fresh.model).use { assertNotNull(it.solve()).objective }
                    assertEquals(expected, result.objective)
                    assertEquals(
                        lower,
                        assertNotNull(integerCertify(current.model, result.duals)).objectiveBoundCeil(0),
                    )
                }
                assertTrue(current.model.m <= previousRows + rootRows)
                previousRows = current.model.m
                assertEquals(rootColumns, current.model.n)
                assertEquals(current.model.m, current.rowFactorIds.size)
                if (reclaimed) break
            }
            assertTrue(reclaimed)
            shared.popTo(0)
            val restored = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val result = assertNotNull(engine.solveNode(restored.model, null, Cancellation.Never)?.second)
            assertTrue(restored.model.m <= previousRows)
            assertEquals(rootColumns, restored.model.n)
            assertEquals(0L, assertNotNull(integerCertify(restored.model, result.duals)).objectiveBoundCeil(0))
            assertEquals(rootRows, assertNotNull(restored.model.exactState).rows.activeCount)
            assertEquals(root.rowFactorIds.toList(), restored.rowFactorIds.take(rootRows))
        }
    }

    @Test
    fun `empty source nodes load and certify pooled constant contradictions`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 0) },
            arrayOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 1)),
        )
        val sink = SolveStatsSink(backend = "constant-cut")
        LpEngine(
            problem,
            LinearObjective(),
            LpParams(lpPlan = LpPlan(bounding = true, cuts = true)),
            sink,
        ).use { engine ->
            val session = PropagationSession(problem)
            val relaxer = assertNotNull(engine.lpRelaxer)
            val base = assertNotNull(engine.nodeRelaxation(relaxer, session))
            assertEquals(0, base.model.n)
            assertEquals(0, base.model.m)
            assertTrue(engine.cutPool.add(Cut(intArrayOf(), longArrayOf(), Relation.GE, 1, global = true), base))

            val outcome = engine.sparseSafePrune(
                relaxer, session, Double.POSITIVE_INFINITY, sink, Cancellation.Never, -1, true, learn = true,
            )

            assertTrue(outcome.prune)
            assertContentEquals(intArrayOf(), assertNotNull(outcome.explanation))
            assertEquals(1, engine.propagator.state?.rows?.activeCount)
            assertEquals(1.0, sink.lp.snapshot().infeasible.sum)
        }
    }

    @Test
    fun `retained cut infeasibility is certified and explained against the augmented model`() {
        val problem = Problem(
            0, 4, Array(4) { IntDomain(2, 3) },
            arrayOf(AllDifferent(intArrayOf(0, 1, 2, 3), domainMin = 2, domainSize = 2)),
        )
        val sink = SolveStatsSink(backend = "retained-cut-conflict")
        LpEngine(
            problem, LinearObjective(intCoefficients = longArrayOf(1, 1, 1, 1)),
            LpParams(lpPlan = LpPlan(bounding = true, cuts = true)), sink,
        ).use { engine ->
            val session = PropagationSession(problem)
            val relaxer = assertNotNull(engine.lpRelaxer)
            val base = assertNotNull(engine.nodeRelaxation(relaxer, session))
            val point = assertNotNull(engine.solveNode(base.model, null, Cancellation.Never)?.second)
            val cut = AllDifferentSeparator().separate(CutContext(problem, base, point.primal, session)).single()
            assertEquals(Relation.LE, cut.rel)
            assertTrue(engine.cutPool.add(cut, base))

            val outcome = engine.sparseSafePrune(
                relaxer, session, 12.0, sink, Cancellation.Never, -1, true, learn = true,
            )

            assertTrue(outcome.prune)
            assertContentEquals(
                IntArray(4) { session.boundGeLit(it, 2, positive = false) }, assertNotNull(outcome.explanation),
            )
            assertEquals(1, assertNotNull(engine.propagator.state).rows.activeCount)
        }
    }

    @Test
    fun `pooled cuts tighten a retained node and survive bound changes without fresh owners`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(2, 6) },
            arrayOf(AllDifferent(intArrayOf(0, 1), domainMin = 2, domainSize = 5)),
        )
        val sink = SolveStatsSink(backend = "retained-cuts")
        LpEngine(
            problem, LinearObjective(intCoefficients = longArrayOf(1, 1)),
            LpParams(lpPlan = LpPlan(bounding = true, cuts = true)), sink,
        ).use { engine ->
            val session = PropagationSession(problem)
            val relaxer = assertNotNull(engine.lpRelaxer)
            val base = assertNotNull(engine.nodeRelaxation(relaxer, session))
            val cut = Cut(base.intColOf, longArrayOf(1, 1), Relation.GE, 5, global = true)
            assertTrue(engine.cutPool.add(cut, base))

            val outcome = engine.sparseSafePrune(relaxer, session, 4.5, sink, Cancellation.Never, -1, true)

            assertTrue(outcome.prune)
            val loaded = assertNotNull(engine.nodeRelaxation(relaxer, session))
            assertSame(engine.propagator.state, loaded.model.exactState)
            val owners = assertNotNull(engine.propagator.metrics).createdOwners
            assertTrue(session.pinIntAtLeast(0, 3) !is com.eignex.klause.propagation.PropagationResult.Unsat)
            val child = assertNotNull(engine.nodeRelaxation(relaxer, session))
            val removed = assertNotNull(engine.cpAdapter.cutRelaxation(child, session, emptyList()))
            assertNotNull(engine.solveNode(removed.model, null, Cancellation.Never)?.second)
            session.popToLevel(0)
            val restored = assertNotNull(engine.nodeRelaxation(relaxer, session))
            val result = assertNotNull(engine.solveNode(restored.model, null, Cancellation.Never)?.second)
            val expected = RevisedSimplex(relaxer.build(session, listOf(cut)).model).use { simplex ->
                assertNotNull(simplex.solve()).objective
            }

            assertEquals(expected, result.objective)
            assertEquals(5L, assertNotNull(integerCertify(restored.model, result.duals)).objectiveBoundCeil(0))
            assertEquals(owners, engine.propagator.metrics?.createdOwners)
            assertEquals(0, engine.propagator.lastMetrics.initialRefactorizations)
            assertTrue((0 until restored.model.m).any { restored.sourceMap?.parent(it)?.global == true })
        }
    }

    @Test
    fun `retained local cut bound reasons cite their source interval premises`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(2, 6) },
            arrayOf(AllDifferent(intArrayOf(0, 1), domainMin = 2, domainSize = 5)),
        )
        LpEngine(
            problem, LinearObjective(intCoefficients = longArrayOf(1, 1)),
            LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink(backend = "retained-cut-proof"),
        ).use { engine ->
            val session = PropagationSession(problem)
            session.pinIntAtLeast(0, 3)
            session.implyIntAtLeast(1, 3)
            val relaxer = assertNotNull(engine.lpRelaxer)
            val base = assertNotNull(engine.nodeRelaxation(relaxer, session))
            val point = assertNotNull(engine.solveNode(base.model, null, Cancellation.Never)?.second)
            val cut = AllDifferentSeparator().separate(CutContext(problem, base, point.primal, session)).single()
            val tightened = assertNotNull(engine.cpAdapter.cutRelaxation(base, session, listOf(cut)))
            val result = assertNotNull(engine.solveNode(tightened.model, null, Cancellation.Never)?.second)
            val certificate = assertNotNull(integerCertify(tightened.model, result.duals))
            val reason = assertNotNull(LpExplanation.objectiveBoundReason(tightened, certificate, session))

            assertEquals(7L, certificate.objectiveBoundCeil(0))
            assertTrue(reason.isNotEmpty())
            session.popToLevel(0)
            val sibling = assertNotNull(engine.nodeRelaxation(relaxer, session))
            val siblingResult = assertNotNull(engine.solveNode(sibling.model, null, Cancellation.Never)?.second)
            assertEquals(4.0, siblingResult.objective)
            assertTrue((0 until sibling.model.m).all { sibling.sourceMap?.parent(it) == null })
        }
    }

    @Test
    fun `live source rows match fresh bounds through same level edits and nested pops`() {
        val problem = Problem(
            1, 1, arrayOf(IntDomain(0, 10)),
            arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 8)),
        )
        LpEngine(
            problem, LinearObjective(intCoefficients = longArrayOf(1L)),
            LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink(backend = "source-rows"),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            assertFalse(relaxer.build(cp.session).persistentEligible)
            var owners = 0L
            var previousRows = 0
            for (step in 0..5) {
                when (step) {
                    1 -> shared.push(SearchDecision.IntAtLeast(0, 2L))
                    2 -> cp.session.implyIntAtLeast(0, 3L)
                    3 -> shared.push(SearchDecision.Bool(0))
                    4 -> shared.popTo(1)
                    5 -> shared.popTo(0)
                }
                val retained = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
                val result = assertNotNull(engine.solveNode(retained.model, null, Cancellation.Never)?.second)
                val fresh = relaxer.build(cp.session)
                val expected = RevisedSimplex(fresh.model).use { simplex ->
                    val solved = assertNotNull(simplex.solve())
                    assertNotNull(integerCertify(fresh.model, solved.duals)).objectiveBoundCeil(fresh.objectiveConstant)
                }

                assertSame(retained.model.exactState, engine.propagator.state)
                assertEquals(
                    expected,
                    assertNotNull(integerCertify(retained.model, result.duals))
                        .objectiveBoundCeil(retained.objectiveConstant),
                )
                if (step >= 4 && retained.model.m == previousRows) {
                    assertEquals(owners, engine.propagator.metrics?.createdOwners)
                    assertEquals(0, engine.propagator.lastMetrics.initialRefactorizations)
                } else if (step >= 4) {
                    assertTrue(retained.model.m < previousRows)
                    assertEquals(owners + 2, engine.propagator.metrics?.createdOwners)
                    assertEquals(1L, engine.propagator.metrics?.currentOwners)
                }
                owners = assertNotNull(engine.propagator.metrics).createdOwners
                previousRows = retained.model.m
                val before = engine.propagator.metrics
                assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
                assertEquals(before, engine.propagator.metrics)
            }
        }
    }

    @Test
    fun `standalone live row siblings preserve declared coordinate origins`() {
        val problem = Problem(
            1, 1, arrayOf(IntDomain(-5, 10)),
            arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 8)),
        )
        LpEngine(
            problem, LinearObjective(intCoefficients = longArrayOf(1L)),
            LpParams(lpPlan = LpPlan(bounding = true)), SolveStatsSink(backend = "source-siblings"),
        ).use { engine ->
            val session = PropagationSession(problem)
            val relaxer = assertNotNull(engine.lpRelaxer)
            for (step in 0..2) {
                if (step == 1) session.pinIntAtLeast(0, 3L)
                if (step == 2) {
                    session.popToLevel(0)
                    session.pinIntAtMost(0, -2L)
                }
                val retained = assertNotNull(engine.nodeRelaxation(relaxer, session))
                val result = assertNotNull(engine.solveNode(retained.model, null, Cancellation.Never)?.second)
                val fresh = relaxer.build(session)
                val expected = RevisedSimplex(fresh.model).use { simplex ->
                    val solved = assertNotNull(simplex.solve())
                    assertNotNull(integerCertify(fresh.model, solved.duals)).objectiveBoundCeil(fresh.objectiveConstant)
                }

                assertEquals(BigFraction.ofLong(-5L), retained.model.exactShift(retained.intColOf[0]))
                assertEquals(
                    expected,
                    assertNotNull(integerCertify(retained.model, result.duals))
                        .objectiveBoundCeil(retained.objectiveConstant),
                )
            }
        }
    }

    @Test
    fun `real activation extends the retained layout and keeps strict sibling certificates valid`() {
        val problem = Problem(
            1, 0, emptyArray(),
            arrayOf(ReifiedRealLinear(
                0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0,
            )), numRealVars = 1, realLower = doubleArrayOf(-2.0), realUpper = doubleArrayOf(5.0),
        )
        LpEngine(
            problem, LinearObjective(), LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "real-source-rows"),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            val root = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertEquals(0, root.model.n)
            for (positive in listOf(true, false)) {
                shared.push(SearchDecision.Bool(if (positive) 0 else 1))
                val node = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))

                val certificate = assertNotNull(engine.propagator.solve())

                val witness = assertNotNull(certificate.witness)
                val column = node.colRealId.indexOf(0)
                val value = witness.primal[column]
                assertTrue(if (positive) value >= BigFraction.ONE else value < BigFraction.ONE)
                assertTrue(value >= BigFraction.ofLong(-2L) && value <= BigFraction.ofLong(5L))
                assertEquals(!positive, node.model.rowStrict.any { it })
                shared.popTo(0)
                val restored = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
                assertEquals(1, restored.model.n)
                assertEquals(0, assertNotNull(engine.propagator.state).rows.activeCount)
                assertNotNull(engine.propagator.solve()?.witness)
            }
        }
    }

    @Test
    fun `mixed integer real tableau route declines both cut families`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(1, 5), IntDomain(1, 5)),
            arrayOf(
                Linear(
                    intArrayOf(0, 1),
                    doubleArrayOf(2.0, 2.0),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    7.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(-1, -1)),
            LpParams(lpPlan = LpPlan(bounding = true, cuts = true)),
            SolveStatsSink(backend = "mixed-tableau-decline"),
        ).use { engine ->
            val session = PropagationSession(problem)
            val relaxer = assertNotNull(engine.lpRelaxer)
            val relaxation = relaxer.build(session)
            assertTrue(relaxation.model.hasContinuous)
            val simplex = engine.dualSimplex(relaxation.model, Cancellation.Never)
            try {
                val result = assertNotNull(simplex.solve())
                assertEquals(-3.5, result.objective)
                assertTrue(simplex.gomoryCuts(8).isEmpty())
                assertTrue(simplex.mirCuts(8).isEmpty())
            } finally {
                simplex.close()
            }
            assertTrue(engine.harvestRootCuts(relaxer, session, emptyList(), gomory = true, mir = true).isEmpty())
        }
    }

    @Test
    fun `generated local tableau cut follows its source bound through pop and sibling`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 5) },
            arrayOf(Linear(intArrayOf(2, 2, 1), intArrayOf(0, 1, 2), LinearOp.LE, 9)),
        )
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(-1, -1, 0)),
            LpParams(lpPlan = LpPlan(bounding = true, cuts = true)),
            SolveStatsSink(backend = "local-tableau"),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val search = SearchSession(listOf(cp, engine.propagator))
            search.initialize()
            search.push(SearchDecision.IntAtLeast(2, 2))
            val relaxer = assertNotNull(engine.lpRelaxer)
            val local = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val simplex = engine.dualSimplex(local.model, Cancellation.Never)
            val (baseResult, raw) = try {
                val result = assertNotNull(simplex.solve())
                result to simplex.gomoryCuts(8).single()
            } finally {
                simplex.close()
            }
            assertEquals(-3.5, baseResult.objective)
            val tableau = assertNotNull(raw.tableau)
            assertNull(raw.provenance)
            assertEquals(Relation.GE, raw.rel)
            assertContentEquals(intArrayOf(0, 1), raw.cols)
            assertContentEquals(longArrayOf(-1, -1), raw.coeffs)
            assertEquals(-3L, raw.rhs)
            val source = assertNotNull(SourceCut.fromCut(raw, local).orNull())
            val rule = source.provenance.rules.single()
            val multiplier = rule.rows.single().multiplier
            assertTrue(multiplier > 0)
            assertEquals(2L * multiplier, rule.divisor)
            assertEquals(1L, rule.reduction)
            assertFalse(rule.mir)
            assertEquals(tableau.divisor, rule.divisor)
            val sourceX = CutSource(CutSourceKind.INTEGER, 0)
            val sourceY = CutSource(CutSourceKind.INTEGER, 1)
            val z = CutSource(CutSourceKind.INTEGER, 2)
            assertEquals(
                CutPremise.Row(
                    CutExpression(
                        mapOf(
                            sourceX to BigFraction.ofLong(2),
                            sourceY to BigFraction.ofLong(2),
                            z to BigFraction.ONE,
                        ),
                    ),
                    Relation.LE,
                    BigFraction.ofLong(9),
                ),
                rule.rows.single().row,
            )
            assertEquals(Relation.GE, source.relation)
            assertEquals(
                CutPremise.Row(
                    CutExpression(mapOf(sourceX to BigFraction.MINUS_ONE, sourceY to BigFraction.MINUS_ONE)),
                    Relation.GE,
                    BigFraction.ofLong(-3),
                ),
                source.provenance.conclusion,
            )
            val guard = CutPremise.Bound(
                CutExpression(mapOf(z to BigFraction.ONE)),
                false,
                BigFraction.ofLong(2),
            )
            for (variable in listOf(sourceX, sourceY, z)) {
                val expression = CutExpression(mapOf(variable to BigFraction.ONE))
                assertTrue(source.provenance.facts.contains(CutProofFact(CutPremise.Integral(expression), true)))
            }
            assertTrue(source.provenance.facts.contains(CutProofFact(guard, false)))
            assertFalse(source.provenance.global)
            assertTrue(source.provenance.facts.none { it.premise == source.provenance.conclusion })

            assertTrue(engine.cutPool.cuts().isEmpty())
            engine.recordSearchCuts(listOf(raw), baseResult.primal, local, cp.session)
            val consumed = engine.cutPool.cuts().single()
            assertFalse(consumed.global)
            assertEquals(tableau.divisor, consumed.provenance?.rules?.single()?.divisor)
            assertFalse(assertNotNull(consumed.provenance).rules.single().mir)
            assertTrue(engine.cutPool.exportGlobalCuts().isEmpty())
            val applied = relaxer.build(cp.session, listOf(consumed))
            val row = applied.model.m - 1
            assertFalse(applied.model.rowGlobal[row])
            assertSame(consumed.provenance, applied.sourceMap?.parent(row))
            assertNull(applied.model.rowPremises[row])
            assertContentEquals(longArrayOf(0, 0, 2), applied.model.loShift)
            val coefficients = LongArray(applied.model.n)
            for (column in coefficients.indices) {
                applied.model.forEachInColumn(column) { index, value ->
                    if (index == row) coefficients[column] = value
                }
            }
            assertContentEquals(longArrayOf(1, 1, 0), coefficients)
            assertEquals(3L, applied.model.rhs[row])
            assertFalse(applied.model.hasUpper[applied.model.slackCol(row)])
            val tightened = assertNotNull(engine.solveNode(applied.model, null, Cancellation.Never)?.second)
            assertEquals(-3.0, tightened.objective)

            for (x in 0L..5L) {
                for (y in 0L..5L) {
                    for (value in 2L..5L) {
                        if (2 * x + 2 * y + value > 9) continue
                        assertTrue(x + y <= 3)
                        val lhs = source.expression.value { term ->
                            BigFraction.ofLong(
                                when (term.id) {
                                    0 -> x
                                    1 -> y
                                    else -> value
                                },
                            )
                        }
                        assertTrue(lhs >= source.rhs)
                    }
                }
            }
            assertTrue(2L * 1 + 2L * 2 + 2 <= 9 && 1L + 2L > applied.model.rhs[row] - 1)
            assertTrue(2L * 4 + 2L * 0 + 0 <= 9 && 4L + 0L > applied.model.rhs[row])

            search.popTo(0)
            assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertTrue(engine.cutPool.cuts().isEmpty())
            search.push(SearchDecision.IntAtMost(2, 1))
            assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertTrue(engine.cutPool.cuts().isEmpty())
            assertTrue(engine.cutPool.exportGlobalCuts().isEmpty())
            search.popTo(0)
            search.push(SearchDecision.IntAtLeast(2, 3))
            assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val reactivated = engine.cutPool.cuts().single()
            assertFalse(reactivated.global)
            assertTrue(assertNotNull(reactivated.provenance).facts.contains(CutProofFact(guard, false)))
            val sibling = relaxer.build(cp.session, listOf(reactivated))
            val siblingRow = sibling.model.m - 1
            assertContentEquals(longArrayOf(0, 0, 3), sibling.model.loShift)
            assertEquals(3L, sibling.model.rhs[siblingRow])
            assertFalse(sibling.model.rowGlobal[siblingRow])
            assertSame(reactivated.provenance, sibling.sourceMap?.parent(siblingRow))
            val siblingCoefficients = LongArray(sibling.model.n)
            for (column in siblingCoefficients.indices) {
                sibling.model.forEachInColumn(column) { index, value ->
                    if (index == siblingRow) siblingCoefficients[column] = value
                }
            }
            assertContentEquals(longArrayOf(1, 1, 0), siblingCoefficients)
        }
    }

    @Test
    fun `generated tableau cuts reach a shifted source row and a solved relaxation`() {
        for (mir in listOf(false, true)) {
            val rawCuts = ArrayList<Cut>()
            val solved = ArrayList<Pair<LpModel, Double>>()
            val factory = object : LpEngineFactory by ProductionLpEngineFactory {
                override fun newTableauSolver(
                    model: LpModel,
                    cancellation: Cancellation,
                    iterationLimit: Int,
                    workLimit: Long,
                    trackDegeneracy: Boolean,
                    pricing: LpPricingOptions,
                ): TableauCutSolver {
                    val solver = ProductionLpEngineFactory.newTableauSolver(
                        model,
                        cancellation,
                        iterationLimit,
                        workLimit,
                        trackDegeneracy,
                        pricing,
                    )
                    return object : TableauCutSolver by solver {
                        override fun solve(warm: Basis?) = solver.solve(warm).also { result ->
                            if (result != null) solved.add(model to result.objective)
                        }
                        override fun gomoryCuts(maxCuts: Int): List<Cut> =
                            solver.gomoryCuts(maxCuts).also(rawCuts::addAll)
                        override fun mirCuts(maxCuts: Int): List<Cut> = solver.mirCuts(maxCuts).also(rawCuts::addAll)
                    }
                }
            }
            val problem = Problem(
                0,
                2,
                arrayOf(IntDomain(1, 5), IntDomain(1, 5)),
                arrayOf(Linear(intArrayOf(2, 2), intArrayOf(0, 1), LinearOp.LE, 7)),
            )
            LpEngine(
                problem,
                LinearObjective(intCoefficients = longArrayOf(-1, -1)),
                LpParams(lpPlan = LpPlan(bounding = true, cuts = true)),
                SolveStatsSink(backend = if (mir) "tableau-mir" else "tableau-gomory"),
                solveContext = LpSolveContext(engineFactory = factory),
            ).use { engine ->
                val session = PropagationSession(problem)
                val relaxer = assertNotNull(engine.lpRelaxer)
                val base = relaxer.build(session)
                assertContentEquals(longArrayOf(1, 1), base.model.loShift)

                val harvested = engine.harvestRootCuts(
                    relaxer,
                    session,
                    emptyList(),
                    gomory = !mir,
                    mir = mir,
                )
                assertTrue(solved.size >= 2)
                assertEquals(-3.5, solved.first().second)
                assertEquals(-3.0, solved.last().second)
                val generated = harvested.single()
                val raw = rawCuts.single()
                val tableau = assertNotNull(raw.tableau)
                assertNull(raw.provenance)
                assertEquals(mir, tableau.mir)
                assertEquals(Relation.GE, raw.rel)
                assertContentEquals(intArrayOf(0, 1), raw.cols)
                assertContentEquals(longArrayOf(-1, -1), raw.coeffs)
                assertEquals(-3L, raw.rhs)
                assertContentEquals(raw.cols, generated.cols)
                assertContentEquals(raw.coeffs, generated.coeffs)
                assertEquals(raw.rhs, generated.rhs)
                val source = assertNotNull(SourceCut.fromCut(generated, base).orNull())
                val sourceX = CutSource(CutSourceKind.INTEGER, 0)
                val sourceY = CutSource(CutSourceKind.INTEGER, 1)
                val row = assertNotNull(source.provenance.conclusion)
                val rule = source.provenance.rules.single()
                assertEquals(mir, rule.mir)
                val multiplier = rule.rows.single().multiplier
                assertTrue(multiplier > 0)
                assertEquals(2L * multiplier, rule.divisor)
                assertEquals(tableau.divisor, rule.divisor)
                assertEquals(if (mir) multiplier else 1L, rule.reduction)
                assertEquals(tableau.reduction, rule.reduction)
                assertEquals(
                    CutPremise.Row(
                        CutExpression(mapOf(sourceX to BigFraction.ofLong(2), sourceY to BigFraction.ofLong(2))),
                        Relation.LE,
                        BigFraction.ofLong(7),
                    ),
                    rule.rows.single().row,
                )
                assertEquals(Relation.GE, source.relation)
                assertEquals(
                    CutPremise.Row(
                        CutExpression(mapOf(sourceX to BigFraction.MINUS_ONE, sourceY to BigFraction.MINUS_ONE)),
                        Relation.GE,
                        BigFraction.ofLong(-3),
                    ),
                    row,
                )
                assertTrue(source.provenance.facts.none { it.premise == row })
                for (sourceVariable in listOf(sourceX, sourceY)) {
                    val expression = CutExpression(mapOf(sourceVariable to BigFraction.ONE))
                    assertTrue(source.provenance.facts.contains(CutProofFact(CutPremise.Integral(expression), true)))
                    assertTrue(
                        source.provenance.facts.contains(
                            CutProofFact(CutPremise.Bound(expression, false, BigFraction.ONE), true),
                        ),
                    )
                }
                assertTrue(source.provenance.global)

                engine.cutPool.addAll(harvested)
                val root = assertNotNull(engine.nodeRelaxation(relaxer, session))
                val consumed = engine.cutPool.cuts().single()
                assertTrue(consumed.global)
                val applied = relaxer.build(session, listOf(consumed))
                val solvedModel = solved.last().first
                assertEquals(root.model.m + 1, solvedModel.m)
                assertContentEquals(applied.model.csc.colPtr, solvedModel.csc.colPtr)
                assertContentEquals(applied.model.csc.rowIdx, solvedModel.csc.rowIdx)
                assertContentEquals(applied.model.csc.colVal, solvedModel.csc.colVal)
                assertContentEquals(applied.model.rhs, solvedModel.rhs)
                assertContentEquals(applied.model.loShift, solvedModel.loShift)
                val cutRow = solvedModel.m - 1
                val structural = LongArray(solvedModel.n)
                for (column in structural.indices) {
                    solvedModel.forEachInColumn(column) { rowIndex, value ->
                        if (rowIndex == cutRow) structural[column] = value
                    }
                }
                assertContentEquals(longArrayOf(1, 1), structural)
                assertEquals(1L, solvedModel.rhs[cutRow])
                assertContentEquals(longArrayOf(1, 1), solvedModel.loShift)
                assertTrue(solvedModel.rowGlobal[cutRow])
                assertNull(solvedModel.rowPremises[cutRow])
                assertSame(consumed.provenance, applied.sourceMap?.parent(cutRow))
                assertFalse(solvedModel.hasUpper[solvedModel.slackCol(cutRow)])
                val sourceThreshold = solvedModel.rhs[cutRow] +
                    structural.indices.sumOf { structural[it] * solvedModel.loShift[it] }
                assertEquals(3L, sourceThreshold)

                for (x in 1L..5L) {
                    for (y in 1L..5L) {
                        if (2 * x + 2 * y > 7) continue
                        val observed = row.expression.value { BigFraction.ofLong(if (it == sourceX) x else y) }
                        assertTrue(observed >= row.rhs)
                        assertTrue(x + y <= sourceThreshold)
                    }
                }
                assertTrue(2L * 1 + 2L * 2 <= 7 && 1L + 2L > sourceThreshold - 1)

                session.implyIntAtLeast(0, 2)
                assertNotNull(engine.nodeRelaxation(relaxer, session))
                val remapped = engine.cutPool.cuts().single()
                assertTrue(remapped.global)
                val shifted = relaxer.build(session, listOf(remapped))
                val shiftedRow = shifted.model.m - 1
                assertContentEquals(longArrayOf(2, 1), shifted.model.loShift)
                assertEquals(0L, shifted.model.rhs[shiftedRow])
                assertSame(remapped.provenance, shifted.sourceMap?.parent(shiftedRow))
                val shiftedCoefficients = LongArray(shifted.model.n)
                for (column in shiftedCoefficients.indices) {
                    shifted.model.forEachInColumn(column) { index, value ->
                        if (index == shiftedRow) shiftedCoefficients[column] = value
                    }
                }
                assertContentEquals(longArrayOf(1, 1), shiftedCoefficients)
                assertEquals(
                    sourceThreshold,
                    shifted.model.rhs[shiftedRow] +
                        shiftedCoefficients.indices.sumOf {
                            shiftedCoefficients[it] * shifted.model.loShift[it]
                        },
                )
            }
        }
    }

    @Test
    fun `generated Hall cut survives pool remapping as an exact source row`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(2, 7) },
            arrayOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 2, domainSize = 6)),
        )
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1, 1, 1)),
            LpParams(lpPlan = LpPlan(bounding = true, cuts = true)),
            SolveStatsSink(backend = "generated-hall"),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val search = SearchSession(listOf(cp, engine.propagator))
            search.initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            val root = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertEquals(0, root.model.m)
            val rootResult = assertNotNull(engine.solveNode(root.model, null, Cancellation.Never)?.second)
            assertEquals(6.0, rootResult.objective)
            val emitted = AllDifferentSeparator()
                .separate(CutContext(problem, root, rootResult.primal, cp.session))
                .single { it.rel == Relation.GE }
            assertTrue(emitted.global)
            assertContentEquals(intArrayOf(0, 1, 2).map { root.intColOf[it] }.toIntArray(), emitted.cols)
            assertContentEquals(longArrayOf(1, 1, 1), emitted.coeffs)
            assertEquals(9L, emitted.rhs)
            assertTrue(emitted.provenance == null)
            engine.recordSearchCuts(listOf(emitted), rootResult.primal, root, cp.session)
            assertEquals(1, engine.cutPool.size)

            search.push(SearchDecision.IntAtLeast(0, 4))
            val node = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val consumed = engine.cutPool.cuts().single()
            assertTrue(consumed.global)
            assertContentEquals(emitted.cols, consumed.cols)
            assertContentEquals(emitted.coeffs, consumed.coeffs)
            assertEquals(emitted.rhs, consumed.rhs)
            val baseResult = assertNotNull(engine.solveNode(node.model, null, Cancellation.Never)?.second)
            assertEquals(8.0, baseResult.objective)
            val applied = relaxer.build(cp.session, listOf(consumed))
            assertEquals(node.model.m + 1, applied.model.m)
            assertContentEquals(intArrayOf(0, 1, 2), IntArray(3) { applied.intColOf[it] })
            val row = applied.model.m - 1
            val coefficients = LongArray(applied.model.n)
            for (column in coefficients.indices) {
                applied.model.forEachInColumn(column) { index, value ->
                    if (index == row) coefficients[column] = value
                }
            }
            val sourceCoefficients = coefficients.map { -it }.toLongArray()
            val sourceThreshold = -applied.model.rhs[row] -
                coefficients.indices.sumOf { coefficients[it] * applied.model.loShift[it] }
            assertContentEquals(longArrayOf(1, 1, 1), sourceCoefficients)
            assertEquals(9L, sourceThreshold)
            assertContentEquals(longArrayOf(4, 2, 2), applied.model.loShift.copyOfRange(0, 3))
            assertEquals(-1L, applied.model.rhs[row])
            assertTrue(applied.model.rowGlobal[row])
            assertTrue(!applied.model.hasUpper[applied.model.slackCol(row)])
            assertTrue(applied.model.rowPremises[row] == null)
            val tightened = assertNotNull(engine.solveNode(applied.model, null, Cancellation.Never)?.second)
            assertEquals(9.0, tightened.objective)

            var strongerCounterexample = false
            for (x in 2L..7L) {
                for (y in 2L..7L) {
                    for (z in 2L..7L) {
                        if (x == y || x == z || y == z) continue
                        val lhs = sourceCoefficients[0] * x + sourceCoefficients[1] * y + sourceCoefficients[2] * z
                        assertTrue(lhs >= sourceThreshold)
                        if (lhs < sourceThreshold + 1) strongerCounterexample = true
                    }
                }
            }
            assertTrue(strongerCounterexample)
        }
    }

    @Test
    fun `generated local Hall cut is pooled while all source interval guards hold`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 5) },
            arrayOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 6)),
        )
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1, 1, 1)),
            LpParams(lpPlan = LpPlan(bounding = true, cuts = true)),
            SolveStatsSink(backend = "local-hall"),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val search = SearchSession(listOf(cp, engine.propagator))
            search.initialize()
            search.push(SearchDecision.IntAtLeast(1, 3))
            search.push(SearchDecision.IntAtLeast(2, 3))
            search.push(SearchDecision.IntAtLeast(0, 3))
            val relaxer = assertNotNull(engine.lpRelaxer)
            val local = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertEquals(0, local.model.m)
            val base = assertNotNull(engine.solveNode(local.model, null, Cancellation.Never)?.second)
            assertEquals(9.0, base.objective)
            val emitted = AllDifferentSeparator()
                .separate(CutContext(problem, local, base.primal, cp.session))
                .single { it.rel == Relation.GE }
            assertEquals(12L, emitted.rhs)
            assertFalse(emitted.global)
            val proof = assertNotNull(emitted.provenance)
            for (variable in 0..2) {
                assertTrue(proof.facts.contains(CutProofFact(
                    CutPremise.Bound(
                        CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, variable) to BigFraction.ONE)),
                        false, BigFraction.ofLong(3),
                    ), false,
                )))
            }
            engine.recordSearchCuts(listOf(emitted), base.primal, local, cp.session)
            assertEquals(1, engine.cutPool.cuts().size)
            assertEquals(1, engine.cutPool.size)
            val localApplied = relaxer.build(cp.session, listOf(emitted))
            assertEquals(local.model.m + 1, localApplied.model.m)
            assertFalse(localApplied.model.rowGlobal.last())
            assertTrue(localApplied.model.rowPremises.last() == null)
            assertEquals(
                12.0,
                assertNotNull(engine.solveNode(localApplied.model, null, Cancellation.Never)?.second).objective,
            )

            var missingGuardCounterexample = false
            var strongerCounterexample = false
            for (x in 0L..5L) {
                for (y in 0L..5L) {
                    for (z in 0L..5L) {
                        if (x == y || x == z || y == z || y < 3 || z < 3) continue
                        val sum = x + y + z
                        if (x >= 3) {
                            assertTrue(sum >= emitted.rhs)
                            if (sum < emitted.rhs + 1) strongerCounterexample = true
                        } else if (sum < emitted.rhs) {
                            missingGuardCounterexample = true
                        }
                    }
                }
            }
            assertTrue(missingGuardCounterexample)
            assertTrue(strongerCounterexample)

            search.popTo(2)
            search.push(SearchDecision.IntAtMost(0, 2))
            val sibling = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertTrue(engine.cutPool.cuts().isEmpty())
            assertEquals(0, sibling.model.m)
            assertEquals(
                6.0,
                assertNotNull(engine.solveNode(sibling.model, null, Cancellation.Never)?.second).objective,
            )
        }
    }

    @Test
    fun `root harvest retains a Hall cut over declared finite bounds`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 3) },
            arrayOf(AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 4)),
        )
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1, 1)),
            LpParams(lpPlan = LpPlan(bounding = true, cuts = true)),
            SolveStatsSink(backend = "finite-hall"),
        ).use { engine ->
            val relaxer = assertNotNull(engine.lpRelaxer)
            val harvested = engine.harvestRootCuts(
                relaxer,
                PropagationSession(problem),
                listOf(AllDifferentSeparator()),
                gomory = false,
                mir = false,
            )
            assertTrue(harvested.any { it.global && it.rel == Relation.GE && it.rhs == 1L })
        }
    }

    @Test
    fun `root harvest refuses a Hall cut based on an invented upper bound`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 5) },
            arrayOf(AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 6)),
            openIntHi = booleanArrayOf(true, false, false),
        )
        assertFalse(problem.intBounds.hasUpper(0))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, -1, -1))
        LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true, cuts = true)),
            SolveStatsSink(backend = "open-hall"),
        ).use { engine ->
            val session = PropagationSession(problem)
            val relaxer = assertNotNull(engine.lpRelaxer)
            val relaxation = relaxer.build(session)
            val emitted = AllDifferentSeparator()
                .separate(CutContext(problem, relaxation, DoubleArray(relaxation.model.numVars) { 5.0 }, session))
                .single { it.rel == Relation.LE }
            assertEquals(12L, emitted.rhs)
            assertFalse(emitted.global)
            val originalSourceWitness = longArrayOf(6, 3, 4)
            assertTrue(originalSourceWitness[0] >= problem.intBounds.lower(0))
            assertTrue(originalSourceWitness[1] in problem.intBounds.lower(1)..problem.intBounds.upper(1))
            assertTrue(originalSourceWitness[2] in problem.intBounds.lower(2)..problem.intBounds.upper(2))
            assertTrue(originalSourceWitness.distinct().size == 3)
            assertTrue(originalSourceWitness[0] + originalSourceWitness[1] + originalSourceWitness[2] > emitted.rhs)
            val harvested = engine.harvestRootCuts(
                relaxer,
                session,
                listOf(AllDifferentSeparator()),
                gomory = false,
                mir = false,
            )
            engine.cutPool.addAll(harvested)
            assertTrue(harvested.none { it.global && it.rel == Relation.LE && it.rhs == emitted.rhs })
            assertTrue(engine.lpGlobalCuts.isEmpty())
            assertTrue(engine.cutPool.exportGlobalCuts().isEmpty())
        }
    }

    @Test
    fun `a shifted source row survives cut pool remapping`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(-3, 5), IntDomain(2, 8)),
            arrayOf(Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.GE, -4)),
        )
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(0, 0)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "source-cut"),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            val root = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val cut = Cut(intArrayOf(root.model.slackCol(0)), longArrayOf(1), Relation.GE, 0, global = true)
            val source = assertNotNull(SourceCut.fromCut(cut, root).orNull())
            for (x in -3L..5L) {
                for (y in 2L..8L) {
                    val lhs = source.expression.value { variable ->
                        BigFraction.ofLong(if (variable.id == 0) x else y)
                    }
                    assertEquals(2 * x - y >= -4, lhs >= source.rhs)
                }
            }
            val boundary = source.expression.value { variable ->
                BigFraction.ofLong(if (variable.id == 0) -1 else 2)
            }
            assertEquals(source.rhs, boundary)
            engine.recordSearchCuts(listOf(cut), DoubleArray(root.model.numVars), root, cp.session)
            assertEquals(1, engine.cutPool.cuts().size)
            shared.push(SearchDecision.IntAtLeast(0, -1))
            val sibling = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertEquals(1, engine.cutPool.cuts().size)
            val remapped = engine.cutPool.cuts().single()
            assertTrue(remapped.global)
            val applied = relaxer.build(cp.session, listOf(remapped))
            assertEquals(sibling.model.m + 1, applied.model.m)
            for (x in -3L..5L) {
                for (y in 2L..8L) {
                    val values = longArrayOf(x, y)
                    val lhs = remapped.cols.indices.sumOf { remapped.coeffs[it] * values[remapped.cols[it]] }
                    assertEquals(2 * x - y >= -4, lhs >= remapped.rhs)
                }
            }
            assertNotNull(sibling.sourceMap)
        }
    }

    @Test
    fun `shared bound changes and pop reuse factors with source equivalent proof views`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(-3, 7) },
            arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            ),
        )
        var constructions = 0
        var closes = 0
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
                constructions++
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
                    override fun close() {
                        closes++
                        delegate.close()
                    }
                }
            }
        }
        val objective = LinearObjective(intCoefficients = longArrayOf(2, 1), constant = 5)
        LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "trail"),
            LpSolveContext(factory),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            val root = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val initial = assertNotNull(engine.solveNode(root.model, null, Cancellation.Never)?.second)
            val saved = initial.primal.copyOf()
            val authority = assertNotNull(engine.propagator.state).model

            shared.push(SearchDecision.IntAtLeast(0, 0))
            val child = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val result = assertNotNull(engine.solveNode(child.model, null, Cancellation.Never)?.second)
            assertEquals(0, engine.propagator.lastMetrics.initialRefactorizations)
            for (x in 0L..7L) {
                for (y in -3L..7L) {
                    val column = child.intColOf[0]
                    val origin = child.model.exactShift(column)
                    assertEquals(authority.column(column).origin.value, origin)
                    assertEquals(
                        BigFraction.ZERO,
                        origin + assertNotNull(child.model.exactBounds(column).lower).number.value,
                    )
                    assertEquals(
                        BigFraction.ofLong(7),
                        origin + assertNotNull(child.model.exactBounds(column).upper).number.value,
                    )
                    val shifted = listOf(
                        BigFraction.ofLong(x) - origin,
                        BigFraction.ofLong(y) - child.model.exactShift(1),
                    )
                    var activity = BigFraction.ZERO
                    var objectiveValue = child.model.exactConstant()
                    for (j in shifted.indices) {
                        child.model.forEachRationalColumn(j) { _, a -> activity += a * shifted[j] }
                        objectiveValue += child.model.exactCost(j) * shifted[j]
                    }
                    assertEquals(x + y >= 1L, activity <= child.model.exactRhs(0))
                    assertEquals(
                        BigFraction.ofLong(2 * x + y + 5),
                        child.model.sourceObjective(objectiveValue) + BigFraction.ofLong(child.objectiveConstant),
                    )
                }
            }
            assertEquals(1.0, result.objective)
            shared.popTo(0)
            val restored = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertTrue(authority.sameAuthority(assertNotNull(engine.propagator.state).model))
            assertNotNull(engine.solveNode(restored.model, null, Cancellation.Never)?.second)
            assertEquals(0, engine.propagator.lastMetrics.initialRefactorizations)
            assertEquals(0, engine.propagator.lastMetrics.warmStartRefactorizations)
            assertContentEquals(saved, initial.primal)
            assertEquals(1, constructions)
            engine.releasePersistentSolvers()
            assertEquals(constructions, closes)
            assertNotNull(engine.solveNode(restored.model, null, Cancellation.Never)?.second)
            assertEquals(2, constructions)
        }
        assertEquals(constructions, closes)
    }

    @Test
    fun `cancelled pruning entry preserves authority and performs no solver work`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val sink = SolveStatsSink(backend = "cancelled")
        var cancelled = false
        val token = Cancellation { cancelled }
        LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true), cancellation = token),
            sink,
        ).use { engine ->
            val native = PropagationSession(problem)
            val relaxer = assertNotNull(engine.lpRelaxer)
            val root = assertNotNull(engine.nodeRelaxation(relaxer, native))
            assertNotNull(engine.solveNode(root.model, null, token))
            val authority = engine.propagator.state
            val metrics = engine.propagator.metrics
            cancelled = true

            val outcome = engine.sparseSafePrune(relaxer, native, -1.0, sink, token, -1, true)

            assertFalse(outcome.prune)
            assertNull(outcome.basis)
            assertSame(authority, engine.propagator.state)
            assertEquals(metrics, engine.propagator.metrics)
            cancelled = false
            assertNotNull(engine.solveNode(root.model, null, token)?.second)
            assertEquals(0, engine.propagator.lastMetrics.initialRefactorizations)
        }
    }

    @Test
    fun `standalone sibling bounds cannot retain a stronger prior assertion`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(-5, 5)), emptyArray())
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "siblings"),
        ).use { engine ->
            val session = PropagationSession(problem)
            val relaxer = assertNotNull(engine.lpRelaxer)
            session.pinIntAtLeast(0, 3)
            val child = assertNotNull(engine.nodeRelaxation(relaxer, session))
            assertEquals(BigFraction.ofLong(-5L), child.model.exactShift(0))
            assertEquals(
                BigFraction.ofLong(3L),
                child.model.exactShift(0) + assertNotNull(child.model.exactBounds(0).lower).number.value,
            )
            session.popToLevel(0)
            session.pinIntAtMost(0, -2)
            val sibling = assertNotNull(engine.nodeRelaxation(relaxer, session))
            assertEquals(BigFraction.ofLong(-5L), sibling.model.exactShift(0))
            assertEquals(
                BigFraction.ofLong(-5L),
                sibling.model.exactShift(0) + assertNotNull(sibling.model.exactBounds(0).lower).number.value,
            )
            assertEquals(
                BigFraction.ofLong(-2L),
                sibling.model.exactShift(0) + assertNotNull(sibling.model.exactBounds(0).upper).number.value,
            )
            assertEquals(
                -5.0,
                assertNotNull(engine.solveNode(sibling.model, null, Cancellation.Never)?.second).objective,
            )
        }
    }

    @Test
    fun `pooled local cuts retain guards absent from their columns through siblings`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 2)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "guards"),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            shared.push(SearchDecision.Bool(0))
            shared.push(SearchDecision.IntAtLeast(0, 1))
            val relaxer = assertNotNull(engine.lpRelaxer)
            val relaxation = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val expression = CutExpression(
                mapOf(
                    CutSource(CutSourceKind.INTEGER, 0) to BigFraction.ONE,
                ),
            )
            val facts = listOf(
                CutProofFact(CutPremise.Literal(0), false),
                CutProofFact(
                    CutPremise.Bound(expression, false, BigFraction.ONE),
                    false,
                ),
            )
            val cut = Cut(
                intArrayOf(relaxation.intColOf[0]),
                longArrayOf(1),
                Relation.GE,
                1,
                provenance = CutProvenance(problem, 0, facts),
            )
            engine.recordSearchCuts(listOf(cut), doubleArrayOf(1.0), relaxation, cp.session)
            assertEquals(1, engine.cutPool.cuts().size)
            assertTrue(engine.cutPool.exportGlobalCuts().isEmpty())
            val consumed = engine.cutPool.cuts().single()
            assertContentEquals(intArrayOf(relaxation.intColOf[0]), consumed.cols)
            assertEquals(1L, consumed.coeffs.single())
            assertEquals(1L, consumed.rhs)
            assertFalse(consumed.global)
            assertEquals(facts, assertNotNull(consumed.provenance).facts.filter { !it.global })
            val cutRelaxation = relaxer.build(cp.session, engine.cutPool.cuts())
            val reason = IntArrayList()
            assertTrue(
                LpExplanation.addRowPremiseLits(
                    reason,
                    IntHashSet(),
                    cutRelaxation,
                    intArrayOf(cutRelaxation.model.m - 1),
                    cp.session,
                ),
            )
            val boundLiteral = cp.session.boundGeLit(0, 1, positive = false)
            assertEquals(setOf(1, boundLiteral), reason.toIntArray().toSet())
            for (guard in listOf(false, true)) {
                for (x in 0L..2L) {
                    val antecedent = reason.toIntArray().none { literal ->
                        when (literal) {
                            1 -> !guard
                            boundLiteral -> x < 1L
                            else -> error("unexpected source literal")
                        }
                    }
                    assertTrue(!antecedent || x >= 1L)
                }
            }

            shared.popTo(0)
            shared.push(SearchDecision.Bool(1))
            shared.push(SearchDecision.IntAtLeast(0, 1))
            assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertTrue(engine.cutPool.cuts().isEmpty())
            assertEquals(1, engine.cutPool.size)
            shared.popTo(0)
            shared.push(SearchDecision.Bool(0))
            shared.push(SearchDecision.IntAtLeast(0, 1))
            assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertEquals(1, engine.cutPool.cuts().size)
            assertEquals(facts, engine.cutPool.cuts().single().provenance?.facts?.filter { !it.global })
        }
    }

    @Test
    fun `interior domain removal updates auxiliary presence without changed source endpoints`() {
        val problem = Problem(
            0,
            3,
            arrayOf(IntDomain(0, 4), IntDomain(0, 5), IntDomain(0, 4)),
            arrayOf(
                Table(intArrayOf(0, 1), longArrayOf(0, 5, 2, 2, 4, 0)),
                AllDifferent(intArrayOf(0, 2), 0, 5),
            ),
        )
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1, 0, 0)),
            LpParams(lpPlan = LpPlan(bounding = true, table = true)),
            SolveStatsSink(backend = "presence"),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            val root = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val auxiliary = root.colReq.indices.first { column ->
                root.colReq[column]?.toList() == listOf(0L, 2L, 1L, 2L)
            }
            assertEquals(BigFraction.ONE, root.model.exactBounds(auxiliary).upper?.number?.value)
            shared.push(SearchDecision.IntEqual(2, 2))

            val child = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))

            assertEquals(0L, cp.session.intDomain(0).min)
            assertEquals(4L, cp.session.intDomain(0).max)
            assertTrue(!cp.session.intDomain(0).contains(2L))
            assertEquals(BigFraction.ZERO, child.model.exactBounds(auxiliary).upper?.number?.value)
            shared.popTo(0)
            assertEquals(
                BigFraction.ONE,
                assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
                    .model.exactBounds(auxiliary).upper?.number?.value,
            )
        }
    }

    @Test
    fun `a reseeded native root replaces previous bound authority`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        LpEngine(
            problem,
            LinearObjective(intCoefficients = longArrayOf(1)),
            LpParams(lpPlan = LpPlan(bounding = true)),
            SolveStatsSink(backend = "reseed"),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            cp.session.seed(Assumptions(ints = mapOf(0 to 4L)))
            cp.rebase()
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            val relaxer = assertNotNull(engine.lpRelaxer)
            val before = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertEquals(4.0, assertNotNull(engine.solveNode(before.model, null, Cancellation.Never)?.second).objective)
            cp.session.reseedFrom(Assumptions(ints = mapOf(0 to 1L)))
            cp.rebase()
            shared.resetRootFacts()
            shared.initialize()
            val after = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertEquals(BigFraction.ZERO, after.model.exactShift(0))
            assertEquals(BigFraction.ONE, assertNotNull(after.model.exactBounds(0).lower).number.value)
            assertEquals(BigFraction.ONE, assertNotNull(after.model.exactBounds(0).upper).number.value)
            assertEquals(1.0, assertNotNull(engine.solveNode(after.model, null, Cancellation.Never)?.second).objective)
            assertEquals(1L, assertNotNull(engine.propagator.metrics).createdOwners)
            assertEquals(0, engine.propagator.lastMetrics.initialRefactorizations)
            assertEquals(0, assertNotNull(engine.propagator.state).depth)
        }
    }

    @Test
    fun `a cancelled column retains its load bearing live M guard through recursive cuts and siblings`() {
        val problem = Problem(
            1,
            3,
            Array(3) { IntDomain(0, 2) },
            arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 1),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 1),
                ReifiedLinear(0, intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 1),
            ),
        )
        val objective = LinearObjective(intCoefficients = LongArray(3))
        LpEngine(
            problem,
            objective,
            LpParams(lpPlan = LpPlan(bounding = true, learn = true)),
            SolveStatsSink(backend = "live-guard"),
        ).use { engine ->
            val cp = CpSearchComponent(PropagationSession(problem))
            engine.cpAdapter.attach(cp.session, feasibility = false)
            val shared = SearchSession(listOf(cp, engine.propagator))
            shared.initialize()
            for (variable in 0..2) shared.push(SearchDecision.IntAtMost(variable, 1))
            val relaxer = assertNotNull(engine.lpRelaxer)
            val live = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val authority = assertNotNull(live.model.authoritativeModel())
            val boolColumn = live.boolColOf[0]
            var row = -1
            for (entry in authority.entries(boolColumn)) {
                if (entry.number.value > BigFraction.ZERO) row = entry.row
            }
            assertTrue(row >= 0)
            assertFalse(live.model.rowGlobal[row])
            val coefficients = LongArray(live.model.n)
            for (column in coefficients.indices) {
                for (entry in authority.entries(column)) {
                    if (entry.row == row) coefficients[column] = assertNotNull(entry.number.exactLong())
                }
            }
            val rhs = assertNotNull(authority.rhs(row).exactLong()) + coefficients.indices.sumOf {
                coefficients[it] * assertNotNull(authority.column(it).origin.exactLong())
            } -
                coefficients[boolColumn]
            assertEquals(1L, rhs)
            val premises = assertNotNull(authority.row(row).premises?.toLegacy())
            val facts = premises.vars.indices.map { index ->
                CutProofFact(
                    CutPremise.Bound(
                        CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, premises.vars[index]) to BigFraction.ONE)),
                        premises.isUpper[index],
                        BigFraction.ofLong(premises.thresholds[index]),
                    ),
                    false,
                )
            } + CutProofFact(CutPremise.Literal(0), false)
            shared.push(SearchDecision.Bool(0))
            val active = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val parent = Cut(
                IntArray(3) { active.intColOf[it] },
                LongArray(3) { coefficients[live.intColOf[it]] },
                Relation.LE,
                rhs,
                provenance = CutProvenance(problem, 0, facts),
            )
            val withParent = relaxer.build(cp.session, listOf(parent))
            val parentRow = withParent.model.m - 1
            val recursive = Cut(
                parent.cols,
                parent.coeffs,
                parent.rel,
                parent.rhs,
                tableau = TableauCutProvenance(
                    withParent.model,
                    emptyList(),
                    listOf(
                        CutInputRow(
                            parentRow,
                            false,
                            1,
                            BigFraction.ofLong(parent.rhs),
                            parent.rel,
                            parent.cols,
                            parent.coeffs,
                            null,
                        ),
                    ),
                    divisor = 1,
                    mir = false,
                ),
            )
            val source = assertNotNull(SourceCut.fromCut(recursive, withParent).orNull())
            val child = assertNotNull(
                source.toCut(
                    assertNotNull(withParent.sourceMap)
                        .withCpBounds(withParent.model, cp.session),
                ).orNull(),
            )
            assertTrue(child.cols.none { withParent.colIsBool[it] })
            assertTrue(assertNotNull(child.provenance).facts.contains(CutProofFact(CutPremise.Literal(0), false)))

            val builder = LpBuilder()
            repeat(3) { builder.addVar(0, 1) }
            builder.addRow(mapOf(0 to -1L, 1 to -1L), Relation.LE, -1)
            builder.addRow(mapOf(1 to -1L, 2 to -1L), Relation.LE, -1)
            builder.addRow(mapOf(0 to -1L, 2 to -1L), Relation.LE, -1)
            builder.addRow(intArrayOf(0, 1, 2), longArrayOf(1, 1, 1), Relation.LE, 1, global = false)
            val model = builder.build(Sense.MINIMIZE)
            val proofView = LpRelaxation(
                model,
                intArrayOf(0, 1, 2),
                BooleanArray(3),
                0,
                intArrayOf(0, 1, 2),
                intArrayOf(-1),
                sourceMap = cpCutSources(
                    model,
                    problem,
                    intArrayOf(0, 1, 2),
                    BooleanArray(3),
                    IntArray(3) { -1 },
                    IntArray(3) { 1 },
                    mapOf(3 to assertNotNull(child.provenance)),
                ),
            )
            val solved = assertNotNull(engine.solveNode(model, null, Cancellation.Never))
            assertNull(solved.second)
            val ray = assertNotNull(integerFarkasRay(model, assertNotNull(solved.first.infeasibleRay)))
            val clause = assertNotNull(LpExplanation.infeasibilityClause(proofView, ray, cp.session))
            val boundLiterals = IntArray(3) { cp.session.boundLeLit(it, 1, positive = false) }
            assertEquals(setOf(1) + boundLiterals.toSet(), clause.toSet())
            var excludedWithoutGuard = 0
            for (b in listOf(false, true)) {
                for (x in 0L..2L) {
                    for (y in 0L..2L) {
                        for (z in 0L..2L) {
                            if (x + y < 1 || y + z < 1 || x + z < 1 || b != (x + y + z <= 1)) continue
                            val values = longArrayOf(x, y, z)
                            val boundEscape = boundLiterals.indices.any { values[it] > 1 }
                            assertTrue(!b || boundEscape)
                            assertTrue(
                                clause.any { literal ->
                                    if (literal == 1) !b else values[boundLiterals.indexOf(literal)] > 1
                                },
                            )
                            if (!boundEscape) excludedWithoutGuard++
                        }
                    }
                }
            }
            assertTrue(excludedWithoutGuard > 0)
            engine.recordSearchCuts(listOf(child), DoubleArray(withParent.model.n), withParent, cp.session)
            assertEquals(1, engine.cutPool.cuts().size)
            assertTrue(
                engine.pruneNode(
                    cp.session,
                    effectiveBound = Double.POSITIVE_INFINITY,
                    objectiveVar = -1,
                    objectiveAscending = true,
                ),
            )
            assertEquals(clause.toSet(), assertNotNull(engine.lastBackjump()).literals.toSet())
            shared.popTo(0)
            assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertTrue(engine.cutPool.cuts().isEmpty())
            for (variable in 0..2) shared.push(SearchDecision.IntAtMost(variable, 1))
            shared.push(SearchDecision.Bool(1))
            assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertTrue(engine.cutPool.cuts().isEmpty())
            shared.popTo(0)
            for (variable in 0..2) shared.push(SearchDecision.IntAtMost(variable, 1))
            shared.push(SearchDecision.Bool(0))
            assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            assertEquals(1, engine.cutPool.cuts().size)
            assertTrue(engine.cutPool.exportGlobalCuts().isEmpty())
        }
    }
}
