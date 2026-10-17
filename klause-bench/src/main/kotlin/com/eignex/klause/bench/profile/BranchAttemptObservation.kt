package com.eignex.klause.bench.profile

import java.lang.ref.WeakReference

internal class BranchAttemptObservation(
    private val ownerCap: Int = 16,
    private val traceCap: Int = 64,
    private val eventCap: Long = 1_000_000L,
) {
    enum class Role { ARITHMETIC_DEPENDENCY, NON_ARITHMETIC, NAMED_THEORY_BOOLEAN, OTHER_DECISION }

    private class Owner(run: Any, session: Any, component: Any, val id: Int, val width: Int) {
        val run = WeakReference(run)
        val session = WeakReference(session)
        val component = WeakReference(component)
        var attempts = 0L
    }

    private val owners = ArrayList<Owner>()
    private val trace = ArrayList<Map<String, Any?>>()
    private val failures = LinkedHashSet<String>()
    private val counts = LongArray(Role.entries.size)
    private var events = 0L

    init {
        require(ownerCap in 1..16 && traceCap in 0..64 && eventCap in 1L..1_000_000L)
    }

    val disabled: Boolean get() = failures.isNotEmpty()

    fun fail(reason: String) {
        if (failures.size < 32) failures += reason.take(64)
    }

    fun attempted(
        run: Any,
        session: Any,
        component: Any,
        width: Int,
        variable: Int?,
        positive: Boolean?,
        selectedSlot: Int?,
        arithmetic: Boolean?,
    ) {
        if (disabled) return
        if (events >= eventCap) {
            fail("event-cap")
            return
        }
        events++
        if (width < 0) {
            fail("source-width")
            return
        }
        val existing = owners.singleOrNull { it.run.get() === run }
        val owner = if (existing != null) {
            if (existing.session.get() !== session || existing.component.get() !== component ||
                existing.width != width
            ) {
                fail("owner-context")
                return
            }
            existing
        } else {
            if (owners.size >= ownerCap) {
                fail("owner-cap")
                return
            }
            Owner(run, session, component, owners.size + 1, width).also(owners::add)
        }
        val role = if (variable == null) {
            if (positive != null || selectedSlot != null || arithmetic != null) {
                fail("other-decision-schema")
                return
            }
            Role.OTHER_DECISION
        } else {
            if (variable < 0 || positive == null) {
                fail("literal-schema")
                return
            }
            if (variable >= width) {
                if (selectedSlot != null || arithmetic != null) {
                    fail("named-decision-schema")
                    return
                }
                Role.NAMED_THEORY_BOOLEAN
            } else {
                if (selectedSlot != -1 || arithmetic == null) {
                    fail("source-slot")
                    return
                }
                if (arithmetic) Role.ARITHMETIC_DEPENDENCY else Role.NON_ARITHMETIC
            }
        }
        owner.attempts++
        counts[role.ordinal]++
        if (trace.size < traceCap) {
            trace += mapOf(
                "sequence" to events,
                "owner" to owner.id,
                "sourceBooleanCount" to width,
                "variable" to variable,
                "positive" to positive,
                "role" to role.name,
            )
        }
    }

    fun snapshot(): Map<String, Any?> = mapOf(
        "status" to if (!disabled && events > 0L) "COMPLETE" else "INCONCLUSIVE",
        "failures" to failures.toList(),
        "scope" to "attempts-before-SearchSession.push; not successful commits",
        "events" to events,
        "counts" to Role.entries.associate { it.name to counts[it.ordinal] },
        "trace" to trace.toList(),
        "traceDropped" to (counts.sum() - trace.size),
        "owners" to owners.map {
            mapOf(
                "id" to it.id,
                "sourceBooleanCount" to it.width,
                "attempts" to it.attempts,
                "runAlive" to (it.run.get() != null),
                "sessionAlive" to (it.session.get() != null),
                "componentAlive" to (it.component.get() != null),
            )
        },
        "limits" to mapOf(
            "owners" to ownerCap,
            "componentsPerCallback" to 8,
            "trace" to traceCap,
            "events" to eventCap,
            "failures" to 32,
            "failureName" to 64,
        ),
        "limitations" to listOf(
            "Membership is not original-input or derived-gate ownership.",
            "No other Boolean slots were scanned; unresolved arithmetic alternatives are not established.",
            "Instrumented attempts are not ordinary timing, successful commits or a recovery.",
        ),
    )
}
