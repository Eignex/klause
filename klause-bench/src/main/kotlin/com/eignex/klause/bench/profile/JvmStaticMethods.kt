package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.sql.Connection

internal class JvmStaticMethods(private val db: Connection, private val checkSize: () -> Unit) {
    init {
        db.createStatement().use {
            it.execute(
                "CREATE TABLE static_classes(binding TEXT, head TEXT, ordinal INTEGER, record INTEGER, body TEXT, " +
                    "PRIMARY KEY(binding,head,ordinal), UNIQUE(binding,record))",
            )
            it.execute(
                "CREATE TABLE static_methods(binding TEXT, head TEXT, ordinal INTEGER, method INTEGER, " +
                    "name TEXT, descriptor TEXT, PRIMARY KEY(binding,head,ordinal,method))",
            )
        }
    }

    private val insertClass = db.prepareStatement("INSERT INTO static_classes VALUES (?,?,?,?,?)")
    private val insertMethod = db.prepareStatement("INSERT INTO static_methods VALUES (?,?,?,?,?,?)")
    private val findClass = db.prepareStatement(
        "SELECT body FROM static_classes WHERE binding=? AND head=? AND ordinal=?",
    )
    private val findMethod = db.prepareStatement(
        "SELECT name,descriptor FROM static_methods WHERE binding=? AND head=? AND ordinal=? AND method=?",
    )
    private var complete = false

    fun add(binding: String, member: String, ordinal: Long, row: JsonObject, identity: JsonObject) {
        check(!complete)
        val joined = row["status"] == JsonPrimitive("joined-candidates")
        require(joined || row["status"] == JsonPrimitive("bytecode-metadata-unavailable"))
        val head = row.savedString("studySourceHead")
        val syntaxOrdinal = row.savedLong("syntaxClassOrdinal")
        require(ordinal > 0 && syntaxOrdinal >= 0)
        val methods = if (joined) row.getValue("methods").jsonArray else null
        if (joined) require(row.savedString("internalName").isNotEmpty())
        val reference = buildJsonObject {
            put("originalStaticMember", member)
            put("originalStaticRecordOrdinal", ordinal)
            put("originalStaticRawRecordIdentity", identity)
            put("methodCount", methods?.let { JsonPrimitive(it.size) } ?: JsonNull)
            put("originalClassContext", JsonObject(row.filterKeys { it != "methods" }))
        }
        insertClass.setString(1, binding)
        insertClass.setString(2, head)
        insertClass.setLong(3, syntaxOrdinal)
        insertClass.setLong(4, ordinal)
        insertClass.setString(5, reference.toString())
        insertClass.executeUpdate()
        methods?.forEachIndexed { position, value ->
            val method = value.jsonObject
            require(method.savedLong("methodIndex") == position.toLong())
            val name = method.savedString("name")
            val descriptor = method.savedString("descriptor")
            require(name.isNotEmpty() && descriptor.startsWith('('))
            insertMethod.setString(1, binding)
            insertMethod.setString(2, head)
            insertMethod.setLong(3, syntaxOrdinal)
            insertMethod.setInt(4, position)
            insertMethod.setString(5, name)
            insertMethod.setString(6, descriptor)
            insertMethod.addBatch()
        }
        insertMethod.executeBatch()
        checkSize()
    }

    fun seal() {
        check(!complete)
        db.createStatement().use { query ->
            for ((left, right) in listOf("classes" to "static_classes", "static_classes" to "classes")) {
                query.executeQuery(
                    "SELECT COUNT(*) FROM $left a LEFT JOIN $right b " +
                        "ON a.binding=b.binding AND a.head=b.head AND a.ordinal=b.ordinal WHERE b.binding IS NULL",
                ).use { require(it.next() && it.getLong(1) == 0L) { "Static and source class universes differ" } }
            }
            query.executeQuery(
                "SELECT a.body,b.body FROM classes a JOIN static_classes b " +
                    "ON a.binding=b.binding AND a.head=b.head AND a.ordinal=b.ordinal",
            ).use { rows ->
                while (rows.next()) {
                    val audit = JvmSavedJson.parse(rows.getString(1)).jsonObject
                    val static = JvmSavedJson.parse(rows.getString(2)).jsonObject
                    val context = static.getValue("originalClassContext").jsonObject
                    require(
                        audit["internalName"] == (context["internalName"] ?: JsonNull) &&
                            audit["originalClassOrigin"] == context["syntaxOrigin"] &&
                            audit["methodCount"] == static["methodCount"],
                    ) { "Static class identity or complete method order differs from its source audit" }
                }
            }
            query.executeQuery(
                "SELECT COUNT(*) FROM methods a LEFT JOIN static_methods b ON a.binding=b.binding " +
                    "AND a.head=b.head AND a.ordinal=b.ordinal AND a.method=b.method " +
                    "WHERE b.binding IS NULL OR a.name<>b.name OR a.descriptor<>b.descriptor",
            ).use { require(it.next() && it.getLong(1) == 0L) { "A formal method differs from its static descriptor" } }
        }
        complete = true
        checkSize()
    }

