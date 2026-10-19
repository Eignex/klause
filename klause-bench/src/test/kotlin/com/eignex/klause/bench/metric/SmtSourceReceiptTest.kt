package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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

    private fun receipt(
        producer: SolveRecord = record,
        machine: String? = null,
        launcher: Boolean = false,
    ): SmtSourceValidation.Receipt {
        val refutation = producer.feasible == false
        val result = SmtSourceValidation.Receipt(
            "valid", "checked", requireNotNull(SmtSourceValidation.eligible(producer)),
            bindings(producer), tools, if (refutation) 1 else 0, false, true, producer.finalWitness, SMT_CHECKER,
            2, 2, runtime, arithmetic, bootstrap,
            if (refutation) check.copy(addressSpaceBytes = 8_589_934_592, timeoutSeconds = 40) else check,
            referenceComparison = if (refutation) "unsat" else null, nativeRuntime = native,
            solverQueryCountObserved = true,
            referenceLogic = if (refutation) "QF_NRA" else null,
        )
        if (machine == null) return result
        val arm = machine == "arm64"
        require(arm || machine == "x86_64")
        val wheel = if (arm) {
            "63/45/dd8e9d7500faa05eafa589cc8f0f0b982ce575d51b455d62dab8e19dd571/"
        } else {
            "54/9e/a11186061d9fead8be43bad7c75055585694124b2ccdd896ef249fe5824f/"
        }
        val framework = "/Library/Frameworks/Python.framework/Versions/3.13/Python"
        val invoked = if (launcher) framework.removeSuffix("/Python") + "/bin/python3.13" else runtime.pythonExecutable
        val loaded = if (launcher) framework.removeSuffix("/Python") +
            "/Resources/Python.app/Contents/MacOS/Python" else invoked
        val macRuntime = runtime.copy(
            pythonExecutable = invoked, machine = machine, libc = listOf("", ""),
            wheelBytes = if (arm) 36_882_388L else 39_637_842L,
            wheelSha256 = if (arm) "65335aab295ded7c0ce27c85556067087a87052389ff160777d1a1d48ef0d74f"
                else "3e62e93adff2def3537ff1ca67c3d58a6ca6d1944e0b5e774f88627b199d50e7",
            wheelUrl = "https://files.pythonhosted.org/packages/" + wheel +
                "z3_solver-4.15.3.0-py3-none-macosx_13_0_$machine.whl",
            extractedFiles = runtime.extractedFiles.map { it.copy(path = it.path.replace(".so", ".dylib")) },
        )
        val library = native.library.copy(path = native.library.path.replace(".so", ".dylib"))
        val cpu = if (arm) 0x100000c else 0x1000007
        val images = listOf(loaded, framework, library.path).mapIndexed { index, path ->
            """{"path":"$path","address":"${(index + 1) * 1000}","magic":"feedfacf","cpuType":$cpu}"""
        }.joinToString("\n", postfix = "\n")
        val memory = mapOf(
            "bootstrap" to 2_147_483_648L,
            "check" to if (refutation) 8_589_934_592L else 4_294_967_296L,
        ).mapValues { (_, limit) ->
            SmtResidentMemory("darwin-proc-taskinfo-wait4-v1", limit, 100, 200, 1, true, false, null)
        }
        val launcherFiles = listOfNotNull(
            MpsCheckerFile(invoked, 100, runtime.pythonExecutableSha256),
            MpsCheckerFile(loaded, 100, "b".repeat(64)).takeIf { launcher },
            MpsCheckerFile(framework, 100, "f".repeat(64)),
        )
        return result.copy(runtime = macRuntime, residentMemory = memory,
            bootstrapProcess = requireNotNull(result.bootstrapProcess).copy(addressSpaceBytes = 0,
                command = bootstrap.command.mapIndexed { index, value -> if (index == 0) invoked else value }),
            checkProcess = requireNotNull(result.checkProcess).let { process ->
                process.copy(addressSpaceBytes = 0,
                    command = process.command.mapIndexed { index, value -> if (index == 0) invoked else value })
            }, nativeRuntime = native.copy(
            executablePath = invoked, library = library, maps = images, mapsSha256 = sha256(images.toByteArray()),
            pythonProviders = launcherFiles,
            platform = "Darwin", systemVersion = "13.0.0", mappingKind = "dyld-images-v1",
        ))
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
    @CsvSource(
        "arm64,ground,false,false", "arm64,ground,true,false", "arm64,SMT,false,false", "arm64,SMT,true,false",
        "x86_64,ground,false,false", "x86_64,ground,true,false", "x86_64,SMT,false,false", "x86_64,SMT,true,false",
        "arm64,ground,false,true", "arm64,ground,true,true", "arm64,SMT,false,true", "arm64,SMT,true,true",
        "x86_64,ground,false,true", "x86_64,ground,true,true", "x86_64,SMT,false,true", "x86_64,SMT,true,true",
    )
    fun `macOS receipts should retain checked claims with pinned loaded providers`(
        machine: String,
        logic: String,
        builtin: Boolean,
        launcher: Boolean,
    ) {
        val refutation = logic != "ground"
        val producer = if (refutation) record.copy(feasible = false, proven = true, finalWitness = null) else record
        val original = receipt(producer, machine, launcher)
        val macNative = requireNotNull(original.nativeRuntime)
        val math = if (builtin) arithmetic.copy(modules = arithmetic.modules - "math", builtinMath = MpsBuiltinModule(
            "built-in", "_frozen_importlib.BuiltinImporter", macNative.executablePath,
            macNative.maps, macNative.mapsSha256, macNative.pythonProviders,
        )) else arithmetic
        val receipt = original.copy(groundArithmetic = math, referenceLogic = logic.takeIf { refutation })

        val result = SmtSourceValidation.checkerResult(0, bind(receipt), producer, bindings(producer), tools)
        val published = SmtSourceValidation.apply(producer, result)

        assertEquals("valid", result.validation.status)
        assertEquals(producer.feasible, published.feasible)
        assertEquals(refutation, published.proven)
        assertEquals(if (refutation) "1" else "0", result.stats["sourceValidationSolverQueries"])
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
            "mac-wheel", "mac-cpu", "mac-version", "mac-provider", "mac-native-path", "mac-maps-hash",
            "mac-memory", "mac-runtime-kind", "mac-framework-version", "mac-duplicate-header",
            "mac-rss-missing", "mac-rss-limit", "mac-rss-peak", "mac-rss-complete", "mac-rss-exceeded",
            "mac-rss-method", "mac-rss-error", "mac-rss-sample", "mac-rss-zero", "linux-rss",
            "mac-launcher-path", "mac-launcher-main", "mac-launcher-framework", "mac-launcher-provider",
        ],
    )
    fun `incomplete or mismatched receipt custody should withhold acceptance`(failure: String) {
        val original = receipt(machine = "arm64".takeIf { failure.startsWith("mac-") },
            launcher = failure.startsWith("mac-launcher-"))
        val macRuntime = requireNotNull(original.runtime)
        val macNative = requireNotNull(original.nativeRuntime)
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

            "mac-wheel" -> original.copy(runtime = macRuntime.copy(wheelSha256 = runtime.wheelSha256))

            "mac-cpu" -> original.copy(runtime = macRuntime.copy(machine = "x86_64"))

            "mac-version" -> original.copy(nativeRuntime = macNative.copy(systemVersion = "12.0.0"))

            "mac-provider" -> original.copy(nativeRuntime = macNative.copy(pythonProviders = emptyList()))

            "mac-native-path" -> original.copy(nativeRuntime = macNative.copy(
                library = macNative.library.copy(path = "/global/libz3.dylib")))

            "mac-maps-hash" -> original.copy(nativeRuntime = macNative.copy(mapsSha256 = "f".repeat(64)))

            "mac-memory" -> original.copy(checkProcess = check.copy(addressSpaceBytes = 1))

            "mac-launcher-path" -> original.copy(nativeRuntime = macNative.copy(executablePath = "/else/python3.13"))

            "mac-launcher-provider" -> original.copy(nativeRuntime = macNative.copy(
                pythonProviders = macNative.pythonProviders.filter { "Python.app" !in it.path },
            ))

            "mac-launcher-main", "mac-launcher-framework" -> {
                val maps = if (failure == "mac-launcher-main") {
                    macNative.maps.replace("/Resources/Python.app/Contents/MacOS/Python", "/foreign/Python")
                } else macNative.maps.replace("/Versions/3.13/Python", "/Versions/3.12/Python")
                original.copy(nativeRuntime = macNative.copy(maps = maps, mapsSha256 = sha256(maps.toByteArray())))
            }

            "mac-rss-missing" -> original.copy(residentMemory = null)

            "linux-rss" -> original.copy(residentMemory = receipt(machine = "arm64").residentMemory)

            "mac-rss-limit", "mac-rss-peak", "mac-rss-complete", "mac-rss-exceeded", "mac-rss-method",
            "mac-rss-error", "mac-rss-sample", "mac-rss-zero" -> original.copy(
                residentMemory = requireNotNull(original.residentMemory).mapValues { (name, memory) ->
                    if (name != "check") memory else when (failure) {
                        "mac-rss-limit" -> memory.copy(limitBytes = memory.limitBytes + 1)
                        "mac-rss-peak" -> memory.copy(kernelPeakBytes = memory.limitBytes + 1)
                        "mac-rss-complete" -> memory.copy(complete = false)
                        "mac-rss-exceeded" -> memory.copy(exceeded = true)
                        "mac-rss-method" -> memory.copy(method = "linux-address-space")
                        "mac-rss-error" -> memory.copy(error = "unreadable")
                        "mac-rss-sample" -> memory.copy(samplePeakBytes = memory.kernelPeakBytes + 1)
                        else -> memory.copy(kernelPeakBytes = 0)
                    }
                },
            )

            "mac-runtime-kind" -> original.copy(nativeRuntime = macNative.copy(mappingKind = "linux-proc-maps-v1"))

            "mac-framework-version" -> {
                val maps = macNative.maps.replace("Versions/3.13", "Versions/3.12")
                original.copy(nativeRuntime = macNative.copy(maps = maps, mapsSha256 = sha256(maps.toByteArray())))
            }

            "mac-duplicate-header" -> {
                val maps = macNative.maps.replace("\"address\":\"2000\"", "\"address\":\"1000\"")
                original.copy(nativeRuntime = macNative.copy(maps = maps, mapsSha256 = sha256(maps.toByteArray())))
            }

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
