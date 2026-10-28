package com.eignex.klause.bench.profile

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ConditionalComponentTraceTest {
    private val separator = 10

    private fun twoCycles(separatorPosition: Int): Problem {
        val factors = ArrayList<Factor>()
        for (offset in intArrayOf(0, 5)) {
            for (i in 0 until 5) {
                factors += Clause(intArrayOf(Lit.make(offset + i, true), Lit.make(offset + (i + 1) % 5, true)))
            }
        }
        val bridge = mutableListOf(Lit.make(0, true), Lit.make(5, true))
        bridge.add(separatorPosition, Lit.make(separator, true))
        factors += Clause(bridge.toIntArray())
        return Problem(11, 0, emptyArray(), factors.toTypedArray())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun `a discharged bridge leaves both cycles separate wherever its true literal sits`(separatorPosition: Int) {
        val problem = twoCycles(separatorPosition)
        val session = PropagationSession(problem)
        val recorder = ConditionalComponentTrace.Recorder(problem, Long.MAX_VALUE, 1)
        session.pinBool(separator, true)

        recorder.observe(session)

        val members = recorder.probes.single().clauses.map { component ->
            component.flatMap { clause -> clause.map(Lit::variable) }.toSet()
        }.toSet()
        assertEquals(setOf((0 until 5).toSet(), (5 until 10).toSet()), members)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2])
    fun `a live bridge joins both cycles into one component`(separatorPosition: Int) {
        val problem = twoCycles(separatorPosition)
        val session = PropagationSession(problem)
        val recorder = ConditionalComponentTrace.Recorder(problem, Long.MAX_VALUE, 1)
        session.pinBool(separator, false)

        recorder.observe(session)

        val root = recorder.summary()["root"] as JsonObject
        assertEquals(1, root.getValue("components").jsonPrimitive.int)
    }
}
