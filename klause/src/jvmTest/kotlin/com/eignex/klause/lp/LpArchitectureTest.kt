package com.eignex.klause.lp

import com.eignex.klause.lp.engine.Csc
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.engine.LpSolver
import com.eignex.klause.lp.engine.RevisedSimplex
import com.eignex.klause.lp.engine.Sense
import com.eignex.klause.lp.engine.newLpSolver
import com.eignex.klause.lp.engine.newPersistentLpSolver
import com.eignex.klause.lp.engine.newTableauCutSolver
import com.eignex.klause.simplex.basis.BasisSolver
import com.eignex.klause.simplex.basis.KotlinBasisSolver
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LpArchitectureTest {
    @Test
    fun `production sources respect the LP kernel boundary`() {
        val root = repositoryRoot()
        val sources = productionKotlinSources(root)
        val coveredPaths = sources.map { root.relativize(it).toString().replace('\\', '/') }
        for (required in listOf("/lp/engine/", "/lp/lattice/", "/simplex/exact/", "/simplex/basis/", "/bound/")) {
            assertTrue(coveredPaths.any { required in it }, "candidate discovery missed $required")
        }
        val violations = sources.flatMap { source ->
            LpBoundaryScanner.scan(root.relativize(source).toString(), source.readText())
        }

        assertEquals(emptyList(), violations, violations.joinToString("\n"))
    }

    @Test
    fun `scanner rejects direct aliased wildcard and qualified bypasses`() {
        val fixtures = listOf(
            "import com.eignex.klause.lp.engine.RevisedSimplex" to "RevisedSimplex",
            "import com.eignex.klause.lp.engine.RevisedSimplex.Companion" to "RevisedSimplex",
            "import com.eignex.klause.lp.engine.Csc as SparseColumns" to "Csc",
            "import com.eignex.klause.lp.engine.*\nval meter = LpWork()" to "LpWork",
            "val solver = com.eignex.klause.lp.engine.RevisedSimplex(model)" to "RevisedSimplex",
            "val columns: com.eignex.klause.lp.engine.Csc? = null" to "Csc",
            "val work = com.eignex.klause.lp.engine.LpWork()" to "LpWork",
            "val text = \"${'$'}{com.eignex.klause.lp.engine.RevisedSimplex(model)}\"" to "RevisedSimplex",
            "import com.eignex.koblas.sparse.basis.BasisSolver" to "koblas",
            "val context = com.eignex.koblas.koblas" to "koblas",
        )

        for ((body, expected) in fixtures) {
            val violations = LpBoundaryScanner.scan(
                "Forbidden.kt",
                "package com.eignex.klause.lp.bounding\n$body",
            )
            assertTrue(violations.any { expected in it }, "scanner accepted forbidden fixture:\n$body")
        }
    }

    @Test
    fun `scanner accepts kernel uses comments strings and exact arithmetic clients`() {
        val fixtures = listOf(
            """
                package com.eignex.klause.lp.engine
                import com.eignex.klause.simplex.basis.BasisSolver
                import com.eignex.koblas.SparseVector
                import com.eignex.koblas.column
                import com.eignex.koblas.koblas
                import com.eignex.koblas.sparse.SparseWorkspace
                val solver: RevisedSimplex? = null
            """.trimIndent(),
            """
                package com.eignex.klause.simplex.basis
                import com.eignex.koblas.SparseVector
                import com.eignex.koblas.column
                import com.eignex.koblas.koblas
                import com.eignex.koblas.sparse.SparseWorkspace
                import com.eignex.klause.simplex.basis.BasisSolver
                import com.eignex.klause.simplex.basis.IndexedVector
                import com.eignex.klause.simplex.basis.BasisUpdate
                import com.eignex.klause.simplex.basis.BasisSolveQuality
                import com.eignex.klause.simplex.exact.BigFraction
                import com.eignex.klause.simplex.exact.Frac128
                import com.eignex.klause.simplex.exact.Frac128Ops
                val matrix: com.eignex.koblas.SparseMatrix? = null
            """.trimIndent(),
            """
                package com.eignex.klause.lp.bounding
                import com.eignex.klause.util.Int128
                import com.eignex.klause.simplex.exact.BigFraction
                // RevisedSimplex and com.eignex.koblas are documentation, not uses.
                val description = "Csc LpWork com.eignex.klause.lp.engine.RevisedSimplex"
                val accumulator: Int128? = null
                val exact: BigFraction? = null
            """.trimIndent(),
        )

        for (fixture in fixtures) {
            assertEquals(emptyList(), LpBoundaryScanner.scan("Allowed.kt", fixture), fixture)
        }
    }

    @Test
    fun `basis leaf rejects factorization and backend dependencies`() {
        for (dependency in listOf(
            "import com.eignex.koblas.sparse.factorization.lu.F64SparseMarkowitzLu",
            "import com.eignex.koblas.sparse.basis.*",
            "import com.eignex.koblas.sparse.basis.ProductFormBasisSolver",
            "import com.eignex.koblas.sparse.basis.BasisSolvers",
            "import com.eignex.koblas.sparse.basis.BasisSolverFactory",
            "import com.eignex.koblas.corex.Matrix as Matrix",
            "import com.eignex.koblas.*",
            "import com.eignex.koblas.SparseMatrixFactory",
            "val backend = com.eignex.koblas.koblas",
            "val solver: com.eignex.klause.lp.engine.LpModel? = null",
            "import com.eignex.klause.simplex.exact.rationalOutcome",
            "import com.eignex.klause.simplex.exact.RationalSimplex",
            "import com.eignex.klause.simplex.exact.*",
        )) {
            val source = "package com.eignex.klause.simplex.basis\n$dependency"
            assertTrue(LpBoundaryScanner.scan("Basis.kt", source).any { "outbound dependency" in it })
        }
    }

    @Test
    fun `engine rejects koblas factorization basis and provider dependencies`() {
        for (dependency in listOf(
            "import com.eignex.koblas.sparse.factorization.lu.F64SparseMarkowitzLu",
            "import com.eignex.koblas.sparse.factorization.lu.F64SparseMarkowitzLu as SparseLu",
            "import com.eignex.koblas.sparse.basis.*",
            "import com.eignex.koblas.sparse.basis.BasisSolverFactory",
            "import com.eignex.koblas.core.Vector",
            "val vector: com.eignex.koblas.core.Vector? = null",
            "val backend = com.eignex.koblas.koblas",
        )) {
            val source = "package com.eignex.klause.lp.engine\n$dependency"
            assertTrue(
                LpBoundaryScanner.scan("Engine.kt", source).any { "outbound dependency" in it },
                "scanner accepted forbidden engine dependency:\n$dependency",
            )
        }
    }

    @Test
    fun `general production factory installs the klause basis owner`() {
        newLpSolver(emptyLpModel(), componentSplit = false).use {
            assertTrue(initializeBasisOwner(it) is KotlinBasisSolver)
        }
    }

    @Test
    fun `tableau production factory installs the klause basis owner`() {
        newTableauCutSolver(emptyLpModel()).use {
            assertTrue(initializeBasisOwner(it) is KotlinBasisSolver)
        }
    }

    @Test
    fun `persistent production factory installs the klause basis owner`() {
        newPersistentLpSolver(emptyLpModel()).use {
            assertTrue(initializeBasisOwner(it) is KotlinBasisSolver)
        }
    }

    @Test
    fun `factory owner observer rejects an injected owner`() {
        RevisedSimplex(
            emptyLpModel(),
            basisSolverFactory = { matrix -> object : BasisSolver by KotlinBasisSolver(matrix) {} },
        ).use { injected ->
            assertFalse(initializeBasisOwner(injected) is KotlinBasisSolver, "observer ignored an injected owner")
        }
    }

    @Test
    fun `scanner rejects outbound kernel dependencies`() {
        val fixtures = listOf(
            """
                package com.eignex.klause.lp.engine
                import com.eignex.klause.propagation.PropagationSession
            """.trimIndent(),
            """
                package com.eignex.klause.bound
                import com.eignex.klause.lp.engine.solveAndCertify
            """.trimIndent(),
            """
                package com.eignex.klause.lp.lattice
                val state: com.eignex.klause.solver.Model? = null
            """.trimIndent(),
            """
                package com.eignex.klause.simplex.exact
                import com.eignex.klause.lp.engine.LpModel as Model
            """.trimIndent(),
        )

        for (fixture in fixtures) {
            val violations = LpBoundaryScanner.scan("Outbound.kt", fixture)
            assertTrue(violations.any { "outbound dependency" in it }, "scanner accepted outbound fixture:\n$fixture")
        }
    }
}

