package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.result.OpenHintUsageSink
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenBooleanCandidateTest {

    @Test
    fun `construction observations preserve completed prefixes and the exact owner trace`() {
        for (scenario in 0..3) {
            var reference: Pair<List<String>, Boolean>? = null
            for (observed in listOf(false, true)) {
                var work = 0L
                val trace = ArrayList<String>()
                val failure = IllegalStateException("raw debit callback failed")
                val stop = object : Cancellation {
                    override fun isCancelled(): Boolean { trace += "poll"; return scenario == 2 && work >= 14 }
                    override fun workMeter(): WorkMeter = WorkMeter {
                        trace += "charge:$it"
                        if (scenario == 3 && it == 5L) throw failure
                        work += it
                    }
                }
                val clauses = listOf(Clause(intArrayOf(0, 2)),
                    Clause(intArrayOf(1, if (scenario == 1) 6 else 4)), Clause(intArrayOf(0, 1)))
                val usage = if (observed) OpenHintUsageSink() else null
                var hints: SourceConjunctionHints? = null
                var threw = false

                try {
                    hints = SourceConjunctionHints.create(clauses, 3, intArrayOf(1, 2),
                        SearchCandidateHints { trace += "primitive"; true }, stop,
                        usage = usage, available = { trace += "live"; true })
                } catch (actual: IllegalStateException) {
                    kotlin.test.assertSame(failure, actual)
                    threw = true
                }
                val actual = trace.toList() to (hints != null)
                if (!observed) reference = actual else assertEquals(reference, actual)
                assertEquals(scenario == 3, threw)
                assertTrue("primitive" !in trace && "live" !in trace)
                if (usage != null) {
                    val stats = usage.snapshot()
                    assertEquals(3L, stats.sourceBooleansMax)
                    assertEquals(3L, stats.sourceClausesMax)
                    assertEquals(2L, stats.sourcePrimitiveEntriesMax)
                    when (scenario) {
                        0 -> {
                            assertEquals(3L, stats.rawClausesMax)
                            assertEquals(6L, stats.rawLiteralsMax)
                            assertEquals(4L, stats.rawIncidencesMax)
                            assertEquals(3L, stats.rawVariablesMax)
                            assertEquals(1L, stats.clauseIndexesAdmitted)
                        }
                        1 -> {
                            assertEquals(1L, stats.rawClausesMax)
                            assertEquals(3L, stats.rawLiteralsMax)
                            assertEquals(3L, stats.rawIncidencesMax)
                            assertEquals(2L, stats.rawVariablesMax)
                            assertEquals(1L, stats.invalidPrimitiveDeclines)
                        }
                        2 -> {
                            assertEquals(0L, stats.rawClausesMax)
                            assertEquals(1L, stats.rawLiteralsMax)
                            assertEquals(0L, stats.rawIncidencesMax)
                            assertEquals(0L, stats.rawVariablesMax)
                            assertEquals(1L, stats.stoppedIndexes)
                        }
                        else -> {
                            assertEquals(0L, stats.rawClausesMax)
                            assertEquals(0L, stats.rawLiteralsMax)
                            assertEquals(0L, stats.rawIncidencesMax)
                            assertEquals(0L, stats.rawVariablesMax)
                            assertEquals(5L, stats.rawLedgerDebits)
                            assertEquals(5L, work)
                            assertEquals(1L, stats.abortedIndexes)
                        }
                    }
                    if (scenario != 0) assertEquals(0L, stats.clauseIndexesAdmitted)
                    assertTrue(assertNotNull(stats.rawLedgerDebits) > 0)
                }
                hints?.close()
            }
        }
    }

    @Test
    fun `complete raw clauses steer a declared choice after structural visits expire`() {
        val clauses = listOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 4))) +
            List(5000) { Clause(intArrayOf(6, 8, 10)) }
        var available = true
        var firstPoint = false
        val calls = IntArray(6)
        var work = 0L
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        val usage = OpenHintUsageSink()
        val hints = assertNotNull(SourceConjunctionHints.create(
            clauses, 6, intArrayOf(1, 2), SearchCandidateHints {
                calls[it]++
                if (it == 1) firstPoint else !firstPoint
            }, stop, usage = usage, available = { available },
        ))
        val constructionWork = work

        assertTrue(constructionWork in 1L..SourceConjunctionHints.MAX_INDEX_ENTRIES.toLong())
        assertContentEquals(IntArray(6), calls)
        assertEquals(1L, usage.snapshot().visitDeclines)
        assertEquals(1L, usage.snapshot().clauseIndexesAdmitted)
        assertEquals(false, hints.preferredBool(0))
        assertContentEquals(intArrayOf(0, 1, 1, 0, 0, 0), calls)
        assertTrue(work > constructionWork)
        firstPoint = true
        assertEquals(true, hints.preferredBool(0))
        assertContentEquals(intArrayOf(0, 2, 2, 0, 0, 0), calls)
        assertEquals(2L, usage.snapshot().clauseSelections)
        available = false
        assertNull(hints.preferredBool(0))
        hints.close()
        assertNull(hints.preferredBool(0))
    }

    @Test
    fun `a declined global profile supplies raw polarity without publishing prefix gates`() {
        val clauses = listOf(
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(0, 3, 5)),
        ) + List(5000) { Clause(intArrayOf(6, 8, 10)) }
        val usage = OpenHintUsageSink()
        val hints = assertNotNull(SourceConjunctionHints.create(
            clauses, 6, intArrayOf(1, 2), SearchCandidateHints { false },
            Cancellation.Never, usage = usage, available = { true },
        ))

        assertEquals(false, observedCandidateHints(SearchCandidateHints.None, hints, usage).preferredBool(0))
        assertEquals(1L, usage.snapshot().visitDeclines)
        assertEquals(1L, usage.snapshot().forwardUncovered)
        assertEquals(1L, usage.snapshot().clauseSelections)
        assertEquals(1L, usage.snapshot().componentSelections)
        assertEquals(0L, usage.snapshot().gateSelections)
        hints.close()
    }

    @Test
    fun `raw clause preferences include the entire late source suffix`() {
        val clauses = listOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 4))) +
            List(5000) { Clause(intArrayOf(6, 8, 10)) } +
            listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 2)))
        val usage = OpenHintUsageSink()
        val hints = assertNotNull(SourceConjunctionHints.create(
            clauses, 6, intArrayOf(1, 2), SearchCandidateHints { it == 2 },
            Cancellation.Never, usage = usage, available = { true },
        ))

        assertEquals(true, hints.preferredBool(0))
        assertEquals(1L, usage.snapshot().visitDeclines)
        assertEquals(1L, usage.snapshot().clauseSelections)
        hints.close()
    }

    @Test
    fun `a single containing stop withholds the completed raw core and retains its debt`() {
        val clauses = listOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 4))) +
            List(5000) { Clause(intArrayOf(6, 8, 10)) }
        var work = 0L
        var stoppedOnce = false
        var calls = 0
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean {
                if (work < 45000L || stoppedOnce) return false
                stoppedOnce = true
                return true
            }
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        val usage = OpenHintUsageSink()

        val hints = SourceConjunctionHints.create(
            clauses, 6, intArrayOf(1, 2), SearchCandidateHints { calls++; true },
            stop, usage = usage, available = { true },
        )

        assertNull(hints)
        assertTrue(stoppedOnce)
        assertTrue(work >= 45000L)
        assertEquals(0, calls)
        assertEquals(1L, usage.snapshot().clauseIndexesAdmitted)
        assertEquals(1L, usage.snapshot().stoppedIndexes)
        assertEquals(0L, usage.snapshot().indexesAdmitted)
    }

    @Test
    fun `a raw source with an invalid late literal exports no prefix preference`() {
        val clauses = listOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 4)), Clause(intArrayOf(6)))
        var calls = 0
        val usage = OpenHintUsageSink()

        val hints = SourceConjunctionHints.create(
            clauses, 3, intArrayOf(1, 2), SearchCandidateHints { calls++; true },
            Cancellation.Never, usage = usage, available = { true },
        )

        assertNull(hints)
        assertEquals(0, calls)
        assertEquals(0L, usage.snapshot().clauseIndexesAdmitted)
        assertEquals(1L, usage.snapshot().invalidPrimitiveDeclines)
    }

    @Test
    fun `hint observations distinguish unavailable points uncovered variables and producer declines`() {
        var available = false
        var calls = 0
        val usage = OpenHintUsageSink()
        val hints = assertNotNull(SourceConjunctionHints.create(
            emptyList(), 2, intArrayOf(0), SearchCandidateHints { calls++; null },
            Cancellation.Never, usage = usage, available = { available },
        ))

        assertNull(hints.preferredBool(0))
        assertEquals(0, calls)
        available = true
        assertNull(hints.preferredBool(1))
        assertEquals(0, calls)
        assertNull(hints.preferredBool(0))

        assertEquals(1, calls)
        assertEquals(1L, usage.snapshot().earlyPointUnavailable)
        assertEquals(1L, usage.snapshot().forwardUncovered)
        assertEquals(1L, usage.snapshot().primitiveProviderNone)
        hints.close()
    }

    @Test
    fun `an uncovered forward variable can select semantically while producer declines are memoized`() {
        for (value in listOf(false, null)) {
            var calls = 0
            val usage = OpenHintUsageSink()
            val hints = assertNotNull(SourceConjunctionHints.create(
                listOf(Clause(intArrayOf(0, 2))), 2, intArrayOf(0),
                SearchCandidateHints { calls++; value }, Cancellation.Never,
                usage = usage, available = { true },
            ))

            val preferred = hints.preferredBool(1)

            assertEquals(if (value == null) null else true, preferred)
            assertEquals(1, calls)
            assertEquals(1L, usage.snapshot().forwardUncovered)
            assertEquals(if (value == null) 1L else 0L, usage.snapshot().primitiveProviderNone)
            assertEquals(1L, usage.snapshot().clauseConsultations)
            assertEquals(if (value == null) 0L else 1L, usage.snapshot().clauseSelections)
            hints.close()
        }
    }

    @Test
    fun `hint observations preserve preferences callback order and charged work`() {
        for (scenario in 0..3) {
            var reference: Triple<Boolean?, List<String>, Long>? = null
            for (observed in listOf(false, true)) {
                var stopped = false
                var work = 0L
                val trace = ArrayList<String>()
                val stop = object : Cancellation {
                    override fun isCancelled(): Boolean { trace += "stop"; return stopped }
                    override fun workMeter(): WorkMeter = WorkMeter { trace += "charge:$it"; work += it }
                }
                val usage = if (observed) OpenHintUsageSink() else null
                val hints = assertNotNull(SourceConjunctionHints.create(
                    emptyList(), 2, intArrayOf(0), SearchCandidateHints {
                        trace += "primitive"
                        if (scenario == 3) stopped = true
                        if (scenario == 2) null else false
                    }, stop, usage = usage, available = { trace += "live"; scenario != 0 },
                ))
                trace.clear()
                work = 0L

                val preferred = hints.preferredBool(if (scenario == 1) 1 else 0)
                val actual = Triple(preferred, trace.toList(), work)

                if (!observed) reference = actual else assertEquals(reference, actual)
                assertNull(preferred)
                assertEquals(if (scenario >= 2) 1 else 0, trace.count { it == "primitive" })
                if (usage != null) {
                    assertEquals(if (scenario == 0) 1L else 0L, usage.snapshot().earlyPointUnavailable)
                    assertEquals(if (scenario == 1) 1L else 0L, usage.snapshot().forwardUncovered)
                    assertEquals(if (scenario == 2) 1L else 0L, usage.snapshot().primitiveProviderNone)
                }
                hints.close()
            }
        }
    }

    @Test
    fun `structural visit decline retains live primitive leaves without partial gates`() {
        val width = 22000
        val target = Lit.make(2, true)
        val clauses = listOf(
            Clause(intArrayOf(Lit.negate(target), Lit.make(0, true))),
            Clause(intArrayOf(Lit.negate(target), Lit.make(1, true))),
            Clause(intArrayOf(target, Lit.make(0, false), Lit.make(1, false))),
            Clause(IntArray(width) { Lit.make(it + 3, true) }),
        )
        for (interruption in 0..2) {
            var available = false
            var stopped = false
            var calls = 0
            var value = true
            val usage = OpenHintUsageSink()
            val hints = assertNotNull(SourceConjunctionHints.create(
                clauses, width + 3, intArrayOf(0, 1), SearchCandidateHints {
                    calls++
                    if (interruption == 1) available = false
                    if (interruption == 2) stopped = true
                    value
                }, Cancellation { stopped }, usage = usage, available = { available },
            ))

            assertEquals(1L, usage.snapshot().visitDeclines)
            assertEquals(0L, usage.snapshot().indexesAdmitted)
            assertNull(hints.preferredBool(0))
            assertEquals(0, calls)
            available = true
            assertNull(hints.preferredBool(2))
            assertEquals(0, calls)
            assertEquals(if (interruption == 0) true else null, hints.preferredBool(0))
            assertEquals(1, calls)
            if (interruption == 0) {
                value = false
                assertEquals(false, hints.preferredBool(0))
                available = false
                assertNull(hints.preferredBool(0))
            }
            assertEquals(0L, usage.snapshot().clauseConsultations)
            hints.close()
            assertNull(hints.preferredBool(0))
            assertNull(hints.preferredBool(2))
        }
    }

    @Test
    fun `source limits retain primitive fallback independently of legacy gate admission`() {
        val primitiveCount = SourceConjunctionHints.MAX_INDEX_ENTRIES / 2
        val clauses = ArrayList<Clause>()
        for (right in 1..primitiveCount + 1) {
            clauses += Clause(intArrayOf(0, Lit.make(right, true)))
        }
        for (live in listOf<(() -> Boolean)?>(null, { true })) {
            var calls = 0
            val usage = OpenHintUsageSink()
            val hints = SourceConjunctionHints.create(
                clauses, primitiveCount + 2, IntArray(primitiveCount) { it },
                SearchCandidateHints { calls++; false }, Cancellation.Never,
                usage = usage, available = live,
            )

            if (live == null) {
                assertNull(hints)
                assertEquals(1L, usage.snapshot().storageDeclines)
                assertEquals(0, calls)
            } else {
                val retained = assertNotNull(hints)
                assertEquals(false, retained.preferredBool(0))
                assertNull(retained.preferredBool(primitiveCount + 1))
                assertEquals(1L, usage.snapshot().visitDeclines)
                assertEquals(1, calls)
                retained.close()
            }
            assertEquals(0L, usage.snapshot().indexesAdmitted)
        }
    }

    @Test
    fun `primitive fallback requires complete validation and an opening hook`() {
        val width = 22000
        val clauses = listOf(Clause(IntArray(width) { Lit.make(it, true) }))
        var calls = 0
        val primitive = SearchCandidateHints { calls++; true }
        val invalid = OpenHintUsageSink()
        val oversized = OpenHintUsageSink()
        val stopped = OpenHintUsageSink()
        val legacy = OpenHintUsageSink()

        assertNull(SourceConjunctionHints.create(clauses, width, intArrayOf(0, width), primitive,
            Cancellation.Never, usage = invalid, available = { true }))
        assertNull(SourceConjunctionHints.create(clauses, width,
            IntArray(SourceConjunctionHints.MAX_INDEX_ENTRIES + 1), primitive,
            Cancellation.Never, usage = oversized, available = { true }))
        assertNull(SourceConjunctionHints.create(clauses, width, intArrayOf(0), primitive,
            Cancellation { true }, usage = stopped, available = { true }))
        assertNull(SourceConjunctionHints.create(clauses, width, intArrayOf(0), primitive,
            Cancellation.Never, usage = legacy))

        assertEquals(0, calls)
        assertEquals(1L, invalid.snapshot().invalidPrimitiveDeclines)
        assertEquals(1L, oversized.snapshot().sourceLimitDeclines)
        assertEquals(1L, stopped.snapshot().stoppedIndexes)
        assertEquals(1L, legacy.snapshot().visitDeclines)
    }

    @Test
    fun `raw construction retains spent indexing work and yields to a containing stop`() {
        val width = 22000
        val clauses = listOf(Clause(IntArray(width) { Lit.make(it, true) }))
        var work = 0L
        var limit = Long.MAX_VALUE
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work >= limit
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        var calls = 0
        val primitive = SearchCandidateHints { calls++; true }
        val baseline = assertNotNull(SourceConjunctionHints.create(
            clauses, width, intArrayOf(0), primitive, stop, available = { false },
        ))
        val spent = work
        baseline.close()
        work = 0
        limit = spent - 1
        val usage = OpenHintUsageSink()

        assertNull(SourceConjunctionHints.create(clauses, width, intArrayOf(0), primitive, stop,
            usage = usage, available = { false }))

        assertTrue(work >= limit)
        assertTrue(work <= spent)
        assertEquals(0, calls)
        assertEquals(1L, usage.snapshot().stoppedIndexes)
        assertEquals(0L, usage.snapshot().indexesAdmitted)
        assertEquals(0L, usage.snapshot().rawClausesMax)
        assertTrue(assertNotNull(usage.snapshot().rawLiteralsMax) in 1 until width.toLong())
        assertTrue(assertNotNull(usage.snapshot().rawIncidencesMax) <= assertNotNull(usage.snapshot().rawLiteralsMax))
        assertEquals(usage.snapshot().rawIncidencesMax, usage.snapshot().rawVariablesMax)
        assertTrue(assertNotNull(usage.snapshot().rawLedgerDebits) > 0)
    }

    @Test
    fun `an ordered wide conjunction retains every leaf within the recognition allowance`() {
        val width = 4096
        val target = Lit.make(width, true)
        val clauses = ArrayList<Clause>()
        for (input in 0 until width) clauses += Clause(intArrayOf(Lit.negate(target), Lit.make(input, true)))
        clauses += Clause(intArrayOf(target, *IntArray(width) { Lit.make(it, false) }))
        var falseLeaf = -1
        val hints = assertNotNull(SourceConjunctionHints.create(
            clauses, width + 1, IntArray(width) { it },
            SearchCandidateHints { it != falseLeaf }, Cancellation.Never,
        ))

        assertEquals(true, hints.preferredBool(width))
        falseLeaf = width - 1
        assertEquals(false, hints.preferredBool(width))
        hints.close()
    }

    @Test
    fun `a wide unrelated clause preserves recognition of a later complete gate`() {
        val width = 512
        val target = Lit.make(width + 2, true)
        val left = Lit.make(width, true)
        val right = Lit.make(width + 1, true)
        val usage = OpenHintUsageSink()
        val hints = assertNotNull(SourceConjunctionHints.create(
            listOf(
                Clause(IntArray(width) { Lit.make(it, true) }),
                Clause(intArrayOf(Lit.negate(target), left)),
                Clause(intArrayOf(Lit.negate(target), right)),
                Clause(intArrayOf(target, Lit.negate(left), Lit.negate(right))),
            ),
            width + 3, intArrayOf(width, width + 1),
            SearchCandidateHints.ofLiterals(intArrayOf(left, right)), Cancellation.Never, usage = usage,
        ))

        assertEquals(true, hints.preferredBool(width + 2))
        assertEquals(1L, usage.snapshot().indexesAdmitted)
        hints.close()
    }

    @Test
    fun `a live primitive point keeps its polarity against semantic clause pressure`() {
        for (interruption in 0..2) {
            var available = true
            var stopped = false
            var calls = 0
            val stop = Cancellation { stopped }
            val usage = OpenHintUsageSink()
            val hints = assertNotNull(SourceConjunctionHints.create(
                listOf(Clause(intArrayOf(0, 2))), 2, intArrayOf(0, 1),
                SearchCandidateHints {
                    calls++
                    if (interruption == 1) available = false
                    if (interruption == 2) stopped = true
                    false
                }, stop, usage = usage, available = { available },
            ))

            assertEquals(if (interruption == 0) false else null, hints.preferredBool(0))
            assertEquals(1, calls)
            assertEquals(0L, usage.snapshot().clauseConsultations)
            hints.close()
        }
    }

    @Test
    fun `an invalidated opt in point withholds its forward fallback even without cancellation`() {
        for (capacity in listOf(0, SourceConjunctionHints.MAX_INDEX_ENTRIES)) {
            var available = true
            val primitive = SearchCandidateHints { available = false; true }
            val hints = assertNotNull(SourceConjunctionHints.create(
                emptyList(), 1, intArrayOf(0), primitive, Cancellation.Never,
                available = { available }, maxClauseEntries = capacity,
            ))

            assertNull(hints.preferredBool(0))
            hints.close()
        }
        val legacy = assertNotNull(SourceConjunctionHints.create(
            emptyList(), 1, intArrayOf(0), SearchCandidateHints { true }, Cancellation.Never,
        ))
        assertEquals(true, legacy.preferredBool(0))
        legacy.close()
    }

    @Test
    fun `semantic clauses can prefer the complement of a signed compound point value`() {
        for (negativeTarget in listOf(false, true)) {
            val target = Lit.make(0, !negativeTarget)
            val calls = IntArray(5)
            val usage = OpenHintUsageSink()
            val clauses = listOf(
                Clause(intArrayOf(Lit.negate(target), 3)),
                Clause(intArrayOf(Lit.negate(target), 4)),
                Clause(intArrayOf(target, 2, 5)),
                Clause(intArrayOf(Lit.negate(target), 6, 8)),
            )
            val hints = assertNotNull(SourceConjunctionHints.create(
                clauses, 5, intArrayOf(1, 2, 3, 4),
                SearchCandidateHints { variable -> calls[variable]++; variable == 2 },
                Cancellation.Never, usage = usage, available = { true },
            ))

            val preferred = hints.preferredBool(0)

            assertEquals(negativeTarget, preferred)
            assertContentEquals(intArrayOf(0, 1, 1, 1, 1), calls)
            assertEquals(1L, usage.snapshot().clauseSelections)
            hints.close()
            assertNull(hints.preferredBool(0))
        }
    }

    @Test
    fun `live pins and point lifetimes replace each consultation without stale clause values`() {
        var available = true
        var pin: Boolean? = true
        val hints = assertNotNull(SourceConjunctionHints.create(
            listOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 4))), 3, intArrayOf(1, 2),
            SearchCandidateHints { it == 1 }, Cancellation.Never,
            available = { available }, knownBool = { if (it == 2) pin else null },
        ))

        assertNull(hints.preferredBool(0))
        pin = false
        assertEquals(true, hints.preferredBool(0))
        available = false
        assertNull(hints.preferredBool(0))
        available = true
        pin = true
        assertNull(hints.preferredBool(0))
        hints.close()
    }

    @Test
    fun `unknown Boolean partners retain definite semantic satisfaction without guessing them`() {
        val hints = assertNotNull(SourceConjunctionHints.create(
            listOf(Clause(intArrayOf(1, 2, 4)), Clause(intArrayOf(0, 2, 6))),
            4, intArrayOf(2, 3), SearchCandidateHints { it == 2 }, Cancellation.Never,
            available = { true },
        ))

        assertEquals(true, hints.preferredBool(0))
        hints.close()
    }

    @Test
    fun `Boolean pins without usable point support supply no semantic preference`() {
        val usage = OpenHintUsageSink()
        val hints = assertNotNull(SourceConjunctionHints.create(
            listOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 4))),
            3, intArrayOf(1, 2), SearchCandidateHints.None, Cancellation.Never,
            usage = usage, available = { true }, knownBool = { if (it == 1) true else null },
        ))

        assertNull(hints.preferredBool(0))
        assertEquals(1L, usage.snapshot().clauseDeclines)
        hints.close()
    }

    @Test
    fun `optional semantic storage decline preserves the admitted forward gate`() {
        val usage = OpenHintUsageSink()
        val hints = assertNotNull(SourceConjunctionHints.create(
            listOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(0, 3, 5))),
            3, intArrayOf(1, 2), SearchCandidateHints.ofLiterals(intArrayOf(2, 4)), Cancellation.Never,
            usage = usage, available = { true }, maxClauseEntries = 0,
        ))

        assertEquals(true, hints.preferredBool(0))
        assertEquals(1L, usage.snapshot().indexesAdmitted)
        assertEquals(1L, usage.snapshot().clauseIndexesDeclined)
        assertEquals(0L, usage.snapshot().clauseConsultations)
        hints.close()
    }

    @Test
    fun `cyclic and ambiguous definitions cannot select their compound target`() {
        for (ambiguous in listOf(false, true)) {
            val clauses = if (ambiguous) listOf(
                Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(0, 3, 5)),
                Clause(intArrayOf(1, 6)), Clause(intArrayOf(0, 3, 7)), Clause(intArrayOf(1, 8)),
            ) else listOf(
                Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(0, 3, 5)),
                Clause(intArrayOf(3, 0)), Clause(intArrayOf(3, 6)), Clause(intArrayOf(2, 1, 7)),
                Clause(intArrayOf(1, 8)),
            )
            val hints = assertNotNull(SourceConjunctionHints.create(
                clauses, 5, if (ambiguous) intArrayOf(1, 2, 3, 4) else intArrayOf(2, 3, 4),
                SearchCandidateHints { it != 4 }, Cancellation.Never, available = { true },
            ))

            assertNull(hints.preferredBool(0))
            hints.close()
        }
    }

    @Test
    fun `a containing stop during point reads withholds semantic selection and retains its charge`() {
        var work = 0L
        var limit = Long.MAX_VALUE
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work >= limit
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        val usage = OpenHintUsageSink()
        val hints = assertNotNull(SourceConjunctionHints.create(
            listOf(Clause(intArrayOf(1, 2)), Clause(intArrayOf(0, 4))), 3, intArrayOf(1, 2),
            SearchCandidateHints { work++; limit = work; it == 1 }, stop,
            usage = usage, available = { true },
        ))
        val before = work

        assertNull(hints.preferredBool(0))
        assertTrue(work > before)
        assertEquals(0L, usage.snapshot().clauseSelections)
        val spent = work
        assertNull(hints.preferredBool(0))
        assertTrue(work >= spent)
        hints.close()
    }

    private val sharedClauses = arrayOf(
        Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
        Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
        Clause(intArrayOf(Lit.make(1, false), Lit.make(2, false))),
    )

    private fun differenceModel(): Problem {
        val open = Bits(2).also { bits -> repeat(2) { bits.set(it) } }
        return Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), open, open.copy()),
            factors = arrayOf(
                Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3),
                *sharedClauses,
            ),
        )
    }

    private fun hybridModel(): Problem {
        val openUpper = Bits(4).also { bits -> for (v in 2..3) bits.set(v) }
        return Problem(
            numBoolVars = 4,
            intBounds = IntBounds.fromModelBounds(LongArray(4), longArrayOf(3, 3, 0, 0), null, openUpper),
            factors = arrayOf(
                AllDifferent(vars = intArrayOf(0, 1), domainMin = 0, domainSize = 4),
                ReifiedLinear(
                    auxBoolVar = 3,
                    coeffs = intArrayOf(1, -1),
                    vars = intArrayOf(2, 3),
                    op = LinearOp.LE,
                    bound = 5,
                ),
                *sharedClauses,
            ),
        )
    }

    private fun satisfiesSharedClauses(model: Problem, candidate: OpenBooleanCandidate): Boolean {
        val bools = BooleanArray(model.numBoolVars)
        for (i in candidate.boolVars.indices) bools[candidate.boolVars[i]] = candidate.values[i]
        return model.factors.filterIsInstance<Clause>().all { clause ->
            clause.literals.any { Lit.evaluate(it, bools[Lit.variable(it)]) }
        }
    }

    @Test
    fun `a throwing index retains its abort while preserving the original failure`() {
        val usage = OpenHintUsageSink()
        val failure = IllegalStateException("index stop failed")
        var polls = 0
        val stop = Cancellation { polls++; throw failure }

        val thrown = kotlin.test.assertFailsWith<IllegalStateException> {
            SourceConjunctionHints.create(emptyList(), 1, intArrayOf(0), SearchCandidateHints.None, stop, usage = usage)
        }

        kotlin.test.assertSame(failure, thrown)
        assertEquals(1, polls)
        assertEquals(1L, usage.snapshot().abortedIndexes)
        assertEquals(0L, usage.snapshot().indexesAdmitted)
    }

    @Test
    fun `declined and stopped source indexes retain distinct outcomes without consulting primitives`() {
        val limited = OpenHintUsageSink()
        val stopped = OpenHintUsageSink()
        val invalid = OpenHintUsageSink()
        var calls = 0
        val primitive = SearchCandidateHints { calls++; true }

        assertNull(SourceConjunctionHints.create(listOf(Clause(intArrayOf(0))), 1, intArrayOf(0),
            primitive, Cancellation.Never, maxClauses = 0, usage = limited))
        assertNull(SourceConjunctionHints.create(emptyList(), 1, intArrayOf(0),
            primitive, Cancellation { true }, usage = stopped))
        assertNull(SourceConjunctionHints.create(emptyList(), 1, intArrayOf(2),
            primitive, Cancellation.Never, usage = invalid))

        assertEquals(0, calls)
        assertEquals(1L, limited.snapshot().sourceLimitDeclines)
        assertEquals(0L, limited.snapshot().stoppedIndexes)
        assertEquals(1L, stopped.snapshot().stoppedIndexes)
        assertEquals(1L, invalid.snapshot().invalidPrimitiveDeclines)
    }

    @Test
    fun `a hybrid plan proposes over the shared clauses and no other column`() {
        val model = hybridModel()
        val plan = model.componentPlan()

        val candidate = assertNotNull(plan.openBooleanDraw(model).candidate)

        assertTrue(plan.hasCpComponent && plan.hasTheoryComponent)
        assertContentEquals(intArrayOf(0, 1, 2), candidate.boolVars)
        assertTrue(satisfiesSharedClauses(model, candidate))
    }

    @Test
    fun `the skeleton keeps source boolean ids and drops every other column`() {
        val model = hybridModel()

        val skeleton = assertNotNull(model.componentPlan().booleanSkeleton(model))

        assertEquals(model.numBoolVars, skeleton.problem.numBoolVars)
        assertEquals(0, skeleton.problem.numIntVars)
        assertEquals(0, skeleton.problem.numRealVars)
        assertContentEquals(
            sharedClauses.map { it.literals.toList() },
            skeleton.problem.factors.map { (it as Clause).literals.toList() },
        )
    }

    @Test
    fun `a plan sharing no clause proposes nothing`() {
        val openUpper = Bits(2).also { bits -> repeat(2) { bits.set(it) } }
        val model = Problem(
            numBoolVars = 3,
            intBounds = IntBounds.fromModelBounds(LongArray(2), LongArray(2), null, openUpper),
            factors = arrayOf(Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.LE, 3)),
        )

        assertNull(model.componentPlan().openBooleanDraw(model).candidate)
    }

    @Test
    fun `a cancelled draw proposes nothing`() {
        val model = differenceModel()

        val draw = model.componentPlan()
            .openBooleanDraw(model, OpenCandidateParams(cancellation = Cancellation { true }))

        assertNull(draw.candidate)
    }
    @Test
    fun `nested nary signed gates preserve every leaf truth combination`() {
        for (positive in listOf(false, true)) for (bits in 0 until 32) {
            val inner = Lit.make(1, false)
            val target = Lit.make(0, positive)
            val definitions = listOf(
                inner to intArrayOf(Lit.make(2, true), Lit.make(3, false), Lit.make(4, true)),
                target to intArrayOf(inner, Lit.make(5, false), Lit.make(6, true)),
            )
            val clauses = ArrayList<Clause>()
            for ((gate, operands) in definitions) {
                for (operand in operands) clauses += Clause(intArrayOf(Lit.negate(gate), operand))
                clauses += Clause(intArrayOf(gate, *operands.map { Lit.negate(it) }.toIntArray()))
            }
            val hints = assertNotNull(SourceConjunctionHints.create(
                clauses, 7, intArrayOf(2, 3, 4, 5, 6), SearchCandidateHints { variable ->
                    if (variable in 2..6) bits and (1 shl (variable - 2)) != 0 else null
                }, Cancellation.Never,
            ))

            val innerTruth = (bits and 1 != 0) && (bits and 2 == 0) && (bits and 4 != 0)
            val outerTruth = innerTruth && (bits and 8 == 0) && (bits and 16 != 0)
            assertEquals(!innerTruth, hints.preferredBool(1))
            assertEquals(outerTruth == positive, hints.preferredBool(0))
        }
    }

    @Test
    fun `nary gate preferences require the reverse clause and every implication pair`() {
        for (missing in 0..3) {
            val clauses = listOf(
                Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(1, 6)),
                Clause(intArrayOf(0, 3, 5, 7)),
            )
            val hints = assertNotNull(SourceConjunctionHints.create(
                clauses.filterIndexed { index, _ -> index != missing }, 4, intArrayOf(1, 2, 3),
                SearchCandidateHints { variable -> if (variable in 1..3) true else null }, Cancellation.Never,
            ))

            assertNull(hints.preferredBool(0))
        }
    }

    @Test
    fun `repeated identical literals preserve the canonical full gate preference`() {
        val clauses = listOf(
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)),
            Clause(intArrayOf(0, 3, 3, 5)),
        )
        val hints = assertNotNull(SourceConjunctionHints.create(
            clauses, 3, intArrayOf(1, 2), SearchCandidateHints { variable ->
                if (variable in 1..2) true else null
            }, Cancellation.Never,
        ))

        assertEquals(true, hints.preferredBool(0))
    }

    @Test
    fun `opposite polarity duplicate ids leave a tautological gate reverse clause declined`() {
        val clauses = listOf(
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 3)), Clause(intArrayOf(1, 4)),
            Clause(intArrayOf(0, 3, 2, 5)),
        )
        val hints = assertNotNull(SourceConjunctionHints.create(
            clauses, 3, intArrayOf(1, 2), SearchCandidateHints { variable ->
                if (variable in 1..2) true else null
            }, Cancellation.Never,
        ))

        assertNull(hints.preferredBool(0))
    }

    @Test
    fun `nested signed gates preserve every leaf truth combination`() {
        for (positive in listOf(false, true)) for (bits in 0 until 8) {
            val inner = Lit.make(1, false)
            val target = Lit.make(0, positive)
            val a = Lit.make(2, true)
            val b = Lit.make(3, false)
            val c = Lit.make(4, true)
            val clauses = listOf(
                Clause(intArrayOf(Lit.negate(inner), a)),
                Clause(intArrayOf(Lit.negate(inner), b)),
                Clause(intArrayOf(inner, Lit.negate(a), Lit.negate(b))),
                Clause(intArrayOf(Lit.negate(target), inner)),
                Clause(intArrayOf(Lit.negate(target), c)),
                Clause(intArrayOf(target, Lit.negate(inner), Lit.negate(c))),
            )
            val primitive = SearchCandidateHints { variable ->
                if (variable in 2..4) bits and (1 shl (variable - 2)) != 0 else null
            }
            val hints = assertNotNull(SourceConjunctionHints.create(
                clauses, 5, intArrayOf(2, 3, 4), primitive, Cancellation.Never,
            ))

            val innerTruth = (bits and 1 != 0) && (bits and 2 == 0)
            assertEquals(!innerTruth, hints.preferredBool(1))
            assertEquals((innerTruth && (bits and 4 != 0)) == positive, hints.preferredBool(0))
        }
    }

    @Test
    fun `primitive leaves retain authority over a matching structural definition`() {
        val clauses = listOf(
            Clause(intArrayOf(1, 4)), Clause(intArrayOf(1, 6)), Clause(intArrayOf(0, 5, 7)),
            Clause(intArrayOf(3, 0)), Clause(intArrayOf(3, 8)), Clause(intArrayOf(2, 1, 9)),
        )
        val hints = assertNotNull(SourceConjunctionHints.create(
            clauses, 5, intArrayOf(0, 2, 3, 4), SearchCandidateHints { variable ->
                when (variable) { 0 -> false; in 2..4 -> true; else -> null }
            }, Cancellation.Never,
        ))

        assertEquals(false, hints.preferredBool(0))
        assertEquals(false, hints.preferredBool(1))
    }

    @Test
    fun `nested gates decline missing definitions or primitive preferences`() {
        for (missingDefinition in listOf(false, true)) {
            val clauses = listOf(
                Clause(intArrayOf(3, 4)), Clause(intArrayOf(3, 6)), Clause(intArrayOf(2, 5, 7)),
                Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 8)), Clause(intArrayOf(0, 3, 9)),
            )
            val primitive = SearchCandidateHints { variable ->
                if (variable == 2 || variable == 4 || (missingDefinition && variable == 3)) true else null
            }
            val hints = assertNotNull(SourceConjunctionHints.create(
                if (missingDefinition) clauses.drop(1) else clauses,
                5, intArrayOf(2, 3, 4), primitive, Cancellation.Never,
            ))

            assertNull(hints.preferredBool(0))
        }
    }

    @Test
    fun `a nested dependency on an ambiguous gate supplies no preference`() {
        val clauses = listOf(
            Clause(intArrayOf(3, 4)), Clause(intArrayOf(3, 6)), Clause(intArrayOf(2, 5, 7)),
            Clause(intArrayOf(3, 5)), Clause(intArrayOf(3, 6)), Clause(intArrayOf(2, 4, 7)),
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 8)), Clause(intArrayOf(0, 3, 9)),
        )
        val hints = assertNotNull(SourceConjunctionHints.create(
            clauses, 5, intArrayOf(2, 3, 4), SearchCandidateHints { variable ->
                if (variable in 2..4) true else null
            }, Cancellation.Never,
        ))

        assertNull(hints.preferredBool(0))
    }

    @Test
    fun `cyclic signed gate closures supply no preference`() {
        val clauses = listOf(
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 4)), Clause(intArrayOf(0, 3, 5)),
            Clause(intArrayOf(3, 0)), Clause(intArrayOf(3, 6)), Clause(intArrayOf(2, 1, 7)),
        )
        val hints = assertNotNull(SourceConjunctionHints.create(
            clauses, 4, intArrayOf(2, 3), SearchCandidateHints { variable ->
                if (variable in 2..3) true else null
            }, Cancellation.Never,
        ))

        assertNull(hints.preferredBool(0))
        assertNull(hints.preferredBool(1))
    }

    @Test
    fun `nested evaluation charges the opening meter and withholds a cancelled proposal`() {
        var work = 0L
        var limit = Long.MAX_VALUE
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = work >= limit
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }
        val clauses = listOf(
            Clause(intArrayOf(3, 4)), Clause(intArrayOf(3, 6)), Clause(intArrayOf(2, 5, 7)),
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 8)), Clause(intArrayOf(0, 3, 9)),
        )
        val hints = assertNotNull(SourceConjunctionHints.create(
            clauses, 5, intArrayOf(2, 3, 4), SearchCandidateHints { variable ->
                if (variable in 2..4) true else null
            }, stop,
        ))
        val preparation = work
        limit = preparation + 4L

        assertNull(hints.preferredBool(0))
        assertTrue(work >= limit)
        limit = Long.MAX_VALUE
        assertEquals(true, hints.preferredBool(0))
        assertTrue(work > preparation + 4L)
    }

    @Test
    fun `nested consultations read live leaves and closing releases the index`() {
        var value: Boolean? = true
        val clauses = listOf(
            Clause(intArrayOf(3, 4)), Clause(intArrayOf(3, 6)), Clause(intArrayOf(2, 5, 7)),
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 8)), Clause(intArrayOf(0, 3, 9)),
        )
        val hints = assertNotNull(SourceConjunctionHints.create(
            clauses, 5, intArrayOf(2, 3, 4), SearchCandidateHints { variable ->
                if (variable in 2..4) value else null
            }, Cancellation.Never,
        ))

        assertEquals(true, hints.preferredBool(0))
        value = false
        assertEquals(false, hints.preferredBool(0))
        value = null
        assertNull(hints.preferredBool(0))
        value = true
        hints.close()
        assertNull(hints.preferredBool(0))
        assertNull(hints.preferredBool(2))
    }

}
