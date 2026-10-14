package com.eignex.klause.solver.result

/**
 * What the unverified Boolean hints of one open-model solve produced, cost, and steered.
 *
 * A hint only reorders branches, so none of this bears on the verdict. It is the accounting a
 * measurement needs, and producing a proposal is not the same as using one: propagation may fix every
 * hinted column before a split reaches it, leaving a drawn hint with no effect on the traversal at all.
 * [produced] and [steeredSplits] are what separate those cases, and a draw that proposed nothing still
 * spent its allowance.
 */
data class OpenHintStats(
    /** Hint draws attempted, at most one per request. */
    val draws: Long = 0,
    /** Draws that reached a proposal. */
    val produced: Long = 0,
    /** Source Boolean columns the proposals cover. */
    val hintedVars: Long = 0,
    /**
     * Splits whose first branch a hint selected.
     *
     * Counts the split orders the hint decided, including one it decided the same way the default order
     * would have: the traversal still ran the polarity the hint named. Zero says the hint reached no
     * split, whatever it proposed.
     */
    val steeredSplits: Long = 0,
    /** Local-search moves the draws spent. */
    val moves: Long = 0,
    /** Bounded source-index and branch-consultation observations, independent of hint draws. */
    val usage: OpenHintUsageStats,
) {
    /** Construct an empty draw and usage observation. */
    constructor() : this(0L, 0L, 0L, 0L, 0L, OpenHintUsageStats())

    /** Construct draw counters without source-index or consultation observations. */
    constructor(
        draws: Long = 0,
        produced: Long = 0,
        hintedVars: Long = 0,
        steeredSplits: Long = 0,
        moves: Long = 0,
    ) : this(draws, produced, hintedVars, steeredSplits, moves, OpenHintUsageStats())

    /** Copy draw counters while retaining [usage]. */
    fun copy(
        draws: Long = this.draws,
        produced: Long = this.produced,
        hintedVars: Long = this.hintedVars,
        steeredSplits: Long = this.steeredSplits,
        moves: Long = this.moves,
    ): OpenHintStats = OpenHintStats(draws, produced, hintedVars, steeredSplits, moves, usage)

    /** Combine counters from independent solve slices. */
    fun mergedWith(other: OpenHintStats): OpenHintStats = OpenHintStats(
        draws = draws + other.draws,
        produced = produced + other.produced,
        hintedVars = hintedVars + other.hintedVars,
        steeredSplits = steeredSplits + other.steeredSplits,
        moves = moves + other.moves,
        usage = usage.mergedWith(other.usage),
    )
}

