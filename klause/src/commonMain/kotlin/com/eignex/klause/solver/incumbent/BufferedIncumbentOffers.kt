package com.eignex.klause.solver.incumbent

import com.eignex.klause.util.Cancellation

internal enum class IncumbentOfferEvent {
    Segments, Offered, Coalesced, Checked, Installed, Rejected, Indeterminate, NotImproving, Dropped, Expired, Failed,
}

internal class BufferedIncumbentOffers<A, V>(
    private val improves: (V, V) -> Boolean,
    private val observe: (IncumbentOfferEvent) -> Unit,
) {
    private class Pending<A, V>(val assignment: A, val objective: V, val measure: (() -> Unit) -> Unit)
    private var pending: Pending<A, V>? = null

    init {
        observe(IncumbentOfferEvent.Segments)
    }

    fun add(assignment: A, objective: V, measure: (() -> Unit) -> Unit) {
        observe(IncumbentOfferEvent.Offered)
        val previous = pending
        if (previous == null) pending = Pending(assignment, objective, measure)
        else {
            val replace = try {
                improves(objective, previous.objective)
            } catch (failure: Throwable) {
                observe(IncumbentOfferEvent.Dropped)
                throw failure
            }
            if (replace) pending = Pending(assignment, objective, measure)
            observe(IncumbentOfferEvent.Coalesced)
        }
    }

    fun flush(
        cancellation: Cancellation,
        measure: (() -> Unit) -> Unit,
        offer: (A, V) -> Publication<A, V>?,
    ) {
        val candidate = pending ?: return
        if (cancellation()) {
            observe(IncumbentOfferEvent.Expired)
            discard()
            return
        }
        pending = null
        observe(IncumbentOfferEvent.Checked)
        try {
            measure {
                candidate.measure {
                    val event = when (offer(candidate.assignment, candidate.objective)) {
                        is Publication.Installed -> IncumbentOfferEvent.Installed
                        is Publication.Rejected -> IncumbentOfferEvent.Rejected
                        is Publication.Indeterminate -> IncumbentOfferEvent.Indeterminate
                        Publication.NotImproving -> IncumbentOfferEvent.NotImproving
                        null -> IncumbentOfferEvent.Expired
                    }
                    observe(event)
                }
            }
        } catch (failure: Throwable) {
            observe(IncumbentOfferEvent.Failed)
            throw failure
        }
    }

    fun discard() {
        if (pending == null) return
        pending = null
        observe(IncumbentOfferEvent.Dropped)
    }
}
