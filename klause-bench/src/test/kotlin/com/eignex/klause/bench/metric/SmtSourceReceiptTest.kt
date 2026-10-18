package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmtSourceReceiptTest {
    private val record = SolveRecord(
        "fixture", "klause", "cp", 1, "free", 3, 10_000,
        "satisfy", false, true, null, 10, proven = false, stats = mapOf("exit" to "0"),
        gitSha = "producer", timestamp = "t", command = "cli", finalWitness = "(model)",
        sourceHashes = mapOf("model" to "a".repeat(64)), sourceOutputSha256 = "b".repeat(64),
    )
    private val tools = listOf(
        "smt_source_validation.py",
        "mps_witness_validation.py",
        "smt_witness_validation.py",
        "verify_nra_source_results.py",
        "verify_source_results.py",
        "requirements-source-checks.txt",
    )
        .associateWith { "c".repeat(64) }
    private val runtime = MpsCheckerRuntime(
        "3.13.7", "/usr/bin/python3", "a".repeat(64), "x86_64",
        listOf("glibc", "2.39"), "4.15.3.0", "4.15.3", 29_056_003,
        "a9afd9ceb290482097474d43f08415bcc1874f433189d1449f6c1508e9c68384",
        "https://files.pythonhosted.org/packages/" +
            "b9/0b/f15168475e5493ea44fa3c5e642903f05d2b870db71ad05662ed87a06976/" +
            "z3_solver-4.15.3.0-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl",
        "b".repeat(64),
            listOf(
            MpsCheckerFile("z3/__init__.py", 1, "c".repeat(64)),
            MpsCheckerFile("z3/lib/libz3.so", 1, "d".repeat(64)),
        ),
            false,
    )
    private val arithmetic = MpsGroundArithmetic(
        "smt-exact-source-arithmetic-v2",
        runtime.pythonVersion,
        runtime.pythonExecutableSha256,
        listOf("fractions", "numbers", "math").associateWith {
            MpsCheckerFile("/stdlib/$it", 100, "e".repeat(64))
        },
    )
    private val maps = "1000-2000 r-xp 00000000 08:01 123 /usr/bin/python3\n" +
        "3000-4000 r-xp 00000000 08:01 124 /private/runtime/dependencies/z3/lib/libz3.so\n"
    private val native = SmtNativeRuntime(
        runtime.pythonExecutable,
        maps,
        sha256(maps.toByteArray()),
        MpsCheckerFile("/private/runtime/dependencies/z3/lib/libz3.so", 1, "d".repeat(64)),
        listOf(MpsCheckerFile(runtime.pythonExecutable, 100, runtime.pythonExecutableSha256)),
    )
    private val stream = MpsCheckerStream(0, 0, true, sha256(byteArrayOf()))
    private val bootstrap = MpsCheckerProcess(
        listOf(
        runtime.pythonExecutable,
        "-I",
        "/owned/case/tools/smt_witness_validation.py",
        "bootstrap",
        "/owned/case",
        "/private/runtime/dependencies",
    ),
        "/private/runtime", 0, false, false, null, mapOf("stdout" to stream, "stderr" to stream),
        true, 1_000_000, 2_147_483_648, 30,
    )
    private val check = bootstrap.copy(
        command = listOf(
            runtime.pythonExecutable,
            "-I",
            "/owned/case/tools/smt_source_validation.py",
            "check",
            "/owned/case",
            "/private/runtime/dependencies",
        ),
        addressSpaceBytes = 4_294_967_296,
    )
    private val childFields = setOf(
        "status", "reason", "scope", "bindings", "solverSearchQueries",
        "proofCertificateChecked", "checkedWitness", "checker", "groundArithmetic", "nativeRuntime",
        "referenceComparison", "solverQueryCountObserved", "referenceLogic", "sourceDerivation",
    )

    private fun bindings(producer: SolveRecord): Map<String, String> = mapOf(
        "source.smt2" to producer.sourceHashes.getValue("model"),
        "stdout.txt" to requireNotNull(producer.sourceOutputSha256),
        "model.smt2" to sha256(producer.finalWitness.orEmpty().toByteArray()),
        "record.json" to sha256(Reports.json.encodeToString(producer).toByteArray()),
        "request.json" to sha256(
            ("{\"scope\":\"${SmtSourceValidation.eligible(producer)}\",\"queryMillis\":30000}").toByteArray(),
        ),
    )

    private fun receipt(producer: SolveRecord = record): SmtSourceValidation.Receipt {
        val refutation = producer.feasible == false
        return SmtSourceValidation.Receipt(
            "valid", "checked", requireNotNull(SmtSourceValidation.eligible(producer)),
            bindings(producer), tools, if (refutation) 1 else 0, false, true, producer.finalWitness, SMT_CHECKER,
            2, 2, runtime, arithmetic, bootstrap,
            if (refutation) check.copy(addressSpaceBytes = 8_589_934_592, timeoutSeconds = 40) else check,
            referenceComparison = if (refutation) "unsat" else null, nativeRuntime = native,
            solverQueryCountObserved = true,
            referenceLogic = if (refutation) "QF_NRA" else null,
        )
    }

    private fun bind(value: SmtSourceValidation.Receipt): String {
        val child = JsonObject(
            Reports.json.parseToJsonElement(Reports.json.encodeToString(value)).jsonObject
                .filterKeys { it in childFields },
        ).toString() + "\n"
        val bytes = child.toByteArray()
        val process = requireNotNull(value.checkProcess)
        val bound = value.copy(
            checkProcess = process.copy(
                streams = process.streams + (
                    "stdout" to
                MpsCheckerStream(bytes.size.toLong(), bytes.size.toLong(), true, sha256(bytes))
                )
            ),
            processLogs = mapOf(
                "bootstrap.stdout" to "",
                "bootstrap.stderr" to "",
                "check.stdout" to child,
                "check.stderr" to "",
            ),
        )
        return Reports.json.encodeToString(bound)
    }

    @ParameterizedTest
    @ValueSource(strings = ["ground", "QF_NRA", "SMT"])
    fun `complete receipts should retain the exact accepted claim and separate query accounting`(logic: String) {
        val refutation = logic != "ground"
        val producer = if (refutation) record.copy(feasible = false, proven = true, finalWitness = null) else record
        val receipt = receipt(producer).copy(referenceLogic = logic.takeIf { refutation })

        val result = SmtSourceValidation.checkerResult(0, bind(receipt), producer, bindings(producer), tools)
        val published = SmtSourceValidation.apply(producer, result)

        assertEquals("valid", result.validation.status)
        assertEquals(producer.finalWitness, result.validation.checkedWitness)
        assertEquals(if (refutation) "1" else "0", result.stats["sourceValidationSolverQueries"])
        assertEquals("false", result.stats["sourceValidationProofChecked"])
        assertEquals(if (refutation) logic else "", result.stats["sourceValidationReferenceLogic"])
        assertEquals("false", result.stats["sourceValidationDerivationChecked"])
        assertEquals(producer.feasible, published.feasible)
        assertEquals(refutation, published.proven)
        assertEquals("valid", published.stats["sourceValidation"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["source-signs", "source-interval", "empty-source-bounds"])
    fun `a bound source derivation should retain only an already reported refutation`(method: String) {
        val producer = record.copy(feasible = false, proven = true, finalWitness = null)
        val bound = when (method) {
            "source-interval" -> SmtSourceBound("-1", false, "1", false)
            "empty-source-bounds" -> SmtSourceBound("2", true, "2", false)
            else -> SmtSourceBound("0", true, null, false)
        }
        val derivation = SmtSourceDerivation("source-polynomial-enclosure-v1", method,
            listOf(0, 1), 1, "eq", "a".repeat(64), mapOf("x" to bound))
        val receipt = receipt(producer).copy(solverSearchQueries = 0, referenceComparison = null,
            referenceLogic = null, sourceDerivation = derivation)

        val result = SmtSourceValidation.checkerResult(0, bind(receipt), producer, bindings(producer), tools)
        val published = SmtSourceValidation.apply(producer, result)

        assertEquals("valid", result.validation.status)
        assertNull(result.validation.checkedWitness)
        assertEquals("0", result.stats["sourceValidationSolverQueries"])
        assertEquals("1", result.stats["sourceValidationSolverQueryLimit"])
        assertEquals("true", result.stats["sourceValidationDerivationChecked"])
        assertEquals("false", result.stats["sourceValidationProofChecked"])
        assertEquals("true", result.stats["independentSourceRefutation"])
        assertEquals(false, published.feasible)
        assertEquals(true, published.proven)
        assertNull(published.checkedWitness)
        assertEquals("valid", published.stats["sourceValidation"])
        assertEquals("true", published.stats["sourceValidationDerivationChecked"])
    }

    @ParameterizedTest
    @ValueSource(strings = ["kind", "method", "assertions", "order", "negative", "contradiction", "relation", "hash",
        "empty-bound", "open-bound", "rational", "queries", "reference", "logic", "proof", "witness",
        "unobserved", "cleanup", "child-exit"])
    fun `incoherent source derivations should withhold refutation acceptance`(failure: String) {
        val producer = record.copy(feasible = false, proven = true, finalWitness = null)
        val bound = SmtSourceBound("0", true, null, false)
        val derivation = SmtSourceDerivation("source-polynomial-enclosure-v1", "source-signs",
            listOf(0, 1), 1, "eq", "a".repeat(64), mapOf("x" to bound))
        val changed = when (failure) {
            "kind" -> derivation.copy(kind = "other")
            "method" -> derivation.copy(method = "other")
            "assertions" -> derivation.copy(assertions = emptyList())
            "order" -> derivation.copy(assertions = listOf(1, 0))
            "negative" -> derivation.copy(assertions = listOf(-1, 1))
            "contradiction" -> derivation.copy(contradictionAssertion = 2)
            "relation" -> derivation.copy(relation = "other")
            "hash" -> derivation.copy(expressionSha256 = "other")
            "empty-bound" -> derivation.copy(bounds = mapOf("x" to bound.copy(lower = null, lowerOpen = false)))
            "open-bound" -> derivation.copy(bounds = mapOf("x" to bound.copy(upperOpen = true)))
            "rational" -> derivation.copy(bounds = mapOf("x" to bound.copy(lower = "1/0")))
            else -> derivation
        }
        val original = receipt(producer).copy(solverSearchQueries = 0, referenceComparison = null,
            referenceLogic = null, sourceDerivation = changed)
        val receipt = when (failure) {
            "queries" -> original.copy(solverSearchQueries = 1)
            "reference" -> original.copy(referenceComparison = "unsat")
            "logic" -> original.copy(referenceLogic = "QF_NRA")
            "proof" -> original.copy(proofCertificateChecked = true)
            "witness" -> original.copy(checkedWitness = "(model)")
            "unobserved" -> original.copy(solverQueryCountObserved = false)
            "cleanup" -> original.copy(cleanupComplete = false)
            "child-exit" -> original.copy(checkProcess = requireNotNull(original.checkProcess).copy(exit = 7))
            else -> original
        }

        val result = SmtSourceValidation.checkerResult(0, bind(receipt), producer, bindings(producer), tools)

        assertEquals("unknown", result.validation.status)
        assertNull(result.validation.checkedWitness)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "queries", "proof", "scope", "witness", "cleanup", "binding", "tools", "wheel",
            "package", "runtime", "module", "provider", "native-hash", "native-path", "maps", "maps-hash",
            "argv", "exit", "timeout", "overflow", "parent", "streams", "memory", "deadline", "timing", "child",
            "logic", "derivation",
        ],
    )
    fun `incomplete or mismatched receipt custody should withhold acceptance`(failure: String) {
        val original = receipt()
        val changed = when (failure) {
            "queries" -> original.copy(solverSearchQueries = 1)

            "logic" -> original.copy(referenceLogic = "QF_NRA")

            "derivation" -> original.copy(sourceDerivation = SmtSourceDerivation(
                "source-polynomial-enclosure-v1", "source-signs", listOf(0), 0, "eq", "a".repeat(64), emptyMap()))

            "proof" -> original.copy(proofCertificateChecked = true)

            "scope" -> original.copy(scope = SmtSourceValidation.REFUTATION)

            "witness" -> original.copy(checkedWitness = "other")

            "cleanup" -> original.copy(cleanupComplete = false)

            "binding" -> original.copy(bindings = original.bindings + ("source.smt2" to "f".repeat(64)))

            "tools" -> original.copy(toolHashes = emptyMap())

            "wheel" -> original.copy(runtime = runtime.copy(wheelSha256 = "f".repeat(64)))

            "package" -> original.copy(runtime = runtime.copy(packageVersion = "other"))

            "runtime" -> original.copy(runtime = null)

            "module" -> original.copy(groundArithmetic = arithmetic.copy(modules = arithmetic.modules - "numbers"))

            "provider" -> original.copy(nativeRuntime = native.copy(pythonProviders = emptyList()))

            "native-hash" -> original.copy(
                nativeRuntime = native.copy(library = native.library.copy(sha256 = "f".repeat(64))),
            )

            "native-path" -> original.copy(
                nativeRuntime = native.copy(library = native.library.copy(path = "/global/libz3.so")),
            )

            "maps" -> original.copy(
                nativeRuntime = native.copy(maps = "malformed\n", mapsSha256 = sha256("malformed\n".toByteArray())),
            )

            "maps-hash" -> original.copy(nativeRuntime = native.copy(mapsSha256 = "f".repeat(64)))

            "argv" -> original.copy(checkProcess = check.copy(command = check.command + "other"))

            "exit" -> original.copy(checkProcess = check.copy(exit = 7))

            "timeout" -> original.copy(checkProcess = check.copy(timedOut = true))

            "overflow" -> original.copy(checkProcess = check.copy(overflow = true))

            "parent" -> original.copy(checkProcess = check.copy(parentStop = "stopped"))

            "streams" -> original.copy(bootstrapProcess = bootstrap.copy(complete = false))

            "memory" -> original.copy(checkProcess = check.copy(addressSpaceBytes = 1))

            "deadline" -> original.copy(checkProcess = check.copy(timeoutSeconds = 31))

            "timing" -> original.copy(checkMs = 70_001)

            else -> original
        }
        val raw = if (failure == "child") {
            bind(original).replace("checked", "mutated")
            .replaceFirst("mutated", "checked")
        } else {
            bind(changed)
        }

        val result = SmtSourceValidation.checkerResult(0, raw, record, bindings(record), tools)

        assertEquals("unknown", result.validation.status)
        assertNull(result.validation.checkedWitness)
    }

    @Test
    fun `a nonzero supervisor exit should withhold a complete receipt`() {
        val result = SmtSourceValidation.checkerResult(7, bind(receipt()), record, bindings(record), tools)

        assertEquals("unknown", result.validation.status)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `an interrupted child should leave its query count unobserved`(refutation: Boolean) {
        val producer = if (refutation) record.copy(feasible = false, proven = true, finalWitness = null) else record
        val interrupted = receipt(producer).copy(
            status = "unknown",
            reason = "checker process incomplete",
            solverSearchQueries = 0,
            checkedWitness = null,
            referenceComparison = null,
            checkProcess = check.copy(exit = -9, timedOut = true, complete = false),
            solverQueryCountObserved = false,
        )

        val result = SmtSourceValidation.checkerResult(0, bind(interrupted), producer, bindings(producer), tools)

        assertEquals("unknown", result.validation.status)
        assertEquals("unknown", result.stats["sourceValidationSolverQueries"])
        assertEquals(if (refutation) "1" else "0", result.stats["sourceValidationSolverQueryLimit"])
        assertNull(result.validation.checkedWitness)
    }
}
