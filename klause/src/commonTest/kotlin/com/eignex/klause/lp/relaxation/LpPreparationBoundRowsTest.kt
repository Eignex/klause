package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpPreparationBoundRowsTest {
    @Test
    fun `prepared unary rows should retain the optimum when column bounds imply them`() {
        val cases = listOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0) to IntDomain(0, 10),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10) to IntDomain(0, 10),
            Linear(intArrayOf(-1), intArrayOf(0), LinearOp.LE, 0) to IntDomain(0, 10),
            Linear(intArrayOf(-1), intArrayOf(0), LinearOp.GE, -10) to IntDomain(0, 10),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 5) to IntDomain(5, 5),
            Linear(intArrayOf(-1), intArrayOf(0), LinearOp.EQ, -5) to IntDomain(5, 5),
        )
        for ((factor, domain) in cases) {
            val problem = Problem(0, 1, arrayOf(domain), arrayOf<Factor>(factor))
            val cost = LinearObjective(intCoefficients = longArrayOf(1L))
            val prepared = CpToLpRelaxation(problem, cost,
                projections = FactorProjectionProviders.Default.prepare(problem),
                reuseTemplateMetadata = true, omitBoundRows = true).build(RootDomains(problem))
            val fresh = CpToLpRelaxation(problem, cost, reuseSourceTemplates = false).build(RootDomains(problem))

            val reused = solveAndCertify(assertNotNull(prepared.authoritativeWithTemplates(ExecutionControl.Never)))
            val original = solveAndCertify(assertNotNull(fresh.model.authoritativeModel()))

            assertEquals(0, prepared.model.m)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, reused.verdict)
            assertEquals(original.verdict, reused.verdict)
            assertEquals(original.lowerBound, reused.lowerBound)
            assertEquals(original.witness?.objective, reused.witness?.objective)
        }
    }

    @Test
    fun `later consumers should compile bound rows from retained definitions`() {
        for (metadata in listOf(false, true)) {
            val problem = Problem(0, 2, Array(2) { IntDomain(0, 10) }, arrayOf<Factor>(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10),
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3)))
            val cost = LinearObjective(intCoefficients = longArrayOf(1L, 1L))
            val captured = FactorProjectionProviders.Default.prepare(problem)
            val before = assertNotNull(captured.prepareLpTemplates(cost, ExecutionControl.Never,
                reuseMetadata = metadata))
            val consumer = CpToLpRelaxation(problem, cost, projections = captured,
                reuseTemplateMetadata = true, omitBoundRows = true)

            val prepared = consumer.build(RootDomains(problem))
            val fresh = CpToLpRelaxation(problem, cost, reuseSourceTemplates = false).build(RootDomains(problem))
            val reused = solveAndCertify(assertNotNull(prepared.authoritativeWithTemplates(ExecutionControl.Never)))
            val original = solveAndCertify(assertNotNull(fresh.model.authoritativeModel()))

            assertEquals(1, prepared.model.m)
            assertEquals(LpVerdict.ATTAINED_OPTIMUM, reused.verdict)
            assertEquals(original.witness?.objective, reused.witness?.objective)
            assertSame(before.factors[1], assertNotNull(consumer.preparationTemplates).factors[1])
            assertEquals(1, consumer.build(RootDomains(problem)).model.m)
        }
    }

    @Test
    fun `cancelled bound row compilation should retain the previous definitions`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10)))
        val captured = FactorProjectionProviders.Default.prepare(problem)
        val before = assertNotNull(captured.prepareLpTemplates(null, ExecutionControl.Never, reuseMetadata = true))
        var work = 0L
        val token = object : ExecutionControl() {
            private val meter = WorkMeter { work += it }
            override fun isCancelled(): Boolean = work >= 3L
            override val accounting: WorkMeter get() = meter
        }

        val stopped = captured.prepareLpTemplates(null, token, reuseMetadata = true, compileBoundRows = true)
        val after = assertNotNull(captured.prepareLpTemplates(null, ExecutionControl.Never, reuseMetadata = true))

        assertNull(stopped)
        assertTrue(work >= 3L)
        assertSame(before.factors, after.factors)
    }

    @Test
    fun `wider consuming domains should restore prepared bound rows`() {
        for (upper in listOf(false, true)) {
            val factor = Linear(intArrayOf(1), intArrayOf(0),
                if (upper) LinearOp.LE else LinearOp.GE, if (upper) 10 else 0)
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(factor))
            val cost = LinearObjective(intCoefficients = longArrayOf(1L))
            val consumer = CpToLpRelaxation(problem, cost,
                projections = FactorProjectionProviders.Default.prepare(problem),
                reuseTemplateMetadata = true, omitBoundRows = true)
            consumer.build(RootDomains(problem))
            val root = RootDomains(problem)
            val wider = object : RelaxationDomains by root {
                override fun intDomain(varId: Int): IntDomain = if (upper) IntDomain(0, 12) else IntDomain(-2, 10)
            }

            val restored = consumer.build(wider)
            val fresh = CpToLpRelaxation(problem, cost, reuseSourceTemplates = false).build(wider)

            assertEquals(1, restored.model.m)
            assertTrue(assertNotNull(restored.authoritativeWithTemplates(ExecutionControl.Never))
                .sameAuthority(assertNotNull(fresh.model.authoritativeModel())))
        }
    }

    @Test
    fun `node bounds should not remove a row needed after undo`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 20)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10)))
        val root = RootDomains(problem)
        val tighter = object : RelaxationDomains by root {
            override fun intDomain(varId: Int): IntDomain = IntDomain(0, 5)
        }

        val prepared = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L)),
            projections = FactorProjectionProviders.Default.prepare(problem),
            reuseTemplateMetadata = true, omitBoundRows = true).build(tighter)

        assertEquals(1, prepared.model.m)
    }

    @Test
    fun `constructed column bounds should govern a prepared row check`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10)))
        val root = RootDomains(problem)
        var calls = 0
        val domains = object : RelaxationDomains by root {
            override fun intDomain(varId: Int): IntDomain =
                if (calls++ == 0) IntDomain(0, 20) else IntDomain(0, 10)
        }

        val prepared = CpToLpRelaxation(problem, LinearObjective(intCoefficients = longArrayOf(1L)),
            projections = FactorProjectionProviders.Default.prepare(problem),
            reuseTemplateMetadata = true, omitBoundRows = true).build(domains)

        assertEquals(1, prepared.model.m)
    }

    @Test
    fun `invented bound endpoints should retain their source rows`() {
        for (upper in listOf(false, true)) {
            val factor = Linear(intArrayOf(1), intArrayOf(0),
                if (upper) LinearOp.LE else LinearOp.GE, if (upper) 10 else 0)
            val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(factor),
                openIntLo = booleanArrayOf(!upper), openIntHi = booleanArrayOf(upper))
            val cost = LinearObjective(intCoefficients = longArrayOf(1L))

            val prepared = CpToLpRelaxation(problem, cost,
                projections = FactorProjectionProviders.Default.prepare(problem),
                reuseTemplateMetadata = true, omitBoundRows = true).build(RootDomains(problem))
            val fresh = CpToLpRelaxation(problem, cost, reuseSourceTemplates = false).build(RootDomains(problem))

            assertEquals(1, prepared.model.m)
            assertTrue(assertNotNull(prepared.authoritativeWithTemplates(ExecutionControl.Never))
                .sameAuthority(assertNotNull(fresh.model.authoritativeModel())))
        }
    }

    @Test
    fun `open columns should retain rows beyond their constructed lower bound`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 5)), openIntHi = booleanArrayOf(true)).bake()
        val cost = LinearObjective(intCoefficients = longArrayOf(1L))

        val prepared = CpToLpRelaxation(problem, cost,
            projections = FactorProjectionProviders.Default.prepare(problem),
            reuseTemplateMetadata = true, omitBoundRows = true).build(RootDomains(problem))
        val fresh = CpToLpRelaxation(problem, cost, reuseSourceTemplates = false).build(RootDomains(problem))

        assertEquals(1, prepared.model.m)
        assertTrue(assertNotNull(prepared.authoritativeWithTemplates(ExecutionControl.Never))
            .sameAuthority(assertNotNull(fresh.model.authoritativeModel())))
    }

    @Test
    fun `loosened source bounds should restore a retained unary row`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10)))
        val selected = FactorProjectionProviders.Default.prepare(problem)
        val cost = LinearObjective(intCoefficients = longArrayOf(1L))
        CpToLpRelaxation(problem, cost, projections = selected,
            reuseTemplateMetadata = true, omitBoundRows = true).build(RootDomains(problem))
        val wider = Problem(0, 1, arrayOf(IntDomain(0, 20)), problem.factors)

        val restored = CpToLpRelaxation(wider, cost, projections = selected.forEngineView(wider),
            reuseTemplateMetadata = true, omitBoundRows = true).build(RootDomains(wider))
        val fresh = CpToLpRelaxation(wider, cost, reuseSourceTemplates = false).build(RootDomains(wider))

        assertEquals(1, restored.model.m)
        assertEquals(listOf(0), restored.rowFactorIds.toList())
        assertTrue(assertNotNull(restored.authoritativeWithTemplates(ExecutionControl.Never))
            .sameAuthority(assertNotNull(fresh.model.authoritativeModel())))
    }

    @Test
    fun `changed unary source rows should retain their current restriction`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10)))
        val selected = FactorProjectionProviders.Default.prepare(problem)
        val cost = LinearObjective(intCoefficients = longArrayOf(1L))
        CpToLpRelaxation(problem, cost, projections = selected,
            reuseTemplateMetadata = true, omitBoundRows = true).build(RootDomains(problem))
        val changed = problem.withFactors(arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5)))

        val restored = CpToLpRelaxation(changed, cost, projections = selected.forEngineView(changed),
            reuseTemplateMetadata = true, omitBoundRows = true).build(RootDomains(changed))
        val fresh = CpToLpRelaxation(changed, cost, reuseSourceTemplates = false).build(RootDomains(changed))

        assertEquals(1, restored.model.m)
        assertEquals(listOf(0), restored.rowFactorIds.toList())
        assertTrue(assertNotNull(restored.authoritativeWithTemplates(ExecutionControl.Never))
            .sameAuthority(assertNotNull(fresh.model.authoritativeModel())))
    }

    @Test
    fun `guarded strict and nonunit rows should retain their ordinary authority`() {
        val factors = listOf<Factor>(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 10),
            ExactLinear.of(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ONE),
                LinearOp.LE, BigFraction.ofLong(10), strict = true),
            Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 20),
        )
        for (factor in factors) {
            val problem = Problem(1, 1, arrayOf(IntDomain(0, 10)), arrayOf(factor))
            val cost = LinearObjective(intCoefficients = longArrayOf(1L))
            val root = RootDomains(problem)
            val domains = object : RelaxationDomains by root {
                override fun boolValue(varId: Int): Boolean = false
            }

            val prepared = CpToLpRelaxation(problem, cost,
                projections = FactorProjectionProviders.Default.prepare(problem),
                reuseTemplateMetadata = true, omitBoundRows = true).build(domains)
            val fresh = CpToLpRelaxation(problem, cost, reuseSourceTemplates = false).build(domains)

            assertTrue(assertNotNull(prepared.authoritativeWithTemplates(ExecutionControl.Never))
                .sameAuthority(assertNotNull(fresh.model.authoritativeModel())))
        }
    }

    @Test
    fun `retained source layouts should keep unary row ownership`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10)))
        val cost = LinearObjective(intCoefficients = longArrayOf(1L))
        val consumer = CpToLpRelaxation(problem, cost,
            projections = FactorProjectionProviders.Default.prepare(problem),
            reuseTemplateMetadata = true, omitBoundRows = true)

        val edit = assertNotNull(LpRetainedSources(problem, consumer)
            .prepare(LpExactState(LpRetainedSources.emptyModel()), RootDomains(problem)))

        assertEquals(1, edit.rows.size)
    }

    @Test
    fun `cancelled bound checks should charge their containing construction`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10)))
        var containing = 0L
        val consumer = CpToLpRelaxation(problem, LinearObjective(),
            projections = FactorProjectionProviders.Default.prepare(problem),
            reuseTemplateMetadata = true, omitBoundRows = true, onTemplateWork = { containing += it })
        consumer.build(RootDomains(problem))
        containing = 0L
        var cancelled = false
        var work = 0L
        val meter = WorkMeter { work += it }
        val token = object : ExecutionControl() {
            override fun isCancelled(): Boolean = cancelled
            override val accounting: WorkMeter get() = meter
        }
        val root = RootDomains(problem)
        val domains = object : RelaxationDomains by root {
            override fun intDomain(varId: Int): IntDomain {
                cancelled = true
                return root.intDomain(varId)
            }
        }

        assertFailsWith<LpAssemblyCancelled> { consumer.build(domains, cancellation = token) }

        assertTrue(cancelled)
        assertTrue(work > 0L)
        assertEquals(work, containing)
    }
}