    fun link(binding: String, observed: JsonElement, candidate: JsonObject): JsonObject {
        check(complete) { "Complete static methods must be sealed before observations" }
        val head = candidate.savedString("studySourceHead")
        val ordinal = candidate.savedLong("syntaxClassOrdinal")
        findClass.setString(1, binding)
        findClass.setString(2, head)
        findClass.setLong(3, ordinal)
        val reference = findClass.executeQuery().use {
            require(it.next()) { "A candidate has no complete static class" }
            JvmSavedJson.parse(it.getString(1)).jsonObject
        }
        val context = reference.getValue("originalClassContext").jsonObject
        require(context["status"] == JsonPrimitive("joined-candidates"))
        require(reference["originalStaticRecordOrdinal"] == candidate["bindingRecordOrdinal"])
        require(CONTEXT_FIELDS.all { context[it] == candidate[it] }) { "Original static candidate context differs" }
        require(context["classIdentity"] == candidate["originalClassIdentity"])
        require(context["syntaxOrigin"] == candidate["originalSyntaxOrigin"])
        val key = requireNotNull(savedMethodKey(observed))
        require(context["internalName"] == JsonPrimitive(key.first))
        findMethod.setString(1, binding)
        findMethod.setString(2, head)
        findMethod.setLong(3, ordinal)
        findMethod.setLong(4, candidate.savedLong("methodIndex"))
        findMethod.executeQuery().use {
            require(it.next() && it.getString(1) == key.second && it.getString(2) == key.third) {
                "Observed method differs from the complete static descriptor table"
            }
        }
        return reference
    }

    companion object {
        private val CONTEXT_FIELDS = setOf(
            "bytecodeClassOrdinal", "studySourceHead", "syntaxArchiveId", "syntaxClassOrdinal",
            "syntaxLineReportFile",
        )

        fun read(index: JvmSourceCompilerIndex, item: JsonObject, paths: Map<String, File>) {
            val completion = JvmSavedJson.read(
                paths.getValue(item.savedString("completionMember")), JvmSavedJson.Kind.PRODUCER_COMPLETION,
            ).jsonObject
            val classes = completion.savedLong("bytecodeClasses")
            require(classes in 1..100_000)
            val contexts = completion.getValue("sourceContexts").jsonArray.map { it.jsonObject }
            val heads = contexts.map { it.savedString("studySourceHead") }
            require(heads.isNotEmpty() && heads.size < 8 && heads.distinct().size == heads.size)
            val member = item.savedString("reportMember")
            val proofs = completion.getValue("reports").jsonArray.map { it.jsonObject }
            val expected = contexts.flatMap { context ->
                listOf("syntaxLineReportFile", "syntaxNodeCoverageFile").map { context.savedString(it) }
            } + member.substringAfterLast('/')
            require(proofs.map { it.savedString("file") }.sorted() == expected.sorted())
            require(expected.distinct().size == expected.size)
            val proof = proofs.single { it["file"] == JsonPrimitive(member.substringAfterLast('/')) }
            readClasses(index, item, paths.getValue(member), proof, completion, contexts)
            proofs.filter { it !== proof }.forEach { original ->
                val name = original.savedString("file")
                val path = paths.entries.single { it.key.substringAfterLast('/') == name }.value
                JvmSavedReports.read(path, original) { _, _ -> }
            }
        }

        private fun readClasses(
            index: JvmSourceCompilerIndex,
            item: JsonObject,
            file: File,
            proof: JsonObject,
            completion: JsonObject,
            contexts: List<JsonObject>,
        ) {
            val classes = completion.savedLong("bytecodeClasses")
            val heads = contexts.map { it.savedString("studySourceHead") }
            val seen = HashSet<Pair<Long, String>>()
            val counts = heads.associateWith { 0L }.toMutableMap()
            var count = 0L
            JvmSavedReports.read(file, proof) { ordinal, raw ->
                val encoded = JvmSavedJson.parse(raw.decodeToString(throwOnInvalidSequence = true)).jsonObject
                if (ordinal == 0L) {
                    require(
                        encoded["record"] == JsonPrimitive("schema") &&
                            encoded["encoding"] == JsonPrimitive("bytecode-source-bindings-v1"),
                    )
                } else {
                    val row = declaration(encoded)
                    val classOrdinal = row.savedLong("bytecodeClassOrdinal")
                    val head = row.savedString("studySourceHead")
                    require(classOrdinal in 0 until classes && head in heads && seen.add(classOrdinal to head))
                    index.addStatic(
                        item.savedString("id"), item.savedString("reportMember"), ordinal, row,
                        JvmSavedReports.identity(raw),
                    )
                    counts[head] = counts.getValue(head) + 1
                    count++
                }
            }
            require(count == completion.savedLong("sourceContextClassRows"))
            require(seen.map { it.first }.toSet().size.toLong() == classes)
            require(contexts.all { counts.getValue(it.savedString("studySourceHead")) == it.savedLong("classContextRows") })
        }

        private fun declaration(encoded: JsonObject): JsonObject {
            val tables = JvmSavedTables(encoded)
            val row = tables.project(CONTEXT_FIELDS + setOf(
                "status", "internalName", "classIdentity", "syntaxOrigin", "bytecodeArchiveId", "bytecodeArchiveClassOrdinal",
            ))
            if (row["status"] != JsonPrimitive("joined-candidates")) return row
            return JsonObject(
                row + ("methods" to tables.objectArray("methods", setOf("methodIndex", "name", "descriptor"))),
            )
        }
    }
}
