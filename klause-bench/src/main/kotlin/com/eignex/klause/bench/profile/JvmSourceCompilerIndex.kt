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
import java.sql.Connection
import java.sql.DriverManager

internal class JvmSourceCompilerIndex(private val file: File) : AutoCloseable {
    private val db = savedIndex(file).apply {
        createStatement().use { statement ->
            statement.execute(
                "CREATE TABLE classes(binding TEXT, head TEXT, ordinal INTEGER, body TEXT, " +
                    "PRIMARY KEY(binding,head,ordinal))",
            )
            statement.execute(
                "CREATE TABLE methods(binding TEXT, head TEXT, ordinal INTEGER, method INTEGER, " +
                    "position INTEGER, name TEXT, descriptor TEXT, PRIMARY KEY(binding,head,ordinal,method))",
            )
            statement.execute(
                "CREATE TABLE links(binding TEXT, observed TEXT, candidate TEXT, reference INTEGER, " +
                    "PRIMARY KEY(binding,observed,candidate))",
            )
            statement.execute(
                "CREATE TABLE method_links(binding TEXT, observed TEXT, parsed TEXT, candidates TEXT, " +
                    "reference INTEGER, PRIMARY KEY(binding,observed,parsed,candidates))",
            )
        }
    }
    private val insertClass = db.prepareStatement("INSERT INTO classes VALUES (?,?,?,?)")
    private val insertMethod = db.prepareStatement("INSERT INTO methods VALUES (?,?,?,?,?,?,?)")
    private val findClass = db.prepareStatement("SELECT body FROM classes WHERE binding=? AND head=? AND ordinal=?")
    private val findMethod = db.prepareStatement(
        "SELECT position,name,descriptor FROM methods WHERE binding=? AND head=? AND ordinal=? AND method=?",
    )
    private val findLink = db.prepareStatement(
        "SELECT reference FROM links WHERE binding=? AND observed=? AND candidate=?",
    )
    private val insertLink = db.prepareStatement("INSERT INTO links VALUES (?,?,?,?)")
    private val findMethodLink = db.prepareStatement(
        "SELECT reference FROM method_links WHERE binding=? AND observed=? AND parsed=? AND candidates=?",
    )
    private val insertMethodLink = db.prepareStatement("INSERT INTO method_links VALUES (?,?,?,?,?)")
    private val linkCache = JvmSavedReferenceCache<LinkKey, Long>()
    private var linkReferences = 0L
    private var methodReferences = 0L

    // Identity avoids hashing full candidate bodies; misses use the complete serialized tuple.
    private class LinkKey(val binding: String, val observed: JsonElement, val candidate: JsonObject) {
        override fun equals(other: Any?): Boolean {
            if (other !is LinkKey) return false
            return binding == other.binding && observed == other.observed && candidate === other.candidate
        }

        override fun hashCode(): Int =
            31 * (31 * binding.hashCode() + observed.hashCode()) + System.identityHashCode(candidate)
    }

    fun beginLinkReport() {
        linkCache.clear()
        db.createStatement().use {
            it.executeUpdate("DELETE FROM links")
            it.executeUpdate("DELETE FROM method_links")
        }
        linkReferences = 0L
        methodReferences = 0L
    }

    fun methodReference(
        binding: String,
        observed: JsonElement,
        parsed: JsonElement,
        candidates: JsonArray,
        define: (Long) -> Unit,
    ): Long {
        val fields = listOf(binding, observed.toString(), parsed.toString(), candidates.toString())
        fields.forEachIndexed { index, text -> findMethodLink.setString(index + 1, text) }
        val existing = findMethodLink.executeQuery().use { if (it.next()) it.getLong(1) else null }
        if (existing != null) return existing
        val reference = methodReferences++
        fields.forEachIndexed { index, text -> insertMethodLink.setString(index + 1, text) }
        insertMethodLink.setLong(5, reference)
        insertMethodLink.executeUpdate()
        checkSize()
        define(reference)
        return reference
    }

