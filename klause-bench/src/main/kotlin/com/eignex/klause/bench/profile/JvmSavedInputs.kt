package com.eignex.klause.bench.profile

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.zip.GZIPInputStream

internal class JvmSavedInputs(private val out: File, private val scratch: File) {
    private val identities = LinkedHashMap<File, JsonObject>()
    private val ids = HashSet<String>()
    val proofs = ArrayList<JsonObject>()
    private var downloaded = 0L
    private var staged = 0L

    @Suppress("LongMethod", "TooGenericExceptionCaught")
    fun authenticate(item: JsonObject): Map<String, File> {
        require(ids.size < 8 && ids.add(item.savedString("id"))) { "Input identity is repeated or excessive" }
        val members = item.getValue("members").jsonArray.map { it.savedString() }
        require(members.isNotEmpty() && members.toSet().size == members.size && members.all(::savedMemberName))
        val manifestFile = File(out, "input-%03d-manifest.json".format(Locale.ROOT, proofs.size))
        savedDownload(
            item.savedString("manifest"),
            manifestFile,
            16L * 1024 * 1024,
            item.savedString("manifestSha256"),
        )
        val manifest = JvmSavedJson.read(manifestFile, JvmSavedJson.Kind.CATALOG_METADATA).jsonObject
        identities[manifestFile] = savedIdentity(manifestFile)
        val archiveIdentity = manifest.getValue("archive").jsonObject
        val files = manifest.getValue("files").jsonObject
        require(files.isNotEmpty() && files.size <= 100_000 && files.keys == members.toSet())
        files.forEach { (name, value) ->
            require(savedMemberName(name))
            savedExpectedIdentity(value.jsonObject)
        }
        savedExpectedIdentity(archiveIdentity)
        require(archiveIdentity.savedLong("size") <= MAX_INPUT_BYTES)
        downloaded += archiveIdentity.savedLong("size")
        require(downloaded <= MAX_INPUT_BYTES) { "Complete archive set exceeds bounds" }
        val archive = File(scratch, "input-%03d.tar.gz".format(Locale.ROOT, proofs.size))
        try {
            savedDownload(item.savedString("archive"), archive, MAX_INPUT_BYTES, archiveIdentity.savedString("sha256"))
        } catch (failure: Throwable) {
            runCatching {
                if (archive.isFile) {
                    val actual = savedIdentity(archive)
                    val privateDirectory = File(out, "private-unverified-inputs").apply { mkdirs() }
                    Files.move(archive.toPath(), File(privateDirectory, archive.name).toPath())
                    File(privateDirectory, "${archive.name}.identity.json").writeText(
                        buildJsonObject {
                        put("input", item.getValue("id"))
                        put("originalArchiveAddress", item.getValue("archive"))
                        put("expectedIdentity", archiveIdentity)
                        put("actualIdentity", actual)
                        put("scope", "Unverified download bytes retained privately; not accepted or published")
                    }.toString() + "\n"
                    )
                }
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        require(savedIdentity(archive) == savedExpectedIdentity(archiveIdentity))
        identities[archive] = savedExpectedIdentity(archiveIdentity)
        val directory = File(scratch, proofs.size.toString())
        check(directory.mkdir())
        val paths = extract(archive, files, directory)
        paths.forEach { (name, path) -> identities[path] = savedExpectedIdentity(files.getValue(name).jsonObject) }
        proofs.add(
            buildJsonObject {
            put("inputCatalogEntryCopiedVerbatim", item)
            put("archive", archiveIdentity)
            put("allArchiveMembersAuthenticatedBeforeInterpretation", true)
            put("selectedMemberIdentities", files)
        }
        )
        return paths
    }

    @Suppress("CyclomaticComplexMethod")
    internal fun extract(archive: File, files: JsonObject, directory: File): Map<String, File> {
        val paths = LinkedHashMap<String, File>()
        var expanded = 0L
        GZIPInputStream(archive.inputStream().buffered()).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val member = tar.nextEntry ?: break
                    require(
                        member.isFile && !member.isSparse && !member.isSymbolicLink && !member.isLink &&
                        member.name in files && member.name !in paths
                    ) { "Unexpected archive member" }
                    val expected = files.getValue(member.name).jsonObject
                    val size = expected.savedLong("size")
                    require(size in 0..512L * 1024 * 1024)
                    expanded += size
                    staged += size
                    require(
                        member.size == size &&
                        expanded <= 16L * 1024 * 1024 * 1024 && staged <= MAX_INPUT_BYTES
                    )
                    val path = File(directory, paths.size.toString())
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    Files.newOutputStream(path.toPath(), StandardOpenOption.CREATE_NEW).use { target ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = tar.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= size)
                            digest.update(buffer, 0, count)
                            target.write(buffer, 0, count)
                        }
                    }
                    require(
                        copied == size &&
                            HexFormat.of().formatHex(digest.digest()) == expected.savedString("sha256"),
                    )
                    paths[member.name] = path
                }
                require(paths.keys == files.keys) { "Complete archive member census differs" }
                gzip.transferTo(OutputStream.nullOutputStream())
            }
        }
        return paths
    }

    fun verifyUnchanged() {
        identities.forEach { (file, expected) ->
            require(savedIdentity(file) == expected) { "Authenticated input changed during review" }
        }
    }

    private companion object {
        const val MAX_INPUT_BYTES = 2L * 1024 * 1024 * 1024
    }
}

internal fun savedMemberName(value: String): Boolean = value.isNotEmpty() && value.length <= 4096 &&
    '\\' !in value && ':' !in value && value.none(Char::isISOControl) &&
    value.split('/').none { it.isEmpty() || it == "." || it == ".." }

internal fun JsonElement.savedString(): String {
    val primitive = this as? JsonPrimitive
    require(primitive != null && primitive.isString)
    return primitive.content
}

internal fun JsonObject.savedString(key: String): String = getValue(key).savedString()

internal fun JsonObject.savedLong(key: String): Long {
    val value = getValue(key) as? JsonPrimitive
    require(value != null && !value.isString)
    return requireNotNull(value.content.toLongOrNull()) { "Expected an integer $key" }
}

internal fun savedExpectedIdentity(value: JsonObject): JsonObject {
    require(value.savedLong("size") >= 0 && Regex("[0-9a-f]{64}").matches(value.savedString("sha256")))
    return buildJsonObject {
        put("size", value.getValue("size"))
        put("sha256", value.getValue("sha256"))
    }
}

internal fun savedIdentity(file: File): JsonObject {
    require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { source ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            size += count
        }
    }
    return buildJsonObject {
        put("size", size)
        put("sha256", HexFormat.of().formatHex(digest.digest()))
    }
}

internal fun savedDownload(address: String, target: File, maximum: Long, expected: String) {
    val uri = URI(address)
    require(
        address.length <= 4096 && uri.scheme == "https" && uri.host != null &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            Regex("[0-9a-f]{64}").matches(expected),
    )
    val connection = uri.toURL().openConnection().apply {
        connectTimeout = 30_000
        readTimeout = 30_000
    }
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    connection.getInputStream().use { source ->
        require(connection.url.protocol == "https")
        Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                size += count
                require(size <= maximum) { "Download exceeds bounds" }
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
    }
    require(HexFormat.of().formatHex(digest.digest()) == expected) { "Download differs from pinned identity" }
}
