package com.eignex.klause.bench.metric

import kotlinx.serialization.Serializable

@Serializable
internal data class MpsCheckerFile(val path: String, val bytes: Long, val sha256: String)

@Serializable
internal data class MpsCheckerRuntime(
    val pythonVersion: String,
    val pythonExecutable: String,
    val pythonExecutableSha256: String,
    val machine: String,
    val libc: List<String>,
    val packageVersion: String,
    val checkerVersion: String,
    val wheelBytes: Long,
    val wheelSha256: String,
    val wheelUrl: String,
    val metadataSha256: String,
    val extractedFiles: List<MpsCheckerFile>,
    val globalInstallationPerformed: Boolean,
)

@Serializable
internal data class MpsGroundArithmetic(
    val implementation: String,
    val pythonVersion: String,
    val pythonExecutableSha256: String,
    val modules: Map<String, MpsCheckerFile>,
    val builtinMath: MpsBuiltinModule? = null,
)

@Serializable
internal data class MpsBuiltinModule(
    val origin: String,
    val loader: String,
    val executablePath: String,
    val maps: String,
    val mapsSha256: String,
    val providers: List<MpsCheckerFile>,
)

@Serializable
internal data class MpsCheckerStream(
    val bytes: Long,
    val observedBytes: Long,
    val complete: Boolean,
    val sha256: String,
    val error: String? = null,
)

@Serializable
internal data class MpsCheckerMemory(
    val mode: String,
    val phase: String,
    val pid: Long,
    val allowanceBytes: Long,
    val baselineVirtualBytes: Long,
    val baselineResidentBytes: Long,
    val addressSpaceLimitBytes: Long,
    val postExecVerified: Boolean,
    val postExecVirtualBytes: Long,
    val postExecResidentBytes: Long,
)

@Serializable
internal data class MpsCheckerProcess(
    val command: List<String>,
    val cwd: String,
    val exit: Int?,
    val timedOut: Boolean,
    val overflow: Boolean,
    val parentStop: String?,
    val streams: Map<String, MpsCheckerStream>,
    val complete: Boolean,
    val processWallNs: Long,
    val addressSpaceBytes: Long,
    val timeoutSeconds: Int,
    val pid: Long? = null,
    val memoryControl: MpsCheckerMemory? = null,
)

