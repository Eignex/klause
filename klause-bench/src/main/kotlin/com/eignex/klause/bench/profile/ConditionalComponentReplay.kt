package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat

/** Offline structural loading and separately requested native prepared-residual controls for lab jobs. */
object ConditionalComponentReplay {
    /** Accept `snapshot64=BASE64` or `snapshotFile=PATH`, `sha256=HEX`, and optional `replay=true`. */
    @JvmStatic
    @Suppress("LongMethod")
    fun main(args: Array<String>) {
        val options = args.associate { it.substringBefore('=') to it.substringAfter('=') }
        val known = setOf("snapshot64", "snapshotFile", "snapshotJob", "sha256", "replay", "probeMillis", "seed")
        require((options.keys - known).isEmpty()) { "unknown arguments" }
        require(options.containsKey("snapshot64") != options.containsKey("snapshotFile")) {
            "choose one snapshot input"
        }
        val bytes = options["snapshot64"]?.let {
            require(it.length <= ResidualSnapshots.MAX_BYTES * 2) { "snapshot byte limit" }
            Base64.getDecoder().decode(it)
        } ?: run {
            val path = options.getValue("snapshotFile")
            val file = options["snapshotJob"]?.let { job ->
                require(job.toLong() > 0)
                val jobs = File(requireNotNull(System.getenv("JOB_DIR"))).canonicalFile.parentFile
                jobs.resolve("$job/$path").canonicalFile.also {
                    require(it.toPath().startsWith(jobs.resolve(job).toPath())) { "foreign snapshot path" }
                }
            } ?: File(path)
            require(file.length() in 1..ResidualSnapshots.MAX_BYTES.toLong()) { "snapshot byte limit or missing input" }
            file.readBytes()
        }
        require(bytes.size <= ResidualSnapshots.MAX_BYTES)
        val digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        require(digest == options.getValue("sha256")) { "snapshot hash mismatch" }
        val started = System.nanoTime()
        val snapshot = ResidualSnapshots.load(bytes.decodeToString())
        val loadNanos = System.nanoTime() - started
        val out = File(System.getenv("OUT") ?: "build/profile-out").apply { mkdirs() }
        out.resolve("input.residual.json").writeBytes(bytes)
        val replay = options["replay"]?.toBooleanStrict() ?: false
        val millis = options["probeMillis"]?.toLong() ?: 2_000L
        val seed = options["seed"]?.toLong() ?: 3L
        val report = buildJsonObject {
            put("revision", Reports.readGitSha())
            put("snapshotSha256", digest)
            put("snapshotBytes", bytes.size)
            put("loadAndVerifyNs", loadNanos)
            put("conditionIdentity", snapshot.conditionIdentity)
            put("originalSourceReplay", snapshot.originalSourceReplay)
            put("sourceVerdictClaim", false)
            put("javaVersion", System.getProperty("java.version"))
            put("osName", System.getProperty("os.name"))
            put("osArch", System.getProperty("os.arch"))
            put("maxMemory", Runtime.getRuntime().maxMemory())
            put("processors", Runtime.getRuntime().availableProcessors())
            put("provenance", runCatching { InstalledBuild.current.fingerprint }.getOrElse { "unavailable: $it" })
            if (replay) {
                put("source", ResidualReplayControls.replay(snapshot, false, millis, seed))
                put("enforcing", ResidualReplayControls.replay(snapshot, true, millis, seed))
            }
        }
        out.resolve("replay.json").writeText(report.toString())
        println(report)
    }
}
