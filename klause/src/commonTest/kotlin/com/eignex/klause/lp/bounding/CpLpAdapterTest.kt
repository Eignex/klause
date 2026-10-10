package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.global.AllDifferent
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
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.TableauCutProvenance
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.integerCertify
import com.eignex.klause.lp.engine.integerFarkasRay
import com.eignex.klause.lp.relaxation.LpExplanation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.cpCutSources
import com.eignex.klause.lp.relaxation.withCpBounds
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
            val ancestorValue = assertNotNull(
                engine.solveNode(ancestor.model, null, Cancellation.Never)?.second,
            ).objective
            shared.popTo(0)
            val root = assertNotNull(engine.nodeRelaxation(relaxer, cp.session))
            val rootValue = assertNotNull(engine.solveNode(root.model, null, Cancellation.Never)?.second).objective

            assertEquals(5.0, childValue)
            assertEquals(3.0, ancestorValue)
            assertEquals(0.0, rootValue)
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
