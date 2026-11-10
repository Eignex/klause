package com.eignex.gradle

import org.gradle.api.GradleException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.HexFormat

class LabSdkRepository(
    private val assets: List<Asset> = PINNED_ASSETS,
    private val connect: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
    private val token: () -> String? = { System.getenv("GITHUB_TOKEN")?.takeIf(String::isNotBlank) },
    private val pause: (Long) -> Unit = Thread::sleep,
    private val downloaded: (String) -> Unit = {},
) {
    data class Asset(val extension: String, val id: Long, val sha256: String)

    fun stage(repository: Path, offline: Boolean = false): Path {
        val directory = repository.resolve("lab-bench-v0.1.0")
        Files.createDirectories(directory)
        FileChannel.open(repository.resolve("lab-bench-v0.1.0.lock"), CREATE, WRITE).use { channel ->
            lock(channel).use {
                for (asset in assets) {
                    val destination = directory.resolve("lab-bench-0.1.0.${asset.extension}")
                    if (Files.isRegularFile(destination) && Files.size(destination) <= MAXIMUM_BYTES &&
                        digest(Files.readAllBytes(destination)) == asset.sha256) continue
                    check(!offline) { "Pinned lab SDK ${asset.extension} is absent or corrupt in the offline cache" }
                    val bytes = download(asset)
                    check(bytes.size <= MAXIMUM_BYTES && digest(bytes) == asset.sha256) {
                        "Lab SDK ${asset.extension} checksum mismatch"
                    }
                    val temporary = Files.createTempFile(directory, ".lab-sdk-", ".part")
                    try {
                        Files.write(temporary, bytes)
                        Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                    downloaded(asset.extension)
                }
            }
        }
        return repository
    }

    private fun lock(channel: FileChannel): FileLock {
        while (true) {
            try {
                channel.tryLock()?.let { return it }
            } catch (_: OverlappingFileLockException) {
                // Another build in this JVM owns the same repository.
            }
            Thread.sleep(25)
        }
    }

    private fun download(asset: Asset): ByteArray {
        for (attempt in 1..3) {
            try {
                return request(URI("https://api.github.com/repos/Eignex/eignex-lab/releases/assets/${asset.id}"))
            } catch (failure: IOException) {
                if (attempt == 3 || failure is HttpFailure && !failure.retryable) {
                    throw GradleException("Could not stage the checksum-pinned lab SDK ${asset.extension}", failure)
                }
                pause(1_000L shl (attempt - 1))
            }
        }
        error("Lab SDK download attempts exhausted")
    }

    private fun request(initial: URI): ByteArray {
        var uri = initial
        repeat(6) {
            check(uri.scheme == "https" && uri.host in DOWNLOAD_HOSTS && uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443)) { "Lab SDK redirect has an unsupported destination" }
            val connection = connect(uri)
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/octet-stream")
                connection.setRequestProperty("User-Agent", "klause-lab-sdk")
                if (uri.host == "api.github.com") {
                    token()?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                }
                val status = connection.responseCode
                if (status == 200) return connection.inputStream.use { it.readNBytes(MAXIMUM_BYTES + 1) }
                if (status in setOf(301, 302, 303, 307, 308)) {
                    uri = uri.resolve(connection.getHeaderField("Location") ?: error("Lab SDK redirect has no location"))
                } else {
                    val remaining = connection.getHeaderField("X-RateLimit-Remaining")?.toLongOrNull()
                    val reset = connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                    val retryAfter = connection.getHeaderField("Retry-After")
                    val limited = status == 429 || status == 403 || remaining == 0L || retryAfter != null
                    throw HttpFailure(
                        "Lab SDK download returned HTTP $status from ${uri.host}; rate remaining ${remaining ?: "unknown"}" +
                            (reset?.let { "; reset at epoch second $it" } ?: "") +
                            (if (limited) "; automatic retries stopped" else ""),
                        retryable = !limited && (status == 408 || status in 500..599),
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Lab SDK download exceeded its redirect limit")
    }

    private class HttpFailure(message: String, val retryable: Boolean) : IOException(message)

    companion object {
        private const val MAXIMUM_BYTES = 1_048_576
        private val DOWNLOAD_HOSTS = setOf(
            "api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
        )
        private val PINNED_ASSETS = listOf(
            Asset("module", 684339118, "2394bfcd15474a473cdf34827b99cb4a03dc3bdb270ebd705ef2dd2c8298a489"),
            Asset("jar", 684339109, "53247d50debd77f6c1d7fa314ffa5ddb6c0fe0632c2d482b01c7704afe7beba4"),
            Asset("pom", 684339115, "a25c59d8bd1520368789459fd42a64bb1a5c376762ea86ca1de6b86e4133f160"),
        )

        private fun digest(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