    fun linkReference(
        binding: String,
        observed: JsonElement,
        candidate: JsonObject,
        define: (Long, JsonObject) -> Unit,
    ): Long {
        return linkCache.getOrLoad(LinkKey(binding, observed, candidate)) {
            val observedText = observed.toString()
            val candidateText = candidate.toString()
            val weight = binding.encodeToByteArray().size.toLong() + observedText.encodeToByteArray().size +
                candidateText.encodeToByteArray().size
            findLink.setString(1, binding)
            findLink.setString(2, observedText)
            findLink.setString(3, candidateText)
            val existing = findLink.executeQuery().use { if (it.next()) it.getLong(1) else null }
            if (existing != null) return@getOrLoad existing to weight
            val value = link(binding, observed, candidate)
            val reference = linkReferences++
            insertLink.setString(1, binding)
            insertLink.setString(2, observedText)
            insertLink.setString(3, candidateText)
            insertLink.setLong(4, reference)
            insertLink.executeUpdate()
            checkSize()
            define(reference, value)
            reference to weight
        }
    }

    fun add(binding: String, head: String, member: String, ordinal: Long, row: JsonObject, identity: JsonObject) {
        require(row["record"] == JsonPrimitive("class-audit") && row.savedLong("originalSyntaxClassOrdinal") >= 0)
        val checks = row.getValue("allOriginalMethodFormalChecks").jsonArray
        val order = row.getValue("originalMethodIndexOrder")
        val unmatched = row.getValue("noFormalAlternativeMethodIndexes")
        if (order == JsonNull) {
            require(checks.isEmpty() && unmatched == JsonNull)
        } else {
            val positions = order.jsonArray.map(::savedInteger)
            val absent = unmatched.jsonArray.map(::savedInteger)
            val checked = checks.map { it.jsonObject.savedLong("methodIndex") }
            require(
                positions == positions.indices.map(Int::toLong) &&
                    absent == absent.distinct().sorted() && checked == checked.distinct().sorted() &&
                    (checked + absent).sorted() == positions,
            ) { "Complete original method order differs" }
        }
        val stored = buildJsonObject {
            put("sourceAuditMember", member)
            put("sourceAuditRecordOrdinal", ordinal)
            put("sourceAuditRecordIdentity", identity)
            put("internalName", row.getValue("internalName"))
            put("originalClassOrigin", row.getValue("originalClassOrigin"))
            put("methodCount", if (order == JsonNull) JsonNull else JsonPrimitive(order.jsonArray.size))
        }
        val classOrdinal = row.savedLong("originalSyntaxClassOrdinal")
        insertClass.setString(1, binding)
        insertClass.setString(2, head)
        insertClass.setLong(3, classOrdinal)
        insertClass.setString(4, stored.toString())
        insertClass.executeUpdate()
        checks.forEachIndexed { position, value ->
            val check = value.jsonObject
            insertMethod.setString(1, binding)
            insertMethod.setString(2, head)
            insertMethod.setLong(3, classOrdinal)
            insertMethod.setLong(4, check.savedLong("methodIndex"))
            insertMethod.setInt(5, position)
            insertMethod.setString(6, check.savedString("name"))
            insertMethod.setString(7, check.savedString("descriptor"))
            insertMethod.addBatch()
        }
        insertMethod.executeBatch()
        checkSize()
    }

