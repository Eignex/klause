package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.engine.solveAndCertify
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

            val reused = solveAndCertify(assertNotNull(prepared.authoritativeWithTemplates(Cancellation.Never)))
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
            val before = assertNotNull(captured.prepareLpTemplates(cost, Cancellation.Never,
                reuseMetadata = metadata))
            val consumer = CpToLpRelaxation(problem, cost, projections = captured,
                reuseTemplateMetadata = true, omitBoundRows = true)

            val prepared = consumer.build(RootDomains(problem))
            val fresh = CpToLpRelaxation(problem, cost, reuseSourceTemplates = false).build(RootDomains(problem))
            val reused = solveAndCertify(assertNotNull(prepared.authoritativeWithTemplates(Cancellation.Never)))
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
        val before = assertNotNull(captured.prepareLpTemplates(null, Cancellation.Never, reuseMetadata = true))
        var work = 0L
        val token = object : Cancellation {
            private val meter = WorkMeter { work += it }
            override fun isCancelled(): Boolean = work >= 3L
            override fun workMeter(): WorkMeter = meter
        }

        val stopped = captured.prepareLpTemplates(null, token, reuseMetadata = true, compileBoundRows = true)
        val after = assertNotNull(captured.prepareLpTemplates(null, Cancellation.Never, reuseMetadata = true))

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
            assertTrue(assertNotNull(restored.authoritativeWithTemplates(Cancellation.Never))
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
            assertTrue(assertNotNull(prepared.authoritativeWithTemplates(Cancellation.Never))
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
        assertTrue(assertNotNull(prepared.authoritativeWithTemplates(Cancellation.Never))
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
        assertTrue(assertNotNull(restored.authoritativeWithTemplates(Cancellation.Never))
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
        assertTrue(assertNotNull(restored.authoritativeWithTemplates(Cancellation.Never))
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

            assertTrue(assertNotNull(prepared.authoritativeWithTemplates(Cancellation.Never))
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
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = cancelled
            override fun workMeter(): WorkMeter = meter
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

    @Test
    fun `a first completed LP consumer should defer selective metadata`() {
        val consumer = CpToLpRelaxation(source, objective, projections = selection,
            reuseTemplateMetadata = true, earnTemplatePreparation = true)

        consumer.build(RootDomains(source))

        assertNull(consumer.preparationTemplates)
    }

    @Test
    fun `a first LP consumer should spend less admission work than a repeated consumer`() {
        val work = List(2) {
            var charged = 0L
            CpToLpRelaxation(source, objective, projections = selection,
                reuseTemplateMetadata = true, earnTemplatePreparation = true,
                onTemplateWork = { charged += it }).build(RootDomains(source))
            charged
        }

        assertTrue(work[0] < work[1])
    }

    @Test
    fun `earned preparation should admit a fully funded inspection`() {
        var inspectionWork = 0L
        assertNotNull(FactorProjectionProviders.Default.prepare(source).prepareLpTemplates(objective,
            Cancellation.Never, reuseMetadata = true, onWork = { inspectionWork += it }))
        val funding = inspectionWork + 1L
        selection.recordLpConstruction(1L, Cancellation.Never) {}
        selection.recordLpConstruction(funding, Cancellation.Never) {}
        var charged = 0L

        val prepared = selection.prepareLpTemplates(objective, Cancellation.Never,
            reuseMetadata = true, earned = true, onWork = { charged += it })

        assertNotNull(prepared)
        assertTrue(charged <= funding)
    }

    @Test
    fun `completed LP consumers should earn selective metadata`() {
        var prepared: LpPreparationTemplates? = null
        repeat(32) {
            val consumer = CpToLpRelaxation(source, objective, projections = selection,
                reuseTemplateMetadata = true, earnTemplatePreparation = true)
            consumer.build(RootDomains(source))
            prepared = consumer.preparationTemplates
        }

        assertNotNull(prepared)
    }

    @Test
    fun `earned preparation should preserve current objective authority`() {
        repeat(32) {
            CpToLpRelaxation(source, objective, projections = selection,
                reuseTemplateMetadata = true, earnTemplatePreparation = true).build(RootDomains(source))
        }
        val changed = LinearObjective(intCoefficients = longArrayOf(2, 1, 1, 1, 1, 1), constant = 7L)

        val reused = assertNotNull(CpToLpRelaxation(source, changed, projections = selection,
            reuseTemplateMetadata = true, earnTemplatePreparation = true).build(RootDomains(source))
            .authoritativeWithTemplates(Cancellation.Never))
        val fresh = assertNotNull(CpToLpRelaxation(source, changed, projections = null)
            .build(RootDomains(source)).model.authoritativeModel())

        assertTrue(reused.sameAuthority(fresh))
    }

    @Test
    fun `post debit cancellation should withhold completed construction admission`() {
        selection.recordLpConstruction(1L, Cancellation.Never) {}
        var cancelled = false
        selection.recordLpConstruction(LpPreparationTemplates.MAX_UNITS, Cancellation { cancelled }) {
            cancelled = true
        }

        val prepared = selection.prepareLpTemplates(objective, Cancellation.Never,
            reuseMetadata = true, earned = true)

        assertNull(prepared)
    }

    @Test
    fun `foreign source scopes should not spend prior construction admission`() {
        repeat(2) {
            selection.recordLpConstruction(LpPreparationTemplates.MAX_UNITS, Cancellation.Never) {}
        }
        val finite = source.bake(selection)
        val remapped = selection.projected(source, IntArray(source.numFactors) { it },
            VarRemap(intArrayOf(0), IntArray(source.numIntVars) { it }))

        val prepared = listOf(assertNotNull(finite.factorProjections), remapped).map { selected ->
            selected.prepareLpTemplates(objective, Cancellation.Never, reuseMetadata = true, earned = true)
        }

        assertTrue(prepared.all { it == null })
    }

    @Test
    fun `repeated factor roles should retain one component reference`() {
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf<Factor>(
            Element(0, 0, longArrayOf(0, 1), false, 0),
        ))
        val captured = FactorProjectionProviders.Default.prepare(problem)

        val templates = assertNotNull(captured.prepareLpTemplates(null, Cancellation.Never))

        assertEquals(listOf(Term.ofIntVar(0)), templates.components.single().references.toList())
        assertEquals(setOf(0), templates.bounds.keys)
    }

    @Test
    fun `equal integer and Boolean IDs should retain independent preparation components`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 10)), arrayOf<Factor>(
            Clause(intArrayOf(0)),
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 3),
        ))
        val captured = FactorProjectionProviders.Default.prepare(problem)

        val templates = assertNotNull(captured.prepareLpTemplates(null, Cancellation.Never))

        assertEquals(2, templates.components.size)
    }

    @Test
    fun `candidate preparation should reserve less storage than complete component snapshots`() {
        val ordinary = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        val captured = FactorProjectionProviders.Default.prepare(source)

        val candidate = assertNotNull(captured.prepareLpTemplates(objective, Cancellation.Never,
            reuseMetadata = true))

        assertTrue(candidate.storageUnits < ordinary.storageUnits)
    }

    @Test
    fun `ordinary preparation should restore component scopes after candidate preparation`() {
        assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never, reuseMetadata = true))

        val ordinary = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))

        assertEquals(3, ordinary.components.size)
    }

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
        assertTrue(reused.sameAuthority(fresh))
    }

    @Test
    fun `bound metadata refresh should retain unrelated definitions and match fresh authority`() {
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
    }

    @Test
    fun `metadata refresh should read objective translations outside source templates`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never))
        val revised = objective.copy(constant = 7L)
        val consumer = CpToLpRelaxation(source, revised, projections = selection, reuseTemplateMetadata = true)

        val relaxation = consumer.build(RootDomains(source))
        val reused = assertNotNull(relaxation.model.authoritativeModel())
        val after = assertNotNull(consumer.preparationTemplates)
        val fresh = assertNotNull(CpToLpRelaxation(source, revised, projections = null)
            .build(RootDomains(source)).model.authoritativeModel())

        assertSame(before.factors, after.factors)
        assertSame(before.bounds, after.bounds)
        assertEquals(7L, relaxation.objectiveConstant)
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
        assertSame(before.bounds[1], after.bounds[1])
        assertTrue(reused.sameAuthority(fresh))
    }

    @Test
    fun `immutable source domain handoffs should avoid repeated endpoint preparation`() {
        val before = assertNotNull(selection.prepareLpTemplates(objective, Cancellation.Never, reuseMetadata = true))
        val view = source.withFactors(source.factors.copyOf())
        var retainedWork = 0L

        val retained = assertNotNull(selection.forEngineView(view).prepareLpTemplates(objective,
            Cancellation.Never, reuseMetadata = true) { retainedWork = it })
        val rebound = Problem(1, 6, Array(6) { IntDomain(0, 10) }, source.factors)
        var reboundWork = 0L
        selection.forEngineView(rebound).prepareLpTemplates(objective,
            Cancellation.Never, reuseMetadata = true) { reboundWork = it }

        assertSame(before.bounds, retained.bounds)
        assertTrue(retainedWork < reboundWork)
    }

    @Test
    fun `raw views carrying shared finite domains should recheck changed endpoints`() {
        val finite = source.bake(selection)
        val view = finite.withFactors(finite.factors.copyOf())
        val captured = FactorProjectionProviders.Default.prepare(view)
        val before = assertNotNull(captured.prepareLpTemplates(objective, Cancellation.Never, reuseMetadata = true))
        finite.rootIntDomainsInPlace[0] = IntDomain(1, 10)
        val consumer = CpToLpRelaxation(view, objective, projections = captured, reuseTemplateMetadata = true)

        val reused = assertNotNull(consumer.build(RootDomains(view)).model.authoritativeModel())
        val after = assertNotNull(consumer.preparationTemplates)
        val fresh = assertNotNull(CpToLpRelaxation(view, objective, projections = null)
            .build(RootDomains(view)).model.authoritativeModel())

        assertNotSame(before.bounds[0], after.bounds[0])
        assertEquals(1L, after.bounds.getValue(0).lower)
        assertTrue(reused.sameAuthority(fresh))
    }

    @Test
    fun `first exact import should retain its existing compatible column`() {
        val consumer = CpToLpRelaxation(source, objective, projections = selection)
        val relaxation = consumer.build(RootDomains(source))
        val imported = assertNotNull(relaxation.model.authoritativeModel())
        val current = imported.column(0)
        val template = assertNotNull(consumer.preparationTemplates).bounds.getValue(relaxation.colVarId[0])

        val adopted = template.compatibleColumn(current)

        assertSame(current, adopted)
    }

    @Test
    fun `candidate import should adopt exact columns with unchanged normalized authority`() {
        val consumer = CpToLpRelaxation(source, objective, projections = selection, reuseTemplateMetadata = true)
        val relaxation = consumer.build(RootDomains(source))
        var adopted = 0

        val reused = assertNotNull(relaxation.authoritativeWithTemplates(Cancellation.Never) { adopted = it })
        val fresh = assertNotNull(CpToLpRelaxation(source, objective, projections = null)
            .build(RootDomains(source)).model.authoritativeModel())

        assertTrue(adopted > 0)
        assertSame(assertNotNull(consumer.preparationTemplates).bounds.getValue(0).column, reused.column(0))
        assertTrue(reused.sameAuthority(fresh))
    }

    @Test
    fun `consumer coefficient changes should not affect retained row templates`() {
        val first = CpToLpRelaxation(source, objective, projections = selection, reuseTemplateMetadata = true)
            .build(RootDomains(source))
        first.model.csc.colVal[0] = 17L
        val next = CpToLpRelaxation(source, objective, projections = selection, reuseTemplateMetadata = true)

        val reused = assertNotNull(next.build(RootDomains(source)).model.authoritativeModel())
        val fresh = assertNotNull(CpToLpRelaxation(source, objective, projections = null)
            .build(RootDomains(source)).model.authoritativeModel())

        assertTrue(reused.sameAuthority(fresh))
    }

    @Test
    fun `cancelled candidate import should withhold exact authority`() {
        val consumer = CpToLpRelaxation(source, objective, projections = selection, reuseTemplateMetadata = true)
        val relaxation = consumer.build(RootDomains(source))
        var work = 0L
        val meter = WorkMeter { work += it }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 2L
            override fun workMeter(): WorkMeter = meter
        }

        var published = false

        val result = relaxation.authoritativeWithTemplates(token) { published = true }

        assertNull(result)
        assertFalse(published)
        assertTrue(work >= 2L)
    }

    @Test
    fun `cancelled bound definition should permit a later complete import`() {
        val consumer = CpToLpRelaxation(source, objective, projections = selection, reuseTemplateMetadata = true)
        val relaxation = consumer.build(RootDomains(source))
        var work = 0L
        val meter = WorkMeter { work += it }
        val token = object : Cancellation {
            override fun isCancelled(): Boolean = work >= 2L
            override fun workMeter(): WorkMeter = meter
        }
        assertNull(relaxation.authoritativeWithTemplates(token))

        val recovered = assertNotNull(relaxation.authoritativeWithTemplates(Cancellation.Never))
        val ordinary = assertNotNull(relaxation.model.authoritativeModel())

        assertTrue(recovered.sameAuthority(ordinary))
    }

    @Test
    fun `template imports should report completed and interrupted work once`() {
        for (limit in listOf(2L, Long.MAX_VALUE)) {
            val captured = FactorProjectionProviders.Default.prepare(source)
            val relaxation = CpToLpRelaxation(source, objective, projections = captured).build(RootDomains(source))
            var charged = 0L
            val meter = WorkMeter { charged += it }
            val token = object : Cancellation {
                override fun isCancelled(): Boolean = charged >= limit
                override fun workMeter(): WorkMeter = meter
            }
            val reported = ArrayList<Long>()

            val imported = relaxation.authoritativeWithTemplates(token, onWork = { reported.add(it) })

            assertTrue(charged > 0L)
            assertEquals(listOf(charged), reported)
            assertEquals(limit == Long.MAX_VALUE, imported != null)
        }
    }

    @Test
    fun `import allowance exhaustion should withhold completed authority`() {
        val relaxation = CpToLpRelaxation(source, objective, projections = selection).build(RootDomains(source))
        var stopped = false
        var published = false
        val token = Cancellation { stopped }

        val imported = relaxation.authoritativeWithTemplates(token, onWork = { stopped = it > 0L }) {
            published = true
        }

        assertTrue(stopped)
        assertNull(imported)
        assertFalse(published)
    }

    @Test
    fun `alternating probe upper sides should preserve current exact authority`() {
        val consumer = CpToLpRelaxation(source, objective, projections = selection, reuseTemplateMetadata = true)
        val relaxation = consumer.build(RootDomains(source))

        for (clamped in listOf(false, true, false)) {
            relaxation.model.probeClampedHi[0] = clamped

            val imported = assertNotNull(relaxation.authoritativeWithTemplates(Cancellation.Never))
            val ordinary = assertNotNull(relaxation.model.authoritativeModel())

            assertTrue(imported.sameAuthority(ordinary))
        }
    }

    @Test
    fun `cancelled empty candidate import should withhold exact authority`() {
        val problem = source.withFactors(emptyArray())
        val captured = FactorProjectionProviders.Default.prepare(problem)
        val consumer = CpToLpRelaxation(problem, null, projections = captured, reuseTemplateMetadata = true)
        val relaxation = consumer.build(RootDomains(problem))

        val result = relaxation.authoritativeWithTemplates(Cancellation { true })

        assertNull(result)
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
    fun `repeated ordinary compact imports should amortize charged bound construction`() {
        for (reuseIndexes in listOf(false, true)) {
            val captured = FactorProjectionProviders.Default.prepare(source)
            val first = CpToLpRelaxation(source, objective, projections = captured,
                reuseTemplateIndexes = reuseIndexes).build(RootDomains(source))
            var work = 0L
            val meter = WorkMeter { work += it }
            val token = object : Cancellation {
                override fun isCancelled(): Boolean = false
                override fun workMeter(): WorkMeter = meter
            }
            assertNotNull(first.authoritativeWithTemplates(token))
            val constructionWork = work
            val second = CpToLpRelaxation(source, objective, projections = captured,
                reuseTemplateIndexes = reuseIndexes).build(RootDomains(source))
            work = 0L

            assertNotNull(second.authoritativeWithTemplates(token))

            assertTrue(work < constructionWork)
        }
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
        val first = CpToLpRelaxation(source, objective, projections = selection, reuseTemplateMetadata = true)
        first.build(RootDomains(source))
        val before = assertNotNull(first.preparationTemplates)
        val replacement = Linear(intArrayOf(2), intArrayOf(1), LinearOp.GE, 1)
        val view = source.withFactors(arrayOf(replacement, source.factors[1], source.factors[2]))
        val changed = CpToLpRelaxation(view, objective, projections = selection.forEngineView(view),
            reuseTemplateMetadata = true)

        val reused = assertNotNull(changed.build(RootDomains(view)).model.authoritativeModel())
        val fresh = assertNotNull(CpToLpRelaxation(view, objective, projections = null)
            .build(RootDomains(view)).model.authoritativeModel())

        assertTrue(reused.sameAuthority(fresh))
        assertNotSame(before.factors[0], assertNotNull(changed.preparationTemplates).factors[0])
        assertSame(before.factors[1], assertNotNull(changed.preparationTemplates).factors[1])
    }

    @Test
    fun `bound metadata should retain integers beyond floating precision`() {
        val lower = 9_007_199_254_740_993L
        val problem = Problem(0, 1, arrayOf(IntDomain(lower, lower + 2)), arrayOf<Factor>(
            Linear(longArrayOf(1), intArrayOf(0), LinearOp.GE, lower),
        ))
        val captured = FactorProjectionProviders.Default.prepare(problem)
        val relaxer = CpToLpRelaxation(problem, null, projections = captured, reuseTemplateMetadata = true)

        val reused = assertNotNull(relaxer.build(RootDomains(problem)).authoritativeWithTemplates(Cancellation.Never))
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
    fun `bound heavy preparation should decline within its storage allowance`() {
        val count = 12_000
        val problem = Problem(0, count, Array(count) { IntDomain(0, 1) }, arrayOf<Factor>(
            Linear(IntArray(count) { 1 }, IntArray(count) { it }, LinearOp.GE, 1),
        ))
        val captured = FactorProjectionProviders.Default.prepare(problem)
        var work = 0L

        val result = captured.prepareLpTemplates(null, Cancellation.Never) { work = it }

        assertNull(result)
        assertTrue(work < 262_144L)
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
