package com.eignex.gradle

import org.gradle.api.GradleException
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LabSdkRepositoryTest {
    private val bytes = "verified SDK".toByteArray()
    private val checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private val assets = listOf(LabSdkRepository.Asset("jar", 123, checksum))

    @Test fun `fresh builds reuse the verified persistent repository without a request`() = withRepository { repository ->
        var requests = 0
        val first = LabSdkRepository(assets, { uri -> requests++; Response(uri, 200, bytes) }, token = { null })
        first.stage(repository)
        val next = LabSdkRepository(assets, { error("warm build must not download") }, token = { null })
        assertEquals(repository, next.stage(repository))
        assertEquals(1, requests)
        assertContentEquals(bytes, Files.readAllBytes(artifact(repository)))
    }

    @Test fun `corrupt cached bytes are replaced only after checksum verification`() = withRepository { repository ->
        Files.createDirectories(artifact(repository).parent)
        Files.writeString(artifact(repository), "corrupt")
        LabSdkRepository(assets, { uri -> Response(uri, 200, bytes) }, token = { null }).stage(repository)
        assertContentEquals(bytes, Files.readAllBytes(artifact(repository)))
    }

    @Test fun `invalid downloaded bytes cannot replace a cached artifact`() = withRepository { repository ->
        Files.createDirectories(artifact(repository).parent)
        Files.writeString(artifact(repository), "old")
        var requests = 0
        val cache = LabSdkRepository(assets, { uri -> requests++; Response(uri, 200, "wrong".toByteArray()) })
        assertFailsWith<IllegalStateException> { cache.stage(repository) }
        assertEquals("old", Files.readString(artifact(repository)))
        assertEquals(1, requests)
        assertEquals(1L, Files.list(artifact(repository).parent).use { it.count() })
    }

    @Test fun `exhausted quota stops after one request and reports the reset`() = withRepository { repository ->
        var requests = 0
        val cache = LabSdkRepository(assets, { uri ->
            requests++
            Response(uri, 403, headers = mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "123456"))
        }, pause = { error("must not retry exhausted quota") })
        val failure = assertFailsWith<GradleException> { cache.stage(repository) }
        assertTrue(failure.cause!!.message!!.contains("rate remaining 0; reset at epoch second 123456"))
        assertEquals(1, requests)
        assertFalse(Files.exists(artifact(repository)))
    }

    @Test fun `server retry directives prevent an early retry`() = withRepository { repository ->
        var requests = 0
        val cache = LabSdkRepository(assets, { uri ->
            requests++
            Response(uri, 503, headers = mapOf("Retry-After" to "120"))
        }, pause = { error("must not retry before the server directive") })
        assertFailsWith<GradleException> { cache.stage(repository) }
        assertEquals(1, requests)
    }

    @Test fun `too many requests stops even without quota headers`() = withRepository { repository ->
        var requests = 0
        val cache = LabSdkRepository(assets, { uri -> requests++; Response(uri, 429) },
            pause = { error("must not retry a rate limit") })
        assertFailsWith<GradleException> { cache.stage(repository) }
        assertEquals(1, requests)
    }

    @Test fun `oversized downloads cannot be published`() = withRepository { repository ->
        val cache = LabSdkRepository(assets, { uri -> Response(uri, 200, ByteArray(1_048_577)) })
        assertFailsWith<IllegalStateException> { cache.stage(repository) }
        assertFalse(Files.exists(artifact(repository)))
    }

    @Test fun `transient failures back off and stop after three attempts`() = withRepository { repository ->
        var requests = 0
        val pauses = mutableListOf<Long>()
        val cache = LabSdkRepository(assets, { uri -> requests++; Response(uri, 504) }, pause = pauses::add)
        assertFailsWith<GradleException> { cache.stage(repository) }
        assertEquals(3, requests)
        assertEquals(listOf(1_000L, 2_000L), pauses)
    }

    @Test fun `authentication is sent to the API and omitted from asset redirects`() = withRepository { repository ->
        val requests = mutableListOf<Response>()
        val cache = LabSdkRepository(assets, { uri ->
            Response(uri, if (requests.isEmpty()) 302 else 200, bytes,
                mapOf("Location" to "https://release-assets.githubusercontent.com/asset")).also(requests::add)
        }, token = { "test-token" })
        cache.stage(repository)
        assertEquals("Bearer test-token", requests[0].getRequestProperty("Authorization"))
        assertEquals(null, requests[1].getRequestProperty("Authorization"))
        assertContentEquals(bytes, Files.readAllBytes(artifact(repository)))
    }

    @Test fun `redirects outside GitHub are refused before opening another connection`() = withRepository { repository ->
        var requests = 0
        val cache = LabSdkRepository(assets, { uri ->
            requests++
            Response(uri, 302, headers = mapOf("Location" to "https://example.com/asset"))
        }, token = { "test-token" })
        assertFailsWith<IllegalStateException> { cache.stage(repository) }
        assertEquals(1, requests)
    }

    @Test fun `offline builds use verified bytes and never attempt a download`() = withRepository { repository ->
        val cache = LabSdkRepository(assets, { error("offline build must not download") })
        assertFailsWith<IllegalStateException> { cache.stage(repository, offline = true) }
        Files.write(artifact(repository), bytes)
        assertEquals(repository, cache.stage(repository, offline = true))
    }

    private fun artifact(repository: Path) = repository.resolve("lab-bench-v0.1.0/lab-bench-0.1.0.jar")

    private fun withRepository(test: (Path) -> Unit) {
        val repository = Files.createTempDirectory("lab-sdk-test")
        try {
            test(repository)
        } finally {
            repository.toFile().deleteRecursively()
        }
    }

    private class Response(
        uri: URI,
        private val status: Int,
        private val body: ByteArray = byteArrayOf(),
        private val headers: Map<String, String> = emptyMap(),
    ) : HttpURLConnection(uri.toURL()) {
        override fun getResponseCode() = status
        override fun getHeaderField(name: String) = headers[name]
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun connect() = error("test transport cannot access the network")
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
