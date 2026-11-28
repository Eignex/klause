package com.eignex.klause.solver.search

/**
 * Resumable traversal of a [SearchSession].
 *
 * Frames contain public shared decisions only, so CP and integer-theory splits share the same
 * push, propagation, retraction, restart and budget path.
 */
class SearchRun internal constructor(
    private val session: SearchSession,
    private val params: SearchSolveParams,
    private val booleanBranching: BooleanBranching,
    private val decisionBudget: SearchDecisionBudget,
    private val observer: SearchRunObserver,
    private val modelContinuation: SearchModelContinuation,
    private val modelPolicy: SearchModelPolicy,
    private val nodePolicy: SearchNodePolicy,
    private val lifecycle: SearchRunLifecycle,
    private val branchSelection: SearchBranchSelection,
    private val minimumDecisionLevel: Int,
    private val retryLeaves: Boolean = true,
) {
    private val branchObserver = observer as? SearchBranchAttemptObserver
    private val branchSources = if (branchSelection === SearchBranchSelection.Default) {
        null
    } else {
        object : SearchBranchSources {
            override fun booleanAlternatives(): List<SearchDecision>? = booleanBranching.alternatives(session)
            override fun componentAlternatives(kind: SearchBranchKind?): List<SearchDecision>? =
                session.branchAlternatives(kind)
        }
    }
    private val frames = ArrayList<Frame>()
    private var queryRoot = minimumDecisionLevel
    private var decisions = 0L
    private var decisionsSinceRestart = 0L
    private var sawIndeterminate = false
    private var indeterminateCause: SearchStopCause? = null

    // Whether the last [applyLearnedConflict] call reached its backjump, so the conflict that produced it is spent.
    private var learnedApplied = false
    private var resumeAfterSolution = false
    private var lastModel: AssembledSearchModel? = null
    private var pendingModel: AssembledSearchModel? = null
    private var consumedModel = false
    private var terminal: SearchRunEvent? = null
    internal var recordComponentStops = false
    internal var componentStop: SearchComponentStop? = null
        private set
    internal var componentStopReplayed = false
        private set
    private var started = false
    private var budgetInterrupted = false
    private val cancellationPoller = SearchCancellationPoller()
    internal var pauseBeforeDecision: () -> Boolean = { false }
    private var pendingAdvance = false
    private var surfaceSkip = false
    private var pendingCancellation = false
    private var pendingCheck = false
    private var retryingAssertion = false
    private var pendingLearnedPropagation: SearchPendingLearnedPropagation? = null
    internal val learnedPropagationPending: Boolean
        get() = pendingLearnedPropagation != null || leafRetries?.propagationPending == true
    private var leafRetries: SearchLeafRetries? = null
    private var retryingLeaves = false
    private var coverageComplete = false
    private var unresolvedModel = false
    private var pendingRetryBlock: AssembledSearchModel? = null
    internal val hasUnresolvedModels: Boolean get() = unresolvedModel || leafRetries?.unresolvedModels == true

    /** Poll on a fixed cadence so a node budget always pauses at the same node. */
    var fixedCancellationCadence: Boolean
        get() = cancellationPoller.fixedCadence
        set(value) {
            cancellationPoller.fixedCadence = value
        }

    init {
        params.restart.beginRun()
    }

    /** Advance until the next model or terminal verdict. */
    fun next(): SearchRunEvent {
        if (!retryingLeaves) {
            val event = advance()
            if (!coverageComplete || leafRetries?.pending != true) return event
            terminal = null
            retryingLeaves = true
        }
        while (true) {
            terminal?.let {
                if (recordComponentStops && it == SearchRunEvent.Indeterminate.Component) componentStopReplayed = true
                return it
            }
            pendingRetryBlock?.let { model -> blockRetryModel(model)?.let { return it } }
            when (val event = checkNotNull(leafRetries).next()) {
                is SearchRunEvent.Satisfied -> return event
                SearchRunEvent.Paused -> if (pendingRetryBlock == null) return event
                SearchRunEvent.Exhausted -> return finish(exhausted())
                SearchRunEvent.Indeterminate.Component -> {
                    rememberIndeterminate()
                    return finish(SearchRunEvent.Indeterminate.Component)
                }
                SearchRunEvent.Indeterminate.Budget, SearchRunEvent.Indeterminate.Cancelled -> return finish(event)
            }
        }
    }

    private fun blockRetryModel(model: AssembledSearchModel): SearchRunEvent? {
        stopBeforeModelBlock()?.let { return it }
        pendingRetryBlock = null
        return when (session.blockModelAtRoot(model)) {
            ComponentResult.Consistent -> {
                checkNotNull(leafRetries).restartSelected()
                consumedModel = true
                lifecycle.onModelBlocked(session).toEvent(SearchComponentStop.Site.ModelBlocked)
            }
            is ComponentResult.Conflict -> finish(exhausted())
            ComponentResult.Indeterminate -> finish(
                SearchRunEvent.Indeterminate.Component, SearchComponentStop.Site.ModelBlock,
            )
        }
    }

    private fun retryModelPolicy(): SearchModelPolicy = object : SearchModelPolicy {
        override fun onModel(model: AssembledSearchModel, context: SearchContext): SearchModelDisposition {
            val disposition = modelPolicy.onModel(model, context)
            if (modelContinuation != SearchModelContinuation.BlockAtRoot ||
                (disposition != SearchModelDisposition.Surface && disposition != SearchModelDisposition.Continue)
            ) return disposition
            pendingRetryBlock = model
            return if (disposition == SearchModelDisposition.Surface) disposition else SearchModelDisposition.Pause
        }
    }

    private fun retainLeaf(model: Boolean = false) {
        if (!retryLeaves) {
            unresolvedModel = unresolvedModel || model
            rememberIndeterminate()
            return
        }
        val retries = leafRetries ?: SearchLeafRetries(
            session, { queryRoot }, { decisions < params.maxDecisions },
            committed = { decision ->
                if (!session.executionControls.recordDecision(decision)) {
                    false
                } else {
                    decisions++
                    observer.onCommit(decision, session.decisionLevel)
                    decisionBudget.consume()
                }
            },
            pause = { pauseBeforeDecision() },
            cancelled = {
                val disposition = lifecycle.onCancellation(session)
                if (disposition != SearchRunDisposition.Pause) rememberIndeterminate()
                disposition.toEvent(SearchComponentStop.Site.ExecutionControl)
                    ?: finish(SearchRunEvent.Indeterminate.Cancelled)
            },
            observer = observer,
            openRun = {
                SearchRun(session, params.copy(maxDecisions = (params.maxDecisions - decisions).coerceAtLeast(0L)),
                    booleanBranching, decisionBudget,
                    object : SearchBranchAttemptObserver, SearchRunObserver by observer {
                        override fun onAttempt(decision: SearchDecision, context: SearchContext) {
                            branchObserver?.onAttempt(decision, context)
                        }

                        override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
                            decisions++
                            observer.onCommit(decision, decisionLevel)
                        }
                    },
                    SearchModelContinuation.Chronological, retryModelPolicy(), nodePolicy,
                    object : SearchRunLifecycle by lifecycle {
                        override fun onStart(context: SearchContext): SearchRunDisposition =
                            SearchRunDisposition.Continue
                    },
                    branchSelection, session.decisionLevel, retryLeaves = false).also {
                    it.pauseBeforeDecision = { pauseBeforeDecision() }
                    it.fixedCancellationCadence = fixedCancellationCadence
                }
            },
        ).also { leafRetries = it }
        if (!retries.retain()) {
            unresolvedModel = unresolvedModel || model
            rememberIndeterminate()
        }
    }

    private fun advance(): SearchRunEvent {
        terminal?.let {
            if (recordComponentStops && it == SearchRunEvent.Indeterminate.Component) componentStopReplayed = true
            return it
        }
        if (!started) {
            started = true
            lifecycle.onStart(session).toEvent(SearchComponentStop.Site.Start)?.let { return it }
        }
        pendingLearnedPropagation?.let { pending ->
            if (session.cancelled()) return pauseLearnedPropagation()
            when (pending.continuePropagation(session)) {
                ComponentResult.Consistent -> pendingLearnedPropagation = null

                is ComponentResult.Conflict -> {
                    pendingLearnedPropagation = null
                    if (!backtrack()) return stopAfterBacktrack()
                }

                ComponentResult.Indeterminate -> return pauseLearnedPropagation()
            }
        }
        if (!pendingCheck) lifecycle.onResume(session).toEvent(SearchComponentStop.Site.Resume)?.let { return it }
        if (retryingAssertion) {
            if (session.cancelled()) return cancelledAssertion()
            retryingAssertion = false
        }
        pendingModel?.let { model ->
            if (session.cancelled()) {
                return lifecycle.onCancellation(session).toEvent(SearchComponentStop.Site.ExecutionControl)
                    ?: finish(SearchRunEvent.Indeterminate.Cancelled)
            }
            cancellationPoller.rearm()
            completeModel(model)?.let { return it }
        }
        if (pendingAdvance) {
            pendingAdvance = false
            if (!backtrack()) return stopAfterBacktrack()
        }
        if (resumeAfterSolution) {
            stopBeforeModelBlock()?.let { return it }
            resumeAfterSolution = false
            when (modelContinuation) {
                SearchModelContinuation.Chronological -> if (!backtrack()) return stopAfterBacktrack()

                SearchModelContinuation.BlockAtRoot -> when (session.blockModelAtRoot(checkNotNull(lastModel))) {
                    ComponentResult.Consistent -> {
                        frames.clear()
                        consumedModel = true
                        lifecycle.onModelBlocked(session).toEvent(SearchComponentStop.Site.ModelBlocked)
                            ?.let { return it }
                    }

                    is ComponentResult.Conflict -> return finish(SearchRunEvent.Exhausted)

                    ComponentResult.Indeterminate -> return finish(
                        SearchRunEvent.Indeterminate.Component, SearchComponentStop.Site.ModelBlock,
                    )
                }
            }
        }
        while (true) {
            if (cancellationPoller.due()) {
                if (session.cancelled()) {
                    return lifecycle.onCancellation(session).toEvent(SearchComponentStop.Site.ExecutionControl)
                        ?: finish(SearchRunEvent.Indeterminate.Cancelled)
                }
                cancellationPoller.rearm()
            }
            when (val node = if (pendingCheck) SearchNodeDisposition.Expand else nodePolicy.beforeBranch(session)) {
                SearchNodeDisposition.Expand -> Unit

                SearchNodeDisposition.Prune -> {
                    observer.onConflict(null)
                    if (!backtrack()) return stopAfterBacktrack()
                    continue
                }

                is SearchNodeDisposition.Backjump -> {
                    observer.onConflict(null)
                    observer.onLearnedNodeBackjump()
                    when (applyLearnedConflict(node.consequence, retainPropagation = true)) {
                        SearchLearnedConflictResult.Resume -> continue

                        SearchLearnedConflictResult.Exhausted -> return finish(SearchRunEvent.Exhausted)

                        SearchLearnedConflictResult.Chronological -> if (!backtrack()) return stopAfterBacktrack()

                        SearchLearnedConflictResult.Indeterminate -> {
                            if (learnedPropagationPending) return pauseLearnedPropagation()
                            return finish(
                                SearchRunEvent.Indeterminate.Component, SearchComponentStop.Site.LearnedConflict,
                            )
                        }

                        is SearchLearnedConflictResult.Backjump -> error("learned conflict cascade did not terminate")
                    }
                    continue
                }

                SearchNodeDisposition.Indeterminate -> return finish(
                    SearchRunEvent.Indeterminate.Component, SearchComponentStop.Site.NodePolicy,
                )

                // Nothing was decided at this node yet, so the frame stack resumes here unchanged.
                SearchNodeDisposition.Pause -> return SearchRunEvent.Paused
            }
            when (val alternatives = if (pendingCheck) Alternatives.Leaf else alternatives()) {
                is Alternatives.Leaf -> when (val result = session.check().also {
                    pendingCheck = it == ComponentCheck.Paused
                }) {
                    ComponentCheck.Paused -> return SearchRunEvent.Paused

                    ComponentCheck.Feasible -> {
                        params.restart.onSolution()
                        val model = session.model().also {
                            lastModel = it
                            observer.onModel(it)
                        }
                        completeModel(model)?.let { return it }
                    }

                    is ComponentCheck.Infeasible -> {
                        observer.onConflict(null)
                        when (resolveExplainedConflict(result.explanation)) {
                            LearnedOutcome.RESUMED -> {
                                if (decisionsSinceRestart > 0 && params.restart.shouldRestart(decisionsSinceRestart)) {
                                    restart()?.let { return it }
                                }
                                continue
                            }

                            LearnedOutcome.ABANDONED -> {
                                if (!backtrack()) return stopAfterBacktrack()
                                continue
                            }

                            LearnedOutcome.UNAPPLIED -> Unit
                        }
                        retainExplanation(result.explanation)
                        if (decisionsSinceRestart > 0 && params.restart.shouldRestart(decisionsSinceRestart)) {
                            restart()?.let { return it }
                            continue
                        }
                        if (!resolveConflict() && !backtrack()) {
                            return stopAfterBacktrack()
                        }
                    }

                    ComponentCheck.Indeterminate -> {
                        val cancelledDisposition = if (session.cancelled()) lifecycle.onCancellation(session) else null
                        if (cancelledDisposition == SearchRunDisposition.Pause) return SearchRunEvent.Paused
                        if (cancelledDisposition != null) {
                            rememberIndeterminate()
                            return cancelledDisposition.toEvent(SearchComponentStop.Site.ExecutionControl)
                                ?: finish(SearchRunEvent.Indeterminate.Cancelled)
                        }
                        retainLeaf()
                        if (!backtrack()) return stopAfterBacktrack(indeterminate = true)
                    }
                }

                is Alternatives.Branch -> {
                    if (session.consumeCancellationPollRequest() && session.cancelled()) {
                        return lifecycle.onCancellation(session).toEvent(SearchComponentStop.Site.ExecutionControl)
                            ?: finish(SearchRunEvent.Indeterminate.Cancelled)
                    }
                    if (alternatives.decisions.isEmpty()) {
                        if (!backtrack()) return stopAfterBacktrack()
                        continue
                    }
                    if (decisionsSinceRestart > 0 && params.restart.shouldRestart(decisionsSinceRestart)) {
                        restart()?.let { return it }
                        continue
                    }
                    frames += Frame(session.decisionLevel, alternatives.decisions)
                    when (advanceFrame()) {
                        Advance.Expanded -> Unit
                        Advance.Paused -> return SearchRunEvent.Paused
                        Advance.Cancelled -> return cancelledAssertion()
                        Advance.Budget -> return finish(SearchRunEvent.Indeterminate.Budget)
                        Advance.Exhausted -> if (!backtrack()) return stopAfterBacktrack()
                        Advance.Restart -> restart()?.let { return it }
                    }
                }
            }
        }
    }

    internal fun surfaceSkippedModel() {
        surfaceSkip = true
    }

    private fun completeModel(model: AssembledSearchModel): SearchRunEvent? {
        pendingModel = null
        resumeAfterSolution = true
        when (val disposition = modelPolicy.onModel(model, session)) {
            SearchModelDisposition.Pause -> {
                resumeAfterSolution = false
                pendingModel = model
                return SearchRunEvent.Paused
            }

            SearchModelDisposition.Surface -> return SearchRunEvent.Satisfied(model)

            SearchModelDisposition.Indeterminate -> {
                resumeAfterSolution = false
                lastModel = null
                return finish(SearchRunEvent.Indeterminate.Component, SearchComponentStop.Site.ModelPolicy)
            }

            is SearchModelDisposition.Retry -> {
                retainLeaf(model = true)
                resumeAfterSolution = false
                lastModel = null
                if (disposition.surface) {
                    pendingAdvance = true
                    return SearchRunEvent.Satisfied(model)
                }
                skipModel()?.let { return it }
            }

            SearchModelDisposition.Skip -> {
                resumeAfterSolution = false
                lastModel = null
                if (surfaceSkip) {
                    surfaceSkip = false
                    pendingAdvance = true
                    return SearchRunEvent.Satisfied(model)
                }
                skipModel()?.let { return it }
            }

            SearchModelDisposition.Continue -> {
                stopBeforeModelBlock()?.let { return it }
                resumeAfterSolution = false
                when (modelContinuation) {
                    SearchModelContinuation.Chronological -> if (!backtrack()) {
                        return stopAfterBacktrack()
                    }

                    SearchModelContinuation.BlockAtRoot -> when (
                        session.blockModelAtRoot(checkNotNull(lastModel))
                    ) {
                        ComponentResult.Consistent -> {
                            frames.clear()
                            consumedModel = true
                            lifecycle.onModelBlocked(session).toEvent(SearchComponentStop.Site.ModelBlocked)
                                ?.let { return it }
                        }

                        is ComponentResult.Conflict -> return finish(SearchRunEvent.Exhausted)

                        ComponentResult.Indeterminate -> return finish(
                            SearchRunEvent.Indeterminate.Component, SearchComponentStop.Site.ModelBlock,
                        )
                    }
                }
            }
        }
        return null
    }

    private fun skipModel(): SearchRunEvent? {
        // A complete check can cost more than the nodes between cadence polls.
        if (session.cancelled()) {
            val event = lifecycle.onCancellation(session).toEvent(SearchComponentStop.Site.ExecutionControl)
                ?: finish(SearchRunEvent.Indeterminate.Cancelled)
            if (event == SearchRunEvent.Paused) pendingAdvance = true
            return event
        }
        return if (!backtrack()) stopAfterBacktrack() else null
    }

    private fun stopBeforeModelBlock(): SearchRunEvent? {
        if (modelContinuation != SearchModelContinuation.BlockAtRoot || !session.cancelled()) return null
        val disposition = lifecycle.onCancellation(session)
        if (retryingLeaves && leafRetries?.pending == true && disposition != SearchRunDisposition.Pause) {
            rememberIndeterminate()
        }
        return disposition.toEvent(SearchComponentStop.Site.ExecutionControl)
            ?: finish(SearchRunEvent.Indeterminate.Cancelled)
    }

    private fun alternatives(): Alternatives {
        val decisions = if (branchSources == null) {
            booleanBranching.alternatives(session) ?: session.branchAlternatives()
        } else {
            branchSelection.alternatives(branchSources)
        }
        return if (decisions == null) Alternatives.Leaf else Alternatives.Branch(decisions)
    }

    private fun advanceFrame(): Advance {
        val frame = frames.last()
        while (frame.next < frame.decisions.size) {
            if (pauseBeforeDecision()) {
                pendingAdvance = true
                return Advance.Paused
            }
            if (decisions == params.maxDecisions) {
                session.markDecisionBudgetExhausted()
                return Advance.Budget
            }
            val level = session.decisionLevel
            val decision = frame.decisions[frame.next++]
            if (!decision.tightens(session)) continue
            if (!session.executionControls.canCommitDecision()) return Advance.Budget
            branchObserver?.onAttempt(decision, session)
            when (val result = session.push(decision)) {
                ComponentResult.Consistent -> {
                    if (!session.lastPushCreatedLevel()) continue
                    if (!session.executionControls.recordDecision(decision)) return Advance.Budget
                    decisions++
                    decisionsSinceRestart++
                    observer.onCommit(decision, session.decisionLevel)
                    if (!decisionBudget.consume()) {
                        session.markDecisionBudgetExhausted()
                        return Advance.Budget
                    }
                    return Advance.Expanded
                }

                ComponentResult.Indeterminate -> {
                    val interrupted = session.cancelled()
                    session.popTo(level)
                    if (interrupted) {
                        // Retraction restores the completed parent; the unrefuted alternative must be retried.
                        frame.next--
                        pendingAdvance = true
                        pendingCancellation = true
                        retryingAssertion = true
                        return Advance.Cancelled
                    }
                    rememberIndeterminate()
                }

                is ComponentResult.Conflict -> {
                    observer.onConflict(decision)
                    val outcome = if (session.hasNativeConflictResolver) {
                        LearnedOutcome.UNAPPLIED
                    } else {
                        resolveExplainedConflict(result.explanation)
                    }
                    if (outcome == LearnedOutcome.RESUMED) return Advance.Expanded
                    if (session.decisionLevel < level) return Advance.Exhausted
                    // Only an unapplied learned conflict leaves the original conflict for chronological resolvers.
                    val unapplied = outcome == LearnedOutcome.UNAPPLIED
                    if (unapplied) retainExplanation(result.explanation)
                    session.popTo(level)
                    if (unapplied && resolveConflict()) return Advance.Expanded
                    if (frames.isEmpty()) return Advance.Exhausted
                    if (frames.last() !== frame) return Advance.Exhausted
                }
            }
        }
        frames.removeLast()
        return Advance.Exhausted
    }

    private fun SearchDecision.tightens(context: SearchContext): Boolean = when (this) {
        is SearchDecision.Bool -> context.boolValue(literal ushr 1) == null

        is SearchDecision.IntAtMost -> (context.intUpperBound(variable) ?: Long.MAX_VALUE) > upper

        is SearchDecision.IntAtLeast -> (context.intLowerBound(variable) ?: Long.MIN_VALUE) < lower

        is SearchDecision.IntEqual -> context.intLowerBound(
            variable,
        ) != value || context.intUpperBound(variable) != value

        is SearchDecision.Theory -> true
    }

    private fun backtrack(): Boolean {
        while (frames.isNotEmpty()) {
            val frame = frames.last()
            if (session.decisionLevel > frame.level) session.popTo(frame.level)
            when (advanceFrame()) {
                Advance.Expanded -> return true

                Advance.Paused, Advance.Cancelled -> return false

                Advance.Budget -> {
                    budgetInterrupted = true
                    return false
                }

                Advance.Exhausted -> Unit

                Advance.Restart -> return restart() == null
            }
        }
        return false
    }

    // A component that keeps its explanations in its own database already propagates them; a second copy
    // here would deduce nothing new.
    private fun retainExplanation(explanation: SearchExplanation?) {
        if (session.explanationIsOwnedElsewhere) return
        session.learn(explanation)
    }

    private fun resolveExplainedConflict(explanation: SearchExplanation?): LearnedOutcome {
        if (session.hasNativeConflictResolver) return LearnedOutcome.UNAPPLIED
        val resolution = session.explainedConflict(explanation)
        if (resolution !is SearchConflictResolution.Backjump) return LearnedOutcome.UNAPPLIED
        val result = applyLearnedConflict(resolution.conflict)
        return when {
            resumed(result) -> LearnedOutcome.RESUMED
            learnedApplied -> LearnedOutcome.ABANDONED
            else -> LearnedOutcome.UNAPPLIED
        }
    }

    private fun resolveConflict(): Boolean {
        val (resolver, resolution) = session.conflictResolution() ?: return false
        if (consumedModel && !resolver.resolvesAfterModelBlock) return false
        when (resolution) {
            SearchConflictResolution.Chronological -> return false

            SearchConflictResolution.Exhausted -> return false

            is SearchConflictResolution.Backjump -> {
                return resumed(applyLearnedConflict(resolution.conflict))
            }
        }
    }

    // The backjump has already dropped the frames above its level, so a caller that is not resumed backtracks
    // below it. An indeterminate propagation refuted nothing, so its region goes unsearched and the run cannot
    // claim exhaustion.
    private fun resumed(result: SearchLearnedConflictResult): Boolean = when (result) {
        SearchLearnedConflictResult.Resume -> true

        SearchLearnedConflictResult.Exhausted,
        SearchLearnedConflictResult.Chronological,
        -> false

        SearchLearnedConflictResult.Indeterminate -> {
            rememberIndeterminate()
            false
        }

        is SearchLearnedConflictResult.Backjump -> error("learned conflict cascade did not terminate")
    }

    private fun applyLearnedConflict(
        initial: SearchLearnedConflict,
        retainPropagation: Boolean = false,
    ): SearchLearnedConflictResult {
        var conflict = initial
        learnedApplied = false
        repeat(MAX_NODE_BACKJUMPS) {
            if (conflict.decisionLevel !in queryRoot..session.decisionLevel) {
                return SearchLearnedConflictResult.Chronological
            }
            learnedApplied = true
            params.restart.recordConflict(conflict.lbd, session.decisionLevel)
            observer.onLearnedConflict(conflict)
            session.popTo(conflict.decisionLevel)
            while (frames.isNotEmpty() && frames.last().level >= conflict.decisionLevel) frames.removeLast()
            when (val result = conflict.apply(session)) {
                SearchLearnedConflictResult.Resume,
                SearchLearnedConflictResult.Exhausted,
                SearchLearnedConflictResult.Chronological,
                -> return result

                SearchLearnedConflictResult.Indeterminate -> {
                    pendingLearnedPropagation = (conflict as? SearchPendingLearnedPropagation)
                        ?.takeIf { retainPropagation && it.propagationPending }
                    return result
                }

                is SearchLearnedConflictResult.Backjump -> conflict = result.conflict
            }
        }
        return SearchLearnedConflictResult.Chronological
    }

    private fun pauseLearnedPropagation(): SearchRunEvent {
        if (pendingLearnedPropagation?.propagationPending == true && session.cancelled() &&
            lifecycle.onCancellation(session) == SearchRunDisposition.Pause
        ) {
            return SearchRunEvent.Paused
        }
        pendingLearnedPropagation = null
        return finish(SearchRunEvent.Indeterminate.Component, SearchComponentStop.Site.LearnedConflict)
    }

    private fun exhausted(): SearchRunEvent = if (sawIndeterminate || leafRetries?.unresolved == true) {
        SearchRunEvent.Indeterminate.Component
    } else {
        SearchRunEvent.Exhausted
    }

    private fun rememberIndeterminate() {
        sawIndeterminate = true
        val cause = session.executionControls.stopCause
        indeterminateCause = cause.takeUnless { it == SearchStopCause.Timeout || it == SearchStopCause.Cancelled }
            ?: SearchStopCause.RepresentationDecline
    }

    private fun finish(
        event: SearchRunEvent,
        site: SearchComponentStop.Site = SearchComponentStop.Site.Coverage,
    ): SearchRunEvent {
        if (event == SearchRunEvent.Exhausted) coverageComplete = true
        if (event == SearchRunEvent.Indeterminate.Component) {
            val stopCause = session.executionControls.stopCause
            if (stopCause == null) {
                session.executionControls.reportStop(
                    indeterminateCause ?: leafRetries?.stopCause ?: SearchStopCause.RepresentationDecline,
                )
            }
            if (recordComponentStops) {
                componentStop = SearchComponentStop(
                    site, stopCause, session.cancelled(), sawIndeterminate || leafRetries?.unresolved == true,
                    session.decisionLevel,
                )
            }
        }
        terminal = event
        return event
    }

    private fun cancelledAssertion(): SearchRunEvent {
        pendingCancellation = false
        return lifecycle.onCancellation(session).toEvent(SearchComponentStop.Site.ExecutionControl)
            ?: finish(SearchRunEvent.Indeterminate.Cancelled)
    }

    private fun stopAfterBacktrack(indeterminate: Boolean = false): SearchRunEvent {
        if (pendingCancellation) return cancelledAssertion()
        if (pendingAdvance) return SearchRunEvent.Paused
        // An exhausted frame stack has no pending region for a later slice to resume.
        if (frames.isEmpty() && !budgetInterrupted) {
            coverageComplete = true
            return finish(exhausted())
        }
        if (session.cancelled()) {
            return lifecycle.onCancellation(session).toEvent(SearchComponentStop.Site.ExecutionControl)
                ?: finish(SearchRunEvent.Indeterminate.Cancelled)
        }
        return when {
            budgetInterrupted -> finish(SearchRunEvent.Indeterminate.Budget)
            indeterminate -> finish(SearchRunEvent.Indeterminate.Component, SearchComponentStop.Site.LeafCheck)
            else -> finish(exhausted())
        }
    }

    private fun restart(): SearchRunEvent? = when (session.restartAt(queryRoot)) {
        ComponentResult.Consistent -> {
            frames.clear()
            params.restart.onRestart()
            observer.onRestart(decisionsSinceRestart)
            decisionsSinceRestart = 0L
            params.restart.beginRun()
            lifecycle.onRestart(session).toEvent(SearchComponentStop.Site.Restart)
        }

        is ComponentResult.Conflict -> finish(SearchRunEvent.Exhausted)

        ComponentResult.Indeterminate -> if (session.cancelled()) {
            lifecycle.onCancellation(session).toEvent(SearchComponentStop.Site.ExecutionControl)
                ?: finish(SearchRunEvent.Indeterminate.Cancelled)
        } else {
            finish(SearchRunEvent.Indeterminate.Component, SearchComponentStop.Site.RestartPropagation)
        }
    }

    private fun SearchRunDisposition.toEvent(site: SearchComponentStop.Site): SearchRunEvent? = when (this) {
        SearchRunDisposition.Continue -> null
        SearchRunDisposition.Exhausted -> finish(exhausted())
        SearchRunDisposition.Pause -> SearchRunEvent.Paused
        SearchRunDisposition.Indeterminate -> finish(SearchRunEvent.Indeterminate.Component, site)
    }

    internal fun retainBooleanRoot(literal: Int): Boolean {
        if (modelContinuation != SearchModelContinuation.Chronological || queryRoot != minimumDecisionLevel ||
            !started || terminal != null || sawIndeterminate || leafRetries?.pending == true ||
            pendingModel != null || lastModel != null ||
            consumedModel || pendingCancellation || pendingCheck || retryingAssertion || learnedPropagationPending ||
            budgetInterrupted) return false
        val frame = frames.firstOrNull() ?: return false
        if (frame.level != minimumDecisionLevel || session.decisionLevel <= frame.level ||
            frame.decisions.size != 2) return false
        val selected = frame.decisions.getOrNull(frame.next - 1) as? SearchDecision.Bool ?: return false
        val opposite = frame.decisions.getOrNull(2 - frame.next) as? SearchDecision.Bool ?: return false
        if (selected.literal != literal || opposite.literal != (literal xor 1) ||
            session.boolValue(literal ushr 1) != (literal and 1 == 0)) return false
        // The peer owns the complementary region; descendant frames and their charged work stay here.
        frames.removeAt(0)
        queryRoot = frame.level + 1
        return true
    }

    internal fun releasePendingPropagation() {
        pendingLearnedPropagation = null
        leafRetries?.clear()
        pendingRetryBlock = null
    }

    /** Reset traversal state after its components have been reseeded at root. */
    fun reset() {
        check(session.decisionLevel == minimumDecisionLevel) { "a search run may only reset at its root" }
        queryRoot = minimumDecisionLevel
        frames.clear()
        decisions = 0L
        decisionsSinceRestart = 0L
        sawIndeterminate = false
        indeterminateCause = null
        leafRetries?.clear()
        retryingLeaves = false
        coverageComplete = false
        unresolvedModel = false
        pendingRetryBlock = null
        resumeAfterSolution = false
        lastModel = null
        pendingModel = null
        consumedModel = false
        terminal = null
        componentStop = null
        componentStopReplayed = false
        budgetInterrupted = false
        pendingAdvance = false
        surfaceSkip = false
        pendingCancellation = false
        pendingCheck = false
        retryingAssertion = false
        pendingLearnedPropagation = null
        started = false
        session.beginTraversal()
        cancellationPoller.reset()
        params.restart.beginRun()
    }

    private data class Frame(val level: Int, val decisions: List<SearchDecision>, var next: Int = 0)

    private sealed interface Alternatives {
        data object Leaf : Alternatives
        data class Branch(val decisions: List<SearchDecision>) : Alternatives
    }

    private enum class Advance {
        Expanded,
        Paused,
        Cancelled,
        Exhausted,
        Budget,
        Restart,
    }

    private companion object {
        const val MAX_NODE_BACKJUMPS = 64
    }
}

