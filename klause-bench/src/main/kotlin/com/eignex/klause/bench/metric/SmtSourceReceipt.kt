package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class SmtNativeRuntime(
    val executablePath: String,
    val maps: String,
    val mapsSha256: String,
    val library: MpsCheckerFile,
    val pythonProviders: List<MpsCheckerFile>,
    val platform: String = "Linux",
    val systemVersion: String? = null,
    val mappingKind: String = "linux-proc-maps-v1",
)

@Serializable
internal data class SmtSourceBound(
    val lower: String?,
    val lowerOpen: Boolean,
    val upper: String?,
    val upperOpen: Boolean,
)

@Serializable
internal data class SmtSourceDerivation(
    val kind: String,
    val method: String,
    val assertions: List<Int>,
    val contradictionAssertion: Int,
    val relation: String,
    val expressionSha256: String,
    val bounds: Map<String, SmtSourceBound>,
)

internal object SmtSourceReceipt {
    private const val WHEEL_SHA = "a9afd9ceb290482097474d43f08415bcc1874f433189d1449f6c1508e9c68384"
    private const val WHEEL_URL = "https://files.pythonhosted.org/packages/" +
        "b9/0b/f15168475e5493ea44fa3c5e642903f05d2b870db71ad05662ed87a06976/" +
        "z3_solver-4.15.3.0-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl"
    private val digest = Regex("[0-9a-f]{64}")
    private val macWheels = mapOf(
        "arm64" to Triple(
            36_882_388L,
            "65335aab295ded7c0ce27c85556067087a87052389ff160777d1a1d48ef0d74f",
            "https://files.pythonhosted.org/packages/" +
                "63/45/dd8e9d7500faa05eafa589cc8f0f0b982ce575d51b455d62dab8e19dd571/" +
                "z3_solver-4.15.3.0-py3-none-macosx_13_0_arm64.whl",
        ),
        "x86_64" to Triple(
            39_637_842L,
            "3e62e93adff2def3537ff1ca67c3d58a6ca6d1944e0b5e774f88627b199d50e7",
            "https://files.pythonhosted.org/packages/" +
                "54/9e/a11186061d9fead8be43bad7c75055585694124b2ccdd896ef249fe5824f/" +
                "z3_solver-4.15.3.0-py3-none-macosx_13_0_x86_64.whl",
        ),
    )

