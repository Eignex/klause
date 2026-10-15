package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.solver.result.HintIndexOutcome
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchCoordinateOwnership
import com.eignex.klause.solver.search.SearchInvocationResources
import com.eignex.klause.solver.search.SearchLearnedDbParams
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SourceCoordinateAgreement
import com.eignex.klause.solver.search.SourceCoordinateAgreementComponent
import com.eignex.klause.solver.search.TheoryComponent
import com.eignex.klause.theory.qflra.ExactLiraSearchComponent
import com.eignex.klause.util.Cancellation

internal class SearchComponentOpening(
    val cancellation: Cancellation,
    val smtStats: SmtStatsSink?,
    val theorySolveStop: Cancellation?,
    private val resources: SearchInvocationResources,
    val openHintFlips: Long? = null,
) {
    private var primitiveHints: Pair<IntArray, SearchCandidateHints>? = null
    private var releaseHints: (() -> Unit)? = null
    private var pointAvailable: (() -> Boolean)? = null
    private var knownBool: (Int) -> Boolean? = { null }
    private var primitiveHintFlips: Long? = null

    fun registerCandidateHints(
        variables: IntArray,
        hints: SearchCandidateHints,
        release: () -> Unit,
        available: (() -> Boolean)? = null,
        knownBool: (Int) -> Boolean? = { null },
        openHintFlips: Long? = null,
    ) {
        check(primitiveHints == null) { "candidate hints already have an owner" }
        primitiveHints = variables to hints
        releaseHints = release
        pointAvailable = available
        this.knownBool = knownBool
        primitiveHintFlips = openHintFlips
        own(AutoCloseable { release() })
    }

    fun candidateHints(source: Problem, defaultComponent: TheoryComponent? = null): SearchCandidateHints {
        var accounted = false
        fun outcome(value: HintIndexOutcome) {
            accounted = true
            smtStats?.hintUsage?.index(value)
        }
        try {
            val registered = primitiveHints
            if (registered == null) {
                val direct = if (openHintFlips == null) {
                    (defaultComponent as? ExactLiraSearchComponent)?.partialPointHints(cancellation)?.second
                } else null
                if (direct == null) outcome(HintIndexOutcome.NO_PROVIDER) else accounted = true
                return direct ?: SearchCandidateHints.None
            }
            val (variables, hints) = registered
            if (source.factors.size > SourceConjunctionHints.MAX_INDEX_ENTRIES) {
                outcome(HintIndexOutcome.SOURCE_LIMIT)
                releaseHints?.invoke()
                return SearchCandidateHints.None
            }
            if (cancellation()) {
                outcome(HintIndexOutcome.STOPPED)
                releaseHints?.invoke()
                return SearchCandidateHints.None
            }
            val clauses = ArrayList<Clause>()
            for (factor in source.factors) {
                cancellation.charge(1)
                if (cancellation()) {
                    outcome(HintIndexOutcome.STOPPED)
                    releaseHints?.invoke()
                    return SearchCandidateHints.None
                }
                if (factor is Clause) clauses += factor
            }
            accounted = true
            val compiled = SourceConjunctionHints.create(clauses, source.numBoolVars, variables, hints, cancellation,
                usage = smtStats?.hintUsage, available = pointAvailable, knownBool = knownBool)
            if (compiled != null) {
                own(compiled)
                return if (primitiveHintFlips == 0L) SearchCandidateHints.None else compiled
            }
            releaseHints?.invoke()
            return SearchCandidateHints.None
        } finally {
            if (!accounted) smtStats?.hintUsage?.index(HintIndexOutcome.ABORTED)
        }
    }

    fun <T : AutoCloseable> own(resource: T): T = resources.own(resource)

    fun ownComponent(component: SearchComponent) = resources.ownComponent(component)
}

internal class PreparedSearchRegistration(
    private val source: Problem,
    val descriptor: ExecutionComponentDescriptor,
    private val factory: (SearchComponentOpening) -> List<SearchComponent>,
) {
    fun requireSource(model: Problem) {
        require(model === source) { "component registration belongs to a different source model" }
    }

    fun open(opening: SearchComponentOpening): List<SearchComponent> = factory(opening).toList().also { components ->
        components.forEach(opening::ownComponent)
    }
}

internal class PreparedSearchRegistrations(
    private val source: Problem,
    registrations: List<PreparedSearchRegistration>,
) {
    private val registrations = registrations.toList()
    private val boolCount = source.numBoolVars

    init {
        this.registrations.forEach { it.requireSource(source) }
        val descriptors = this.registrations.map { it.descriptor }
        requireOwnership(descriptors.flatMap { it.factorIndices }, source.factors.size, "factor")
        require(descriptors.flatMap { it.participatingFactorIndices }.all { it in source.factors.indices }) {
            "factor participation is outside the source model"
        }
        requireOwnership(descriptors.flatMap { it.booleanCoordinates }, source.numBoolVars, "Boolean")
        requireOwnership(descriptors.flatMap { it.integerCoordinates }, source.numIntVars, "integer")
        requireOwnership(descriptors.flatMap { it.realCoordinates }, source.numRealVars, "real")
    }

    @Suppress("TooGenericExceptionCaught") // Invocation cleanup preserves an arbitrary primary failure.
    fun open(
        maxChecks: Long,
        cancellation: Cancellation,
        learnedDb: SearchLearnedDbParams,
        smtStats: SmtStatsSink?,
        theorySolveStop: Cancellation?,
        openHintFlips: Long? = null,
    ): PlannedSearch {
        val resources = SearchInvocationResources()
        val opening = SearchComponentOpening(cancellation, smtStats, theorySolveStop, resources, openHintFlips)
        try {
            val groups = registrations.map { it.open(opening) }
            val components = groups.flatten()
            val arithmetic = registrations.indices.filter {
                registrations[it].descriptor.responsibility == ComponentResponsibility.COMPLETION
            }
            lateinit var session: SearchSession
            val installed = if (arithmetic.size > 1) {
                val participants = arithmetic.flatMap { groups[it].filterIsInstance<SourceCoordinateAgreement>() }
                val supported = arithmetic.all { index ->
                    groups[index].isNotEmpty() && groups[index].all { it is SourceCoordinateAgreement }
                }
                components + SourceCoordinateAgreementComponent(participants, { session.model() }, supported)
            } else components
            session = SearchComponentSet(installed).session(
                maxChecks, cancellation, learnedDb, SearchAtomRegistry(boolCount),
            )
            if (arithmetic.size > 1) {
                val owners = registrations.indices.flatMap { index ->
                    val descriptor = registrations[index].descriptor
                    val ownership = SearchCoordinateOwnership(descriptor.integerCoordinates, descriptor.realCoordinates)
                    groups[index].map { it to ownership }
                }.toMap()
                session.restrictCoordinateContributions(owners)
            }
            val cp = components.filterIsInstance<CpSearchComponent>().firstOrNull()
            val theory = components.filterIsInstance<TheoryComponent>().firstOrNull()
            return PlannedSearch(
                session,
                cp,
                theory,
                resources,
                opening.candidateHints(source, theory.takeIf {
                    arithmetic.size == 1 && source.numRealVars > 0
                }),
            )
        } catch (failure: Throwable) {
            resources.closeAfter(failure)
            throw failure
        }
    }

    private fun requireOwnership(indices: List<Int>, size: Int, kind: String) {
        require(indices.all { it in 0 until size }) { "$kind ownership is outside the source model" }
        require(indices.distinct().size == indices.size) { "a source $kind has multiple component owners" }
    }
}