/** Charges successful shared decisions to an optional solve-wide allowance. */
fun interface SearchDecisionBudget {
    /** Record one decision and return whether traversal may continue. */
    fun consume(): Boolean

    /** No additional solve-wide decision limit. */
    data object Unlimited : SearchDecisionBudget {
        override fun consume(): Boolean = true
    }
}

/** Observes shared traversal lifecycle events without owning its trail. */
interface SearchRunObserver {
    /** A shared decision was accepted at [decisionLevel]. */
    fun onCommit(decision: SearchDecision, decisionLevel: Int) {}

    /** A branch assertion or candidate leaf was refuted. */
    fun onConflict(decision: SearchDecision?) {}

    /** A node policy supplied an asserting learned consequence. */
    fun onLearnedNodeBackjump() {}

    /** A component produced a learned consequence, before the runner retracts to its asserting level. */
    fun onLearnedConflict(conflict: SearchLearnedConflict) {}

    /** The runner returned the session to root after [decisions] decisions in the completed run. */
    fun onRestart(decisions: Long) {}

    /** The session assembled a complete model. */
    fun onModel(model: AssembledSearchModel) {}

    /** No-op lifecycle observer. */
    data object None : SearchRunObserver
}

/** Opt-in observation of branch attempts. */
interface SearchBranchAttemptObserver : SearchRunObserver {
    /** A tightening alternative is entering its assertion, including alternatives that conflict immediately. */
    fun onAttempt(decision: SearchDecision, context: SearchContext)
}

