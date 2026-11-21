package com.eignex.klause.bench.lab

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CliHarnessTest {
    companion object {
        private lateinit var loader: URLClassLoader
        private lateinit var libraries: List<File>
        private lateinit var benchClasses: List<Pair<File, String>>
        private lateinit var implicitClasspaths: List<String>
        private lateinit var suite: Class<*>

        @BeforeAll
        @JvmStatic
        fun openInstalledHarness() {
            val installation = File(requireNotNull(System.getProperty("klause.bench.cliHarness")))
            libraries = requireNotNull(installation.resolve("lib").listFiles()).sortedBy { it.name }
            benchClasses = libraries.flatMap { file ->
                JarFile(file).use { jar ->
                    jar.entries().asSequence()
                        .filter { it.name.startsWith("com/eignex/klause/bench/") && it.name.endsWith(".class") }
                        .map { file to it.name }.toList()
                }
            }
            implicitClasspaths = libraries.mapNotNull { file ->
                JarFile(file).use { it.manifest?.mainAttributes?.getValue("Class-Path")?.takeIf(String::isNotBlank) }
            }
            loader = URLClassLoader(libraries.map { it.toURI().toURL() }.toTypedArray(),
                ClassLoader.getPlatformClassLoader())
            suite = Class.forName("com.eignex.klause.bench.lab.KlauseLabSuite", true, loader)
        }

        @AfterAll
        @JvmStatic
        fun closeInstalledHarness() {
            loader.close()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "lab.LabAdapterKt", "profile.HarnessRecording", "profile.SourcePreparationCapture",
        "profile.SourcePreparationCapture\$PreparationEvent", "profile.SourcePreparationCapture\$span\$1",
    ])
    fun `CLI runtime loads its entry point and recording helpers`(name: String) {
        val type = Class.forName("com.eignex.klause.bench.$name", false, loader)

        assertSame(loader, type.classLoader)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "FixedWorkSatMeasurement", "OrdinaryArithmeticLearningProfile", "PreparedArithmeticLearningProfile",
        "FinitePortfolioLpCapture",
    ])
    fun `standalone profile programs remain outside the CLI runtime`(name: String) {
        val qualified = "com.eignex.klause.bench.profile.$name"
        assertNotNull(Class.forName(qualified, false, javaClass.classLoader))

        assertFailsWith<ClassNotFoundException> { Class.forName(qualified, false, loader) }
    }

    @Test
    fun `one installed CLI jar provides every bench class`() {
        assertTrue(benchClasses.isNotEmpty())
        val providers = benchClasses.map { it.first.name }.distinct()

        assertEquals(1, providers.size)
        assertTrue(providers.single().startsWith("klause-bench-cli-"))
        assertEquals(benchClasses.size, benchClasses.map { it.second }.distinct().size)
    }

    @Test
    fun `installed jars do not extend the explicit runtime classpath`() {
        assertTrue(implicitClasspaths.isEmpty())
    }

    @Test
    fun `installed driver describes its suite without the full bench jar`() {
        val descriptor = suite.getMethod("describe").invoke(suite.getField("INSTANCE").get(null))

        assertEquals("klause-bench", descriptor.javaClass.getMethod("getId").invoke(descriptor))
    }
}
