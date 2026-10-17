package com.eignex.klause.bench.profile

import java.io.File
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassHierarchyResolver
import java.lang.classfile.CodeTransform
import java.lang.classfile.MethodModel
import java.lang.classfile.MethodTransform
import java.lang.classfile.Opcode
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.classfile.instruction.LineNumber
import java.lang.constant.ClassDesc
import java.lang.constant.ConstantDescs
import java.lang.constant.MethodTypeDesc
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.lang.management.ManagementFactory
import java.lang.reflect.Field
import java.security.MessageDigest
import java.security.ProtectionDomain
import java.util.Locale
import java.util.Properties

@Suppress("TooGenericExceptionCaught")
internal object BranchAttemptObservationAgent {
    private const val SEARCH = "com.eignex.klause.solver.search."
    private const val TARGET = "com/eignex/klause/solver/search/SearchRun"
    private const val METHOD = "advanceFrame"
    private const val METHOD_TYPE = "()Lcom/eignex/klause/solver/search/SearchRun\$Advance;"
    private const val PUSH_TYPE = "(Lcom/eignex/klause/solver/search/SearchDecision;)" +
        "Lcom/eignex/klause/solver/search/ComponentResult;"
    private const val COMPONENT = "com.eignex.klause.theory.qflra.ExactLiraSearchComponent"
    private val helper = ClassDesc.of("com.eignex.klause.bench.profile.BranchAttemptObservationAgent")
    private val helperType = MethodTypeDesc.of(
        ConstantDescs.CD_void,
        ConstantDescs.CD_Object,
        ConstantDescs.CD_Object,
        ConstantDescs.CD_Object,
    )
    private val access = Access()
    private var observation: BranchAttemptObservation? = null

    @JvmStatic
    @Suppress("LongMethod")
    fun premain(argument: String, instrumentation: Instrumentation) {
        val file = File(argument)
        require(file.isFile && file.length() <= 16384L)
        val config = Properties().apply { file.inputStream().use(::load) }
        require(
            config.stringPropertyNames() == setOf(
            "report",
            "coreJar",
            "agentSha256",
            "fieldSchemaSha256",
            "class.$TARGET",
            "schema.$TARGET",
            "rewrite.$TARGET",
        )
        )
        val ownJar = File(javaClass.protectionDomain.codeSource.location.toURI())
        require(ownJar.isFile && ownJar.length() in 1L..524288L)
        require(sha(ownJar.readBytes()) == config.getProperty("agentSha256"))
        val core = File(config.getProperty("coreJar")).canonicalFile
        val recorder = BranchAttemptObservation()
        observation = recorder
        val transformations = ArrayList<Map<String, Any?>>()
        instrumentation.addTransformer(
            object : ClassFileTransformer {
            override fun transform(
                loader: ClassLoader?,
                className: String?,
                classBeingRedefined: Class<*>?,
                protectionDomain: ProtectionDomain?,
                classfileBuffer: ByteArray,
            ): ByteArray? {
                if (className != TARGET) return null
                return synchronized(recorder) {
                    try {
                        require(transformations.isEmpty() && classBeingRedefined == null)
                        require(classfileBuffer.size <= 2 * 1024 * 1024)
                        val location = requireNotNull(protectionDomain?.codeSource?.location)
                        require(File(location.toURI()).canonicalFile == core)
                        require(sha(classfileBuffer) == config.getProperty("class.$TARGET"))
                        val selected = targetMethods().getValue(TARGET)
                        val schema = schema(classfileBuffer, selected)
                        require(sha(schemaText(schema).toByteArray()) == config.getProperty("schema.$TARGET"))
                        val output = rewrite(classfileBuffer, selected, loader)
                        require(sha(output) == config.getProperty("rewrite.$TARGET"))
                        transformations += mapOf(
                            "class" to TARGET,
                            "beforeSha256" to sha(classfileBuffer),
                            "afterSha256" to sha(output),
                            "methods" to schema,
                            "codeSource" to location.toString(),
                        )
                        output
                    } catch (_: Throwable) {
                        recorder.fail("transform-helper")
                        null
                    }
                }
            }
        },
            false
        )
        Runtime.getRuntime().addShutdownHook(
            Thread({
            synchronized(recorder) {
                if (transformations.size != 1) recorder.fail("transform-coverage")
                val fields = try {
                    fieldSchema(javaClass.classLoader)
                } catch (_: Throwable) {
                    recorder.fail("field-schema")
                    emptyMap()
                }
                val fieldSha = sha(json(fields).toByteArray())
                if (fieldSha != config.getProperty("fieldSchemaSha256")) recorder.fail("field-schema-mismatch")
                val report = recorder.snapshot() + mapOf(
                    "fieldSchema" to fields, "fieldSchemaSha256" to fieldSha,
                    "transformations" to transformations.toList(), "agentSha256" to config.getProperty("agentSha256"),
                    "configSha256" to sha(file.readBytes()), "pid" to ProcessHandle.current().pid(),
                    "javaCommand" to System.getProperty("sun.java.command"),
                    "jvmArguments" to ManagementFactory.getRuntimeMXBean().inputArguments,
                    "processors" to Runtime.getRuntime().availableProcessors(),
                )
                val text = json(report)
                File(config.getProperty("report")).writeText(
                    if (text.toByteArray().size <= 524288) {
                        text
                    } else {
                        "{\"status\":\"INCONCLUSIVE\",\"failures\":[\"report-cap\"]}"
                    },
                )
            }
        }, "branch-attempt-report")
        )
    }

