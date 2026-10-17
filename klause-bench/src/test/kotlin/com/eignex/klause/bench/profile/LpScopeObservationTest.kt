package com.eignex.klause.bench.profile

import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import kotlin.test.assertEquals

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
        for (change in revisionNames + listOf("model", "root", "session", "policy", "context", "factory", "controls",
            "failed-edit", "numerical-owner", "generation", "publication")) {
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
        val owners = observer.javaClass.getDeclaredField("owners").apply { isAccessible = true }.get(observer) as List<*>
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

    private class ObservationCase(val observer: LpScopeObservation = LpScopeObservation()) {
        val owner = Any()
        val root = Any()
        val session = Any()
        var context = Any()
        var policy = Any()
        var factory = Any()
        var model = Any()
        var numerical = Any()
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

        private fun state() = LpScopeObservation.State(identity, model, context, policy, factory,
            revisions.toList(), depth, editAttempts = edits, editSuccesses = successes,
            generation = generation, numericalOwner = numerical)

        fun enter(method: LpScopeObservation.Method) {
            observer.ledger(method, true)
            observer.enter(method, owner, state(), controls, clock++, 1L)
        }

        fun returned(method: LpScopeObservation.Method, kind: String = "OTHER", push: Boolean = false) {
            observer.ledger(method, false)
            observer.returned(method, owner, state(), kind, push, clock++)
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
