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
) {
    /** Merge independent nonnegative usage deltas with saturating arithmetic. */
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
    )
}

private fun sumHintUsage(left: Long, right: Long): Long = left + minOf(right, Long.MAX_VALUE - left)
