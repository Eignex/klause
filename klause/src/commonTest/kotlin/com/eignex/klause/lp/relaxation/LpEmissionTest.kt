package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.RelaxationProjection
import com.eignex.klause.lp.RelaxationProjectionProvider
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.RelaxationCapability
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpEmissionTest {
    @Test
    fun `interrupted emission should charge materialized construction`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 10) }, arrayOf<Factor>(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 5),
        ))
        var cancelled = false
        val projections = FactorProjectionProviders(
            relaxation = RelaxationProjectionProvider {
                RelaxationProjection(RelaxationCapability.SOUND) { _, builder ->
                    builder.linearRow(LinearOp.GE, intArrayOf(0), longArrayOf(1), 0)
                    cancelled = true
                    builder.linearRow(LinearOp.GE, intArrayOf(1), longArrayOf(1), 0)
                }
            },
        ).prepare(problem)
        val cache = LpEmissionCache(CpToLpRelaxation(problem, null, projections = projections))
        var work = 0L

        assertFailsWith<LpAssemblyCancelled> {
            cache.prepare(RootDomains(problem), 0, Cancellation { cancelled }) { work += it }
        }

        assertEquals(3L, work)
    }

    @Test
    fun `restored cached emissions should add no construction charge`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 8),
        ))
        val root = problem.finiteIntDomain(0)
        var live = root
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = live
            override fun boolValue(varId: Int): Boolean? = null
        }
        val cache = LpEmissionCache(CpToLpRelaxation(problem, null))
        cache.refresh(domains, 0)
        live = root.withMinAtLeast(2)
        cache.refresh(domains, 1)
        live = root
        var work = 0L

        cache.prepare(domains, 0, onWork = { work += it }).commit()

        assertEquals(0L, work)
    }

    @Test
    fun `declined preparation should charge once for each dirty emission update`() {
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1)
        val expensive = object : Factor by row {
            override val structuralKeyWeight: Int = 262_144
        }
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            expensive,
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 8),
        ))
        val projections = FactorProjectionProviders.Default.prepare(problem)
        var attemptWork = 0L
        projections.prepareLpTemplates(null, Cancellation.Never) { attemptWork = it }
        var work = 0L
        val relaxer = CpToLpRelaxation(problem, null, projections = projections, onTemplateWork = { work += it })
        val cache = LpEmissionCache(relaxer)
        var live = problem.finiteIntDomain(0)
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = live
            override fun boolValue(varId: Int): Boolean? = null
        }

        cache.refresh(domains, 0)
        cache.refresh(domains, 0)
        live = live.withMinAtLeast(2)
        cache.refresh(domains, 1)

        assertTrue(attemptWork > 0L)
        assertEquals(2L * attemptWork, work)
    }

    @Test
    fun `interrupted template preparation should allow a later emission update`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
        ))
        val projections = FactorProjectionProviders.Default.prepare(problem)
        val relaxer = CpToLpRelaxation(problem, null, projections = projections)
        val cache = LpEmissionCache(relaxer)
        var work = 0L
        val meter = WorkMeter { work += it }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = work > 0L
            override fun workMeter(): WorkMeter = meter
        }
        assertFailsWith<LpAssemblyCancelled> { cache.prepare(RootDomains(problem), 0, token) }

        cache.refresh(RootDomains(problem), 0)

        assertNotNull(relaxer.preparationTemplates)
    }

    @Test
    fun `emission cache should restore the enclosing emission on nested retracts`() {
        val problem = Problem(
            1,
            1,
            arrayOf(IntDomain(0, 10)),
            arrayOf<Factor>(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 8)),
        )
        val rootDomain = problem.finiteIntDomain(0)
        var live = rootDomain
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = live
            override fun boolValue(varId: Int): Boolean? = null
        }
        val cache = LpEmissionCache(CpToLpRelaxation(problem, null))
        val root = cache.refresh(domains, 0)
        live = rootDomain.withMinAtLeast(1)
        cache.refresh(domains, 1)
        val enclosingDomain = rootDomain.withMinAtLeast(2)
        live = enclosingDomain
        val enclosing = cache.refresh(domains, 1)
        live = rootDomain.withMinAtLeast(3)
        cache.refresh(domains, 2)

        cache.retract(1)
        live = enclosingDomain
        val parent = cache.refresh(domains, 1)
        cache.retract(0)
        live = rootDomain
        val ancestor = cache.refresh(domains, 0)

        assertSame(enclosing.emissions[1], parent.emissions[1])
        assertSame(root.emissions[1], ancestor.emissions[1])
    }

    @Test
    fun `stale prepared emission should fail to overwrite a later publication`() {
        val problem = Problem(
            1,
            1,
            arrayOf(IntDomain(0, 10)),
            arrayOf<Factor>(
                ReifiedRealLinear(
                    0,
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    intArrayOf(),
                    doubleArrayOf(),
                    LinearOp.GE,
                    8.0,
                ),
            ),
        )
        var pin: Boolean? = null
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = problem.finiteIntDomain(varId)
            override fun boolValue(varId: Int): Boolean? = pin
        }
        val cache = LpEmissionCache(CpToLpRelaxation(problem, null))
        val root = cache.refresh(domains, 0)
        pin = true
        val prepared = cache.prepare(domains, 1)
        pin = null
        val later = cache.refresh(domains, 0)

        assertFailsWith<IllegalStateException> { prepared.commit() }

        assertSame(root.emissions[1], later.emissions[1])
        assertEquals(0, cache.depth)
    }

    @Test
    fun `changed live big M should retain the exact fresh row and its premises`() {
        val problem = Problem(
            1,
            1,
            arrayOf(IntDomain(0, 10)),
            arrayOf<Factor>(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.GE, 8)),
        )
        var live = problem.finiteIntDomain(0)
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = live
            override fun boolValue(varId: Int): Boolean? = null
        }
        val relaxer = CpToLpRelaxation(problem, null)
        val cache = LpEmissionCache(relaxer)
        cache.refresh(domains, 0)
        live = live.withMinAtLeast(2)

        val child = cache.refresh(domains, 1)

        assertEquals(listOf(1), child.changed)
        val cached = assertNotNull(child.emissions[1].relaxation.model.authoritativeModel())
        val fresh = assertNotNull(relaxer.build(domains).model.authoritativeModel())
            .recentered(List(cached.n) { cached.column(it).origin })
        assertTrue(cached.copy(columns = List(fresh.numVars) { fresh.column(it) }).sameAuthority(fresh))
    }

    @Test
    fun `live reification should invalidate only its own source region`() {
        val problem = Problem(
            1,
            2,
            arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
            arrayOf<Factor>(
                ReifiedRealLinear(
                    0,
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    intArrayOf(),
                    doubleArrayOf(),
                    LinearOp.GE,
                    8.0,
                ),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.GE, 2),
            ),
        )
        var pin: Boolean? = null
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = problem.finiteIntDomain(varId)
            override fun boolValue(varId: Int): Boolean? = pin
        }
        val cache = LpEmissionCache(CpToLpRelaxation(problem, null))
        val root = cache.refresh(domains, 0)

        pin = true
        val child = cache.refresh(domains, 1)

        assertEquals(listOf(1), child.changed)
        assertSame(root.emissions[2], child.emissions[2])
        assertEquals(-8.0, child.emissions[1].relaxation.model.doubleView!!.rhs[0])
    }

    @Test
    fun `cancelled refresh should leave the published ancestor unchanged`() {
        val problem = Problem(
            1,
            1,
            arrayOf(IntDomain(0, 10)),
            arrayOf<Factor>(
                ReifiedRealLinear(
                    0,
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    intArrayOf(),
                    doubleArrayOf(),
                    LinearOp.GE,
                    8.0,
                ),
            ),
        )
        var pin: Boolean? = null
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = problem.finiteIntDomain(varId)
            override fun boolValue(varId: Int): Boolean? = pin
        }
        val cache = LpEmissionCache(CpToLpRelaxation(problem, null))
        val root = cache.refresh(domains, 0)
        pin = true
        assertFailsWith<LpAssemblyCancelled> { cache.refresh(domains, 1, Cancellation { true }) }
        pin = null

        val unchanged = cache.refresh(domains, 0)

        assertSame(root.emissions[1], unchanged.emissions[1])
        assertEquals(0, cache.depth)
    }
}
