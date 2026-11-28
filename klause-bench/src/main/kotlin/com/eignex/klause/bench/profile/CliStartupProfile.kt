package com.eignex.klause.bench.profile

import java.io.File
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.util.concurrent.TimeUnit

/** Measures the installed JVM launcher and repeated entry-point calls with matched CLI arguments. */
object CliStartupProfile {
    private const val MAIN = "com.eignex.klause.cli.MainKt"
    private const val CLI = "com.eignex.klause.cli"
    private const val PIPELINE = "com.eignex.klause.solver.pipeline"
    private const val KOBLAS_BACKEND = "com.eignex.koblas.internal.jvm.JvmBackend"
    private val runtime = listOf("-Xmx2048m", "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC")
    private val methods = listOf(
        "$MAIN::<clinit>", "$MAIN::run", "$CLI.PlatformKt::installCliConfig", "$CLI.CliModeKt::parseArgs",
        "$CLI.DimacsMode\$Session::load", "$CLI.MpsMode\$Session::load", "$CLI.SmtLibMode\$Session::runCommands",
        "$CLI.SmtLibMode\$Session::loadSource", "$CLI.CliLogKt::cliLogger",
        "$CLI.SolveCore::solve", "$PIPELINE.SourceSetup::selectRoute", "$PIPELINE.SourceExecutionKt::execute",
        "$PIPELINE.FinitePipeline::prepare", "$PIPELINE.NativeSourceExecutionKt::executeNativeSource",
        "com.eignex.klause.arithmetic.quadratic.QuadraticCandidates::proposeActiveRows",
        "com.eignex.koblas.Koblas__KoblasEngineKt::<clinit>", "$KOBLAS_BACKEND::getDefault",
        "$KOBLAS_BACKEND::getSimd", "$KOBLAS_BACKEND::open",
    )

    /** Pass a repeat count and one or more vendored fixture paths relative to the repository. */
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "warm") {
            invokeRepeatedly(args.drop(1))
            return
        }
        require(args.size >= 2) { "Expected a repeat count and one or more input paths" }
        val repeats = args.first().toInt().also { require(it in 2..100) }
        val root = File(System.getenv("LAB_WORKTREE") ?: ".").canonicalFile
        val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
        val dist = root.resolve("klause-cli/build/install/klause-cli-jvm")
        val script = dist.resolve("bin/klause-cli")
        val launcherDefaults = script.useLines { lines -> lines.first { it.startsWith("DEFAULT_JVM_OPTS=") } }
        val defaults = Regex("\"([^\"]+)\"").findAll(launcherDefaults).map { it.groupValues[1] }.toList()
        require("-XX:TieredStopAtLevel=1" in defaults) { launcherDefaults }
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val helper = File(javaClass.protectionDomain.codeSource.location.toURI()).canonicalPath
        val launcherClasspath = script.useLines { lines -> lines.first { it.startsWith("CLASSPATH=") } }
            .substringAfter('=').replace("\$APP_HOME", dist.absolutePath)
        val classpath = launcherClasspath + File.pathSeparator + helper
        val launcher = listOf(script.absolutePath)

        for (iteration in 1..repeats) {
            runProcess(launcher + "--version", runtime, root, out.resolve("version-$iteration"))
        }
        for (path in args.drop(1)) {
            val input = root.resolve(path).canonicalFile.also { require(it.isFile) { it } }
            val directory = out.resolve(input.name).also { check(it.mkdir()) { it } }
            val options = listOf("--exact", "-p", "1", "-r", "1", "-t", "30000", "--param", "node-limit=100000",
                input.absolutePath)
            for (iteration in 1..repeats) {
                runProcess(launcher + options, runtime, root, directory.resolve("fresh-$iteration"))
            }
            runProcess(
                listOf(java) + defaults + runtime + listOf("-cp", classpath, javaClass.name, "warm",
                    repeats.toString(), directory.absolutePath) + options,
                emptyList(), root, directory.resolve("warm"),
            )
            val expected = directory.resolve("fresh-1.out").readBytes()
            for (iteration in 1..repeats) {
                check(expected.contentEquals(directory.resolve("fresh-$iteration.out").readBytes())) { input }
                check(expected.contentEquals(directory.resolve("warm-$iteration.out").readBytes())) { input }
            }
            val recording = directory.resolve("phases.jfr")
            val phaseRecording = "-XX:StartFlightRecording=filename=$recording," +
                "settings=profile,jdk.Compilation#threshold=0ms,jdk.ClassLoad#enabled=true," +
                "jdk.ClassLoad#threshold=0ms,jdk.ExecutionSample#period=1ms,jdk.MethodTrace#enabled=true," +
                "jdk.MethodTrace#threshold=0ms,jdk.MethodTrace#stackTrace=false," +
                "jdk.MethodTrace#filter=${methods.joinToString(";")},dumponexit=true"
            runProcess(launcher + options, runtime + listOf("-Xlog:jfr+startup=off", phaseRecording),
                root, directory.resolve("phases"))
            check(recording.length() > 0) { "Missing phase recording: $recording" }
            val classLog = "-Xlog:class+init=info,class+load=info:file=${directory.resolve("classes.log")}:" +
                "uptimenanos,tid,tags"
            runProcess(launcher + options, runtime + classLog, root, directory.resolve("classes"))
        }
    }

    private fun invokeRepeatedly(args: List<String>) {
        val repeats = args[0].toInt()
        val directory = File(args[1])
        val options = args.drop(2).toTypedArray()
        val entry = Class.forName(MAIN, false, javaClass.classLoader).getMethod("main", Array<String>::class.java)
        val clock = ManagementFactory.getThreadMXBean()
        val original = System.out
        directory.resolve("warm.tsv").printWriter().use { timings ->
            timings.println("invocation\twallNs\tthreadCpuNs")
            for (iteration in 1..repeats) {
                PrintStream(directory.resolve("warm-$iteration.out"), Charsets.UTF_8).use { output ->
                    System.setOut(output)
                    try {
                        val cpu = clock.currentThreadCpuTime
                        val start = System.nanoTime()
                        entry.invoke(null, options)
                        val wall = System.nanoTime() - start
                        timings.println("$iteration\t$wall\t${clock.currentThreadCpuTime - cpu}")
                    } finally {
                        System.setOut(original)
                    }
                }
            }
        }
    }

    private fun runProcess(command: List<String>, options: List<String>, root: File, artifact: File) {
        val timed = listOf("/usr/bin/time", "-f", "%e\t%U\t%S\t%M", "-o", "$artifact.time")
        val process = ProcessBuilder(timed + command).directory(root)
            .redirectOutput(File("$artifact.out")).redirectError(File("$artifact.err"))
            .apply {
                environment()["KLAUSE_DEBUG"] = "false"
                val arguments = File("$artifact.args").apply {
                    writeText(options.joinToString("\n", postfix = "\n") {
                        "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                    })
                }
                environment()["KLAUSE_CLI_OPTS"] = "@${arguments.absolutePath}"
                environment().remove("JAVA_OPTS")
                environment().remove("JDK_JAVA_OPTIONS")
                environment().remove("JAVA_TOOL_OPTIONS")
                environment().remove("_JAVA_OPTIONS")
                environment()["JAVA_HOME"] = System.getProperty("java.home")
            }.start()
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            error("Child exceeded whole-process allowance: $artifact")
        }
        check(process.exitValue() == 0) { "$artifact exited ${process.exitValue()}" }
    }
}
