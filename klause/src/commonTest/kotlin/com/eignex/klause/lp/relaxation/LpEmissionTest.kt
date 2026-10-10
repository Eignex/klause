package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpEmissionTest {
    @Test
    fun `repeated edits in one scope restore the enclosing emission on nested pops`() {
        val problem = Problem(
            1, 1, arrayOf(IntDomain(0, 10)),
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
    fun `a stale prepared emission cannot overwrite a later publication`() {
        val problem = Problem(
            1, 1, arrayOf(IntDomain(0, 10)),
            arrayOf<Factor>(
                ReifiedRealLinear(
                    0, intArrayOf(0), doubleArrayOf(1.0), intArrayOf(), doubleArrayOf(), LinearOp.GE, 8.0,
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
    fun `a changed live big M retains the exact fresh row and its premises`() {
        val problem = Problem(
            1, 1, arrayOf(IntDomain(0, 10)),
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
    fun `a live reification invalidates only its own source region`() {
        val problem = Problem(
            1, 2, arrayOf(IntDomain(0, 10), IntDomain(0, 10)),
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
    fun `cancelled refresh leaves the published ancestor unchanged`() {
        val problem = Problem(
            1, 1, arrayOf(IntDomain(0, 10)),
            arrayOf<Factor>(
                ReifiedRealLinear(
                    0, intArrayOf(0), doubleArrayOf(1.0), intArrayOf(), doubleArrayOf(), LinearOp.GE, 8.0,
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