    fun requireComplete(receipt: SmtSourceValidation.Receipt, json: JsonObject) {
        require(receipt.solverQueryCountObserved)
        val runtime = requireNotNull(receipt.runtime)
        val arithmetic = requireNotNull(receipt.groundArithmetic)
        val native = requireNotNull(receipt.nativeRuntime)
        require(native.platform in setOf("Linux", "Darwin"))
        val darwin = native.platform == "Darwin"
        require(arithmetic.implementation == "smt-exact-source-arithmetic-v2")
        require(
            arithmetic.pythonVersion == runtime.pythonVersion &&
                arithmetic.pythonExecutableSha256 == runtime.pythonExecutableSha256,
        )
        val moduleNames = if (arithmetic.builtinMath == null) {
            setOf("fractions", "numbers", "math")
        } else {
            setOf("fractions", "numbers")
        }
        require(arithmetic.modules.keys == moduleNames)
        arithmetic.builtinMath?.let {
            if (darwin) requireDarwinPython(it, arithmetic, runtime) else requireBuiltinMath(it, arithmetic, runtime)
        }
        require(
            arithmetic.modules.values.all { file ->
            file.path.startsWith('/') && file.bytes in 1..4_194_304L && digest.matches(file.sha256)
        }
        )
        require(runtime.packageVersion == "4.15.3.0" && runtime.checkerVersion == "4.15.3")
        require(!runtime.globalInstallationPerformed)
        if (darwin) {
            require(native.mappingKind == "dyld-images-v1")
            val version = requireNotNull(native.systemVersion).split('.').map { it.toInt() }
            require(version.size in 2..3 && version[0] >= 13 && version.all { it >= 0 })
            val wheel = requireNotNull(macWheels[runtime.machine])
            require(runtime.wheelBytes == wheel.first && runtime.wheelSha256 == wheel.second &&
                runtime.wheelUrl == wheel.third)
            require(runtime.libc == listOf("", ""))
        } else {
            require(native.mappingKind == "linux-proc-maps-v1" && native.systemVersion == null)
            require(runtime.machine == "x86_64")
            require(runtime.wheelBytes == 29_056_003L && runtime.wheelSha256 == WHEEL_SHA &&
                runtime.wheelUrl == WHEEL_URL)
            require(runtime.libc.size == 2 && runtime.libc[0] == "glibc")
            val libc = runtime.libc[1].split('.').map { it.toInt() }
            require(libc.size >= 2 && (libc[0] > 2 || (libc[0] == 2 && libc[1] >= 17)))
        }
        require(digest.matches(runtime.metadataSha256) && digest.matches(runtime.pythonExecutableSha256))
        val python = runtime.pythonVersion.split('.').map { it.toInt() }
        require(python.size == 3 && (python[0] > 3 || (python[0] == 3 && python[1] >= 9)))
        require(runtime.pythonExecutable.startsWith('/'))
        val files = runtime.extractedFiles
        require(files.size in 1..512 && files.map { it.path }.distinct().size == files.size)
        require(
            files.all { file ->
            file.path.isNotEmpty() && !file.path.startsWith('/') && '\\' !in file.path &&
                file.path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
                file.bytes in 0..100_663_296L && digest.matches(file.sha256)
        }
        )
        require(files.sumOf { it.bytes } <= 134_217_728L)
        require(files.any { it.path == "z3/__init__.py" })
        require(files.any { it.path == if (darwin) "z3/lib/libz3.dylib" else "z3/lib/libz3.so" })
        val bootstrap = requireNotNull(receipt.bootstrapProcess)
        val check = requireNotNull(receipt.checkProcess)
        val logs = requireNotNull(receipt.processLogs)
        require(logs.keys == setOf("bootstrap.stdout", "bootstrap.stderr", "check.stdout", "check.stderr"))
        val ground = receipt.scope == SmtSourceValidation.GROUND
        require(receipt.scope in setOf(SmtSourceValidation.GROUND, SmtSourceValidation.REFUTATION))
        requireProcess(
            bootstrap,
            logs,
            "bootstrap",
            "smt_witness_validation.py",
            runtime.pythonExecutable,
            2_147_483_648L,
            30,
        )
        requireProcess(
            check,
            logs,
            "check",
            "smt_source_validation.py",
            runtime.pythonExecutable,
            if (ground) 4_294_967_296L else 8_589_934_592L,
            if (ground) 30 else 40,
        )
        require(bootstrap.cwd == check.cwd && bootstrap.command.takeLast(2) == check.command.takeLast(2))
        require(receipt.setupMs >= bootstrap.processWallNs / 1_000_000 && receipt.setupMs <= 40_000)
        require(
            receipt.checkMs >= check.processWallNs / 1_000_000 && receipt.checkMs <= (if (ground) 40_000 else 50_000),
        )
        require(receipt.setupMs + receipt.checkMs <= (if (ground) 70_000 else 100_000))
        val child = Reports.json.parseToJsonElement(logs.getValue("check.stdout")).jsonObject
        val fields = setOf(
            "status", "reason", "scope", "bindings", "solverSearchQueries",
            "proofCertificateChecked", "checkedWitness", "checker", "groundArithmetic", "nativeRuntime",
            "referenceComparison", "solverQueryCountObserved", "referenceLogic", "sourceDerivation",
        )
        require(child.keys == fields && fields.all { child[it] == json[it] })
        receipt.sourceDerivation?.let { requireDerivation(it) }
        val library = native.library
        val relative = library.path.removePrefix(check.command[5] + "/")
        val libraries = if (darwin) setOf("z3/lib/libz3.dylib", "z3/lib/libz3.4.15.dylib") else setOf("z3/lib/libz3.so")
        require(relative in libraries)
        require(library.path == check.command[5] + "/" + relative)
        val extracted = files.single { it.path == relative }
        require(library.bytes == extracted.bytes && library.sha256 == extracted.sha256)
        require(library.bytes in 1..100_663_296L && digest.matches(library.sha256))
        val providers = MpsBuiltinModule(
            "built-in",
            "_frozen_importlib.BuiltinImporter",
            native.executablePath,
            native.maps,
            native.mapsSha256,
            native.pythonProviders,
        )
        if (darwin) {
            requireDarwinPython(providers, arithmetic, runtime)
            val nativePaths = darwinImagePaths(native.maps, native.mapsSha256, runtime.machine)
                .filter { "libz3" in it.substringAfterLast('/') }
            require(nativePaths == listOf(library.path))
        } else {
            requireBuiltinMath(providers, arithmetic, runtime)
            val nativeLines = native.maps.removeSuffix("\n").split('\n')
                .map { it.trimStart().split(Regex("\\s+"), limit = 6) }
                .filter { it.size == 6 && "libz3" in it[5].substringAfterLast('/') }
            require(nativeLines.isNotEmpty() && nativeLines.all {
                it[5] == library.path && it[4].any { c -> c != '0' }
            })
            require(nativeLines.any { 'x' in it[1] })
        }
    }

