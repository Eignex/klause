package com.eignex.klause.bench.profile

import java.lang.ref.WeakReference

internal class LpScopeObservation(
    private val ownerLimit: Int = 128,
    private val traceLimit: Int = 1024,
    private val eventLimit: Long = 1_000_000L,
) {
    enum class Method {
        PUSH, PUBLISH, SOLVE, FLOAT, WORKING, CLOSE, PROPAGATOR, REPLACE, SCAN_POINT, CHOOSE_POINT_CORRECTION;

        val staticHelper: Boolean get() = this == SCAN_POINT || this == CHOOSE_POINT_CORRECTION
    }

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

    class Shape(val identity: Any, val matrix: List<*>, val rows: Int, val total: Int) {
        val structural: Int get() = matrix.size

        init {
            require(rows >= 0 && total >= 0 && matrix.size.toLong() + rows == total.toLong())
        }

        fun dimensions(): List<Int> = listOf(structural, rows, total)
    }

    class Shapes(val current: Shape, val candidate: Shape? = null, val append: Boolean = false)

    class Recovery(val decline: String?, val phase: String?, val counters: Map<String, Long>)

    class Certificate(
        val verdict: String?,
        val floatOptimal: Boolean?,
        val witness: Boolean,
        val bound: Boolean,
        val recovery: Map<String, Recovery?>,
    )

    private class PointSites {
        val entries = LongArray(2)
        val returns = LongArray(2)
        val sites = LongArray(6)

        fun snapshot(): Map<String, Any?> = mapOf(
            "entries" to listOf("SCAN_POINT", "CHOOSE_POINT_CORRECTION").zip(entries.toList()).toMap(),
            "normalReturns" to listOf("SCAN_POINT", "CHOOSE_POINT_CORRECTION").zip(returns.toList()).toMap(),
            "sites" to listOf(
                "SCAN_SOURCE_COLUMN_BOUND", "SCAN_STRICT_LOGICAL_BOUNDARY", "SCAN_READY",
                "CORRECTION_CANDIDATE_CAP", "CORRECTION_NO_ADMISSIBLE", "CORRECTION_SELECTED",
            ).zip(sites.toList()).toMap(),
        )
    }

    private class RecoveryCell {
        var present = 0L
        var absent = 0L
        val declines = LinkedHashMap<String, Long>()
        val phases = LinkedHashMap<String, Long>()
        val counters = LinkedHashMap<String, Long>()
    }

    private class CertificateCell(
        val certificate: Certificate,
        val sourceBound: Boolean,
        val production: Boolean,
    ) {
        var returns = 0L
        val pointSites = PointSites()
        val recovery = listOf("reconstruction", "exactPoint", "refinement", "basisVerification")
            .associateWith { RecoveryCell() }
    }

    private class ShapeCell {
        var entries = 0L
        var accepted = 0L
        var rejected = 0L
        var nanos = 0L
        val minimum = IntArray(6) { Int.MAX_VALUE }
        val maximum = IntArray(6)
    }

    private class ShapeFrame(
        val before: Identity,
        val candidate: Identity,
        val dimensions: List<Int>,
        val bucket: Int,
        val sample: MutableMap<String, Any?>?,
    )

    class Identity(value: Any?) {
        private val absent = value == null
        private val reference = value?.let(::WeakReference)

        fun matches(value: Any?): Boolean = if (absent) {
            value == null
        } else {
            reference?.get()?.let { it === value } == true
        }

        fun matches(other: Identity): Boolean = if (absent) {
            other.absent
        } else {
            reference?.get()?.let { other.matches(it) } == true
        }
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
        val shape: ShapeFrame?,
        val observationSequence: Long,
    ) {
        val pointSites: PointSites? = if (method == Method.SOLVE) PointSites() else null
    }

    private class HelperFrame(val method: Method, val thread: Long, val ownerId: Long, val solve: Frame)

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
        var queried = false
        var replacements = 0L
        var lastShapeBucket = -1
    }

    private val owners = ArrayList<Owner>()
    private val helperFrames = ArrayDeque<HelperFrame>()
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
    private val shapeCells = arrayOfNulls<ShapeCell>(8192)
    private val shapeSamples = ArrayList<Map<String, Any?>>()
    private val shapeRefusals = linkedMapOf("column-budget" to 0L, "snapshot-width" to 0L, "snapshot-count" to 0L)
    private var columnVisits = 0L
    private val certificateCells = LinkedHashMap<String, CertificateCell>()
    private var certificateReturns = 0L

    private val returnedKinds = linkedMapOf(
        "NULL" to 0L,
        "NONE" to 0L,
        "WITNESS" to 0L,
        "BOUND" to 0L,
        "BOTH" to 0L,
        "OTHER" to 0L,
    )
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
        shapes: Shapes? = null,
    ) {
        if (disabled || method == Method.PROPAGATOR) return
        val owner = owner(value) ?: return
        if (owner.frames.size + helperFrames.count { it.ownerId == owner.id } >= 16 ||
            (owner.frames.isNotEmpty() && owner.thread != thread)
        ) {
            fail("owner-overlap-or-frame-cap")
            return
        }
        owner.thread = thread
        val replacement = shapeEntry(method, owner, state, shapes)
        val origin = owner.origin
        val basic = origin != null && origin.pushes > 0 && origin.stamp.sameMath(state) &&
            origin.binding == owner.binding && origin.controls.matches(controls) &&
            state.editAttempts == origin.stamp.editAttempts + origin.pushes &&
            state.editSuccesses == origin.stamp.editSuccesses + origin.pushes &&
            state.generation == origin.stamp.generation &&
            origin.stamp.numericalOwner.matches(state.numericalOwner) &&
            !state.closed && !state.working && state.production
        val bindingLive = owner.bound && origin != null && owner.root.matches(owner.root) &&
            owner.session.matches(
                owner.session,
            ) && origin.root.matches(origin.root) && origin.session.matches(origin.session)
        val match = basic && bindingLive && origin != null && origin.root.matches(owner.root) &&
            origin.session.matches(owner.session)
        if (method == Method.SOLVE && basic && !bindingLive) missingRootCandidates++
        if (method == Method.SOLVE && match) {
            matchedEntries++
            priorKinds[origin.kind] = priorKinds.getValue(origin.kind) + 1L
        }
        owner.frames.addLast(
            Frame(method, Stamp(state), controls, origin, owner.serial, owner.binding, now, match, replacement, events)
        )
        if (method != Method.PUSH) {
            owner.origin = null
        }
        if (method == Method.WORKING || method == Method.CLOSE ||
            (method == Method.FLOAT && owner.frames.dropLast(1).none { it.method == Method.SOLVE })
        ) {
            owner.serial++
        }
        emit(owner, method, "entry", state, match)
    }

    fun helperEntered(method: Method, thread: Long) {
        if (disabled) return
        if (!method.staticHelper || helperFrames.size >= ownerLimit * 16 ||
            helperFrames.count { it.thread == thread } >= 16
        ) {
            fail("helper-method-or-frame-cap")
            return
        }
        val contexts = owners.filter { it.thread == thread }.flatMap { owner ->
            owner.frames.map { owner to it }
        }
        val latest = contexts.maxOfOrNull { it.second.observationSequence }
        val matches = contexts.filter { it.second.observationSequence == latest }
        if (matches.size != 1) {
            fail(if (matches.isEmpty()) "helper-missing-solve-context" else "helper-ambiguous-solve-context")
            return
        }
        val (owner, solve) = matches.single()
        if (solve.method != Method.SOLVE) {
            fail("helper-intervening-observed-context")
            return
        }
        if (owner.frames.size + helperFrames.count { it.ownerId == owner.id } >= 16) {
            fail("helper-method-or-frame-cap")
            return
        }
        val frame = HelperFrame(method, thread, owner.id, solve)
        helperFrames.addLast(frame)
        requireNotNull(solve.pointSites).entries[if (method == Method.SCAN_POINT) 0 else 1]++
        emitHelper(frame, "entry", null, null)
    }

    fun helperReturned(method: Method, thread: Long, site: Int, nonNull: Boolean) {
        if (disabled) return
        val frame = helperFrames.lastOrNull { it.thread == thread }
        if (frame == null || frame.method != method || !method.staticHelper) {
            fail("helper-unmatched-normal-return")
            return
        }
        val live = owners.singleOrNull { it.id == frame.ownerId }
        if (live == null || frame.solve !in live.frames) {
            fail("helper-retired-solve-context")
            return
        }
        val label = when (method) {
            Method.SCAN_POINT -> when {
                site in 0..1 && !nonNull -> site
                site == 2 && nonNull -> 2
                else -> -1
            }
            Method.CHOOSE_POINT_CORRECTION -> when {
                site == 0 && !nonNull -> 3
                site == 1 -> if (nonNull) 5 else 4
                else -> -1
            }
            else -> -1
        }
        if (label < 0) {
            fail("helper-return-site-value-mismatch")
            return
        }
        helperFrames.remove(frame)
        val counts = requireNotNull(frame.solve.pointSites)
        counts.returns[if (method == Method.SCAN_POINT) 0 else 1]++
        counts.sites[label]++
        emitHelper(frame, "return", site, nonNull)
    }

    private fun emitHelper(frame: HelperFrame, stage: String, site: Int?, nonNull: Boolean?) {
        if (trace.size >= traceLimit) {
            droppedTrace++
            return
        }
        trace += mapOf(
            "owner" to frame.ownerId, "method" to frame.method.name, "stage" to stage,
            "solveObservationSequence" to frame.solve.observationSequence,
            "returnSite" to site, "nonNull" to nonNull,
        )
    }

    fun returned(
        method: Method,
        value: Any,
        state: State,
        resultKind: String,
        acceptedPush: Boolean,
        now: Long,
        shape: Shape? = null,
        certificate: Certificate? = null,
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
            Method.REPLACE -> replacementReturned(frame, state, acceptedPush, now, shape)

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
                if (helperFrames.any { it.solve === frame }) fail("helper-unfinished-at-solve-return")
                if (certificate == null) fail("missing-certificate-metadata")
                else recordCertificate(resultKind, certificate, owner.bound, state.production,
                    requireNotNull(frame.pointSites))
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
                    owner.origin = Origin(
                        Stamp(state),
                        owner.root,
                        owner.session,
                        owner.binding,
                        frame.controls,
                        resultKind,
                    )
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

    private fun replacementReturned(frame: Frame, state: State, accepted: Boolean, now: Long, shape: Shape?) {
        val replacement = requireNotNull(frame.shape)
        val current = requireNotNull(shape)
        val expected = if (accepted) replacement.candidate else replacement.before
        val dimensions = if (accepted) replacement.dimensions.takeLast(3) else replacement.dimensions.take(3)
        if (!expected.matches(state.model) || current.identity !== state.model || current.dimensions() != dimensions) {
            fail("replacement-publication-mismatch")
            return
        }
        val cell = requireNotNull(shapeCells[replacement.bucket])
        if (accepted) cell.accepted++ else cell.rejected++
        cell.nanos = add(cell.nanos, (now - frame.started).coerceAtLeast(0L))
        replacement.sample?.put("normalReturnAccepted", accepted)
    }

    fun snapshot(): Map<String, Any?> {
        val unmatched = Method.entries.associate { method ->
            method.name to (entries[method.ordinal] - returns[method.ordinal])
        }
        val replacementEntries = shapeCells.filterNotNull().sumOf { it.entries }
        val replacementReturns = shapeCells.filterNotNull().sumOf { it.accepted + it.rejected }
        val helpersComplete = listOf(Method.SCAN_POINT, Method.CHOOSE_POINT_CORRECTION).withIndex().all { (i, method) ->
            certificateCells.values.sumOf { it.pointSites.entries[i] } == entries[method.ordinal] &&
                certificateCells.values.sumOf { it.pointSites.returns[i] } == returns[method.ordinal]
        }
        val complete = helpersComplete && helperFrames.isEmpty() &&
            certificateReturns == returns[Method.SOLVE.ordinal] &&
            replacementEntries == entries[Method.REPLACE.ordinal] &&
            replacementReturns == returns[Method.REPLACE.ordinal] && !disabled &&
            unmatched.values.all { it == 0L } && missingRootCandidates == 0L &&
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
            "appendShape" to shapeSnapshot(),
            "returnedCertificateMetadata" to certificateSnapshot(),
            "ordinaryTimingOrRecoveryCredit" to false,
            "certificateReuseAuthority" to false,
        )
    }

    private fun certificateKind(certificate: Certificate): String? = when (certificate.verdict) {
        null -> if (certificate.floatOptimal == null && !certificate.witness && !certificate.bound &&
            certificate.recovery.values.all { it == null }
        ) "NULL" else null
        "CERTIFIED_BOUND" -> if (certificate.bound && !certificate.witness) "BOUND" else null
        "FEASIBLE" -> if (certificate.witness) {
            if (certificate.bound) "BOTH" else "WITNESS"
        } else null
        "ATTAINED_OPTIMUM" -> if (certificate.witness && certificate.bound) "BOTH" else null
        "INDETERMINATE" -> if (!certificate.witness && !certificate.bound) "NONE" else null
        "INFEASIBLE", "UNBOUNDED", "TOLERANCE_OPTIMUM" -> "OTHER"
        else -> null
    }

    private fun recordCertificate(
        kind: String, certificate: Certificate, sourceBound: Boolean, production: Boolean, pointSites: PointSites,
    ) {
        if (certificateKind(certificate) != kind) {
            fail("certificate-cohort-mismatch")
            return
        }
        val key = listOf(
            sourceBound, production, kind, certificate.verdict,
            certificate.floatOptimal, certificate.witness, certificate.bound,
        ).joinToString("|")
        if (key !in certificateCells && certificateCells.size == 128) {
            fail("certificate-cohort-cap")
            return
        }
        val cell = certificateCells.getOrPut(key) {
            CertificateCell(Certificate(
                certificate.verdict, certificate.floatOptimal, certificate.witness, certificate.bound, emptyMap(),
            ), sourceBound, production)
        }
        if (certificate.recovery.keys != cell.recovery.keys) {
            fail("certificate-metadata-shape")
            return
        }
        certificateReturns++
        cell.returns++
        for (i in pointSites.entries.indices) {
            cell.pointSites.entries[i] = certificateAdd(cell.pointSites.entries[i], pointSites.entries[i])
            cell.pointSites.returns[i] = certificateAdd(cell.pointSites.returns[i], pointSites.returns[i])
        }
        for (i in pointSites.sites.indices) {
            cell.pointSites.sites[i] = certificateAdd(cell.pointSites.sites[i], pointSites.sites[i])
        }
        for ((name, recovery) in certificate.recovery) {
            val detail = cell.recovery.getValue(name)
            if (recovery == null) {
                detail.absent++
            } else {
                if (recovery.counters.size > 16 || recovery.counters.any { (key, value) ->
                        key.length !in 1..48 || value < 0L
                    } || listOfNotNull(recovery.decline, recovery.phase).any { it.length !in 1..48 }
                ) {
                    fail("certificate-invalid-scalar")
                    return
                }
                detail.present++
                countLabel(detail.declines, recovery.decline ?: "NO_DECLINE")
                countLabel(detail.phases, recovery.phase ?: "NO_PHASE")
                for ((counter, value) in recovery.counters) {
                    if (counter !in detail.counters && detail.counters.size == 16) {
                        fail("certificate-scalar-cap")
                        return
                    }
                    detail.counters[counter] = certificateAdd(detail.counters[counter] ?: 0L, value)
                }
            }
        }
    }

    private fun countLabel(labels: MutableMap<String, Long>, label: String) {
        if (label !in labels && labels.size == 32) {
            fail("certificate-label-cap")
            return
        }
        labels[label] = certificateAdd(labels[label] ?: 0L, 1L)
    }

    private fun certificateAdd(total: Long, increment: Long): Long = if (increment > Long.MAX_VALUE - total) {
        fail("certificate-counter-overflow")
        Long.MAX_VALUE
    } else total + increment

    private fun certificateSnapshot(): Map<String, Any?> = mapOf(
        "normalSolveReturns" to certificateReturns,
        "missingMetadataMeaning" to "unobserved retained result metadata; does not establish an absent attempt",
        "completeCertifierItinerary" to false,
        "helperAssociationScope" to
            "innermost same-thread observed SOLVE interval; not direct numerical owner authority",
        "cohorts" to certificateCells.map { (key, cell) ->
            mapOf(
                "key" to key, "sourceBound" to cell.sourceBound, "production" to cell.production,
                "verdict" to cell.certificate.verdict,
                "floatOptimal" to cell.certificate.floatOptimal,
                "witnessPresent" to cell.certificate.witness, "boundPresent" to cell.certificate.bound,
                "normalReturns" to cell.returns,
                "pointRecoverySites" to cell.pointSites.snapshot(),
                "recovery" to cell.recovery.mapValues { (_, detail) ->
                    mapOf(
                        "present" to detail.present, "absent" to detail.absent,
                        "declines" to detail.declines.toMap(), "phases" to detail.phases.toMap(),
                        "counterSums" to detail.counters.toMap(),
                    )
                },
            )
        },
    )

    private fun shapeEntry(method: Method, owner: Owner, state: State, shapes: Shapes?): ShapeFrame? {
        if (method != Method.REPLACE && method != Method.SOLVE && method != Method.FLOAT) return null
        val shape = requireNotNull(shapes).current
        require(shape.identity === state.model)
        if ((method == Method.SOLVE || method == Method.FLOAT) && !owner.queried) {
            sample(owner.id, "current-at-first-observed-solve-or-float", shape)
            owner.queried = true
        }
        if (method != Method.REPLACE) return null
        val candidate = requireNotNull(shapes.candidate)
        val phase = (if (state.numericalOwner != null) 4 else 0) +
            (if (owner.queried) 2 else 0) + if (shapes.append) 1 else 0
        val bucket = (bucket(candidate.structural) * 32 + bucket(candidate.rows)) * 8 + phase
        val cell = shapeCells[bucket] ?: ShapeCell().also { shapeCells[bucket] = it }
        val dimensions = shape.dimensions() + candidate.dimensions()
        cell.entries++
        for (index in dimensions.indices) {
            cell.minimum[index] = minOf(cell.minimum[index], dimensions[index])
            cell.maximum[index] = maxOf(cell.maximum[index], dimensions[index])
        }
        owner.replacements++
        val detail = if (owner.replacements <= 8L || bucket != owner.lastShapeBucket) {
            sample(owner.id, "candidate-on-replace-entry", candidate)
        } else null
        owner.lastShapeBucket = bucket
        return ShapeFrame(Identity(shape.identity), Identity(candidate.identity), dimensions, bucket, detail)
    }

    private fun bucket(value: Int): Int = if (value == 0) 0 else 32 - Integer.numberOfLeadingZeros(value)

    private fun sample(owner: Long, role: String, shape: Shape): MutableMap<String, Any?>? {
        val refusal = when {
            shapeSamples.size >= 128 -> "snapshot-count"
            shape.structural > 65536 -> "snapshot-width"
            shape.structural > 262144L - columnVisits -> "column-budget"
            else -> null
        }
        if (refusal != null) {
            shapeRefusals[refusal] = shapeRefusals.getValue(refusal) + 1L
            return null
        }
        var nonempty = 0
        for (column in shape.matrix) {
            require(column is List<*>)
            columnVisits++
            if (column.isNotEmpty()) nonempty++
        }
        return linkedMapOf<String, Any?>(
            "owner" to owner, "role" to role, "dimensions" to shape.dimensions(),
            "storedNonemptyStructuralColumns" to nonempty,
            "normalReturnAccepted" to null,
        ).also { shapeSamples += it }
    }

    private fun shapeSnapshot(): Map<String, Any?> {
        val cells = shapeCells.mapIndexedNotNull { index, cell ->
            cell?.let {
                linkedMapOf(
                    "structuralWidthBucket" to index / 8 / 32,
                    "rowBucket" to index / 8 % 32,
                    "nativeSolverPresent" to (index % 8 and 4 != 0),
                    "afterObservedSolveOrFloat" to (index % 8 and 2 != 0),
                    "append" to (index % 8 and 1 != 0),
                    "entries" to it.entries, "accepted" to it.accepted, "rejected" to it.rejected,
                    "elapsedNs" to it.nanos,
                    "minimumOldThenCandidateDimensions" to it.minimum.toList(),
                    "maximumOldThenCandidateDimensions" to it.maximum.toList(),
                )
            }
        }
        return linkedMapOf(
            "replacementEntries" to shapeCells.filterNotNull().sumOf { it.entries },
            "replacementAccepted" to shapeCells.filterNotNull().sumOf { it.accepted },
            "replacementRejected" to shapeCells.filterNotNull().sumOf { it.rejected },
            "shapeBuckets" to cells,
            "columnVisits" to columnVisits,
            "columnVisitLimit" to 262144, "snapshotWidthLimit" to 65536, "snapshotCountLimit" to 128,
            "sampleRefusals" to shapeRefusals.toMap(),
            "storedNonemptyColumnSamples" to shapeSamples.toList(),
            "columnScope" to "stored matrix entries, irrespective of row activation or current bound activity",
            "phaseScope" to "first observed scoped solve or solveFloat entry; preparation may perform numerical work",
            "sampleCoverage" to "deterministic bounded samples, not an active-column or full nonzero ledger",
        )
    }

    private fun owner(value: Any): Owner? {
        owners.removeAll { current ->
            if (current.reference.get() != null) {
                false
            } else {
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
        return Owner(value, ++nextOwner).also {
            owners += it;
            ownerHighWater = maxOf(ownerHighWater, owners.size)
        }
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
        trace += linkedMapOf(
            "owner" to owner.id, "method" to method.name, "stage" to stage,
            "depth" to state.depth, "revisions" to state.revisions.toList(), "binding" to owner.binding,
            "sourceBound" to owner.bound, "matchedEntry" to matched,
            "stateEpoch" to owner.stateEpoch, "modelEpoch" to owner.modelEpoch,
            "editAttempts" to state.editAttempts, "editSuccesses" to state.editSuccesses,
            "ownerGeneration" to state.generation.toList(),
            "positiveKind" to owner.origin?.kind, "positivePushes" to owner.origin?.pushes,
        )
    }

    private fun add(total: Long, increment: Long): Long =
        if (increment > Long.MAX_VALUE - total) Long.MAX_VALUE else total + increment
}
