package com.eignex.klause.bench.profile

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.lang.ref.WeakReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LpScopeObservationTest {
    @Test
    fun `a positive result followed only by pure pushes should identify the next solve`() {
        val case = ObservationCase()
        case.positive()
        case.push()
        case.push()

        case.positive()

        assertEquals(1L, case.report()["matchingSolveEntries"])
        assertEquals(1L, case.report()["matchingNormalSolveReturns"])
        assertEquals(2L, case.report()["purePushes"])
        assertEquals("COMPLETE", case.report()["status"])
    }

    @Test
    fun `intervening changes should prevent a scope only match`() {
        val revisionNames = listOf("matrix", "bound", "objective", "row", "pop")
        for (change in revisionNames + listOf(
            "model", "root", "session", "policy", "context", "factory", "controls",
            "failed-edit", "numerical-owner", "generation", "publication",
        )) {
            val case = ObservationCase()
            case.positive()
            case.push()
            when (change) {
                "model" -> case.model = Any()
                in revisionNames -> case.revisions[revisionNames.indexOf(change)]++
                "root" -> case.observer.bind(case.owner, Any(), case.session)
                "session" -> case.observer.bind(case.owner, case.root, Any())
                "policy" -> case.policy = Any()
                "context" -> case.context = Any()
                "factory" -> case.factory = Any()
                "controls" -> case.controls = LpScopeObservation.Controls(listOf(99L))
                "failed-edit" -> case.edits++
                "numerical-owner" -> case.numerical = Any()
                "generation" -> case.generation = listOf(1L)
                "publication" -> case.publishOnly()
            }

            case.positive()

            assertEquals(0L, case.report()["matchingSolveEntries"], change)
        }
    }

    @Test
    fun `a null solve return should not establish a fresh positive origin`() {
        val case = ObservationCase()
        case.positive()
        case.push()
        case.enter(LpScopeObservation.Method.SOLVE)
        case.returned(LpScopeObservation.Method.SOLVE, "NULL")
        case.push()

        case.positive()

        assertEquals(1L, case.report()["matchingSolveEntries"])
        assertEquals(1L, (case.report()["matchingReturnedKinds"] as Map<*, *>)["NULL"])
        assertEquals("COMPLETE", case.report()["status"])
    }

    @Test
    fun `working scopes and direct float solves should invalidate prior positive tracking`() {
        for (method in listOf(LpScopeObservation.Method.FLOAT, LpScopeObservation.Method.WORKING)) {
            val case = ObservationCase()
            case.positive()
            case.push()
            case.enter(method)
            case.returned(method)

            case.positive()

            assertEquals(0L, case.report()["matchingSolveEntries"], method.name)
            assertEquals("COMPLETE", case.report()["status"])
        }
    }

    @Test
    fun `an unmatched entry should remain inconclusive after its owner closes`() {
        val case = ObservationCase()
        case.enter(LpScopeObservation.Method.SOLVE)
        case.enter(LpScopeObservation.Method.CLOSE)
        case.returned(LpScopeObservation.Method.CLOSE)
        ObservationCase(case.observer).positive()

        val report = case.report()

        assertEquals("INCONCLUSIVE", report["status"])
        assertEquals(1L, (report["methodUnmatchedEntries"] as Map<*, *>)["SOLVE"])
    }

    @Test
    fun `evicting an unfinished owner should preserve unmatched entry accounting`() {
        val observer = LpScopeObservation()
        val first = ObservationCase(observer)
        first.enter(LpScopeObservation.Method.SOLVE)
        val owners = observer.javaClass.getDeclaredField(
            "owners",
        ).apply { isAccessible = true }.get(observer) as List<*>
        val owner = checkNotNull(owners.first())
        val reference = owner.javaClass.getDeclaredField("reference").apply { isAccessible = true }.get(owner)
        (reference as WeakReference<*>).clear()

        ObservationCase(observer).positive()

        assertEquals("INCONCLUSIVE", observer.snapshot()["status"])
        assertEquals(1L, (observer.snapshot()["methodUnmatchedEntries"] as Map<*, *>)["SOLVE"])
    }

    @Test
    fun `an unmatched entry should survive coverage disablement and new owner activity`() {
        val observer = LpScopeObservation(ownerLimit = 1)
        val first = ObservationCase(observer)
        first.enter(LpScopeObservation.Method.PUSH)
        val second = ObservationCase(observer)
        second.positive()

        val report = observer.snapshot()

        assertEquals("INCONCLUSIVE", report["status"])
        assertEquals(1L, (report["methodUnmatchedEntries"] as Map<*, *>)["PUSH"])
    }

    @Test
    fun `trace saturation should preserve aggregate match counts`() {
        val case = ObservationCase(LpScopeObservation(traceLimit = 1))
        case.positive()
        case.push()

        case.positive()

        assertEquals(1L, case.report()["matchingNormalSolveReturns"])
        assertEquals(1, (case.report()["trace"] as List<*>).size)
        assertEquals("COMPLETE", case.report()["status"])
    }

    @Test
    fun `event capacity exhaustion should prevent a complete capture`() {
        val case = ObservationCase(LpScopeObservation(eventLimit = 1L))

        case.positive()

        assertEquals("INCONCLUSIVE", case.report()["status"])
        assertEquals(0L, (case.report()["methodUnmatchedEntries"] as Map<*, *>)["SOLVE"])
    }

    @Test
    fun `a scope candidate without source binding should remain inconclusive`() {
        val case = ObservationCase()
        case.observer.bind(case.owner, null, null)
        case.positive()
        case.push()

        case.positive()

        assertEquals(1L, case.report()["missingRootCandidates"])
        assertEquals("INCONCLUSIVE", case.report()["status"])
    }

    @ParameterizedTest
    @CsvSource(
        "false,false,false", "false,false,true", "false,true,false", "false,true,true",
        "true,false,false", "true,false,true", "true,true,false", "true,true,true",
    )
    fun `replacement phases should separate native presence observed queries and append`(
        native: Boolean, queried: Boolean, append: Boolean,
    ) {
        val case = ObservationCase()
        case.numerical = if (native) Any() else null
        if (queried) case.positive()

        case.replace(LpScopeObservation.Shape(Any(), listOf(emptyList<Any>(), listOf(Any())), 3, 5), append = append)

        val shape = case.report()["appendShape"] as Map<*, *>
        val cell = (shape["shapeBuckets"] as List<*>).single() as Map<*, *>
        assertEquals(native, cell["nativeSolverPresent"])
        assertEquals(queried, cell["afterObservedSolveOrFloat"])
        assertEquals(append, cell["append"])
        assertEquals(listOf(0, 0, 0, 2, 3, 5), cell["minimumOldThenCandidateDimensions"])
        assertEquals(1L, cell["accepted"])
        assertEquals("COMPLETE", case.report()["status"])
    }

    @Test
    fun `a rejected candidate should remain distinct from the current first query model`() {
        val case = ObservationCase()
        case.replace(LpScopeObservation.Shape(Any(), listOf(emptyList<Any>(), listOf(Any())), 3, 5), accepted = false)

        case.positive()

        val shape = case.report()["appendShape"] as Map<*, *>
        val samples = shape["storedNonemptyColumnSamples"] as List<*>
        val candidate = samples[0] as Map<*, *>
        val current = samples[1] as Map<*, *>
        assertEquals(1L, shape["replacementRejected"])
        assertEquals("candidate-on-replace-entry", candidate["role"])
        assertEquals(false, candidate["normalReturnAccepted"])
        assertEquals(1, candidate["storedNonemptyStructuralColumns"])
        assertEquals(listOf(0, 0, 0), current["dimensions"])
        assertEquals("current-at-first-observed-solve-or-float", current["role"])
        assertEquals("COMPLETE", case.report()["status"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["SOLVE", "FLOAT"])
    fun `first observed query should sample the current shape once`(name: String) {
        val case = ObservationCase()
        val method = LpScopeObservation.Method.valueOf(name)

        repeat(2) {
            case.enter(method)
            case.returned(method)
        }

        val shape = case.report()["appendShape"] as Map<*, *>
        assertEquals(1, (shape["storedNonemptyColumnSamples"] as List<*>).size)
        assertEquals("COMPLETE", case.report()["status"])
    }

    @Test
    fun `inconsistent dimensions should be refused`() {
        assertFailsWith<IllegalArgumentException> {
            LpScopeObservation.Shape(Any(), emptyList<Any>(), 1, 0)
        }
    }

    @Test
    fun `replacement aggregates should cover all calls beyond the trace prefix`() {
        val case = ObservationCase(LpScopeObservation(traceLimit = 1))

        repeat(12) { case.replace(LpScopeObservation.Shape(Any(), emptyList<Any>(), it, it)) }

        val shape = case.report()["appendShape"] as Map<*, *>
        assertEquals(12L, shape["replacementEntries"])
        assertEquals(12L, shape["replacementAccepted"])
        assertEquals(0L, (case.report()["methodUnmatchedEntries"] as Map<*, *>)["REPLACE"])
        assertEquals(23L, case.report()["traceDropped"])
        assertEquals("COMPLETE", case.report()["status"])
    }

    @ParameterizedTest
    @ValueSource(ints = [65536, 65537])
    fun `wide snapshots should respect the inclusive sampling width limit`(width: Int) {
        val case = ObservationCase()

        case.replace(LpScopeObservation.Shape(Any(), List(width) { emptyList<Any>() }, 0, width))

        val shape = case.report()["appendShape"] as Map<*, *>
        assertEquals(if (width == 65536) 65536L else 0L, shape["columnVisits"])
        assertEquals(if (width == 65536) 0L else 1L, (shape["sampleRefusals"] as Map<*, *>)["snapshot-width"])
        assertEquals(1L, shape["replacementAccepted"])
        assertEquals("COMPLETE", case.report()["status"])
    }

    @Test
    fun `exhausted column sampling should preserve complete scalar replacement counts`() {
        val case = ObservationCase()
        val matrix = List(65536) { emptyList<Any>() }

        repeat(5) { case.replace(LpScopeObservation.Shape(Any(), matrix, it, 65536 + it)) }

        val shape = case.report()["appendShape"] as Map<*, *>
        assertEquals(262144L, shape["columnVisits"])
        assertEquals(1L, (shape["sampleRefusals"] as Map<*, *>)["column-budget"])
        assertEquals(5L, shape["replacementAccepted"])
        assertEquals("COMPLETE", case.report()["status"])
    }

    @Test
    fun `snapshot count exhaustion should preserve complete scalar replacement counts`() {
        val case = ObservationCase()

        repeat(129) {
            val rows = it % 2
            case.replace(LpScopeObservation.Shape(Any(), emptyList<Any>(), rows, rows))
        }

        val shape = case.report()["appendShape"] as Map<*, *>
        assertEquals(128, (shape["storedNonemptyColumnSamples"] as List<*>).size)
        assertEquals(1L, (shape["sampleRefusals"] as Map<*, *>)["snapshot-count"])
        assertEquals(129L, shape["replacementAccepted"])
        assertEquals("COMPLETE", case.report()["status"])
    }

    @Test
    fun `an interrupted replacement should remain an unmatched candidate`() {
        val case = ObservationCase()

        case.enter(LpScopeObservation.Method.REPLACE, LpScopeObservation.Shape(Any(), emptyList<Any>(), 1, 1))

        val shape = case.report()["appendShape"] as Map<*, *>
        assertEquals(1L, shape["replacementEntries"])
        assertEquals(0L, shape["replacementAccepted"])
        assertEquals(1L, (case.report()["methodUnmatchedEntries"] as Map<*, *>)["REPLACE"])
        assertEquals("INCONCLUSIVE", case.report()["status"])
    }

    @Test
    fun `an accepted return without candidate publication should be inconclusive`() {
        val case = ObservationCase()
        case.enter(LpScopeObservation.Method.REPLACE, LpScopeObservation.Shape(Any(), emptyList<Any>(), 1, 1))

        case.returned(LpScopeObservation.Method.REPLACE, push = true)

        assertEquals("INCONCLUSIVE", case.report()["status"])
        assertEquals(listOf("replacement-publication-mismatch"), case.report()["failures"])
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `certificate cohorts and recovery ledgers should cover every return after trace saturation`(limit: Int) {
        val case = ObservationCase(LpScopeObservation(traceLimit = limit))
        val recovery = mapOf(
            "reconstruction" to LpScopeObservation.Recovery("CANDIDATE", "POINT", mapOf("pointChecks" to 1L)),
            "exactPoint" to LpScopeObservation.Recovery(null, null, mapOf("checks" to 1L, "witness" to 0L)),
            "refinement" to LpScopeObservation.Recovery("WORK", null, mapOf("work" to 7L)),
            "basisVerification" to null,
        )
        for (optimal in listOf(null, false, true)) {
            case.enter(LpScopeObservation.Method.SOLVE)
            case.returned(LpScopeObservation.Method.SOLVE, "BOUND", metadata = LpScopeObservation.Certificate(
                "CERTIFIED_BOUND", optimal, false, true, recovery,
            ))
        }

        val report = case.report()
        val metadata = report["returnedCertificateMetadata"] as Map<*, *>
        val cohorts = metadata["cohorts"] as List<*>

        assertEquals("COMPLETE", report["status"])
        assertEquals(3L, metadata["normalSolveReturns"])
        assertEquals(false, metadata["completeCertifierItinerary"])
        assertEquals(3, cohorts.size)
        for (entry in cohorts) {
            val cohort = entry as Map<*, *>
            val details = cohort["recovery"] as Map<*, *>
            val refinement = details["refinement"] as Map<*, *>
            val point = details["exactPoint"] as Map<*, *>
            assertEquals(1L, cohort["normalReturns"])
            assertEquals(true, cohort["sourceBound"])
            assertEquals(true, cohort["production"])
            assertEquals(mapOf("WORK" to 1L), refinement["declines"])
            assertEquals(mapOf("work" to 7L), refinement["counterSums"])
            assertEquals(mapOf("NO_DECLINE" to 1L), point["declines"])
            assertEquals(1L, (details["basisVerification"] as Map<*, *>)["absent"])
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["binding", "policy"])
    fun `source binding and production policy should separate returned cohorts`(change: String) {
        val case = ObservationCase()
        case.positive()
        if (change == "binding") case.observer.bind(case.owner, null, null) else case.production = false

        case.positive()

        val metadata = case.report()["returnedCertificateMetadata"] as Map<*, *>
        val cohorts = metadata["cohorts"] as List<*>
        assertEquals("COMPLETE", case.report()["status"])
        assertEquals(2L, metadata["normalSolveReturns"])
        assertEquals(2, cohorts.size)
        val field = if (change == "binding") "sourceBound" else "production"
        assertEquals(setOf(false, true), cohorts.map { (it as Map<*, *>)[field] }.toSet())
    }

    @Test
    fun `a normal solve without a metadata observation should be inconclusive`() {
        val case = ObservationCase()
        case.enter(LpScopeObservation.Method.SOLVE)

        case.returned(LpScopeObservation.Method.SOLVE, "NULL", metadata = null)

        assertEquals("INCONCLUSIVE", case.report()["status"])
        assertEquals(listOf("missing-certificate-metadata"), case.report()["failures"])
    }

    @ParameterizedTest
    @CsvSource("CERTIFIED_BOUND,NONE,false,false", "FEASIBLE,BOUND,true,false", "UNKNOWN,NONE,false,false")
    fun `inconsistent returned evidence should refuse its cohort`(
        verdict: String, kind: String, witness: Boolean, bound: Boolean,
    ) {
        val case = ObservationCase()
        case.enter(LpScopeObservation.Method.SOLVE)
        val metadata = LpScopeObservation.Certificate(verdict, true, witness, bound,
            listOf("reconstruction", "exactPoint", "refinement", "basisVerification").associateWith { null })

        case.returned(LpScopeObservation.Method.SOLVE, kind, metadata = metadata)

        assertEquals("INCONCLUSIVE", case.report()["status"])
        assertEquals(listOf("certificate-cohort-mismatch"), case.report()["failures"])
    }

    @Test
    fun `a missing recovery metadata slot should refuse a complete ledger`() {
        val case = ObservationCase()
        case.enter(LpScopeObservation.Method.SOLVE)

        case.returned(LpScopeObservation.Method.SOLVE, "BOUND", metadata = LpScopeObservation.Certificate(
            "CERTIFIED_BOUND", true, false, true, emptyMap(),
        ))

        assertEquals("INCONCLUSIVE", case.report()["status"])
        assertEquals(listOf("certificate-metadata-shape"), case.report()["failures"])
    }

    @Test
    fun `negative retained counters should make the metadata inconclusive`() {
        val case = ObservationCase()
        val recovery = listOf("reconstruction", "exactPoint", "refinement", "basisVerification")
            .associateWith { if (it == "refinement") LpScopeObservation.Recovery("WORK", null,
                mapOf("work" to -1L)) else null }
        case.enter(LpScopeObservation.Method.SOLVE)

        case.returned(LpScopeObservation.Method.SOLVE, "BOUND", metadata = LpScopeObservation.Certificate(
            "CERTIFIED_BOUND", true, false, true, recovery,
        ))

        assertEquals("INCONCLUSIVE", case.report()["status"])
        assertEquals(listOf("certificate-invalid-scalar"), case.report()["failures"])
    }

    @Test
    fun `overflowing retained sums should make the metadata inconclusive without wrapping`() {
        val case = ObservationCase()
        val recovery = listOf("reconstruction", "exactPoint", "refinement", "basisVerification")
            .associateWith { if (it == "refinement") LpScopeObservation.Recovery("WORK", null,
                mapOf("work" to Long.MAX_VALUE)) else null }
        repeat(2) {
            case.enter(LpScopeObservation.Method.SOLVE)
            case.returned(LpScopeObservation.Method.SOLVE, "BOUND", metadata = LpScopeObservation.Certificate(
                "CERTIFIED_BOUND", true, false, true, recovery,
            ))
        }

        val report = case.report()

        assertEquals("INCONCLUSIVE", report["status"])
        assertEquals(listOf("certificate-counter-overflow"), report["failures"])
        val cohorts = (report["returnedCertificateMetadata"] as Map<*, *>)["cohorts"] as List<*>
        val details = (cohorts.single() as Map<*, *>)["recovery"] as Map<*, *>
        assertEquals(mapOf("work" to Long.MAX_VALUE), (details["refinement"] as Map<*, *>)["counterSums"])
    }

    private class ObservationCase(val observer: LpScopeObservation = LpScopeObservation()) {
        val owner = Any()
        val root = Any()
        val session = Any()
        var context = Any()
        var policy = Any()
        var production = true
        var factory = Any()
        var model = Any()
        var numerical: Any? = Any()
        var matrix: List<*> = emptyList<Any>()
        var rows = 0
        var controls = LpScopeObservation.Controls(listOf(1L))
        val revisions = MutableList(5) { 0L }
        var generation = emptyList<Long>()
        var edits = 0L
        private var successes = 0L
        private var depth = 0
        private var identity = Any()
        private var clock = 0L

        init {
            observer.bind(owner, root, session)
        }

        private fun state() = LpScopeObservation.State(
            identity, model, context, policy, factory,
            revisions.toList(), depth, editAttempts = edits, editSuccesses = successes,
            generation = generation, numericalOwner = numerical, production = production,
        )

        fun enter(
            method: LpScopeObservation.Method,
            candidate: LpScopeObservation.Shape? = null,
            append: Boolean = true
        ) {
            observer.ledger(method, true)
            observer.enter(
                method, owner, state(), controls, clock++, 1L,
                LpScopeObservation.Shapes(
                    LpScopeObservation.Shape(model, matrix, rows, matrix.size + rows), candidate, append
                ),
            )
        }

        fun returned(
            method: LpScopeObservation.Method,
            kind: String = "OTHER",
            push: Boolean = false,
            metadata: LpScopeObservation.Certificate? = LpScopeObservation.Certificate(
                when (kind) {
                    "BOTH" -> "ATTAINED_OPTIMUM"
                    "WITNESS" -> "FEASIBLE"
                    "BOUND" -> "CERTIFIED_BOUND"
                    "NONE" -> "INDETERMINATE"
                    "NULL" -> null
                    else -> "INFEASIBLE"
                },
                null, kind in listOf("BOTH", "WITNESS"), kind in listOf("BOTH", "BOUND"),
                listOf("reconstruction", "exactPoint", "refinement", "basisVerification")
                    .associateWith { null },
            ),
        ) {
            observer.ledger(method, false)
            observer.returned(
                method, owner, state(), kind, push, clock++,
                LpScopeObservation.Shape(model, matrix, rows, matrix.size + rows), metadata,
            )
        }

        fun replace(candidate: LpScopeObservation.Shape, accepted: Boolean = true, append: Boolean = true) {
            enter(LpScopeObservation.Method.REPLACE, candidate, append)
            if (accepted) {
                model = candidate.identity
                matrix = candidate.matrix
                rows = candidate.rows
            }
            returned(LpScopeObservation.Method.REPLACE, push = accepted)
        }

        fun positive() {
            enter(LpScopeObservation.Method.SOLVE)
            returned(LpScopeObservation.Method.SOLVE, "BOTH")
        }

        fun push() {
            enter(LpScopeObservation.Method.PUSH)
            enter(LpScopeObservation.Method.PUBLISH)
            identity = Any()
            depth++
            edits++
            successes++
            returned(LpScopeObservation.Method.PUBLISH)
            returned(LpScopeObservation.Method.PUSH, push = true)
        }

        fun publishOnly() {
            enter(LpScopeObservation.Method.PUBLISH)
            identity = Any()
            returned(LpScopeObservation.Method.PUBLISH)
        }

        fun report(): Map<String, Any?> = observer.snapshot()
    }
}
