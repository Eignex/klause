package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiClass
import org.jetbrains.kotlin.com.intellij.psi.PsiClassInitializer
import org.jetbrains.kotlin.com.intellij.psi.PsiCodeBlock
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiFile
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaCodeReferenceElement
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
import org.jetbrains.kotlin.com.intellij.psi.PsiLambdaExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.PsiMember
import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
import org.jetbrains.kotlin.com.intellij.psi.PsiMethodCallExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiNamedElement
import org.jetbrains.kotlin.com.intellij.psi.PsiNewExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
import org.jetbrains.kotlin.com.intellij.psi.PsiStatement
import org.jetbrains.kotlin.com.intellij.psi.PsiVariable
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtSecondaryConstructor
import org.jetbrains.kotlin.psi.KtSimpleNameExpression

@OptIn(CompilerConfiguration.Internals::class, K1Deprecation::class)
internal class JvmSourceDeclarationParser : AutoCloseable {
    private val disposable = Disposer.newDisposable("klause-source-declarations")
    private val environment = try {
        val configuration = CompilerConfiguration().apply {
            put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        }
        KotlinCoreEnvironment.createForProduction(disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
    } catch (failure: Exception) {
        Disposer.dispose(disposable)
        throw failure
    }
    private val factory = KtPsiFactory(environment.project, false)
    private var visited = 0L
    private var closed = false

    @Suppress("LongMethod")
    fun inspect(path: String, text: String, emit: (JsonObject) -> Unit): SourceSyntaxResult {
        check(!closed)
        val source = SourceSyntaxIndex(text)
        val fileName = path.substringAfterLast('/')
        val file = if (path.endsWith(".java")) {
            PsiFileFactory.getInstance(environment.project).createFileFromText(fileName, JavaFileType.INSTANCE, text)
                .also { require(it is PsiJavaFile) { "Java parser unavailable" } }
        } else {
            require(path.endsWith(".kt") || path.endsWith(".kts"))
            factory.createFile(fileName, text)
        }
        require(file.text == text) { "Parser changed original source text" }
        val pending = ArrayDeque<SourceSyntaxCursor>()
        pending.addLast(SourceSyntaxCursor(file, null, 0))
        var records = 0
        var errors = 0
        try {
            while (pending.isNotEmpty()) {
                val (element, owner, depth) = pending.removeLast()
                check(++visited <= MAX_NODES && depth <= MAX_DEPTH) { "Complete source syntax exceeds bounds" }
                var parent = owner
                if (retained(element)) {
                    val index = records++
                    if (element is PsiErrorElement) errors++
                    emit(buildJsonObject {
                        put("index", index)
                        put("parentIndex", owner?.let(::JsonPrimitive) ?: JsonNull)
                        put("kind", kind(element))
                        put("psiKind", element.javaClass.name)
                        put("name", when (element) {
                            is KtNamedDeclaration -> element.name
                            is PsiNamedElement -> element.name
                            else -> null
                        })
                        put("role", role(element))
                        put("package", when (element) {
                            is KtFile -> element.packageFqName.asString()
                            is PsiJavaFile -> element.packageName
                            else -> null
                        })
                        put("range", source.range(element))
                        put("syntaxError", (element as? PsiErrorElement)?.errorDescription)
                        put("referencedName", when (element) {
                            is KtSimpleNameExpression -> element.getReferencedName()
                            is PsiReferenceExpression -> element.referenceName
                            is PsiJavaCodeReferenceElement -> element.referenceName
                            else -> null
                        })
                        put("symbolResolutionProven", false)
                        put("jvmMemberOrGeneratedBodyInferred", false)
                    })
                    parent = index
                }
                val children = element.children
                for (index in children.lastIndex downTo 0) {
                    pending.addLast(SourceSyntaxCursor(children[index], parent, depth + 1))
                }
            }
        } finally {
            PsiManager.getInstance(environment.project).dropPsiCaches()
        }
        return SourceSyntaxResult(records, errors)
    }

    override fun close() {
        if (!closed) {
            closed = true
            Disposer.dispose(disposable)
        }
    }

    private fun retained(element: PsiElement): Boolean = element is PsiFile || element is PsiNamedElement ||
        element is KtDeclaration || element is KtExpression || element is KtClassBody || element is PsiCodeBlock ||
        element is PsiMember || element is PsiVariable || element is PsiExpression || element is PsiStatement ||
        element is PsiJavaCodeReferenceElement || element is PsiErrorElement

    @Suppress("CyclomaticComplexMethod")
    private fun kind(element: PsiElement): String = when (element) {
        is PsiFile -> "source-file"
        is PsiErrorElement -> "syntax-error"
        is KtNamedFunction -> if (element.name == null) "anonymous-function" else "function"
        is KtFunctionLiteral -> "lambda"
        is KtPropertyAccessor -> if (element.isGetter) "getter" else "setter"
        is KtPrimaryConstructor -> "primary-constructor"
        is KtSecondaryConstructor -> "secondary-constructor"
        is KtAnonymousInitializer -> "initializer"
        is KtClassOrObject -> "type"
        is KtClassBody -> "block"
        is KtProperty -> "property"
        is KtParameter -> "parameter"
        is KtDeclaration -> "declaration"
        is KtCallExpression -> "call"
        is KtSimpleNameExpression -> "reference"
        is KtExpression -> "expression"
        is PsiMethod -> if (element.isConstructor) "constructor" else "method"
        is PsiClassInitializer -> "initializer"
        is PsiClass -> "type"
        is PsiCodeBlock -> "block"
        is PsiVariable -> "variable"
        is PsiLambdaExpression -> "lambda"
        is PsiReferenceExpression, is PsiJavaCodeReferenceElement -> "reference"
        is PsiMethodCallExpression, is PsiNewExpression -> "call"
        is PsiExpression -> "expression"
        is PsiNamedElement -> "declaration"
        else -> "statement"
    }

    private fun role(element: PsiElement): String? = when (val parent = element.parent) {
        is KtParameter -> if (parent.defaultValue === element) "default-argument" else null
        is KtProperty -> when (element) {
            parent.initializer -> "property-initializer"
            parent.delegateExpression -> "property-delegate"
            else -> null
        }
        is KtPropertyDelegate -> if (parent.expression === element) "property-delegate" else null
        is KtClassOrObject -> if (parent.body === element) "body" else null
        is KtDeclarationWithBody -> if (parent.bodyExpression === element) "body" else null
        is KtAnonymousInitializer -> if (parent.body === element) "body" else null
        is PsiVariable -> if (parent.initializer === element) "variable-initializer" else null
        is PsiMethod -> if (parent.body === element) "body" else null
        is PsiClassInitializer -> if (parent.body === element) "body" else null
        is PsiLambdaExpression -> if (parent.body === element) "body" else null
        else -> null
    }

    private companion object {
        const val MAX_NODES = 10_000_000L
        const val MAX_DEPTH = 4096
    }
}

internal data class SourceSyntaxResult(val records: Int, val syntaxErrors: Int)

private data class SourceSyntaxCursor(val element: PsiElement, val parentIndex: Int?, val depth: Int)

private class SourceSyntaxIndex(private val text: String) {
    private val raw = text.encodeToByteArray()
    private val byteOffsets = IntArray(text.length + 1) { -1 }
    private val lineStarts = ArrayList<Int>().apply { add(0) }

