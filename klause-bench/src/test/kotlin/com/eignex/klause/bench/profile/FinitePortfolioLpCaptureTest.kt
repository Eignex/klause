package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.lp.bounding.LpEmphasis
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FinitePortfolioLpCaptureTest {
    @Test
    fun `admission depth rejects the wrong runtime numeric type`() {
        val original = FinitePortfolioLpCapture.RootAdmissionRecord(
            schema = 1,
            disposition = "PROJECTION_SKIPPED",
            emphasis = "DEFAULT",
            sparseOverride = null,
            sparsePermitted = true,
            automaticEligible = true,
            automaticEvaluated = 32767L,
            automaticPassed = 32767L,
            outerEvaluated = 63L,
            outerPassed = 63L,
            estimatedRows = 3L,
            estimatedColumns = 3L,
            estimatedCells = 21L,
            baseCellCap = 1L,
            ceilingCellCap = 1000L,
            boundingPermitted = true,
            withinCeiling = true,
            structurallyApplicable = true,
            projectionAllowed = false,
            producerQueryWork = 1000L,
            producerPreparationWork = 1000L,
            helperQueryWork = null,
            helperPreparationWork = null,
            resolvedBounding = true,
            resolvedProbe = true,
            resolvedBranching = true,
            resolvedComponentOwnership = false,
            resolvedLbTreeSearch = false,
            resolvedRootMaxWork = 1000L,
            resolvedBoundMaxDepth = 0,
            resolvedRootBudgetMillis = 1000L,
            resolvedRootBudgetFraction = 0.5,
            resolvedLpWorkBudgetFraction = 0.25,
            metrics = mapOf("DeclineProjection" to 1L, "Automatic" to 1L),
        )

        assertFailsWith<ClassCastException> {
            FinitePortfolioLpCapture.RootAdmissionRecord.read(original, original.disposition, false) { value, getter ->
                if (getter == "getResolvedBoundMaxDepth") Int.MAX_VALUE.toLong() + 1L
                else value.javaClass.getMethod(getter).invoke(value)
            }
        }
    }

    @Test
    fun `dropped admission lifecycle remains incomplete`() {
        val owner = Any()
        val window = FinitePortfolioLpCapture.RootPointWindow("any",
            FinitePortfolioLpCapture.RootPointLimits(ownerEvents = 1))
        val admission = FinitePortfolioLpCapture.RootAdmissionRecord(
            schema = 1,
            disposition = "NOT_CONFIGURED",
            emphasis = null,
            sparseOverride = null,
            sparsePermitted = null,
            automaticEligible = null,
            automaticEvaluated = 0L,
            automaticPassed = 0L,
            outerEvaluated = 0L,
            outerPassed = 0L,
            estimatedRows = null,
            estimatedColumns = null,
            estimatedCells = null,
            baseCellCap = null,
            ceilingCellCap = null,
            boundingPermitted = null,
            withinCeiling = null,
            structurallyApplicable = null,
            projectionAllowed = null,
            producerQueryWork = null,
            producerPreparationWork = null,
            helperQueryWork = null,
            helperPreparationWork = null,
            resolvedBounding = null,
            resolvedProbe = null,
            resolvedBranching = null,
            resolvedComponentOwnership = null,
            resolvedLbTreeSearch = null,
            resolvedRootMaxWork = null,
            resolvedBoundMaxDepth = null,
            resolvedRootBudgetMillis = null,
            resolvedRootBudgetFraction = null,
            resolvedLpWorkBudgetFraction = null,
            metrics = emptyMap(),
        )

        window.observe("bt/lp-default", RootEvent(owner, 1L, stage = "ADMISSION", disposition = "NOT_CONFIGURED",
            automatic = false, admission = admission), 1L)
        window.observe("bt/lp-default", RootEvent(owner, 2L, stage = "CLOSE_BEGIN", disposition = "BEGIN"), 2L)
        window.observe("bt/lp-default", RootEvent(owner, 3L, stage = "CLOSE_END", disposition = "END"), 3L)
        val snapshot = window.snapshot()

        assertEquals(2L, snapshot.dropped)
        assertEquals("UNKNOWN", snapshot.owners.single().coverage)
        assertEquals(false, snapshot.absenceEvidence)
        assertEquals(1, snapshot.events.size)
        assertEquals("NOT_CONFIGURED", snapshot.events.single().admission?.disposition)
    }

    @Test
    fun `encoded root records enforce their byte limit`() {
        val window = FinitePortfolioLpCapture.RootPointWindow("any")
        window.observe("x".repeat(128), RootEvent(Any(), 1L), 1L)
        val record = window.snapshot().events.single()

        assertFailsWith<IllegalArgumentException> { record.copy(worker = "x".repeat(8192)).json() }
    }

    @ParameterizedTest
    @CsvSource("true,default", "true,aggressive", "true,'default,+sparse-trial'", "false,default")
    fun `root ceiling accepts only an explicit default cap`(root: Boolean, value: String) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "false", "portfolio-policy" to "ordinary",
            "portfolio-progress" to "false", "captures" to "0", "capture-root-points" to "true",
            "lp-ceiling" to value, "capture-root-points" to root.toString())

        if (root && value == "default") {
            assertEquals(listOf("portfolio-progress=false"), FinitePortfolioLpCapture.parameters(options))
            assertEquals(LpEmphasis.DEFAULT, FinitePortfolioLpCapture.sourcePolicy(options).lpCeiling?.emphasis)
        } else assertFailsWith<IllegalArgumentException> { FinitePortfolioLpCapture.parameters(options) }
    }

    @Test
    fun `absent root ceiling preserves the uncapped policy`() {
        assertNull(FinitePortfolioLpCapture.sourcePolicy(emptyMap()).lpCeiling)
    }

    @Test
    fun `admission metadata preserves query pairing and closed recorded scope`() {
        val window = FinitePortfolioLpCapture.RootPointWindow("any")
        val owner = Any()
        val model = Any()
        val admission = FinitePortfolioLpCapture.RootAdmissionRecord(
            schema = 1,
            disposition = "NOT_CONFIGURED",
            emphasis = null,
            sparseOverride = null,
            sparsePermitted = null,
            automaticEligible = null,
            automaticEvaluated = 0L,
            automaticPassed = 0L,
            outerEvaluated = 0L,
            outerPassed = 0L,
            estimatedRows = null,
            estimatedColumns = null,
            estimatedCells = null,
            baseCellCap = null,
            ceilingCellCap = null,
            boundingPermitted = null,
            withinCeiling = null,
            structurallyApplicable = null,
            projectionAllowed = null,
            producerQueryWork = null,
            producerPreparationWork = null,
            helperQueryWork = null,
            helperPreparationWork = null,
            resolvedBounding = null,
            resolvedProbe = null,
            resolvedBranching = null,
            resolvedComponentOwnership = null,
            resolvedLbTreeSearch = null,
            resolvedRootMaxWork = null,
            resolvedBoundMaxDepth = null,
            resolvedRootBudgetMillis = null,
            resolvedRootBudgetFraction = null,
            resolvedLpWorkBudgetFraction = null,
            metrics = emptyMap(),
        )

        window.observe("bt/lp-default", RootEvent(owner, 1L, stage = "ADMISSION", disposition = "NOT_CONFIGURED",
            automatic = false, admission = admission), 1L)
        window.observe("bt/lp-default", RootEvent(owner, 2L, stage = "QUERY_CALL", disposition = "ENTERED",
            query = 1L, model = model), 2L)
        window.observe("bt/lp-default", RootEvent(owner, 3L, stage = "QUERY_CALL", disposition = "OPTIMAL",
            query = 1L, model = model, point = Any()), 3L)
        window.observe("bt/lp-default", RootEvent(owner, 4L, stage = "CLOSE_BEGIN", disposition = "BEGIN",
            query = 1L), 4L)
        window.observe("bt/lp-default", RootEvent(owner, 5L, stage = "CLOSE_END", disposition = "END",
            query = 1L), 5L)
        val snapshot = window.snapshot()
        val json = snapshot.json()

        assertEquals("CLOSED_JOURNALED_SCOPE", snapshot.owners.single().coverage)
        assertEquals("UNKNOWN", json.getValue("missingAdmissionMeaning").jsonPrimitive.content)
        assertEquals("NOT_CONFIGURED", json.getValue("events").jsonArray.first().jsonObject
            .getValue("admission").jsonObject.getValue("disposition").jsonPrimitive.content)
        assertTrue(json.toString().encodeToByteArray().size <= FinitePortfolioLpCapture.ROOT_POINT_JOURNAL_BYTE_LIMIT)
        assertEquals(false, snapshot.absenceEvidence)
        assertEquals(true, snapshot.partialQueryCoverage)
    }

    @ParameterizedTest
    @ValueSource(strings = ["valid-detached", "valid-helper", "valid-signed-policy", "valid-reuse", "schema",
        "metric", "mask", "disposition", "funding", "helper-preparation-over", "helper-query-over",
        "metric-allowance", "unfunded-reuse", "valid-reuse-decline", "unfunded-source-reuse", "fraction"])
    fun `admission payload validation preserves detached scalars and visible failures`(variant: String) {
        val metrics = linkedMapOf("DeclineProjection" to 1L, "Automatic" to 1L)
        val original = FinitePortfolioLpCapture.RootAdmissionRecord(
            schema = 1,
            disposition = "PROJECTION_SKIPPED",
            emphasis = "DEFAULT",
            sparseOverride = null,
            sparsePermitted = true,
            automaticEligible = true,
            automaticEvaluated = 32767L,
            automaticPassed = 32767L,
            outerEvaluated = 63L,
            outerPassed = 63L,
            estimatedRows = 3L,
            estimatedColumns = 3L,
            estimatedCells = 21L,
            baseCellCap = 1L,
            ceilingCellCap = 1000L,
            boundingPermitted = true,
            withinCeiling = true,
            structurallyApplicable = true,
            projectionAllowed = false,
            producerQueryWork = 1000L,
            producerPreparationWork = 1000L,
            helperQueryWork = null,
            helperPreparationWork = null,
            resolvedBounding = true,
            resolvedProbe = true,
            resolvedBranching = true,
            resolvedComponentOwnership = false,
            resolvedLbTreeSearch = false,
            resolvedRootMaxWork = 1000L,
            resolvedBoundMaxDepth = 0,
            resolvedRootBudgetMillis = 1000L,
            resolvedRootBudgetFraction = 0.5,
            resolvedLpWorkBudgetFraction = 0.25,
            metrics = metrics,
        )
        val helper = original.copy(disposition = "ADMITTED", projectionAllowed = true,
            helperPreparationWork = 1000L, helperQueryWork = 1000L,
            metrics = mapOf("Automatic" to 1L, "Admitted" to 1L, "QueryWorkAllowance" to 1000L))
        val reused = helper.copy(metrics = helper.metrics + mapOf("PreparationWork" to 1L,
            "ReusedPreparationWork" to 900L, "EstimatedProbeTotalWork" to 975L))
        val reuseDecline = reused.copy(disposition = "ATTEMPTED_DECLINED", helperQueryWork = 950L,
            metrics = reused.metrics + mapOf("Admitted" to 0L, "DeclineQueryWork" to 1L,
                "SourceEstimatedPassTotalWork" to 925L, "QueryWorkAllowance" to 950L))
        val payload = when (variant) {
            "valid-helper" -> helper
            "valid-reuse" -> reused
            "valid-reuse-decline" -> reuseDecline
            "unfunded-reuse" -> reused.copy(helperPreparationWork = 899L)
            "unfunded-source-reuse" -> reuseDecline.copy(
                metrics = reuseDecline.metrics + ("SourceEstimatedPassTotalWork" to 951L))
            "valid-signed-policy" -> original.copy(baseCellCap = -1L, ceilingCellCap = -1L,
                withinCeiling = false, resolvedBounding = false, resolvedProbe = false,
                resolvedBoundMaxDepth = -1, resolvedRootMaxWork = -1L, resolvedRootBudgetMillis = -1L,
                resolvedRootBudgetFraction = -0.5, resolvedLpWorkBudgetFraction = 2.0)
            "helper-preparation-over" -> helper.copy(helperPreparationWork = 1001L)
            "helper-query-over" -> helper.copy(helperQueryWork = 1001L)
            "metric-allowance" -> helper.copy(metrics = helper.metrics + ("QueryWorkAllowance" to 7L))
            "schema" -> original.copy(schema = 2)
            "metric" -> original.copy(metrics = metrics + ("UnknownMetric" to 1L))
            "mask" -> original.copy(automaticEvaluated = 15L)
            "disposition" -> original.copy(disposition = "ADMITTED")
            "funding" -> original.copy(helperQueryWork = 1L)
            "fraction" -> original.copy(resolvedRootBudgetFraction = Double.NaN)
            else -> original
        }
        val window = FinitePortfolioLpCapture.RootPointWindow("any")

        window.observe("bt/lp-default", RootEvent(Any(), 1L, stage = "ADMISSION", disposition = payload.disposition,
            automatic = payload.disposition == "ADMITTED", admission = payload), 1L)
        metrics["Automatic"] = 0L
        val snapshot = window.snapshot()

        if (variant.startsWith("valid-")) {
            assertEquals(0L, snapshot.failures)
            if (variant == "valid-signed-policy") {
                val json = snapshot.events.single().json().getValue("admission").jsonObject
                for (name in listOf("baseCellCap", "ceilingCellCap", "resolvedBoundMaxDepth",
                    "resolvedRootMaxWork", "resolvedRootBudgetMillis")) {
                    assertEquals(-1L, json.getValue(name).jsonPrimitive.long)
                }
                assertEquals("-0.5", json.getValue("resolvedRootBudgetFraction").jsonPrimitive.content)
                assertEquals("2.0", json.getValue("resolvedLpWorkBudgetFraction").jsonPrimitive.content)
            }
            assertEquals(1L, snapshot.events.single().admission?.metrics?.get("Automatic"))
            assertEquals(1L, snapshot.events.single().json().getValue("admission").jsonObject
                .getValue("metrics").jsonObject.getValue("Automatic").jsonPrimitive.long)
            if (variant == "valid-reuse" || variant == "valid-reuse-decline") {
                val retained = requireNotNull(snapshot.events.single().admission).metrics
                assertEquals(1L, retained["PreparationWork"])
                assertEquals(900L, retained["ReusedPreparationWork"])
            }
        } else {
            assertEquals(1L, snapshot.failures)
            assertEquals(1L, snapshot.admissionFailures)
            val unsupported = if (variant in setOf("schema", "metric", "fraction")) 1L else 0L
            assertEquals(unsupported, snapshot.admissionUnsupported)
            assertEquals("UNKNOWN", snapshot.owners.single().coverage)
            assertTrue(snapshot.events.isEmpty())
        }
    }

    @ParameterizedTest
    @CsvSource("false,1048576,true,reader", "false,1048576,true,bytes",
        "false,1048577,false,reader", "false,1048577,false,bytes",
        "true,2097152,true,reader", "true,2097152,true,bytes",
        "true,2097153,false,reader", "true,2097153,false,bytes",
        "true,1055074,true,reader", "true,1055074,true,bytes")
    fun `source input paths enforce the selected byte bound`(root: Boolean, size: Int,
        accepted: Boolean, path: String) {
        val bytes = ByteArray(size)
        val options = mapOf("capture-root-points" to root.toString(), "sha256" to sha256(bytes))

        fun read(): ByteArray = if (path == "reader") {
            bytes.inputStream().use { FinitePortfolioLpCapture.readSource(options, it) }
        } else {
            FinitePortfolioLpCapture.validateSource(options, bytes)
            bytes
        }

        if (accepted) assertContentEquals(bytes, read())
        else assertFailsWith<IllegalArgumentException> { read() }
    }

    @ParameterizedTest
    @CsvSource("false,reader", "false,bytes", "true,reader", "true,bytes")
    fun `source input paths reject a different content hash`(root: Boolean, path: String) {
        val bytes = "NAME fixture\nENDATA\n".encodeToByteArray()
        val options = mapOf("capture-root-points" to root.toString(), "sha256" to "0".repeat(64))

        assertFailsWith<IllegalArgumentException> {
            if (path == "reader") bytes.inputStream().use { FinitePortfolioLpCapture.readSource(options, it) }
            else FinitePortfolioLpCapture.validateSource(options, bytes)
        }
    }

    @ParameterizedTest
    @CsvSource("false,1048576", "true,2097152")
    fun `source reader stops after the overflow sentinel`(root: Boolean, limit: Int) {
        val bytes = ByteArray(limit + 32)
        val options = mapOf("capture-root-points" to root.toString(), "sha256" to sha256(bytes))
        val input = bytes.inputStream()

        assertFailsWith<IllegalArgumentException> { FinitePortfolioLpCapture.readSource(options, input) }

        assertEquals(31, input.available())
    }

    @ParameterizedTest
    @CsvSource("portfolio-policy,retained", "handoff,true", "portfolio-progress,true", "captures,1")
    fun `root point capture requires ordinary fixed policy`(key: String, value: String) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "false", "portfolio-policy" to "ordinary",
            "portfolio-progress" to "false", "captures" to "0", "capture-root-points" to "true")

        assertFailsWith<IllegalArgumentException> {
            FinitePortfolioLpCapture.parameters(options + (key to value))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `root capture selection preserves ordinary algorithm parameters`(root: Boolean) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "false", "portfolio-policy" to "ordinary",
            "portfolio-progress" to "false", "captures" to "0", "capture-guidance" to "true",
            "capture-root-points" to root.toString())

        val parameters = FinitePortfolioLpCapture.parameters(options)

        assertEquals(listOf("portfolio-progress=false"), parameters)
    }

    @Test
    fun `root point identities use reference identity and preserve actual objective sharing`() {
        val window = FinitePortfolioLpCapture.RootPointWindow("any")
        val first = String(charArrayOf('x'))
        val second = String(charArrayOf('x'))
        val objective = Any()
        window.observe("bt/lp-default", RootEvent(first, 1L, objective = objective), 1L)
        window.observe("bt/lp-aggressive", RootEvent(second, 1L, objective = objective), 2L)

        val events = window.snapshot().events

        assertEquals(0, events[0].owner)
        assertEquals(1, events[1].owner)
        assertEquals(events[0].objective, events[1].objective)
    }

    @ParameterizedTest
    @ValueSource(strings = ["complete", "valid-query", "guard-only", "open-query", "missing-end",
        "sequence-gap", "reflection-failure", "mismatched-query", "mismatched-model", "query-gap",
        "return-without-entry", "duplicate-entry", "duplicate-close-begin", "duplicate-close-end",
        "event-after-close-begin", "event-after-close-end", "end-without-begin", "invalid-query-kind",
        "source-change", "objective-change"])
    fun `root journals reject broken query pairing and close order`(shape: String) {
        val window = FinitePortfolioLpCapture.RootPointWindow("any")
        val event = RootEvent(Any(), 1L)
        val model = Any()
        var sequence = 2L
        var query = 0L
        window.observe("bt/lp-default", event, 1L)
        fun send(stage: String, disposition: String, call: Long = query, observedModel: Any? = null) {
            window.observe("bt/lp-default", event.copy(sequence = sequence++, stage = stage,
                disposition = disposition, query = call, model = observedModel), sequence)
        }
        if (shape in setOf("valid-query", "open-query", "mismatched-query", "mismatched-model",
                "query-gap", "duplicate-entry")) {
            query = if (shape == "query-gap") 2L else 1L
            send("QUERY_CALL", "ENTERED", observedModel = model)
            if (shape == "duplicate-entry") send("QUERY_CALL", "ENTERED", observedModel = model)
            if (shape != "open-query") send("QUERY_CALL", "OPTIMAL",
                if (shape == "mismatched-query") 99L else query,
                if (shape == "mismatched-model") Any() else model)
        }
        if (shape == "return-without-entry") send("QUERY_CALL", "OPTIMAL", 1L, model)
        if (shape == "guard-only") send("QUERY_CALL", "ROOT_WORK")
        if (shape == "invalid-query-kind") send("QUERY_CALL", "RETAINED")
        if (shape == "source-change") window.observe("bt/lp-default",
            event.copy(sequence = sequence++, source = Any()), 3L)
        if (shape == "objective-change") window.observe("bt/lp-default",
            event.copy(sequence = sequence++, objective = Any()), 3L)
        if (shape == "sequence-gap") sequence++
        if (shape == "reflection-failure") window.observe("bt/lp-default", Any(), 3L)
        if (shape == "end-without-begin") send("CLOSE_END", "END")
        send("CLOSE_BEGIN", "BEGIN")
        if (shape == "duplicate-close-begin") send("CLOSE_BEGIN", "BEGIN")
        if (shape == "event-after-close-begin") send("TARGETS", "RETAINED")
        if (shape != "missing-end") send("CLOSE_END", "END")
        if (shape == "duplicate-close-end") send("CLOSE_END", "END")
        if (shape == "event-after-close-end") send("TARGETS", "RETAINED")

        val snapshot = window.snapshot()
        val owner = snapshot.owners.single()

        assertEquals(if (shape in setOf("complete", "valid-query", "guard-only"))
            "CLOSED_JOURNALED_SCOPE" else "UNKNOWN", owner.coverage)
        assertTrue(snapshot.partialQueryCoverage)
        assertEquals(false, snapshot.absenceEvidence)
    }

    @Test
    fun `root event overflow retains bounded records and reports unknown coverage`() {
        val window = FinitePortfolioLpCapture.RootPointWindow("any",
            FinitePortfolioLpCapture.RootPointLimits(ownerEvents = 4))
        val event = RootEvent(Any(), 1L)
        repeat(5) { index -> window.observe("bt/lp-default", event.copy(sequence = index + 1L), index.toLong()) }

        val snapshot = window.snapshot()

        assertEquals(4, snapshot.events.size)
        assertEquals(1L, snapshot.dropped)
        assertEquals("UNKNOWN", snapshot.owners.single().coverage)
    }

    @Test
    fun `root owner exhaustion never aliases a new owner`() {
        val window = FinitePortfolioLpCapture.RootPointWindow("any",
            FinitePortfolioLpCapture.RootPointLimits(owners = 4))
        val owners = List(5) { Any() }
        owners.forEach { window.observe("bt/lp-default", RootEvent(it, 1L), 1L) }

        val snapshot = window.snapshot()

        assertEquals(4, snapshot.owners.size)
        assertEquals(4, snapshot.events.size)
        assertEquals(1L, snapshot.identityOverflow)
        assertEquals(1L, snapshot.dropped)
    }

    @Test
    fun `root capture counts unselected workers without reading borrowed identities`() {
        val window = FinitePortfolioLpCapture.RootPointWindow("bt/lp-default")

        window.observe("bt/lp-aggressive", Any(), 1L)

        val snapshot = window.snapshot()
        assertEquals(1L, snapshot.unselected)
        assertEquals(0L, snapshot.failures)
        assertTrue(snapshot.events.isEmpty())
    }

    @Test
    fun `concurrent root callbacks share the total event capacity`() {
        val window = FinitePortfolioLpCapture.RootPointWindow("any",
            FinitePortfolioLpCapture.RootPointLimits(ownerEvents = 8, events = 16))
        val owners = List(4) { Any() }
        val threads = owners.map { owner ->
            Thread {
                repeat(8) { index ->
                    window.observe("bt/lp-default", RootEvent(owner, index + 1L), index.toLong())
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val snapshot = window.snapshot()
        assertEquals(16, snapshot.events.size)
        assertEquals(16L, snapshot.dropped)
        assertEquals(0L, snapshot.failures)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `root snapshot json preserves observed identities and dropped coverage`(drop: Boolean) {
        val window = FinitePortfolioLpCapture.RootPointWindow("any",
            FinitePortfolioLpCapture.RootPointLimits(ownerEvents = if (drop) 2 else 512))
        val event = RootEvent(Any(), 1L, point = Any(), previousPoint = Any(),
            model = Any(), pointObjective = Any(), sourceRoot = true, paused = false, termination = "LIMIT")
        window.observe("bt/lp-default", event, 11L)
        window.observe("bt/lp-default", event.copy(sequence = 2L, stage = "CLOSE_BEGIN", disposition = "BEGIN",
            model = null, point = null, previousPoint = null, pointObjective = null, sourceRoot = null,
            paused = null, termination = null), 12L)
        window.observe("bt/lp-default", event.copy(sequence = 3L, stage = "CLOSE_END", disposition = "END"), 13L)

        val snapshot = window.snapshot()
        val json = snapshot.json()
        val owner = json.getValue("owners").jsonArray.single().jsonObject
        val first = json.getValue("events").jsonArray.first().jsonObject

        assertEquals(if (drop) "UNKNOWN" else "CLOSED_JOURNALED_SCOPE",
            owner.getValue("coverage").jsonPrimitive.content)
        assertEquals(if (drop) 1L else 0L, json.getValue("dropped").jsonPrimitive.long)
        assertEquals(if (drop) 2 else 3, json.getValue("retained").jsonPrimitive.int)
        assertEquals(if (drop) "1" else null,
            json.getValue("droppedStages").jsonObject["CLOSE_END"]?.jsonPrimitive?.content)
        assertEquals(setOf("worker", "owner", "source", "objective", "pointObjective", "model", "point",
            "previousPoint", "sequence", "queryCall", "stage", "disposition", "automatic", "auxiliary",
            "partial", "sourceRoot", "paused", "termination", "elapsedNs"), first.keys)
        assertEquals("bt/lp-default", first.getValue("worker").jsonPrimitive.content)
        for (name in listOf("owner", "source", "objective", "model", "point", "queryCall")) {
            assertEquals(0L, first.getValue(name).jsonPrimitive.long)
        }
        assertEquals(1, first.getValue("pointObjective").jsonPrimitive.int)
        assertEquals(1, first.getValue("previousPoint").jsonPrimitive.int)
        assertEquals(1L, first.getValue("sequence").jsonPrimitive.long)
        assertEquals(11L, first.getValue("elapsedNs").jsonPrimitive.long)
        assertEquals("OWNER", first.getValue("stage").jsonPrimitive.content)
        assertEquals("RETAINED", first.getValue("disposition").jsonPrimitive.content)
        assertEquals("true", first.getValue("automatic").jsonPrimitive.content)
        assertEquals("false", first.getValue("auxiliary").jsonPrimitive.content)
        assertEquals("false", first.getValue("partial").jsonPrimitive.content)
        assertEquals("true", first.getValue("sourceRoot").jsonPrimitive.content)
        assertEquals("false", first.getValue("paused").jsonPrimitive.content)
        assertEquals("LIMIT", first.getValue("termination").jsonPrimitive.content)
        assertEquals("true", json.getValue("partialQueryCoverage").jsonPrimitive.content)
        assertEquals("false", json.getValue("absenceEvidence").jsonPrimitive.content)
        assertEquals("UNKNOWN", json.getValue("cursorLineage").jsonPrimitive.content)
        val closing = json.getValue("events").jsonArray[1].jsonObject
        for (name in listOf("model", "point", "previousPoint", "pointObjective",
            "sourceRoot", "paused", "termination")) {
            assertEquals(JsonNull, closing.getValue(name))
        }
        assertEquals(setOf("owner", "worker", "seen", "dropped", "retainedOwnerObserved", "closeBegin", "closeEnd",
            "openQuery", "coverage"), owner.keys)
        assertEquals(JsonNull, owner.getValue("openQuery"))
    }

    @Test
    fun `root snapshots preserve their observed prefix after later callbacks`() {
        val window = FinitePortfolioLpCapture.RootPointWindow("any")
        val event = RootEvent(Any(), 1L)
        window.observe("bt/lp-default", event, 1L)
        val before = window.snapshot()

        window.observe("bt/lp-default", event.copy(sequence = 2L, stage = "CLOSE_BEGIN", disposition = "BEGIN"), 2L)
        window.observe("bt/lp-default", event.copy(sequence = 3L, stage = "CLOSE_END", disposition = "END"), 3L)

        assertEquals(1, before.events.size)
        assertEquals("UNKNOWN", before.owners.single().coverage)
        assertEquals(3, window.snapshot().events.size)
        assertEquals("CLOSED_JOURNALED_SCOPE", window.snapshot().owners.single().coverage)
    }

    @Test
    fun `root adapter records ordinary getter failures`() {
        val window = FinitePortfolioLpCapture.RootPointWindow("any")

        window.observe("bt/lp-default", RootGetterFailure(IllegalStateException("getter")), 1L)

        val snapshot = window.snapshot()
        assertEquals(1L, snapshot.failures)
        assertTrue(snapshot.events.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["cancellation", "error"])
    fun `root adapter propagates interrupted getters`(kind: String) {
        val window = FinitePortfolioLpCapture.RootPointWindow("any")
        val failure = if (kind == "cancellation") CancellationException("getter") else AssertionError("getter")

        val thrown = assertFailsWith<Throwable> {
            window.observe("bt/lp-default", RootGetterFailure(failure), 1L)
        }

        assertSame(failure, thrown)
        assertEquals(0L, window.snapshot().failures)
    }

    class RootGetterFailure(private val failure: Throwable) {
        val owner: Any get() = throw failure
    }

    @Test
    fun `a committed root value keeps the offered point identity and split`() {
        val owner = Any()
        val point = Any()
        val window = FinitePortfolioLpCapture.RootPointWindow("any")
        val split = FinitePortfolioLpCapture.RootPointValueCommit(5, 42L, 40L, false, 1)
        val offer = RootEvent(owner, 1L, "CONSUMER", "VALUE_OFFERED", point = point, sourceRoot = true)

        window.observe("bt/lp-default", offer, 0L)
        window.observe("bt/lp-default", offer.copy(sequence = 2L, disposition = "VALUE_COMMITTED",
            valueCommit = split), 0L)

        val events = window.snapshot().events
        assertEquals(events[0].point, events[1].point)
        assertEquals(split, events[1].valueCommit)
    }

    @Test
    fun `an applied root bound keeps the checked model and preceding shared floor`() {
        val owner = Any()
        val model = Any()
        val window = FinitePortfolioLpCapture.RootPointWindow("any")
        val checked = RootEvent(owner, 1L, "BOUND", "BOUND_CHECKED", model = model, sourceRoot = true, bound = 33.0)

        window.observe("bt/lp-default", checked, 0L)
        window.observe("bt/lp-default", checked.copy(sequence = 2L, disposition = "BOUND_APPLIED",
            priorSharedBound = 12.0), 0L)

        val events = window.snapshot().events
        assertEquals(events[0].model, events[1].model)
        assertEquals(events[0].bound, events[1].bound)
        assertEquals(12.0, events[1].priorSharedBound)
    }

    internal data class RootEvent(
        val owner: Any,
        val sequence: Long,
        val stage: String = "OWNER",
        val disposition: String = "RETAINED",
        val source: Any = owner,
        val objective: Any = owner,
        val pointObjective: Any? = null,
        val model: Any? = null,
        val point: Any? = null,
        val previousPoint: Any? = null,
        val query: Long = 0L,
        val automatic: Boolean = true,
        val auxiliary: Boolean = false,
        val partial: Boolean = false,
        val sourceRoot: Boolean? = null,
        val paused: Boolean? = null,
        val termination: String? = null,
        val admission: FinitePortfolioLpCapture.RootAdmissionRecord? = null,
        val valueCommit: FinitePortfolioLpCapture.RootPointValueCommit? = null,
        val bound: Double? = null,
        val priorSharedBound: Double? = null,
    )

    @ParameterizedTest
    @ValueSource(strings = ["bt/satOptimized", "bt/conflictDriven"])
    fun `unselected callbacks should preserve selected metadata and owner capacity`(worker: String) {
        val window = FinitePortfolioLpCapture.QueryWindow("bt/lp-default", 1)
        val owner = Any()

        repeat(200) {
            assertNull(window.observe(worker, { error("unselected owner must not be read") }) { false })
        }
        val records = List(124) { window.observe("bt/lp-default", { owner }) { false } }

        records.forEach { assertEquals(0, assertNotNull(it).owner) }
        assertEquals(324, window.queries)
        assertEquals(124, window.workerQueries)
        assertEquals(200, window.unselectedWorkerQueries)
        assertEquals(0, window.metadataDropped)
    }

    @Test
    fun `all worker capture should share the early metadata budget`() {
        val window = FinitePortfolioLpCapture.QueryWindow("any", 1)
        val owner = Any()
        repeat(124) {
            val worker = if (it % 2 == 0) "bt/satOptimized" else "bt/lp-default"
            assertNotNull(window.observe(worker, { owner }) { false })
        }

        val overflow = window.observe("bt/lp-default", { owner }) { false }

        assertNull(overflow)
        assertEquals(125, window.workerQueries)
        assertEquals(0, window.unselectedWorkerQueries)
        assertEquals(1, window.metadataDropped)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 2, 3, 4])
    fun `late matching captures should fit the reserved metadata capacity`(captures: Int) {
        val window = FinitePortfolioLpCapture.QueryWindow("bt/lp-default", captures)
        val owner = Any()
        repeat(124) { assertNotNull(window.observe("bt/lp-default", { owner }) { false }) }

        val late = List(10) { window.observe("bt/lp-default", { owner }) { true } }

        assertEquals(captures, late.count { it?.capture == true })
        assertEquals(captures, window.captureAttempts)
        assertEquals(10 - captures, window.metadataDropped)
    }

    @Test
    fun `equal owner values should retain separate identities`() {
        val window = FinitePortfolioLpCapture.QueryWindow("any", 1)
        val first = String(charArrayOf('x'))
        val second = String(charArrayOf('x'))

        val a = window.observe("bt/lp-default", { first }) { false }
        val b = window.observe("bt/lp-default", { second }) { false }

        assertEquals(first, second)
        assertEquals(0, assertNotNull(a).owner)
        assertEquals(1, assertNotNull(b).owner)
    }

    @Test
    fun `owner exhaustion should preserve queries from retained owners`() {
        val window = FinitePortfolioLpCapture.QueryWindow("bt/lp-default", 1)
        val owners = List(64) { Any() }
        owners.forEach { assertNotNull(window.observe("bt/lp-default", { it }) { false }) }

        val overflow = window.observe("bt/lp-default", { Any() }) { true }
        val retained = window.observe("bt/lp-default", { owners.first() }) { true }

        assertNull(overflow)
        assertEquals(0, assertNotNull(retained).owner)
        assertEquals(true, retained.capture)
        assertEquals(1, window.captureAttempts)
        assertEquals(1, window.metadataDropped)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `omitted policy should retain the companion configuration`(handoff: Boolean) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to handoff.toString())

        val parameters = FinitePortfolioLpCapture.parameters(options)

        assertEquals(listOf("companion-integers=true", "companion-active-ms=4000",
            "companion-after-incumbent=true", "companion-admission=progress-funded", "companion-repair=fixed",
            "source-incumbent-completion=$handoff", "portfolio-progress=true", "portfolio-reward=ordinary",
            "phase-retention=0.25", "reseed-stale-threshold=3", "portfolio-probes=ordinary",
            "portfolio-proof-workers=retained", "portfolio-proof-share=0.5"), parameters)
    }

    @Test
    fun `ordinary policy should leave algorithm selection at source defaults`() {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "false", "portfolio-policy" to "ordinary", "portfolio-progress" to "false")

        val parameters = FinitePortfolioLpCapture.parameters(options)

        assertEquals(listOf("portfolio-progress=false"), parameters)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `complete coverage should preserve supported handoff selection`(handoff: Boolean) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to handoff.toString(), "portfolio-policy" to "complete")

        val parameters = FinitePortfolioLpCapture.parameters(options).associate {
            it.substringBefore('=') to it.substringAfter('=')
        }

        assertEquals("complete", parameters["portfolio-proof-workers"])
        assertEquals(handoff.toString(), parameters["source-incumbent-completion"])
        assertEquals("fixed", parameters["companion-repair"])
    }

    @ParameterizedTest
    @CsvSource("ordinary,false", "ordinary,true", "retained,false", "retained,true", "complete,false", "complete,true")
    fun `progress logging should be independent of policy selection`(policy: String, progress: Boolean) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "false", "portfolio-policy" to policy,
            "portfolio-progress" to progress.toString())

        val parameters = FinitePortfolioLpCapture.parameters(options)

        assertEquals(listOf("portfolio-progress=$progress"), parameters.filter { it.startsWith("portfolio-progress=") })
    }

    @Test
    fun `ordinary joint capture should preserve quiet production companion controls`() {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "true", "capture-mode" to "joint-ordinary")

        val parameters = FinitePortfolioLpCapture.parameters(options)

        assertEquals(FinitePortfolioLpCapture.parameters(options + ("capture-mode" to "joint-before")), parameters)
        assertTrue("portfolio-progress=false" in parameters)
        assertTrue("companion-admission=ordinary" in parameters)
        assertTrue(parameters.none { it.startsWith("source-refutation-center-step=") })
    }

    @Test
    fun `handoff capture should keep ordinary binary admission and the incumbent gate`() {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "true", "capture-mode" to "joint-handoff")

        val parameters = FinitePortfolioLpCapture.parameters(options)

        assertEquals(listOf("companion-integers=false", "companion-active-ms=4000",
            "companion-after-incumbent=true", "companion-admission=ordinary", "companion-repair=fixed",
            "source-incumbent-completion=true", "portfolio-progress=false",
            "portfolio-proof-workers=complete", "portfolio-proof-share=0.5"), parameters)
    }

    @ParameterizedTest
    @ValueSource(strings = ["joint-ordinary", "joint-handoff"])
    fun `proposal journals should reject LP snapshots before corpus resolution`(mode: String) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "true", "capture-mode" to mode, "captures" to "1")

        assertFailsWith<IllegalArgumentException> {
            FinitePortfolioLpCapture.parameters(options)
        }
    }

    @ParameterizedTest
    @CsvSource("joint-ordinary,false", "joint-ordinary,true", "joint-handoff,false", "joint-handoff,true")
    fun `joint capture should forward explicit center progress`(mode: String, enabled: Boolean) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "true", "capture-mode" to mode)
        val expected = FinitePortfolioLpCapture.parameters(options) + "source-refutation-center-step=$enabled"

        val parameters = FinitePortfolioLpCapture.parameters(
            options + ("source-refutation-center-step" to enabled.toString()))

        assertEquals(expected, parameters)
    }

    @ParameterizedTest
    @ValueSource(strings = ["yes", "1", "TRUE"])
    fun `ordinary joint capture should reject invalid center progress values`(value: String) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "true", "capture-mode" to "joint-ordinary",
            "source-refutation-center-step" to value)

        assertFailsWith<IllegalArgumentException> { FinitePortfolioLpCapture.parameters(options) }
    }

    @ParameterizedTest
    @CsvSource("portfolio,false", "portfolio,true", "joint-before,false", "joint-before,true")
    fun `other capture modes should reject center progress selection`(mode: String, value: String) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "true", "capture-mode" to mode,
            "source-refutation-center-step" to value)

        val failure = assertFailsWith<IllegalArgumentException> { FinitePortfolioLpCapture.parameters(options) }

        assertTrue(assertNotNull(failure.message).contains("requires joint-ordinary capture"))
    }

    @ParameterizedTest
    @CsvSource("captures,1", "handoff,false", "portfolio-progress,true", "inspect-rays,true", "capture-guidance,true")
    fun `ordinary joint capture should reject unrelated observer controls`(key: String, value: String) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "true", "capture-mode" to "joint-ordinary") + (key to value)

        assertFailsWith<IllegalArgumentException> { FinitePortfolioLpCapture.parameters(options) }
    }

    @Test
    fun `joint capture should retain ordinary pre incumbent grants`() {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "true", "capture-mode" to "joint-before")

        val parameters = FinitePortfolioLpCapture.parameters(options).associate {
            it.substringBefore('=') to it.substringAfter('=')
        }

        assertEquals("ordinary", parameters["companion-admission"])
        assertEquals("false", parameters["companion-after-incumbent"])
        assertEquals("4000", parameters["companion-active-ms"])
        assertEquals("fixed", parameters["companion-repair"])
        assertEquals("complete", parameters["portfolio-proof-workers"])
    }

    @ParameterizedTest
    @CsvSource("handoff,false", "portfolio-policy,retained", "portfolio-progress,true", "capture-worker,any",
        "inspect-rays,unknown")
    fun `joint capture should reject incompatible controls before corpus resolution`(key: String, value: String) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "true", "capture-mode" to "joint-before") + (key to value)

        assertFailsWith<IllegalArgumentException> {
            FinitePortfolioLpCapture.main(options.map { (name, setting) -> "$name=$setting" }.toTypedArray())
        }
    }

    @ParameterizedTest
    @CsvSource("portfolio-policy,unknown", "portfolio-progress,yes", "handoff,true", "unsupported,true",
        "inspect-rays,true")
    fun `invalid policy requests should fail before corpus resolution`(key: String, value: String) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "false", "portfolio-policy" to "ordinary") + (key to value)

        assertFailsWith<IllegalArgumentException> {
            FinitePortfolioLpCapture.main(options.map { (name, setting) -> "$name=$setting" }.toTypedArray())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `ray inspection should preserve producer parameters`(enabled: Boolean) {
        val options = mapOf("suite" to "unused", "problem" to "unused", "sha256" to "unused",
            "seed" to "1", "handoff" to "true", "capture-mode" to "joint-before")
        val expected = FinitePortfolioLpCapture.parameters(options)

        val parameters = FinitePortfolioLpCapture.parameters(options + ("inspect-rays" to enabled.toString()))

        assertEquals(expected, parameters)
    }
}