    fun link(binding: String, observed: JsonElement, candidate: JsonObject): JsonObject {
        findClass.setString(1, binding)
        findClass.setString(2, candidate.savedString("studySourceHead"))
        findClass.setLong(3, candidate.savedLong("syntaxClassOrdinal"))
        val stored = findClass.executeQuery().use { rows ->
            require(rows.next()) { "A declaration candidate has no complete source audit" }
            JvmSavedJson.parse(rows.getString(1)).jsonObject
        }
        val key = requireNotNull(savedMethodKey(observed)) { "An observed method is unparsed" }
        val owner = stored.getValue("internalName")
        require(
            (owner == JsonNull || owner == JsonPrimitive(key.first)) &&
                stored.getValue("originalClassOrigin") == candidate.getValue("originalSyntaxOrigin"),
        )
        val position = candidate.savedLong("methodIndex")
        val count = stored.getValue("methodCount")
        require(position >= 0 && (count == JsonNull || position < savedInteger(count)))
        findMethod.setString(1, binding)
        findMethod.setString(2, candidate.savedString("studySourceHead"))
        findMethod.setLong(3, candidate.savedLong("syntaxClassOrdinal"))
        findMethod.setLong(4, position)
        val checked = findMethod.executeQuery().use { rows ->
            if (!rows.next()) {
                null
            } else {
                require(rows.getString(2) == key.second && rows.getString(3) == key.third)
                buildJsonObject {
                    put("allOriginalMethodFormalChecksIndex", rows.getLong(1))
                    put("methodIndex", position)
                }
            }
        }
        return buildJsonObject {
            stored.filterKeys { it !in setOf("internalName", "originalClassOrigin", "methodCount") }
                .forEach { (name, value) -> put(name, value) }
            put("originalDeclarationCandidateCopiedVerbatim", candidate)
            put("recordedOwnerAgreement", if (owner == JsonNull) JsonNull else JsonPrimitive(true))
            put(
                "formalAlternativeStatus",
                when {
                    count == JsonNull -> "unavailable-method-order"
                    checked == null -> "no-formal-alternative"
                    else -> "all-formal-alternatives-retained"
                },
            )
            put("recordedMethodFormalAuditReference", checked ?: JsonNull)
            put("allFormalAlternativesRetainedByWholeOriginalAuditReference", true)
            put("unmatchedIsNotAbsenceZeroOrRetirement", true)
            SOURCE_PROOF_FLAGS.forEach { put(it, false) }
        }
    }

    fun finish() {
        db.commit()
        checkSize()
    }

    private fun checkSize() = check(file.length() <= MAX_SAVED_INDEX_BYTES) { "Source index exceeds bounds" }

    override fun close() {
        linkCache.clear()
        db.close()
    }
}

internal const val MAX_SAVED_INDEX_BYTES = 8L * 1024 * 1024 * 1024

@Suppress("TooGenericExceptionCaught")
internal fun savedIndex(file: File): Connection {
    check(!file.exists()) { "A fresh owned index is required" }
    val db = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    try {
        db.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=OFF")
            statement.execute("PRAGMA cache_size=-16384")
            statement.execute("PRAGMA page_size=4096")
            statement.execute("PRAGMA max_page_count=2097152")
        }
        db.autoCommit = false
        return db
    } catch (failure: Throwable) {
        db.close()
        throw failure
    }
}

internal fun savedInteger(value: JsonElement): Long {
    require(value is JsonPrimitive && !value.isString)
    return requireNotNull(value.content.toLongOrNull())
}

internal fun savedMethodKey(value: JsonElement): Triple<String, String, String>? {
    if (value !is JsonPrimitive || !value.isString) return null
    val text = value.content
    val descriptor = text.indexOf('(')
    if (descriptor < 0) return null
    val qualified = text.substring(0, descriptor)
    val split = qualified.lastIndexOf('.')
    if (split <= 0 || split == qualified.lastIndex) return null
    return Triple(
        qualified.substring(0, split).replace('.', '/'),
        qualified.substring(split + 1),
        text.substring(descriptor),
    )
}

internal val SOURCE_PROOF_FLAGS = listOf(
    "sourcePhaseToJvmDescriptorMappingProven",
    "fullSourcePhaseCoverageProven",
    "loadedClassSelectionProven",
    "generatedBodySelectionProven",
    "physicalCompilerRootCoverageProven",
    "compilerElapsedTimeProven",
    "acceptedSavingsCredit",
)

internal val COHORT_PROOF_FLAGS = listOf(
    "loadedClassSelectionProven", "runtimeCalleeSelectionProven",
    "generatedBodySelectionProven", "sourcePhaseToJvmDescriptorMappingProven", "fullSourcePhaseCoverageProven",
    "physicalCompilerRootCoverageProven", "compilerElapsedTimeProven", "acceptedSavingsCredit",
    "runtimeSourceBindingProven", "rootCensusProven", "codeSizeProven", "sourceOutcomesValidated",
    "completeStateOrderRngWorkEquivalenceProven", "wholeCliRuntimeEquivalenceProven",
    "wholeFixtureAllocationEquivalenceProven",
)