    @JvmStatic
    fun attempted(session: Any, decision: Any, run: Any) {
        val recorder = observation ?: return
        synchronized(recorder) { record(recorder, session, decision, run) }
    }

    internal fun record(recorder: BranchAttemptObservation, session: Any, decision: Any, run: Any) {
        if (recorder.disabled) return
        try {
            require(run.javaClass.name == SEARCH + "SearchRun")
            require(session.javaClass.name == SEARCH + "SearchSession" && access.field(run, "session") === session)
            var branching = requireNotNull(access.field(run, "booleanBranching"))
            if (branching.javaClass.name == SEARCH + "HintedBooleanBranching") {
                branching = requireNotNull(access.field(branching, "delegate"))
            }
            require(branching.javaClass.name == SEARCH + "BooleanBranching\$SourceOrder")
            val width = access.field(branching, "numBoolVars") as Int
            val components = requireNotNull(access.field(session, "components"))
            require(trustedList(components))
            components as List<*>
            require(components.size <= 8)
            var selected: Any? = null
            for (component in components) {
                if (component?.javaClass?.name != COMPONENT) continue
                require(selected == null)
                selected = component
            }
            val component = requireNotNull(selected)
            require(access.field(component, "context") === session)
            val bools = access.field(component, "bools") as IntArray
            require(bools.size == width)
            if (decision.javaClass.name != SEARCH + "SearchDecision\$Bool") {
                require(decision.javaClass.name in otherDecisions)
                recorder.attempted(run, session, component, width, null, null, null, null)
                return
            }
            val literal = access.field(decision, "literal") as Int
            require(literal >= 0)
            val variable = literal ushr 1
            if (variable >= width) {
                recorder.attempted(run, session, component, width, variable, literal and 1 == 0, null, null)
                return
            }
            val arithmetic = requireNotNull(access.field(component, "arithmeticVariables"))
            require(trustedSet(arithmetic))
            arithmetic as Set<*>
            recorder.attempted(
                run,
                session,
                component,
                width,
                variable,
                literal and 1 == 0,
                bools[variable],
                variable in arithmetic,
            )
        } catch (_: Throwable) {
            recorder.fail("callback-schema")
        }
    }

    private val trustedSets = setOf(
        "java.util.HashSet",
        "java.util.LinkedHashSet",
        "java.util.Collections\$SingletonSet",
        "java.util.Collections\$EmptySet",
    )

    internal fun trustedSet(value: Any): Boolean = trustedCollection(value, trustedSets, "kotlin.collections.EmptySet")

    private val trustedLists = setOf(
        "java.util.ArrayList",
        "java.util.Arrays\$ArrayList",
        "java.util.Collections\$SingletonList",
        "java.util.Collections\$EmptyList",
        "java.util.ImmutableCollections\$List12",
        "java.util.ImmutableCollections\$ListN",
    )

    private fun trustedList(value: Any): Boolean =
        trustedCollection(value, trustedLists, "kotlin.collections.EmptyList")

    private fun trustedCollection(value: Any, bootstrap: Set<String>, kotlinEmpty: String): Boolean =
        (value.javaClass.name in bootstrap && value.javaClass.classLoader == null) ||
            (value.javaClass.name == kotlinEmpty && value.javaClass.classLoader === Unit.javaClass.classLoader)

    private val otherDecisions = setOf("IntAtMost", "IntAtLeast", "IntEqual", "Theory").mapTo(HashSet()) {
        SEARCH + "SearchDecision\$" + it
    }

    fun targetMethods(): Map<String, Map<String, String>> = mapOf(TARGET to mapOf(METHOD to METHOD_TYPE))

