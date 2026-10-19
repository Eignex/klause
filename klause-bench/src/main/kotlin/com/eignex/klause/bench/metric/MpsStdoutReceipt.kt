package com.eignex.klause.bench.metric

import kotlinx.serialization.Serializable

internal data class MpsGzipExpectation(val wireBytes: Long, val decodedBytes: Long)

@Serializable
internal data class MpsStdoutTransport(
    val encoding: String,
    val wireBytes: Long,
    val decodedBytes: Long,
    val wireSha256: String,
    val decodedSha256: String,
    val envelopeBytes: Long,
    val envelopeSha256: String,
    val complete: Boolean,
    val decoderRuntime: MpsGzipRuntime,
)

@Serializable
internal data class MpsGzipRuntime(
    val pythonVersion: String,
    val pythonExecutableSha256: String,
    val modules: Map<String, MpsCheckerFile>,
    val zlibVersion: String,
    val zlibRuntimeVersion: String,
    val maps: String,
    val mapsSha256: String,
    val nativeProviders: List<MpsCheckerFile>,
    val builtinZlib: MpsBuiltinModule? = null,
    val implementation: String = "python-zlib-gzip-v1",
)

internal object MpsStdoutReceipt {
    private val digest = Regex("[0-9a-f]{64}")

    fun requireComplete(
        transport: MpsStdoutTransport,
        expected: MpsGzipExpectation,
        record: SolveRecord,
        bindings: Map<String, String>,
        runtime: MpsCheckerRuntime,
    ) {
        require(record.validationPolicy == MPS_GZIP_SOURCE_POLICY)
        require(bindings.keys == setOf("source.mps", "stdout.txt.gz", "record.json", "request.json"))
        require(transport.encoding == "gzip" && transport.complete)
        require(transport.wireBytes == expected.wireBytes && transport.wireBytes in 1..8_388_608L)
        require(transport.decodedBytes == expected.decodedBytes && transport.decodedBytes in 0..67_108_864L)
        require(transport.wireSha256 == bindings["stdout.txt.gz"] && digest.matches(transport.wireSha256))
        require(transport.decodedSha256 == record.sourceOutputSha256 && digest.matches(transport.decodedSha256))
        require(transport.envelopeBytes in 1..8_388_608L && digest.matches(transport.envelopeSha256))
        val decoder = transport.decoderRuntime
        require(decoder.implementation == "python-zlib-gzip-v1")
        require(decoder.pythonVersion == runtime.pythonVersion &&
            decoder.pythonExecutableSha256 == runtime.pythonExecutableSha256)
        val moduleNames = if (decoder.builtinZlib == null) {
            setOf("gzip", "zlib", "_compression")
        } else {
            setOf("gzip", "_compression")
        }
        require(decoder.modules.keys == moduleNames)
        decoder.builtinZlib?.let {
            MpsSourceReceipt.requireBuiltinProvider(it, decoder.pythonVersion, decoder.pythonExecutableSha256)
        }
        require(decoder.modules.values.all {
            it.path.startsWith('/') && it.bytes in 1..4_194_304L && digest.matches(it.sha256)
        })
        require(Regex("[0-9]+(?:\\.[0-9]+){1,3}").matches(decoder.zlibVersion))
        require(Regex("[0-9]+(?:\\.[0-9]+){1,3}").matches(decoder.zlibRuntimeVersion))
        require(decoder.maps.endsWith('\n') && '\r' !in decoder.maps)
        val raw = decoder.maps.toByteArray()
        require(raw.size in 1..262_144 && sha256(raw) == decoder.mapsSha256)
        val lines = decoder.maps.removeSuffix("\n").split('\n')
        require(lines.size in 1..4096)
        val providers = mutableMapOf<String, Boolean>()
        for (line in lines) {
            val fields = line.trimStart().split(Regex("\\s+"), limit = 6)
            require(fields.size in 5..6)
            require(Regex("[0-9a-f]{1,16}-[0-9a-f]{1,16}").matches(fields[0]))
            val interval = fields[0].split('-').map { it.toULong(16) }
            require(interval[0] < interval[1])
            require(Regex("[r-][w-][x-][ps]").matches(fields[1]))
            require(Regex("[0-9a-f]{1,16}").matches(fields[2]))
            require(Regex("[0-9a-f]{1,8}:[0-9a-f]{1,8}").matches(fields[3]))
            require(Regex("[0-9]{1,20}").matches(fields[4]))
            if (fields.size == 5) continue
            val path = fields[5]
            if (path.substringAfterLast('/').startsWith("libz.so")) {
                require(path.startsWith('/') && path.none { it.isWhitespace() || it == '\\' })
                require(Regex("libz\\.so(?:\\.[0-9]+)*").matches(path.substringAfterLast('/')))
                require(fields[4].any { it != '0' })
                providers[path] = providers.getOrDefault(path, false) || 'x' in fields[1]
            }
        }
        require(providers.size <= 3 && providers.values.all { it })
        require(decoder.nativeProviders.size == providers.size)
        require(decoder.nativeProviders.map { it.path }.toSet() == providers.keys)
        require(decoder.nativeProviders.all { it.bytes in 1..67_108_864L && digest.matches(it.sha256) })
    }
}
