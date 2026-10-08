package com.eignex.klause.solver.result

/** A channel a portfolio arm shares with the rest of its pool through. */
enum class SharingChannel {
    /** Learned clauses and globally valid nogoods. */
    Clauses,

    /** Globally valid LP cuts. */
    Cuts,

    /** The proven lower bound on the objective. */
    Floor,

    /** Globally valid root bounds on integer variables. */
    Bounds,

    /** Verified incumbents. */
    Incumbents,
}

/** What one arm's traffic on one [SharingChannel] cost and moved. */
data class ChannelTraffic(
    /** Nanoseconds the arm spent exchanging on the channel. */
    val nanos: Long = 0L,
    /** Entries the arm published. */
    val exported: Long = 0L,
    /** Entries the arm took in from other arms. */
    val imported: Long = 0L,
    /** Entries the arm received but had already seen, and so dropped. */
    val duplicates: Long = 0L,
)

/** What one portfolio arm's sharing cost and moved, by channel; channels it never touched are absent. */
data class SharingStats(
    /** Traffic per channel the arm exchanged on. */
    val channels: Map<SharingChannel, ChannelTraffic> = emptyMap(),
) {
    /** Nanoseconds spent across every channel. */
    val nanos: Long get() = channels.values.sumOf { it.nanos }
}
