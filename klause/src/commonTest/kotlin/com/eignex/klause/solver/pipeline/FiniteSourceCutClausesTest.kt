package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpFunding
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.lp.engine.CutFamily
import com.eignex.klause.lp.optimization.LinearAuthority
import com.eignex.klause.lp.optimization.LinearAuthorityCut
import com.eignex.klause.lp.optimization.atScope
import com.eignex.klause.portfolio.Contribution
import com.eignex.klause.portfolio.ContributionTally
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PoolClauseExchange
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.portfolio.SharedClausePool
import com.eignex.klause.portfolio.SharedPools
import com.eignex.klause.presolve.SourceMapping
import com.eignex.klause.presolve.TransformationGuarantees
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.propagation.addLearnedClause
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ProblemProfile
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FiniteSourceCutClausesTest : RuntimeInitializedTest() {
    @Test
    fun `a root contradictory cut should withhold an empty nogood`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, -1)))
        val model = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val binding = assertNotNull(FiniteSourceCutBinding.admit(LinearToleranceAuthority(source, source, objective),
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), model, objective, ExecutionControl.Never))
        val context = assertNotNull(binding.capture(model, objective, ExecutionControl.Never))
        val row = context.premises.entries.single { it.value.inequality.rhs == BigFraction.MINUS_ONE }
        val cut = SourceEvidence(context.scope, SourceClaim.Consequence(row.value.inequality),
            SourceDerivation.Premise(row.key))
        val pool = SharedClausePool()
        val funding = SourceLpFunding(1_000_000, 4_000_000).also { it.grant(1_000_000) }

        FiniteSourceCutClauses(binding, model, context, SourceLpBudget(funding = funding),
            ExecutionControl.Never, pool, 0).publish(listOf(cut))

        assertTrue(pool.drainSince(0).clauses.isEmpty())
    }

    @Test
    fun `wide binary covers should reach finite publication within the allowance`() {
        for (positive in listOf(false, true)) {
            val source = Problem(0, 8, Array(8) { IntDomain(0, 1) }, arrayOf(
                Linear(IntArray(8) { 1 }, IntArray(8) { it },
                    if (positive) LinearOp.LE else LinearOp.GE, if (positive) 0 else 1)))
            val model = source.bake()
            val objective = LinearObjective(intCoefficients = LongArray(8) { 1 })
            val binding = assertNotNull(FiniteSourceCutBinding.admit(
                LinearToleranceAuthority(source, source, objective),
                SourceMapping(source, source, TransformationGuarantees.IDENTITY), model, objective,
                ExecutionControl.Never))
            val context = assertNotNull(binding.capture(model, objective, ExecutionControl.Never))
            val row = context.premises.entries.single { it.value.inequality.supportSize == 8 }
            val cut = SourceEvidence(context.scope, SourceClaim.Consequence(row.value.inequality),
                SourceDerivation.Premise(row.key))
            val pool = SharedClausePool()
            val funding = SourceLpFunding(1_000_000, 4_000_000).also { it.grant(1_000_000) }

            val observations = FiniteSourceCutClauses(binding, model, context, SourceLpBudget(funding = funding),
                ExecutionControl.Never, pool, 0).publish(listOf(cut))

            assertEquals(if (positive) 8L else 1L, observations["SourceCutClausePublished"])
            val consumer = PropagationSession(Problem(0, 8, Array(8) { IntDomain(0, 1) }, emptyArray()))
            PoolClauseExchange(pool, origin = 1).onSearchStart(consumer)
            if (positive) {
                consumer.pinIntAtLeast(0, 0)
                assertTrue((0..7).all { consumer.intDomain(it).max == 0L })
            } else {
                for (id in 0..6) assertFalse(consumer.pinInt(id, 0) is PropagationResult.Unsat)
                assertTrue(consumer.pinInt(7, 0) is PropagationResult.Unsat)
            }
            assertTrue(funding.spent in 1L..65_536L)
            assertTrue(funding.allocated in 1L..1_048_576L)
        }
    }

    @Test
    fun `proved covers should preserve different cut coordinate supports`() {
        val source = Problem(0, 4, Array(4) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 1),
            Linear(intArrayOf(1, 1), intArrayOf(2, 3), LinearOp.GE, 1)))
        val model = source.bake()
        val objective = LinearObjective(intCoefficients = LongArray(4) { 1 })
        val binding = assertNotNull(FiniteSourceCutBinding.admit(LinearToleranceAuthority(source, source, objective),
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), model, objective, ExecutionControl.Never))
        val context = assertNotNull(binding.capture(model, objective, ExecutionControl.Never))
        val cuts = context.premises.entries.filter { it.value.inequality.supportSize == 2 }.map {
            SourceEvidence(context.scope, SourceClaim.Consequence(it.value.inequality),
                SourceDerivation.Premise(it.key))
        }
        val pool = SharedClausePool()
        val funding = SourceLpFunding(1_000_000, 4_000_000).also { it.grant(1_000_000) }

        val observations = FiniteSourceCutClauses(binding, model, context, SourceLpBudget(funding = funding),
            ExecutionControl.Never, pool, 0).publish(cuts)

        assertEquals(2L, observations["SourceCutClausePublished"])
        for (pair in 0..1) {
            val consumer = PropagationSession(Problem(0, 4, Array(4) { IntDomain(0, 1) }, emptyArray()))
            PoolClauseExchange(pool, origin = 1).onSearchStart(consumer)
            assertFalse(consumer.pinInt(pair * 2, 0) is PropagationResult.Unsat)
            assertTrue(consumer.pinInt(pair * 2 + 1, 0) is PropagationResult.Unsat)
        }
    }

    @Test
    fun `missing or disabled sharing should preserve the numerical result`() {
        for ((sharing, cuts) in listOf(true to true, false to true, true to false)) {
            val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))
            val model = source.bake()
            val objective = LinearObjective(intCoefficients = longArrayOf(1))
            val authority = LinearToleranceAuthority(source, source, objective)
            val binding = assertNotNull(FiniteSourceCutBinding.admit(authority,
                SourceMapping(source, source, TransformationGuarantees.IDENTITY), model, objective,
                ExecutionControl.Never))
            val params = TheoryParams(sourceCuts = cuts, sourcePointCuts = true, sourceAggregateCuts = true)
            val cost = finiteNumericalCompanionEligibility(model, PortfolioScenario.sequential(Kind.COP),
                objective, params).requirements.construction
            val companions = assertNotNull(FiniteNumericalCompanions.prepare(model, objective, params,
                ExecutionControl.Never, cost, toleranceAuthority = authority, cutBinding = binding).companions)
            val plan = model.prepareExecutionPlan(objective, preferFinite = true)
            val finite = PortfolioWorker.ofSolve("finite", 1) { _, _ -> error("unused finite worker") }
            finite.sharedPools = SharedPools(if (sharing) SharedClausePool() else null, null,
                identity = ModelIdentity.of(model, objective))
            var result: MinimizeResult? = null

            finite.use {
                PortfolioInvocationResources(2, 1, 0, ExecutionControl.Never, null).use { resources ->
                    companions.factories(plan, 0).first().materialize(plan, resources.slots[0]).use { worker ->
                        companions.bindCutPool(listOf(worker, finite))
                        assertNotNull(worker.newResumableSearch { Double.POSITIVE_INFINITY }).use { search ->
                            repeat(8) {
                                if (!search.isDone) result = search.runSlice(ExecutionControl.Never, 100, 5_000) { }
                            }
                            val stages = search.stats.smt.completeSearchStages.orEmpty()
                            assertEquals(sharing && cuts, stages.keys.any { it.contains("SourceCutClause") })
                        }
                    }
                }
            }

            assertEquals(0.0, assertIs<MinimizeResult.Optimal>(result).objective)
        }
    }

    @Test
    fun `a full pool should compact or defer within the producer allowance`() {
        val source = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 1)))
        val model = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val binding = assertNotNull(FiniteSourceCutBinding.admit(LinearToleranceAuthority(source, source, objective),
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), model, objective, ExecutionControl.Never))
        val context = assertNotNull(binding.capture(model, objective, ExecutionControl.Never))
        val row = context.premises.entries.single { it.value.inequality.supportSize == 2 }
        val cut = SourceEvidence(context.scope, SourceClaim.Consequence(row.value.inequality),
            SourceDerivation.Premise(row.key))
        for (existing in listOf(1, 256)) {
            val pool = SharedClausePool(cap = existing)
            val initial = List(existing) { id ->
                SharedClause(IntArray(0), longArrayOf(0, 0, -id.toLong(), 0), 1)
            }
            assertEquals(existing, pool.publish(initial, origin = 42, isGlobal = true))
            val funding = SourceLpFunding(1_000_000, 4_000_000).also { it.grant(1_000_000) }

            val observations = FiniteSourceCutClauses(binding, model, context, SourceLpBudget(funding = funding),
                ExecutionControl.Never, pool, 0).publish(listOf(cut))

            assertEquals(1L, observations["SourceCutClauseProved"])
            val drained = pool.drainSince(0)
            if (existing == 1) {
                assertEquals(1L, observations["SourceCutClausePublished"])
                assertEquals(listOf(0), drained.origins.toList())
                assertEquals(listOf(0L, 1L, 0L, 0L, 1L, 1L, 0L, 0L), drained.clauses.single().atomQuads.toList())
                assertEquals(1L, pool.memory()["poolCompactions"])
            } else {
                assertEquals(0L, observations["SourceCutClausePublished"])
                assertEquals(initial.map { it.key }, drained.clauses.map { it.key })
                assertTrue(drained.origins.all { it == 42 })
                assertTrue(assertNotNull(pool.memory()["poolReclamationDeferred"]) > 0L)
            }
            assertEquals(funding.spent, observations["SourceCutClauseWork"])
            assertEquals(funding.allocated, observations["SourceCutClauseModeledAllocation"])
            assertTrue(funding.spent in 1L..65_536L)
            assertTrue(funding.allocated in 1L..1_048_576L)
        }
    }

    @Test
    fun `companion insertion should keep finite sharing origins and recipe seeds`() {
        val model = Problem(0, 2, Array(2) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 1))).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1))
        val seeds = mutableListOf<Long>()
        val recipes = listOf("first", "second").map { label ->
            BacktrackRecipe(label) { seed, events ->
                seeds += seed
                BacktrackParams(randomSeed = seed, lpConfig = LpConfig.OFF, onEvent = events)
            }
        }
        val scenario = PortfolioScenario(cores = 1, arms = 2, kind = Kind.COP, engine = EngineMix.BACKTRACK,
            btPool = recipes.map { recipe -> { recipe } })
        val factories = PortfolioBuilder.factories(model, scenario, objective, null, null, null,
            ProblemProfile.of(model, true), numericalCompletion = false,
            onSession = { label, session ->
                val positive = label == "bt/first"
                session.addLearnedClause(Clause(IntArray(2) { id ->
                    if (positive) session.boundGeLit(id, 1, true) else session.boundLeLit(id, 0, true)
                }), 1)
            })
        val origins = mutableSetOf<Int>()
        PortfolioInvocationResources(3, 1, 17, ExecutionControl.Never, null).use { resources ->
            factories.forEachIndexed { id, factory ->
                factory.materialize(resources.slots[id + 1], 1).use { worker ->
                    assertNotNull(worker.newResumableSearch { Double.POSITIVE_INFINITY }).use { search ->
                        search.runSlice(ExecutionControl.Never, 100, 5_000) { }
                    }
                    origins += assertNotNull(worker.sharedPools?.clauses).drainSince(0).origins.toSet()
                }
            }
        }

        assertEquals(listOf(1017L, 1018L), seeds)
        assertEquals(setOf(1, 2), origins)
    }

    @Test
    fun `root cut publication should survive producer retirement and credit finite use`() {
        val factors = listOf(
            Linear(intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, -0.5),
            Linear(intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.LE, -0.5),
        ) + (0 until 3).map { id ->
            Linear(intArrayOf(-1, -1), intArrayOf(id + 1, (id + 1) % 3 + 1), LinearOp.LE, -1)
        }
        val source = Problem(0, 4, Array(4) { IntDomain(0, 1) }, factors.toTypedArray(),
            numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val model = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 1, 1, 1))
        val binding = assertNotNull(FiniteSourceCutBinding.admit(LinearToleranceAuthority(source, source, objective),
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), model, objective, ExecutionControl.Never))
        val context = assertNotNull(binding.capture(model, objective, ExecutionControl.Never))
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val premises = context.premises.entries.filter {
            it.value.inequality.supportSize == 2 && it.value.inequality.coefficient(real) != null
        }
        val proof = SourceDerivation.IntegerRounding(SourceDerivation.Combination(premises.map {
            SourceDerivation.Combination.Term(BigFraction.ofLong(2).reciprocal(), SourceDerivation.Premise(it.key))
        }))
        val owners = premises.mapTo(HashSet()) { context.premises.keys.indexOf(it.key) }
        val cut = LinearAuthorityCut(owners.first(), SourceInequality(
            mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE), proof,
            owners = owners, family = CutFamily.SOURCE_AGGREGATION, conditionalCandidate = true)
        val pool = SharedClausePool()
        var callbacks = 0
        var published = 0L
        val funding = SourceLpFunding(10_000_000, 8_000_000)
        val authority = LinearAuthority(SourceLpBudget(funding = funding), retainLp = true)
        authority.useReplayCuts(listOf(cut))
        LinearAuthorityInvocation(context, TheoryParams(sourcePointCuts = true, sourceAggregateCuts = true),
            borrowedAuthority = authority, boundOrdered = true, onRootConditionalCuts = { cuts, budget, stop ->
                callbacks++
                FiniteSourceCutClauses(binding, model, context, budget, stop, pool, 0).publish(cuts).also {
                    published += it["SourceCutClausePublished"] ?: 0L
                }
            }).use { producer ->
            producer.runSlice(ExecutionControl { published > 0 }, 100, 5_000) { _, _ -> }
            producer.runSlice(ExecutionControl.Never, 1, 1) { _, _ -> }
        }
        authority.close()
        assertEquals(1, callbacks)
        assertTrue(published > 0L)
        val consumer = PropagationSession(model)
        val tally = ContributionTally()
        val exchange = PoolClauseExchange(pool, origin = 1, tally = tally)

        exchange.onSearchStart(consumer)
        consumer.pinIntAtLeast(0, 0)
        exchange.onSearchEnd(consumer)

        assertEquals(1L, consumer.intDomain(0).min)
        var uses = 0.0
        tally.drain { kind, origin, amount ->
            if (kind == Contribution.Clause && origin == 0) uses += amount
        }
        assertTrue(uses > 0.0)
    }

    @Test
    fun `portable nogoods should accept exactly the binary cut assignments`() {
        val source = Problem(0, 3, Array(3) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(2, -3, 1), intArrayOf(0, 1, 2), LinearOp.LE, 0)))
        val model = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val binding = assertNotNull(FiniteSourceCutBinding.admit(LinearToleranceAuthority(source, source, objective),
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), model, objective, ExecutionControl.Never))
        val context = assertNotNull(binding.capture(model, objective, ExecutionControl.Never))
        val row = SourceInequality((0..2).associate {
            SourceCoordinate(SourceCoordinate.Kind.Integer, it) to BigFraction.ofLong(longArrayOf(2, -3, 1)[it])
        }, BigFraction.ZERO)
        val premise = context.premises.entries.single { it.value.inequality == row }.key
        val cut = SourceEvidence(context.scope, SourceClaim.Consequence(row), SourceDerivation.Premise(premise))
        val pool = SharedClausePool()
        val funding = SourceLpFunding(1_000_000, 4_000_000).also { it.grant(1_000_000) }

        val observations = FiniteSourceCutClauses(binding, model, context, SourceLpBudget(funding = funding),
            ExecutionControl.Never, pool, 0).publish(listOf(cut))

        assertEquals(2L, observations["SourceCutClausePublished"])
        for (bits in 0..7) {
            val values = LongArray(3) { if (bits and (1 shl it) == 0) 0L else 1L }
            val consumer = PropagationSession(Problem(0, 3, Array(3) { IntDomain(0, 1) }, emptyArray()))
            PoolClauseExchange(pool, origin = 1).onSearchStart(consumer)
            var refuted = false
            for (id in values.indices) {
                if (!refuted) refuted = consumer.pinInt(id, values[id]) is PropagationResult.Unsat
            }
            assertEquals(2 * values[0] - 3 * values[1] + values[2] > 0, refuted)
        }
        assertTrue(funding.spent > 0)
        assertTrue(funding.allocated <= 1_048_576L)
    }

    @Test
    fun `a freshly proved cut nogood should propagate in another finite worker`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(
            Linear(intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, -0.5),
            Linear(intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.LE, -0.5)),
            numRealVars = 1, realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0))
        val model = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val binding = assertNotNull(FiniteSourceCutBinding.admit(LinearToleranceAuthority(source, source, objective),
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), model, objective, ExecutionControl.Never))
        val context = assertNotNull(binding.capture(model, objective, ExecutionControl.Never))
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val premises = context.premises.filterValues { it.inequality.supportSize == 2 }.keys
        val proof = SourceDerivation.IntegerRounding(SourceDerivation.Combination(premises.map {
            SourceDerivation.Combination.Term(BigFraction.ofLong(2).reciprocal(), SourceDerivation.Premise(it))
        }))
        val cut = SourceEvidence(context.scope, SourceClaim.Consequence(
            SourceInequality(mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)), proof)
        val pool = SharedClausePool()
        val funding = SourceLpFunding(1_000_000, 4_000_000).also { it.grant(1_000_000) }
        val producer = FiniteSourceCutClauses(binding, model, context, SourceLpBudget(funding = funding),
            ExecutionControl.Never, pool, 0)
        assertEquals(1L, producer.publish(listOf(cut))["SourceCutClausePublished"])
        val consumer = PropagationSession(model)
        assertEquals(0L, consumer.intDomain(0).min)
        val tally = ContributionTally()
        val exchange = PoolClauseExchange(pool, origin = 1, tally = tally)

        exchange.onSearchStart(consumer)
        consumer.pinIntAtLeast(0, 0)
        exchange.onSearchEnd(consumer)

        assertEquals(1L, consumer.intDomain(0).min)
        var uses = 0.0
        tally.drain { kind, origin, amount ->
            if (kind == Contribution.Clause && origin == 0) uses += amount
        }
        assertTrue(uses > 0.0)
    }

    @Test
    fun `foreign or restricted contexts should not publish clauses`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val model = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val binding = assertNotNull(FiniteSourceCutBinding.admit(LinearToleranceAuthority(source, source, objective),
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), model, objective, ExecutionControl.Never))
        val context = assertNotNull(binding.capture(model, objective, ExecutionControl.Never))
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        for (condition in listOf(SourceCondition(), SourceCondition(upper = mapOf(coordinate to BigFraction.ZERO)))) {
            val foreign = context.atScope(context.scope.copy(condition = condition))
            val pool = SharedClausePool()
            val funding = SourceLpFunding(1_000_000, 4_000_000).also { it.grant(1_000_000) }

            val result = FiniteSourceCutClauses(binding, model, foreign, SourceLpBudget(funding = funding),
                ExecutionControl.Never, pool, 0).publish(emptyList())

            assertTrue(result.isEmpty())
            assertTrue(pool.drainSince(0).clauses.isEmpty())
        }
        assertFalse(binding.matches(source.bake(), context))
    }

    @Test
    fun `foreign mapping or rewritten factors should not admit cut sharing`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), arrayOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)))
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val authority = LinearToleranceAuthority(source, source, objective)
        val foreign = source.withFactors(emptyArray())

        assertNull(FiniteSourceCutBinding.admit(authority,
            SourceMapping(foreign, foreign, TransformationGuarantees.IDENTITY), source.bake(), objective,
            ExecutionControl.Never))
        assertNull(FiniteSourceCutBinding.admit(authority,
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), foreign.bake(), objective,
            ExecutionControl.Never))
    }

    @Test
    fun `a forged cut or exhausted allowance should publish no clause`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())
        val model = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val binding = assertNotNull(FiniteSourceCutBinding.admit(LinearToleranceAuthority(source, source, objective),
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), model, objective, ExecutionControl.Never))
        val context = assertNotNull(binding.capture(model, objective, ExecutionControl.Never))
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val forged = SourceEvidence(context.scope, SourceClaim.Consequence(
            SourceInequality(mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE)),
            SourceDerivation.Premise("int/0/lower"))
        for ((allowance, cancelled) in listOf(1_000_000L to false, 0L to false, 1_000_000L to true)) {
            val pool = SharedClausePool()
            val funding = SourceLpFunding(allowance, 4_000_000).also { it.grant(allowance) }

            FiniteSourceCutClauses(binding, model, context, SourceLpBudget(funding = funding),
                ExecutionControl { cancelled }, pool, 0).publish(listOf(forged))

            assertTrue(pool.drainSince(0).clauses.isEmpty())
        }
    }

    @Test
    fun `a stopped partial batch should retain only completed checked clauses`() {
        val source = Problem(0, 3, Array(3) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 1)))
        val model = source.bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1))
        val binding = assertNotNull(FiniteSourceCutBinding.admit(LinearToleranceAuthority(source, source, objective),
            SourceMapping(source, source, TransformationGuarantees.IDENTITY), model, objective, ExecutionControl.Never))
        val context = assertNotNull(binding.capture(model, objective, ExecutionControl.Never))
        val row = context.premises.entries.single { it.value.inequality.supportSize == 3 }
        val cut = SourceEvidence(context.scope, SourceClaim.Consequence(row.value.inequality),
            SourceDerivation.Premise(row.key))
        for (cancelled in listOf(false, true)) {
            val pool = SharedClausePool()
            val funding = SourceLpFunding(1_000_000, 4_000_000).also { it.grant(1_000_000) }
            val stop = ExecutionControl {
                val firstPublished = pool.drainSince(0).clauses.isNotEmpty()
                if (!cancelled && firstPublished) funding.grant(0L)
                cancelled && firstPublished
            }

            val observations = FiniteSourceCutClauses(binding, model, context, SourceLpBudget(funding = funding),
                stop, pool, 0).publish(listOf(cut))

            assertEquals(1L, observations["SourceCutClausePublished"])
            assertEquals(1, pool.drainSince(0).clauses.size)
            assertTrue(funding.spent <= 65_536L)
            assertTrue(funding.allocated <= 1_048_576L)
        }
    }
}