    init {
        var offset = 0
        var bytes = 0
        while (offset < text.length) {
            byteOffsets[offset] = bytes
            val first = text[offset]
            if (first == '\r') {
                lineStarts.add(offset + if (offset + 1 < text.length && text[offset + 1] == '\n') 2 else 1)
            } else if (first == '\n' && (offset == 0 || text[offset - 1] != '\r')) {
                lineStarts.add(offset + 1)
            }
            val point = Character.codePointAt(text, offset)
            require(point !in 0xD800..0xDFFF) { "Unpaired source surrogate" }
            bytes += when {
                point <= 0x7F -> 1
                point <= 0x7FF -> 2
                point <= 0xFFFF -> 3
                else -> 4
            }
            offset += Character.charCount(point)
        }
        byteOffsets[text.length] = bytes
        check(bytes == raw.size)
    }

    fun range(element: PsiElement): JsonObject {
        val range = element.textRange
        val start = range.startOffset
        val end = range.endOffset
        require(start in 0..end && end <= text.length && byteOffsets[start] >= 0 && byteOffsets[end] >= 0)
        return buildJsonObject {
            put("startUtf16", start)
            put("endUtf16Exclusive", end)
            put("startByte", byteOffsets[start])
            put("endByteExclusive", byteOffsets[end])
            put("firstLine", line(start))
            put("lastLine", line(if (end > start) end - 1 else end))
            put("sha256", sha256(raw.copyOfRange(byteOffsets[start], byteOffsets[end])))
        }
    }

    private fun line(offset: Int): Int {
        val found = lineStarts.binarySearch(offset)
        return if (found >= 0) found + 1 else -found - 1
    }
}
