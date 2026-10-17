package com.eignex.klause.bench.profile

import java.lang.ref.WeakReference

internal class LpScopeObservation(
    private val ownerLimit: Int = 128,
    private val traceLimit: Int = 1024,
    private val eventLimit: Long = 1_000_000L,
) {
    enum class Method { PUSH, PUBLISH, SOLVE, FLOAT, WORKING, CLOSE, PROPAGATOR }

    class State(
        val identity: Any,
        val model: Any,
        val context: Any,
        val policy: Any,
        val factory: Any,
        val revisions: List<Long>,
        val depth: Int,
        val closed: Boolean = false,
        val working: Boolean = false,
        val production: Boolean = true,
        val editAttempts: Long = 0L,
        val editSuccesses: Long = 0L,
        val generation: List<Long> = emptyList(),
        val numericalOwner: Any? = null,
    )

    class Identity(value: Any?) {
        private val absent = value == null
        private val reference = value?.let(::WeakReference)

        fun matches(value: Any?): Boolean = if (absent) value == null else
            reference?.get()?.let { it === value } == true

        fun matches(other: Identity): Boolean = if (absent) other.absent else
            reference?.get()?.let { other.matches(it) } == true
    }

    class Controls(val values: List<Long>, references: List<Any?> = emptyList()) {
        private val identities = references.map(::Identity)

        fun matches(other: Controls): Boolean = values == other.values &&
            identities.size == other.identities.size && identities.indices.all {
                identities[it].matches(other.identities[it])
            }
    }

    private class Stamp(state: State) {
        val identity = Identity(state.identity)
        val model = Identity(state.model)
        val context = Identity(state.context)
        val policy = Identity(state.policy)
        val factory = Identity(state.factory)
        val revisions = state.revisions.toList()
        val depth = state.depth
        val editAttempts = state.editAttempts
        val editSuccesses = state.editSuccesses
        val generation = state.generation.toList()
        val numericalOwner = Identity(state.numericalOwner)

        fun sameMath(state: State): Boolean = model.matches(state.model) && revisions == state.revisions &&
            context.matches(state.context) && policy.matches(state.policy) && factory.matches(state.factory)
    }

    private class Origin(
        val stamp: Stamp,
        val root: Identity,
        val session: Identity,
        val binding: Long,
        val controls: Controls,
        val kind: String,
        val pushes: Int = 0,
    ) {
        fun pushed(): Origin = Origin(stamp, root, session, binding, controls, kind, pushes + 1)
    }

    private class Frame(
        val method: Method,
        val stamp: Stamp,
        val controls: Controls,
        val origin: Origin?,
        val serial: Long,
        val binding: Long,
        val started: Long,
        val entryMatch: Boolean,
    )

    private class Owner(value: Any, val id: Long) {
        val reference = WeakReference(value)
        val frames = ArrayDeque<Frame>()
        var root = Identity(null)
        var session = Identity(null)
        var bound = false
        var binding = 0L
        var serial = 0L
        var origin: Origin? = null
        var lastPublished: Identity? = null
        var thread: Long? = null
        var state = Identity(null)
        var model = Identity(null)
        var stateEpoch = 0L
        var modelEpoch = 0L
    }

    private val owners = ArrayList<Owner>()
    private val entries = LongArray(Method.entries.size)
    private val returns = LongArray(Method.entries.size)
    private val trace = ArrayList<Map<String, Any?>>()
    private val failures = LinkedHashSet<String>()
    private var nextOwner = 0L
    private var events = 0L
    private var droppedTrace = 0L
    private var ownerHighWater = 0
    private var matchedEntries = 0L
    private var matchedReturns = 0L
    private var matchedNanos = 0L
    private var solveNanos = 0L
    private var missingRootCandidates = 0L
    private var positiveReturns = 0L
    private var purePushes = 0L
    private val priorKinds = linkedMapOf("WITNESS" to 0L, "BOUND" to 0L, "BOTH" to 0L)
    private val returnedKinds = linkedMapOf("NULL" to 0L, "NONE" to 0L, "WITNESS" to 0L, "BOUND" to 0L,
        "BOTH" to 0L, "OTHER" to 0L)
    var disabled: Boolean = false
        private set

    fun ledger(method: Method, entering: Boolean) {
        val counts = if (entering) entries else returns
        if (counts[method.ordinal] == Long.MAX_VALUE || events == Long.MAX_VALUE) {
            fail("counter-saturated")
            return
        }
        counts[method.ordinal]++
        events++
        if (events > eventLimit) fail("event-cap")
    }

    fun fail(reason: String) {
        if (failures.size < 16) failures += reason.take(96)
        disabled = true
    }

    fun bind(value: Any, root: Any?, session: Any?) {
        val owner = owner(value) ?: return
        if (!owner.root.matches(root) || !owner.session.matches(session)) {
            owner.binding++
            owner.origin = null
        }
        owner.root = Identity(root)
        owner.session = Identity(session)
        owner.bound = root != null && session != null
    }

    fun enter(
        method: Method,
        value: Any,
        state: State,
        controls: Controls,
        now: Long,
        thread: Long,
    ) {
        if (disabled || method == Method.PROPAGATOR) return
        val owner = owner(value) ?: return
        if (owner.frames.size >= 16 || (owner.frames.isNotEmpty() && owner.thread != thread)) {
            fail("owner-overlap-or-frame-cap")
            return
        }
        owner.thread = thread
        val origin = owner.origin
        val basic = origin != null && origin.pushes > 0 && origin.stamp.sameMath(state) &&
            origin.binding == owner.binding && origin.controls.matches(controls) &&
            state.editAttempts == origin.stamp.editAttempts + origin.pushes &&
            state.editSuccesses == origin.stamp.editSuccesses + origin.pushes &&
            state.generation == origin.stamp.generation &&
            origin.stamp.numericalOwner.matches(state.numericalOwner) &&
            !state.closed && !state.working && state.production
        val bindingLive = owner.bound && origin != null && owner.root.matches(owner.root) &&
            owner.session.matches(owner.session) && origin.root.matches(origin.root) && origin.session.matches(origin.session)
        val match = basic && bindingLive && origin != null && origin.root.matches(owner.root) &&
            origin.session.matches(owner.session)
        if (method == Method.SOLVE && basic && !bindingLive) missingRootCandidates++
        if (method == Method.SOLVE && match) {
            matchedEntries++
            origin?.let { priorKinds[it.kind] = priorKinds.getValue(it.kind) + 1L }
        }
        owner.frames.addLast(Frame(method, Stamp(state), controls, origin, owner.serial, owner.binding, now, match))
        if (method != Method.PUSH) {
            owner.origin = null
        }
        if (method == Method.WORKING || method == Method.CLOSE ||
            (method == Method.FLOAT && owner.frames.dropLast(1).none { it.method == Method.SOLVE })
        ) owner.serial++
        emit(owner, method, "entry", state, match)
    }

    fun returned(
        method: Method,
        value: Any,
        state: State,
        resultKind: String,
        acceptedPush: Boolean,
        now: Long,
    ) {
        if (disabled || method == Method.PROPAGATOR) return
        val owner = owner(value) ?: return
        val frame = owner.frames.lastOrNull()
        if (frame == null || frame.method != method) {
            fail("unmatched-normal-return")
            return
        }
        owner.frames.removeLast()
        when (method) {
            Method.PUBLISH -> {
                owner.serial++
                owner.lastPublished = Identity(state.identity)
            }
            Method.PUSH -> {
                val pure = acceptedPush && frame.stamp.sameMath(state) &&
                    state.depth == frame.stamp.depth + 1 && owner.serial == frame.serial + 1L &&
                    state.editAttempts == frame.stamp.editAttempts + 1L &&
                    state.editSuccesses == frame.stamp.editSuccesses + 1L &&
                    state.generation == frame.stamp.generation &&
                    frame.stamp.numericalOwner.matches(state.numericalOwner) &&
                    owner.lastPublished?.matches(state.identity) == true &&
                    !state.closed && !state.working && frame.binding == owner.binding
                if (pure) {
                    purePushes++
                    owner.origin = frame.origin?.pushed()
                } else {
                    owner.origin = null
                }
            }
            Method.SOLVE -> {
                val duration = (now - frame.started).coerceAtLeast(0L)
                solveNanos = add(solveNanos, duration)
                val unchanged = frame.stamp.identity.matches(state.identity) && frame.stamp.sameMath(state) &&
                    frame.binding == owner.binding && !state.closed && !state.working && state.production
                if (frame.entryMatch && unchanged) {
                    matchedReturns++
                    matchedNanos = add(matchedNanos, duration)
                    if (resultKind in returnedKinds) returnedKinds[resultKind] = returnedKinds.getValue(resultKind) + 1L
                }
                if (unchanged && (resultKind == "WITNESS" || resultKind == "BOUND" || resultKind == "BOTH")) {
                    positiveReturns++
                    owner.origin = Origin(Stamp(state), owner.root, owner.session, owner.binding,
                        frame.controls, resultKind)
                }
            }
            else -> Unit
        }
        emit(owner, method, "return:$resultKind", state, frame.entryMatch)
        if (method == Method.CLOSE) {
            if (owner.frames.isNotEmpty()) fail("closed-owner-has-unfinished-entry")
            owners.remove(owner)
        }
    }

    fun snapshot(): Map<String, Any?> {
        val unmatched = Method.entries.associate { method ->
            method.name to (entries[method.ordinal] - returns[method.ordinal])
        }
        val complete = !disabled && unmatched.values.all { it == 0L } && missingRootCandidates == 0L &&
            owners.all { it.frames.isEmpty() }
        return linkedMapOf(
            "status" to if (complete) "COMPLETE" else "INCONCLUSIVE",
            "failures" to failures.toList(),
            "methodEntries" to Method.entries.associate { it.name to entries[it.ordinal] },
            "methodNormalReturns" to Method.entries.associate { it.name to returns[it.ordinal] },
            "methodUnmatchedEntries" to unmatched,
            "observedEvents" to events,
            "ownersSeen" to nextOwner,
            "activeOwners" to owners.size,
            "ownerHighWater" to ownerHighWater,
            "positiveReturns" to positiveReturns,
            "purePushes" to purePushes,
            "matchingSolveEntries" to matchedEntries,
            "matchingPriorKinds" to priorKinds.toMap(),
            "matchingNormalSolveReturns" to matchedReturns,
            "matchingReturnedKinds" to returnedKinds.toMap(),
            "matchingSolveElapsedNs" to matchedNanos,
            "allSolveElapsedNs" to solveNanos,
            "timingScope" to "inclusive instrumented method durations; nested owners may overlap",
            "missingRootCandidates" to missingRootCandidates,
            "traceDropped" to droppedTrace,
            "trace" to trace.toList(),
            "ordinaryTimingOrRecoveryCredit" to false,
            "certificateReuseAuthority" to false,
        )
    }

    private fun owner(value: Any): Owner? {
        owners.removeAll { current ->
            if (current.reference.get() != null) false else {
                if (current.frames.isNotEmpty()) fail("evicted-owner-has-unfinished-entry")
                true
            }
        }
        if (disabled) return null
        owners.firstOrNull { it.reference.get() === value }?.let { return it }
        if (owners.size == ownerLimit) {
            fail("owner-cap")
            return null
        }
        return Owner(value, ++nextOwner).also { owners += it; ownerHighWater = maxOf(ownerHighWater, owners.size) }
    }

    private fun emit(owner: Owner, method: Method, stage: String, state: State, matched: Boolean) {
        if (trace.size >= traceLimit) {
            droppedTrace++
            return
        }
        if (!owner.state.matches(state.identity)) owner.stateEpoch++
        if (!owner.model.matches(state.model)) owner.modelEpoch++
        owner.state = Identity(state.identity)
        owner.model = Identity(state.model)
        trace += linkedMapOf("owner" to owner.id, "method" to method.name, "stage" to stage,
            "depth" to state.depth, "revisions" to state.revisions.toList(), "binding" to owner.binding,
            "sourceBound" to owner.bound, "matchedEntry" to matched,
            "stateEpoch" to owner.stateEpoch, "modelEpoch" to owner.modelEpoch,
            "editAttempts" to state.editAttempts, "editSuccesses" to state.editSuccesses,
            "ownerGeneration" to state.generation.toList(),
            "positiveKind" to owner.origin?.kind, "positivePushes" to owner.origin?.pushes)
    }

    private fun add(total: Long, increment: Long): Long =
        if (increment > Long.MAX_VALUE - total) Long.MAX_VALUE else total + increment
}
