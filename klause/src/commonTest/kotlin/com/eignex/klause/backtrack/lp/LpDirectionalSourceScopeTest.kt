package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.ExactLpSide
import com.eignex.klause.lp.engine.LpBoundTrail
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpScopedRow
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.lp.relaxation.CutColumnSource
import com.eignex.klause.lp.relaxation.CutSourceMap
import com.eignex.klause.lp.relaxation.LpRelaxation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpDirectionalSourceScopeTest {
    @Test
    fun `row edits preserve completed source coordinates and objective units`() {
        for (scoped in listOf(false, true)) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1L)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val coordinates = listOf(CutColumnSource(CutSource(CutSourceKind.INTEGER, 0)))
            val map = CutSourceMap(Any(), 0L, coordinates)
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0), sourceMap = map)
            val scope = assertNotNull(LpDirectionalSourceScope.capture(relaxation, {}))
            assertTrue(trail.append(
                LpScopedRow(0L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(8L),
                    ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))), scoped,
            ))

            val next = relaxation.withModel(assertNotNull(trail.state.ownerWorkingModel()), map)

            assertTrue(scope.matches(next, {}))
        }
    }

    @Test
    fun `foreign source coordinates mappings and assumptions reject retained estimates`() {
        for (change in listOf("source", "coordinates", "variable", "kind", "assumptions", "missing")) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1L)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            val source = Any()
            val coordinates = listOf(CutColumnSource(CutSource(CutSourceKind.INTEGER, 0)))
            val map = CutSourceMap(source, 0L, coordinates)
            val model = assertNotNull(trail.state.ownerWorkingModel())
            val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false),
                0L, intArrayOf(0), IntArray(0), sourceMap = map)
            val scope = assertNotNull(LpDirectionalSourceScope.capture(relaxation, {}))
            val nextMap = if (change == "missing") null else CutSourceMap(
                if (change == "source") Any() else source, 0L,
                if (change == "coordinates") {
                    listOf(CutColumnSource(CutSource(CutSourceKind.INTEGER, 0)))
                } else coordinates,
                assumptions = if (change == "assumptions") setOf("condition") else emptySet(),
            )
            val next = LpRelaxation(model, intArrayOf(if (change == "variable") 1 else 0),
                booleanArrayOf(change == "kind"), 0L, intArrayOf(0), IntArray(0), sourceMap = nextMap)

            assertFalse(scope.matches(next, {}), change)
        }
    }

    @Test
    fun `bound premise epochs preserve completed source units`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val model = assertNotNull(trail.state.ownerWorkingModel())
        val map = CutSourceMap(Any(), 0L, listOf(CutColumnSource(CutSource(CutSourceKind.INTEGER, 0))))
        val relaxation = LpRelaxation(model, intArrayOf(0), booleanArrayOf(false),
            0L, intArrayOf(0), IntArray(0), sourceMap = map)
        val scope = assertNotNull(LpDirectionalSourceScope.capture(relaxation, {}))

        val next = relaxation.withModel(model, map.withBounds(model))

        assertTrue(scope.matches(next, {}))
    }

    @Test
    fun `changed objective units coefficients and logical costs reject retained estimates`() {
        for (change in listOf("constant", "scale", "external", "sense", "coefficient", "logical")) {
            val builder = LpBuilder()
            builder.addVar(0, 10, cost = 1L)
            val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
            assertTrue(trail.append(
                LpScopedRow(0L, listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(8L),
                    ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))), false,
            ))
            val base = trail.state.baseModel
            val map = CutSourceMap(Any(), 0L, listOf(CutColumnSource(CutSource(CutSourceKind.INTEGER, 0))))
            val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
                booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0), sourceMap = map)
            val scope = assertNotNull(LpDirectionalSourceScope.capture(relaxation, {}))
            val objective = base.objective
            val changed = ExactLpObjective(
                List(base.numVars) { column ->
                    if ((change == "coefficient" && column == 0) || (change == "logical" && column == base.n)) {
                        ExactLpNumber.of(2L)
                    } else objective.cost(column)
                },
                constant = if (change == "constant") ExactLpNumber.of(1L) else objective.constant,
                scale = if (change == "scale") ExactLpNumber.of(2L) else objective.scale,
                externalConstant = if (change == "external") ExactLpNumber.of(1L) else objective.externalConstant,
                sense = if (change == "sense") Sense.MAXIMIZE else objective.sense,
            )
            val state = LpExactState(base.withObjective(changed))
            val next = relaxation.withModel(assertNotNull(state.ownerWorkingModel()), map)

            assertFalse(scope.matches(next, {}), change)
            if (change == "logical") assertNull(LpDirectionalSourceScope.capture(next, {}))
        }
    }

    @Test
    fun `oversized scopes decline before retaining or traversing vectors`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val map = CutSourceMap(Any(), 0L, listOf(CutColumnSource(CutSource(CutSourceKind.INTEGER, 0))))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0), sourceMap = map)
        var charged = 0L

        val scope = LpDirectionalSourceScope.capture(relaxation, { charged += it }, maxSize = 0)

        assertNull(scope)
        assertEquals(1L, charged)
    }

    @Test
    fun `row growth beyond the retained scope limit declines before traversal`() {
        val builder = LpBuilder()
        builder.addVar(0, 10, cost = 1L)
        val trail = LpBoundTrail(assertNotNull(builder.build(Sense.MINIMIZE).authoritativeModel()))
        val map = CutSourceMap(Any(), 0L, listOf(CutColumnSource(CutSource(CutSourceKind.INTEGER, 0))))
        val relaxation = LpRelaxation(assertNotNull(trail.state.ownerWorkingModel()), intArrayOf(0),
            booleanArrayOf(false), 0L, intArrayOf(0), IntArray(0), sourceMap = map)
        val scope = assertNotNull(LpDirectionalSourceScope.capture(relaxation, {}, maxSize = 1))
        repeat(2) { index ->
            assertTrue(trail.append(
                LpScopedRow(index.toLong(), listOf(0 to ExactLpNumber.of(1L)), ExactLpNumber.of(8L),
                    ExactLpColumn(ExactLpBounds(lower = ExactLpSide(ExactLpNumber.of(0L))))), false,
            ))
        }
        val next = relaxation.withModel(assertNotNull(trail.state.ownerWorkingModel()), map)
        var charged = 0L

        val matches = scope.matches(next) { charged += it }

        assertFalse(matches)
        assertEquals(1L, charged)
    }
}
