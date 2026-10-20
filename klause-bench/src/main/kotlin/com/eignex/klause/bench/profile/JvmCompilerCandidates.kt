package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

internal class JvmCompilerCandidates(private val file: File) : AutoCloseable {
    private val db = savedIndex(file).apply {
        createStatement().use {
            it.execute("CREATE TABLE bindings(record INTEGER PRIMARY KEY, context TEXT)")
            it.execute(
                "CREATE TABLE contexts(record INTEGER PRIMARY KEY, reference TEXT, " +
                    "method_count INTEGER, used INTEGER DEFAULT 0)",
            )
        }
    }
    private val insertBinding = db.prepareStatement("INSERT INTO bindings VALUES (?,?)")
    private val findBinding = db.prepareStatement("SELECT context FROM bindings WHERE record=?")
    private val insertContext = db.prepareStatement(
        "INSERT INTO contexts(record,reference,method_count) VALUES (?,?,?)",
    )
    private val findContext = db.prepareStatement("SELECT reference,method_count FROM contexts WHERE record=?")
    private val markUsed = db.prepareStatement("UPDATE contexts SET used=1 WHERE record=?")
    private data class Context(val reference: JsonObject, val methodCount: Long)

    private val contextCache = JvmSavedReferenceCache<Long, Context>()
    private val candidateCache = JvmSavedReferenceCache<Pair<Long, Long>, JsonObject>()
    private var schemaSeen = false
    private var legacy = false
    private var bindingSchema = false
    private var bindingsDone = false
    private var ended = false
    private var records = 1L
    private var bindings = 0L
    private var contexts = 0L

    @Suppress("LongMethod")
    fun accept(row: JsonObject): JsonObject? {
        if (!schemaSeen) {
            schemaSeen = true
            return schema(row)
        }
        if (legacy) return row
        require(!ended) { "Candidate report continues after its end" }
        val kind = row.savedString("record")
        if (kind == "candidate-report-end") {
            require(
                row == buildJsonObject {
                put("record", "candidate-report-end")
                put("recordsBeforeEnd", records)
                put("bindingReferences", bindings)
                put("candidateContexts", contexts)
            } && bindingSchema && bindings > 0
            ) { "Complete candidate report counts differ" }
            db.commit()
            checkSize()
            db.createStatement().use { statement ->
                statement.executeQuery("SELECT 1 FROM contexts WHERE used=0 LIMIT 1").use {
                    require(!it.next()) { "Candidate context was never referenced" }
                }
            }
            ended = true
            return null
        }
        records++
        return when (kind) {
            "original-binding-schema" -> {
                require(!bindingSchema && !bindingsDone && bindings == 0L)
                bindingSchema = true
                row
            }

            "original-binding-reference" -> {
                require(bindingSchema && !bindingsDone && row.savedLong("originalRecordOrdinal") == bindings + 1)
                val context = row.getValue("originalClassContext").jsonObject
                bindings++
                insertBinding.setLong(1, bindings)
                insertBinding.setString(2, context.toString())
                insertBinding.executeUpdate()
                checkSize()
                row
            }

            else -> {
                bindingsDone = true
                when (kind) {
                    "declaration-candidate-context" -> {
                        storeContext(row)
                        null
                    }

                    "compiler-observation-candidates", "xml-observation-candidates" -> observation(row, kind)

                    "original-case", "original-recording-sections", "original-xml-sections" -> row

                    else -> throw IllegalArgumentException("Unknown normalized candidate report record: $kind")
                }
            }
        }
    }

    private fun schema(row: JsonObject): JsonObject {
        require(row["record"] == JsonPrimitive("schema"))
        legacy = row["encoding"] == JsonPrimitive("compiler-bytecode-candidates-v2")
        if (legacy) return row
        require(
            row.keys == setOf("record", "encoding", "bindingInputId", "censusInputId", "deniedProofFlags") &&
            row["encoding"] == JsonPrimitive("compiler-bytecode-candidates-v3") &&
            row.savedString("bindingInputId").isNotEmpty() && row.savedString("censusInputId").isNotEmpty()
        )
        val defaults = row.getValue("deniedProofFlags").jsonObject
        require(defaults.keys.toList() == COHORT_PROOF_FLAGS && defaults.values.all { it == JsonPrimitive(false) })
        return buildJsonObject {
            put("record", "schema")
            put("encoding", "compiler-bytecode-candidates-v2")
            put("bindingInputId", row.getValue("bindingInputId"))
            put("censusInputId", row.getValue("censusInputId"))
            defaults.forEach { (key, value) -> put(key, value) }
        }
    }

