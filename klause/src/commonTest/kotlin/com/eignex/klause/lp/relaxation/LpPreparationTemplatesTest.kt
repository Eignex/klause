package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
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
    fun `objective metadata refresh should retain row and bound indexes and match fresh authority`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        val revised = objective.copy(intCoefficients = longArrayOf(2, 1, 1, 1, 1, 1))
        val consumer = CpToLpRelaxation(source, revised, projections = selection, reuseTemplateMetadata = true)

        val reused = assertNotNull(consumer.build(RootDomains(source)).model.authoritativeModel())
        val after = assertNotNull(consumer.preparationTemplates)
        val fresh = assertNotNull(CpToLpRelaxation(source, revised, projections = null)
            .build(RootDomains(source)).model.authoritativeModel())

        assertSame(before.factors, after.factors)
        assertSame(before.bounds, after.bounds)
        assertSame(before.components, after.components)
        assertSame(before.components[1], after.components[1])
        assertTrue(reused.sameAuthority(fresh))
    }

    @Test
    fun `bound metadata refresh should retain unrelated component scopes and match fresh authority`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        val view = Problem(1, 6, Array(6) { IntDomain(if (it == 0) 1 else 0, 10) }, source.factors)
        val consumer = CpToLpRelaxation(view, objective, projections = selection.forEngineView(view),
            reuseTemplateMetadata = true)

        val reused = assertNotNull(consumer.build(RootDomains(view)).model.authoritativeModel())
        val after = assertNotNull(consumer.preparationTemplates)
        val fresh = assertNotNull(CpToLpRelaxation(view, objective, projections = null)
            .build(RootDomains(view)).model.authoritativeModel())

        assertSame(before.factors, after.factors)
        assertSame(before.bounds[1], after.bounds[1])
        assertNotSame(before.bounds[0], after.bounds[0])
        assertNotSame(before.components[0], after.components[0])
        assertSame(before.components[1], after.components[1])
        assertTrue(reused.sameAuthority(fresh))
    }

    @Test
    fun `interrupted metadata refresh should preserve the last complete snapshot`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        val revised = objective.copy(intCoefficients = longArrayOf(2, 1, 1, 1, 1, 1))
        val view = Problem(1, 6, Array(6) { IntDomain(if (it == 0) 1 else 0, 10) }, source.factors)
        var work = 0L
        val meter = WorkMeter { work += it }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 12L
            override fun workMeter(): WorkMeter = meter
        }

        val stopped = selection.forEngineView(view).prepareLpTemplates(revised, token, reuseMetadata = true)
        val after = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never, reuseMetadata = true))

        assertNull(stopped)
        assertTrue(work >= 12L)
        assertSame(before.factors, after.factors)
        assertSame(before.bounds, after.bounds)
        assertSame(before.components, after.components)
    }

    @Test
    fun `metadata refresh should read objective translations outside source templates`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        val revised = objective.copy(constant = 7L)
        val consumer = CpToLpRelaxation(source, revised, projections = selection, reuseTemplateMetadata = true)

        val reused = assertNotNull(consumer.build(RootDomains(source)).model.authoritativeModel())
        val after = assertNotNull(consumer.preparationTemplates)
        val fresh = assertNotNull(CpToLpRelaxation(source, revised, projections = null)
            .build(RootDomains(source)).model.authoritativeModel())

        assertSame(before.components, after.components)
        assertSame(before.factors, after.factors)
        assertSame(before.bounds, after.bounds)
        assertTrue(reused.sameAuthority(fresh))
    }

    @Test
    fun `metadata refresh should recheck bounds in a shared finite view`() {
        val finite = source.bake(selection)
        val captured = assertNotNull(finite.factorProjections)
        val before = assertNotNull(captured.prepareLpTemplates(objective, Cancellation.Never))
        finite.rootIntDomainsInPlace[0] = IntDomain(1, 10)
        val consumer = CpToLpRelaxation(finite, objective, projections = captured, reuseTemplateMetadata = true)

        val reused = assertNotNull(consumer.build(RootDomains(finite)).model.authoritativeModel())
        val after = assertNotNull(consumer.preparationTemplates)
        val fresh = assertNotNull(CpToLpRelaxation(finite, objective, projections = null)
            .build(RootDomains(finite)).model.authoritativeModel())

        assertNotSame(before.bounds[0], after.bounds[0])
        assertSame(before.components[1], after.components[1])
        assertTrue(reused.sameAuthority(fresh))
    }

    @Test
    fun `unchanged consumers should reuse completed indexes with less preparation work`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        var reusedWork = 0L
        val view = source.withFactors(source.factors.copyOf())
        val captured = selection.forEngineView(view)

        val after = assertNotNull(captured.prepareLpTemplates(objective.copy(), Cancellation.Never) {
            reusedWork = it
        })
        var rebuiltWork = 0L
        captured.prepareLpTemplates(objective, Cancellation.Never, reuseIndexes = false) { rebuiltWork = it }

        assertTrue(after.reusedIndexes)
        assertSame(before.factors, after.factors)
        assertSame(before.bounds, after.bounds)
        assertSame(before.components, after.components)
        assertTrue(reusedWork < rebuiltWork)
    }

    @Test
    fun `changed late Boolean cost should retain source indexes and match fresh assembly`() {
        val problem = Problem(7, 0, emptyArray(), arrayOf<Factor>(
            Clause(intArrayOf(12, 0, 8)),
        ))
        val captured = FactorProjectionProviders.Default.prepare(problem)
        val original = LinearObjective(boolWeights = LongArray(7) { 1L })
        captured.prepareLpTemplates(original, Cancellation.Never)
        val changed = original.copy(boolWeights = LongArray(7) { if (it == 4) 3L else 1L })
        val consumer = CpToLpRelaxation(problem, changed, projections = captured, reuseTemplateMetadata = true)

        val reused = consumer.build(RootDomains(problem)).model.authoritativeModel()
        val fresh = CpToLpRelaxation(problem, changed, projections = null)
            .build(RootDomains(problem)).model.authoritativeModel()

        assertTrue(assertNotNull(consumer.preparationTemplates).reusedIndexes)
        assertTrue(assertNotNull(reused).sameAuthority(assertNotNull(fresh)))
    }

    @Test
    fun `compatible preparation should reuse row and bound objects across consumers`() {
        val first = CpToLpRelaxation(source, objective, projections = selection)
        val view = source.withFactors(source.factors.copyOf())
        val next = CpToLpRelaxation(view, objective, projections = selection.forEngineView(view))
        first.build(RootDomains(source))
        val before = assertNotNull(first.preparationTemplates)

        val reused = next.build(RootDomains(view)).model.authoritativeModel()
        val fresh = CpToLpRelaxation(view, objective, projections = selection.forEngineView(view),
            reuseSourceTemplates = false).build(RootDomains(view)).model.authoritativeModel()
        val after = assertNotNull(next.preparationTemplates)

        assertSame(before.factors[0].rows?.first(), after.factors[0].rows?.first())
        assertSame(before.bounds[0], after.bounds[0])
        assertTrue(after.reboundSource)
        assertEquals(3, after.reusedComponents)
        assertTrue(assertNotNull(reused).sameAuthority(assertNotNull(fresh)))
    }

    @Test
    fun `separate retained consumers should install the same immutable exact bound objects`() {
        val first = CpToLpRelaxation(source, objective, projections = selection)
        val before = assertNotNull(LpRetainedSources(source, first)
            .prepare(LpExactState(LpRetainedSources.emptyModel()), RootDomains(source)))
        val second = CpToLpRelaxation(source, objective, projections = selection)

        val after = assertNotNull(LpRetainedSources(source, second)
            .prepare(LpExactState(LpRetainedSources.emptyModel()), RootDomains(source)))

        assertSame(before.columns.first().column.bounds, after.columns.first().column.bounds)
        assertSame(assertNotNull(first.preparationTemplates).bounds.getValue(0).column.bounds,
            after.columns.first().column.bounds)
    }

    @Test
    fun `fixed layout consumers should adopt exact bound templates without changing authority`() {
        val relaxation = CpToLpRelaxation(source, objective, projections = selection).build(RootDomains(source))
        var reused = 0

        val exact = assertNotNull(relaxation.authoritativeWithTemplates(Cancellation.Never) { reused = it })

        assertTrue(relaxation.persistentEligible)
        assertEquals(6, reused)
        assertSame(assertNotNull(relaxation.preparationTemplates).bounds.getValue(0).column,
            exact.column(relaxation.intColOf[0]))
        assertTrue(exact.sameAuthority(assertNotNull(relaxation.model.authoritativeModel())))
    }

    @Test
    fun `exact source rows should retain reusable compact emission templates`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 10) }, arrayOf<Factor>(
            ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
                listOf(BigFraction.ONE, BigFraction.ONE), LinearOp.GE, BigFraction.ofLong(3)),
        ))
        val captured = FactorProjectionProviders.Default.prepare(problem)
        val first = CpToLpRelaxation(problem, null, projections = captured)
        first.build(RootDomains(problem))
        val view = problem.withFactors(problem.factors.copyOf())
        val next = CpToLpRelaxation(view, null, projections = captured.forEngineView(view))

        val reused = assertNotNull(next.build(RootDomains(view)).model.authoritativeModel())
        val fresh = assertNotNull(CpToLpRelaxation(view, null, projections = null)
            .build(RootDomains(view)).model.authoritativeModel())

        assertSame(assertNotNull(assertNotNull(first.preparationTemplates).factors[0].rows).single(),
            assertNotNull(assertNotNull(next.preparationTemplates).factors[0].rows).single())
        assertTrue(reused.sameAuthority(fresh))
        assertEquals(1, reused.m)
    }

    @Test
    fun `wide exact source rows should preserve the ordinary exact projection`() {
        val coefficient = BigFraction.ofLong(9_007_199_254_740_993L)
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 10) }, arrayOf<Factor>(
            ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofIntVar(1)),
                listOf(coefficient, BigFraction.ONE), LinearOp.LE, coefficient),
        ))
        val captured = FactorProjectionProviders.Default.prepare(problem)

        val reused = assertNotNull(CpToLpRelaxation(problem, null, projections = captured)
            .build(RootDomains(problem)).model.authoritativeModel())
        val fresh = assertNotNull(CpToLpRelaxation(problem, null, projections = null)
            .build(RootDomains(problem)).model.authoritativeModel())

        assertTrue(reused.sameAuthority(fresh))
        assertEquals(1, reused.m)
    }

    @Test
    fun `bound revisions should invalidate only their component metadata`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        val view = Problem(1, 6, Array(6) { IntDomain(if (it == 0) 1 else 0, 10) }, source.factors)

        val after = assertNotNull(selection.forEngineView(view).prepareLpTemplates(objective, Cancellation.Never))

        assertNotSame(before.bounds[0], after.bounds[0])
        assertTrue(!after.reusedIndexes)
        assertNotSame(before.components[0], after.components[0])
        assertSame(before.components[1], after.components[1])
        assertSame(before.factors[0], after.factors[0])
    }

    @Test
    fun `changed stated upper side should invalidate indexes with equal finite endpoints`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        val view = Problem(1, 6, Array(6) { IntDomain(0, 10) }, source.factors,
            openIntHi = BooleanArray(6) { it == 5 })

        val after = assertNotNull(selection.forEngineView(view).prepareLpTemplates(objective, Cancellation.Never))

        assertTrue(!after.reusedIndexes)
        assertNotSame(before.bounds[5], after.bounds[5])
        assertSame(before.components[0], after.components[0])
    }

    @Test
    fun `objective revisions should invalidate only components with changed costs`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        val revised = LinearObjective(intCoefficients = longArrayOf(2, 1, 1, 1, 1, 1))

        val after = assertNotNull(selection.prepareLpTemplates(revised, Cancellation.Never))

        assertNotSame(before.components[0], after.components[0])
        assertTrue(!after.reusedIndexes)
        assertSame(before.components[1], after.components[1])
        assertSame(before.factors[0], after.factors[0])
    }

    @Test
    fun `objective translation should invalidate component objective scopes`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))

        val after = assertNotNull(selection.prepareLpTemplates(objective.copy(constant = 7), Cancellation.Never))

        assertEquals(0, after.reusedComponents)
        assertTrue(!after.reusedIndexes)
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
            .prepareLpTemplates(objective, Cancellation.Never, reuseMetadata = true))

        assertEquals(0, after.reusedFactors)
        assertEquals(0, after.reusedBounds)
    }

    @Test
    fun `finite preparation should reject source scope templates`() {
        selection.prepareLpTemplates(objective, Cancellation.Never)
        val finite = source.bake(selection)

        val after = assertNotNull(finite.factorProjections?.prepareLpTemplates(objective, Cancellation.Never,
            reuseMetadata = true))

        assertEquals(0, after.reusedFactors)
        assertEquals(0, after.reusedBounds)
    }

    @Test
    fun `temporary source preparation should retain the completed finite snapshot`() {
        val first = source.bake(selection)
        val before = assertNotNull(first.factorProjections?.prepareLpTemplates(objective, Cancellation.Never))
        selection.prepareLpTemplates(objective, Cancellation.Never)
        val next = source.bake(selection)

        val after = assertNotNull(next.factorProjections?.prepareLpTemplates(objective, Cancellation.Never))

        assertSame(before.factors[0], after.factors[0])
        assertSame(before.bounds[0], after.bounds[0])
        assertTrue(after.reboundSource)
    }

    @Test
    fun `foreign projection selection should reject a matching source shape`() {
        val foreign = source.withFactors(source.factors.copyOf())

        assertFailsWith<IllegalArgumentException> { CpToLpRelaxation(foreign, objective, projections = selection) }
    }

    @Test
    fun `affine row replacement should match fresh authority and retain unrelated rows`() {
        val first = CpToLpRelaxation(source, objective, projections = selection)
        first.build(RootDomains(source))
        val before = assertNotNull(first.preparationTemplates)
        val replacement = Linear(intArrayOf(2), intArrayOf(1), LinearOp.GE, 1)
        val view = source.withFactors(arrayOf(replacement, source.factors[1], source.factors[2]))
        val changed = CpToLpRelaxation(view, objective, projections = selection.forEngineView(view))

        val reused = assertNotNull(changed.build(RootDomains(view)).model.authoritativeModel())
        val fresh = assertNotNull(CpToLpRelaxation(view, objective, projections = null)
            .build(RootDomains(view)).model.authoritativeModel())

        assertTrue(reused.sameAuthority(fresh))
        assertNotSame(before.factors[0], assertNotNull(changed.preparationTemplates).factors[0])
        assertSame(before.components[1], assertNotNull(changed.preparationTemplates).components[1])
    }

    @Test
    fun `bound metadata should retain integers beyond floating precision`() {
        val lower = 9_007_199_254_740_993L
        val problem = Problem(0, 1, arrayOf(IntDomain(lower, lower + 2)), arrayOf<Factor>(
            Linear(longArrayOf(1), intArrayOf(0), LinearOp.GE, lower),
        ))
        val captured = FactorProjectionProviders.Default.prepare(problem)
        val relaxer = CpToLpRelaxation(problem, null, projections = captured, reuseTemplateMetadata = true)

        val reused = assertNotNull(relaxer.build(RootDomains(problem)).model.authoritativeModel())
        val fresh = assertNotNull(CpToLpRelaxation(problem, null, projections = null)
            .build(RootDomains(problem)).model.authoritativeModel())

        assertEquals(lower, assertNotNull(relaxer.preparationTemplates).bounds.getValue(0).lower)
        assertTrue(reused.sameAuthority(fresh))
    }

    @Test
    fun `guarded templates should reconstruct rows from each current pin`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 8),
        ))
        val captured = FactorProjectionProviders.Default.prepare(problem)
        val relaxer = CpToLpRelaxation(problem, null, projections = captured, reuseTemplateMetadata = true)
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

    @Test
    fun `oversized preparation should decline before reading the factor payload`() {
        var payloadRead = false
        val expensive = object : Factor by source.factors[0] {
            override val structuralKeyWeight: Int = 262_144
            override val intVars: IntArray get() {
                payloadRead = true
                return source.factors[0].intVars
            }
        }
        val problem = source.withFactors(arrayOf(expensive))
        val captured = FactorProjectionProviders.Default.prepare(problem)
        payloadRead = false

        val result = captured.prepareLpTemplates(objective, Cancellation.Never)

        assertNull(result)
        assertTrue(!payloadRead)
    }
}
