package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.cut.AllDifferentSeparator
import com.eignex.klause.lp.cut.CutContext
import com.eignex.klause.lp.cut.SourceCut
import com.eignex.klause.lp.cut.orNull
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
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.TableauCutProvenance
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.lp.engine.integerFarkasRay
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
            val root = engine.nodeRelaxation(relaxer, cp.session)
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
            val harvested = engine.harvestRootCuts(
                relaxer,
                cp.session,
                listOf(AllDifferentSeparator()),
                gomory = false,
                mir = false,
            )
            assertTrue(harvested.any { it.global && it.rel == emitted.rel && it.rhs == emitted.rhs })
            engine.recordSearchCuts(listOf(emitted), rootResult.primal, root, cp.session)
            assertEquals(1, engine.cutPool.size)

            search.push(SearchDecision.IntAtLeast(0, 4))
            val node = engine.nodeRelaxation(relaxer, cp.session)
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
            for (x in 2L..7L) for (y in 2L..7L) for (z in 2L..7L) {
                if (x == y || x == z || y == z) continue
                val lhs = sourceCoefficients[0] * x + sourceCoefficients[1] * y + sourceCoefficients[2] * z
                assertTrue(lhs >= sourceThreshold)
                if (lhs < sourceThreshold + 1) strongerCounterexample = true
            }
            assertTrue(strongerCounterexample)
        }
    }

    @Test
    fun `generated local Hall cut declines pool reuse after its bound is withdrawn`() {
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
            val local = engine.nodeRelaxation(relaxer, cp.session)
            assertEquals(0, local.model.m)
            val base = assertNotNull(engine.solveNode(local.model, null, Cancellation.Never)?.second)
            assertEquals(9.0, base.objective)
            val emitted = AllDifferentSeparator()
                .separate(CutContext(problem, local, base.primal, cp.session))
                .single { it.rel == Relation.GE }
            assertEquals(12L, emitted.rhs)
            assertFalse(emitted.global)
            assertTrue(emitted.provenance == null)
            engine.recordSearchCuts(listOf(emitted), base.primal, local, cp.session)
            assertTrue(engine.cutPool.cuts().isEmpty())
            assertEquals(0, engine.cutPool.size)
            val localApplied = relaxer.build(cp.session, listOf(emitted))
            assertEquals(local.model.m + 1, localApplied.model.m)
            assertFalse(localApplied.model.rowGlobal.last())
            assertTrue(localApplied.model.rowPremises.last() == null)
            assertEquals(12.0, assertNotNull(engine.solveNode(localApplied.model, null, Cancellation.Never)?.second).objective)

            var missingGuardCounterexample = false
            var strongerCounterexample = false
            for (x in 0L..5L) for (y in 0L..5L) for (z in 0L..5L) {
                if (x == y || x == z || y == z || y < 3 || z < 3) continue
                val sum = x + y + z
                if (x >= 3) {
                    assertTrue(sum >= emitted.rhs)
                    if (sum < emitted.rhs + 1) strongerCounterexample = true
                } else if (sum < emitted.rhs) {
                    missingGuardCounterexample = true
                }
            }
            assertTrue(missingGuardCounterexample)
            assertTrue(strongerCounterexample)

            search.popTo(2)
            search.push(SearchDecision.IntAtMost(0, 2))
            val sibling = engine.nodeRelaxation(relaxer, cp.session)
            assertTrue(engine.cutPool.cuts().isEmpty())
            assertEquals(0, sibling.model.m)
            assertEquals(6.0, assertNotNull(engine.solveNode(sibling.model, null, Cancellation.Never)?.second).objective)
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
            val root = engine.nodeRelaxation(relaxer, cp.session)
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
            val sibling = engine.nodeRelaxation(relaxer, cp.session)
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
            val root = engine.nodeRelaxation(relaxer, cp.session)
            val initial = assertNotNull(engine.solveNode(root.model, null, Cancellation.Never)?.second)
            val saved = initial.primal.copyOf()
            val authority = assertNotNull(engine.propagator.state).model

            shared.push(SearchDecision.IntAtLeast(0, 0))
            val child = engine.nodeRelaxation(relaxer, cp.session)
            val result = assertNotNull(engine.solveNode(child.model, null, Cancellation.Never)?.second)
            assertEquals(0, engine.propagator.lastMetrics.initialRefactorizations)
            for (x in 0L..7L) {
                for (y in -3L..7L) {
                    val column = child.intColOf[0]
                    assertEquals(BigFraction.ZERO, child.model.exactShift(column))
                    assertEquals(BigFraction.ofLong(7), child.model.exactBounds(column).upper?.number?.value)
                    val shifted = longArrayOf(x - child.model.loShift[0], y - child.model.loShift[1])
                    var activity = 0L
                    for (j in shifted.indices) child.model.forEachInColumn(j) { _, a -> activity += a * shifted[j] }
                    assertEquals(x + y >= 1L, activity <= child.model.rhs[0])
                    val objectiveValue = child.model.objConstant + child.objectiveConstant +
                        shifted.indices.sumOf { child.model.cost[it] * shifted[it] }
                    assertEquals(2 * x + y + 5, objectiveValue)
                }
            }
            assertEquals(1.0, result.objective)
            shared.popTo(0)
            val restored = engine.nodeRelaxation(relaxer, cp.session)
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
            val root = engine.nodeRelaxation(relaxer, native)
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
            assertEquals(3L, engine.nodeRelaxation(relaxer, session).model.loShift[0])
            session.popToLevel(0)
            session.pinIntAtMost(0, -2)
            val sibling = engine.nodeRelaxation(relaxer, session)
            assertEquals(-5L, sibling.model.loShift[0])
            assertEquals(3L, sibling.model.upper[0])
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
            val relaxation = engine.nodeRelaxation(relaxer, cp.session)
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
            engine.nodeRelaxation(relaxer, cp.session)
            assertTrue(engine.cutPool.cuts().isEmpty())
            assertEquals(1, engine.cutPool.size)
            shared.popTo(0)
            shared.push(SearchDecision.Bool(0))
            shared.push(SearchDecision.IntAtLeast(0, 1))
            engine.nodeRelaxation(relaxer, cp.session)
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
            val root = engine.nodeRelaxation(relaxer, cp.session)
            val auxiliary = root.colReq.indices.first { column ->
                root.colReq[column]?.toList() == listOf(0L, 2L, 1L, 2L)
            }
            assertEquals(1L, root.model.upper[auxiliary])
            shared.push(SearchDecision.IntEqual(2, 2))

            val child = engine.nodeRelaxation(relaxer, cp.session)

            assertEquals(0L, cp.session.intDomain(0).min)
            assertEquals(4L, cp.session.intDomain(0).max)
            assertTrue(!cp.session.intDomain(0).contains(2L))
            assertEquals(0L, child.model.upper[auxiliary])
            shared.popTo(0)
            assertEquals(1L, engine.nodeRelaxation(relaxer, cp.session).model.upper[auxiliary])
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
            val before = engine.nodeRelaxation(relaxer, cp.session)
            assertEquals(4.0, assertNotNull(engine.solveNode(before.model, null, Cancellation.Never)?.second).objective)
            cp.session.reseedFrom(Assumptions(ints = mapOf(0 to 1L)))
            cp.rebase()
            shared.resetRootFacts()
            shared.initialize()
            val after = engine.nodeRelaxation(relaxer, cp.session)
            assertEquals(1L, after.model.loShift[0])
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
            val live = engine.nodeRelaxation(relaxer, cp.session)
            val boolColumn = live.boolColOf[0]
            var row = -1
            live.model.forEachInColumn(boolColumn) { index, coefficient ->
                if (coefficient > 0) row = index
            }
            assertTrue(row >= 0)
            assertFalse(live.model.rowGlobal[row])
            val coefficients = LongArray(live.model.n)
            for (column in coefficients.indices) {
                live.model.forEachInColumn(column) { index, value ->
                    if (index == row) coefficients[column] = value
                }
            }
            val rhs = live.model.rhs[row] + coefficients.indices.sumOf { coefficients[it] * live.model.loShift[it] } -
                coefficients[boolColumn]
            assertEquals(1L, rhs)
            val premises = assertNotNull(live.model.rowPremises[row])
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
            val active = engine.nodeRelaxation(relaxer, cp.session)
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
            engine.nodeRelaxation(relaxer, cp.session)
            assertTrue(engine.cutPool.cuts().isEmpty())
            for (variable in 0..2) shared.push(SearchDecision.IntAtMost(variable, 1))
            shared.push(SearchDecision.Bool(1))
            engine.nodeRelaxation(relaxer, cp.session)
            assertTrue(engine.cutPool.cuts().isEmpty())
            shared.popTo(0)
            for (variable in 0..2) shared.push(SearchDecision.IntAtMost(variable, 1))
            shared.push(SearchDecision.Bool(0))
            engine.nodeRelaxation(relaxer, cp.session)
            assertEquals(1, engine.cutPool.cuts().size)
            assertTrue(engine.cutPool.exportGlobalCuts().isEmpty())
        }
    }
}