    fun schema(bytes: ByteArray, selected: Map<String, String>): List<Map<String, Any?>> {
        require(selected == mapOf(METHOD to METHOD_TYPE))
        val model = ClassFile.of().parse(bytes)
        require(model.thisClass().asInternalName() == TARGET)
        val method = model.methods().single { it.methodName().stringValue() == METHOD }
        require(method.flags().flagsMask() and 15 == 2 && method.methodTypeSymbol().descriptorString() == METHOD_TYPE)
        var line = -1
        val sites = ArrayList<Map<String, Any?>>()
        for (part in method.code().orElseThrow()) {
            if (part is LineNumber) line = part.line()
            if (part is InvokeInstruction && isPush(part)) {
                require(part.opcode() == Opcode.INVOKEVIRTUAL)
                sites += mapOf(
                    "owner" to part.owner().asInternalName(),
                    "name" to part.name().stringValue(),
                    "descriptor" to part.type().stringValue(),
                    "opcode" to part.opcode().name,
                    "line" to line,
                )
            }
        }
        require(sites.size == 1)
        return listOf(
            mapOf(
            "name" to METHOD,
            "descriptor" to METHOD_TYPE,
            "flags" to method.flags().flagsMask(),
            "prePushSites" to sites,
        )
        )
    }

    private fun isPush(part: InvokeInstruction): Boolean =
        part.owner().asInternalName() == "com/eignex/klause/solver/search/SearchSession" &&
            part.name().stringValue() == "push" && part.type().stringValue() == PUSH_TYPE

    fun schemaText(schema: List<Map<String, Any?>>): String = json(schema) + "\n"

    fun rewrite(bytes: ByteArray, selected: Map<String, String>, loader: ClassLoader?): ByteArray {
        schema(bytes, selected)
        val resolver = ClassHierarchyResolver.ofResourceParsing(requireNotNull(loader))
        val factory = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver))
        return factory.transformClass(factory.parse(bytes)) { builder, element ->
            if (element is MethodModel && element.methodName().stringValue() == METHOD) {
                builder.transformMethod(
                    element,
                    MethodTransform.transformingCode(CodeTransform { code, part ->
                        if (part is InvokeInstruction && isPush(part)) {
                            code.dup2().aload(0).invokestatic(helper, "attempted", helperType)
                        }
                        code.with(part)
                    }),
                )
            } else {
                builder.with(element)
            }
        }
    }

    fun fieldSchema(loader: ClassLoader): Map<String, List<String>> = Access.schema.mapValues { (name, fields) ->
        val type = Class.forName(name, false, loader)
        fields.map { (field, expected) ->
            val actual = type.getDeclaredField(field)
            require(actual.type.name == expected)
            "$field:$expected"
        }
    }

    private class Access {
        private val fields = HashMap<Pair<Class<*>, String>, Field>()

        fun field(value: Any, name: String): Any? {
            require(name in schema.getValue(value.javaClass.name))
            val field = fields.getOrPut(value.javaClass to name) {
                require(fields.size < 16)
                value.javaClass.getDeclaredField(name).apply {
                    require(type.name == schema.getValue(value.javaClass.name).getValue(name))
                    isAccessible = true
                }
            }
            return field.get(value)
        }

        companion object {
            val schema = linkedMapOf(
                SEARCH + "SearchRun" to mapOf(
                    "session" to SEARCH + "SearchSession",
                    "booleanBranching" to SEARCH + "BooleanBranching",
                ),
                SEARCH + "HintedBooleanBranching" to mapOf("delegate" to SEARCH + "BooleanBranching"),
                SEARCH + "BooleanBranching\$SourceOrder" to mapOf("numBoolVars" to "int"),
                SEARCH + "SearchSession" to mapOf("components" to "java.util.List"),
                COMPONENT to mapOf(
                    "context" to SEARCH + "SearchContext",
                    "bools" to "[I",
                    "arithmeticVariables" to "java.util.Set",
                ),
                SEARCH + "SearchDecision\$Bool" to mapOf("literal" to "int"),
            )
        }
    }

    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(Locale.ROOT, it.toInt() and 255)
    }

    fun json(value: Any?): String = when (value) {
        null -> "null"

        is Boolean, is Long, is Int -> value.toString()

        is String -> buildString {
            append('"')
            for (character in value) {
                when (character) {
                '"' -> append("\\\"")

                '\\' -> append("\\\\")

                '\n' -> append("\\n")

                '\r' -> append("\\r")

                '\t' -> append("\\t")

                else -> if (character.code < 32) {
                    append("\\u%04x".format(Locale.ROOT, character.code))
                } else {
                    append(character)
                }
            }
            }
            append('"')
        }

        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }

        is Iterable<*> -> value.joinToString(",", "[", "]", transform = ::json)

        else -> error("unsupported report value")
    }
}
