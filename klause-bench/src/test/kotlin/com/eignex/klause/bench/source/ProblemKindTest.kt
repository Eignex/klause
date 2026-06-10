package com.eignex.klause.bench.source

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProblemKindTest {

    @Test
    fun `single-line solve minimize is COP`() {
        assertTrue(ProblemKind.hasSolveObjective("solve minimize objective;"))
    }

    @Test
    fun `multi-line solve item with search annotation is COP`() {
        // The shape of most annotated MiniZinc Challenge models (routing, scheduling): the
        // objective sits several lines below `solve`, past the search annotation. The old
        // line-by-line scan missed these and misclassified them as CSP.
        val model = """
            constraint forall(i in 1..n)(x[i] >= 0);
            solve
                :: int_search(u ++ [x[i, j] | i in 1..N, j in 1..N],
                    first_fail, indomain_min, complete)
                minimize objective;
        """.trimIndent()
        assertTrue(ProblemKind.hasSolveObjective(model))
    }

    @Test
    fun `solve satisfy is not COP`() {
        assertFalse(ProblemKind.hasSolveObjective("solve satisfy;"))
    }

    @Test
    fun `commented-out objective is not COP`() {
        assertFalse(ProblemKind.hasSolveObjective("% solve minimize objective;\nsolve satisfy;"))
    }

    @Test
    fun `solve-prefixed identifier does not falsely match`() {
        assertFalse(ProblemKind.hasSolveObjective("constraint solve_count = maximize_calls;\nsolve satisfy;"))
    }
}