internal object LpBoundaryScanner {
    private val packagePattern = Regex("(?m)^\\s*package\\s+([A-Za-z_]\\w*(?:\\s*\\.\\s*[A-Za-z_]\\w*)*)")
    private val importPattern = Regex(
        "(?m)^\\s*import\\s+([A-Za-z_]\\w*(?:\\s*\\.\\s*(?:[A-Za-z_]\\w*|\\*))*)(?:\\s+as\\s+[A-Za-z_]\\w*)?",
    )
    private val qualifiedPattern = Regex(
        "\\bcom\\s*\\.\\s*eignex\\s*\\.\\s*(?:klause|koblas)(?:\\s*\\.\\s*[A-Za-z_]\\w*)+",
    )
    private val forbiddenEngineSymbols = setOf("RevisedSimplex", "Csc", "LpWork")
    private val forbiddenSymbolPatterns = forbiddenEngineSymbols.associateWith { Regex("\\b$it\\b") }
    private const val ENGINE_PACKAGE = "com.eignex.klause.lp.engine"
    private const val BASIS_PACKAGE = "com.eignex.klause.simplex.basis"
    fun scan(path: String, source: String): List<String> {
        val code = codeOnly(source)
        val packageName = packagePattern.find(code)?.groupValues?.get(1)?.normalizedName().orEmpty()
        val imports = importPattern.findAll(code).map { it.groupValues[1].normalizedName() }.toList()
        val body = withoutDirectives(code)
        val qualifiedDependencies = qualifiedPattern.findAll(body).map { it.value.normalizedName() }.toList()
        val allowedKernel = packageName == ENGINE_PACKAGE || packageName.startsWith("$ENGINE_PACKAGE.") ||
            packageName == BASIS_PACKAGE || packageName.startsWith("$BASIS_PACKAGE.")
        val violations = linkedSetOf<String>()

        if (!allowedKernel) {
            for (imported in imports) {
                if (forbiddenImport(imported)) violations += "$path: forbidden import $imported"
            }
            for (qualified in qualifiedDependencies) {
                if (forbiddenQualifiedUse(qualified)) violations += "$path: forbidden qualified use $qualified"
            }
            if (imports.any { it == "$ENGINE_PACKAGE.*" }) {
                for (symbol in forbiddenEngineSymbols) {
                    if (checkNotNull(forbiddenSymbolPatterns[symbol]).containsMatchIn(body)) {
                        violations += "$path: forbidden wildcard use $symbol"
                    }
                }
            }
        }

        for (dependency in imports + qualifiedDependencies) {
            if (forbiddenOutboundDependency(packageName, dependency)) {
                violations += "$path: outbound dependency $packageName -> $dependency"
            }
        }
        return violations.toList()
    }