/** Supplies Boolean alternatives before component-owned residual splits. */
fun interface BooleanBranching {
    /** Exhaustive alternatives for one unassigned Boolean, or null when Boolean branching is complete. */
    fun alternatives(context: SearchContext): List<SearchDecision>?

    /** Source-order Boolean variables with false before true. */
    class SourceOrder(private val numBoolVars: Int) : BooleanBranching {
        override fun alternatives(context: SearchContext): List<SearchDecision>? =
            (0 until numBoolVars).firstOrNull { context.boolValue(it) == null }?.let { variable ->
                listOf(SearchDecision.Bool((variable shl 1) or 1), SearchDecision.Bool(variable shl 1))
            }
    }

    /** Leave Boolean selection to a component-owned [SearchBrancher]. */
    data object None : BooleanBranching {
        override fun alternatives(context: SearchContext): List<SearchDecision>? = null
    }
}

/** A model or terminal verdict from a resumable [SearchRun]. */
sealed interface SearchRunEvent {
    /** A complete model; call [SearchRun.next] again to continue enumeration. */
    data class Satisfied(
        /** The complete model assembled by the participating search components. */
        val model: AssembledSearchModel,
    ) : SearchRunEvent

    /** Every branch was refuted. */
    data object Exhausted : SearchRunEvent