/** Invocation-owned source hint usage; no observation supplies a fact, witness or verdict. */
data class OpenHintUsageStats(
    /** Openings with no component hint provider. */
    val noProvider: Long = 0,
    /** Completed source indexes. */
    val indexesAdmitted: Long = 0,
    /** Indexes declined at the source factor or primitive size boundary. */
    val sourceLimitDeclines: Long = 0,
    /** Indexes declined at their private storage boundary. */
    val storageDeclines: Long = 0,
    /** Indexes declined at their recognition visit boundary. */
    val visitDeclines: Long = 0,
    /** Indexes declined for a primitive outside the source Boolean coordinates. */
    val invalidPrimitiveDeclines: Long = 0,
    /** Indexes stopped by their opening owner. */
    val stoppedIndexes: Long = 0,
    /** Indexes whose producer threw before a completed outcome. */
    val abortedIndexes: Long = 0,
    /** Actual caller or deferred provider consultations at eligible Boolean splits. */
    val callerConsultations: Long = 0,
    /** Splits whose first polarity the caller provider selected. */
    val callerSelections: Long = 0,
    /** Component consultations after the caller supplied no preference. */
    val componentConsultations: Long = 0,
    /** Splits whose first polarity the component provider selected. */
    val componentSelections: Long = 0,
    /** Component selections over direct primitive source guards. */
    val primitiveSelections: Long = 0,
    /** Component selections over recognized source gates. */
    val gateSelections: Long = 0,
    /** Completed optional semantic-clause indexes. */
    val clauseIndexesAdmitted: Long = 0,
    /** Optional semantic-clause indexes declined without releasing forward hints. */
    val clauseIndexesDeclined: Long = 0,
    /** Two-hypothesis semantic-clause consultations at a live arithmetic point. */
    val clauseConsultations: Long = 0,
    /** Semantic comparisons that selected the first polarity. */
    val clauseSelections: Long = 0,
    /** Semantic consultations that produced no preference, including ties and resource declines. */
    val clauseDeclines: Long = 0,
    /** Consultations whose first live-point hook returned unavailable. */
    val earlyPointUnavailable: Long = 0,
    /** Selected variables absent from the forward primitive and gate domains. */
    val forwardUncovered: Long = 0,
    /** Actual primitive callbacks that returned no preference, before owner masking. */
    val primitiveProviderNone: Long = 0,
    /** Largest supplied source Boolean coordinate count; null when unobserved. */
    val sourceBooleansMax: Long?,
    /** Largest supplied original clause count; null when unobserved. */
    val sourceClausesMax: Long?,
    /** Largest supplied primitive input count, including duplicates; null when unobserved. */
    val sourcePrimitiveEntriesMax: Long?,
    /** Largest completed raw clause prefix; null when no raw stage was observed. */
    val rawClausesMax: Long?,
    /** Largest validated raw literal prefix, including tautological clauses; null when unobserved. */
    val rawLiteralsMax: Long?,
    /** Largest number of completed raw incidence appends; null when unobserved. */
    val rawIncidencesMax: Long?,
    /** Largest completed raw distinct-variable bucket count; null when unobserved. */
    val rawVariablesMax: Long?,
    /** Private raw-stage visit debits; null when unobserved, not external meter acknowledgement or completion. */
    val rawLedgerDebits: Long?,
) {
    /** Construct empty usage without construction observations. */
    constructor() : this(
        noProvider = 0,
        indexesAdmitted = 0,
        sourceLimitDeclines = 0,
        storageDeclines = 0,
        visitDeclines = 0,
        invalidPrimitiveDeclines = 0,
        stoppedIndexes = 0,
        abortedIndexes = 0,
        callerConsultations = 0,
        callerSelections = 0,
        componentConsultations = 0,
        componentSelections = 0,
        primitiveSelections = 0,
        gateSelections = 0,
        clauseIndexesAdmitted = 0,
        clauseIndexesDeclined = 0,
        clauseConsultations = 0,
        clauseSelections = 0,
        clauseDeclines = 0,
        earlyPointUnavailable = 0,
        forwardUncovered = 0,
        primitiveProviderNone = 0,
        sourceBooleansMax = null,
        sourceClausesMax = null,
        sourcePrimitiveEntriesMax = null,
        rawClausesMax = null,
        rawLiteralsMax = null,
        rawIncidencesMax = null,
        rawVariablesMax = null,
        rawLedgerDebits = null,
    )

    /** Construct 19 usage counters without construction observations. */
    constructor(
        noProvider: Long = 0,
        indexesAdmitted: Long = 0,
        sourceLimitDeclines: Long = 0,
        storageDeclines: Long = 0,
        visitDeclines: Long = 0,
        invalidPrimitiveDeclines: Long = 0,
        stoppedIndexes: Long = 0,
        abortedIndexes: Long = 0,
        callerConsultations: Long = 0,
        callerSelections: Long = 0,
        componentConsultations: Long = 0,
        componentSelections: Long = 0,
        primitiveSelections: Long = 0,
        gateSelections: Long = 0,
        clauseIndexesAdmitted: Long = 0,
        clauseIndexesDeclined: Long = 0,
        clauseConsultations: Long = 0,
        clauseSelections: Long = 0,
        clauseDeclines: Long = 0,
    ) : this(
        noProvider = noProvider,
        indexesAdmitted = indexesAdmitted,
        sourceLimitDeclines = sourceLimitDeclines,
        storageDeclines = storageDeclines,
        visitDeclines = visitDeclines,
        invalidPrimitiveDeclines = invalidPrimitiveDeclines,
        stoppedIndexes = stoppedIndexes,
        abortedIndexes = abortedIndexes,
        callerConsultations = callerConsultations,
        callerSelections = callerSelections,
        componentConsultations = componentConsultations,
        componentSelections = componentSelections,
        primitiveSelections = primitiveSelections,
        gateSelections = gateSelections,
        clauseIndexesAdmitted = clauseIndexesAdmitted,
        clauseIndexesDeclined = clauseIndexesDeclined,
        clauseConsultations = clauseConsultations,
        clauseSelections = clauseSelections,
        clauseDeclines = clauseDeclines,
        earlyPointUnavailable = 0,
        forwardUncovered = 0,
        primitiveProviderNone = 0,
        sourceBooleansMax = null,
        sourceClausesMax = null,
        sourcePrimitiveEntriesMax = null,
        rawClausesMax = null,
        rawLiteralsMax = null,
        rawIncidencesMax = null,
        rawVariablesMax = null,
        rawLedgerDebits = null,
    )

    /** Copy 19 usage counters while retaining omitted observations. */
    fun copy(
        noProvider: Long = this.noProvider,
        indexesAdmitted: Long = this.indexesAdmitted,
        sourceLimitDeclines: Long = this.sourceLimitDeclines,
        storageDeclines: Long = this.storageDeclines,
        visitDeclines: Long = this.visitDeclines,
        invalidPrimitiveDeclines: Long = this.invalidPrimitiveDeclines,
        stoppedIndexes: Long = this.stoppedIndexes,
        abortedIndexes: Long = this.abortedIndexes,
        callerConsultations: Long = this.callerConsultations,
        callerSelections: Long = this.callerSelections,
        componentConsultations: Long = this.componentConsultations,
        componentSelections: Long = this.componentSelections,
        primitiveSelections: Long = this.primitiveSelections,
        gateSelections: Long = this.gateSelections,
        clauseIndexesAdmitted: Long = this.clauseIndexesAdmitted,
        clauseIndexesDeclined: Long = this.clauseIndexesDeclined,
        clauseConsultations: Long = this.clauseConsultations,
        clauseSelections: Long = this.clauseSelections,
        clauseDeclines: Long = this.clauseDeclines,
    ): OpenHintUsageStats = OpenHintUsageStats(
        noProvider = noProvider,
        indexesAdmitted = indexesAdmitted,
        sourceLimitDeclines = sourceLimitDeclines,
        storageDeclines = storageDeclines,
        visitDeclines = visitDeclines,
        invalidPrimitiveDeclines = invalidPrimitiveDeclines,
        stoppedIndexes = stoppedIndexes,
        abortedIndexes = abortedIndexes,
        callerConsultations = callerConsultations,
        callerSelections = callerSelections,
        componentConsultations = componentConsultations,
        componentSelections = componentSelections,
        primitiveSelections = primitiveSelections,
        gateSelections = gateSelections,
        clauseIndexesAdmitted = clauseIndexesAdmitted,
        clauseIndexesDeclined = clauseIndexesDeclined,
        clauseConsultations = clauseConsultations,
        clauseSelections = clauseSelections,
        clauseDeclines = clauseDeclines,
        earlyPointUnavailable = earlyPointUnavailable,
        forwardUncovered = forwardUncovered,
        primitiveProviderNone = primitiveProviderNone,
        sourceBooleansMax = sourceBooleansMax,
        sourceClausesMax = sourceClausesMax,
        sourcePrimitiveEntriesMax = sourcePrimitiveEntriesMax,
        rawClausesMax = rawClausesMax,
        rawLiteralsMax = rawLiteralsMax,
        rawIncidencesMax = rawIncidencesMax,
        rawVariablesMax = rawVariablesMax,
        rawLedgerDebits = rawLedgerDebits,
    )

    /** Construct 22 usage counters without construction observations. */
    constructor(
        noProvider: Long = 0,
        indexesAdmitted: Long = 0,
        sourceLimitDeclines: Long = 0,
        storageDeclines: Long = 0,
        visitDeclines: Long = 0,
        invalidPrimitiveDeclines: Long = 0,
        stoppedIndexes: Long = 0,
        abortedIndexes: Long = 0,
        callerConsultations: Long = 0,
        callerSelections: Long = 0,
        componentConsultations: Long = 0,
        componentSelections: Long = 0,
        primitiveSelections: Long = 0,
        gateSelections: Long = 0,
        clauseIndexesAdmitted: Long = 0,
        clauseIndexesDeclined: Long = 0,
        clauseConsultations: Long = 0,
        clauseSelections: Long = 0,
        clauseDeclines: Long = 0,
        earlyPointUnavailable: Long = 0,
        forwardUncovered: Long = 0,
        primitiveProviderNone: Long = 0,
    ) : this(
        noProvider = noProvider,
        indexesAdmitted = indexesAdmitted,
        sourceLimitDeclines = sourceLimitDeclines,
        storageDeclines = storageDeclines,
        visitDeclines = visitDeclines,
        invalidPrimitiveDeclines = invalidPrimitiveDeclines,
        stoppedIndexes = stoppedIndexes,
        abortedIndexes = abortedIndexes,
        callerConsultations = callerConsultations,
        callerSelections = callerSelections,
        componentConsultations = componentConsultations,
        componentSelections = componentSelections,
        primitiveSelections = primitiveSelections,
        gateSelections = gateSelections,
        clauseIndexesAdmitted = clauseIndexesAdmitted,
        clauseIndexesDeclined = clauseIndexesDeclined,
        clauseConsultations = clauseConsultations,
        clauseSelections = clauseSelections,
        clauseDeclines = clauseDeclines,
        earlyPointUnavailable = earlyPointUnavailable,
        forwardUncovered = forwardUncovered,
        primitiveProviderNone = primitiveProviderNone,
        sourceBooleansMax = null,
        sourceClausesMax = null,
        sourcePrimitiveEntriesMax = null,
        rawClausesMax = null,
        rawLiteralsMax = null,
        rawIncidencesMax = null,
        rawVariablesMax = null,
        rawLedgerDebits = null,
    )

    /** Copy 22 usage counters while retaining omitted observations. */
    fun copy(
        noProvider: Long = this.noProvider,
        indexesAdmitted: Long = this.indexesAdmitted,
        sourceLimitDeclines: Long = this.sourceLimitDeclines,
        storageDeclines: Long = this.storageDeclines,
        visitDeclines: Long = this.visitDeclines,
        invalidPrimitiveDeclines: Long = this.invalidPrimitiveDeclines,
        stoppedIndexes: Long = this.stoppedIndexes,
        abortedIndexes: Long = this.abortedIndexes,
        callerConsultations: Long = this.callerConsultations,
        callerSelections: Long = this.callerSelections,
        componentConsultations: Long = this.componentConsultations,
        componentSelections: Long = this.componentSelections,
        primitiveSelections: Long = this.primitiveSelections,
        gateSelections: Long = this.gateSelections,
        clauseIndexesAdmitted: Long = this.clauseIndexesAdmitted,
        clauseIndexesDeclined: Long = this.clauseIndexesDeclined,
        clauseConsultations: Long = this.clauseConsultations,
        clauseSelections: Long = this.clauseSelections,
        clauseDeclines: Long = this.clauseDeclines,
        earlyPointUnavailable: Long = this.earlyPointUnavailable,
        forwardUncovered: Long = this.forwardUncovered,
        primitiveProviderNone: Long = this.primitiveProviderNone,
    ): OpenHintUsageStats = OpenHintUsageStats(
        noProvider = noProvider,
        indexesAdmitted = indexesAdmitted,
        sourceLimitDeclines = sourceLimitDeclines,
        storageDeclines = storageDeclines,
        visitDeclines = visitDeclines,
        invalidPrimitiveDeclines = invalidPrimitiveDeclines,
        stoppedIndexes = stoppedIndexes,
        abortedIndexes = abortedIndexes,
        callerConsultations = callerConsultations,
        callerSelections = callerSelections,
        componentConsultations = componentConsultations,
        componentSelections = componentSelections,
        primitiveSelections = primitiveSelections,
        gateSelections = gateSelections,
        clauseIndexesAdmitted = clauseIndexesAdmitted,
        clauseIndexesDeclined = clauseIndexesDeclined,
        clauseConsultations = clauseConsultations,
        clauseSelections = clauseSelections,
        clauseDeclines = clauseDeclines,
        earlyPointUnavailable = earlyPointUnavailable,
        forwardUncovered = forwardUncovered,
        primitiveProviderNone = primitiveProviderNone,
        sourceBooleansMax = sourceBooleansMax,
        sourceClausesMax = sourceClausesMax,
        sourcePrimitiveEntriesMax = sourcePrimitiveEntriesMax,
        rawClausesMax = rawClausesMax,
        rawLiteralsMax = rawLiteralsMax,
        rawIncidencesMax = rawIncidencesMax,
        rawVariablesMax = rawVariablesMax,
        rawLedgerDebits = rawLedgerDebits,
    )

    /** Merge events and observed ledger debits by saturating sum, and observed construction maxima by maximum. */
    fun mergedWith(other: OpenHintUsageStats): OpenHintUsageStats = OpenHintUsageStats(
        noProvider = sumHintUsage(noProvider, other.noProvider),
        indexesAdmitted = sumHintUsage(indexesAdmitted, other.indexesAdmitted),
        sourceLimitDeclines = sumHintUsage(sourceLimitDeclines, other.sourceLimitDeclines),
        storageDeclines = sumHintUsage(storageDeclines, other.storageDeclines),
        visitDeclines = sumHintUsage(visitDeclines, other.visitDeclines),
        invalidPrimitiveDeclines = sumHintUsage(invalidPrimitiveDeclines, other.invalidPrimitiveDeclines),
        stoppedIndexes = sumHintUsage(stoppedIndexes, other.stoppedIndexes),
        abortedIndexes = sumHintUsage(abortedIndexes, other.abortedIndexes),
        callerConsultations = sumHintUsage(callerConsultations, other.callerConsultations),
        callerSelections = sumHintUsage(callerSelections, other.callerSelections),
        componentConsultations = sumHintUsage(componentConsultations, other.componentConsultations),
        componentSelections = sumHintUsage(componentSelections, other.componentSelections),
        primitiveSelections = sumHintUsage(primitiveSelections, other.primitiveSelections),
        gateSelections = sumHintUsage(gateSelections, other.gateSelections),
        clauseIndexesAdmitted = sumHintUsage(clauseIndexesAdmitted, other.clauseIndexesAdmitted),
        clauseIndexesDeclined = sumHintUsage(clauseIndexesDeclined, other.clauseIndexesDeclined),
        clauseConsultations = sumHintUsage(clauseConsultations, other.clauseConsultations),
        clauseSelections = sumHintUsage(clauseSelections, other.clauseSelections),
        clauseDeclines = sumHintUsage(clauseDeclines, other.clauseDeclines),
        earlyPointUnavailable = sumHintUsage(earlyPointUnavailable, other.earlyPointUnavailable),
        forwardUncovered = sumHintUsage(forwardUncovered, other.forwardUncovered),
        primitiveProviderNone = sumHintUsage(primitiveProviderNone, other.primitiveProviderNone),
        sourceBooleansMax = maxHintObservation(sourceBooleansMax, other.sourceBooleansMax),
        sourceClausesMax = maxHintObservation(sourceClausesMax, other.sourceClausesMax),
        sourcePrimitiveEntriesMax = maxHintObservation(sourcePrimitiveEntriesMax, other.sourcePrimitiveEntriesMax),
        rawClausesMax = maxHintObservation(rawClausesMax, other.rawClausesMax),
        rawLiteralsMax = maxHintObservation(rawLiteralsMax, other.rawLiteralsMax),
        rawIncidencesMax = maxHintObservation(rawIncidencesMax, other.rawIncidencesMax),
        rawVariablesMax = maxHintObservation(rawVariablesMax, other.rawVariablesMax),
        rawLedgerDebits = sumHintObservation(rawLedgerDebits, other.rawLedgerDebits),
    )
}

