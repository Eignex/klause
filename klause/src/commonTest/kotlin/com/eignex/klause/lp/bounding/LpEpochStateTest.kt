package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.VarStatus
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.RootDomains
import com.eignex.klause.lp.relaxation.withModel
import com.eignex.klause.propagation.PropagationSession
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpEpochStateTest {
    @Test
    fun `row hints preserve last nonzero coefficients for duplicate source keys`() {
        val problem = Problem(
            0,
            3,
            Array(3) { IntDomain(0, 5) },
            arrayOf(
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 7),
                Linear(intArrayOf(1, -1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 7),
            ),
        )
        val first = CpToLpRelaxation(problem, null).build(RootDomains(problem))
        val second = CpToLpRelaxation(problem, null).build(RootDomains(problem))
        val sources = assertNotNull(first.sourceMap)
        val mapping = CutSourceMap(sources.model, sources.epoch, List(3) { sources.column(0) })
        val previous = first.withModel(first.model, mapping)
        val next = second.withModel(second.model, mapping)
        intArrayOf(0, 0, 0, 1, 0, 1).copyInto(previous.model.csc.rowIdx)
        intArrayOf(1, 1, 1, 0, 1, 0).copyInto(next.model.csc.rowIdx)
        longArrayOf(5, 0, 8, 7, 3, 4).copyInto(previous.model.csc.colVal)
        longArrayOf(5, 0, 8, 7, 3, 4).copyInto(next.model.csc.colVal)
        val basis = Basis(
            intArrayOf(3, 4),
            arrayOf(
            VarStatus.AT_LOWER,
            VarStatus.AT_LOWER,
            VarStatus.AT_LOWER,
            VarStatus.BASIC,
            VarStatus.BASIC,
        )
        )

        val mapped = assertNotNull(LpEpochState.remapBasis(previous, next, basis))

        assertContentEquals(intArrayOf(4, 3), mapped.basicVars)
        assertContentEquals(intArrayOf(3, 4), basis.basicVars)
    }

    @Test
    fun `snapshot guards admit narrower domains and reject a replacement root`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 5)), emptyArray())
        val session = PropagationSession(problem)
        session.implyIntAtLeast(0, 2)
        session.implyBool(0, true)
        val root = assertNotNull(LpEpochRoot.capture(session))
        session.implyIntAtMost(0, 4)
        assertTrue(root.admits(session))
        assertTrue(root.changed(session))
        assertFalse(root.admits(PropagationSession(problem)))
    }

    @Test
    fun `a root with interior holes retains its membership scope`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 5).excludeValue(2)), emptyArray())
        val session = PropagationSession(problem)
        val root = assertNotNull(LpEpochRoot.capture(session))
        assertTrue(root.admits(session))
        session.implyIntAtMost(0, 4)
        assertTrue(root.admits(session))
    }

    @Test
    fun `row hints remap by source identity instead of row position`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 5) },
            arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 7),
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 2),
            ),
        )
        val previous = CpToLpRelaxation(problem, null).build(RootDomains(problem))
        val basis = Basis(
            intArrayOf(0, 3),
            arrayOf(VarStatus.BASIC, VarStatus.AT_UPPER, VarStatus.AT_LOWER, VarStatus.BASIC),
        )
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0, 5) }
        builder.addRow(intArrayOf(0, 1), longArrayOf(1, -1), Relation.LE, 2)
        builder.addRow(intArrayOf(0, 1), longArrayOf(1, 1), Relation.LE, 7)
        val next = previous.withModel(builder.build(Sense.MINIMIZE), previous.sourceMap)
        val mapped = assertNotNull(LpEpochState.remapBasis(previous, next, basis))
        assertContentEquals(intArrayOf(0, 2), mapped.basicVars)
        assertContentEquals(
            arrayOf(VarStatus.BASIC, VarStatus.AT_UPPER, VarStatus.BASIC, VarStatus.AT_LOWER),
            mapped.status,
        )
        val foreign = CpToLpRelaxation(Problem(0, 2, Array(2) { IntDomain(0, 5) }, problem.factors), null)
            .build(RootDomains(problem))
        assertNull(LpEpochState.remapBasis(previous, foreign, basis))
    }

    @Test
    fun `transformed tidy models retain a checked proof binding`() {
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 5)),
            arrayOf(Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 5)),
        )
        val result = CpToLpRelaxation(problem, null, tidy = RelaxationTidyConfig(enabled = true))
            .build(RootDomains(problem))
        val map = assertNotNull(result.tidyDerivation)
        assertTrue(map.validate())
        assertTrue(map.appliesTo(map.scope))
        assertTrue(map.bounds.isNotEmpty())
        assertTrue(LpEpochState.supports(result))
    }
}
