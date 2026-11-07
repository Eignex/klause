package com.eignex.klause.backtrack.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpHintUnavailable
import com.eignex.klause.lp.engine.Basis
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpDirectionalCost
import com.eignex.klause.lp.engine.LpDirectionalValues
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpProbeSource
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.LpStructuralColumn
import com.eignex.klause.lp.engine.Relation
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.VarStatus
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `disabled guidance spends no optional observation work`() {
        val builder = LpBuilder()
        builder.addVar(0, 2, cost = 1)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            BooleanArray(1), 0L, intArrayOf(0), IntArray(0))
        var work = 0L
        var queries = 0
        val hints = LpHints(1, 0, directionalGains = false, rootBasis = true, charge = { work += it })
        val variable = VarRef.IntVar(0)
        val domain = IntDomain(0, 2)
        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(0.75), DoubleArray(0))
        hints.recordBound(relaxation, 0.0)
        hints.branch(variable, domain)
        hints.commit(SearchDecision.IntAtMost(0, 0), 1)
        hints.beginNode(1, 100.0)

        hints.recordPoint(relaxation, doubleArrayOf(0.25), DoubleArray(0))
        hints.recordFiniteBound(trail.state, 1L)
        hints.finishNode(trail.state)
        hints.prepareSelectedSide(variable, domain) { _, _ ->
            queries++
            listOf(LpDirectionalCost(1.0, 4.0))
        }

        val existing = listOf(SearchDecision.IntAtMost(0, 0L), SearchDecision.IntAtLeast(0, 1L))
        assertEquals(existing, hints.selectRootAlternatives(variable, domain, existing) { _, _ ->
            queries++
            listOf(LpDirectionalCost(1.0, 4.0))
        })

        assertEquals(0L, work)
        assertEquals(0, queries)
    }

    @Test
    fun `finite child bounds train history without refreshing the LP point`() {
        for (constant in listOf(0L, 1L shl 60, -(1L shl 60))) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                BooleanArray(1), constant, intArrayOf(0), IntArray(0))
            val observations = ArrayList<String>()
            val hints = LpHints(1, 0, directionalGains = true, observe = observations::add)
            val variable = VarRef.IntVar(0)
            val domain = IntDomain(0, 10)
            repeat(2) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordBound(relaxation, 10.0)
                hints.branch(variable, domain)
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, 100.0)
                    val bound = constant + if (up) 13L else 11L

                    hints.recordFiniteBound(trail.state, bound)
                    hints.recordFiniteBound(trail.state, bound)
                    hints.finishNode(trail.state)

                    assertFalse(hints.prepareSide(variable, domain, allowBasis = false) { _, _ -> null })
                }
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordBound(relaxation, 10.0)
            hints.prepareSide(variable, domain, allowBasis = false) { _, _ -> null }
            hints.branch(variable, domain)
            val existing = listOf(SearchDecision.IntAtMost(0, 2), SearchDecision.IntAtLeast(0, 3))

            assertEquals(existing.reversed(), hints.orderAlternatives(variable, existing, allowBasis = false))
            assertEquals(4, observations.count { it == "FINITE_BOUND_RECORDED" })
        }
    }

    @Test
    fun `finite child observations require the matching checked parent context`() {
        for (change in listOf("same", "equal", "weaker", "cutoff", "incumbent", "external", "rows", "floating",
            "LP bound", "disabled", "overflow", "unavailable", "infeasible", "absent")) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val constant = if (change == "overflow") -1L else 0L
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                BooleanArray(1), constant, intArrayOf(0), IntArray(0))
            val observations = ArrayList<String>()
            val hints = LpHints(1, 0, directionalGains = change != "disabled", observe = observations::add)
            val first = BigFraction.ofLong((1L shl 60) + 32L)
            val second = BigFraction.ofLong((1L shl 60) + 33L)
            hints.beginNode(0, 100.0, 1L, first)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            if (change == "floating") hints.recordObjective(relaxation, 10.0) else hints.recordBound(relaxation, 10.0)
            if (change != "absent") {
                hints.branch(VarRef.IntVar(0))
                hints.commit(SearchDecision.IntAtMost(0, 2), 1)
            }
            hints.beginNode(1, if (change == "cutoff") 99.0 else 100.0,
                if (change == "incumbent") 2L else 1L, if (change == "external") second else first)
            if (change == "rows") assertTrue(trail.append(
                LpScopedRow(0L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(8L),
                    ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))), scoped = false,
            ))
            if (change == "LP bound") {
                hints.record(relaxation, doubleArrayOf(2.0), DoubleArray(0))
                hints.recordBound(relaxation, 11.0)
            }
            if (change == "infeasible") hints.recordInfeasible()
            val bound = when (change) {
                "equal" -> 10L
                "weaker" -> 9L
                "overflow" -> Long.MAX_VALUE
                "unavailable" -> Long.MIN_VALUE
                else -> 11L
            }

            hints.recordFiniteBound(trail.state, bound)
            hints.finishNode(trail.state)

            assertEquals(if (change == "same" || change == "equal") 1 else 0,
                observations.count { it == "FINITE_BOUND_RECORDED" }, change)
            if (change == "LP bound") assertEquals(1, observations.count { it == "DOWN" })
        }
    }

    @Test
    fun `finite bound reservation failure preserves the pending child`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            BooleanArray(1), 0L, intArrayOf(0), IntArray(0))
        var remaining = Long.MAX_VALUE
        val observations = ArrayList<String>()
        val hints = LpHints(1, 0, directionalGains = true, observe = observations::add, charge = { work ->
            require(work <= remaining)
            remaining -= work
        })
        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
        hints.recordBound(relaxation, 10.0)
        hints.branch(VarRef.IntVar(0))
        hints.commit(SearchDecision.IntAtMost(0, 2), 1)
        hints.beginNode(1, 100.0)
        remaining = 7L

        assertFailsWith<IllegalArgumentException> { hints.recordFiniteBound(trail.state, 11L) }
        remaining = Long.MAX_VALUE
        hints.recordFiniteBound(trail.state, 11L)

        assertEquals(1, observations.count { it == "DOWN" })
    }

    @Test
    fun `reused shallow history fits its observation allowance`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            BooleanArray(1), 0L, intArrayOf(0), IntArray(0))
        val observations = ArrayList<String>()
        var remaining = Long.MAX_VALUE
        val hints = LpHints(1, 0, directionalGains = true, observe = observations::add, charge = { work ->
            assertTrue(work <= remaining, "unfunded work=$work remaining=$remaining")
            remaining -= work
        })
        val variable = VarRef.IntVar(0)
        val domain = IntDomain(0, 10)
        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
        hints.recordBound(relaxation, 10.0)
        hints.branch(variable, domain)
        hints.resetHistory()
        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
        hints.recordBound(relaxation, 10.0)
        remaining = 20L

        hints.branch(variable, domain)
        hints.commit(SearchDecision.IntAtMost(0, 2), 1)
        hints.beginNode(1, 100.0)
        hints.record(relaxation, doubleArrayOf(2.0), DoubleArray(0))
        hints.recordBound(relaxation, 11.0)

        assertEquals(1, observations.count { it == "DOWN" })
    }

    @Test
    fun `basis advice compares fractional gains below the root without child solves`() {
        for (bools in 0..2) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, if (it < bools) 1 else 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()),
                IntArray(2) { if (it < bools) it else it - bools }, BooleanArray(2) { it < bools },
                0L, IntArray(2 - bools) { it + bools }, IntArray(bools) { it })
            val observations = ArrayList<String>()
            val hints = LpHints(2 - bools, bools, directionalGains = true,
                basisVariableComparisons = true, observe = observations::add)
            val first = if (bools > 0) VarRef.Bool(0) else VarRef.IntVar(0)
            val second = if (bools > 1) VarRef.Bool(1) else VarRef.IntVar(1 - bools)
            val domain = IntDomain(0, 3)
            var queries = 0
            val estimate: (LpModel, IntArray) -> List<LpDirectionalCost?> = { model, columns ->
                assertTrue(model === relaxation.model)
                assertEquals(listOf(0, 1), columns.toList())
                queries++
                listOf(LpDirectionalCost(4.0, 4.0), LpDirectionalCost(8.0, 8.0))
            }
            repeat(2) { depth ->
                hints.beginNode(depth, 100.0)
                hints.record(relaxation, doubleArrayOf(0.25, 0.5), DoubleArray(0))
                hints.recordBound(relaxation, 10.0)
                hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))

                val proposal = hints.compareBasisVariables(first, domain, second, domain, {
                    assertEquals(20L, it)
                    true
                }, estimate)

                assertEquals(if (depth == 0) null else second, proposal)
                assertEquals(if (depth == 0) first else second, hints.applyVariableChoice(proposal, first))
            }
            assertEquals(null, hints.compareBasisVariables(first, domain, second, domain, { true }, estimate))
            assertEquals(1, queries)
            assertEquals(1, observations.count { it == "VARIABLE_BASIS_CONSUMED" })
            assertEquals(0, observations.count { it == "VARIABLE_PROBE_ATTEMPT" })
            assertEquals(0, observations.count { it == "BASIS_ATTEMPT" })
        }
    }

    @Test
    fun `basis advice rejects a normalized directional tradeoff`() {
        val cases = listOf(
            doubleArrayOf(0.5, 0.5) to LpDirectionalCost(1.0, 30.0),
            doubleArrayOf(0.5, 0.5) to LpDirectionalCost(30.0, 1.0),
            doubleArrayOf(0.25, 0.75) to LpDirectionalCost(8.0, 8.0),
        )
        for ((point, cost) in cases) for (bools in 0..2) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, if (it < bools) 1 else 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()),
                IntArray(2) { if (it < bools) it else it - bools }, BooleanArray(2) { it < bools },
                0L, IntArray(2 - bools) { it + bools }, IntArray(bools) { it })
            val observations = ArrayList<String>()
            val hints = LpHints(2 - bools, bools, directionalGains = true,
                basisVariableComparisons = true, observe = observations::add)
            val first = if (bools > 0) VarRef.Bool(0) else VarRef.IntVar(0)
            val second = if (bools > 1) VarRef.Bool(1) else VarRef.IntVar(1 - bools)
            hints.beginNode(1, 100.0)
            hints.record(relaxation, point, DoubleArray(0))
            hints.recordBound(relaxation, 10.0)
            hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))

            val proposal = hints.compareBasisVariables(first, IntDomain(0, 3), second, IntDomain(0, 3),
                { true }) { _, _ -> listOf(LpDirectionalCost(4.0, 4.0), cost) }

            assertEquals(null, proposal)
            assertEquals(first, hints.applyVariableChoice(proposal, first))
            assertEquals(1, observations.count { it == "VARIABLE_BASIS_DIRECTION_TRADEOFF" })
            assertEquals(0, observations.count { it == "VARIABLE_BASIS_CONSUMED" })
        }
    }

    @Test
    fun `basis advice accepts nondegrading normalized gains with one equal direction`() {
        for (cost in listOf(LpDirectionalCost(2.0, 8.0), LpDirectionalCost(4.0, 6.0))) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val hints = LpHints(2, 0, directionalGains = true, basisVariableComparisons = true)
            val first = VarRef.IntVar(0)
            val second = VarRef.IntVar(1)
            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(0.25, 0.5), DoubleArray(0))
            hints.recordBound(relaxation, 10.0)
            hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))

            val proposal = hints.compareBasisVariables(first, IntDomain(0, 3), second, IntDomain(0, 3),
                { true }) { _, _ -> listOf(LpDirectionalCost(4.0, 4.0), cost) }

            assertEquals(second, proposal)
            assertEquals(second, hints.applyVariableChoice(proposal, first))
        }
    }

    @Test
    fun `basis comparison stays within its funded followup allowance`() {
        for (tradeoff in listOf(false, true)) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            var remaining = Long.MAX_VALUE
            val hints = LpHints(2, 0, directionalGains = true, basisVariableComparisons = true,
                charge = { work ->
                    assertTrue(work <= remaining, "unfunded work=$work remaining=$remaining")
                    remaining -= work
                })
            val first = VarRef.IntVar(0)
            val second = VarRef.IntVar(1)
            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
            hints.recordBound(relaxation, 10.0)
            hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))

            val proposal = hints.compareBasisVariables(first, IntDomain(0, 3), second, IntDomain(0, 3), {
                assertEquals(20L, it)
                true
            }) { _, _ ->
                remaining = 48L
                listOf(LpDirectionalCost(4.0, 4.0),
                    if (tradeoff) LpDirectionalCost(1.0, 30.0) else LpDirectionalCost(8.0, 8.0))
            }

            assertEquals(if (tradeoff) first else second, hints.applyVariableChoice(proposal, first))
        }
    }

    @Test
    fun `unconfirmed basis advice retains current scoring`() {
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0, 3) }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
            BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
        val observations = ArrayList<String>()
        val hints = LpHints(2, 0, directionalGains = true, basisVariableComparisons = true,
            verifyBasisVariables = true, observe = observations::add)
        val first = VarRef.IntVar(0)
        val second = VarRef.IntVar(1)
        hints.beginNode(1, 100.0)
        hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
        hints.recordBound(relaxation, 10.0)
        hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))

        val proposal = hints.compareBasisVariables(first, IntDomain(0, 3), second, IntDomain(0, 3),
            { true }) { _, _ -> listOf(LpDirectionalCost(1.0, 4.0), LpDirectionalCost(4.0, 9.0)) }

        assertEquals(second, proposal)
        assertEquals(first, hints.applyVariableChoice(proposal, first))
        assertEquals(0, observations.count { it == "VARIABLE_CONSUMED" })
    }

    @Test
    fun `basis confirmation requires complete improving child values`() {
        val baseline = LpDirectionalValues(11.0, 14.0)
        val cases: List<Pair<String, List<LpDirectionalValues>?>> = listOf(
            "improving" to listOf(baseline, LpDirectionalValues(12.0, 16.0)),
            "tied" to listOf(baseline, baseline),
            "weaker" to listOf(baseline, LpDirectionalValues(10.0, 16.0)),
            "tradeoff" to listOf(baseline, LpDirectionalValues(12.0, 13.0)),
            "missing" to listOf(baseline, LpDirectionalValues(Double.NaN, 16.0)),
            "incomplete" to listOf(baseline),
            "declined" to null,
        )
        for ((name, values) in cases) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val observations = ArrayList<String>()
            val hints = LpHints(2, 0, directionalGains = true, basisVariableComparisons = true,
                verifyBasisVariables = true, observe = observations::add)
            val first = VarRef.IntVar(0)
            val second = VarRef.IntVar(1)
            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
            hints.recordBound(relaxation, 10.0)
            hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))
            val nomination = hints.compareBasisVariables(first, IntDomain(0, 3), second, IntDomain(0, 3),
                { true }) { _, _ -> listOf(LpDirectionalCost(1.0, 4.0), LpDirectionalCost(4.0, 9.0)) }
            assertEquals(second, nomination, name)

            val proposal = hints.compareProbeVariables(first, IntDomain(0, 3), nomination, IntDomain(0, 3),
                { true }) { source, columns, splits ->
                assertTrue(source.model === relaxation.model)
                assertEquals(listOf(0, 1), columns.toList())
                assertEquals(listOf(0L, 0L), splits.toList())
                values
            }

            assertEquals(if (name == "improving") second else first,
                hints.applyVariableChoice(proposal, first), name)
            assertEquals(1, observations.count { it == "VARIABLE_BASIS_VERIFICATION_ATTEMPT" }, name)
            assertEquals(0, observations.count { it == "VARIABLE_BASIS_CONSUMED" }, name)
            assertEquals(if (name == "improving") 1 else 0,
                observations.count { it == "VARIABLE_PROBE_CONSUMED" }, name)
        }
    }

    @Test
    fun `basis confirmation spends its lifetime attempt across history resets`() {
        for (outcome in listOf("unfunded", "declined", "accepted")) {
            val observations = ArrayList<String>()
            val hints = LpHints(2, 0, directionalGains = true, basisVariableComparisons = true,
                verifyBasisVariables = true, observe = observations::add)
            var basisQueries = 0
            var reservations = 0
            var childQueries = 0
            repeat(3) { iteration ->
                val builder = LpBuilder()
                repeat(2) { builder.addVar(0, 3) }
                val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                    BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
                val first = VarRef.IntVar(0)
                val second = VarRef.IntVar(1)
                hints.resetHistory()
                hints.beginNode(1, 100.0)
                hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
                hints.recordBound(relaxation, 10.0)
                hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))
                val nomination = hints.compareBasisVariables(first, IntDomain(0, 3), second, IntDomain(0, 3),
                    { true }) { _, _ ->
                    basisQueries++
                    listOf(LpDirectionalCost(1.0, 4.0), LpDirectionalCost(4.0, 9.0))
                }

                val proposal = hints.compareProbeVariables(first, IntDomain(0, 3), nomination, IntDomain(0, 3), {
                    reservations++
                    outcome != "unfunded"
                }) { _, _, _ ->
                    childQueries++
                    if (outcome == "declined") null
                    else listOf(LpDirectionalValues(11.0, 14.0), LpDirectionalValues(12.0, 16.0))
                }

                assertEquals(if (iteration == 0 && outcome == "accepted") second else first,
                    hints.applyVariableChoice(proposal, first), outcome)
            }
            assertEquals(1, basisQueries, outcome)
            assertEquals(1, reservations, outcome)
            assertEquals(if (outcome == "unfunded") 0 else 1, childQueries, outcome)
            assertEquals(1, observations.count { it == "VARIABLE_BASIS_VERIFICATION_ATTEMPT" }, outcome)
        }
    }

    @Test
    fun `unfunded basis confirmation stays within its advertised followup work`() {
        val builder = LpBuilder()
        repeat(2) { builder.addVar(0, 3) }
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
            BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
        var remaining = Long.MAX_VALUE
        val hints = LpHints(2, 0, directionalGains = true, basisVariableComparisons = true,
            verifyBasisVariables = true, charge = { work ->
                assertTrue(work <= remaining, "unfunded work=$work remaining=$remaining")
                remaining -= work
            })
        val first = VarRef.IntVar(0)
        val second = VarRef.IntVar(1)
        hints.beginNode(1, 100.0)
        hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
        hints.recordPoint(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
        hints.recordBound(relaxation, 10.0)
        hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))
        val nomination = hints.compareBasisVariables(first, IntDomain(0, 3), second, IntDomain(0, 3),
            { true }) { _, _ ->
            remaining = hints.basisComparisonFollowupWork
            listOf(LpDirectionalCost(1.0, 4.0), LpDirectionalCost(4.0, 9.0))
        }
        var queries = 0

        val proposal = hints.compareProbeVariables(first, IntDomain(0, 3), nomination, IntDomain(0, 3),
            { work -> work <= remaining }) { _, _, _ -> queries++; null }

        assertEquals(first, hints.applyVariableChoice(proposal, first))
        assertEquals(0, queries)
    }

    @Test
    fun `unavailable or nonimproving basis advice retains current scoring`() {
        val valid = LpDirectionalCost(1.0, 4.0)
        val unavailable: List<List<LpDirectionalCost?>?> = listOf(null, emptyList(), listOf(valid),
            listOf(null, valid), listOf(valid, valid), listOf(valid, LpDirectionalCost(-1.0, 9.0)),
            listOf(valid, LpDirectionalCost(Double.NaN, 4.0)),
            listOf(valid, LpDirectionalCost(1.0, Double.POSITIVE_INFINITY)),
            listOf(valid, LpDirectionalCost(0.0, 0.0)))
        for (costs in unavailable) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val hints = LpHints(2, 0, directionalGains = true, basisVariableComparisons = true)
            val first = VarRef.IntVar(0)
            val domain = IntDomain(0, 3)
            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
            hints.recordBound(relaxation, 10.0)
            hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))

            val proposal = hints.compareBasisVariables(first, domain, VarRef.IntVar(1), domain,
                { true }) { _, _ -> costs }

            assertEquals(null, proposal)
            assertEquals(first, hints.applyVariableChoice(proposal, first))
        }
    }

    @Test
    fun `unproductive basis attempts stop across history resets`() {
        val valid = LpDirectionalCost(1.0, 4.0)
        for (failure in listOf("unfunded", "declined", "missing", "nonimproving", "tradeoff")) {
            val observations = ArrayList<String>()
            val hints = LpHints(2, 0, directionalGains = true, basisVariableComparisons = true,
                observe = observations::add)
            var reservations = 0
            var queries = 0
            repeat(10) {
                val builder = LpBuilder()
                repeat(2) { builder.addVar(0, 3) }
                val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                    BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
                hints.resetHistory()
                hints.beginNode(1, 100.0)
                hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
                hints.recordBound(relaxation, 10.0)
                hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))

                val proposal = hints.compareBasisVariables(VarRef.IntVar(0), IntDomain(0, 3),
                    VarRef.IntVar(1), IntDomain(0, 3), { reservations++; failure != "unfunded" }) { _, _ ->
                    queries++
                    when (failure) {
                        "declined" -> null
                        "missing" -> listOf(null, valid)
                        "tradeoff" -> listOf(valid, LpDirectionalCost(0.0, 30.0))
                        else -> listOf(valid, valid)
                    }
                }

                assertEquals(null, proposal, failure)
            }
            assertEquals(2, reservations, failure)
            assertEquals(if (failure == "unfunded") 0 else 2, queries, failure)
            assertEquals(1, observations.count { it == "VARIABLE_BASIS_STOPPED" }, failure)
        }
    }

    @Test
    fun `basis admission reopens only on compatible LP child progress`() {
        val same = LpDirectionalCost(1.0, 4.0)
        for (progress in listOf("positive", "floating", "equal", "weaker", "kind", "cutoff")) {
            val observations = ArrayList<String>()
            val hints = LpHints(3, 0, directionalGains = true, basisVariableComparisons = true,
                observe = observations::add)
            var queries = 0
            repeat(2) {
                val builder = LpBuilder()
                repeat(3) { builder.addVar(0, 3) }
                val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1, 2),
                    BooleanArray(3), 0L, intArrayOf(0, 1, 2), IntArray(0))
                hints.resetHistory()
                hints.beginNode(1, 100.0)
                hints.record(relaxation, doubleArrayOf(0.5, 0.5, 0.5), DoubleArray(0))
                hints.recordBound(relaxation, 10.0)
                hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(3) { VarStatus.AT_LOWER }))
                hints.compareBasisVariables(VarRef.IntVar(1), IntDomain(0, 3),
                    VarRef.IntVar(2), IntDomain(0, 3), { true }) { _, _ ->
                    queries++
                    listOf(same, same)
                }
            }
            val builder = LpBuilder()
            repeat(3) { builder.addVar(0, 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1, 2),
                BooleanArray(3), 0L, intArrayOf(0, 1, 2), IntArray(0))
            hints.resetHistory()
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.25, 0.5), DoubleArray(0))
            if (progress == "floating") hints.recordObjective(relaxation, 10.0)
            else hints.recordBound(relaxation, 10.0)
            hints.branch(VarRef.IntVar(0), IntDomain(0, 3))
            hints.commit(SearchDecision.IntAtMost(0, 0), 1)

            hints.beginNode(1, if (progress == "cutoff") 99.0 else 100.0)
            hints.record(relaxation, doubleArrayOf(0.0, 0.25, 0.5), DoubleArray(0))
            val bound = when (progress) {
                "equal" -> 10.0
                "weaker" -> 9.0
                else -> 11.0
            }
            if (progress == "floating" || progress == "kind") hints.recordObjective(relaxation, bound)
            else hints.recordBound(relaxation, bound)
            hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(3) { VarStatus.AT_LOWER }))
            val proposal = hints.compareBasisVariables(VarRef.IntVar(1), IntDomain(0, 3),
                VarRef.IntVar(2), IntDomain(0, 3), { true }) { _, _ ->
                queries++
                listOf(same, LpDirectionalCost(4.0, 9.0))
            }

            val reopened = progress == "positive" || progress == "floating"
            assertEquals(if (reopened) VarRef.IntVar(2) else null, proposal, progress)
            assertEquals(if (reopened) 3 else 2, queries, progress)
            assertEquals(if (reopened) 1 else 0,
                observations.count { it == "VARIABLE_BASIS_REOPENED" }, progress)
        }
    }

    @Test
    fun `finite child progress respects its reservation and owner attempt cap`() {
        val same = LpDirectionalCost(1.0, 4.0)
        for (gain in listOf(-1L, 0L, 1L)) {
            val observations = ArrayList<String>()
            var remaining = Long.MAX_VALUE
            val hints = LpHints(3, 0, directionalGains = true, basisVariableComparisons = true,
                observe = observations::add, charge = { work ->
                    assertTrue(work <= remaining, "unfunded work=$work remaining=$remaining")
                    remaining -= work
                })
            var queries = 0
            repeat(if (gain > 0L) 5 else 1) {
                repeat(2) {
                    val builder = LpBuilder()
                    repeat(3) { builder.addVar(0, 3) }
                    val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                    val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1, 2),
                        BooleanArray(3), 0L, intArrayOf(0, 1, 2), IntArray(0))
                    hints.resetHistory()
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(0.5, 0.5, 0.5), DoubleArray(0))
                    hints.recordBound(relaxation, 10.0)
                    hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(3) { VarStatus.AT_LOWER }))
                    hints.compareBasisVariables(VarRef.IntVar(1), IntDomain(0, 3),
                        VarRef.IntVar(2), IntDomain(0, 3), { true }) { _, _ ->
                        queries++
                        listOf(same, same)
                    }
                }
                val builder = LpBuilder()
                repeat(3) { builder.addVar(0, 3) }
                val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
                val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1, 2),
                    BooleanArray(3), 0L, intArrayOf(0, 1, 2), IntArray(0))
                hints.resetHistory()
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(0.5, 0.25, 0.5), DoubleArray(0))
                hints.recordBound(relaxation, 10.0)
                hints.branch(VarRef.IntVar(0), IntDomain(0, 3))
                hints.commit(SearchDecision.IntAtMost(0, 0), 1)
                hints.beginNode(1, 100.0)
                remaining = hints.finiteBoundWork

                hints.recordFiniteBound(trail.state, 10L + gain)

                remaining = Long.MAX_VALUE
                hints.record(relaxation, doubleArrayOf(0.0, 0.25, 0.5), DoubleArray(0))
                hints.recordBound(relaxation, 10.0 + gain)
                hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(3) { VarStatus.AT_LOWER }))
                hints.compareBasisVariables(VarRef.IntVar(1), IntDomain(0, 3),
                    VarRef.IntVar(2), IntDomain(0, 3), { true }) { _, _ ->
                    queries++
                    listOf(same, LpDirectionalCost(4.0, 9.0))
                }
            }

            assertEquals(if (gain > 0L) 8 else 2, queries, "gain=$gain")
            assertEquals(if (gain > 0L) 2 else 0,
                observations.count { it == "VARIABLE_BASIS_REOPENED" }, "gain=$gain")
        }
    }

    @Test
    fun `computed basis proposals renew admission without consumption`() {
        val valid = listOf(LpDirectionalCost(1.0, 4.0), LpDirectionalCost(4.0, 9.0))
        val results = listOf(null, valid, null, null, valid)
        val hints = LpHints(2, 0, directionalGains = true, basisVariableComparisons = true)
        val proposals = ArrayList<VarRef?>()
        var queries = 0
        for (costs in results) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            hints.resetHistory()
            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
            hints.recordBound(relaxation, 10.0)
            hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))

            proposals += hints.compareBasisVariables(VarRef.IntVar(0), IntDomain(0, 3),
                VarRef.IntVar(1), IntDomain(0, 3), { true }) { _, _ ->
                queries++
                costs
            }
        }

        assertEquals(listOf<VarRef?>(null, VarRef.IntVar(1), null, null, null), proposals)
        assertEquals(4, queries)
    }

    @Test
    fun `productive basis requests retain the owner attempt cap`() {
        val hints = LpHints(2, 0, directionalGains = true, basisVariableComparisons = true)
        var queries = 0
        repeat(10) { attempt ->
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            hints.resetHistory()
            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
            hints.recordBound(relaxation, 10.0)
            hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))

            val proposal = hints.compareBasisVariables(VarRef.IntVar(0), IntDomain(0, 3),
                VarRef.IntVar(1), IntDomain(0, 3), { true }) { _, _ ->
                queries++
                listOf(LpDirectionalCost(1.0, 4.0), LpDirectionalCost(4.0, 9.0))
            }

            assertEquals(if (attempt < 8) VarRef.IntVar(1) else null, proposal)
        }
        assertEquals(8, queries)
    }

    @Test
    fun `withdrawn or disabled basis sources do not consult the owner`() {
        for (missing in listOf("disabled", "bound", "point", "withdrawn")) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val hints = LpHints(2, 0, directionalGains = missing != "disabled", basisVariableComparisons = true)
            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
            if (missing != "bound") hints.recordBound(relaxation, 10.0)
            hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))
            if (missing == "point") hints.beginNode(1, 100.0)
            if (missing == "withdrawn") hints.recordUnavailable(LpHintUnavailable.NO_BOUND)
            var queries = 0

            val proposal = hints.compareBasisVariables(VarRef.IntVar(0), IntDomain(0, 3),
                VarRef.IntVar(1), IntDomain(0, 3), { true }) { _, _ ->
                queries++
                listOf(LpDirectionalCost(1.0, 4.0), LpDirectionalCost(4.0, 9.0))
            }

            assertEquals(null, proposal, missing)
            assertEquals(0, queries, missing)
        }
    }

    @Test
    fun `fresh child values improve the weaker bound below the root`() {
        for (bools in 0..2) for (offset in listOf(-100.0, 0.0, 100.0)) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, if (it < bools) 1 else 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()),
                IntArray(2) { if (it < bools) it else it - bools }, BooleanArray(2) { it < bools },
                0L, IntArray(2 - bools) { it + bools }, IntArray(bools) { it })
            val hints = LpHints(2 - bools, bools, directionalGains = true, freshVariableProbes = true)
            val first = if (bools > 0) VarRef.Bool(0) else VarRef.IntVar(0)
            val second = if (bools > 1) VarRef.Bool(1) else VarRef.IntVar(1 - bools)
            val domain = IntDomain(0, 3)
            val basis = Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER })
            var queries = 0
            val estimate: (LpProbeSource, IntArray, LongArray) -> List<LpDirectionalValues>? = { source, cols, splits ->
                assertTrue(source.model === relaxation.model)
                assertTrue(source.basis === basis)
                assertEquals(listOf(0, 1), cols.toList())
                assertEquals(listOf(0L, 0L), splits.toList())
                queries++
                listOf(LpDirectionalValues(offset, offset + 5.0), LpDirectionalValues(offset + 1.0, offset + 6.0))
            }
            repeat(2) { depth ->
                hints.beginNode(depth, 100.0)
                hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
                hints.recordBound(relaxation, 10.0)
                hints.recordProbeBasis(relaxation, basis)

                val proposal = hints.compareProbeVariables(first, domain, second, domain, { true }, estimate)

                assertEquals(if (depth == 0) null else second, proposal)
                assertEquals(if (depth == 0) first else second, hints.applyVariableChoice(proposal, first))
            }
            assertEquals(1, queries)
            assertEquals(null, hints.compareVariables(first, domain, second, domain))
            assertEquals(null, hints.compareProbeVariables(first, domain, second, domain, { true }, estimate))
        }
    }

    @Test
    fun `fresh child advice preserves both corresponding child bounds`() {
        val cases = listOf(
            Triple(LpDirectionalValues(1.0, 4.0), LpDirectionalValues(1.0, 5.0), true),
            Triple(LpDirectionalValues(4.0, 1.0), LpDirectionalValues(5.0, 1.0), true),
            Triple(LpDirectionalValues(1.0, 4.0), LpDirectionalValues(5.0, 1.0), false),
            Triple(LpDirectionalValues(4.0, 1.0), LpDirectionalValues(1.0, 5.0), false),
            Triple(LpDirectionalValues(1.0, 4.0), LpDirectionalValues(2.0, 3.0), false),
            Triple(LpDirectionalValues(4.0, 1.0), LpDirectionalValues(3.0, 2.0), false),
            Triple(LpDirectionalValues(1.0, 4.0), LpDirectionalValues(1.0, 4.0), false),
            Triple(LpDirectionalValues(1.0, 4.0), LpDirectionalValues(1.0, 3.0), false),
            Triple(LpDirectionalValues(1.0, 4.0), LpDirectionalValues(1.0 - 1e-10, 99.0), false),
            Triple(LpDirectionalValues(1.0, 4.0), LpDirectionalValues(1.0 - 1e-6, 99.0), false),
            Triple(LpDirectionalValues(1.0, 4.0), LpDirectionalValues(1.0 + 1e-10, 5.0), true),
        )
        for ((baseline, candidate, stronger) in cases) for (offset in listOf(-100.0, 0.0, 100.0)) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val observations = ArrayList<String>()
            val hints = LpHints(2, 0, directionalGains = true, freshVariableProbes = true,
                observe = observations::add)
            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
            hints.recordBound(relaxation, 10.0)
            hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))

            val proposal = hints.compareProbeVariables(VarRef.IntVar(0), IntDomain(0, 3),
                VarRef.IntVar(1), IntDomain(0, 3), { true }) { _, _, _ ->
                listOf(LpDirectionalValues(offset + baseline.down, offset + baseline.up),
                    LpDirectionalValues(offset + candidate.down, offset + candidate.up))
            }

            assertEquals(if (stronger) VarRef.IntVar(1) else null, proposal)
            assertEquals(if (stronger) 1 else 0, observations.count { it == "VARIABLE_PROBE_STRONGER_TIE" })
            assertEquals(if (stronger) VarRef.IntVar(1) else VarRef.IntVar(0),
                hints.applyVariableChoice(proposal, VarRef.IntVar(0)))
        }
    }

    @Test
    fun `a retained source is queried at most once across cooperative entries`() {
        for (enabled in listOf(false, true)) for (funded in listOf(false, true)) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 2) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val hints = LpHints(2, 0, directionalGains = true, freshVariableProbes = enabled)
            var queries = 0
            repeat(2) { entry ->
                hints.beginNode(entry + 1, 100.0)
                hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
                hints.recordBound(relaxation, 10.0)
                hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))

                val proposal = hints.compareProbeVariables(VarRef.IntVar(0), IntDomain(0, 2),
                    VarRef.IntVar(1), IntDomain(0, 2), { funded }) { _, _, _ ->
                    queries++
                    listOf(LpDirectionalValues(1.0, 4.0), LpDirectionalValues(2.0, 5.0))
                }

                assertEquals(if (enabled && funded && entry == 0) VarRef.IntVar(1) else null, proposal)
            }
            assertEquals(if (enabled && funded) 1 else 0, queries)
        }
    }

    @Test
    fun `new points require a current source without reusing previous numerical advice`() {
        for (change in listOf("point", "reset", "cutoff", "incumbent", "scale")) {
            val builder = LpBuilder()
            repeat(3) { builder.addVar(0, 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val hints = LpHints(2, 0, directionalGains = true, freshVariableProbes = true)
            var queries = 0
            repeat(2) { node ->
                if (node > 0) {
                    assertTrue(trail.assertBound(2, true, ExactLpSide(ExactLpNumber.of(2L)), 1L))
                    if (change == "reset") hints.resetHistory()
                    if (change == "scale") {
                        val objective = trail.state.model.objective
                        assertTrue(trail.replaceObjective(ExactLpObjective(
                            List(objective.size) { objective.cost(it) }, scale = ExactLpNumber.of(2L))))
                    }
                }
                val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1, -1),
                    BooleanArray(3), 0L, intArrayOf(0, 1), IntArray(0))
                hints.beginNode(node + 1, if (node > 0 && change == "cutoff") 90.0 else 100.0,
                    incumbentVersion = if (node > 0 && change == "incumbent") 1L else 0L)
                hints.record(relaxation, doubleArrayOf(0.5, 0.5, 0.0), DoubleArray(0))
                hints.recordBound(relaxation, 10.0)
                assertFalse(hints.probeVariableChoiceReady)
                hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(3) { VarStatus.AT_LOWER }))

                val proposal = hints.compareProbeVariables(VarRef.IntVar(0), IntDomain(0, 3),
                    VarRef.IntVar(1), IntDomain(0, 3), { true }) { _, _, _ ->
                    queries++
                    if (node == 0) listOf(LpDirectionalValues(1.0, 4.0), LpDirectionalValues(2.0, 5.0))
                    else listOf(LpDirectionalValues(2.0, 5.0), LpDirectionalValues(1.0, 4.0))
                }

                assertEquals(if (node == 0) VarRef.IntVar(1) else null, proposal, change)
            }
            assertEquals(2, queries, change)
        }
    }

    @Test
    fun `incomplete nonfinite and tied child comparisons retain the baseline`() {
        val valid = LpDirectionalValues(1.0, 4.0)
        val unusable: List<List<LpDirectionalValues>?> = listOf(null, emptyList(), listOf(valid),
            listOf(valid, valid), listOf(valid, LpDirectionalValues(0.0, 99.0)),
            listOf(valid, LpDirectionalValues(Double.NaN, 4.0)),
            listOf(valid, LpDirectionalValues(1.0, Double.POSITIVE_INFINITY)),
            listOf(LpDirectionalValues(-Double.MAX_VALUE, 0.0),
                LpDirectionalValues(Double.MAX_VALUE, Double.MAX_VALUE)))
        for (bad in unusable) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val hints = LpHints(2, 0, directionalGains = true, freshVariableProbes = true)
            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
            hints.recordBound(relaxation, 10.0)
            hints.recordProbeBasis(relaxation, Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))

            val proposal = hints.compareProbeVariables(VarRef.IntVar(0), IntDomain(0, 3),
                VarRef.IntVar(1), IntDomain(0, 3), { true }) { _, _, _ -> bad }

            assertEquals(null, proposal)
            assertEquals(VarRef.IntVar(0), hints.applyVariableChoice(proposal, VarRef.IntVar(0)))
            assertEquals(null, hints.compareVariables(VarRef.IntVar(0), IntDomain(0, 3),
                VarRef.IntVar(1), IntDomain(0, 3)))
        }
    }

    @Test
    fun `missing mismatched and withdrawn probe sources cannot request a comparison`() {
        for (missing in listOf("basis", "bound", "model", "unavailable", "reset")) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 3) }
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val hints = LpHints(2, 0, directionalGains = true, freshVariableProbes = true)
            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
            if (missing != "bound") hints.recordBound(relaxation, 10.0)
            if (missing == "model") assertTrue(trail.assertBound(0, true, ExactLpSide(ExactLpNumber.of(2L)), 1L))
            if (missing != "basis") hints.recordProbeBasis(if (missing == "model")
                LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0, 1),
                    BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0)) else relaxation,
                Basis(IntArray(0), Array(2) { VarStatus.AT_LOWER }))
            if (missing == "unavailable") hints.recordUnavailable(LpHintUnavailable.MODEL)
            if (missing == "reset") hints.resetHistory()

            assertFalse(hints.probeVariableChoiceReady, missing)
            assertEquals(null, hints.compareProbeVariables(VarRef.IntVar(0), IntDomain(0, 3),
                VarRef.IntVar(1), IntDomain(0, 3), { error("missing source cannot reserve") }) { _, _, _ ->
                error("missing source cannot query")
            })
        }
    }

    @Test
    fun `selected side basis opportunity is confined to one root attempt across resets`() {
        for (enabled in listOf(false, true)) for (available in listOf(false, true)) {
            val builder = LpBuilder()
            builder.addVar(0, 2)
            val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0),
                BooleanArray(1), 0L, intArrayOf(0), IntArray(0))
            val hints = LpHints(1, 0, directionalGains = true, rootBasis = enabled)
            val variable = VarRef.IntVar(0)
            val domain = IntDomain(0, 2)
            var queries = 0
            val estimate: (LpModel, IntArray) -> List<LpDirectionalCost?>? = { _, columns ->
                assertEquals(listOf(0), columns.toList())
                queries++
                if (available) listOf(LpDirectionalCost(1.0, 4.0)) else null
            }
            hints.beginNode(1, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5), DoubleArray(0))
            assertFalse(hints.prepareSelectedSide(variable, domain, estimate))
            assertEquals(0, queries)
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5), DoubleArray(0))

            val prepared = hints.prepareSelectedSide(variable, domain, estimate)
            hints.branch(variable, domain)
            val existing = listOf(SearchDecision.IntAtMost(0, 0), SearchDecision.IntAtLeast(0, 1))
            val ordered = hints.orderAlternatives(variable, existing)

            assertEquals(enabled && available, prepared)
            assertEquals(if (enabled && available) existing.reversed() else existing, ordered)
            assertFalse(hints.prepareSelectedSide(variable, domain, estimate))
            hints.resetHistory()
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5), DoubleArray(0))
            assertFalse(hints.prepareSelectedSide(variable, domain, estimate))
            assertEquals(if (enabled) 1 else 0, queries)
        }
    }

    @Test
    fun `reliable history preserves the root basis opportunity`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            BooleanArray(1), 0L, intArrayOf(0), IntArray(0))
        val hints = LpHints(1, 0, directionalGains = true, rootBasis = true)
        val variable = VarRef.IntVar(0)
        val domain = IntDomain(0, 10)
        repeat(2) {
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordBound(relaxation, 10.0)
            hints.branch(variable, domain)
            for (up in listOf(false, true)) {
                hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                hints.beginNode(1, 100.0)
                hints.recordFiniteBound(trail.state, if (up) 13L else 11L)
                hints.finishNode(trail.state)
            }
        }
        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
        hints.recordBound(relaxation, 10.0)
        var queries = 0
        val estimate: (LpModel, IntArray) -> List<LpDirectionalCost?>? = { _, _ ->
            queries++
            listOf(LpDirectionalCost(1.0, 4.0))
        }

        assertTrue(hints.prepareSelectedSide(variable, domain, estimate))
        assertEquals(0, queries)
        hints.resetHistory()
        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
        hints.recordBound(relaxation, 10.0)
        assertTrue(hints.prepareSelectedSide(variable, domain, estimate))
        assertEquals(1, queries)
    }

    @Test
    fun `history only preparation never queries the basis without reliable gains`() {
        for (allowBasis in listOf(false, true)) {
            val builder = LpBuilder()
            builder.addVar(0, 2)
            val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0),
                BooleanArray(1), 0L, intArrayOf(0), IntArray(0))
            val hints = LpHints(1, 0, directionalGains = true)
            val variable = VarRef.IntVar(0)
            hints.record(relaxation, doubleArrayOf(0.5), DoubleArray(0))
            var queries = 0

            val prepared = hints.prepareSide(variable, IntDomain(0, 2), allowBasis) { _, _ ->
                queries++
                listOf(LpDirectionalCost(1.0, 4.0))
            }

            assertEquals(allowBasis, prepared)
            assertEquals(if (allowBasis) 1 else 0, queries)
        }
    }

    @Test
    fun `basis side advice puts the larger predicted child gain first when allowed`() {
        for (boolean in listOf(false, true)) for (allowBasis in listOf(false, true)) {
            for (id in 0..1) for (upCostly in listOf(false, true)) for (upFirst in listOf(false, true)) {
                val builder = LpBuilder()
                repeat(2) { builder.addVar(0, 2) }
                val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0, 1),
                    BooleanArray(2) { boolean }, 0L, if (boolean) IntArray(0) else intArrayOf(0, 1),
                    if (boolean) intArrayOf(0, 1) else IntArray(0))
                val hints = LpHints(if (boolean) 0 else 2, if (boolean) 2 else 0, directionalGains = true)
                val variable = if (boolean) VarRef.Bool(id) else VarRef.IntVar(id)
                val cost = if (upCostly) LpDirectionalCost(1.0, 4.0) else LpDirectionalCost(4.0, 1.0)
                hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
                hints.prepareSide(variable, IntDomain(0, 2)) { _, _ -> listOf(cost) }
                hints.branch(variable, if (boolean) null else IntDomain(0, 2))
                val lower = if (boolean) SearchDecision.Bool(2 * id + 1) else SearchDecision.IntAtMost(id, 0L)
                val upper = if (boolean) SearchDecision.Bool(2 * id) else SearchDecision.IntAtLeast(id, 1L)
                val existing = if (upFirst) listOf(upper, lower) else listOf(lower, upper)

                val ordered = hints.orderAlternatives(variable, existing, allowBasis)

                val expected = if (!allowBasis) existing else
                    if (upCostly) listOf(upper, lower) else listOf(lower, upper)
                assertEquals(expected, ordered)
            }
        }
    }

    @Test
    fun `basis side advice expires with its node and point`() {
        for (change in listOf("node", "reset", "clear", "record", "point", "selection")) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 2) }
            val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val hints = LpHints(2, 0, directionalGains = true)
            val variable = VarRef.IntVar(0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
            hints.prepareSide(variable, IntDomain(0, 2)) { _, _ -> listOf(LpDirectionalCost(1.0, 4.0)) }
            hints.branch(variable, IntDomain(0, 2))
            when (change) {
                "node" -> hints.beginNode(1, 100.0)
                "reset" -> hints.resetHistory()
                "clear" -> hints.clear()
                "record" -> hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
                "point" -> hints.recordPoint(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
                else -> hints.prepareSide(null, null) { _, _ -> null }
            }
            val existing = listOf(SearchDecision.IntAtMost(0, 0L), SearchDecision.IntAtLeast(0, 1L))

            val ordered = hints.orderAlternatives(variable, existing)

            assertEquals(existing, ordered, change)
        }
    }

    @Test
    fun `basis side advice preserves changed and pruned partitions`() {
        for (change in listOf("variable", "split", "single", "empty", "domain")) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 2) }
            val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val hints = LpHints(2, 0, directionalGains = true)
            val variable = VarRef.IntVar(if (change == "variable") 1 else 0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
            hints.prepareSide(VarRef.IntVar(0), IntDomain(0, 2)) { _, _ -> listOf(LpDirectionalCost(1.0, 4.0)) }
            hints.branch(variable, if (change == "domain") IntDomain(0, 2).excludeValue(1) else IntDomain(0, 2))
            val split = if (change == "split") 1L else 0L
            val lower = SearchDecision.IntAtMost(variable.varId, split)
            val upper = SearchDecision.IntAtLeast(variable.varId, split + 1L)
            val existing = when (change) {
                "empty" -> emptyList()
                "single" -> listOf(lower)
                else -> listOf(lower, upper)
            }

            val ordered = hints.orderAlternatives(variable, existing)

            assertEquals(existing, ordered, change)
        }
    }

    @Test
    fun `root basis gains choose complementary cuts around the current LP point`() {
        for (boolean in listOf(false, true)) for (split in if (boolean) listOf(0L) else listOf(-1L, 0L, 1L)) {
            for (upFirst in listOf(false, true)) for (allowBasis in listOf(false, true)) {
                val builder = LpBuilder()
                builder.addVar(if (boolean) 0L else -2L, if (boolean) 1L else 3L)
                val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0),
                    booleanArrayOf(boolean), 0L, if (boolean) IntArray(0) else intArrayOf(0),
                    if (boolean) intArrayOf(0) else IntArray(0))
                val events = mutableListOf<String>()
                val hints = LpHints(if (boolean) 0 else 1, if (boolean) 1 else 0,
                    directionalGains = true, rootBasis = true, observe = events::add)
                val variable = if (boolean) VarRef.Bool(0) else VarRef.IntVar(0)
                val domain = if (boolean) null else IntDomain(-2, 3)
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(0.5), DoubleArray(0))
                hints.branch(variable, domain)
                val lower = if (boolean) SearchDecision.Bool(1) else SearchDecision.IntAtMost(0, split)
                val upper = if (boolean) SearchDecision.Bool(0) else SearchDecision.IntAtLeast(0, split + 1L)
                val existing = if (upFirst) listOf(upper, lower) else listOf(lower, upper)
                var queries = 0

                val ordered = hints.selectRootAlternatives(variable, domain, existing, allowBasis) { _, _ ->
                    queries++
                    listOf(LpDirectionalCost(1.0, 4.0))
                }

                val floorLower = if (boolean) SearchDecision.Bool(1) else SearchDecision.IntAtMost(0, 0L)
                val floorUpper = if (boolean) SearchDecision.Bool(0) else SearchDecision.IntAtLeast(0, 1L)
                val expected = if (!allowBasis) existing else listOf(floorUpper, floorLower)
                assertEquals(expected, ordered)
                assertEquals(1, queries)
                assertEquals(1, events.count { it == "BASIS_PARTITION_PREPARED" })
                assertEquals(if (allowBasis && split != 0L) 1 else 0,
                    events.count { it == "BASIS_PARTITION_CHANGED" })
                assertEquals(if (allowBasis && (split != 0L || !upFirst)) 1 else 0,
                    events.count { it == "BASIS_ROOT_CHOICE_CHANGED" })
                assertEquals(if (allowBasis && split == 0L && upFirst) 1 else 0,
                    events.count { it == "BASIS_ROOT_CHOICE_AGREED" })
                assertEquals(if (allowBasis) 1 else 0, events.count { it == "BASIS_SIDE_CONSUMED" })
                assertEquals(if (allowBasis) 0 else 1, events.count { it == "BASIS_SIDE_WITHHELD" })
            }
        }
    }

    @Test
    fun `incomplete root partitions preserve the single funded basis opportunity`() {
        val variable = VarRef.IntVar(0)
        val domain = IntDomain(0, 2)
        val existing = listOf(SearchDecision.IntAtMost(0, 0L), SearchDecision.IntAtLeast(0, 1L))
        for (invalid in listOf(emptyList(), existing.take(1),
            listOf(SearchDecision.IntAtMost(0, 0L), SearchDecision.IntAtLeast(0, 2L)),
            listOf(SearchDecision.IntAtMost(1, 0L), SearchDecision.IntAtLeast(1, 1L)),
            listOf(SearchDecision.IntAtMost(0, -1L), SearchDecision.IntAtLeast(0, 0L)),
            listOf(SearchDecision.IntAtMost(0, Long.MAX_VALUE), SearchDecision.IntAtLeast(0, Long.MIN_VALUE)))) {
            val builder = LpBuilder()
            builder.addVar(0, 2)
            val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0),
                BooleanArray(1), 0L, intArrayOf(0), IntArray(0))
            var work = 0L
            var queries = 0
            val hints = LpHints(1, 0, directionalGains = true, rootBasis = true, charge = { work += it })
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5), DoubleArray(0))
            hints.branch(variable, domain)
            val before = work
            val estimate: (LpModel, IntArray) -> List<LpDirectionalCost?>? = { _, _ ->
                queries++
                listOf(LpDirectionalCost(1.0, 4.0))
            }

            assertEquals(invalid, hints.selectRootAlternatives(variable, domain, invalid, estimate = estimate))
            assertEquals(0, queries)
            assertTrue(work - before <= 32L)
            assertEquals(existing.reversed(), hints.selectRootAlternatives(variable, domain, existing,
                estimate = estimate))
            hints.resetHistory()
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5), DoubleArray(0))
            hints.branch(variable, domain)
            assertEquals(existing, hints.selectRootAlternatives(variable, domain, existing, estimate = estimate))
            assertEquals(1, queries)
        }
    }

    @Test
    fun `unfunded partition preparation preserves the root basis opportunity`() {
        val builder = LpBuilder()
        builder.addVar(0, 2)
        val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0),
            BooleanArray(1), 0L, intArrayOf(0), IntArray(0))
        val hints = LpHints(1, 0, directionalGains = true, rootBasis = true)
        val variable = VarRef.IntVar(0)
        val domain = IntDomain(0, 2)
        hints.beginNode(0, 100.0)
        hints.record(relaxation, doubleArrayOf(0.5), DoubleArray(0))
        hints.branch(variable, domain)
        val existing = listOf(SearchDecision.IntAtMost(0, 0L), SearchDecision.IntAtLeast(0, 1L))
        var requested = 0L
        var queries = 0
        val estimate: (LpModel, IntArray) -> List<LpDirectionalCost?>? = { _, _ ->
            queries++
            listOf(LpDirectionalCost(1.0, 4.0))
        }

        val unchanged = hints.selectRootAlternatives(variable, domain, existing,
            reserve = { requested = it; false }, estimate = estimate)

        assertEquals(existing, unchanged)
        assertEquals(32L, requested)
        assertEquals(0, queries)
        assertEquals(existing.reversed(), hints.selectRootAlternatives(variable, domain, existing,
            estimate = estimate))
        assertEquals(1, queries)
    }

    @Test
    fun `side fallback identifies the incompatible branch alternatives`() {
        val variable = VarRef.IntVar(0)
        val lower = SearchDecision.IntAtMost(0, 0L)
        val upper = SearchDecision.IntAtLeast(0, 1L)
        for ((target, alternatives, reason) in listOf(
            Triple(variable, emptyList(), "ARITY"),
            Triple(variable, listOf(lower), "ARITY"),
            Triple(variable, listOf(SearchDecision.IntAtMost(0, 1L), SearchDecision.IntAtLeast(0, 2L)), "PARTITION"),
            Triple(VarRef.IntVar(1), listOf(lower, upper), "VARIABLE"),
        )) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 2) }
            val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val events = mutableListOf<String>()
            var work = 0L
            val hints = LpHints(2, 0, directionalGains = true, observe = events::add, charge = { work += it })
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
            hints.prepareSide(variable, IntDomain(0, 2)) { _, _ -> listOf(LpDirectionalCost(1.0, 4.0)) }
            hints.branch(variable, IntDomain(0, 2))
            val preparedWork = work

            val ordered = hints.orderAlternatives(target, alternatives)

            assertEquals(alternatives, ordered, reason)
            assertEquals(12L, work - preparedWork, reason)
            assertEquals(listOf("SIDE_FALLBACK", "BASIS_SIDE_FALLBACK_" + reason),
                events.filter { it.contains("SIDE_FALLBACK") }, reason)
        }
    }

    @Test
    fun `tied basis child gains preserve the existing order`() {
        val builder = LpBuilder()
        builder.addVar(0, 2)
        val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0),
            BooleanArray(1), 0L, intArrayOf(0), IntArray(0))
        val hints = LpHints(1, 0, directionalGains = true)
        val variable = VarRef.IntVar(0)
        hints.record(relaxation, doubleArrayOf(0.5), DoubleArray(0))
        hints.prepareSide(variable, IntDomain(0, 2)) { _, _ -> listOf(LpDirectionalCost(2.0, 2.0)) }
        hints.branch(variable, IntDomain(0, 2))
        val existing = listOf(SearchDecision.IntAtLeast(0, 1L), SearchDecision.IntAtMost(0, 0L))

        val ordered = hints.orderAlternatives(variable, existing)

        assertEquals(existing, ordered)
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
    fun `basis advice owns the refreshed point with a matching split`() {
        for (change in listOf("same-split", "moved-split", "next-node", "clear")) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 2) }
            val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val hints = LpHints(2, 0, directionalGains = true)
            val variable = VarRef.IntVar(0)
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(0.5, 0.5), DoubleArray(0))
            val point = doubleArrayOf(if (change == "moved-split") 1.1 else 0.1, 0.5)
            hints.recordPoint(relaxation, point, DoubleArray(0))
            point.fill(0.0)
            if (change == "next-node") hints.beginNode(1, 100.0)
            if (change == "clear") hints.clear()
            hints.prepareSide(variable, IntDomain(0, 2)) { _, _ -> listOf(LpDirectionalCost(4.0, 4.0)) }
            hints.branch(variable, IntDomain(0, 2))
            val existing = listOf(SearchDecision.IntAtMost(0, 0L), SearchDecision.IntAtLeast(0, 1L))

            val ordered = hints.orderAlternatives(variable, existing)

            assertEquals(if (change == "same-split") existing.reversed() else existing, ordered, change)
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
    fun `basis advice reads only its selected column and rejects unusable costs`() {
        for ((cost, accepted) in listOf(
            LpDirectionalCost(1.0, 4.0) to true,
            LpDirectionalCost(3.0, 1.0) to false,
            LpDirectionalCost(0.0, 0.0) to false,
            LpDirectionalCost(-1.0, 6.0) to false,
            LpDirectionalCost(Double.NaN, 6.0) to false,
            null to false,
        )) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 2) }
            val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0, 1),
                BooleanArray(2), 0L, intArrayOf(0, 1), IntArray(0))
            val hints = LpHints(2, 0, directionalGains = true)
            val variable = VarRef.IntVar(1)
            hints.record(relaxation, doubleArrayOf(0.5, 0.25), DoubleArray(0))
            hints.prepareSide(variable, IntDomain(0, 2)) { model, columns ->
                assertTrue(model === relaxation.model)
                assertEquals(listOf(1), columns.toList())
                listOf(cost)
            }
            hints.branch(variable, IntDomain(0, 2))
            val existing = listOf(SearchDecision.IntAtMost(1, 0L), SearchDecision.IntAtLeast(1, 1L))

            val ordered = hints.orderAlternatives(variable, existing)

            assertEquals(if (accepted) existing.reversed() else existing, ordered)
        }
    }

    @Test
    fun `basis advice ignores missing points and excluded split neighbours`() {
        for (decline in listOf("disabled", "next-node", "domain", "integral", "clear")) {
            val builder = LpBuilder()
            builder.addVar(0, 2)
            val relaxation = LpRelaxation(builder.build(Sense.MINIMIZE), intArrayOf(0),
                BooleanArray(1), 0L, intArrayOf(0), IntArray(0))
            val hints = LpHints(1, 0, directionalGains = decline != "disabled")
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(if (decline == "integral") 0.0 else 0.5), DoubleArray(0))
            if (decline == "next-node") hints.beginNode(1, 100.0)
            if (decline == "clear") hints.clear()
            var estimates = 0

            val available = hints.prepareSide(VarRef.IntVar(0),
                if (decline == "domain") IntDomain(1, 2) else IntDomain(0, 2)) { _, _ ->
                estimates++
                listOf(LpDirectionalCost(1.0, 4.0))
            }

            assertTrue(!available, decline)
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
    fun `only reliable history supplies child priority`() {
        for ((samples, gain, accepted) in listOf(
            Triple(1, 1.0, false), Triple(1, 8.0, false), Triple(1, 16.0, false), Triple(2, 1.0, true),
        )) for (allowBasis in listOf(false, true)) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                BooleanArray(1), 0L, intArrayOf(0), IntArray(0))
            val hints = LpHints(1, 0, directionalGains = true)
            val variable = VarRef.IntVar(0)
            repeat(samples) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
                hints.recordBound(relaxation, 10.0)
                hints.branch(variable)
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(if (up) 3.0 else 2.0), DoubleArray(0))
                    hints.recordBound(relaxation, 10.0 + if (up) 3.0 else gain)
                }
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(0))
            hints.recordBound(relaxation, 10.0)
            hints.prepareSide(variable, IntDomain(0, 10), allowBasis) { _, _ -> null }
            hints.branch(variable, IntDomain(0, 10))
            val existing = listOf(SearchDecision.IntAtMost(0, 2L), SearchDecision.IntAtLeast(0, 3L))

            val ordered = hints.orderAlternatives(variable, existing, allowBasis)

            assertEquals(if (accepted) existing.reversed() else existing, ordered, "samples=$samples gain=$gain")
        }
    }

    @Test
    fun `only comparable completed gains survive context changes`() {
        val changes = listOf("cutoff" to true, "incumbent" to true, "external" to true,
            "rows" to true, "finished rows" to true, "append" to true, "finished append" to true,
            "costed append" to false, "column append" to false, "rewritten prefix" to false, "objective" to false,
            "constant" to false, "layout" to false, "kind" to false, "reset" to false, "clear" to false)
        for ((change, retained) in changes) {
            val builder = LpBuilder()
            repeat(2) { builder.addVar(0, 10, cost = 1) }
            var trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            assertTrue(trail.push())
            assertTrue(trail.append(
                LpScopedRow(1L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(8L),
                    ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))), scoped = true,
            ))
            var swapped = false
            var constant = 0L
            fun relaxation() = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()),
                if (swapped) intArrayOf(1, 0) else intArrayOf(0, 1), BooleanArray(2), constant,
                if (swapped) intArrayOf(1, 0) else intArrayOf(0, 1), IntArray(0))
            val hints = LpHints(2, 0, directionalGains = true)
            val variable = VarRef.IntVar(0)
            val domain = IntDomain(0, 10)
            val first = BigFraction.ofLong((1L shl 60) + 32L)
            val second = BigFraction.ofLong((1L shl 60) + 33L)
            repeat(2) {
                hints.beginNode(0, 100.0, 1L, first)
                val parent = relaxation()
                hints.record(parent, doubleArrayOf(2.25, 2.25), DoubleArray(parent.model.m))
                hints.recordBound(parent, 10.0)
                hints.branch(variable, domain)
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, 100.0, 1L, first)
                    val child = relaxation()
                    hints.record(child, doubleArrayOf(if (up) 3.0 else 2.0, 2.25), DoubleArray(child.model.m))
                    hints.recordBound(child, if (up) 13.0 else 11.0)
                }
            }
            when (change) {
                "rows", "finished rows" -> {
                    assertTrue(trail.pop(0))
                    if (change == "finished rows") hints.finishNode(trail.state)
                }
                "append", "finished append", "costed append", "column append", "rewritten prefix" -> {
                    if (change == "rewritten prefix") {
                        val base = trail.state.baseModel
                        trail = LpBoundTrail(base.copy(rhs = List(base.m) { ExactLpNumber.of(7L) }))
                    }
                    val addedColumns = if (change == "column append") {
                        listOf(LpStructuralColumn(ExactLpColumn(ExactLpBounds())))
                    } else emptyList()
                    assertTrue(trail.append(addedColumns, listOf(
                        LpScopedRow(2L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(9L),
                            ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L)))),
                            cost = ExactLpNumber.of(if (change == "costed append") 1L else 0L)),
                    ), scoped = false))
                    if (change == "finished append") hints.finishNode(trail.state)
                }
                "objective" -> {
                    val old = trail.state.model.objective
                    assertTrue(trail.replaceObjective(ExactLpObjective(List(old.size) { old.cost(it) },
                        scale = ExactLpNumber.of(2L))))
                }
                "constant" -> constant = 1L
                "layout" -> swapped = true
                "reset" -> hints.resetHistory()
                "clear" -> hints.clear()
            }
            hints.beginNode(0, if (change == "cutoff") 99.0 else 100.0,
                if (change == "incumbent") 2L else 1L, if (change == "external") second else first)
            val current = relaxation()
            hints.record(current, doubleArrayOf(2.25, 2.25), DoubleArray(current.model.m))
            if (change == "kind") hints.recordObjective(current, 10.0) else hints.recordBound(current, 10.0)
            hints.prepareSide(variable, domain, allowBasis = false) { _, _ -> null }
            hints.branch(variable, domain)
            val existing = listOf(SearchDecision.IntAtMost(0, 2), SearchDecision.IntAtLeast(0, 3))

            val ordered = hints.orderAlternatives(variable, existing, allowBasis = false)

            assertEquals(if (retained) existing.reversed() else existing, ordered, change)
        }
    }

    @Test
    fun `current row changes discard prepared side preferences`() {
        for (append in listOf(false, true)) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            assertTrue(trail.push())
            assertTrue(trail.append(
                LpScopedRow(1L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(8L),
                    ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))), scoped = true,
            ))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                BooleanArray(1), 0L, intArrayOf(0), IntArray(0))
            val hints = LpHints(1, 0, directionalGains = true)
            val variable = VarRef.IntVar(0)
            val domain = IntDomain(0, 10)
            repeat(2) {
                hints.beginNode(0, 100.0)
                hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(relaxation.model.m))
                hints.recordBound(relaxation, 10.0)
                hints.branch(variable, domain)
                for (up in listOf(false, true)) {
                    hints.commit(if (up) SearchDecision.IntAtLeast(0, 3) else SearchDecision.IntAtMost(0, 2), 1)
                    hints.beginNode(1, 100.0)
                    hints.record(relaxation, doubleArrayOf(if (up) 3.0 else 2.0), DoubleArray(relaxation.model.m))
                    hints.recordBound(relaxation, if (up) 13.0 else 11.0)
                }
            }
            hints.beginNode(0, 100.0)
            hints.record(relaxation, doubleArrayOf(2.25), DoubleArray(relaxation.model.m))
            hints.recordBound(relaxation, 10.0)
            assertTrue(hints.prepareSide(variable, domain, allowBasis = false) { _, _ -> null })
            val existing = listOf(SearchDecision.IntAtMost(0, 2), SearchDecision.IntAtLeast(0, 3))

            if (append) {
                assertTrue(trail.append(
                    LpScopedRow(2L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(9L),
                        ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))), scoped = false,
                ))
            } else assertTrue(trail.pop(0))
            hints.finishNode(trail.state)
            hints.branch(variable, domain)
            val ordered = hints.orderAlternatives(variable, existing, allowBasis = false)

            assertEquals(existing, ordered)
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