    private fun forbiddenImport(name: String): Boolean = forbiddenQualifiedUse(name)

    private fun forbiddenQualifiedUse(name: String): Boolean =
        name.startsWith("com.eignex.koblas.") || name == "com.eignex.koblas" ||
            forbiddenEngineSymbols.any { name == "$ENGINE_PACKAGE.$it" || name.startsWith("$ENGINE_PACKAGE.$it.") }

    private fun forbiddenOutboundDependency(packageName: String, dependency: String): Boolean {
        if (dependency == "com.eignex.koblas" || dependency.startsWith("com.eignex.koblas.")) {
            val allowedTypes = when {
                packageName == ENGINE_PACKAGE || packageName.startsWith("$ENGINE_PACKAGE.") -> listOf(
                    "com.eignex.koblas.SparseMatrix",
                    "com.eignex.koblas.SparseVector",
                    "com.eignex.koblas.column",
                    "com.eignex.koblas.koblas",
                    "com.eignex.koblas.sparse.SparseWorkspace",
                )

                packageName == BASIS_PACKAGE || packageName.startsWith("$BASIS_PACKAGE.") -> listOf(
                    "com.eignex.koblas.SparseMatrix",
                    "com.eignex.koblas.SparseVector",
                    "com.eignex.koblas.column",
                    "com.eignex.koblas.koblas",
                    "com.eignex.koblas.sparse.SparseWorkspace",
                )

                else -> return false
            }
            val basisPackage = packageName == BASIS_PACKAGE || packageName.startsWith("$BASIS_PACKAGE.")
            return !(basisPackage && dependency.startsWith("com.eignex.koblas.core.")) &&
                allowedTypes.none { dependency == it || dependency.startsWith("$it.") }
        }
        if (!dependency.startsWith("com.eignex.klause.")) return false
        if (
            (packageName == "com.eignex.klause.bound" || packageName.startsWith("com.eignex.klause.bound.")) &&
            (dependency == ENGINE_PACKAGE || dependency.startsWith("$ENGINE_PACKAGE."))
        ) {
            return true
        }
        val allowed = when {
            packageName == ENGINE_PACKAGE || packageName.startsWith("$ENGINE_PACKAGE.") -> listOf(
                ENGINE_PACKAGE,
                "com.eignex.klause.util",
                "com.eignex.klause.simplex.exact",
                "com.eignex.klause.lp.lattice",
                BASIS_PACKAGE,
            )

            packageName == "com.eignex.klause.lp.lattice" ||
                packageName.startsWith("com.eignex.klause.lp.lattice.") -> listOf(
                "com.eignex.klause.lp.lattice",
                "com.eignex.klause.util",
            )

            packageName == "com.eignex.klause.simplex.exact" ||
                packageName.startsWith("com.eignex.klause.simplex.exact.") -> listOf(
                "com.eignex.klause.simplex.exact",
                "com.eignex.klause.util",
            )

            packageName == BASIS_PACKAGE || packageName.startsWith("$BASIS_PACKAGE.") -> listOf(
                BASIS_PACKAGE,
                "com.eignex.klause.util",
                "com.eignex.klause.simplex.exact.BigFraction",
                "com.eignex.klause.simplex.exact.Frac128",
                "com.eignex.klause.simplex.exact.Frac128Ops",
            )

            else -> return false
        }
        return allowed.none { dependency == it || dependency.startsWith("$it.") }
    }

