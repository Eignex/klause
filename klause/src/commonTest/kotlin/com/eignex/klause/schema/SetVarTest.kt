package com.eignex.klause.schema

import com.eignex.klause.ast.MultipleSpec
import com.eignex.klause.ast.SetSpec
import com.eignex.klause.ast.eq
import com.eignex.klause.ast.iff
import com.eignex.klause.compile.compile
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetVarDeclaratorTest {
    private class S : VariableSchema() {
        val chosen by setVar(0..3)
        val pickedLabels by multiple("a", "b", "c")
    }

    @Test
    fun `declarators register SetSpec and MultipleSpec`() {
        val s = S()
        val entries = s.entries.entries.toList()
        assertEquals(2, entries.size)
        assertEquals("chosen", entries[0].key)
        assertTrue(entries[0].value is SetSpec)
        assertEquals(listOf(0, 1, 2, 3), (entries[0].value as SetSpec).universe)
        assertEquals("pickedLabels", entries[1].key)
        assertTrue(entries[1].value is MultipleSpec)
        assertEquals(listOf("a", "b", "c"), (entries[1].value as MultipleSpec).labels)
    }

    @Test
    fun `compile allocates one indicator bool per universe element`() {
        val s = S()
        val compiled = s.compile()
        // 4 + 3 = 7 indicators
        assertEquals(7, compiled.problem.numBoolVars)
        assertEquals(4, compiled.setLayouts["chosen"]!!.size)
        assertEquals(3, compiled.setLayouts["pickedLabels"]!!.size)
        assertEquals(listOf("a", "b", "c"), compiled.setNominalLabels["pickedLabels"])
    }
}

class SetMembershipTest {
    private class S : VariableSchema() {
        val s by setVar(0..3)
        val x by intVar(0, 3)
        val c by constraint { x inSet s }
    }

    @Test
    fun `top-level x inSet s forces x to a present element`() {
        val schema = S()
        val compiled = schema.compile()
        val solver = BacktrackSolver(compiled.problem)
        val samples = solver.enumerate(BacktrackParams()).take(50).toList()
        assertTrue(samples.isNotEmpty(), "expected at least one solution")
        for (sample in samples) {
            val xv = compiled.decode(schema.x, sample)
            val sv = compiled.decode(schema.s, sample)
            assertTrue(xv in sv, "x=$xv not in s=$sv")
        }
    }
}

class SetSubsetTest {
    private class S : VariableSchema() {
        val a by setVar(0..2)
        val b by setVar(0..2)
        val c by constraint { a subsetOf b }
    }

    @Test
    fun `a subsetOf b holds in every solution`() {
        val schema = S()
        val compiled = schema.compile()
        val solver = BacktrackSolver(compiled.problem)
        val samples = solver.enumerate(BacktrackParams()).take(100).toList()
        // 2^3 * 2^3 = 64 raw assignments; sub-set pairs: |{A: A⊆B}| over each B is 2^|B|;
        // total = Σ_B 2^|B| = (1+1)^3 * ... actually total subsets-of-b summed = 3^3 = 27.
        assertEquals(27, samples.size, "expected 3^3 = 27 (A ⊆ B) pairs over 2^3 universe")
        for (sample in samples) {
            val av = compiled.decode(schema.a, sample)
            val bv = compiled.decode(schema.b, sample)
            assertTrue(bv.containsAll(av), "a=$av not subset of b=$bv")
        }
    }
}

class SetDisjointTest {
    private class S : VariableSchema() {
        val a by setVar(0..2)
        val b by setVar(0..2)
        val c by constraint { a disjointFrom b }
    }

    @Test
    fun `disjoint sets share no elements`() {
        val schema = S()
        val compiled = schema.compile()
        val solver = BacktrackSolver(compiled.problem)
        val samples = solver.enumerate(BacktrackParams()).take(100).toList()
        // For each pair: a[i] ∈ {0,1}, b[i] ∈ {0,1}, but not both → 3 options per element
        // → 3^3 = 27 disjoint pairs.
        assertEquals(27, samples.size)
        for (sample in samples) {
            val av = compiled.decode(schema.a, sample)
            val bv = compiled.decode(schema.b, sample)
            assertTrue(av.intersect(bv).isEmpty(), "a=$av and b=$bv share elements")
        }
    }
}

class SetUnionIntersectTest {
    private class S : VariableSchema() {
        val a by setVar(0..2)
        val b by setVar(0..2)
        val u by setVar(0..2)
        val c by constraint { (a union b) eq u }
    }

    @Test
    fun `union is computed correctly`() {
        val schema = S()
        val compiled = schema.compile()
        val solver = BacktrackSolver(compiled.problem)
        val samples = solver.enumerate(BacktrackParams()).take(200).toList()
        assertTrue(samples.isNotEmpty())
        for (sample in samples) {
            val av = compiled.decode(schema.a, sample)
            val bv = compiled.decode(schema.b, sample)
            val uv = compiled.decode(schema.u, sample)
            assertEquals(av union bv, uv, "u should equal a ∪ b but got u=$uv vs ${av union bv}")
        }
    }

    private class IntersectS : VariableSchema() {
        val a by setVar(0..2)
        val b by setVar(0..2)
        val i by setVar(0..2)
        val c by constraint { (a intersect b) eq i }
    }

