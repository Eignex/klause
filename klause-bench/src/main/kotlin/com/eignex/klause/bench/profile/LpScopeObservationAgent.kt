package com.eignex.klause.bench.profile

import java.io.File
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassHierarchyResolver
import java.lang.classfile.CodeBuilder
import java.lang.classfile.CodeElement
import java.lang.classfile.CodeTransform
import java.lang.classfile.MethodModel
import java.lang.classfile.MethodTransform
import java.lang.classfile.Opcode
import java.lang.classfile.instruction.ReturnInstruction
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
internal object LpScopeObservationAgent {
    private const val ENGINE = "com.eignex.klause.lp.engine."
    private const val SYSTEM = "com/eignex/klause/lp/engine/LpScopedSolver"
    private const val PROPAGATOR = "com/eignex/klause/lp/bounding/LpPropagator"
    private val helper = ClassDesc.of("com.eignex.klause.bench.profile.LpScopeObservationAgent")
    private val objectType = ConstantDescs.CD_Object
    private val enterType = MethodTypeDesc.of(
        ConstantDescs.CD_void,
        ConstantDescs.CD_int,
        objectType,
        objectType.arrayType(),
    )
    private val returnType = MethodTypeDesc.of(
        ConstantDescs.CD_void,
        objectType,
        ConstantDescs.CD_int,
        objectType,
    )
    private val methods = mapOf(
        SYSTEM to linkedMapOf(
            "push" to LpScopeObservation.Method.PUSH,
            "publish" to LpScopeObservation.Method.PUBLISH,
            "solve" to LpScopeObservation.Method.SOLVE,
            "solveFloat" to LpScopeObservation.Method.FLOAT,
            "withWorkingModel" to LpScopeObservation.Method.WORKING,
            "close" to LpScopeObservation.Method.CLOSE,
            "replace" to LpScopeObservation.Method.REPLACE,
        ),
        PROPAGATOR to mapOf("solve" to LpScopeObservation.Method.PROPAGATOR),
    )
    private val access = Access()
    private var observation: LpScopeObservation? = null
    private val transformations = ArrayList<Map<String, Any?>>()

