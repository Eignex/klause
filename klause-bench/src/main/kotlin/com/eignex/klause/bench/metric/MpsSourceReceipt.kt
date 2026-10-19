package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
internal data class MpsCheckerFile(val path: String, val bytes: Long, val sha256: String)

@Serializable
internal data class MpsCheckerRuntime(
    val pythonVersion: String,
    val pythonExecutable: String,
    val pythonExecutableSha256: String,
    val machine: String,
    val libc: List<String>,
    val packageVersion: String,
    val checkerVersion: String,
    val wheelBytes: Long,
    val wheelSha256: String,
    val wheelUrl: String,
    val metadataSha256: String,
    val extractedFiles: List<MpsCheckerFile>,
    val globalInstallationPerformed: Boolean,
)

@Serializable
internal data class MpsGroundArithmetic(
    val implementation: String,
    val pythonVersion: String,
    val pythonExecutableSha256: String,
    val modules: Map<String, MpsCheckerFile>,
    val builtinMath: MpsBuiltinModule? = null,
)

@Serializable
internal data class MpsBuiltinModule(
    val origin: String,
    val loader: String,
    val executablePath: String,
    val maps: String,
    val mapsSha256: String,
    val providers: List<MpsCheckerFile>,
)

@Serializable
internal data class MpsCheckerStream(
    val bytes: Long,
    val observedBytes: Long,
    val complete: Boolean,
    val sha256: String,
    val error: String? = null,
)

@Serializable
internal data class MpsCheckerProcess(
    val command: List<String>,
    val cwd: String,
    val exit: Int?,
    val timedOut: Boolean,
    val overflow: Boolean,
    val parentStop: String?,
    val streams: Map<String, MpsCheckerStream>,
    val complete: Boolean,
    val processWallNs: Long,
    val addressSpaceBytes: Long,
    val timeoutSeconds: Int,
)

internal object MpsSourceReceipt {
    private const val WHEEL_SHA = "a9afd9ceb290482097474d43f08415bcc1874f433189d1449f6c1508e9c68384"
    private const val WHEEL_URL = "https://files.pythonhosted.org/packages/" +
        "b9/0b/f15168475e5493ea44fa3c5e642903f05d2b870db71ad05662ed87a06976/" +
        "z3_solver-4.15.3.0-py3-none-manylinux_2_17_x86_64.manylinux2014_x86_64.whl"
    private val digest = Regex("[0-9a-f]{64}")

