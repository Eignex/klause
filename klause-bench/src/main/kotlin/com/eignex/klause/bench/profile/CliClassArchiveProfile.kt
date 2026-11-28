package com.eignex.klause.bench.profile

import java.io.File

/** Paired installed-launcher measurements with one JVM class archive trained on the same worker. */
object CliClassArchiveProfile {
    private const val DEFAULT_SEED = 1L
    private const val COLD_REPEATS = 11
    private const val WARM_REPEATS = 7
    private const val WARM_CALLS = 100

    /** Arguments select the archive format, a training fixture and measurement fixtures. */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 3)
        val root = File(requireNotNull(System.getenv("LAB_WORKTREE"))).canonicalFile
        val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile
        val trainOnHelp = args[1] == "--help"
        val train = root.resolve(if (trainOnHelp) args[2] else args[1]).also { require(it.isFile) }
        val training = out.resolve("training").apply { mkdirs() }
        val format = args[0]
        val archive = training.resolve(if (format == "dynamic") "cli.jsa" else "cli.aot")
        val linking = if (format == "aot-unlinked") listOf("-XX:-AOTClassLinking") else emptyList()
        val create = when (format) {
            "dynamic" -> "-XX:ArchiveClassesAtExit=$archive"
            "aot", "aot-unlinked" -> "-XX:AOTCacheOutput=$archive"
            else -> error("Unknown archive format: $format")
        }
        val use = if (format == "dynamic") "-XX:SharedArchiveFile=$archive" else "-XX:AOTCache=$archive"
        val cacheOptions = linking + listOf(use, "-Xlog:aot*=off:stdout", "-Xlog:aot*=warning:stderr")
        val force = if (format == "dynamic") "-Xshare:on" else "-XX:AOTMode=on"
        val trainingOptions = linking + create
        val trainingWall = if (trainOnHelp) {
            CliStartupProfile.captureHelp(root, training, trainingOptions)
        } else {
            CliStartupProfile.captureFresh(root, train, training, 1, DEFAULT_SEED, trainingOptions).single()
        }
        check(archive.length() > 0) { "Archive was not created: $archive" }
        training.resolve("identity.tsv").writeText(
            "format\tbytes\ttrainingWallNs\tjavaRuntime\n" +
                "$format\t${archive.length()}\t$trainingWall\t${System.getProperty("java.runtime.version")}\n",
        )
        val preflight = out.resolve("preflight").apply { mkdirs() }
        val loading = preflight.resolve("class-load.log")
        CliStartupProfile.captureFresh(root, train, preflight, 1, DEFAULT_SEED,
            cacheOptions + listOf(force, "-Xlog:class+load=info:file=$loading"))
        check(loading.useLines { lines ->
            lines.any { "com.eignex.klause.cli.MainKt source: shared objects file" in it }
        }) { "The application did not use the archive" }

        for (path in args.drop(2)) {
            val input = root.resolve(path).also { require(it.isFile) }
            val fixture = out.resolve(input.name).apply { check(mkdir()) }
            val protocol = preflightOutput(input, root, fixture)
            val stderr = fixture.resolve("control/fresh-1.err").readBytes()
            fixture.resolve("cold.tsv").printWriter().use { timings ->
                timings.println("repeat\tarm\twallNs\tcpuMs")
                for (repeat in 1..COLD_REPEATS) for ((arm, options) in arms(repeat, cacheOptions)) {
                    val directory = fixture.resolve("cold-$repeat-$arm").apply { check(mkdir()) }
                    val wall = CliStartupProfile.captureFresh(root, input, directory, 1, DEFAULT_SEED, options).single()
                    val cpu = directory.resolve("fresh-1.time").readText().trim().split('\t')
                        .drop(1).take(2).sumOf(String::toDouble) * 1000.0
                    check(protocol.contentEquals(directory.resolve("fresh-1.out").readBytes())) { directory }
                    check(stderr.contentEquals(directory.resolve("fresh-1.err").readBytes())) { directory }
                    timings.println("$repeat\t$arm\t$wall\t$cpu")
                }
            }
            for (repeat in 1..WARM_REPEATS) for ((arm, options) in arms(repeat, cacheOptions)) {
                val directory = fixture.resolve("warm-$repeat-$arm").apply { check(mkdir()) }
                CliStartupProfile.captureWarm(root, input, directory, WARM_CALLS, DEFAULT_SEED, options)
                check(stderr.contentEquals(directory.resolve("warm.err").readBytes())) { directory }
                for (call in 1..WARM_CALLS) {
                    val output = directory.resolve("warm-$call.out")
                    check(protocol.contentEquals(output.readBytes())) { output }
                    if (call != 1 && call != WARM_CALLS) check(output.delete())
                }
            }
        }
    }

    private fun preflightOutput(input: File, root: File, fixture: File): ByteArray {
        val directory = fixture.resolve("control").apply { check(mkdir()) }
        CliStartupProfile.captureFresh(root, input, directory, 1, DEFAULT_SEED)
        return directory.resolve("fresh-1.out").readBytes()
    }

    private fun arms(repeat: Int, options: List<String>): List<Pair<String, List<String>>> {
        val arms = listOf("base" to emptyList<String>(), "archive" to options)
        return if (repeat % 2 == 1) arms else arms.reversed()
    }
}
