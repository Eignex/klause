package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.theory.BuiltInEncoderProvider
import com.eignex.klause.theory.EncodedTheoryFactor
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.EncoderProvider
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.linearTheoryModel
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SelectedEncoderRequestTest {
    @Test
    fun `an ineligible selected scope should not install any supported prefix`() {
        for (support in listOf(EncoderSupport.UNSUPPORTED, EncoderSupport.RELAXATION_ONLY)) {
            for (reverse in listOf(false, true)) {
                val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
                val excluded = Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)
                val factors = if (reverse) arrayOf<Factor>(excluded, row) else arrayOf<Factor>(row, excluded)
                val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), factors)
                var installed = 0
                var decline: EncoderDecline? = null
                val selected = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
                    if (factor === excluded) Encoder(support) else {
                        val builtin = BuiltInEncoderProvider.select(factor, target)
                        Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                            installed++
                            builtin.install(view, encoding)
                        }
                    }
                }).prepare(source)

                val model = selected.linearTheoryModel(EncodingTarget.LINEAR, onDecline = { decline = it })

                assertNull(model)
                assertEquals(EncoderDecline.UNSUPPORTED, decline)
                assertEquals(0, installed)
            }
        }
    }

    @Test
    fun `an eligible selected subset should retain its original source attribution`() {
        val excluded = Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(excluded, row))
        val selected = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            if (factor === excluded) Encoder(EncoderSupport.UNSUPPORTED)
            else BuiltInEncoderProvider.select(factor, target)
        }).prepare(source)

        val model = assertNotNull(selected.linearTheoryModel(EncodingTarget.LINEAR, listOf(1)))
        val relation = assertIs<EncodedTheoryFactor>(model.factors.single())

        assertSame(row, relation.source)
        assertEquals(1, relation.attribution.sourceFactorIndex)
    }

    @Test
    fun `missing captured integer or Boolean conditions should reject the complete selected scope`() {
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
        val conditioned = Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)
        val source = Problem(1, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(row, conditioned))
        var installed = 0
        val selected = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val builtin = BuiltInEncoderProvider.select(factor, target)
            if (factor === conditioned) {
                Encoder(EncoderSupport.CONDITIONED, listOf(SourceIntId(0)), mapOf(SourceBoolId(0) to true)) {
                        view, encoding ->
                    installed++
                    builtin.install(view, encoding)
                }
            } else Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                installed++
                builtin.install(view, encoding)
            }
        }).prepare(source)
        for ((integers, booleans) in listOf(
            emptyMap<SourceIntId, BigInt>() to emptyMap(),
            mapOf(SourceIntId(0) to bigIntOf(1)) to emptyMap(),
            emptyMap<SourceIntId, BigInt>() to mapOf(SourceBoolId(0) to true),
            mapOf(SourceIntId(0) to bigIntOf(1)) to mapOf(SourceBoolId(0) to false),
            mapOf(SourceIntId(0) to bigIntOf(1)) to mapOf(SourceBoolId(1) to true),
        )) {
            var decline: EncoderDecline? = null

            val model = selected.linearTheoryModel(EncodingTarget.LINEAR, fixedIntegers = integers,
                fixedBooleans = booleans, onDecline = { decline = it }, completeFiniteConditions = true)

            assertNull(model)
            assertEquals(EncoderDecline.CONDITIONS, decline)
        }
        assertEquals(0, installed)
    }

    @Test
    fun `installation should retain captured pins when the request inputs change`() {
        val prefix = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1)
        val source = Problem(1, 1, arrayOf(IntDomain(0, 2)), arrayOf<Factor>(prefix, row))
        val selected = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val builtin = BuiltInEncoderProvider.select(factor, target)
            Encoder(EncoderSupport.CONDITIONED, listOf(SourceIntId(0)), mapOf(SourceBoolId(0) to true)) {
                    view, encoding ->
                builtin.install(view, encoding)
            }
        }).prepare(source)
        val indices = mutableListOf(1)
        val integers = mutableMapOf(SourceIntId(0) to bigIntOf(1))
        val booleans = mutableMapOf(SourceBoolId(0) to true)
        val request = assertNotNull(selected.requestEncoders(EncodingTarget.LINEAR, indices, integers, booleans))
        indices.clear()
        integers[SourceIntId(0)] = bigIntOf(2)
        booleans[SourceBoolId(0)] = false

        val model = assertNotNull(selected.linearTheoryModel(request))
        val relation = assertIs<EncodedTheoryFactor>(model.factors.single())

        assertSame(row, relation.source)
        assertEquals(1, relation.attribution.sourceFactorIndex)
        assertEquals(mapOf(SourceIntId(0) to bigIntOf(1)), relation.attribution.integerConditions)
        assertEquals(mapOf(SourceBoolId(0) to true), relation.attribution.booleanConditions)
    }

    @Test
    fun `a request should reject a different source capture before installation`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)))
        val first = FactorProjectionProviders.Default.prepare(source)
        val second = FactorProjectionProviders.Default.prepare(source.withFactors(source.factors.copyOf()))
        val request = assertNotNull(first.requestEncoders(EncodingTarget.LINEAR, listOf(0)))

        assertFailsWith<IllegalArgumentException> { second.linearTheoryModel(request) }
    }

    @Test
    fun `invalid selected factor scopes should fail before installation`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)))
        var installed = 0
        val selected = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val builtin = BuiltInEncoderProvider.select(factor, target)
            Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                installed++
                builtin.install(view, encoding)
            }
        }).prepare(source)

        for (indices in listOf(listOf(0, 1), listOf(0, 0))) {
            assertFailsWith<IllegalArgumentException> { selected.linearTheoryModel(EncodingTarget.LINEAR, indices) }
        }

        assertEquals(0, installed)
    }

    @Test
    fun `an admitted request should retain an observed stop through later installation`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)))
        var stopped = false
        var installed = 0
        val selected = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val builtin = BuiltInEncoderProvider.select(factor, target)
            Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                installed++
                builtin.install(view, encoding)
            }
        }).prepare(source)
        val request = assertNotNull(selected.requestEncoders(EncodingTarget.LINEAR, listOf(0),
            cancellation = Cancellation { stopped }))
        var decline: EncoderDecline? = null
        stopped = true

        val cancelled = selected.linearTheoryModel(request) { decline = it }
        stopped = false
        val retry = selected.linearTheoryModel(request)

        assertNull(cancelled)
        assertNull(retry)
        assertEquals(EncoderDecline.RESOURCE, decline)
        assertEquals(0, installed)
    }

    @Test
    fun `captured integer pins should respect source bounds and holes`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2).excludeValue(1)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2)))
        val selected = FactorProjectionProviders.Default.prepare(source)
        for (value in listOf(-1L, 1L, 3L)) {
            var decline: EncoderDecline? = null

            val request = selected.requestEncoders(EncodingTarget.LINEAR, listOf(0),
                fixedIntegers = mapOf(SourceIntId(0) to bigIntOf(value)), onDecline = { decline = it })

            assertNull(request)
            assertEquals(EncoderDecline.CONDITIONS, decline)
        }
    }

    @Test
    fun `an open source condition should not be limited by its finite fallback box`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 1)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0)), openIntHi = booleanArrayOf(true))
        val selected = FactorProjectionProviders.Default.prepare(source)
        val request = assertNotNull(selected.requestEncoders(EncodingTarget.LINEAR, listOf(0),
            fixedIntegers = mapOf(SourceIntId(0) to bigIntOf(7))))

        val model = assertNotNull(selected.linearTheoryModel(request))
        val relation = assertIs<EncodedTheoryFactor>(model.factors.single())

        assertEquals(mapOf(SourceIntId(0) to bigIntOf(7)), relation.attribution.integerConditions)
    }

    @Test
    fun `a cancelled preflight should spend its work without installing a prefix`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 2)),
            Array<Factor>(3) { Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2) })
        var installed = 0
        var charged = 0L
        var decline: EncoderDecline? = null
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 2
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }
        val selected = FactorProjectionProviders(encoder = EncoderProvider { factor, target ->
            val builtin = BuiltInEncoderProvider.select(factor, target)
            Encoder(EncoderSupport.EQUIVALENT) { view, encoding ->
                installed++
                builtin.install(view, encoding)
            }
        }).prepare(source)

        val cancelled = selected.linearTheoryModel(EncodingTarget.LINEAR, cancellation = stop,
            onDecline = { decline = it })

        assertNull(cancelled)
        assertEquals(EncoderDecline.RESOURCE, decline)
        assertEquals(0, installed)
        assertTrue(charged >= 2)
        assertNotNull(selected.linearTheoryModel(EncodingTarget.LINEAR))
        assertEquals(3, installed)
    }
}