internal enum class HintIndexOutcome {
    NO_PROVIDER, ADMITTED, SOURCE_LIMIT, STORAGE, VISITS, INVALID_PRIMITIVE, STOPPED, ABORTED,
}

internal class OpenHintUsageSink {
    private val indexes = LongArray(HintIndexOutcome.entries.size)
    private var callerConsultations = 0L
    private var callerSelections = 0L
    private var componentConsultations = 0L
    private var componentSelections = 0L
    private var primitiveSelections = 0L
    private var gateSelections = 0L
    private var clauseIndexesAdmitted = 0L
    private var clauseIndexesDeclined = 0L
    private var clauseConsultations = 0L
    private var clauseSelections = 0L
    private var clauseDeclines = 0L
    private var earlyPointUnavailable = 0L
    private var forwardUncovered = 0L
    private var primitiveProviderNone = 0L
    private var sourceBooleansMax: Long? = null
    private var sourceClausesMax: Long? = null
    private var sourcePrimitiveEntriesMax: Long? = null
    private var rawClausesMax: Long? = null
    private var rawLiteralsMax: Long? = null
    private var rawIncidencesMax: Long? = null
    private var rawVariablesMax: Long? = null
    private var rawLedgerDebits: Long? = null

    fun index(outcome: HintIndexOutcome) {
        val before = indexes[outcome.ordinal]
        indexes[outcome.ordinal] = before + minOf(1L, Long.MAX_VALUE - before)
    }

