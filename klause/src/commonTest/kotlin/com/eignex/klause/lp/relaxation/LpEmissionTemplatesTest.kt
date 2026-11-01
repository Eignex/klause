package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.propagation.bake
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.FactorProjectionProviders
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LpStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LpEmissionTemplatesTest {
    private val source = Problem(1, 3, Array(3) { IntDomain(0, 10) }, arrayOf<Factor>(
        Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 6),
    ))
    private val selected = FactorProjectionProviders.Default.prepare(source)
    private val objective = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))

    @Test
    fun `repeated source consumers should share completed fragments and keep private numerical models`() {
        val exact = Problem(1, 3, Array(3) { IntDomain(0, 10) }, arrayOf<Factor>(
            ExactLinear.of(IntArray(3) { Term.ofIntVar(it) }, List(3) { BigFraction.ONE },
                LinearOp.GE, BigFraction.ofLong(6L)),
        ))
        for (problem in listOf(source, exact)) {
            val selection = FactorProjectionProviders.Default.prepare(problem)
            val sink = LpStatsSink()
            val models = ArrayList<Any>()
            repeat(3) { attempt ->
                val consumer = CpToLpRelaxation(problem, objective, projections = selection,
                    templateStats = sink, retainImportedDefinitions = true)
                val sources = LpRetainedSources(problem, consumer)
                LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
                    val edit = assertNotNull(sources.prepare(owner.state, RootDomains(problem)))
                    assertTrue(owner.replaceRows(edit.retired, edit.columns, edit.rows, false,
                        objective = edit.objective))
                    edit.commit()
                    assertEquals(BigFraction.ofLong(6L), assertNotNull(owner.solve()).lowerBound)
                    models.add(owner.state.model)
                }
                if (attempt == 0) assertEquals(0L, selection.lpEmissionTemplateUnits)
            }

            assertNotSame(models[0], models[1])
            assertNotSame(models[1], models[2])
            assertEquals(1L, sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE")
                .outcomes["EMISSION_FRAGMENTS_REUSED"])
            assertEquals(1L, sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE")
                .outcomes["EMISSION_IMPORT_MODELS_REUSED"])
            assertEquals(1L, sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE")
                .outcomes["EMISSION_IMPORT_MODELS_PUBLISHED"])
            assertTrue(selection.lpEmissionTemplateUnits in 1L..LpEmissionTemplates.MAX_UNITS)
        }
    }

    @Test
    fun `a new source endpoint should replace its fragment before reuse`() {
        val domains = RootDomains(source)
        repeat(3) {
            val consumer = CpToLpRelaxation(source, objective, projections = selected, retainImportedDefinitions = true)
            LpEmissionCache(consumer).refresh(domains, 0)
        }
        val changed = Problem(1, 3, arrayOf(IntDomain(2, 10), IntDomain(0, 10), IntDomain(0, 10)), source.factors)
        val captured = selected.forEngineView(changed)
        val sink = LpStatsSink()
        val consumer = CpToLpRelaxation(changed, objective, projections = captured,
            templateStats = sink, retainImportedDefinitions = true)

        val update = LpEmissionCache(consumer).refresh(RootDomains(changed), 0)

        val model = update.emissions.single { it.region.kind == LpEmissionKind.FACTOR }.relaxation.model
        assertEquals(2L, model.loShift[0])
        assertNull(sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE").outcomes["EMISSION_FRAGMENTS_REUSED"])
    }

    @Test
    fun `an opened source side should replace the finite fragment`() {
        repeat(3) {
            LpEmissionCache(CpToLpRelaxation(source, objective, projections = selected,
                retainImportedDefinitions = true)).refresh(RootDomains(source), 0)
        }
        val changed = Problem(1, 3, Array(3) { IntDomain(0, 10) }, source.factors,
            openIntHi = booleanArrayOf(true, false, false))
        val sink = LpStatsSink()
        val consumer = CpToLpRelaxation(changed, objective, projections = selected.forEngineView(changed),
            templateStats = sink, retainImportedDefinitions = true)

        val update = LpEmissionCache(consumer).refresh(RootDomains(changed), 0)

        val model = update.emissions.single { it.region.kind == LpEmissionKind.FACTOR }.relaxation.model
        assertFalse(model.hasFiniteUpper(0))
        assertNull(sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE").outcomes["EMISSION_FRAGMENTS_REUSED"])
    }

    @Test
    fun `a rewritten source row should supply the current numerical bound`() {
        repeat(3) {
            LpEmissionCache(CpToLpRelaxation(source, objective, projections = selected,
                retainImportedDefinitions = true)).refresh(RootDomains(source), 0)
        }
        val changed = source.withFactors(arrayOf<Factor>(
            Linear(intArrayOf(2, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 6),
        ))
        val consumer = CpToLpRelaxation(changed, objective, projections = selected.forEngineView(changed),
            retainImportedDefinitions = true)
        val sources = LpRetainedSources(changed, consumer)

        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val edit = assertNotNull(sources.prepare(owner.state, RootDomains(changed)))
            assertTrue(owner.replaceRows(edit.retired, edit.columns, edit.rows, false, objective = edit.objective))
            edit.commit()

            assertEquals(BigFraction.ofLong(3L), assertNotNull(owner.solve()).lowerBound)
        }
    }

    @Test
    fun `changed dimensions or semantics should restart fragment admission`() {
        val changed = listOf(
            Problem(1, 4, Array(4) { IntDomain(0, 10) }, source.factors),
            source.withSourceSemantics(SourceSemantics(SourceFormat.SCHEMA).resolve()),
        )
        for (problem in changed) {
            repeat(3) {
                LpEmissionCache(CpToLpRelaxation(source, objective, projections = selected,
                    retainImportedDefinitions = true)).refresh(RootDomains(source), 0)
            }
            val sink = LpStatsSink()
            val consumer = CpToLpRelaxation(problem, objective, projections = selected.forEngineView(problem),
                templateStats = sink, retainImportedDefinitions = true)

            LpEmissionCache(consumer).refresh(RootDomains(problem), 0)

            assertNull(sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE").outcomes["EMISSION_FRAGMENTS_REUSED"])
        }
    }

    @Test
    fun `changed objective costs should replace only affected fragment definitions`() {
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 10) }, arrayOf<Factor>(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 6),
            Linear(intArrayOf(1, 1, 1), intArrayOf(1, 2, 3), LinearOp.GE, 6),
        ))
        val selection = FactorProjectionProviders.Default.prepare(problem)
        val cost = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L, 1L))
        repeat(3) {
            LpEmissionCache(CpToLpRelaxation(problem, cost, projections = selection,
                retainImportedDefinitions = true)).refresh(RootDomains(problem), 0)
        }
        val sink = LpStatsSink()
        val changed = LinearObjective(intCoefficients = longArrayOf(2L, 1L, 1L, 1L), constant = 7L)
        val consumer = CpToLpRelaxation(problem, changed, projections = selection,
            templateStats = sink, retainImportedDefinitions = true)

        val update = LpEmissionCache(consumer).refresh(RootDomains(problem), 0)

        assertTrue(update.emissions.all { it.relaxation.objectiveConstant == 7L })
        assertEquals(1L, sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE")
            .outcomes["EMISSION_FRAGMENTS_REUSED"])
    }

    @Test
    fun `raw finite and remapped views should keep separate fragment admission`() {
        repeat(3) {
            LpEmissionCache(CpToLpRelaxation(source, objective, projections = selected,
                retainImportedDefinitions = true)).refresh(RootDomains(source), 0)
        }
        val finite = source.bake(selected)
        val remapped = selected.projected(source, intArrayOf(0), VarRemap(intArrayOf(0), intArrayOf(0, 1, 2)))
        for ((problem, selection) in listOf(finite to assertNotNull(finite.factorProjections), source to remapped)) {
            val sink = LpStatsSink()
            val consumer = CpToLpRelaxation(problem, objective, projections = selection,
                templateStats = sink, retainImportedDefinitions = true)

            LpEmissionCache(consumer).refresh(RootDomains(problem), 0)

            assertNull(sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE").outcomes["EMISSION_FRAGMENTS_REUSED"])
        }
    }

    @Test
    fun `a changed live endpoint should invalidate a guarded fragment`() {
        val problem = Problem(1, 3, Array(3) { IntDomain(0, 10) }, arrayOf<Factor>(
            ReifiedLinear(0, intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 6),
        ))
        val selection = FactorProjectionProviders.Default.prepare(problem)
        var lower = 0L
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain =
                problem.finiteIntDomain(varId).withMinAtLeast(if (varId == 0) lower else 0L)
            override fun boolValue(varId: Int): Boolean? = null
        }
        repeat(3) {
            LpEmissionCache(CpToLpRelaxation(problem, objective, projections = selection,
                retainImportedDefinitions = true)).refresh(domains, 0)
        }
        lower = 3L
        val sink = LpStatsSink()
        val consumer = CpToLpRelaxation(problem, objective, projections = selection,
            templateStats = sink, retainImportedDefinitions = true)

        LpEmissionCache(consumer).refresh(domains, 0)

        assertNull(sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE").outcomes["EMISSION_FRAGMENTS_REUSED"])
    }

    @Test
    fun `post debit cancellation should withhold a cached fragment and permit a fresh token`() {
        repeat(3) {
            LpEmissionCache(CpToLpRelaxation(source, objective, projections = selected,
                retainImportedDefinitions = true)).refresh(RootDomains(source), 0)
        }
        var cancelled = false
        val token = Cancellation { cancelled }
        val interrupted = CpToLpRelaxation(source, objective, projections = selected,
            retainImportedDefinitions = true, onTemplateWork = { cancelled = it > 0L })

        assertFailsWith<LpAssemblyCancelled> {
            LpEmissionCache(interrupted).refresh(RootDomains(source), 0, token)
        }

        val sink = LpStatsSink()
        val fresh = CpToLpRelaxation(source, objective, projections = selected,
            templateStats = sink, retainImportedDefinitions = true)
        LpEmissionCache(fresh).refresh(RootDomains(source), 0)
        assertEquals(1L, sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE")
            .outcomes["EMISSION_FRAGMENTS_REUSED"])
    }

    @Test
    fun `post debit cancellation should withhold a completed source definition publication`() {
        val definition = LpEmissionSourceDefinition()
        val model = LpRetainedSources.emptyModel()
        var cancelled = false

        val published = definition.publish(model, Cancellation { cancelled }) { cancelled = true }

        assertFalse(published)
        assertNull(definition.read(Cancellation.Never) {})
        assertTrue(definition.publish(model, Cancellation.Never) {})
        assertSame(model, definition.read(Cancellation.Never) {})
    }

    @Test
    fun `post debit cancellation should withhold a completed source definition borrow`() {
        val definition = LpEmissionSourceDefinition()
        val model = LpRetainedSources.emptyModel()
        assertTrue(definition.publish(model, Cancellation.Never) {})
        var cancelled = false

        val borrowed = definition.read(Cancellation { cancelled }) { cancelled = true }

        assertNull(borrowed)
        assertSame(model, definition.read(Cancellation.Never) {})
    }

    @Test
    fun `a completed source definition exceeding its reservation should remain unpublished`() {
        val definition = LpEmissionSourceDefinition()
        val model = assertNotNull(CpToLpRelaxation(source, objective).build(RootDomains(source))
            .model.authoritativeModel())

        val published = definition.publish(model, Cancellation.Never) {}

        assertFalse(published)
        assertNull(definition.read(Cancellation.Never) {})
    }

    @Test
    fun `current Boolean restrictions should remain private when a reified fragment is shared`() {
        val problem = Problem(1, 3, Array(3) { IntDomain(0, 10) }, arrayOf<Factor>(
            ReifiedLinear(0, intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 6),
        ))
        val selection = FactorProjectionProviders.Default.prepare(problem)
        var pin: Boolean? = null
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = problem.finiteIntDomain(varId)
            override fun boolValue(varId: Int): Boolean? = pin
        }
        repeat(3) {
            LpEmissionCache(CpToLpRelaxation(problem, objective, projections = selection,
                retainImportedDefinitions = true)).refresh(domains, 0)
        }
        val sink = LpStatsSink()
        for (value in listOf(true, false)) {
            pin = value
            val consumer = CpToLpRelaxation(problem, objective, projections = selection,
                templateStats = sink, retainImportedDefinitions = true)
            val sources = LpRetainedSources(problem, consumer)
            LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
                val edit = assertNotNull(sources.prepare(owner.state, domains))
                assertTrue(owner.replaceRows(edit.retired, edit.columns, edit.rows, false,
                    objective = edit.objective, assertions = assertNotNull(edit.boundAssertions(0L))))
                edit.commit()

                assertEquals(BigFraction.ofLong(if (value) 6L else 0L), assertNotNull(owner.solve()).lowerBound)
            }
        }

        assertEquals(2L, sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE")
            .outcomes["EMISSION_FRAGMENTS_REUSED"])
        assertEquals(1L, sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE")
            .outcomes["EMISSION_IMPORT_MODELS_REUSED"])
    }

    @Test
    fun `hole sensitive unary equality should keep ordinary construction`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 2).excludeValue(1)), arrayOf<Factor>(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 2),
        ))
        val selection = FactorProjectionProviders.Default.prepare(problem)
        val cost = LinearObjective(intCoefficients = longArrayOf(1L))
        repeat(3) {
            LpEmissionCache(CpToLpRelaxation(problem, cost, projections = selection,
                retainImportedDefinitions = true)).refresh(RootDomains(problem), 0)
        }
        val changed = Problem(1, 1, arrayOf(IntDomain(0, 2)), problem.factors)
        val domains = object : RelaxationDomains {
            override fun intDomain(varId: Int): IntDomain = IntDomain(1, 1)
            override fun boolValue(varId: Int): Boolean = false
        }
        val consumer = CpToLpRelaxation(changed, cost, projections = selection.forEngineView(changed),
            retainImportedDefinitions = true)
        val sources = LpRetainedSources(changed, consumer)

        LpScopedSolver(LpExactState(LpRetainedSources.emptyModel())).use { owner ->
            val edit = assertNotNull(sources.prepare(owner.state, domains))
            assertTrue(owner.replaceRows(edit.retired, edit.columns, edit.rows, false,
                objective = edit.objective, assertions = assertNotNull(edit.boundAssertions(0L))))
            edit.commit()

            assertEquals(BigFraction.ONE, assertNotNull(owner.solve()).lowerBound)
        }
        assertEquals(0L, selection.lpEmissionTemplateUnits)
    }

    @Test
    fun `fragment admission should stop at the shared storage ceiling`() {
        val variables = 96
        val factors = 64
        val problem = Problem(0, variables, Array(variables) { IntDomain(0, 10) }, Array<Factor>(factors) {
            Linear(IntArray(variables) { 1 }, IntArray(variables) { it }, LinearOp.GE, 6)
        })
        val selection = FactorProjectionProviders.Default.prepare(problem)
        repeat(2) {
            LpEmissionCache(CpToLpRelaxation(problem, null, projections = selection,
                retainImportedDefinitions = true)).refresh(RootDomains(problem), 0)
        }
        val sink = LpStatsSink()
        val consumer = CpToLpRelaxation(problem, null, projections = selection,
            templateStats = sink, retainImportedDefinitions = true)

        LpEmissionCache(consumer).refresh(RootDomains(problem), 0)

        assertTrue(selection.lpEmissionTemplateUnits in 1L..LpEmissionTemplates.MAX_UNITS)
        assertTrue(sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE")
            .outcomes.getValue("EMISSION_FRAGMENTS_REUSED") in 1L until factors.toLong())
    }

    @Test
    fun `earned fragment demand should replace ordinary complete metadata`() {
        assertNotNull(selected.prepareLpTemplates(objective, Cancellation.Never))
        val sink = LpStatsSink()
        repeat(3) {
            LpEmissionCache(CpToLpRelaxation(source, objective, projections = selected,
                templateStats = sink, retainImportedDefinitions = true)).refresh(RootDomains(source), 0)
        }

        assertTrue(selected.lpEmissionTemplateUnits in 1L..LpEmissionTemplates.MAX_UNITS)
        assertEquals(1L, sink.snapshot().phases.getValue("TEMPLATE_ASSEMBLY_NODE")
            .outcomes["EMISSION_FRAGMENTS_REUSED"])
        assertNull(selected.prepareLpTemplates(objective, Cancellation.Never))
    }

    @Test
    fun `completed metadata and fragment indexes should occupy alternative publication slots`() {
        assertNotNull(selected.prepareLpTemplates(objective, Cancellation.Never, reuseMetadata = true))
        repeat(3) {
            LpEmissionCache(CpToLpRelaxation(source, objective, projections = selected,
                retainImportedDefinitions = true)).refresh(RootDomains(source), 0)
        }

        assertEquals(0L, selected.lpEmissionTemplateUnits)
        assertNotNull(selected.prepareLpTemplates(objective, Cancellation.Never, reuseMetadata = true))
    }
}