    @JvmStatic
    @Suppress("LongMethod")
    fun premain(argument: String, instrumentation: Instrumentation) {
        val configFile = File(argument)
        require(configFile.isFile && configFile.length() <= 16384)
        val config = Properties().apply { configFile.inputStream().use(::load) }
        require(
            config.stringPropertyNames() == setOf("report", "coreJar", "agentSha256", "fieldSchemaSha256") +
            methods.keys.flatMap { listOf("class.$it", "schema.$it", "rewrite.$it") }
        )
        val ownJar = File(javaClass.protectionDomain.codeSource.location.toURI())
        require(sha(ownJar.readBytes()) == config.getProperty("agentSha256"))
        val core = File(config.getProperty("coreJar")).canonicalFile
        val recorder = LpScopeObservation()
        observation = recorder
        instrumentation.addTransformer(
            object : ClassFileTransformer {
                override fun transform(
                    loader: ClassLoader?,
                    className: String?,
                    classBeingRedefined: Class<*>?,
                    protectionDomain: ProtectionDomain?,
                    classfileBuffer: ByteArray,
                ): ByteArray? {
                    val selected = methods[className] ?: return null
                    return synchronized(recorder) {
                        try {
                            require(classBeingRedefined == null && classfileBuffer.size <= 2 * 1024 * 1024)
                            val location = requireNotNull(protectionDomain?.codeSource?.location)
                            require(File(location.toURI()).canonicalFile == core)
                            require(sha(classfileBuffer) == config.getProperty("class.$className"))
                            val schema = schema(classfileBuffer, selected)
                            require(sha(schemaText(schema).toByteArray()) == config.getProperty("schema.$className"))
                            val output = rewrite(classfileBuffer, selected, loader)
                            require(sha(output) == config.getProperty("rewrite.$className"))
                            transformations += mapOf(
                                "class" to className,
                                "beforeSha256" to sha(classfileBuffer),
                                "afterSha256" to sha(output),
                                "methods" to schema,
                                "codeSource" to location.toString(),
                            )
                            output
                        } catch (failure: Throwable) {
                            recorder.fail("transform:${failure.javaClass.simpleName}")
                            null
                        }
                    }
                }
            },
            false,
        )
        Runtime.getRuntime().addShutdownHook(
            Thread(
                {
                    synchronized(recorder) {
                        if (transformations.map { it["class"] }.toSet() != methods.keys) {
                            recorder.fail("transform-coverage")
                        }
                        val actualFields = try {
                            fieldSchema(javaClass.classLoader)
                        } catch (failure: Throwable) {
                            recorder.fail("field-schema:${failure.javaClass.simpleName}")
                            emptyMap()
                        }
                        val actualFieldSha = sha(json(actualFields).toByteArray())
                        if (actualFieldSha != config.getProperty("fieldSchemaSha256")) {
                            recorder.fail("field-schema-mismatch")
                        }
                        val report = recorder.snapshot() + mapOf(
                            "fieldSchema" to actualFields,
                            "fieldSchemaSha256" to actualFieldSha,
                            "transformations" to transformations.toList(),
                            "agentSha256" to config.getProperty("agentSha256"),
                            "configSha256" to sha(configFile.readBytes()),
                            "pid" to ProcessHandle.current().pid(),
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
                },
                "lp-scope-report",
            ),
        )
    }

    @JvmStatic
    fun entered(tag: Int, emitter: Any, arguments: Array<Any?>) {
        val recorder = observation ?: return
        synchronized(recorder) {
            val method = LpScopeObservation.Method.entries[tag]
            recorder.ledger(method, true)
            if (recorder.disabled) return
            try {
                if (method == LpScopeObservation.Method.PROPAGATOR) {
                    access.field(emitter, "owner")?.let { owner ->
                        recorder.bind(owner, access.field(emitter, "modelKey"), access.field(emitter, "proofContext"))
                    }
                } else {
                    val state = access.state(emitter)
                    val shapes = when (method) {
                        LpScopeObservation.Method.REPLACE -> {
                            require(arguments.size == 4)
                            val candidate = requireNotNull(access.field(requireNotNull(arguments[0]), "state"))
                            LpScopeObservation.Shapes(
                                access.shape(state.model),
                                access.shape(requireNotNull(access.field(candidate, "model"))),
                                arguments[2] as Boolean,
                            )
                        }
                        LpScopeObservation.Method.SOLVE, LpScopeObservation.Method.FLOAT ->
                            LpScopeObservation.Shapes(access.shape(state.model))
                        else -> null
                    }
                    recorder.enter(
                        method, emitter, state, access.controls(method, emitter, arguments),
                        System.nanoTime(), Thread.currentThread().threadId(), shapes,
                    )
                }
            } catch (failure: Throwable) {
                recorder.fail("entry:${failure.javaClass.simpleName}")
            }
        }
    }

    @JvmStatic
    fun returned(result: Any?, tag: Int, emitter: Any) {
        val recorder = observation ?: return
        synchronized(recorder) {
            val method = LpScopeObservation.Method.entries[tag]
            recorder.ledger(method, false)
            if (recorder.disabled || method == LpScopeObservation.Method.PROPAGATOR) return
            try {
                val kind = if (method != LpScopeObservation.Method.SOLVE) {
                    "OTHER"
                } else {
                    when {
                    result == null -> "NULL"

                    (access.field(result, "verdict") as Enum<*>).name in
                        listOf("INFEASIBLE", "UNBOUNDED", "TOLERANCE_OPTIMUM") -> "OTHER"

                    access.field(result, "witness") != null && access.field(result, "bound") != null -> "BOTH"

                    access.field(result, "witness") != null -> "WITNESS"

                    access.field(result, "bound") != null -> "BOUND"

                    else -> "NONE"
                }
                }
                val state = access.state(emitter)
                recorder.returned(
                    method, emitter, state, kind, result is Boolean && result, System.nanoTime(),
                    if (method == LpScopeObservation.Method.REPLACE) access.shape(state.model) else null,
                )
            } catch (failure: Throwable) {
                recorder.fail("return:${failure.javaClass.simpleName}")
            }
        }
    }

    fun targetMethods(): Map<String, Map<String, LpScopeObservation.Method>> = methods

    fun fieldSchema(loader: ClassLoader): Map<String, List<String>> = Access.schema.mapValues { (type, names) ->
        val actual = Class.forName(type, false, loader)
        names.map { name ->
            val field = actual.getDeclaredField(name)
            "$name:${field.type.name}"
        }
    }

    fun schema(bytes: ByteArray, selected: Map<String, LpScopeObservation.Method>): List<Map<String, Any?>> {
        val model = ClassFile.of().parse(bytes)
        return selected.map { (name, tag) ->
            val method = model.methods().single {
                it.methodName().stringValue() == name &&
                    it.flags().flagsMask() and 8 == 0
            }
            val type = method.methodTypeSymbol()
            if (tag == LpScopeObservation.Method.REPLACE) {
                require(method.flags().flagsMask() and 2 != 0)
                require(type.descriptorString() ==
                    "(Lcom/eignex/klause/lp/engine/LpBoundTrail;Lcom/eignex/klause/util/Cancellation;" +
                    "ZLcom/eignex/klause/lp/engine/LpLayoutRemap;)Z")
            }
            require(type.parameterCount() <= 16)
            require(
                type.parameterList().all {
                it.descriptorString().startsWith("L") ||
                    it.descriptorString().startsWith("[") || it.descriptorString() == "Z"
            }
            )
            val returns = method.code().orElseThrow().count { it is ReturnInstruction }
            mapOf(
                "name" to name,
                "descriptor" to type.descriptorString(),
                "tag" to tag.ordinal,
                "normalReturns" to returns,
            )
        }
    }

    fun schemaText(schema: List<Map<String, Any?>>): String = schema.joinToString("\n", postfix = "\n") {
        listOf(it["name"], it["descriptor"], it["tag"], it["normalReturns"]).joinToString("\t")
    }

    fun rewrite(bytes: ByteArray, selected: Map<String, LpScopeObservation.Method>, loader: ClassLoader?): ByteArray {
        schema(bytes, selected)
        // Resource parsing avoids recursively defining an application class while transforming it.
        val resolver = ClassHierarchyResolver.ofResourceParsing(requireNotNull(loader))
        val factory = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver))
        val model = factory.parse(bytes)
        return factory.transformClass(model) { builder, element ->
            val tag = (element as? MethodModel)?.methodName()?.stringValue()?.let(selected::get)
            if (element is MethodModel && tag != null && element.flags().flagsMask() and 8 == 0) {
                val type = element.methodTypeSymbol()
                builder.transformMethod(
                    element,
                    MethodTransform.transformingCode(object : CodeTransform {
                    override fun atStart(code: CodeBuilder) {
                        emitEntry(code, tag, type)
                    }

                    override fun accept(code: CodeBuilder, part: CodeElement) {
                        if (part is ReturnInstruction) emitReturn(code, tag, part.opcode())
                        code.with(part)
                    }
                })
                )
            } else {
                builder.with(element)
            }
        }
    }

