package com.eignex.klause.bench.profile

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

internal object NativeVmDwarfRecordReview {
    private const val MAX_REPORT_BYTES = 8 * 1024 * 1024
    private const val TIMEOUT_SECONDS = 60L

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(args.size == 3 && options.keys == setOf("records", "records-sha256", "producer-sha256"))
        val uri = URI(requireNotNull(options["records"]))
        require(uri.scheme == "https" && uri.host != null && uri.userInfo == null)
        for (key in listOf("records-sha256", "producer-sha256")) {
            require(requireNotNull(options[key]).matches(Regex("[0-9a-f]{64}")))
        }
        ProfileCaptureArchive.run {
            val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
            val connection = uri.toURL().openConnection().apply {
                connectTimeout = 20_000
                readTimeout = 20_000
            }
            val raw = connection.getInputStream().use { it.readNBytes(MAX_REPORT_BYTES + 1) }
            val records = File(out, "captured-vm-dwarf-records.json").apply { writeBytes(raw) }
            require(raw.size <= MAX_REPORT_BYTES) { "Captured report exceeds diagnostic bounds" }
            val actual = MessageDigest.getInstance("SHA-256").digest(raw)
                .joinToString("") { "%02x".format(Locale.ROOT, it) }
            require(actual == options["records-sha256"]) { "Captured report hash differs" }
            val root = File(requireNotNull(System.getProperty("klause.workspace.root")))
            val tool = "review_native_vm_dwarf_records.py"
            File(root, "klause-bench/tools/$tool").copyTo(File(out, tool))
            val process = ProcessBuilder(
                "python3", File(root, "klause-bench/tools/$tool").path, records.path,
                requireNotNull(options["records-sha256"]), requireNotNull(options["producer-sha256"]),
                File(out, "vm-dwarf-record-review.json").path,
            ).directory(root).redirectOutput(File(out, "dwarf-record-review.stdout"))
                .redirectError(File(out, "dwarf-record-review.stderr")).start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            File(out, "dwarf-record-review.exit").writeText("${process.exitValue()}\n")
            check(finished) { "Saved VM DWARF review timed out; metadata retained" }
            check(process.exitValue() == 0) { "Saved VM DWARF review failed; metadata retained" }
        }
    }
}
