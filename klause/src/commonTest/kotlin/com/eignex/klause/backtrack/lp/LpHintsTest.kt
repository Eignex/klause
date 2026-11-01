package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpDirectionalCost
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.VarRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpHintsTest {

    private fun recordedHints(lpValue: Double): LpHints {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 10)),
            factors = arrayOf<Factor>(Linear(longArrayOf(1), intArrayOf(0), LinearOp.LE, 10L)),
        )
        val rel = CpToLpRelaxation(p, LinearObjective(intCoefficients = longArrayOf(1))).build(PropagationSession(p))
        val hints = LpHints(numIntVars = 1, numBoolVars = 0)
        val primal = DoubleArray(rel.colVarId.size)
        for (col in rel.colVarId.indices) {
            if (rel.colVarId[col] == 0 && !rel.colIsBool[col]) primal[col] = lpValue
        }
        hints.record(rel, primal, DoubleArray(rel.model.m))
        return hints
    }

    @Test
    fun `directional point refresh preserves fallback reduced cost priority`() {
        val builder = LpBuilder()
        val x = builder.addVar(0, 10, cost = 0)
        builder.addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.LE, 3.0)
        val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(x), IntArray(0))
        val hints = LpHints(1, 0, directionalGains = true)
        hints.record(relaxation, doubleArrayOf(3.25), doubleArrayOf(-4.0))
        val priority = hints.branchScore(VarRef.IntVar(0))

        hints.recordPoint(relaxation, doubleArrayOf(3.5), doubleArrayOf(-20.0))

        assertEquals(priority, hints.branchScore(VarRef.IntVar(0)))
    }

    @Test
    fun `directional point refresh preserves fallback value order`() {
        val builder = LpBuilder()
        builder.addVar(0, 10)
        val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
        val hints = LpHints(1, 0, directionalGains = true)
        hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))

        hints.recordPoint(relaxation, doubleArrayOf(3.5), DoubleArray(0))

        assertEquals(2L, hints.order(VarRef.IntVar(0), (0L..10L).asSequence()).first())
    }

    @Test
    fun `basis comparison owns the refreshed point with a matching split`() {
        for (change in listOf("same-split", "moved-split", "next-node", "clear")) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 2) }
            val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val hints = LpHints(2, 0, directionalGains = true)
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
            val point = doubleArrayOf(if (change == "moved-split") 1.1 else 0.1, 0.5)
            hints.recordPoint(relaxation, point, DoubleArray(0))
            point.fill(0.0)
            if (change == "next-node") hints.beginNode(1, 100.0)
            if (change == "clear") hints.clear()

            val selected = hints.basisPick(VarRef.IntVar(0), VarRef.IntVar(1),
                IntDomain(0, 2), IntDomain(0, 2)) { _, _ ->
                listOf(LpDirectionalCost(4.0, 4.0), LpDirectionalCost(4.0, 4.0))
            }

            assertEquals(if (change == "same-split") VarRef.IntVar(1) else null, selected?.first, change)
        }
    }

    @Test
    fun `directional gains use refreshed parent coordinates`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val hints = LpHints(1, 0, directionalGains = true)
        val variable = VarRef.IntVar(0)
        fun relaxation() = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
        repeat(2) {
            hints.beginNode(0, 100.0)
            val parent = relaxation()
            hints.record(parent, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordPoint(parent, doubleArrayOf(2.5), DoubleArray(0))
            hints.recordBound(parent, 10.0)
            hints.branch(variable)
            for (up in listOf(false, true)) {
                hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                hints.beginNode(1, 100.0)
                val child = relaxation()
                hints.record(child, doubleArrayOf(if (up) 3.0 else 2.0), DoubleArray(0))
                hints.recordBound(child, if (up) 13.0 else 11.0)
            }
        }
        hints.beginNode(0, 100.0)
        val current = relaxation()
        hints.record(current, doubleArrayOf(2.25), DoubleArray(0))
        hints.recordBound(current, 10.0)

        val score = hints.directionalScore(variable)

        assertEquals(1.25, score, 1e-9)
    }

    @Test
    fun `basis comparison requires a strictly larger balanced directional gain`() {
        for ((alternateCost, expected) in listOf(
            LpDirectionalCost(4.0, 4.0) to VarRef.IntVar(1),
            LpDirectionalCost(2.0, 6.0) to null,
            LpDirectionalCost(1.0, 12.0) to null,
            LpDirectionalCost(-1.0, 6.0) to null,
            LpDirectionalCost(Double.NaN, 6.0) to null,
            null to null,
        )) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 2) }
            val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val hints = LpHints(2, 0, directionalGains = true)
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))

            val selected = hints.basisPick(VarRef.IntVar(0), VarRef.IntVar(1), IntDomain(0, 2),
                IntDomain(0, 2)) { model, columns ->
                assertTrue(model === relaxation.model)
                assertEquals(listOf(0, 1), columns.toList())
                listOf(LpDirectionalCost(2.0, 6.0), alternateCost)
            }

            assertEquals(expected, selected?.first)
        }
    }

    @Test
    fun `basis comparison ignores missing points and excluded split neighbours`() {
        for (decline in listOf("disabled", "next-node", "domain", "integral", "clear")) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 2) }
            val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val hints = LpHints(2, 0, directionalGains = decline != "disabled")
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(if (decline == "integral") 0.0 else 0.5, 0.5),
                DoubleArray(0))
            if (decline == "next-node") hints.beginNode(1, 100.0)
            if (decline == "clear") hints.clear()
            var estimates = 0

            val selected = hints.basisPick(VarRef.IntVar(0), VarRef.IntVar(1),
                if (decline == "domain") IntDomain(1, 2) else IntDomain(0, 2), IntDomain(0, 2)) { _, _ ->
                estimates++
                listOf(LpDirectionalCost(1.0, 1.0), LpDirectionalCost(4.0, 4.0))
            }

            assertNull(selected, decline)
            assertEquals(0, estimates, decline)
        }
    }

    @Test
    fun `a fractional lp value should put the floor split first`() {
        val ordered = recordedHints(3.7).order(VarRef.IntVar(0), (0L..10L).asSequence())
        assertEquals(3L, ordered.first())
    }

    @Test
    fun `an integral lp value should put the nearest value first`() {
        val ordered = recordedHints(4.0).order(VarRef.IntVar(0), (0L..10L).asSequence())
        assertEquals(4L, ordered.first())
    }

    @Test
    fun `values should stay unordered without a recorded solve`() {
        val ordered = LpHints(numIntVars = 1, numBoolVars = 0).order(VarRef.IntVar(0), (5L..9L).asSequence())
        assertEquals(5L, ordered.first())
    }

    @Test
    fun `clearing hints should restore the configured value order`() {
        val hints = recordedHints(3.7)

        hints.clear()

        assertEquals(listOf(5L, 6L, 7L), hints.order(VarRef.IntVar(0), sequenceOf(5L, 6L, 7L)).toList())
    }

    @Test
    fun `the branch score should weigh a real row's share of the reduced cost`() {
        // x in [0,10] sits only in the real row 0.5x <= 3; a dual of -4 on it gives x a reduced cost of 2.
        val b = LpBuilder()
        val x = b.addVar(0, 10, cost = 0)
        b.addRealRow(intArrayOf(x), doubleArrayOf(0.5), Relation.LE, 3.0)
        val relaxation = LpRelaxation(
            model = b.build(Sense.MINIMIZE),
            colVarId = intArrayOf(0),
            colIsBool = booleanArrayOf(false),
            objectiveConstant = 0L,
            intColOf = intArrayOf(x),
            boolColOf = IntArray(0),
        )
        val hints = LpHints(numIntVars = 1, numBoolVars = 0)

        hints.record(relaxation, doubleArrayOf(3.5), doubleArrayOf(-4.0))

        assertEquals(1.0, hints.branchScore(VarRef.IntVar(0)), 1e-6)
    }

    @Test
    fun `cutoff row and objective changes reject pending directional gains`() {
        for (change in listOf("cutoff", "cut", "scale", "objective", "constant", "mapping", "kind")) {
            val b = LpBuilder()
            val x = b.addVar(0, 10, cost = 1)
            b.addRow(intArrayOf(x), longArrayOf(1), Relation.LE, 10L)
            val base = b.build(Sense.MINIMIZE)
            val trail = LpBoundTrail(assertNotNull(base.authoritativeModel()))
            val observations = ArrayList<String>()
            val hints = LpHints(1, 1, directionalGains = true, observe = observations::add)
            fun relaxation() = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(change == "mapping" && trail.state.depth > 0),
                if (change == "constant" && trail.state.depth > 0) 1L else 0L,
                intArrayOf(0), IntArray(0))
            hints.beginNode(0, 100.0)
            val parent = relaxation()
            hints.record(parent, doubleArrayOf(2.25), DoubleArray(parent.model.m))
            hints.recordBound(parent, 10.0)
            hints.branch(VarRef.IntVar(0))
            hints.commit(SearchDecision.IntAtMost(0, 2), 1)
            assertTrue(trail.push())
            when (change) {
                "cut" -> assertTrue(trail.append(
                    LpScopedRow(1L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(2L),
                        ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))),
                    scoped = true,
                ))
                "scale", "objective" -> {
                    val old = trail.state.model.objective
                    assertTrue(trail.replaceObjective(ExactLpObjective(
                        List(old.size) { old.cost(it) },
                        scale = ExactLpNumber.of(if (change == "scale") 2L else 1L),
                        externalConstant = ExactLpNumber.of(if (change == "objective") 1L else 0L),
                    )))
                }
            }
            hints.beginNode(1, if (change == "cutoff") 99.0 else 100.0)
            val child = relaxation()
            hints.record(child, doubleArrayOf(2.0), DoubleArray(child.model.m))

            if (change == "kind") hints.recordObjective(child, 11.0) else hints.recordBound(child, 11.0)

            assertEquals(0, observations.count { it == "DOWN" }, change)
        }
    }

    @Test
    fun `cutoff context changes reject gains when floating cutoffs are equal`() {
        for (change in listOf("incumbent", "external", "unchanged")) {
            val first = BigFraction.ofLong((1L shl 60) + 32L)
            val second = BigFraction.ofLong((1L shl 60) + 33L)
            assertEquals(first.toDouble(), second.toDouble())
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), 1L shl 60, intArrayOf(0), IntArray(0))
            val observations = ArrayList<String>()
            val hints = LpHints(1, 0, directionalGains = true, observe = observations::add)
            hints.beginNode(0, first.toDouble(), 1L, first)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordBound(relaxation, 1.0)
            hints.branch(VarRef.IntVar(0))
            hints.commit(SearchDecision.IntAtMost(0, 2), 1)

            hints.beginNode(1, second.toDouble(), if (change == "incumbent") 2L else 1L,
                if (change == "external") second else first)
            hints.record(relaxation, doubleArrayOf(2.0), DoubleArray(0))
            hints.recordBound(relaxation, 1.5)

            assertEquals(if (change == "unchanged") 1 else 0, observations.count { it == "DOWN" }, change)
        }
    }

    @Test
    fun `compatible bound edits and sibling retraction retain the immediate parent gain`() {
        val b = LpBuilder()
        b.addVar(0, 10, cost = 1)
        val trail = LpBoundTrail(assertNotNull(b.build(Sense.MINIMIZE).authoritativeModel()))
        val observations = ArrayList<String>()
        val hints = LpHints(1, 0, directionalGains = true, observe = observations::add)
        fun relaxation() = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
        hints.beginNode(0, 100.0)
        hints.record(relaxation(), doubleArrayOf(2.25), DoubleArray(0))
        hints.recordBound(relaxation(), 10.0)
        hints.branch(VarRef.IntVar(0))
        for (up in listOf(false, true)) {
            assertTrue(trail.push())
            assertTrue(trail.assertBound(0, !up, ExactLpSide(ExactLpNumber.of(if (up) 3L else 2L)), 1L))
            hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
            hints.beginNode(1, 100.0)
            hints.record(relaxation(), doubleArrayOf(if (up) 3.0 else 2.0), DoubleArray(0))
            hints.recordBound(relaxation(), if (up) 13.0 else 11.0)
            assertTrue(trail.pop(0))
        }

        assertEquals(1, observations.count { it == "DOWN" })
        assertEquals(1, observations.count { it == "UP" })
    }

    @Test
    fun `missing or mismatched current bounds keep the existing score`() {
        for (change in listOf("missing", "cut", "point", "domain", "hole")) {
            val b = LpBuilder()
            b.addVar(0, 10, cost = 1)
            val trail = LpBoundTrail(assertNotNull(b.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
            val hints = LpHints(1, 0, directionalGains = true)
            repeat(2) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordBound(relaxation, 10.0)
                hints.branch(VarRef.IntVar(0))
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                    hints.recordBound(relaxation, if (up) 13.0 else 11.0)
                }
            }
            assertTrue(hints.directionalScore(VarRef.IntVar(0)).isFinite())

            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))

            if (change == "domain" || change == "hole") {
                hints.recordBound(relaxation, 10.0)
                hints.finishNode()
            } else if (change == "point") {
                val other = LpRelaxation(b.build(Sense.MINIMIZE), intArrayOf(0),
                    booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0))
                hints.recordBound(other, 10.0)
                hints.finishNode()
            } else if (change == "cut") {
                hints.recordBound(relaxation, 10.0)
                assertTrue(trail.append(
                    LpScopedRow(0L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(8L),
                        ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))),
                    scoped = false,
                ))
                hints.finishNode(trail.state)
            } else hints.finishNode()

            val domain = when (change) {
                "domain" -> IntDomain(0, 1)
                "hole" -> IntDomain(0, 10).excludeValue(2)
                else -> null
            }
            assertTrue(hints.directionalScore(VarRef.IntVar(0), domain).isNaN())
            assertTrue(hints.branchScore(VarRef.IntVar(0)).isFinite())
        }
    }

    @Test
    fun `only reliable variable history can override the baseline`() {
        for ((samples, gain, accepted) in listOf(
            Triple(1, 1.0, false), Triple(1, 8.0, false), Triple(1, 16.0, false), Triple(2, 1.0, true),
        )) {
            val builder = LpBuilder()
            repeat(3) { builder.addVar(0, 10, cost = if (it == 2) 100 else 1) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1, 2),
                BooleanArray(3), 0L, intArrayOf(0, 1, 2), IntArray(0))
            val observations = ArrayList<String>()
            val hints = LpHints(3, 0, directionalGains = true, observe = observations::add)
            for (variable in 0..1) {
                repeat(if (variable == 0) 2 else samples) {
                    hints.beginNode(0, 100.0)
                    hints.record(relaxation, DoubleArray(3) { 2.25 }, DoubleArray(0))
                    hints.recordBound(relaxation, 10.0)
                    hints.branch(VarRef.IntVar(variable))
                    for (up in if (variable == 0 || samples == 2) listOf(false, true) else listOf(false)) {
                        hints.commit(if (up) SearchDecision.IntAtLeast(variable, 3)
                            else SearchDecision.IntAtMost(variable, 2), 1)
                        hints.beginNode(1, 100.0)
                        hints.record(relaxation, DoubleArray(3) { 2.25 }, DoubleArray(0))
                        val delta = if (variable == 0) {
                            if (up) 24.0 else 8.0
                        } else if (up) 3.0 else gain
                        hints.recordBound(relaxation, 10.0 + delta)
                    }
                }
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(0.0, 2.25, 2.25), DoubleArray(0))
            hints.recordBound(relaxation, 10.0)
            observations.clear()
            val variable = VarRef.IntVar(1)
            val baseline = VarRef.IntVar(2)

            val selected = hints.selected(variable, baseline, hints.directionalScore(variable))
            hints.branch(selected ?: baseline)

            assertEquals(if (accepted) variable else null, selected, "samples=$samples gain=$gain")
            assertEquals(if (accepted) 1 else 0, observations.count { it == "CONSUMED" })
        }
    }

    @Test
    fun `stable objective constants preserve small directional gains`() {
        for (constant in listOf(0L, 1L shl 60, -(1L shl 60))) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), constant, intArrayOf(0), IntArray(0))
            val hints = LpHints(1, 0, directionalGains = true)
            val variable = VarRef.IntVar(0)
            repeat(2) {
                hints.beginNode(0, Double.POSITIVE_INFINITY)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordBound(relaxation, 10.0)
                hints.branch(variable)
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, Double.POSITIVE_INFINITY)
                    hints.record(relaxation, doubleArrayOf(if (up) 3.0 else 2.0), DoubleArray(0))
                    hints.recordBound(relaxation, if (up) 13.0 else 11.0)
                }
            }
            hints.beginNode(0, Double.POSITIVE_INFINITY)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordBound(relaxation, 10.0)

            assertEquals(1.5, hints.directionalScore(variable), 1e-9, "constant=$constant")
        }
    }
}
