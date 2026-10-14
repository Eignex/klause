package com.eignex.klause.bench.profile

import com.eignex.klause.bench.report.Reports
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Capture compiler-generated published API declarations from a pinned remote checkout. */
object PublishedAbiSnapshot {
    /** Update the checkout's reference declarations and retain their exact bytes and source identity. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size <= 1 && args.all { it.startsWith("out=") })
        val out = File(args.singleOrNull()?.substringAfter('=') ?: requireNotNull(System.getenv("OUT")))
        require(out.mkdirs() || out.isDirectory)
        val workspace = File(requireNotNull(System.getProperty("klause.workspace.root")))
        val process = ProcessBuilder(File(workspace, "gradlew").absolutePath, ":klause:updateKotlinAbi")
            .directory(workspace).redirectErrorStream(true).redirectOutput(out.resolve("abi-generation.log")).start()
        try {
            check(process.waitFor(15, TimeUnit.MINUTES)) { "ABI generation timed out" }
            check(process.exitValue() == 0) { "ABI generation failed" }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        val provenance = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("task", ":klause:updateKotlinAbi")
            put("javaVersion", System.getProperty("java.version"))
            for (name in listOf("klause.api", "klause.klib.api")) {
                val bytes = workspace.resolve("klause/api/$name").readBytes()
                out.resolve(name).writeBytes(bytes)
                put(name, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(Locale.ROOT, it)
                })
            }
        }
        out.resolve("abi-provenance.json").writeText(provenance.toString() + "\n")
        println("ABI_SNAPSHOT revision=${Reports.readGitSha()} output=$out")
    }
}
