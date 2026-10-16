package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object JvmCompilationCohortCosts {
    private const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 2L * 1024 * 1024 * 1024
    private const val TIMEOUT_SECONDS = 2400L

    @JvmStatic
    @Suppress("LongMethod")
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        val required = setOf("manifest", "manifest-sha256", "archive")
        val withMapping = required + setOf("mapping", "mapping-sha256")
        require(args.size == options.size && options.keys in listOf(required, withMapping))
        val expected = options.getValue("manifest-sha256")
        require(expected.matches(Regex("[0-9a-f]{64}")))
        for (name in listOf("manifest", "archive")) validatedUri(options.getValue(name))
        options["mapping"]?.let { validatedUri(it) }
        options["mapping-sha256"]?.let { require(it.matches(Regex("[0-9a-f]{64}"))) }
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            File(out, "input-origins.json").writeText(
                buildJsonObject {
                    for ((name, value) in options) put(name, value)
                }.toString() + "\n"
            )
            val temporary = Files.createTempDirectory("klause-jfr-costs-").toFile()
            try {
                val manifest = File(out, "captured-manifest.json")
                require(download(options.getValue("manifest"), manifest, MAX_MANIFEST_BYTES) == expected)
                val archive = File(temporary, "census-archive.tar.gz")
                val archiveHash = download(options.getValue("archive"), archive, MAX_ARCHIVE_BYTES)
                File(out, "downloaded-archive.sha256").writeText("$archiveHash\n")
                options["mapping"]?.let { address ->
                    val mapping = File(out, "captured-phase-mapping.json")
                    require(download(address, mapping, MAX_MANIFEST_BYTES) == options.getValue("mapping-sha256"))
                }
                review(out, manifest, options, archive, temporary)
            } finally {
                check(temporary.deleteRecursively()) { "Cannot remove owned saved-census input staging" }
            }
        }
    }

    private fun download(address: String, output: File, limit: Long): String {
        val connection = validatedUri(address).toURL().openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 20_000
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        connection.getInputStream().use { source ->
            output.outputStream().use { target ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= limit) { "Saved JFR cohort download exceeds bounds" }
                    digest.update(buffer, 0, count)
                    target.write(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun validatedUri(address: String): URI {
        require(address.length <= 4096)
        val uri = URI(address)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        return uri
    }

    private fun review(out: File, manifest: File, options: Map<String, String>, archive: File, temporary: File) {
        val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
        val names = listOf(
            "report_jvm_compilation_cohort.py", "review_jvm_compilation_cohort.py", "jvm_compilation_events.py",
            "jvm_compilation_mapping.py",
            "jvm_recording_context.py", "read_jvm_class_origins.py",
            "jvm_method_costs.py",
            "review_jvm_task_log_cohort.py", "review_jvm_task_log.py", "stream_jvm_task_log.py",
            "review_native_vm_log_identities.py", "review_native_vm_live_arguments.py", "native_vm_event_loss.py",
        )
        for (name in names) File(root, "klause-bench/tools/$name").copyTo(File(out, name))
        val runtime = File(System.getProperty("java.home")).canonicalFile
        val bench = File(requireNotNull(System.getenv("KLAUSE_BENCH"))).canonicalFile
        val provenance = File(out, "observer-build-provenance.json")
        InstalledBuild.writeManifest(provenance, bench, runtime)
        File(out, "observer-runtime.json").writeText(
            buildJsonObject {
                put("javaHome", System.getProperty("java.home"))
                put("runtimeVersion", System.getProperty("java.runtime.version"))
                put("vendor", System.getProperty("java.vendor"))
                put(
                    "scope",
                    "Saved-census observer runtime; original CLI source/runtime/control identities remain historical",
                )
            }.toString() + "\n"
        )
        val command = listOf(
            "python3",
            File(root, "klause-bench/tools/report_jvm_compilation_cohort.py").path,
            manifest.path,
            options.getValue("manifest-sha256"),
            archive.path,
            out.path,
            temporary.path,
        ) + if ("mapping" in options) {
            listOf(File(out, "captured-phase-mapping.json").path, options.getValue("mapping-sha256"))
        } else {
            emptyList()
        }
        val process = ProcessBuilder(command).directory(root)
            .redirectOutput(File(out, "compilation-costs.stdout"))
            .redirectError(File(out, "compilation-costs.stderr")).start()
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly().waitFor()
        }
        File(out, "compilation-costs.exit").writeText("${process.exitValue()}\n")
        check(finished && process.exitValue() == 0) { "Saved JFR cohort cost comparison failed; evidence retained" }
        InstalledBuild.readManifest(provenance, bench, runtime, emptyMap())
    }
}