    fun callerConsulted() { callerConsultations += minOf(1L, Long.MAX_VALUE - callerConsultations) }
    fun callerSelected() { callerSelections += minOf(1L, Long.MAX_VALUE - callerSelections) }
    fun componentConsulted() { componentConsultations += minOf(1L, Long.MAX_VALUE - componentConsultations) }
    fun componentSelected(primitive: Boolean?, gate: Boolean) {
        componentSelections += minOf(1L, Long.MAX_VALUE - componentSelections)
        if (primitive == true) primitiveSelections += minOf(1L, Long.MAX_VALUE - primitiveSelections)
        if (gate) gateSelections += minOf(1L, Long.MAX_VALUE - gateSelections)
    }

    fun clauseIndex(admitted: Boolean) {
        if (admitted) clauseIndexesAdmitted += minOf(1L, Long.MAX_VALUE - clauseIndexesAdmitted)
        else clauseIndexesDeclined += minOf(1L, Long.MAX_VALUE - clauseIndexesDeclined)
    }
    fun clauseConsulted() { clauseConsultations += minOf(1L, Long.MAX_VALUE - clauseConsultations) }
    fun clauseSelected() { clauseSelections += minOf(1L, Long.MAX_VALUE - clauseSelections) }
    fun clauseDeclined() { clauseDeclines += minOf(1L, Long.MAX_VALUE - clauseDeclines) }
    fun pointUnavailable() { earlyPointUnavailable += minOf(1L, Long.MAX_VALUE - earlyPointUnavailable) }
    fun variableUncovered() { forwardUncovered += minOf(1L, Long.MAX_VALUE - forwardUncovered) }
    fun primitiveUnavailable() { primitiveProviderNone += minOf(1L, Long.MAX_VALUE - primitiveProviderNone) }