    private fun withoutDirectives(code: String): String = code.lineSequence()
        .filterNot { line -> line.trimStart().startsWith("package ") || line.trimStart().startsWith("import ") }
        .joinToString("\n")

    private fun String.normalizedName(): String = replace(Regex("\\s+"), "")

    fun codeOnly(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        var blockDepth = 0
        var state = LexicalState.CODE
        val interpolationStates = ArrayDeque<LexicalState>()
        val interpolationDepths = ArrayDeque<Int>()
        while (index < source.length) {
            val ch = source[index]
            val next = source.getOrNull(index + 1)
            val third = source.getOrNull(index + 2)
            when (state) {
                LexicalState.CODE -> when {
                    ch == '{' && interpolationDepths.isNotEmpty() -> {
                        out.append(ch)
                        index++
                        interpolationDepths.addLast(interpolationDepths.removeLast() + 1)
                    }

                    ch == '}' && interpolationDepths.isNotEmpty() -> {
                        out.append(' ')
                        index++
                        val depth = interpolationDepths.removeLast() - 1
                        if (depth == 0) {
                            state = interpolationStates.removeLast()
                        } else {
                            interpolationDepths.addLast(depth)
                        }
                    }

                    ch == '/' && next == '/' -> {
                        out.append("  ")
                        index += 2
                        state = LexicalState.LINE_COMMENT
                    }

                    ch == '/' && next == '*' -> {
                        out.append("  ")
                        index += 2
                        blockDepth = 1
                        state = LexicalState.BLOCK_COMMENT
                    }

                    ch == '"' && next == '"' && third == '"' -> {
                        out.append("   ")
                        index += 3
                        state = LexicalState.RAW_STRING
                    }

                    ch == '"' -> {
                        out.append(' ')
                        index++
                        state = LexicalState.STRING
                    }

                    ch == '\'' -> {
                        out.append(' ')
                        index++
                        state = LexicalState.CHAR
                    }

                    else -> {
                        out.append(ch)
                        index++
                    }
                }

                LexicalState.LINE_COMMENT -> {
                    out.append(if (ch == '\n') '\n' else ' ')
                    index++
                    if (ch == '\n') state = LexicalState.CODE
                }

                LexicalState.BLOCK_COMMENT -> when {
                    ch == '/' && next == '*' -> {
                        out.append("  ")
                        index += 2
                        blockDepth++
                    }

                    ch == '*' && next == '/' -> {
                        out.append("  ")
                        index += 2
                        blockDepth--
                        if (blockDepth == 0) state = LexicalState.CODE
                    }

                    else -> {
                        out.append(if (ch == '\n') '\n' else ' ')
                        index++
                    }
                }

                LexicalState.STRING, LexicalState.CHAR -> when {
                    ch == '\\' && next != null -> {
                        out.append("  ")
                        index += 2
                    }

                    state == LexicalState.STRING && ch == '$' && next == '{' -> {
                        out.append("  ")
                        index += 2
                        interpolationStates.addLast(LexicalState.STRING)
                        interpolationDepths.addLast(1)
                        state = LexicalState.CODE
                    }

                    state == LexicalState.STRING && ch == '$' && next?.isJavaIdentifierStart() == true -> {
                        out.append(' ')
                        index++
                        while (index < source.length && source[index].isJavaIdentifierPart()) {
                            out.append(source[index++])
                        }
                    }

                    (state == LexicalState.STRING && ch == '"') ||
                        (state == LexicalState.CHAR && ch == '\'') -> {
                        out.append(' ')
                        index++
                        state = LexicalState.CODE
                    }

                    else -> {
                        out.append(if (ch == '\n') '\n' else ' ')
                        index++
                    }
                }

                LexicalState.RAW_STRING -> when {
                    ch == '$' && next == '{' -> {
                        out.append("  ")
                        index += 2
                        interpolationStates.addLast(LexicalState.RAW_STRING)
                        interpolationDepths.addLast(1)
                        state = LexicalState.CODE
                    }

                    ch == '$' && next?.isJavaIdentifierStart() == true -> {
                        out.append(' ')
                        index++
                        while (index < source.length && source[index].isJavaIdentifierPart()) {
                            out.append(source[index++])
                        }
                    }

                    ch == '"' && next == '"' && third == '"' -> {
                        out.append("   ")
                        index += 3
                        state = LexicalState.CODE
                    }

                    else -> {
                        out.append(if (ch == '\n') '\n' else ' ')
                        index++
                    }
                }
            }
        }
        return out.toString()
    }

