package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object OriginalFixtureAllocationStudy {
    private const val TEST_SOURCE =
        "klause/src/commonTest/kotlin/com/eignex/klause/factor/global/AllDifferentPropagatorTest.kt"
    private const val FIXTURE_CLASS =
        "com/eignex/klause/factor/global/AllDifferentPropagatorTest.class"
    private const val MATCHER_CLASS = "com/eignex/klause/factor/global/internals/ReginMatcherKt.class"
    private const val MAX_CLASSPATH_BYTES = 512L * 1024 * 1024
    private const val MAX_OUTPUT_BYTES = 1024L * 1024
    private val json = Json

    @JvmStatic
    @Suppress("LongMethod", "TooGenericExceptionCaught")
    fun main(args: Array<String>) = ProfileCaptureArchive.run {
        val heads = args.associate {
            require('=' in it)
            it.substringBefore('=') to it.substringAfter('=')
        }
        require(heads.size == args.size)
        CompilationStudyBuilds.validateHeads(heads)
        val root = File(requireNotNull(System.getProperty("klause.workspace.root"))).canonicalFile
        val out = File(requireNotNull(System.getenv("OUT"))).canonicalFile.apply { mkdirs() }
        val cases = mutableListOf<OriginalAllocationCase>()
        val plan = OriginalFixtureAllocationProtocol.fixtures.flatMap { fixture ->
            (0 until OriginalFixtureAllocationProtocol.PAIRS).flatMap { pair ->
                OriginalFixtureAllocationProtocol.arms(pair).map { arm ->
                    OriginalAllocationPlan(fixture.key, pair, arm)
                }
            }
        }
        Reports.writeJson(File(out, "allocation-request.json").path, heads)
        Reports.writeJson(File(out, "allocation-plan.json").path, plan)
        Reports.writeJson(File(out, "allocation-fixtures.json").path, OriginalFixtureAllocationProtocol.fixtures)
        var failure: String? = null
        try {
            CompilationStudyBuilds(root, out).use { workspace ->
                val builds = workspace.prepare(heads).mapValues { (arm, build) ->
                    prepare(root, out, heads.getValue(arm), build)
                }
                for (request in plan) {
                    val fixture = OriginalFixtureAllocationProtocol.fixtures.single { it.key == request.fixture }
                    val build = builds.getValue(request.arm)
                    verify(build)
                    val observation = measure(out, build, fixture, request.pair)
                    cases += observation
                    Reports.writeJson(File(out, "allocation-cases.json").path, cases)
                    verify(build)
                    cases[cases.lastIndex] = observation.copy(retainedInputsVerifiedAfter = true,
                        completed = observation.originalInvocationCompleted)
                    Reports.writeJson(File(out, "allocation-cases.json").path, cases)
                }
                check(cases.size == 12 && cases.all { it.completed }) { "Original fixture invocations incomplete" }
            }
        } catch (error: Exception) {
            failure = error.toString()
            throw error
        } finally {
            Reports.writeJson(File(out, "allocation-paired-rounds.json").path, pairedRounds(cases))
            Reports.writeJson(File(out, "allocation-completion.json").path,
                OriginalAllocationCompletion(cases.size, failure,
                    failure == null && cases.size == 12 && cases.all { it.completed }))
        }
    }

    @Suppress("LongMethod")
    private fun prepare(root: File, out: File, head: String, build: CompilationStudyBuild): FixtureBuild {
        val source = File(build.root, TEST_SOURCE).readText()
        val bodies = OriginalFixtureAllocationProtocol.fixtures.associate { fixture ->
            fixture.key to OriginalFixtureAllocationProtocol.sourceBody(source, fixture)
        }
        Reports.writeJson(File(out, "${build.arm}-original-fixture-sources.json").path,
            OriginalFixtureSources(head, TEST_SOURCE, sha256(source.toByteArray()), bodies))
        val initialization = File(out, "fixture-classpath.init.gradle")
        if (!initialization.exists()) {
            File(root, "klause-bench/compilation-study/fixture-classpath.init.gradle").copyTo(initialization)
        }
        val scriptIdentity = CompilationStudyDistributions.identity(initialization)
        val classpathFile = File(out, "${build.arm}-fixture-classpath.json")
        CompilationStudyBuilds.command(build.root, out, "${build.arm}-fixture-build",
            listOf("./gradlew", ":klause:writeOriginalFixtureClasspath",
                "-Pklause.fixture.classpath=${classpathFile.path}", "--init-script",
                File(out, "jvm-only.init.gradle").path, "--init-script", initialization.path,
                "--max-workers=2", "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process"))
        check(CompilationStudyDistributions.identity(initialization) == scriptIdentity)
        check(CompilationStudyBuilds.command(build.root, out, "${build.arm}-clean-fixture-after",
            listOf("git", "status", "--porcelain")).isBlank())
        check(File(build.root, TEST_SOURCE).readText() == source)
        require(classpathFile.length() <= MAX_OUTPUT_BYTES)
        val declared = json.decodeFromString<OriginalFixtureClasspath>(classpathFile.readText())
        require(declared.outputs.isNotEmpty() && declared.outputs.size <= 16 &&
            declared.jars.isNotEmpty() && declared.jars.size <= 128)
        require((declared.outputs + declared.jars).toSet().size == declared.outputs.size + declared.jars.size)
        val cli = File(build.root, "klause-cli/build/install/klause-cli-jvm")
        val production = File(cli, "lib").listFiles().orEmpty().filter { it.extension == "jar" }.sortedBy { it.name }
        require(production.isNotEmpty() && production.all { it.isFile && !Files.isSymbolicLink(it.toPath()) })
        require(production.map { it.name }.toSet().size == production.size)
        val originalJar = production.single { file -> JarFile(file).use { it.getJarEntry(MATCHER_CLASS) != null } }
        val retainedRoot = File(out, "private-fixtures/${build.arm}").apply { check(mkdirs()) }
        val outputs = declared.outputs.mapIndexed { index, path ->
            val original = File(path)
            require(original.isAbsolute && original.canonicalFile.toPath().startsWith(
                File(build.root, "klause/build").canonicalFile.toPath()))
            val retained = File(retainedRoot, "outputs-$index")
            retained to retainOutput(original, retained)
        }.toMap()
        val fixtureRoot = outputs.keys.single { File(it, FIXTURE_CLASS).isFile }
        val dependencies = retainDependencies(declared.jars, production, retainedRoot, build, out)
        val probe = File(OriginalMatchingFixtureAllocation::class.java.protectionDomain.codeSource.location.toURI())
            .canonicalFile
        require(probe.isFile)
        val classpath = production + outputs.keys + dependencies.keys + probe
        require(classpath.all { it.isAbsolute && it.path.none { c -> c == File.pathSeparatorChar } })
        Reports.writeJson(File(out, "${build.arm}-retained-fixture-outputs.json").path,
            outputs.mapKeys { it.key.path })
        val prepared = FixtureBuild(build, classpath, originalJar, CompilationStudyDistributions.identity(originalJar),
            fixtureRoot, CompilationStudyDistributions.identity(File(fixtureRoot, FIXTURE_CLASS)), outputs,
            dependencies + (probe to CompilationStudyDistributions.identity(probe)))
        verify(prepared)
        return prepared
    }

    private fun retainOutput(original: File, retained: File): Map<String, CaptureFile> {
        require(original.isDirectory)
        val contents = original.walkTopDown().onEnter {
            require(!Files.isSymbolicLink(it.toPath()))
            true
        }.toList()
        require(contents.all { !Files.isSymbolicLink(it.toPath()) && (it.isDirectory || it.isFile) })
        return if (contents.any { it.isFile }) {
            CompilationStudyDistributions.retain(original, retained)
        } else {
            check(retained.mkdirs())
            emptyMap()
        }
    }

    private fun retainDependencies(
        paths: List<String>,
        production: List<File>,
        destination: File,
        build: CompilationStudyBuild,
        out: File,
    ): Map<File, CaptureFile> {
        val dependencies = linkedMapOf<File, CaptureFile>()
        val aliases = linkedMapOf<String, String>()
        val gradleHome = File(requireNotNull(System.getenv("GRADLE_USER_HOME"))).canonicalFile.toPath()
        var bytes = 0L
        for ((index, path) in paths.withIndex()) {
            val original = File(path)
            require(original.isAbsolute && !Files.isSymbolicLink(original.toPath()) &&
                (original.canonicalFile.toPath().startsWith(gradleHome) ||
                    original.canonicalFile.toPath().startsWith(build.root.canonicalFile.toPath())))
            val identity = CompilationStudyDistributions.identity(original)
            require(identity.bytes <= MAX_CLASSPATH_BYTES - bytes)
            bytes += identity.bytes
            val sameName = production.singleOrNull { it.name == original.name }
            if (sameName != null) {
                require(CompilationStudyDistributions.identity(sameName) == identity) {
                    "Fixture dependency conflicts with original CLI runtime"
                }
                aliases[original.path] = sameName.path
            } else {
                val copy = File(destination, "dependency-$index.jar")
                original.copyTo(copy)
                check(CompilationStudyDistributions.identity(copy) == identity &&
                    CompilationStudyDistributions.identity(original) == identity)
                dependencies[copy] = identity
            }
        }
        Reports.writeJson(File(out, "${build.arm}-fixture-cli-dependency-aliases.json").path, aliases)
        Reports.writeJson(File(out, "${build.arm}-retained-fixture-dependencies.json").path,
            dependencies.mapKeys { it.key.path })
        return dependencies
    }

    private fun verify(prepared: FixtureBuild) {
        val build = prepared.build
        InstalledBuild.readManifest(build.provenance,
            File(build.root, "klause-cli/build/install/klause-cli-jvm/bin/klause-cli"),
            File(System.getProperty("java.home")), emptyMap())
        check(CompilationStudyDistributions.identity(prepared.productionJar) == prepared.productionIdentity)
        for ((directory, inventory) in prepared.outputs) {
            val files = directory.walkTopDown().onEnter { check(!Files.isSymbolicLink(it.toPath())); true }
                .filter { it.isFile }.toList()
            check(files.map { it.relativeTo(directory).invariantSeparatorsPath }.toSet() == inventory.keys)
            for (file in files) {
                check(!Files.isSymbolicLink(file.toPath()))
                check(CompilationStudyDistributions.identity(file) ==
                    inventory.getValue(file.relativeTo(directory).invariantSeparatorsPath))
            }
        }
        for ((file, identity) in prepared.dependencies) {
            check(!Files.isSymbolicLink(file.toPath()) && CompilationStudyDistributions.identity(file) == identity)
        }
    }

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    private fun measure(
        out: File,
        build: FixtureBuild,
        fixture: OriginalAllocationFixture,
        pair: Int,
    ): OriginalAllocationCase {
        val directory = File(out, "cases/${fixture.key}-$pair-${build.build.arm}").apply { check(mkdirs()) }
        val arguments = listOf("taskset", "-c", "0", File(System.getProperty("java.home"), "bin/java").path,
            "-Xmx3g", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1", "--enable-preview",
            "--enable-native-access=ALL-UNNAMED",
            "--add-modules=jdk.incubator.vector", "-cp", build.classpath.joinToString(File.pathSeparator) { it.path },
            OriginalMatchingFixtureAllocation::class.java.name, build.productionJar.path,
            build.productionIdentity.sha256, fixture.key, build.fixtureRoot.path, build.fixtureIdentity.sha256)
        Reports.writeJson(File(directory, "command.json").path, arguments)
        val stdout = File(directory, "fixture.stdout")
        val stderr = File(directory, "fixture.stderr")
        val builder = ProcessBuilder(arguments).directory(build.build.root).redirectOutput(stdout).redirectError(stderr)
        for (key in listOf("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
            builder.environment().remove(key)
        }
        for (key in listOf("OPENBLAS_NUM_THREADS", "OMP_NUM_THREADS", "MKL_NUM_THREADS")) {
            builder.environment()[key] = "1"
        }
        var process: Process? = null
        var error: String? = null
        var measurements = emptyList<OriginalAllocationMeasurement>()
        try {
            process = builder.start()
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                check(stdout.length() <= MAX_OUTPUT_BYTES && stderr.length() <= MAX_OUTPUT_BYTES)
                check(System.nanoTime() < deadline) { "Original fixture process timed out" }
            }
            check(process.exitValue() == 0 &&
                stdout.length() <= MAX_OUTPUT_BYTES && stderr.length() <= MAX_OUTPUT_BYTES)
            measurements = OriginalFixtureAllocationProtocol.measurements(stdout.readText(), fixture.key)
        } catch (failure: Exception) {
            error = failure.toString()
        } finally {
            process?.let { if (it.isAlive) CompilationStudyBuilds.kill(it) }
            File(directory, "fixture.exit").writeText("${process?.exitValue()}\n")
        }
        return OriginalAllocationCase(fixture.key, pair, build.build.arm, directory.path,
            process?.exitValue(), error, measurements, error == null && measurements.size == 21)
    }

    private fun pairedRounds(cases: List<OriginalAllocationCase>): List<OriginalAllocationPair> =
        OriginalFixtureAllocationProtocol.fixtures.flatMap { fixture ->
            (0 until OriginalFixtureAllocationProtocol.PAIRS).map { pair ->
                val control = cases.singleOrNull {
                    it.fixture == fixture.key && it.pair == pair && it.arm == "control"
                }
                val candidate = cases.singleOrNull {
                    it.fixture == fixture.key && it.pair == pair && it.arm == "candidate"
                }
                val comparisons = if (control?.completed == true && candidate?.completed == true) {
                    control.allReportedRounds.zip(candidate.allReportedRounds).map { (a, b) ->
                        check(a.round == b.round)
                        OriginalAllocationDifference(a.round, a, b, b.elapsedNanos - a.elapsedNanos,
                            b.threadAllocatedBytes - a.threadAllocatedBytes)
                    }
                } else null
                OriginalAllocationPair(fixture.key, pair, control, candidate, comparisons)
            }
        }

    private data class FixtureBuild(
        val build: CompilationStudyBuild,
        val classpath: List<File>,
        val productionJar: File,
        val productionIdentity: CaptureFile,
        val fixtureRoot: File,
        val fixtureIdentity: CaptureFile,
        val outputs: Map<File, Map<String, CaptureFile>>,
        val dependencies: Map<File, CaptureFile>,
    )
}