    private fun darwinImagePaths(maps: String, hash: String, machine: String): List<String> {
        val bytes = maps.toByteArray()
        require(bytes.size in 1..262_144 && sha256(bytes) == hash && maps.endsWith('\n') && '\r' !in maps)
        val lines = maps.removeSuffix("\n").split('\n')
        require(lines.size in 1..4096)
        val cpu = when (machine) {
            "arm64" -> 0x100000cL
            "x86_64" -> 0x1000007L
            else -> error("unsupported CPU")
        }
        val addresses = mutableSetOf<String>()
        return lines.map { line ->
            val row = Reports.json.parseToJsonElement(line).jsonObject
            require(row.keys == setOf("path", "address", "magic", "cpuType"))
            require(row.getValue("magic").jsonPrimitive.content == "feedfacf")
            val cpuType = row.getValue("cpuType").jsonPrimitive
            require(!cpuType.isString && cpuType.content.toLong() == cpu)
            val address = row.getValue("address").jsonPrimitive.content
            require(Regex("[0-9a-f]{1,16}").matches(address) && address.toULong(16) > 0uL && addresses.add(address))
            row.getValue("path").jsonPrimitive.content.also { path ->
                require(path.length in 1..4096 && path.startsWith('/') && path.none { it == '\n' || it == '\r' })
            }
        }
    }

    private fun requireDarwinPython(
        builtin: MpsBuiltinModule,
        arithmetic: MpsGroundArithmetic,
        runtime: MpsCheckerRuntime,
    ) {
        require(builtin.origin == "built-in" && builtin.loader == "_frozen_importlib.BuiltinImporter")
        val paths = darwinImagePaths(builtin.maps, builtin.mapsSha256, runtime.machine)
        val executable = builtin.executablePath
        require(paths.first() == executable)
        require(executable.startsWith('/') && executable.none { it.isWhitespace() || it == '\\' })
        val version = Regex.escape(runtime.pythonVersion.split('.').take(2).joinToString("."))
        val selected = paths.filter { path ->
            val name = path.substringAfterLast('/')
            val python = name.startsWith("libpython") || (name == "Python" && "/Python.framework/" in path)
            if (python && path != executable) {
                require(Regex("libpython$version[dmt]*\\.dylib").matches(name) ||
                    Regex("/Python\\.framework/Versions/$version[dmt]*/Python$").containsMatchIn(path))
            }
            path == executable || python
        }.toSet()
        val providers = builtin.providers
        require(selected.size in 1..3 && providers.size == selected.size &&
            providers.map { it.path }.toSet() == selected)
        require(providers.all { it.bytes in 1..67_108_864L && digest.matches(it.sha256) })
        require(providers.sumOf { it.bytes } <= 201_326_592L)
        require(providers.single { it.path == executable }.sha256 == arithmetic.pythonExecutableSha256)
    }

    private fun requireDerivation(derivation: SmtSourceDerivation) {
        require(derivation.kind == "source-polynomial-enclosure-v1")
        require(derivation.method in setOf("empty-source-bounds", "source-signs", "source-interval"))
        require(derivation.assertions.isNotEmpty() && derivation.assertions.all { it >= 0 })
        require(derivation.assertions == derivation.assertions.distinct().sorted())
        require(derivation.contradictionAssertion in derivation.assertions)
        require(derivation.relation in setOf("eq", "ne", "lt", "le", "gt", "ge"))
        require(digest.matches(derivation.expressionSha256))
        val rational = Regex("(?:0|-?[1-9][0-9]*)(?:/[1-9][0-9]*)?")
        for ((name, bound) in derivation.bounds) {
            require(name.isNotEmpty())
            require(bound.lower != null || bound.upper != null)
            require(!bound.lowerOpen || bound.lower != null)
            require(!bound.upperOpen || bound.upper != null)
            for (value in listOfNotNull(bound.lower, bound.upper)) {
                require(value.length <= 65_536 && rational.matches(value))
            }
        }
    }