    /** The mode yielded cooperatively and may resume by calling [SearchRun.next]. */
    data object Paused : SearchRunEvent

    /** ExecutionControl, a limit, or a component prevented an exact verdict. */
    sealed interface Indeterminate : SearchRunEvent {
        /** A component could not decide a candidate leaf exactly. */
        data object Component : Indeterminate

        /** The shared decision allowance was spent. */
        data object Budget : Indeterminate

        /** The solve cancellation token fired. */
        data object Cancelled : Indeterminate
    }
}

/** Terminal result of [SearchSession.solveBoolean]. */
sealed interface BooleanSearchResult {
    /**
     * Every component accepted the complete shared assignment.
     *
     * @property model Values contributed by every active component.
     */
    data class Satisfied(val model: AssembledSearchModel) : BooleanSearchResult

    /** Every complete Boolean assignment was refuted. */
    data object Exhausted : BooleanSearchResult

    /** A component or a solve-wide limit prevented an exact verdict. */
    data object Indeterminate : BooleanSearchResult
}

/** Terminal result of [SearchSession.solve]. */
sealed interface SearchResult {
    /**
     * Every component accepted the complete shared assignment.
     *
     * @property model Values contributed by every active component.
     */
    data class Satisfied(val model: AssembledSearchModel) : SearchResult

    /** Every component-provided branch was refuted. */
    data object Exhausted : SearchResult

    /** A component or a solve-wide limit prevented an exact verdict. */
    data object Indeterminate : SearchResult
}

/* What one learned conflict did to the run. */
private enum class LearnedOutcome {
    /* The search resumes from the backjump level. */
    RESUMED,

    /* The conflict was never applied; the original conflict is still live. */
    UNAPPLIED,

    /* The backjump ran but did not resume, so the caller backtracks below it. */
    ABANDONED,
}