    private fun emitEntry(code: CodeBuilder, tag: LpScopeObservation.Method, type: MethodTypeDesc) {
        code.loadConstant(tag.ordinal).aload(0).loadConstant(type.parameterCount()).anewarray(objectType)
        var slot = 1
        for ((index, parameter) in type.parameterList().withIndex()) {
            code.dup().loadConstant(index)
            if (parameter.descriptorString() == "Z") {
                code.iload(slot).invokestatic(
                    ClassDesc.of("java.lang.Boolean"),
                    "valueOf",
                    MethodTypeDesc.of(ClassDesc.of("java.lang.Boolean"), ConstantDescs.CD_boolean),
                )
            } else {
                code.aload(slot)
            }
            code.aastore()
            slot++
        }
        code.invokestatic(helper, "entered", enterType)
    }

    private fun emitReturn(code: CodeBuilder, tag: LpScopeObservation.Method, opcode: Opcode) {
        when (opcode) {
            Opcode.ARETURN -> code.dup()

            Opcode.IRETURN -> code.dup().invokestatic(
                ClassDesc.of("java.lang.Boolean"),
                "valueOf",
                MethodTypeDesc.of(ClassDesc.of("java.lang.Boolean"), ConstantDescs.CD_boolean),
            )

            Opcode.RETURN -> code.aconst_null()

            else -> error("unsupported return opcode")
        }
        code.loadConstant(tag.ordinal).aload(0).invokestatic(helper, "returned", returnType)
    }

    private class Access {
        private val fields = HashMap<Pair<Class<*>, String>, Field>()

        fun field(value: Any, name: String): Any? {
            val type = value.javaClass
            require(name in schema[type.name].orEmpty())
            val key = type to name
            val accessor = fields[key] ?: run {
                require(fields.size < 128)
                type.getDeclaredField(name).apply { isAccessible = true }.also { fields[key] = it }
            }
            return accessor.get(value)
        }

