package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetBitsetAlgebraTest {

    @Test
    fun `subset forces right true when left is true`() {
        // left ⊆ right over universe of size 4. Pinning left[1] = true should imply right[1] = true.
        val left = intArrayOf(0, 1, 2, 3)
        val right = intArrayOf(4, 5, 6, 7)
        val factor = SetBitsetSubset(left, right)
        val problem = Problem(
            numBoolVars = 8, numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.propagate(Assumptions(bools = mapOf(1 to true)))
        assertTrue(r is PropagationResult.Implied, "expected Implied; got $r")
        assertEquals(true, r.bools[5], "right[1] (bool 5) should be implied true")
    }

    @Test
    fun `subset forces left false when right is false`() {
        val left = intArrayOf(0, 1, 2, 3)
        val right = intArrayOf(4, 5, 6, 7)
        val factor = SetBitsetSubset(left, right)
        val problem = Problem(
            numBoolVars = 8, numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.propagate(Assumptions(bools = mapOf(6 to false)))
        assertTrue(r is PropagationResult.Implied)
        assertEquals(false, r.bools[2], "left[2] (bool 2) should be implied false")
    }

    @Test
    fun `subset absent-right pins left false`() {
        // Universe position 2 is absent from right (-1 sentinel) — must force left[2] = false.
        val left = intArrayOf(0, 1, 2, 3)
        val right = intArrayOf(4, 5, -1, 6)
        val factor = SetBitsetSubset(left, right)
        val problem = Problem(
            numBoolVars = 7, numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.propagate(Assumptions.None)
        assertTrue(r is PropagationResult.Implied)
        assertEquals(false, r.bools[2], "left[2] must be pinned false (no right slot)")
    }

    @Test
    fun `subset detects contradiction`() {
        val left = intArrayOf(0, 1)
        val right = intArrayOf(2, 3)
        val factor = SetBitsetSubset(left, right)
        val problem = Problem(
            numBoolVars = 4, numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.propagate(Assumptions(bools = mapOf(1 to true, 3 to false)))
        assertTrue(r is PropagationResult.Unsat, "left[1]=true ∧ right[1]=false must conflict; got $r")
    }

    @Test
    fun `disjoint forces other false when one is true`() {
        val left = intArrayOf(0, 1, 2)
        val right = intArrayOf(3, 4, 5)
        val factor = SetBitsetDisjoint(left, right)
        val problem = Problem(
            numBoolVars = 6, numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.propagate(Assumptions(bools = mapOf(1 to true)))
        assertTrue(r is PropagationResult.Implied)
        assertEquals(false, r.bools[4], "right[1] must be forced false since left[1] is true")
    }

    @Test
    fun `disjoint detects conflict when both true`() {
        val left = intArrayOf(0, 1)
        val right = intArrayOf(2, 3)
        val factor = SetBitsetDisjoint(left, right)
        val problem = Problem(
            numBoolVars = 4, numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.propagate(Assumptions(bools = mapOf(0 to true, 2 to true)))
        assertTrue(r is PropagationResult.Unsat)
    }

    @Test
    fun `eq biconditional in both directions`() {
        val left = intArrayOf(0, 1, 2)
        val right = intArrayOf(3, 4, 5)
        val factor = SetBitsetEq(left, right)
        val problem = Problem(
            numBoolVars = 6, numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.propagate(Assumptions(bools = mapOf(0 to true, 5 to false)))
        assertTrue(r is PropagationResult.Implied)
        assertEquals(true, r.bools[3], "left[0]=true ⇒ right[0]=true")
        assertEquals(false, r.bools[2], "right[2]=false ⇒ left[2]=false")
    }

    @Test
    fun `eq absent-position forces other side false`() {
        val left = intArrayOf(0, 1, -1)
        val right = intArrayOf(2, -1, 3)
        val factor = SetBitsetEq(left, right)
        val problem = Problem(
            numBoolVars = 4, numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(factor),
        )
        val r = problem.propagate(Assumptions.None)
        assertTrue(r is PropagationResult.Implied)
        assertEquals(false, r.bools[1], "left[1] has no right partner → must be false")
        assertEquals(false, r.bools[3], "right[2] has no left partner → must be false")
    }
}