@Serializable
private data class OriginalFixtureClasspath(val outputs: List<String>, val jars: List<String>)

@Serializable
private data class OriginalFixtureSources(
    val head: String,
    val sourcePath: String,
    val wholeSourceSha256: String,
    val originalMethodBodies: Map<String, String>,
)

@Serializable
private data class OriginalAllocationPlan(val fixture: String, val pair: Int, val arm: String)

@Serializable
private data class OriginalAllocationCase(
    val fixture: String,
    val pair: Int,
    val arm: String,
    val directory: String,
    val exit: Int?,
    val error: String?,
    val allReportedRounds: List<OriginalAllocationMeasurement>,
    val originalInvocationCompleted: Boolean,
    val retainedInputsVerifiedBefore: Boolean = true,
    val retainedInputsVerifiedAfter: Boolean = false,
    val completed: Boolean = false,
    val completeStateOrderRngWorkEquivalenceProven: Boolean = false,
    val wholeCliRuntimeEquivalenceProven: Boolean = false,
    val acceptedSavingsCredit: Boolean = false,
)

@Serializable
private data class OriginalAllocationDifference(
    val round: Int,
    val control: OriginalAllocationMeasurement,
    val candidate: OriginalAllocationMeasurement,
    val candidateMinusControlElapsedNanos: Long,
    val candidateMinusControlThreadAllocatedBytes: Long,
)

@Serializable
private data class OriginalAllocationPair(
    val fixture: String,
    val pair: Int,
    val control: OriginalAllocationCase?,
    val candidate: OriginalAllocationCase?,
    val allRoundDifferences: List<OriginalAllocationDifference>?,
    val completeStateOrderRngWorkEquivalenceProven: Boolean = false,
    val acceptedSavingsCredit: Boolean = false,
)

@Serializable
private data class OriginalAllocationCompletion(
    val attemptedInvocations: Int,
    val error: String?,
    val completed: Boolean,
    val expectedInvocations: Int = 12,
    val warmupOriginalMethodCallsPerProcess: Int = 30,
    val measuredOriginalMethodCallsPerProcess: Int = 21,
    val completeStateOrderRngWorkEquivalenceProven: Boolean = false,
    val sourceOutcomesValidated: Boolean = false,
    val acceptedSavingsCredit: Boolean = false,
)
