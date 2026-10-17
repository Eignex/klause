package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI

internal object JvmSavedBytecodeMetadata {
    private const val MAX_SPECIFICATION_BYTES = 16 * 1024 * 1024

    @JvmStatic
    @Suppress("LongMethod")
    fun main(args: Array<String>) {
        val options = parse(args)
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val specification = File(out, "bytecode-inputs.json")
            val runtime = File(System.getProperty("java.home"))
            val observer = File(requireNotNull(System.getenv("KLAUSE_BENCH")))
            val provenance = File(out, "observer-build-provenance.json")
            InstalledBuild.writeManifest(provenance, observer, runtime)
            val execution = runCatching {
                val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
                for (name in listOf(
                    "JvmSavedBytecodeMetadata.kt", "JvmBytecodeInputs.kt", "JvmBytecodeInventory.kt",
                    "JvmBytecodeReport.kt", "JvmBytecodeEncoding.kt", "JvmBytecodeMetadata.kt", "JvmBytecodeSymbols.kt",
                    "JvmBytecodeLayout.kt", "JvmRuntimeImage.kt", "ProfileCaptureArchive.kt",
                )) {
                    File(root, "klause-bench/src/main/kotlin/com/eignex/klause/bench/profile/$name")
                        .copyTo(File(out, name))
                }
                Reports.writeJson(File(out, "input-origins.json").path, options)
                download(options.getValue("specification"), specification, options.getValue("specification-sha256"))
                val text = specification.readBytes().decodeToString(throwOnInvalidSequence = true)
                val inputs = JvmBytecodeInputs.read(text)
                JvmBytecodeReport.write(out) { emit -> JvmBytecodeInventory.inspect(inputs, emit) }
            }
            val preservation = runCatching {
                if (specification.isFile) {
                    require(JvmBytecodeInputs.digest(specification) == options.getValue("specification-sha256"))
                }
                InstalledBuild.readManifest(provenance, observer, runtime, emptyMap())
            }
            execution.exceptionOrNull()?.let { failure -> preservation.exceptionOrNull()?.let(failure::addSuppressed) }
            val available = execution.getOrNull()?.get("allClassMetadataAvailable") == JsonPrimitive(true)
            Reports.writeJson(
                File(out, "completion.json").path,
                buildJsonObject {
                execution.getOrNull()?.forEach { (name, value) -> put(name, value) }
                put("completed", execution.isSuccess && preservation.isSuccess && available)
                put("errorType", (execution.exceptionOrNull() ?: preservation.exceptionOrNull())?.javaClass?.name)
                put("allClassMetadataAvailable", available)
                put("classFileVerificationPerformed", false)
                put("runtimeCalleeSelectionProven", false)
                put("loadedClassSelectionProven", false)
                put("generatedBodySelectionProven", false)
                put("fullSourcePhaseCoverageProven", false)
                put("compilerElapsedTimeProven", false)
                put("acceptedSavingsCredit", false)
            }
            )
            execution.getOrThrow()
            preservation.getOrThrow()
            check(available) { "Unavailable class metadata remains in the complete inventory" }
        }
    }

    internal fun parse(args: Array<String>): Map<String, String> {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == options.size && options.keys == setOf("specification", "specification-sha256"))
        require(options.getValue("specification-sha256").matches(Regex("[0-9a-f]{64}")))
        val address = options.getValue("specification")
        require(address.length <= 4096 && address.none(Char::isISOControl))
        val uri = URI(address)
        require(
            uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null
        )
        return options
    }

    private fun download(address: String, output: File, expected: String) {
        val connection = URI(address).toURL().openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 20_000
        }
        val bytes = connection.getInputStream().use { it.readNBytes(MAX_SPECIFICATION_BYTES + 1) }
        require(bytes.size <= MAX_SPECIFICATION_BYTES) { "Bytecode input specification exceeds bounds" }
        output.writeBytes(bytes)
        require(JvmBytecodeInputs.digest(output) == expected) { "Bytecode specification hash mismatch" }
    }
}
