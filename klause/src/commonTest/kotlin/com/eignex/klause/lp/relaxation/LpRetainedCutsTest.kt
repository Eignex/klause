package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.cut.SourceCut
import com.eignex.klause.lp.cut.orNull
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutAuxiliaryDefinition
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.FloatDeductionCertificate
import com.eignex.klause.lp.engine.LpBoundSlack
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpLayoutRemap
import com.eignex.klause.lp.engine.LpRowSlack
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpRetainedCutsTest {
    @Test
    fun `retained cuts without displacement should not exclude a tolerance feasible objective`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) }, listOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.LE, 1),
        ), semantics = SourceSemantics(SourceFormat.MPS,
            feasibilityTolerance = SourceTolerance(SemanticRational.ONE)).resolve())
        val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(-1, -1, -1)))
            .build(RootDomains(problem))
        val candidate = Cut(base.intColOf, longArrayOf(1, 1, 1), Relation.LE, 1L, global = true)
        val cuts = LpRetainedCuts()
        LpScopedSolver(LpExactState(assertNotNull(base.model.authoritativeModel()))).use { owner ->
            val edit = assertNotNull(cuts.prepare(owner.state, base, listOf(candidate)))
            assertTrue(owner.replaceRows(edit.retired, emptyList(), edit.rows, false))
            edit.commit()
            val current = assertNotNull(owner.state.ownerWorkingModel())

            val certificate = RevisedSimplex(current).use { solver ->
                val result = assertNotNull(solver.solve())
                FloatDeductionCertificate.of(current, result.duals, slack = LpBoundSlack(1.0, 0.0))
            }

            assertTrue(certificate == null || certificate.lowerBound <= -3.0)
        }
    }

    @Test
    fun `scoped cut pricing should restore ancestor premise displacement on pop`() {
        val model = LpBuilder().apply { addVar(0L, 10L, cost = -1L) }.build(Sense.MINIMIZE)
        val source = CutSource(CutSourceKind.INTEGER, 0)
        val map = CutSourceMap(Any(), 0, listOf(CutColumnSource(source)))
        val base = LpRelaxation(model, intArrayOf(0), BooleanArray(1), 0L, intArrayOf(0), intArrayOf(), sourceMap = map)
        val cuts = LpRetainedCuts()
        LpScopedSolver(LpExactState(assertNotNull(model.authoritativeModel()))).use { owner ->
            for ((rhs, weight) in listOf(1L to 3.0, 0L to 5.0)) {
                if (rhs == 0L) assertTrue(owner.push())
                val candidate = Cut(intArrayOf(0), longArrayOf(1L), Relation.LE, rhs, global = true,
                    slack = LpRowSlack(doubleArrayOf(weight), doubleArrayOf(0.0)))
                val current = base.withModel(assertNotNull(owner.state.ownerWorkingModel()), map)
                val edit = assertNotNull(cuts.prepare(owner.state, current, listOf(candidate)))
                assertTrue(owner.replaceRows(edit.retired, emptyList(), edit.rows, rhs == 0L))
                edit.commit()
            }
            for ((depth, expected) in listOf(1 to -5.0, 0 to -4.0)) {
                assertTrue(owner.pop(depth))
                cuts.retract(depth)
                val current = assertNotNull(owner.state.ownerWorkingModel())
                val bound = RevisedSimplex(current).use { solver ->
                    val result = assertNotNull(solver.solve())
                    assertNotNull(FloatDeductionCertificate.of(current, result.duals,
                        slack = LpBoundSlack(1.0, 0.0))).lowerBound
                }

                assertTrue(bound <= expected && bound > expected - 1e-4, "bound $bound should cover $expected")
            }
        }
    }

    @Test
    fun `equivalent auxiliary coordinates should refresh retained proofs without replacing their rows`() {
        val problem = Problem(0, 0, emptyArray(), emptyArray())
        val old = CutSource(CutSourceKind.AUXILIARY, 0)
        val next = CutSource(CutSourceKind.AUXILIARY, 1)
        val definition = CutAuxiliaryDefinition(listOf(1L), emptyList(), 1L, true)
        val original = CutSourceMap(
            problem,
            0L,
            listOf(CutColumnSource(old)),
            auxiliaryDefinitions = mapOf(old to definition),
        )
        val current = CutSourceMap(
            problem,
            1L,
            listOf(CutColumnSource(next)),
            auxiliaryDefinitions = mapOf(next to definition),
        )
        val model = LpBuilder().apply { addVar(0L, 1L) }.build(Sense.MINIMIZE)
        val base = LpRelaxation(
            model,
            intArrayOf(-1),
            BooleanArray(1),
            0L,
            intArrayOf(),
            intArrayOf(),
            sourceMap = original,
        )
        val source = SourceCut(
            CutExpression(mapOf(old to BigFraction.ONE)),
            Relation.LE,
            BigFraction.ONE,
            CutProvenance(problem, 0L, emptyList(), auxiliaryDefinitions = mapOf(old to definition)),
        )
        val cuts = LpRetainedCuts()
        LpScopedSolver(LpExactState(assertNotNull(model.authoritativeModel()))).use { owner ->
            val initial = assertNotNull(
                cuts.prepare(
                    owner.state,
                    base,
                    listOf(assertNotNull(source.toCut(original).orNull())),
                ),
            )
            assertTrue(owner.replaceRows(initial.retired, emptyList(), initial.rows, false))
            initial.commit()
            val before = owner.state
            val id = before.rows.row(0).id
            val edit = assertNotNull(
                cuts.prepare(
                    before,
                    base.withModel(assertNotNull(before.ownerWorkingModel()), current),
                ),
            )
            assertTrue(edit.rows.isEmpty() && edit.retired.isEmpty())

            edit.commit()

            val proof = cuts.parentRows(owner.state).values.single()
            assertEquals(mapOf(next to definition), proof.auxiliaryDefinitions)
            assertEquals(
                CutPremise.Row(CutExpression(mapOf(next to BigFraction.ONE)), Relation.LE, BigFraction.ONE),
                proof.conclusion,
            )
            assertSame(before, owner.state)
            assertEquals(id, owner.state.rows.row(0).id)
        }
    }

    @Test
    fun `column compaction should remap cut geometry for repeated loads and ancestor restoration`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(2, 6)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 3)),
        )
        val session = PropagationSession(problem)
        val model = LpBuilder().apply {
            addVar(0L, 1L)
            val x = addVar(2L, 6L, cost = 1L)
            addRow(intArrayOf(x), longArrayOf(1L), Relation.GE, 3L)
        }.build(Sense.MINIMIZE)
        val variables = intArrayOf(-1, 0)
        val kinds = BooleanArray(2)
        val reals = intArrayOf(-1, -1)
        val signs = intArrayOf(1, 1)
        val base = LpRelaxation(
            model,
            variables,
            kinds,
            0L,
            intArrayOf(1),
            intArrayOf(),
            sourceMap = cpCutSources(model, problem, variables, kinds, reals, signs, emptyMap()),
        )
        val cuts = LpRetainedCuts()
        LpScopedSolver(LpExactState(assertNotNull(model.authoritativeModel()))).use { owner ->
            val root = assertNotNull(
                cuts.prepare(
                    owner.state,
                    base,
                    listOf(Cut(intArrayOf(1), longArrayOf(1), Relation.GE, 3L, global = true)),
                ),
            )
            assertTrue(owner.replaceRows(root.retired, emptyList(), root.rows, false))
            root.commit()
            val rootProof = cuts.parentRows(owner.state).values.single()
            assertTrue(owner.push())
            session.pinIntAtLeast(0, 4L)
            assertTrue(owner.assertBound(1, false, ExactLpSide(ExactLpNumber.of(2L)), 7L))
            val point = assertNotNull(owner.state.ownerWorkingModel())
            val map = assertNotNull(base.sourceMap).withCpBounds(point, session)
            val fact = CutPremise.Bound(
                CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, 0) to BigFraction.ONE)),
                false,
                BigFraction.ofLong(4L),
            )
            val local = Cut(
                intArrayOf(1),
                longArrayOf(1),
                Relation.GE,
                4L,
                provenance = CutProvenance(map.model, map.epoch, listOf(CutProofFact(fact, false))),
            )
            val child = assertNotNull(cuts.prepare(owner.state, base.withModel(point, map), listOf(local)))
            assertTrue(owner.replaceRows(child.retired, emptyList(), child.rows, true))
            child.commit()
            val remap = LpLayoutRemap(2, owner.state.rows, listOf(1))
            val compaction = assertNotNull(cuts.prepareCompaction(owner.state, remap))

            assertTrue(owner.compact(remap))
            compaction.commit()

            val compacted = assertNotNull(owner.state.ownerWorkingModel())
            val remapped = LpRelaxation(
                compacted,
                intArrayOf(0),
                BooleanArray(1),
                0L,
                intArrayOf(0),
                intArrayOf(),
                sourceMap = cpCutSources(
                    compacted,
                    problem,
                    intArrayOf(0),
                    BooleanArray(1),
                    intArrayOf(-1),
                    intArrayOf(1),
                    cuts.parentRows(owner.state),
                ).withCpBounds(compacted, session),
            )
            val repeated = assertNotNull(cuts.prepare(owner.state, remapped))
            assertTrue(repeated.rows.isEmpty() && repeated.retired.isEmpty())
            repeated.commit()
            assertEquals(BigFraction.ofLong(4L), assertNotNull(owner.solve()).lowerBound)
            assertEquals(7L, owner.state.activeSide(0, false)?.witness)
            assertTrue(owner.pop(0))
            cuts.retract(0)
            session.popToLevel(0)
            assertSame(rootProof, cuts.parentRows(owner.state).values.single())
            assertEquals(BigFraction.ofLong(3L), assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `nested selections should restore ancestor rows and their complete proof`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(2, 6)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 3)),
        )
        val session = PropagationSession(problem)
        val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1))).build(session)
        val cuts = LpRetainedCuts()
        LpScopedSolver(LpExactState(assertNotNull(base.model.authoritativeModel()))).use { owner ->
            val root = assertNotNull(
                cuts.prepare(
                    owner.state,
                    base,
                    listOf(Cut(base.intColOf, longArrayOf(1), Relation.GE, 3, global = true)),
                ),
            )
            assertTrue(owner.replaceRows(root.retired, emptyList(), root.rows, false))
            root.commit()
            val rootProof = cuts.parentRows(owner.state).values.single()
            val rootStorage = cuts.storageUnits
            assertTrue(owner.push())
            session.implyIntAtLeast(0, 4)
            val model = assertNotNull(owner.state.ownerWorkingModel())
            val map = assertNotNull(base.sourceMap).withCpBounds(model, session)
            val premise = CutPremise.Bound(
                CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, 0) to BigFraction.ONE)),
                false,
                BigFraction.ofLong(4),
            )
            val proof = CutProvenance(map.model, map.epoch, listOf(CutProofFact(premise, false)))
            val local = Cut(base.intColOf, longArrayOf(1), Relation.GE, 4, provenance = proof)
            val current = base.withModel(assertNotNull(owner.state.ownerWorkingModel()), map)
            val child = assertNotNull(cuts.prepare(owner.state, current, listOf(local)))
            assertTrue(owner.replaceRows(child.retired, emptyList(), child.rows, true))
            child.commit()
            val childStorage = cuts.storageUnits
            assertTrue(owner.push())
            val deeper = assertNotNull(cuts.prepare(owner.state, current, emptyList()))
            assertTrue(owner.replaceRows(deeper.retired, emptyList(), deeper.rows, true))
            deeper.commit()
            val owners = owner.metrics.createdOwners

            assertTrue(owner.pop(1))
            cuts.retract(1)
            assertEquals(childStorage, cuts.storageUnits)
            assertEquals(BigFraction.ofLong(4), assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.pop(0))
            cuts.retract(0)
            assertEquals(rootStorage, cuts.storageUnits)

            assertEquals(BigFraction.ofLong(3), assertNotNull(owner.solve()).lowerBound)
            assertSame(rootProof, cuts.parentRows(owner.state).values.single())
            assertEquals(owners, owner.metrics.createdOwners)
            assertEquals(0, owner.lastMetrics.initialRefactorizations)
        }
    }

    @Test
    fun `root guard weakening should retire a cut without rebuilding the numerical owner`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(2, 6)), emptyArray())
        val session = PropagationSession(problem)
        session.implyIntAtLeast(0, 4)
        val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1)))
            .build(PropagationSession(problem))
        val cuts = LpRetainedCuts()
        LpScopedSolver(LpExactState(assertNotNull(base.model.authoritativeModel()))).use { owner ->
            val map = assertNotNull(base.sourceMap).withCpBounds(base.model, session)
            val premise = CutPremise.Bound(
                CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, 0) to BigFraction.ONE)),
                false,
                BigFraction.ofLong(4),
            )
            val cut = Cut(
                base.intColOf,
                longArrayOf(1),
                Relation.GE,
                4,
                provenance = CutProvenance(map.model, map.epoch, listOf(CutProofFact(premise, false))),
            )
            val first = assertNotNull(cuts.prepare(owner.state, base.withModel(base.model, map), listOf(cut)))
            assertTrue(owner.replaceRows(first.retired, emptyList(), first.rows, false))
            first.commit()
            assertEquals(BigFraction.ofLong(4), assertNotNull(owner.solve()).lowerBound)
            val before = owner.metrics.createdOwners
            val model = assertNotNull(owner.state.ownerWorkingModel())
            val weakened = base.withModel(model, map.withCpBounds(model, PropagationSession(problem)))
            val edit = assertNotNull(cuts.prepare(owner.state, weakened))

            assertTrue(owner.replaceRows(edit.retired, emptyList(), edit.rows, false))
            edit.commit()

            assertFalse(owner.state.rows.row(owner.state.rows.index(first.rows.single().id)).active)
            assertTrue(cuts.parentRows(owner.state).isEmpty())
            assertEquals(BigFraction.ofLong(2), assertNotNull(owner.solve()).lowerBound)
            assertEquals(before, owner.metrics.createdOwners)
        }
    }
}