        fun state(owner: Any): LpScopeObservation.State {
            val state = requireNotNull(field(requireNotNull(field(owner, "trail")), "state"))
            val context = requireNotNull(field(owner, "context"))
            val policy = requireNotNull(field(context, "certificationPolicy"))
            return LpScopeObservation.State(
                state, requireNotNull(field(state, "model")), context, policy,
                requireNotNull(field(context, "engineFactory")),
                scalars(
                    state,
                    listOf(
                        "matrixRevision",
                        "boundRevision",
                        "objectiveRevision",
                        "rowRevision",
                        "popRevision",
                    ),
                ),
                (field(state, "scopeMarks") as List<*>).size,
                field(owner, "closed") as Boolean, field(owner, "workingActive") as Boolean,
                policy.javaClass.name == "${ENGINE}ProductionLpCertificationPolicy",
                number(field(owner, "editAttempts")), number(field(owner, "editSuccesses")),
                scalars(owner, listOf("createdOwners", "closedOwners", "preparationAttempts")), field(owner, "solver"),
            )
        }

        fun shape(model: Any): LpScopeObservation.Shape = LpScopeObservation.Shape(
            model,
            field(model, "matrix") as List<*>,
            (field(model, "rows") as List<*>).size,
            (field(model, "columns") as List<*>).size,
        )

        fun controls(
            method: LpScopeObservation.Method,
            owner: Any,
            arguments: Array<Any?>,
        ): LpScopeObservation.Controls {
            if (method != LpScopeObservation.Method.SOLVE) return LpScopeObservation.Controls(emptyList())
            require(arguments.size == 8)
            val values = scalars(
                owner,
                listOf(
                    "refactorUpdateLimit",
                    "iterationLimit",
                    "workLimit",
                    "trackDegeneracy",
                    "maxRetainedRows",
                ),
            ) + listOf(number(arguments[4]), number(arguments[7])) +
                scalars(
                    requireNotNull(arguments[3]),
                    listOf(
                        "maxRows", "maxColumns", "maxCells", "maxBits",
                        "maxWork", "maxAllocation", "maxTimeNs", "maxImportPivots", "maxPivots",
                    ),
                ) +
                scalars(
                    requireNotNull(arguments[6]),
                    listOf(
                        "maxRounds", "maxAuxiliaries", "maxCoordinates",
                        "maxEntries", "maxBits", "maxWork", "maxAllocation", "maxPivots", "time",
                    ),
                )
            return LpScopeObservation.Controls(
                values,
                listOf(
                    arguments[0],
                    arguments[1],
                    arguments[2],
                    arguments[5],
                    field(owner, "pricing"),
                ),
            )
        }

        private fun scalars(value: Any, names: List<String>): List<Long> = names.map { number(field(value, it)) }
        private fun number(value: Any?): Long = when (value) {
            null -> Long.MIN_VALUE
            is Boolean -> if (value) 1L else 0L
            is Long -> value
            is Int -> value.toLong()
            else -> error("unsupported scalar field")
        }

        companion object {
            val schema = mapOf(
                "${ENGINE}LpScopedSolver" to listOf(
                    "trail", "context", "closed", "workingActive", "editAttempts",
                    "editSuccesses", "createdOwners", "closedOwners", "preparationAttempts", "solver",
                    "refactorUpdateLimit", "iterationLimit", "workLimit", "trackDegeneracy", "maxRetainedRows",
                    "pricing",
                ),
                "${ENGINE}LpBoundTrail" to listOf("state"),
                "${ENGINE}LpExactState" to listOf(
                    "model",
                    "matrixRevision",
                    "boundRevision",
                    "objectiveRevision",
                    "rowRevision",
                    "popRevision",
                    "scopeMarks",
                ),
                "${ENGINE}ExactLpModel" to listOf("matrix", "rows", "columns"),
                "${ENGINE}LpSolveContext" to listOf("certificationPolicy", "engineFactory"),
                "${ENGINE}CertifiedLpResult" to listOf("witness", "bound", "verdict"),
                "${ENGINE}LpRefinementLimits" to listOf(
                    "maxRounds", "maxAuxiliaries", "maxCoordinates", "maxEntries",
                    "maxBits", "maxWork", "maxAllocation", "maxPivots", "time",
                ),
                "com.eignex.klause.simplex.exact.ExactContinuationLimits" to listOf(
                    "maxRows", "maxColumns", "maxCells",
                    "maxBits", "maxWork", "maxAllocation", "maxTimeNs", "maxImportPivots", "maxPivots",
                ),
                "com.eignex.klause.lp.bounding.LpPropagator" to listOf("owner", "modelKey", "proofContext"),
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