    fun requireComplete(receipt: MpsSourceValidation.Receipt, json: JsonObject, exact: Boolean, gzip: Boolean = false) {
        val runtime = requireNotNull(receipt.runtime)
        val arithmetic = requireNotNull(receipt.groundArithmetic)
        require(arithmetic.implementation == MPS_GROUND_CHECKER)
        require(arithmetic.pythonVersion == runtime.pythonVersion &&
            arithmetic.pythonExecutableSha256 == runtime.pythonExecutableSha256)
        val moduleNames = if (arithmetic.builtinMath == null) {
            setOf("fractions", "numbers", "math")
        } else {
            setOf("fractions", "numbers")
        }
        require(arithmetic.modules.keys == moduleNames)
        arithmetic.builtinMath?.let {
            requireBuiltinProvider(it, arithmetic.pythonVersion, arithmetic.pythonExecutableSha256)
        }
        require(arithmetic.modules.values.all { file ->
            file.path.startsWith('/') && file.bytes in 1..4_194_304L && digest.matches(file.sha256)
        })
        require(runtime.packageVersion == "4.15.3.0" && runtime.checkerVersion == "4.15.3")
        require(runtime.machine == "x86_64" && !runtime.globalInstallationPerformed)
        require(runtime.wheelBytes == 29_056_003L && runtime.wheelSha256 == WHEEL_SHA && runtime.wheelUrl == WHEEL_URL)
        require(digest.matches(runtime.metadataSha256) && digest.matches(runtime.pythonExecutableSha256))
        val python = runtime.pythonVersion.split('.').map { it.toInt() }
        require(python.size == 3 && (python[0] > 3 || (python[0] == 3 && python[1] >= 9)))
        require(runtime.pythonExecutable.startsWith('/'))
        require(runtime.libc.size == 2 && runtime.libc[0] == "glibc")
        val libc = runtime.libc[1].split('.').map { it.toInt() }
        require(libc.size >= 2 && (libc[0] > 2 || (libc[0] == 2 && libc[1] >= 17)))
        val files = runtime.extractedFiles
        require(files.size in 1..512 && files.map { it.path }.distinct().size == files.size)
        require(files.all { file ->
            file.path.isNotEmpty() && !file.path.startsWith('/') && '\\' !in file.path &&
                file.path.split('/').all { it.isNotEmpty() && it != "." && it != ".." } &&
                file.bytes in 0..100_663_296L && digest.matches(file.sha256)
        })
        require(files.sumOf { it.bytes } <= 134_217_728L)
        require(files.any { it.path == "z3/__init__.py" } && files.any { it.path.endsWith("/libz3.so") })
        val bootstrap = requireNotNull(receipt.bootstrapProcess)
        val check = requireNotNull(receipt.checkProcess)
        val logs = requireNotNull(receipt.processLogs)
        require(logs.keys == setOf("bootstrap.stdout", "bootstrap.stderr", "check.stdout", "check.stderr"))
        requireProcess(bootstrap, logs, "bootstrap", "smt_witness_validation.py", runtime.pythonExecutable,
            2_147_483_648L)
        requireProcess(check, logs, "check", "mps_witness_validation.py", runtime.pythonExecutable, 4_294_967_296L)
        require(bootstrap.cwd == check.cwd && bootstrap.command.takeLast(2) == check.command.takeLast(2))
        require(receipt.setupMs >= bootstrap.processWallNs / 1_000_000 && receipt.setupMs <= 40_000)
        require(receipt.checkMs >= check.processWallNs / 1_000_000 && receipt.checkMs <= 40_000)
        require(receipt.setupMs + receipt.checkMs <= 70_000)
        val child = Reports.json.parseToJsonElement(logs.getValue("check.stdout")).jsonObject
        val fields = setOf("status", "reason", "scope", "bindings", "solverSearchQueries",
            "proofCertificateChecked", "checkedWitness", "checker", "groundArithmetic") +
            (if (gzip) setOf("stdoutTransport") else emptySet()) +
            if (receipt.status == "valid") {
                setOf("exactFeasible", "toleranceAccepted", "sourceTolerance", "witnessCheck")
            } else {
                emptySet()
            }
        require(child.keys == fields && fields.all { child[it] == json[it] })
        if (receipt.status == "valid") {
            val exactFeasible = requireNotNull(receipt.exactFeasible)
            require(receipt.toleranceAccepted == (!exact && !exactFeasible) && (!exact || exactFeasible))
            val tolerance = if (exact) "0" else "1/10000000"
            require(receipt.sourceTolerance == mapOf("absolute" to tolerance, "relative" to tolerance))
            require(receipt.witnessCheck ==
                "all original MPS rows bounds integrality and objective evaluated at supplied point")
        }
    }

    fun requireBuiltinProvider(
        builtin: MpsBuiltinModule,
        pythonVersion: String,
        executableSha256: String,
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
        val version = pythonVersion.split('.').take(2)
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
                    val match = requireNotNull(Regex("libpython([0-9]+)(?:\\.([0-9]+))?[dmt]*" +
                        "\\.so(?:\\.[0-9]+)*").matchEntire(name))
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
        require(providers.single { it.path == executable }.sha256 == executableSha256)
    }

    fun requireProcess(
        process: MpsCheckerProcess,
        logs: Map<String, String>,
        mode: String,
        script: String,
        python: String,
        memory: Long,
    ) {
        require(process.exit == 0 && process.complete && !process.timedOut && !process.overflow)
        require(process.parentStop == null && process.addressSpaceBytes == memory && process.timeoutSeconds == 30)
        require(process.processWallNs in 1..40_000_000_000L)
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
