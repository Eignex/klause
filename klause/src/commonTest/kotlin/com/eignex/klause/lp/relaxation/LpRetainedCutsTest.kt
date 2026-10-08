package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProofFact
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpLayoutRemap
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpRetainedCutsTest {
    @Test
    fun `column compaction remaps cut geometry for repeated loads and ancestor restoration`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(2, 6)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 3)))
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
        val base = LpRelaxation(model, variables, kinds, 0L, intArrayOf(1), intArrayOf(),
            sourceMap = cpCutSources(model, problem, variables, kinds, reals, signs, emptyMap()))
        val cuts = LpRetainedCuts()
        LpScopedSolver(LpExactState(assertNotNull(model.authoritativeModel()))).use { owner ->
            val root = assertNotNull(cuts.prepare(owner.state, base,
                listOf(Cut(intArrayOf(1), longArrayOf(1), Relation.GE, 3L, global = true))))
            assertTrue(owner.replaceRows(root.retired, emptyList(), root.rows, false))
            root.commit()
            val rootProof = cuts.parentRows(owner.state).values.single()
            assertTrue(owner.push())
            session.pinIntAtLeast(0, 4L)
            assertTrue(owner.assertBound(1, false, ExactLpSide(ExactLpNumber.of(2L)), 7L))
            val point = assertNotNull(owner.state.ownerWorkingModel())
            val map = assertNotNull(base.sourceMap).withCpBounds(point, session)
            val fact = CutPremise.Bound(CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, 0) to BigFraction.ONE)),
                false, BigFraction.ofLong(4L))
            val local = Cut(intArrayOf(1), longArrayOf(1), Relation.GE, 4L,
                provenance = CutProvenance(map.model, map.epoch, listOf(CutProofFact(fact, false))))
            val child = assertNotNull(cuts.prepare(owner.state, base.withModel(point, map), listOf(local)))
            assertTrue(owner.replaceRows(child.retired, emptyList(), child.rows, true))
            child.commit()
            val remap = LpLayoutRemap(2, owner.state.rows, listOf(1))
            val compaction = assertNotNull(cuts.prepareCompaction(owner.state, remap))

            assertTrue(owner.compact(remap))
            compaction.commit()

            val compacted = assertNotNull(owner.state.ownerWorkingModel())
            val remapped = LpRelaxation(compacted, intArrayOf(0), BooleanArray(1), 0L, intArrayOf(0), intArrayOf(),
                sourceMap = cpCutSources(compacted, problem, intArrayOf(0), BooleanArray(1), intArrayOf(-1),
                    intArrayOf(1), cuts.parentRows(owner.state)).withCpBounds(compacted, session))
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
            val restored = remapped.withModel(assertNotNull(owner.state.ownerWorkingModel()))
            val repeatRoot = assertNotNull(cuts.prepare(owner.state, restored))
            assertTrue(repeatRoot.rows.isEmpty() && repeatRoot.retired.isEmpty())
        }
    }

    @Test
    fun `selected cuts use exact origins and repeated loads retain their rows`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(2, 6) },
            arrayOf(AllDifferent(intArrayOf(0, 1), domainMin = 2, domainSize = 5)),
        )
        val session = PropagationSession(problem)
        val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1, 1))).build(session)
        val cuts = LpRetainedCuts()
        LpScopedSolver(LpExactState(assertNotNull(base.model.authoritativeModel()))).use { owner ->
            val cut = Cut(base.intColOf, longArrayOf(1, 1), Relation.GE, 5, global = true)
            val edit = assertNotNull(cuts.prepare(owner.state, base, listOf(cut)))

            assertTrue(owner.replaceRows(edit.retired, emptyList(), edit.rows, false))
            edit.commit()

            val row = edit.rows.single()
            assertEquals(BigFraction.MINUS_ONE, row.rhs.value)
            assertEquals(BigFraction.ofLong(5), assertNotNull(owner.solve()).lowerBound)
            val owners = owner.metrics.createdOwners
            val model = assertNotNull(owner.state.ownerWorkingModel())
            val map = assertNotNull(base.sourceMap).withParentRows(cuts.parentRows(owner.state))
            val rebound = base.withModel(model, map)
            assertEquals(model.m, rebound.rowFactorIds.size)
            assertEquals(-1, rebound.rowFactorIds.last())
            val repeated = assertNotNull(cuts.prepare(owner.state, rebound, listOf(cut)))
            assertTrue(repeated.rows.isEmpty() && repeated.retired.isEmpty())
            repeated.commit()
            assertEquals(row.metadata.global, cuts.parentRows(owner.state).values.single().global)
            assertEquals(owners, owner.metrics.createdOwners)
        }
    }

    @Test
    fun `nested selections restore ancestor rows and their complete proof`() {
        val problem = Problem(
            0, 1, arrayOf(IntDomain(2, 6)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 3)),
        )
        val session = PropagationSession(problem)
        val base = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1))).build(session)
        val cuts = LpRetainedCuts()
        LpScopedSolver(LpExactState(assertNotNull(base.model.authoritativeModel()))).use { owner ->
            val root = assertNotNull(cuts.prepare(
                owner.state, base, listOf(Cut(base.intColOf, longArrayOf(1), Relation.GE, 3, global = true)),
            ))
            assertTrue(owner.replaceRows(root.retired, emptyList(), root.rows, false))
            root.commit()
            val rootProof = cuts.parentRows(owner.state).values.single()
            assertTrue(owner.push())
            session.implyIntAtLeast(0, 4)
            val model = assertNotNull(owner.state.ownerWorkingModel())
            val map = assertNotNull(base.sourceMap).withCpBounds(model, session)
            val premise = CutPremise.Bound(
                CutExpression(mapOf(CutSource(CutSourceKind.INTEGER, 0) to BigFraction.ONE)),
                false, BigFraction.ofLong(4),
            )
            val proof = CutProvenance(map.model, map.epoch, listOf(CutProofFact(premise, false)))
            val local = Cut(base.intColOf, longArrayOf(1), Relation.GE, 4, provenance = proof)
            val current = base.withModel(assertNotNull(owner.state.ownerWorkingModel()), map)
            val child = assertNotNull(cuts.prepare(owner.state, current, listOf(local)))
            assertTrue(owner.replaceRows(child.retired, emptyList(), child.rows, true))
            child.commit()
            assertEquals(BigFraction.ofLong(4), assertNotNull(owner.solve()).lowerBound)
            assertTrue(cuts.parentRows(owner.state).values.single().facts.contains(CutProofFact(premise, false)))
            assertTrue(owner.push())
            val deeper = assertNotNull(cuts.prepare(owner.state, current, emptyList()))
            assertTrue(owner.replaceRows(deeper.retired, emptyList(), deeper.rows, true))
            deeper.commit()
            val owners = owner.metrics.createdOwners

            assertTrue(owner.pop(1))
            cuts.retract(1)
            assertEquals(BigFraction.ofLong(4), assertNotNull(owner.solve()).lowerBound)
            assertTrue(owner.pop(0))
            cuts.retract(0)

            assertEquals(BigFraction.ofLong(3), assertNotNull(owner.solve()).lowerBound)
            assertSame(rootProof, cuts.parentRows(owner.state).values.single())
            assertEquals(owners, owner.metrics.createdOwners)
            assertEquals(0, owner.lastMetrics.initialRefactorizations)
        }
    }

    @Test
    fun `root guard weakening retires a cut without rebuilding the numerical owner`() {
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
                false, BigFraction.ofLong(4),
            )
            val cut = Cut(
                base.intColOf, longArrayOf(1), Relation.GE, 4,
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