    fun sourceDimensions(booleans: Int, clauses: Int, primitives: Int) {
        sourceBooleansMax = maxHintObservation(sourceBooleansMax, booleans.toLong())
        sourceClausesMax = maxHintObservation(sourceClausesMax, clauses.toLong())
        sourcePrimitiveEntriesMax = maxHintObservation(sourcePrimitiveEntriesMax, primitives.toLong())
    }

    fun rawConstruction(clauses: Long, literals: Long, incidences: Long, variables: Long, debits: Long) {
        rawClausesMax = maxHintObservation(rawClausesMax, clauses)
        rawLiteralsMax = maxHintObservation(rawLiteralsMax, literals)
        rawIncidencesMax = maxHintObservation(rawIncidencesMax, incidences)
        rawVariablesMax = maxHintObservation(rawVariablesMax, variables)
        rawLedgerDebits = sumHintObservation(rawLedgerDebits, debits)
    }

    fun snapshot(): OpenHintUsageStats = OpenHintUsageStats(
        noProvider = indexes[HintIndexOutcome.NO_PROVIDER.ordinal],
        indexesAdmitted = indexes[HintIndexOutcome.ADMITTED.ordinal],
        sourceLimitDeclines = indexes[HintIndexOutcome.SOURCE_LIMIT.ordinal],
        storageDeclines = indexes[HintIndexOutcome.STORAGE.ordinal],
        visitDeclines = indexes[HintIndexOutcome.VISITS.ordinal],
        invalidPrimitiveDeclines = indexes[HintIndexOutcome.INVALID_PRIMITIVE.ordinal],
        stoppedIndexes = indexes[HintIndexOutcome.STOPPED.ordinal],
        abortedIndexes = indexes[HintIndexOutcome.ABORTED.ordinal],
        callerConsultations = callerConsultations,
        callerSelections = callerSelections,
        componentConsultations = componentConsultations,
        componentSelections = componentSelections,
        primitiveSelections = primitiveSelections,
        gateSelections = gateSelections,
        clauseIndexesAdmitted = clauseIndexesAdmitted,
        clauseIndexesDeclined = clauseIndexesDeclined,
        clauseConsultations = clauseConsultations,
        clauseSelections = clauseSelections,
        clauseDeclines = clauseDeclines,
        earlyPointUnavailable = earlyPointUnavailable,
        forwardUncovered = forwardUncovered,
        primitiveProviderNone = primitiveProviderNone,
        sourceBooleansMax = sourceBooleansMax,
        sourceClausesMax = sourceClausesMax,
        sourcePrimitiveEntriesMax = sourcePrimitiveEntriesMax,
        rawClausesMax = rawClausesMax,
        rawLiteralsMax = rawLiteralsMax,
        rawIncidencesMax = rawIncidencesMax,
        rawVariablesMax = rawVariablesMax,
        rawLedgerDebits = rawLedgerDebits,
    )
}

private fun sumHintUsage(left: Long, right: Long): Long = left + minOf(right, Long.MAX_VALUE - left)

private fun maxHintObservation(left: Long?, right: Long?): Long? = when {
    left == null -> right
    right == null -> left
    else -> maxOf(left, right)
}

private fun sumHintObservation(left: Long?, right: Long?): Long? = when {
    left == null -> right
    right == null -> left
    else -> sumHintUsage(left, right)
}
