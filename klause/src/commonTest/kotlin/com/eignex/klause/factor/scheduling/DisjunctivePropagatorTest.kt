package com.eignex.klause.factor.scheduling

import com.eignex.klause.factor.ConflictReasonOracle
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
import com.eignex.klause.propagation.undoTo
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisjunctivePropagatorTest {

    @Test
    fun `disjunctive deductions over optional tasks should be implied by their reasons`() {
        val rng = Random(0xD15)
        repeat(100) { iter ->
            val n = 3
            val problem = Problem(
                numBoolVars = n,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, 6) },
                factors = arrayOf<Factor>(
                    Cumulative.unary(
                        starts = IntArray(n) { it },
                        durations = LongArray(n) { 2L + rng.nextInt(2) },
                        presents = IntArray(n) { Lit.make(it, true) },
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "disjunctive#$iter") { state ->
                (0 until n).all { b -> rng.nextInt(3) != 0 || state.pinBool(b, rng.nextBoolean()) } &&
                    (0 until 3).all {
                        val v = rng.nextInt(n)
                        if (rng.nextBoolean()) {
                            state.tightenIntMax(v, rng.nextInt(4).toLong())
                        } else {
                            state.tightenIntMin(v, 1L + rng.nextInt(4))
                        }
                    }
            }
        }
    }

    @Test
    fun `disjunctive deductions over duration variables should be implied by their reasons`() {
        val rng = Random(0xD16)
        repeat(40) { iter ->
            val n = 3
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2 * n,
                intDomains = Array(2 * n) { if (it < n) IntDomain(0, 4) else IntDomain(1, 3) },
                factors = arrayOf<Factor>(
                    Cumulative.unary(
                        starts = IntArray(n) { it },
                        durations = LongArray(n) { 1L },
                        durationVars = IntArray(n) { n + it },
                    ),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "disjunctive-durations#$iter") { state ->
                (0 until n).all {
                    val d = 1L + rng.nextInt(3)
                    state.tightenIntMin(n + it, d) && state.tightenIntMax(n + it, d)
                } &&
                    (0 until 3).all {
                        val v = rng.nextInt(n)
                        when (rng.nextInt(3)) {
                            0 -> state.excludeIntValue(v, rng.nextInt(5).toLong())
                            1 -> state.tightenIntMin(v, 1L + rng.nextInt(4))
                            else -> state.tightenIntMax(v, rng.nextInt(5).toLong())
                        }
                    }
            }
        }
    }

    @Test
    fun `overload deductions over duration ranges and optional tasks should be implied by their reasons`() {
        for ((index, shape) in overloadShapes.withIndex()) {
            val problem = shape.problem()
            val rng = Random(0xD17 + index)
            repeat(5) { iter ->
                PropagationReasonOracle.assertReasonsImply(problem, "${shape.label}#$iter") { state ->
                    (0 until 3).all { b -> rng.nextInt(3) != 0 || state.pinBool(b, rng.nextBoolean()) } &&
                        shape.starts.all { state.tightenIntMax(it, 1L + rng.nextInt(3)) } &&
                        shape.durationVars.all { rng.nextBoolean() || state.tightenIntMin(it, 2) } &&
                        (rng.nextBoolean() || state.excludeIntValue(shape.starts[2], 1))
                }
            }
        }
    }

    @Test
    fun `guard exclusions should be implied by their reasons`() {
        val guards = IntArray(3) { Lit.make(it / 2, true) }
        val shapes = listOf(
            OverloadShape("shared guard", presents = guards),
            OverloadShape("shared guard and start", starts = intArrayOf(0, 0, 1), presents = guards),
            OverloadShape("shared guard and duration", durationVars = intArrayOf(3, 3, 4), presents = guards),
            OverloadShape("shared guard zero minima", presents = guards, minDuration = 0),
            OverloadShape("one guard", presents = IntArray(3) { Lit.make(0, false) }),
        )
        for ((index, shape) in shapes.withIndex()) {
            val problem = shape.problem()
            val rng = Random(0xD19 + index)
            repeat(3) { iter ->
                PropagationReasonOracle.assertReasonsImply(problem, "${shape.label}#$iter") { state ->
                    (0 until 2).all { b -> rng.nextInt(3) != 0 || state.pinBool(b, rng.nextBoolean()) } &&
                        shape.starts.all { state.tightenIntMax(it, 1L + rng.nextInt(2)) } &&
                        shape.durationVars.all { rng.nextBoolean() || state.tightenIntMin(it, 2) } &&
                        (rng.nextBoolean() || state.excludeIntValue(shape.starts[0], 1))
                }
            }
        }
    }

    @Test
    fun `start bounds over minimum durations should be implied by their reasons`() {
        for ((index, shape) in overloadShapes.withIndex()) {
            val problem = shape.problem()
            val rng = Random(0xD18 + index)
            repeat(3) { iter ->
                PropagationReasonOracle.assertReasonsImply(problem, "${shape.label} starts#$iter") { state ->
                    (0 until 3).all { b -> rng.nextInt(4) == 0 || state.pinBool(b, rng.nextInt(4) != 0) } &&
                        shape.starts.all {
                            if (rng.nextBoolean()) {
                                state.tightenIntMin(it, rng.nextInt(2).toLong())
                            } else {
                                state.tightenIntMax(it, 1L + rng.nextInt(3))
                            }
                        } &&
                        shape.durationVars.all { rng.nextInt(3) != 0 || state.tightenIntMin(it, 2) } &&
                        (rng.nextBoolean() || state.excludeIntValue(shape.starts[1], 1))
                }
            }
        }
    }

    private class OverloadShape(
        val label: String,
        val starts: IntArray = intArrayOf(0, 1, 2),
        val durationVars: IntArray = intArrayOf(3, 4, 5),
        val presents: IntArray = IntArray(3) { Lit.make(it, true) },
        val minDuration: Long = 1,
    ) {
        fun problem(): Problem {
            val used = (starts + durationVars).toSet()
            return Problem(
                numBoolVars = 3,
                numIntVars = 6,
                intDomains = Array(6) {
                    when {
                        it !in used -> IntDomain(0, 0)
                        it < 3 -> IntDomain(0, 3)
                        else -> IntDomain(minDuration, 2)
                    }
                },
                factors = arrayOf<Factor>(
                    Cumulative.unary(
                        starts = starts,
                        durations = LongArray(3) { 2L },
                        presents = presents,
                        durationVars = durationVars,
                    ),
                ),
            )
        }
    }

    private val overloadShapes = listOf(
        OverloadShape("distinct"),
        OverloadShape("shared start", starts = intArrayOf(0, 0, 1)),
        OverloadShape("shared duration", durationVars = intArrayOf(3, 3, 4)),
        OverloadShape("repeated presence", presents = IntArray(3) { Lit.make(it / 2, true) }),
        OverloadShape("opposite presence", presents = IntArray(3) { Lit.make(minOf(it, 1), it < 2) }),
        OverloadShape("zero minima", minDuration = 0),
        OverloadShape("start as duration", durationVars = intArrayOf(3, 4, 0)),
    )

    @Test
    fun `tasks sharing an unknown presence that cannot fit together should rule it out`() {
        for (positive in listOf(true, false)) {
            val problem = Problem(
                numBoolVars = 1,
                numIntVars = 2,
                intDomains = Array(2) { IntDomain(0, 1) },
                factors = arrayOf<Factor>(guardedPair(positive)),
            )

            val result = problem.propagate(Assumptions.None)

            assertTrue(result is PropagationResult.Implied, "$result")
            assertEquals(!positive, result.boolValueOrNull(0))
        }
    }

    @Test
    fun `a ruled-out shared presence should reach a clause over it`() {
        val problem = Problem(
            numBoolVars = 2,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 1) },
            factors = arrayOf(guardedPair(true), Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )

        val result = problem.propagate(Assumptions.None)

        assertTrue(result is PropagationResult.Implied, "$result")
        assertEquals(true, result.boolValueOrNull(1))
    }

    @Test
    fun `a guard exclusion reason should cite the windows as they stood and not the shared presence`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 9) },
            factors = arrayOf<Factor>(guardedPair(true)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 1) && state.tightenIntMax(1, 1))
        state.currentFactor = 0
        check(state.factorAt(0).propagate(state, 0) && state.boolValues[0] == false)
        val recorded = state.boolAntecedents[0]
        state.currentFactor = -1
        state.currentLevel = 2
        check(state.tightenIntMax(0, 0))

        val reason = requireNotNull(state.reasonOf(recorded))

        val cited = reason.map { lit ->
            val v = Lit.variable(lit)
            if (v < problem.numBoolVars) {
                "b$v=${Lit.isPositive(lit)}"
            } else {
                val atom = v - problem.numBoolVars
                "x${state.atoms.intVar[atom]} ${state.atoms.kind[atom]} ${state.atoms.threshold[atom]}"
            }
        }.toSet()
        assertEquals(setOf("x0 LE 1", "x1 LE 1"), cited)
    }

    @Test
    fun `a guard exclusion should retract with its premises`() {
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 9) },
            factors = arrayOf<Factor>(guardedPair(true)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        val mark = state.mark()
        state.currentLevel = 1
        check(state.tightenIntMax(0, 1) && state.tightenIntMax(1, 1))
        state.currentFactor = 0
        check(state.factorAt(0).propagate(state, 0) && state.boolValues[0] == false)

        state.undoTo(mark)

        assertNull(state.boolValues[0])
    }

    @Test
    fun `an unrepresentable completion time should decline the guard check`() {
        val top = Long.MAX_VALUE - 1
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(top, top) },
            factors = arrayOf<Factor>(guardedPair(true)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        assertNull(state.boolValues[0])
    }

    private fun guardedPair(positive: Boolean) = Cumulative.unary(
        starts = intArrayOf(0, 1),
        durations = longArrayOf(2, 2),
        presents = IntArray(2) { Lit.make(0, positive) },
    )

    @Test
    fun `minimum durations should bound a start against a task it cannot pass`() {
        // A at 0 must finish by 2 at the earliest before B starts; A at 3 leaves B only the room before it.
        for ((aStart, expected) in listOf(0L to (2L to 3L), 3L to (0L to 1L))) {
            val problem = minimumPair(IntDomain(aStart, aStart))

            val result = problem.propagate(Assumptions.None)

            assertTrue(result is PropagationResult.Implied, "$result")
            assertEquals(expected, (result.intMinOrNullCompat(1) ?: 0L) to (result.intMaxOrNullCompat(1) ?: 3L))
        }
    }

    @Test
    fun `a minimum-duration start bound should reach a linked row`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 3), IntDomain(2, 3), IntDomain(2, 3), IntDomain(1, 5)),
            factors = arrayOf(minimumUnary(), Linear(longArrayOf(1, 1), intArrayOf(1, 4), LinearOp.LE, 3)),
        )

        val result = problem.propagate(Assumptions.None)

        assertTrue(result is PropagationResult.Implied, "$result")
        assertEquals(1L, result.intValueOrNull(4))
    }

    @Test
    fun `a minimum-duration start bound should cite the minima as they stood`() {
        val problem = minimumPair(IntDomain(0, 9), durations = IntDomain(1, 3))
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 0) && state.tightenIntMin(2, 2) && state.tightenIntMin(3, 2))
        state.currentFactor = 0
        check(state.factorAt(0).propagate(state, 0) && state.intDomains[1].min == 2L)
        val recorded = state.intMinAntecedents[1]
        state.currentFactor = -1
        state.currentLevel = 2
        check(state.tightenIntMin(3, 3))

        val reason = requireNotNull(state.reasonOf(recorded))

        val cited = reason.map { lit ->
            val atom = Lit.variable(lit)
            "x${state.atoms.intVar[atom]} ${state.atoms.kind[atom]} ${state.atoms.threshold[atom]}"
        }.toSet()
        assertEquals(setOf("x0 LE 0", "x2 GE 2", "x3 GE 2"), cited)
    }

    @Test
    fun `a minimum-duration start bound should retract with its premises`() {
        val problem = minimumPair(IntDomain(0, 9))
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        val mark = state.mark()
        state.currentLevel = 1
        check(state.tightenIntMax(0, 0))
        state.currentFactor = 0
        check(state.factorAt(0).propagate(state, 0) && state.intDomains[1].min == 2L)

        state.undoTo(mark)

        assertEquals(0L, state.intDomains[1].min)
    }

    // Start A in [aStart], start B in 0..3, both durations in [durations].
    private fun minimumPair(aStart: IntDomain, durations: IntDomain = IntDomain(2, 3)) = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = arrayOf(aStart, IntDomain(0, 3), durations, durations),
        factors = arrayOf<Factor>(minimumUnary()),
    )

    private fun minimumUnary() =
        Cumulative.unary(starts = intArrayOf(0, 1), durations = longArrayOf(3, 3), durationVars = intArrayOf(2, 3))

    @Test
    fun `minimum durations should refute three tasks that cannot share a window`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { if (it < 3) IntDomain(0, 2) else IntDomain(2, 3) },
            factors = arrayOf<Factor>(
                Cumulative.unary(
                    starts = intArrayOf(0, 1, 2),
                    durations = LongArray(3) { 3L },
                    durationVars = intArrayOf(3, 4, 5),
                ),
            ),
        )

        val result = problem.propagate(Assumptions.None)

        assertTrue(result is PropagationResult.Unsat, "$result")
    }

    @Test
    fun `an unknown task overloading the present ones should be ruled absent`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(optionalTriple()),
        )

        val result = problem.propagate(Assumptions(bools = mapOf(0 to true, 1 to true)))

        assertTrue(result is PropagationResult.Implied, "$result")
        assertEquals(false, result.boolValueOrNull(2))
    }

    @Test
    fun `an excluded presence should reach a clause over it`() {
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 2) },
            factors = arrayOf(optionalTriple(), Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true)))),
        )

        val result = problem.propagate(Assumptions(bools = mapOf(0 to true, 1 to true)))

        assertTrue(result is PropagationResult.Implied, "$result")
        assertEquals(true, result.boolValueOrNull(3))
    }

    @Test
    fun `an exclusion reason should cite the overloaded windows as they stood and not the excluded presence`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 6,
            intDomains = Array(6) { if (it < 3) IntDomain(0, 9) else IntDomain(1, 4) },
            factors = arrayOf<Factor>(
                Cumulative.unary(
                    starts = intArrayOf(0, 1, 2),
                    durations = LongArray(3) { 4L },
                    presents = IntArray(3) { Lit.make(it, true) },
                    durationVars = intArrayOf(3, 4, 5),
                ),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.pinBool(0, true) && state.pinBool(1, true))
        check((0 until 3).all { state.tightenIntMax(it, 2) } && (3 until 6).all { state.tightenIntMin(it, 2) })
        state.currentFactor = 0
        check(state.factorAt(0).propagate(state, 0))
        val recorded = state.boolAntecedents[2]
        state.currentFactor = -1
        state.currentLevel = 2
        check(state.tightenIntMax(0, 1) && state.tightenIntMin(3, 3))

        val reason = requireNotNull(state.reasonOf(recorded))

        val cited = reason.map { lit ->
            val v = Lit.variable(lit)
            if (v < problem.numBoolVars) {
                "b$v=${Lit.isPositive(lit)}"
            } else {
                val atom = v - problem.numBoolVars
                "x${state.atoms.intVar[atom]} ${state.atoms.kind[atom]} ${state.atoms.threshold[atom]}"
            }
        }.toSet()
        assertEquals(
            setOf("b0=false", "b1=false", "x0 LE 2", "x1 LE 2", "x2 LE 2", "x3 GE 2", "x4 GE 2", "x5 GE 2"),
            cited,
        )
    }

    @Test
    fun `an exclusion should retract with its premises`() {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(optionalTriple()),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        val mark = state.mark()
        state.currentLevel = 1
        check(state.pinBool(0, true) && state.pinBool(1, true) && (0 until 3).all { state.tightenIntMax(it, 2) })
        state.currentFactor = 0
        check(state.factorAt(0).propagate(state, 0) && state.boolValues[2] == false)

        state.undoTo(mark)

        assertNull(state.boolValues[2])
    }

    @Test
    fun `an unrepresentable completion time should decline the overload check`() {
        val top = Long.MAX_VALUE - 1
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(top, top), IntDomain(top, top), IntDomain(2, 3), IntDomain(2, 3)),
            factors = arrayOf<Factor>(
                Cumulative.unary(
                    starts = intArrayOf(0, 1),
                    durations = longArrayOf(3, 3),
                    durationVars = intArrayOf(2, 3),
                ),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.currentFactor = 0

        assertTrue(state.factorAt(0).propagate(state, 0))
    }

    private fun optionalTriple() = Cumulative.unary(
        starts = intArrayOf(0, 1, 2),
        durations = LongArray(3) { 2L },
        presents = IntArray(3) { Lit.make(it, true) },
    )

    @Test
    fun `an edge-finding bound should cite only the cluster it follows`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 9) },
            factors = arrayOf<Factor>(
                Cumulative.unary(starts = intArrayOf(0, 1, 2, 3), durations = longArrayOf(2, 2, 2, 2)),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 3) && state.tightenIntMax(1, 3) && state.tightenIntMax(2, 4))
        check(state.tightenIntMin(3, 7))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.intMinAntecedents[2])!!.map { state.atoms.intVar[Lit.variable(it)] }.toSet()
        assertEquals(setOf(0, 1), cited)
    }

    @Test
    fun `energetic-window conflict reason should cite only the tasks packed into the overloaded window`() {
        // Three unit tasks squeezed into [0, 2) overload without any compulsory overlap.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(
                Cumulative.unary(starts = intArrayOf(0, 1, 2, 3), durations = longArrayOf(1, 1, 1, 1)),
            ),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 1))
        check(state.tightenIntMax(1, 1))
        check(state.tightenIntMax(2, 1))
        check(state.tightenIntMin(3, 5))
        state.currentFactor = 0
        assertFalse(state.factorAt(0).propagate(state, 0))
        val reason = state.factorAt(0).conflictReason(state, 0)!!
        val citedVars = reason.map { state.atoms.intVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertTrue(citedVars.all { it in setOf(0, 1, 2) }, "$citedVars")
        ConflictReasonOracle.assertEntailed(problem, state, 0, "disjunctive-energetic")
    }

    @Test
    fun `mutual-precedence conflict reason should be a sound nogood citing only the two tasks`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(Cumulative.unary(starts = intArrayOf(0, 1, 2), durations = longArrayOf(3, 3, 3))),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 2))
        check(state.tightenIntMax(1, 2))
        check(state.tightenIntMin(2, 6))
        state.currentFactor = 0
        assertFalse(state.factorAt(0).propagate(state, 0))
        val reason = state.factorAt(0).conflictReason(state, 0)!!
        val citedVars = reason.map { state.atoms.intVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertTrue(citedVars.all { it == 0 || it == 1 }, "$citedVars")
        ConflictReasonOracle.assertEntailed(problem, state, 0, "disjunctive-precedence")
    }

    @Test
    fun `pairwise detectable precedence should push the earliest start`() {
        val factor = Cumulative.unary(starts = intArrayOf(0, 1), durations = longArrayOf(3, 1))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Implied, "$result")
        val unsatPin = problem.propagate(Assumptions(ints = mapOf(1 to 2)))
        assertTrue(unsatPin is PropagationResult.Unsat, "$unsatPin")
        val okPin = problem.propagate(Assumptions(ints = mapOf(1 to 3)))
        assertTrue(okPin is PropagationResult.Implied, "$okPin")
    }

    @Test
    fun `edge-finding should pin a task forced to come last by an energetic overflow`() {
        // 0 + 2 + 4 > lct({0, 1}) = 5 puts task 2 after the cluster; no single pair forces it.
        val factor = Cumulative.unary(starts = intArrayOf(0, 1, 2), durations = longArrayOf(2, 2, 2))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 4)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Implied, "$result")
        assertEquals(4, result.ints[2], "implied=${result.ints}")
    }

    @Test
    fun `pure pairwise infeasibility should be detected`() {
        val factor = Cumulative.unary(starts = intArrayOf(0, 1), durations = longArrayOf(1, 1))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(5, 5), IntDomain(5, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions.None)
        assertTrue(result is PropagationResult.Unsat, "$result")
    }
}