    private fun storeContext(row: JsonObject) {
        require(row.keys == setOf("record", "bindingRecordOrdinal", "reference", "methodCount"))
        val ordinal = row.savedLong("bindingRecordOrdinal")
        require(ordinal in 1..bindings)
        findBinding.setLong(1, ordinal)
        val original = findBinding.executeQuery().use {
            require(it.next())
            JvmSavedJson.parse(it.getString(1)).jsonObject
        }
        val reference = row.getValue("reference").jsonObject
        val keys = CONTEXT_FIELDS.toSet() +
            setOf("bindingRecordOrdinal", "originalClassIdentity", "originalSyntaxOrigin")
        require(
            original["status"] == JsonPrimitive("joined-candidates") &&
            CONTEXT_FIELDS.all { it in original && reference[it] == original[it] } &&
            reference.keys == keys &&
            reference.savedLong("bindingRecordOrdinal") == ordinal && row.savedLong("methodCount") >= 0
        )
        insertContext.setLong(1, ordinal)
        insertContext.setString(2, reference.toString())
        insertContext.setLong(3, row.savedLong("methodCount"))
        insertContext.executeUpdate()
        contexts++
        checkSize()
    }

    private fun observation(row: JsonObject, kind: String): JsonObject {
        require(COHORT_PROOF_FLAGS.none { it in row })
        val original = row.getValue("originalObservationCopiedVerbatim").jsonObject
        val collection = row.savedString("collection")
        val candidates = if (kind == "compiler-observation-candidates") {
            require(collection in setOf("compilations", "inlining"))
            val fields = if (collection == "inlining") listOf("caller", "callee") else listOf("method")
            val methods = row.getValue("methodCandidates").jsonObject
            require(methods.keys == fields.toSet())
            JsonObject(
                fields.associateWith { field ->
                expand(methods.getValue(field).jsonObject).also {
                    require(it["observedMethodCopiedVerbatim"] == (original[field] ?: JsonNull))
                }
            }
            )
        } else {
            require(collection in setOf("queued", "tasks", "nmethods"))
            val methods = expand(row.getValue("methodCandidates").jsonObject)
            val attributes = (original["rawAttributes"] ?: JsonObject(emptyMap())).jsonObject
            require(methods["observedMethodCopiedVerbatim"] == xmlMethod(attributes["method"]))
            methods
        }
        return JsonObject(
            row + ("methodCandidates" to candidates) + COHORT_PROOF_FLAGS.associateWith {
            JsonPrimitive(false)
        }
        )
    }

    private fun expand(value: JsonObject): JsonObject {
        require(
            value.keys == setOf(
            "observedMethodCopiedVerbatim",
            "declarationCandidateReferences",
            "methodIdentityParsed",
        )
        )
        val observed = value.getValue("observedMethodCopiedVerbatim")
        val parsed = savedMethodKey(observed) != null
        require(value["methodIdentityParsed"] == JsonPrimitive(parsed))
        val references = value.getValue("declarationCandidateReferences").jsonArray
        require(references.size <= 1_000_000 && (references.isEmpty() || parsed))
        var previous: Pair<Long, Long>? = null
        val candidates = references.map { raw ->
            val pair = raw.jsonArray
            require(pair.size == 2)
            val record = savedInteger(pair[0])
            val position = savedInteger(pair[1])
            previous?.let { require(record > it.first || (record == it.first && position > it.second)) }
            previous = record to position
            val context = contextCache.getOrLoad(record) {
                findContext.setLong(1, record)
                val (text, count) = findContext.executeQuery().use {
                    require(it.next()) { "Candidate context is unbound" }
                    it.getString(1) to it.getLong(2)
                }
                val reference = JvmSavedJson.parse(text).jsonObject
                markUsed.setLong(1, record)
                markUsed.executeUpdate()
                Context(reference, count) to text.encodeToByteArray().size.toLong()
            }
            require(position >= 0 && position < context.methodCount)
            candidateCache.getOrLoad(record to position) {
                val candidate = JsonObject(context.reference + ("methodIndex" to JsonPrimitive(position)))
                candidate to candidate.toString().encodeToByteArray().size.toLong()
            }
        }
        return buildJsonObject {
            put("observedMethodCopiedVerbatim", observed)
            put("declarationCandidates", JsonArray(candidates))
            put("methodIdentityParsed", parsed)
            COHORT_PROOF_FLAGS.forEach { put(it, false) }
        }
    }

    fun finish() {
        require(schemaSeen && (legacy || ended)) { "Complete candidate report end is absent" }
    }

    private fun checkSize() = check(file.length() <= MAX_SAVED_INDEX_BYTES) { "Candidate index exceeds bounds" }

    override fun close() {
        contextCache.clear()
        candidateCache.clear()
        db.close()
    }

    private companion object {
        val CONTEXT_FIELDS = listOf(
            "bytecodeClassOrdinal",
            "studySourceHead",
            "syntaxArchiveId",
            "syntaxClassOrdinal",
            "syntaxLineReportFile",
        )

        fun xmlMethod(value: JsonElement?): JsonElement {
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return JsonNull
            val parts = text.split(Regex("(?U)[\\s\\u001c-\\u001f]+")).filter { it.isNotEmpty() }
            return if (parts.size < 3) {
                JsonNull
            } else {
                JsonPrimitive(parts[0].replace('/', '.') + "." + parts[1] + parts.drop(2).joinToString(""))
            }
        }
    }
}
