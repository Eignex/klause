package com.eignex.klause.model

import com.eignex.skema.SchemaDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReferencesTest {
    private val qualify: (String) -> String = { "slot.$it" }

    private fun integers(n: (String) -> String): List<IntExpr> {
        val x = IntRef(n("x"))
        val y = IntRef(n("y"))
        return listOf(
            IntScale(2, x),
            IntSum(listOf(x, IntLit(1))),
            IntMin(listOf(x, y)),
            IntMax(listOf(x, y)),
            IntAbs(x),
            IntElement(x, listOf(y, IntLit(3))),
            IntMul(x, y),
            IntDiv(x, y),
            IntMod(x, y),
            IntPower(x, 2),
            SetCard(SetRef(n("s"))),
            IntIfThenElse(BoolRef(n("b")), x, y),
        )
    }

    private fun sets(n: (String) -> String): List<SetExpr> {
        val s = SetRef(n("s"))
        val t = SetRef(n("t"))
        return listOf(
            SetUnion(s, SetLiteral(listOf(1, 2))),
            SetIntersect(s, t),
            SetDiff(SetRef(n("m")), SetNominalLiteral(listOf("red"))),
        )
    }

    @Suppress("LongMethod")
    private fun constraints(n: (String) -> String): List<BoolExpr> {
        val x = IntRef(n("x"))
        val y = IntRef(n("y"))
        val xs = listOf(x, y)
        val b = BoolRef(n("b"), negated = true)
        val c = BoolRef(n("c"))
        val lits = listOf(b, c)
        val s = SetRef(n("s"))
        val t = SetRef(n("t"))
        val unit = listOf(1, 1)
        return listOf(
            NominalEq(n("color"), "red"),
            Not(b),
            And(lits),
            Or(lits),
            Implies(b, c),
            Iff(b, c),
            AtMost(lits, k = 1),
            AtLeast(lits, k = 1),
            CardinalityExpr(lits, min = 0, max = 1),
            XorExpr(lits),
            PseudoBooleanExpr(weights = listOf(2, 3), lits = lits, op = PbOp.LE, bound = 4),
            IntCompare(IntMul(x, y), IntCmpOp.LE, IntLit(5)),
            FloatLinearConstraint(doubleArrayOf(1.5), listOf(n("f")), IntCmpOp.GE, bound = 0.5),
            ContinuousRealEquality(
                ContinuousRealExpr.Multiply(
                    ContinuousRealExpr.Affine(mapOf(n("f") to 2.0)),
                    ContinuousRealExpr.Affine(mapOf(n("g") to 1.0)),
                ),
                ContinuousRealExpr.Power(ContinuousRealExpr.Affine(mapOf(n("g") to 1.0), constant = 1.0), 2),
            ),
            AllDifferent(xs),
            SymmetricAllDifferent(xs),
            InverseChannel(f = xs, g = xs),
            MddExpr(seq = listOf(x), numStatesPerLayer = unit, layerStarts = listOf(0, 3),
                transitions = listOf(0, 1, 0), initial = 0, accepting = listOf(0)),
            CostRegularExpr(seq = listOf(x), numStates = 1, numSymbols = 1, transitions = listOf(1),
                weights = listOf(2), initial = 1, accepting = listOf(1), cost = y),
            CostMddExpr(seq = listOf(x), numStatesPerLayer = unit, layerStarts = listOf(0, 4),
                transitions = listOf(0, 1, 0, 2), initial = 0, accepting = listOf(0), cost = y),
            CircuitExpr(xs),
            SubcircuitExpr(xs),
            CumulativeExpr(starts = xs, durations = unit, resources = unit, capacity = 1),
            DisjunctiveExpr(starts = xs, durations = unit),
            SortExpr(xs = xs, ys = listOf(y, x)),
            IncreasingExpr(xs),
            DiffnExpr(xs = xs, ys = listOf(y, x), widths = unit, heights = unit),
            RegularExpr(seq = listOf(x), numStates = 1, alphabetSize = 1, transitions = listOf(1), q0 = 1,
                accepting = listOf(1)),
            TableConstraint(xs, listOf(listOf(1, 2))),
            SetIn(x, s),
            SetNominalIn("red", SetRef(n("m"))),
            SetSubsetOf(s, t),
            SetDisjoint(s, t),
            SetEq(s, t),
            AllDifferentOpt(xs, lits),
            CumulativeExprOpt(starts = xs, durations = unit, resources = unit, capacity = 1, presents = lits),
            DisjunctiveExprOpt(starts = xs, durations = unit, presents = lits),
            NValueExprOpt(n = y, xs = xs, presents = lits),
            GccExprOpt(xs = xs, cover = listOf(1), low = listOf(0), high = listOf(1), closed = false, presents = lits),
        )
    }

    @Test
    fun `mapReferences should rename references nested in every integer node`() {
        assertEquals(integers(qualify), integers { it }.map { it.mapReferences(qualify) })
    }

    @Test
    fun `mapReferences should rename references nested in every set node`() {
        assertEquals(sets(qualify), sets { it }.map { it.mapReferences(qualify) })
    }

    @Test
    fun `mapReferences should rename references in Boolean global continuous and optional constraints`() {
        assertEquals(constraints(qualify), constraints { it }.map { it.mapReferences(qualify) })
    }

    @Test
    fun `mapReferences should keep signed zero continuous coefficients`() {
        val expr = ContinuousRealExpr.Affine(mapOf("x" to -0.0), constant = -0.0)

        val mapped = expr.mapReferences(qualify) as ContinuousRealExpr.Affine

        assertEquals(listOf("slot.x"), mapped.coefficients.keys.toList())
        assertEquals((-0.0).toRawBits(), mapped.coefficients.getValue("slot.x").toRawBits())
        assertEquals((-0.0).toRawBits(), mapped.constant.toRawBits())
    }

    @Test
    fun `mapReferences should reject merging continuous coefficients`() {
        val expr = ContinuousRealExpr.Affine(mapOf("x" to 0.1, "y" to 0.2))

        assertFailsWith<IllegalArgumentException> { expr.mapReferences { "z" } }
    }

    @Test
    fun `renameDeclarations should rename entries with their references in declaration order`() {
        val def = SchemaDef<SchemaEntry>(
            linkedMapOf(
                "x__present" to PresenceSpec("x"),
                "x" to IntSpec(0, 3),
                "fit" to NamedConstraint(IntCompare(IntMul(IntRef("x"), IntRef("x")), IntCmpOp.LE, IntLit(4))),
            ),
        )

        val renamed = def.renameDeclarations(qualify)

        val expected = listOf(
            "slot.x__present" to PresenceSpec("slot.x"),
            "slot.x" to IntSpec(0, 3),
            "slot.fit" to NamedConstraint(
                IntCompare(IntMul(IntRef("slot.x"), IntRef("slot.x")), IntCmpOp.LE, IntLit(4)),
            ),
        )
        assertEquals(expected, renamed.entries.toList())
    }

    @Test
    fun `renameDeclarations should reject two entries renamed to the same name`() {
        val def = SchemaDef<SchemaEntry>(linkedMapOf("x" to IntSpec(0, 1), "y" to BoolSpec))

        assertFailsWith<IllegalArgumentException> { def.renameDeclarations { "z" } }
    }
}