    @Test
    fun `intersect is computed correctly`() {
        val schema = IntersectS()
        val compiled = schema.compile()
        val solver = BacktrackSolver(compiled.problem)
        val samples = solver.enumerate(BacktrackParams()).take(200).toList()
        assertTrue(samples.isNotEmpty())
        for (sample in samples) {
            val av = compiled.decode(schema.a, sample)
            val bv = compiled.decode(schema.b, sample)
            val iv = compiled.decode(schema.i, sample)
            assertEquals(av intersect bv, iv, "i should equal a ∩ b")
        }
    }
}

class SetCardTest {
    private class S : VariableSchema() {
        val s by setVar(0..3)
        val c by constraint { card(s) eq 2 }
    }

    @Test
    fun `card constraint forces exactly two members`() {
        val schema = S()
        val compiled = schema.compile()
        val solver = BacktrackSolver(compiled.problem)
        val samples = solver.enumerate(BacktrackParams()).take(20).toList()
        // C(4,2) = 6 subsets of size 2.
        assertEquals(6, samples.size)
        for (sample in samples) {
            val sv = compiled.decode(schema.s, sample)
            assertEquals(2, sv.size, "set should have cardinality 2, got $sv")
        }
    }
}

class SetReifiedTest {
    private class S : VariableSchema() {
        val s by setVar(0..2)
        val x by intVar(0, 2)
        val flag by boolVar()

        // flag ↔ x ∈ s — i.e. flag tracks membership.
        val r by constraint { flag iff (x inSet s) }
    }

    @Test
    fun `reified set membership tracks flag`() {
        val schema = S()
        val compiled = schema.compile()
        val solver = BacktrackSolver(compiled.problem)
        val samples = solver.enumerate(BacktrackParams()).take(100).toList()
        assertTrue(samples.isNotEmpty())
        for (sample in samples) {
            val xv = compiled.decode(schema.x, sample)
            val sv = compiled.decode(schema.s, sample)
            val flag = compiled.decode(schema.flag, sample)
            val expected = xv in sv
            assertEquals(
                expected,
                flag,
                "reified mismatch: x=$xv s=$sv flag=$flag expected=$expected"
            )
        }
    }
}

class NominalSetTest {
    private class S : VariableSchema() {
        val tags by multiple("red", "green", "blue")
        val c by constraint { "red" inSet tags }
    }

    @Test
    fun `nominal-set membership pins the indicator`() {
        val schema = S()
        val compiled = schema.compile()
        val solver = BacktrackSolver(compiled.problem)
        val samples = solver.enumerate(BacktrackParams()).take(20).toList()
        // "red" must be present; the other two are free. 2^2 = 4 solutions.
        assertEquals(4, samples.size)
        for (sample in samples) {
            val tags = compiled.decode(schema.tags, sample)
            assertTrue("red" in tags, "red should always be in tags=$tags")
        }
    }
}

class SetEqLiteralTest {
    private class S : VariableSchema() {
        val s by setVar(0..3)
        val c by constraint { s eq setOfInts(1, 2) }
    }

    @Test
    fun `set equals literal pins all indicators`() {
        val schema = S()
        val compiled = schema.compile()
        val solver = BacktrackSolver(compiled.problem)
        val samples = solver.enumerate(BacktrackParams()).take(5).toList()
        assertEquals(1, samples.size)
        assertEquals(setOf(1, 2), compiled.decode(schema.s, samples[0]))
    }
}

/**
 * Every set constraint must bit-blast cleanly — the decompositions land in BitBlaster-
 * supported factor kinds (Clause, Linear, ReifiedLinear), so this is a smoke gate on the
 * compiler-level lowering.
 */
class SetBitBlastTest {
    private class MembershipSchema : VariableSchema() {
        val s by setVar(0..2)
        val x by intVar(0, 2)
        val c by constraint { x inSet s }
    }
    private class SubsetSchema : VariableSchema() {
        val a by setVar(0..2)
        val b by setVar(0..2)
        val c by constraint { a subsetOf b }
    }
    private class DisjointSchema : VariableSchema() {
        val a by setVar(0..2)
        val b by setVar(0..2)
        val c by constraint { a disjointFrom b }
    }
    private class UnionSchema : VariableSchema() {
        val a by setVar(0..2)
        val b by setVar(0..2)
        val u by setVar(0..2)
        val c by constraint { (a union b) eq u }
    }
    private class CardSchema : VariableSchema() {
        val s by setVar(0..3)
        val c by constraint { card(s) eq 2 }
    }
    private class ReifiedSchema : VariableSchema() {
        val s by setVar(0..2)
        val x by intVar(0, 2)
        val flag by boolVar()
        val r by constraint { flag iff (x inSet s) }
    }

    @Test fun `inSet bit-blasts`() = assertBitBlasts(MembershipSchema().compile())

    @Test fun `subsetOf bit-blasts`() = assertBitBlasts(SubsetSchema().compile())

    @Test fun `disjointFrom bit-blasts`() = assertBitBlasts(DisjointSchema().compile())

    @Test fun `union bit-blasts`() = assertBitBlasts(UnionSchema().compile())

    @Test fun `card bit-blasts`() = assertBitBlasts(CardSchema().compile())

    @Test fun `reified inSet bit-blasts`() = assertBitBlasts(ReifiedSchema().compile())

    private fun assertBitBlasts(compiled: com.eignex.klause.compile.CompiledProblem) {
        val cnf = com.eignex.klause.cnf.BitBlaster.compile(compiled.problem)
        assertTrue(cnf.clauses.isNotEmpty(), "bit-blasted CNF should be non-empty")
    }
}
