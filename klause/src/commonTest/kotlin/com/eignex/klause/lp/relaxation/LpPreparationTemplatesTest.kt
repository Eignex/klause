package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpPreparationTemplatesTest {
    private val source = Problem(1, 6, Array(6) { IntDomain(0, 10) }, arrayOf<Factor>(
        Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3),
        Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 3),
        Linear(intArrayOf(1, 1), intArrayOf(4, 5), LinearOp.GE, 3),
    ))
    private val selection = FactorProjectionProviders.Default.prepare(source)
    private val objective = LinearObjective(intCoefficients = LongArray(6) { 1L })

    @Test
    fun `compatible preparation should reuse row and bound objects across consumers`() {
        val first = CpToLpRelaxation(source, objective, projections = selection)
        first.build(RootDomains(source))
        val before = assertNotNull(first.preparationTemplates)
        val view = source.withFactors(source.factors.copyOf())
        val next = CpToLpRelaxation(view, objective, projections = selection.forEngineView(view))

        val reused = next.build(RootDomains(view)).model.authoritativeModel()
        val fresh = CpToLpRelaxation(view, objective, projections = null).build(RootDomains(view)).model.authoritativeModel()
        val after = assertNotNull(next.preparationTemplates)

        assertSame(before.factors[0].rows?.first(), after.factors[0].rows?.first())
        assertSame(before.bounds[0], after.bounds[0])
        assertEquals(3, after.reusedComponents)
        assertTrue(assertNotNull(reused).sameAuthority(assertNotNull(fresh)))
    }

    @Test
    fun `bound revisions should invalidate only their component metadata`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        val view = Problem(1, 6, Array(6) { IntDomain(if (it == 0) 1 else 0, 10) }, source.factors)

        val after = assertNotNull(selection.forEngineView(view).prepareLpTemplates(objective, Cancellation.Never))

        assertNotSame(before.bounds[0], after.bounds[0])
        assertNotSame(before.components[0], after.components[0])
        assertSame(before.components[1], after.components[1])
        assertSame(before.factors[0], after.factors[0])
    }

    @Test
    fun `objective revisions should invalidate only components with changed costs`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        val revised = LinearObjective(intCoefficients = longArrayOf(2, 1, 1, 1, 1, 1))

        val after = assertNotNull(selection.prepareLpTemplates(revised, Cancellation.Never))

        assertNotSame(before.components[0], after.components[0])
        assertSame(before.components[1], after.components[1])
        assertSame(before.factors[0], after.factors[0])
    }

    @Test
    fun `objective translation should invalidate component objective scopes`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))

        val after = assertNotNull(selection.prepareLpTemplates(objective.copy(constant = 7), Cancellation.Never))

        assertEquals(0, after.reusedComponents)
        assertSame(before.factors[0], after.factors[0])
    }

    @Test
    fun `rewritten rows should preserve unaffected component templates`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        val view = source.withFactors(arrayOf(
            Linear(intArrayOf(1, 2), intArrayOf(0, 1), LinearOp.GE, 4), source.factors[1], source.factors[2],
        ))

        val after = assertNotNull(selection.forEngineView(view).prepareLpTemplates(objective, Cancellation.Never))

        assertNotSame(before.factors[0], after.factors[0])
        assertSame(before.components[1], after.components[1])
    }

    @Test
    fun `component merging should retire the merged scopes and preserve unrelated templates`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        val view = source.withFactors(source.factors + Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 2))

        val after = assertNotNull(selection.forEngineView(view).prepareLpTemplates(objective, Cancellation.Never))

        assertEquals(2, after.components.size)
        assertEquals(1, after.reusedComponents)
        assertSame(before.components[2], after.components[1])
    }

    @Test
    fun `explicit namespace remaps should reject all prior templates`() {
        selection.prepareLpTemplates(objective, Cancellation.Never)
        val remap = VarRemap(intArrayOf(0), intArrayOf(1, 0, 2, 3, 4, 5))
        val view = source.withFactors(source.factors.map { it.remap(remap) }.toTypedArray())

        val after = assertNotNull(selection.projected(view, intArrayOf(0, 1, 2), remap)
            .prepareLpTemplates(objective, Cancellation.Never))

        assertEquals(0, after.reusedFactors)
        assertEquals(0, after.reusedBounds)
    }

    @Test
    fun `finite preparation should reject source scope templates`() {
        selection.prepareLpTemplates(objective, Cancellation.Never)
        val finite = source.bake(selection)

        val after = assertNotNull(finite.factorProjections?.prepareLpTemplates(objective, Cancellation.Never))

        assertEquals(0, after.reusedFactors)
        assertEquals(0, after.reusedBounds)
    }

    @Test
    fun `foreign projection selection should reject a matching source shape`() {
        val foreign = source.withFactors(source.factors.copyOf())

        assertFailsWith<IllegalArgumentException> { CpToLpRelaxation(foreign, objective, projections = selection) }
    }

    @Test
    fun `guarded templates should reconstruct rows from each current pin`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 8),
        ))
        val captured = FactorProjectionProviders.Default.prepare(problem)
        val relaxer = CpToLpRelaxation(problem, null, projections = captured)
        for (pin in listOf(null, true, false)) {
            val domains = object : RelaxationDomains {
                override fun intDomain(varId: Int): IntDomain = problem.finiteIntDomain(varId)
                override fun boolValue(varId: Int): Boolean? = pin
            }

            val reused = assertNotNull(relaxer.build(domains).model.authoritativeModel())
            val fresh = assertNotNull(CpToLpRelaxation(problem, null, projections = null)
                .build(domains).model.authoritativeModel())

            assertTrue(reused.sameAuthority(fresh))
        }
    }

    @Test
    fun `interrupted preparation should charge work and retain the last complete snapshot`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        var work = 0L
        val meter = WorkMeter { work += it }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 3L
            override fun workMeter(): WorkMeter = meter
        }

        val stopped = selection.prepareLpTemplates(objective, token)
        val after = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))

        assertNull(stopped)
        assertTrue(work >= 3L)
        assertSame(before.components[0], after.components[0])
    }
}