    private fun requireBuiltinMath(
        builtin: MpsBuiltinModule,
        arithmetic: MpsGroundArithmetic,
        runtime: MpsCheckerRuntime,
    ) {
        require(builtin.origin == "built-in" && builtin.loader == "_frozen_importlib.BuiltinImporter")
        val raw = builtin.maps.toByteArray()
        require(raw.size in 1..262_144 && sha256(raw) == builtin.mapsSha256)
        val executable = builtin.executablePath
        require(executable.startsWith('/') && executable.none { it.isWhitespace() || it == '\\' })
        require(builtin.maps.endsWith('\n') && '\r' !in builtin.maps)
        val lines = builtin.maps.removeSuffix("\n").split('\n')
        require(lines.size in 1..4096)
        val paths = mutableMapOf(executable to false)
        val version = runtime.pythonVersion.split('.').take(2)
        for (line in lines) {
            val fields = line.trimStart().split(Regex("\\s+"), limit = 6)
            require(fields.size in 5..6)
            require(Regex("[0-9a-f]{1,16}-[0-9a-f]{1,16}").matches(fields[0]))
            val interval = fields[0].split('-').map { it.toULong(16) }
            require(interval[0] < interval[1])
            require(Regex("[r-][w-][x-][ps]").matches(fields[1]))
            require(Regex("[0-9a-f]{1,16}").matches(fields[2]))
            require(Regex("[0-9a-f]{1,8}:[0-9a-f]{1,8}").matches(fields[3]))
            require(Regex("[0-9]{1,20}").matches(fields[4]))
            if (fields.size == 5) continue
            val path = fields[5]
            val name = path.substringAfterLast('/')
            if (path == executable || path.startsWith("$executable ") || name.startsWith("libpython")) {
                require(path.startsWith('/') && path.none { it.isWhitespace() || it == '\\' })
                require(fields[4].any { it != '0' })
                if (name.startsWith("libpython")) {
                    val match = requireNotNull(
                        Regex(
                        "libpython([0-9]+)(?:\\.([0-9]+))?[dmt]*" +
                            "\\.so(?:\\.[0-9]+)*",
                    ).matchEntire(name)
                    )
                    require(match.groupValues[1] == version[0])
                    require(match.groupValues[2].isEmpty() || match.groupValues[2] == version[1])
                }
                paths[path] = paths.getOrDefault(path, false) || 'x' in fields[1]
            }
        }
        require(paths.size in 1..3 && paths.values.all { it })
        val providers = builtin.providers
        require(providers.size == paths.size && providers.map { it.path }.toSet() == paths.keys)
        require(providers.all { it.bytes in 1..67_108_864L && digest.matches(it.sha256) })
        require(providers.sumOf { it.bytes } <= 201_326_592L)
        require(providers.single { it.path == executable }.sha256 == arithmetic.pythonExecutableSha256)
    }

    private fun requireProcess(
        process: MpsCheckerProcess,
        logs: Map<String, String>,
        mode: String,
        script: String,
        python: String,
        memory: Long,
        timeout: Int,
    ) {
        require(process.exit == 0 && process.complete && !process.timedOut && !process.overflow)
        require(process.parentStop == null && process.addressSpaceBytes == memory && process.timeoutSeconds == timeout)
        require(process.processWallNs in 1..((timeout + 10L) * 1_000_000_000L))
        val command = process.command
        require(command.size == 6 && command[0] == python && command[1] == "-I" && command[3] == mode)
        require(command[2] == command[4] + "/tools/" + script)
        require(command[5] == process.cwd + "/dependencies" && process.cwd.startsWith('/'))
        require(process.streams.keys == setOf("stdout", "stderr"))
        for ((name, stream) in process.streams) {
            val bytes = logs.getValue("$mode.$name").toByteArray()
            require(stream.complete && stream.error == null && stream.bytes in 0..1_048_576L)
            require(stream.observedBytes == stream.bytes && stream.bytes == bytes.size.toLong())
            require(stream.sha256 == sha256(bytes))
        }
    }
}
