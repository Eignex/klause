package com.eignex.klause.bench.tune

import com.google.longrunning.GetOperationRequest
import com.google.protobuf.Value
import io.grpc.ManagedChannelBuilder
import vizier.StudyOuterClass.Measurement
import vizier.StudyOuterClass.Study
import vizier.StudyOuterClass.StudySpec
import vizier.StudyOuterClass.Trial
import vizier.VizierServiceGrpc
import vizier.VizierServiceOuterClass.AddTrialMeasurementRequest
import vizier.VizierServiceOuterClass.CompleteTrialRequest
import vizier.VizierServiceOuterClass.CreateStudyRequest
import vizier.VizierServiceOuterClass.CreateTrialRequest
import vizier.VizierServiceOuterClass.DeleteStudyRequest
import vizier.VizierServiceOuterClass.SuggestTrialsRequest
import vizier.VizierServiceOuterClass.SuggestTrialsResponse
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * The OSS Vizier backend for [Tuner] (see `klause-bench/vizier/`). This is the ONLY file that touches
 * the generated gRPC stubs — all Vizier specifics (the [StudySpec] mapping, the `SuggestTrials` +
 * long-running-[com.google.longrunning.Operation] polling, the `owners/…/studies/…` resource names,
 * `AddTrialMeasurement` + `CompleteTrial`) live behind the backend-agnostic [Tuner] / [TuningStudy]
 * seam. One metric ("reward"); parameters are declared flat (Vizier has no notion of the config space's
 * conditional gating, so an inactive param is simply ignored by the eval).
 *
 * The service runs at [host]:[port] (default the container's localhost:6789). [close] shuts the channel.
 */
internal class VizierTuner(
    host: String = DEFAULT_HOST,
    port: Int = DEFAULT_PORT,
    private val owner: String = "klause",
) : Tuner {
    private val channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build()
    private val stub = VizierServiceGrpc.newBlockingStub(channel)

    /** Create the study (reused by display name, so idempotent) and hand back a live [TuningStudy].
     *  `Study.name` is left unset — it is OUTPUT_ONLY and the OSS server rejects a create that sets it. */
    override fun openStudy(space: ConfigSpace, maximize: Boolean, studyId: String): TuningStudy {
        val goal = if (maximize) StudySpec.MetricSpec.GoalType.MAXIMIZE else StudySpec.MetricSpec.GoalType.MINIMIZE
        val spec = StudySpec.newBuilder()
            .setAlgorithm(ALGORITHM)
            .addMetrics(StudySpec.MetricSpec.newBuilder().setMetricId(REWARD).setGoal(goal))
        for (p in space.params) spec.addParameters(parameterSpec(p))
        val study = Study.newBuilder().setDisplayName(studyId).setStudySpec(spec).build()
        val name = stub.createStudy(
            CreateStudyRequest.newBuilder().setParent("owners/$owner").setStudy(study).build(),
        ).name
        return VizierStudy(name)
    }

    override fun close() {
        channel.shutdownNow().awaitTermination(SHUTDOWN_WAIT_S, TimeUnit.SECONDS)
    }

    /** A live Vizier study. Shares the enclosing tuner's channel/stub; [close] deletes the server-side
     *  study (freeing the per-owner slot). */
    private inner class VizierStudy(private val studyName: String) : TuningStudy {
        override fun suggest(count: Int): List<Suggestion> {
            var op = stub.suggestTrials(
                SuggestTrialsRequest.newBuilder()
                    .setParent(studyName)
                    .setSuggestionCount(count)
                    .setClientId(owner)
                    .build(),
            )
            while (!op.done) {
                Thread.sleep(POLL_INTERVAL_MS)
                op = stub.getOperation(GetOperationRequest.newBuilder().setName(op.name).build())
            }
            check(!op.hasError()) { "vizier SuggestTrials failed: ${op.error.message}" }
            // The OSS server sets only the Any's `value` bytes (no `type_url`), so parse them directly
            // rather than `unpack`, which would reject the empty type URL.
            return SuggestTrialsResponse.parseFrom(op.response.value).trialsList.map { decode(it) }
        }

        override fun complete(suggestion: Suggestion, objective: Double) {
            val measurement = Measurement.newBuilder()
                .addMetrics(Measurement.Metric.newBuilder().setMetricId(REWARD).setValue(objective))
                .build()
            stub.addTrialMeasurement(
                AddTrialMeasurementRequest.newBuilder()
                    .setTrialName(suggestion.handle).setMeasurement(measurement).build(),
            )
            stub.completeTrial(
                CompleteTrialRequest.newBuilder()
                    .setName(suggestion.handle).setFinalMeasurement(measurement).build(),
            )
        }

        /** Inject a pre-evaluated config as a prior so the GP-bandit fits on it. `AddTrialMeasurement` /
         *  `CompleteTrial` reject a non-ACTIVE trial (measurements model an in-progress evaluation), so a
         *  known result can't go through the suggest→complete path. Instead `CreateTrial` accepts a trial
         *  already in state `SUCCEEDED` with a `final_measurement` verbatim — the server only forces
         *  non-succeeded trials to `REQUESTED` — so one call adds the completed prior. */
        override fun observe(values: Map<String, Any>, objective: Double) {
            val measurement = Measurement.newBuilder()
                .addMetrics(Measurement.Metric.newBuilder().setMetricId(REWARD).setValue(objective))
                .build()
            val trial = Trial.newBuilder()
                .setState(Trial.State.SUCCEEDED)
                .setFinalMeasurement(measurement)
            for ((id, v) in values) {
                val value = if (v is Number) {
                    Value.newBuilder().setNumberValue(v.toDouble())
                } else {
                    Value.newBuilder().setStringValue(v.toString())
                }
                trial.addParameters(Trial.Parameter.newBuilder().setParameterId(id).setValue(value))
            }
            stub.createTrial(CreateTrialRequest.newBuilder().setParent(studyName).setTrial(trial).build())
        }

        override fun close() {
            stub.deleteStudy(DeleteStudyRequest.newBuilder().setName(studyName).build())
        }
    }

    private fun parameterSpec(p: ConfigParam): StudySpec.ParameterSpec {
        val b = StudySpec.ParameterSpec.newBuilder().setParameterId(p.name)
        when (p) {
            is CategoricalParam -> b.categoricalValueSpec = StudySpec.ParameterSpec.CategoricalValueSpec.newBuilder()
                .addAllValues(p.values).build()

            is IntParam -> b.integerValueSpec = StudySpec.ParameterSpec.IntegerValueSpec.newBuilder()
                .setMinValue(p.min.toLong()).setMaxValue(p.max.toLong()).build()

            is DoubleParam -> b.doubleValueSpec = StudySpec.ParameterSpec.DoubleValueSpec.newBuilder()
                .setMinValue(p.min).setMaxValue(p.max).build()
        }
        return b.build()
    }

    /** A trial → [Suggestion]: its resource name is the opaque handle; a categorical value is its
     *  string, a numeric its Double. */
    private fun decode(trial: Trial): Suggestion = Suggestion(
        trial.name,
        trial.parametersList.associate { param ->
            val v = param.value
            param.parameterId to if (v.kindCase == Value.KindCase.STRING_VALUE) v.stringValue else v.numberValue
        },
    )

    companion object {
        const val DEFAULT_HOST = "localhost"
        const val DEFAULT_PORT = 6789
        private const val REWARD = "reward"

        /** OSS Vizier's Gaussian-process bandit — the BO algorithm the recalibration wants. */
        private const val ALGORITHM = "GAUSSIAN_PROCESS_BANDIT"
        private const val POLL_INTERVAL_MS = 200L
        private const val SHUTDOWN_WAIT_S = 5L
        private const val PROBE_TIMEOUT_MS = 500

        /** Whether the Vizier service is reachable at [host]:[port] (so a test can skip when it is not). */
        fun reachable(host: String = DEFAULT_HOST, port: Int = DEFAULT_PORT): Boolean = runCatching {
            Socket().use {
                it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                true
            }
        }.getOrElse { false }
    }
}