    private enum class LexicalState { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, RAW_STRING, CHAR }
}

private fun initializeBasisOwner(solver: LpSolver): BasisSolver {
    val ownerMethod = generateSequence(solver.javaClass as Class<*>?) { it.superclass }
        .flatMap { it.declaredMethods.asSequence() }
        .first { it.name == "solver" }
    assertTrue(ownerMethod.trySetAccessible(), "could not invoke ${solver.javaClass.name}.solver")
    ownerMethod.invoke(solver)
    val field = generateSequence(solver.javaClass as Class<*>?) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { it.name == "basisSolver" }
        ?: error("could not find ${solver.javaClass.name}.basisSolver")
    assertTrue(field.trySetAccessible(), "could not inspect ${solver.javaClass.name}.basisSolver")
    return assertNotNull(field.get(solver) as? BasisSolver)
}

private fun emptyLpModel() = LpModel(
    n = 0,
    m = 0,
    csc = Csc(intArrayOf(0), intArrayOf(), longArrayOf()),
    rhs = longArrayOf(),
    cost = longArrayOf(),
    upper = longArrayOf(),
    hasUpper = booleanArrayOf(),
    loShift = longArrayOf(),
    objConstant = 0L,
    sense = Sense.MINIMIZE,
    tag = intArrayOf(),
)

private fun productionKotlinSources(root: Path): List<Path> {
    val sourceRoot = root.resolve("klause/src")
    return try {
        val process = ProcessBuilder(
            "rg",
            "--files-with-matches",
            "--glob",
            "**/*Main/kotlin/**/*.kt",
            PRODUCTION_CANDIDATE_PATTERN,
            sourceRoot.toString(),
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val paths = process.inputStream.bufferedReader().use { it.readLines() }
        when (val exit = process.waitFor()) {
            0 -> paths.map(Paths::get).sorted()
            1 -> allProductionKotlinSources(sourceRoot)
            else -> error("rg source discovery failed with exit $exit")
        }
    } catch (_: IOException) {
        allProductionKotlinSources(sourceRoot)
    }
}

private fun allProductionKotlinSources(sourceRoot: Path): List<Path> = Files.list(sourceRoot).use { sourceSets ->
    sourceSets
        .filter { it.isDirectory() && it.name.endsWith("Main") }
        .flatMap { sourceSet ->
            val kotlinRoot = sourceSet.resolve("kotlin")
            if (kotlinRoot.isDirectory()) Files.walk(kotlinRoot) else java.util.stream.Stream.empty()
        }
        .filter { it.extension == "kt" }
        .sorted()
        .toList()
}

private const val PRODUCTION_CANDIDATE_PATTERN =
    "RevisedSimplex|\\bCsc\\b|\\bLpWork\\b|com\\.eignex\\.koblas|" +
        "package\\s+com\\.eignex\\.klause\\.(lp\\.engine|lp\\.lattice|simplex\\.exact|simplex\\.basis|bound)"

private fun repositoryRoot(): Path = generateSequence(Paths.get("").toAbsolutePath().normalize()) { it.parent }
    .first { Files.isDirectory(it.resolve("klause/src")) && Files.exists(it.resolve("settings.gradle.kts")) }
