package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.ClauseExchange
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.withLifetime
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateRebuildSatisfactionTest {
    @Test
    fun `an independent private byte grant should preserve the canonical scratch limit`() {
        val clauses = (0..3).flatMap { excluded ->
            val columns = (0..3).filter { it != excluded }
            val rhs = if (excluded == 0) 1 else 0
            (0..7).filter { it.countOneBits() % 2 != rhs }.map { mask ->
                Clause(IntArray(3) { Lit.make(columns[it], mask and (1 shl it) == 0) })
            }
        }.toTypedArray<Factor>()
        val source = Problem(4, 0, emptyArray(), clauses).bake()
        for (privateBytes in listOf(null, 1_048_576L)) {
            val params = BacktrackParams(privateRebuildWork = 65_536L,
                learnedMemoryScratchBytes = 0L, privateRebuildBytes = privateBytes)

            val result = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(params))

            assertEquals(0L, params.learnedMemoryScratchBytes)
            assertEquals(if (privateBytes == null) 0L else 1L,
                result.stats.search.learning["privateRebuildConsumers"] ?: 0L)
            assertTrue(clauses.all { factor ->
                (factor as Clause).literals.any { Lit.evaluate(it, result.assignment.boolValue(Lit.variable(it))) }
            })
        }
    }


    @Test
    fun `proved parity pins should reach a consuming worker and a checked source witness`() {
        for (native in listOf(false, true)) {
            val clauses = (0..3).flatMap { excluded ->
                val columns = (0..3).filter { it != excluded }
                val rhs = if (excluded == 0) 1 else 0
                (0..7).filter { it.countOneBits() % 2 != rhs }.map { mask ->
                    Clause(IntArray(3) { Lit.make(columns[it], mask and (1 shl it) == 0) })
                }
            }.toTypedArray<Factor>()
            val source = Problem(4, 0, emptyArray(), clauses).bake()

            val result = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(BacktrackParams(
                nativeSat = native, privateRebuildWork = 65_536L,
            )))

            assertTrue(clauses.all { factor ->
                (factor as Clause).literals.any { Lit.evaluate(it, result.assignment.boolValue(Lit.variable(it))) }
            })
            val learning = result.stats.search.learning
            assertEquals(4L, learning["privateRebuildParityPins"])
            assertEquals(4L, learning["privateRebuildParityGenerationPins"])
            assertEquals(1L, learning["privateRebuildConsumers"])
            assertEquals(1L, learning["privateRebuildSourceAccepted"])
            assertEquals(1L, learning["privateRebuildRetired"])
        }
    }

    @Test
    fun `free affine coordinates should reach a consuming worker and a checked source witness`() {
        for (native in listOf(false, true)) {
            val clauses = listOf(0, 3, 5, 6).map { mask ->
                Clause(IntArray(3) { Lit.make(it, mask and (1 shl it) == 0) })
            }.toTypedArray<Factor>()
            val source = Problem(3, 0, emptyArray(), clauses).bake()

            val result = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(BacktrackParams(
                nativeSat = native, privateRebuildWork = 65_536L,
            )))

            assertTrue(clauses.all { factor ->
                (factor as Clause).literals.any { Lit.evaluate(it, result.assignment.boolValue(Lit.variable(it))) }
            })
            val learning = result.stats.search.learning
            assertEquals(0L, learning["privateRebuildParityPins"])
            assertEquals(1L, learning["privateRebuildAffineGenerationColumns"])
            assertEquals(2L, learning["privateRebuildAffineGenerationFreeColumns"])
            assertEquals(1L, learning["privateRebuildConsumers"])
            assertEquals(1L, learning["privateRebuildSourceAccepted"])
            assertEquals(1L, learning["privateRebuildRetired"])
        }
    }

    @Test
    fun `mixed parity rows should reach a consumer with a checked source witness`() {
        for (native in listOf(false, true)) {
            val rows = listOf(intArrayOf(0, 1, 2, 0), intArrayOf(1, 2, 3, 1))
            val clauses = rows.flatMap { row ->
                (0..7).filter { it.countOneBits() % 2 != row[3] }.map { mask ->
                    Clause(IntArray(3) { Lit.make(row[it], mask and (1 shl it) == 0) })
                }
            } + Clause(intArrayOf(Lit.make(0, false), Lit.make(3, true)))
            val source = Problem(4, 0, emptyArray(), clauses.toTypedArray<Factor>()).bake()

            val result = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(BacktrackParams(
                nativeSat = native, privateRebuildWork = 65_536L,
            )))

            assertTrue(clauses.all { clause ->
                clause.literals.any { Lit.evaluate(it, result.assignment.boolValue(Lit.variable(it))) }
            })
            val learning = result.stats.search.learning
            assertEquals(0L, learning["privateRebuildParityPins"])
            assertEquals(2L, learning["privateRebuildParityReducedRows"])
            assertEquals(8L, learning["privateRebuildParityCoveredClauses"])
            assertEquals(1L, learning["privateRebuildConsumers"])
            assertEquals(1L, learning["privateRebuildSourceAccepted"])
            assertEquals(1L, learning["privateRebuildRetired"])
        }
    }

    @Test
    fun `parity discovery should remain in the complete private charged ledger`() {
        val clauses = (0..3).flatMap { excluded ->
            val columns = (0..3).filter { it != excluded }
            val rhs = if (excluded == 0) 1 else 0
            (0..7).filter { it.countOneBits() % 2 != rhs }.map { mask ->
                Clause(IntArray(3) { Lit.make(columns[it], mask and (1 shl it) == 0) })
            }
        }.toTypedArray<Factor>()
        val source = Problem(4, 0, emptyArray(), clauses).bake()

        val result = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(BacktrackParams(
            privateRebuildWork = 65_536L,
        )))

        val learning = result.stats.search.learning
        val stages = listOf("RootScanWork", "ParityDiscoveryWork", "BinaryScanWork", "BinaryHistoryWork",
            "PreparationWork", "OpeningConstructionWork", "TrialWork", "ReconstructionWork", "SourceCheckWork")
        assertTrue(learning.getValue("privateRebuildParityDiscoveryWork") > 0L)
        assertEquals(stages.sumOf { learning["privateRebuild$it"] ?: 0L },
            learning.getValue("privateRebuildChargedWork"))
    }

    @Test
    fun `an owned learned equivalence should publish a checked witness without root pins`() {
        for (replacement in listOf(2, 3)) for (native in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(1, replacement, 4)), Clause(intArrayOf(1, replacement, 5)),
                Clause(intArrayOf(0, Lit.negate(replacement), 6)), Clause(intArrayOf(0, Lit.negate(replacement), 7)),
            )).bake()
            var opens = 0
            var closes = 0
            val exchange = object : ClauseExchange {
                override fun onSearchStart(session: PropagationSession) {
                    opens++
                    session.addLearnedClause(Clause(intArrayOf(1, replacement)), 2)
                    session.addLearnedClause(Clause(intArrayOf(0, Lit.negate(replacement))), 2)
                }
                override fun onSearchClose(session: PropagationSession) { closes++ }
                override fun onRestart(session: PropagationSession) = Unit
            }

            val result = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(BacktrackParams(
                nativeSat = native, privateRebuildWork = 65_536L, clauseExchange = exchange,
            )))

            assertTrue(source.factors.all { factor ->
                (factor as Clause).literals.any { Lit.evaluate(it, result.assignment.boolValue(Lit.variable(it))) }
            })
            val stats = result.stats.search.learning
            assertNull(stats["privateRebuildProgressPins"])
            assertEquals(1L, stats["privateRebuildBinaryRelations"])
            assertEquals(1L, stats["privateRebuildEquivalentColumns"])
            assertEquals(0L, stats["privateRebuildBveIndexedFactors"])
            assertEquals(1L, stats["privateRebuildConsumers"])
            assertEquals(1L, stats["privateRebuildSourceAccepted"])
            assertEquals(1L, stats["privateRebuildRetired"])
            assertEquals(1, opens)
            assertEquals(1, closes)
        }
    }

    @Test
    fun `root pins should open a checked private consumer and release its generation`() {
        for (native in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3)),
                Clause(intArrayOf(2, 4)), Clause(intArrayOf(3, 6)), Clause(intArrayOf(5, 7)),
            )).bake()
            var opens = 0
            var closes = 0
            val exchange = object : ClauseExchange {
                override fun onSearchStart(session: PropagationSession) { opens++ }
                override fun onSearchClose(session: PropagationSession) { closes++ }
                override fun onRestart(session: PropagationSession) = Unit
            }

            val result = BacktrackSolver(source).solve(BacktrackParams(
                nativeSat = native, privateRebuildWork = 65_536L, clauseExchange = exchange,
                variableSelector = InputOrder, valueSelector = IndomainMin,
            ))

            val accepted = assertIs<SolveResult.Sat>(result)
            assertTrue(source.factors.all { factor ->
                (factor as Clause).literals.any { Lit.evaluate(it, accepted.assignment.boolValue(Lit.variable(it))) }
            })
            val stats = result.stats.search.learning
            assertEquals(1L, stats["privateRebuildConsumers"])
            assertEquals(1L, stats["privateRebuildSourceAccepted"])
            assertEquals(1L, stats["privateRebuildRetired"])
            assertTrue(stats.getValue("privateRebuildProgressPins") > 0L)
            assertTrue(stats.getValue("privateRebuildWarmClausesRetained") > 0L)
            assertTrue(stats.getValue("privateRebuildBakeConstructionEntries") > 0L)
            assertTrue(stats.getValue("privateRebuildOpeningConstructionWork") > 0L)
            assertTrue(stats.getValue("privateRebuildMappingPreparationWork") > 0L)
            assertEquals(1, opens)
            assertEquals(1, closes)
        }
    }

    @Test
    fun `eligibility scans should not count as canonical propagation`() {
        for (native in listOf(false, true)) {
            val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake()
            val params = BacktrackParams(nativeSat = native,
                variableSelector = InputOrder, valueSelector = IndomainMin)
            val canonical = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(params))

            val trial = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(
                params.copy(privateRebuildWork = 65_536L),
            ))

            assertEquals(canonical.stats.search.propagationWork.sum, trial.stats.search.propagationWork.sum)
            assertTrue(trial.stats.search.learning.getValue("privateRebuildRootScanWork") > 0L)
            assertTrue(trial.stats.search.learning.getValue("privateRebuildChargedWork") > 0L)
        }
    }

    @Test
    fun `retired trial work should remain in the final charged ledger`() {
        for (native in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3)),
                Clause(intArrayOf(4, 6)), Clause(intArrayOf(5, 7)),
            )).bake()

            val result = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(BacktrackParams(
                nativeSat = native, privateRebuildWork = 65_536L,
                variableSelector = InputOrder, valueSelector = IndomainMin,
            )))

            val learning = result.stats.search.learning
            assertTrue(learning.getValue("privateRebuildChargedWork") >=
                learning.getValue("privateRebuildPreparationWork") + learning.getValue("privateRebuildTrialWork"))
        }
    }

    @Test
    fun `a refused generation should resume the retained worker without retrying preparation`() {
        val source = Problem(4, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3)),
            Clause(intArrayOf(4, 6)), Clause(intArrayOf(5, 7)),
        )).bake()
        val search = BacktrackSolver(source).resumableSolve(BacktrackParams(
            privateRebuildWork = 65_536L, learnedMemoryScratchBytes = 0L,
            variableSelector = InputOrder, valueSelector = IndomainMin,
        ))
        var result: SolveResult? = null

        search.use {
            assertNull(it.runSlice(Cancellation.Never, Long.MAX_VALUE, 0L))
            repeat(16) {
                if (result == null) result = search.runSlice(Cancellation.Never, Long.MAX_VALUE, 1L)
            }
        }

        val completed = assertIs<SolveResult.Sat>(assertNotNull(result))
        assertEquals(1L, completed.stats.search.learning["privateRebuildAttempts"])
        assertEquals(1L, completed.stats.search.learning["privateRebuildDeclined"])
        assertNull(completed.stats.search.learning["privateRebuildConsumers"])
    }

    @Test
    fun `an unchanged baked root should solve without preparing a private generation`() {
        for (pinned in listOf(false, true)) {
            val clauses: Array<Factor> = if (pinned) {
                arrayOf(Clause(intArrayOf(0)), Clause(intArrayOf(2, 4)))
            } else arrayOf(Clause(intArrayOf(0, 2, 4)))
            val source = Problem(3, 0, emptyArray(), clauses).bake()

            val result = BacktrackSolver(source).solve(BacktrackParams(privateRebuildWork = 65_536L))

            assertIs<SolveResult.Sat>(result)
            assertNull(result.stats.search.learning["privateRebuildAttempts"])
            assertNull(result.stats.search.learning["privateRebuildConsumers"])
        }
    }

    @Test
    fun `caller assumptions should keep their original search and verdict authority`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake()

        val result = BacktrackSolver(source).solve(BacktrackParams(
            privateRebuildWork = 65_536L, assumptions = Assumptions.None.withBool(0, false),
        ))

        val accepted = assertIs<SolveResult.Sat>(result)
        assertEquals(false, accepted.assignment.boolValue(0))
        assertNull(result.stats.search.learning["privateRebuildAttempts"])
    }

    @Test
    fun `one shot cancellation during opening should return unknown`() {
        for (native in listOf(false, true)) {
            val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake()

            val result = BacktrackSolver(source).solve(BacktrackParams(
                nativeSat = native, privateRebuildWork = 65_536L, cancellation = Cancellation { true },
            ))

            assertIs<SolveResult.Unknown>(result)
            assertNull(result.stats.search.learning["privateRebuildConsumers"])
        }
    }

    @Test
    fun `an established root refutation should retain its canonical verdict after cancellation`() {
        for (work in listOf(0L, 65_536L, -1L)) {
            val source = Problem(1, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0)), Clause(intArrayOf(1)),
            )).bake()

            val result = BacktrackSolver(source).solve(BacktrackParams(
                privateRebuildWork = work, cancellation = Cancellation { true },
            ))

            assertIs<SolveResult.Unsat>(result)
            assertNull(result.stats.search.learning["privateRebuildConsumers"])
        }
    }

    @Test
    fun `an expired opening token should not cancel a later private consumer`() {
        val source = Problem(4, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3)),
            Clause(intArrayOf(4, 6)), Clause(intArrayOf(5, 7)),
        )).bake()
        var openingEnded = false
        val search = BacktrackSolver(source).resumableSolve(BacktrackParams(
            privateRebuildWork = 65_536L, cancellation = Cancellation { openingEnded },
            variableSelector = InputOrder, valueSelector = IndomainMin,
        ))
        openingEnded = true

        val result = search.use { it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) }

        val accepted = assertIs<SolveResult.Sat>(result)
        assertEquals(1L, accepted.stats.search.learning["privateRebuildSourceAccepted"])
    }

    @Test
    fun `cancellation during canonical checking should withhold the private witness and release its generation`() {
        for (native in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3)),
                Clause(intArrayOf(4, 6)), Clause(intArrayOf(5, 7)),
            )).bake()
            var live: ResumableSolve? = null
            val cancellation = Cancellation {
                (live?.stats?.search?.learning?.get("privateRebuildSourceCheckWork") ?: 0L) > 0L
            }
            val search = BacktrackSolver(source).resumableSolve(BacktrackParams(
                nativeSat = native, privateRebuildWork = 65_536L,
                variableSelector = InputOrder, valueSelector = IndomainMin,
            ))
            live = search

            val result = search.use { it.runSlice(cancellation, Long.MAX_VALUE, -1L) }

            val stopped = assertIs<SolveResult.Unknown>(result)
            assertTrue(stopped.stats.search.learning.getValue("privateRebuildSourceCheckWork") > 0L)
            assertEquals(1L, stopped.stats.search.learning["privateRebuildRetired"])
            assertNull(stopped.stats.search.learning["privateRebuildSourceAccepted"])
        }
    }

    @Test
    fun `cancellation after private opening should release the generation without publishing a point`() {
        for (lifetime in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3)),
                Clause(intArrayOf(4, 6)), Clause(intArrayOf(5, 7)),
            )).bake()
            var live: ResumableSolve? = null
            val cancellation = Cancellation {
                (live?.stats?.search?.learning?.get("privateRebuildConsumers") ?: 0L) > 0L
            }
            val params = BacktrackParams(privateRebuildWork = 65_536L,
                variableSelector = InputOrder, valueSelector = IndomainMin,
                cancellation = if (lifetime) Cancellation.Never.withLifetime(cancellation) else Cancellation.Never)
            val search = BacktrackSolver(source).resumableSolve(params)
            live = search

            val result = search.use {
                it.runSlice(if (lifetime) Cancellation.Never else cancellation, Long.MAX_VALUE, -1L)
            }

            val stopped = assertIs<SolveResult.Unknown>(result)
            assertEquals(1L, stopped.stats.search.learning["privateRebuildConsumers"])
            assertEquals(1L, stopped.stats.search.learning["privateRebuildRetired"])
            assertNull(stopped.stats.search.learning["privateRebuildSourceAccepted"])
            assertEquals(stopped, search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L))
        }
    }

    @Test
    fun `automatic parity admission should publish a checked source point and release its consumer`() {
        for (native in listOf(false, true)) {
            val clauses = (0 until 128).flatMap { row ->
                listOf(1, 2, 4, 7).map { mask ->
                    Clause(IntArray(3) { Lit.make(row + it, mask and (1 shl it) == 0) })
                }
            } + Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))
            val source = Problem(130, 0, emptyArray(), clauses.toTypedArray<Factor>()).bake()

            val result = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(BacktrackParams(
                nativeSat = native, privateRebuildWork = -1L, learnedMemoryScratchBytes = 4_194_304L,
                variableSelector = InputOrder, valueSelector = IndomainMin,
            )))

            assertTrue(clauses.all { clause ->
                clause.literals.any { Lit.evaluate(it, result.assignment.boolValue(Lit.variable(it))) }
            })
            val learning = result.stats.search.learning
            assertEquals(1L, learning["privateRebuildAutomaticAdmitted"])
            assertEquals(1L, learning["privateRebuildConsumers"])
            assertEquals(1L, learning["privateRebuildSourceAccepted"])
            assertEquals(1L, learning["privateRebuildRetired"])
            assertEquals(67_108_864L, learning["privateRebuildAutomaticWorkLimit"])
            val stages = listOf("RootScanWork", "ParityDiscoveryWork", "BinaryScanWork", "BinaryHistoryWork",
                "PreparationWork", "OpeningConstructionWork", "TrialWork", "ReconstructionWork", "SourceCheckWork")
            assertEquals(stages.sumOf { learning["privateRebuild$it"] ?: 0L },
                learning.getValue("privateRebuildChargedWork"))
            assertTrue(learning.getValue("privateRebuildChargedWork") <= 1_048_576L)
        }
    }

    @Test
    fun `automatic low rank misses should retain canonical search without other private attempts`() {
        val clauses = (0 until 128).flatMap {
            listOf(1, 2, 4, 7).map { mask ->
                Clause(IntArray(3) { Lit.make(it, mask and (1 shl it) == 0) })
            }
        }.toTypedArray<Factor>()
        val source = Problem(128, 0, emptyArray(), clauses).bake()

        val result = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(BacktrackParams(
            privateRebuildWork = -1L, learnedMemoryScratchBytes = 4_194_304L,
        )))

        assertTrue(clauses.all { factor ->
            (factor as Clause).literals.any { Lit.evaluate(it, result.assignment.boolValue(Lit.variable(it))) }
        })
        val learning = result.stats.search.learning
        assertEquals(1L, learning["privateRebuildAutomaticAttempts"])
        assertEquals(1L, learning["privateRebuildAutomaticDeclinedStructure"])
        assertNull(learning["privateRebuildAttempts"])
        assertNull(learning["privateRebuildConsumers"])
        assertNull(learning["privateRebuildRootScanWork"])
        assertNull(learning["privateRebuildBinaryScanWork"])
        assertTrue(learning.getValue("privateRebuildParityDiscoveryWork") > 0L)
        assertEquals(learning.getValue("privateRebuildParityDiscoveryWork"),
            learning.getValue("privateRebuildChargedWork"))
    }

    @Test
    fun `explicit zero should disable discovery on an automatically sized source`() {
        val clauses = (0 until 128).flatMap { row ->
            listOf(1, 2, 4, 7).map { mask ->
                Clause(IntArray(3) { Lit.make(row + it, mask and (1 shl it) == 0) })
            }
        }.toTypedArray<Factor>()
        val source = Problem(130, 0, emptyArray(), clauses).bake()
        val automatic = BacktrackParams(privateRebuildWork = -1L, learnedMemoryScratchBytes = 4_194_304L)

        val result = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(
            automatic.copy(privateRebuildWork = 0L),
        ))

        assertNull(result.stats.search.learning["privateRebuildAutomaticAttempts"])
        assertNull(result.stats.search.learning["privateRebuildParityDiscoveryWork"])
        assertNull(result.stats.search.learning["privateRebuildConsumers"])
    }

    @Test
    fun `automatic admission should respect an exhausted scratch grant`() {
        val clauses = (0 until 128).flatMap { row ->
            listOf(1, 2, 4, 7).map { mask ->
                Clause(IntArray(3) { Lit.make(row + it, mask and (1 shl it) == 0) })
            }
        } + Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))
        val source = Problem(130, 0, emptyArray(), clauses.toTypedArray<Factor>()).bake()

        val result = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(BacktrackParams(
            privateRebuildWork = -1L, learnedMemoryScratchBytes = 0L, variableSelector = InputOrder,
            valueSelector = IndomainMin,
        )))

        assertTrue(clauses.all { clause ->
            clause.literals.any { Lit.evaluate(it, result.assignment.boolValue(Lit.variable(it))) }
        })
        val learning = result.stats.search.learning
        assertEquals(1L, learning["privateRebuildAutomaticAttempts"])
        assertEquals(1L, learning["privateRebuildParityDeclinedMemory"])
        assertNull(learning["privateRebuildConsumers"])
        assertEquals(0L, learning["privateRebuildChargedWork"])
    }

    @Test
    fun `small automatic requests should retain canonical search without discovery`() {
        val clauses = listOf(1, 2, 4, 7).map { mask ->
            Clause(IntArray(3) { Lit.make(it, mask and (1 shl it) == 0) })
        }.toTypedArray<Factor>()
        val source = Problem(3, 0, emptyArray(), clauses).bake()

        val result = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(BacktrackParams(
            privateRebuildWork = -1L,
        )))

        assertNull(result.stats.search.learning["privateRebuildAutomaticAttempts"])
        assertNull(result.stats.search.learning["privateRebuildParityDiscoveryWork"])
        assertNull(result.stats.search.learning["privateRebuildConsumers"])
    }

    @Test
    fun `automatic low coverage misses should retain the complete canonical source`() {
        val clauses = (0 until 128).flatMap { row ->
            listOf(1, 2, 4, 7).map { mask ->
                Clause(IntArray(3) { Lit.make(row + it, mask and (1 shl it) == 0) })
            }
        } + List(600) { Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))) }
        val source = Problem(130, 0, emptyArray(), clauses.toTypedArray<Factor>()).bake()

        val result = assertIs<SolveResult.Sat>(BacktrackSolver(source).solve(BacktrackParams(
            privateRebuildWork = -1L, learnedMemoryScratchBytes = 4_194_304L,
            variableSelector = InputOrder, valueSelector = IndomainMin,
        )))

        assertTrue(clauses.all { clause ->
            clause.literals.any { Lit.evaluate(it, result.assignment.boolValue(Lit.variable(it))) }
        })
        val learning = result.stats.search.learning
        assertEquals(128L, learning["privateRebuildParityBasisColumns"])
        assertEquals(512L, learning["privateRebuildParityCoveredSourceClauses"])
        assertEquals(1L, learning["privateRebuildAutomaticDeclinedStructure"])
        assertNull(learning["privateRebuildConsumers"])
        assertEquals(learning.getValue("privateRebuildParityDiscoveryWork"),
            learning.getValue("privateRebuildChargedWork"))
    }
}
