package com.mitas.ppnam.station1aa

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Operator authentication over MQTT — the shared Station 2 schema 4.1 contract on Station 1's
 * namespaced topics (Station1_MQTT_Contract v3.0.0 §4):
 *
 *   req/scram_start_requested   -> res/scram_challenge
 *   req/scram_proof_requested   -> res/scram_proof_result
 *   req/login_requested         -> res/operator_context
 *   req/reader_logout_requested -> res/operator_context (fire-and-forget here)
 *   req/operator_list_requested -> res/operator_list (login dropdown, v3.2.0 §4.5)
 *
 * Every request carries the schema 4.1 envelope (Schema41). Responses are correlated on
 * inResponseToMessageId and branched on `accepted`/`errorCode` — free-text `reason` is logged,
 * never parsed and never shown (every failure is an [AuthFailure] the UI maps to its own copy).
 * Envelope and routing failures arrive on res/request_rejected, so every round trip listens
 * there too.
 *
 * Logout clears the local session regardless of the outcome: stranding an operator logged-in
 * because the network blipped would be worse than a server-side session that expires on its own.
 */
class AuthClient(context: Context) {

    private val appContext = context.applicationContext
    private val mqtt = MqttManager.getInstance(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())

    private companion object {
        const val TAG = "AuthClient"
        const val REQUEST_TIMEOUT_MS = 10_000L
        const val PURPOSE_LOGIN = "login"
        const val REJECTED_SUFFIX = "request_rejected"
    }

    private fun deviceId(): String = DeviceIdentity.deviceId(appContext)

    fun login(username: String, password: String, onResult: (Result<OperatorSession>) -> Unit) {
        val clientNonce = ScramCrypto.generateClientNonce()

        val startPayload = Schema41.envelope(Schema41.newMessageId("auth-start"), deviceId()).apply {
            put("username", username)
            put("clientNonce", clientNonce)
            put("purpose", PURPOSE_LOGIN)
        }

        request(AuthFailure.Step.SCRAM_START, "scram_start_requested", "scram_challenge", startPayload) { startResult ->
            val challenge = startResult.getOrElse { return@request onResult(Result.failure(it)) }

            val challengeId = challenge.optString("challengeId", "")
            val serverFirstMessage = challenge.optString("serverFirstMessage", "")
            val iterations = challenge.optInt("iterations", 0)
            if (challengeId.isBlank() || serverFirstMessage.isBlank()) {
                return@request onResult(protocol("Station sent an incomplete authentication challenge"))
            }
            if (iterations <= 0) {
                return@request onResult(protocol("Station sent an invalid authentication challenge"))
            }

            // RFC 5802: the combined nonce must extend the one we sent. Anything else means this
            // challenge is not an answer to our start — refuse rather than proving against it.
            val serverNonce = ScramCrypto.parseServerNonce(serverFirstMessage, clientNonce)
                ?: challenge.optString("serverNonce", "").takeIf {
                    it.startsWith(clientNonce) && it.length > clientNonce.length
                }
                ?: return@request onResult(
                    protocol("Station's authentication challenge did not match this device's request")
                )

            val clientFinalWithoutProof = ScramCrypto.clientFinalWithoutProof(serverNonce)
            val proof = try {
                ScramCrypto.computeProof(
                    password = password,
                    saltBase64 = challenge.optString("salt", ""),
                    iterations = iterations,
                    authMessage = ScramCrypto.authMessage(
                        clientFirstBare = ScramCrypto.clientFirstBare(username, clientNonce),
                        serverFirstMessage = serverFirstMessage,
                        clientFinalWithoutProof = clientFinalWithoutProof,
                    ),
                )
            } catch (e: Exception) {
                // A malformed salt lands here. Deliberately not echoing the exception text, which
                // would put challenge material into a log line.
                return@request onResult(protocol("Station sent an unusable authentication challenge"))
            }

            val proofPayload = Schema41.envelope(Schema41.newMessageId("auth-proof"), deviceId()).apply {
                put("challengeId", challengeId)
                put("clientFinalWithoutProof", clientFinalWithoutProof)
                put("clientProof", proof.clientProofBase64)
                put("purpose", PURPOSE_LOGIN)
            }

            request(AuthFailure.Step.SCRAM_PROOF, "scram_proof_requested", "scram_proof_result", proofPayload) { proofResult ->
                val response = proofResult.getOrElse { return@request onResult(Result.failure(it)) }

                // Mutual authentication. Without this check anything that can answer on the
                // response topic could hand us a session we never actually proved for — which is
                // precisely what SCRAM's server signature exists to prevent.
                val serverSignature = response.optString("serverSignature", "")
                if (!ScramCrypto.verifyServerSignature(proof.expectedServerSignatureBase64, serverSignature)) {
                    return@request onResult(
                        protocol("Station failed authentication verification — this response is not trusted")
                    )
                }

                onResult(buildSession(response))
            }
        }
    }

    fun loginWithBadge(badgeTag: String, onResult: (Result<OperatorSession>) -> Unit) {
        val payload = Schema41.envelope(Schema41.newMessageId("badge-login"), deviceId()).apply {
            put("badgeTag", badgeTag)
        }
        request(AuthFailure.Step.BADGE_LOGIN, "login_requested", "operator_context", payload) { result ->
            onResult(result.fold({ buildSession(it) }, { Result.failure(it) }))
        }
    }

    /** Contract v3.2.0 §4.5: the display-only operator directory for the login dropdown. */
    fun operatorList(onResult: (Result<List<OperatorEntry>>) -> Unit) {
        val payload = Schema41.envelope(Schema41.newMessageId("operator-list"), deviceId())
        request(AuthFailure.Step.OTHER, "operator_list_requested", "operator_list", payload) { result ->
            onResult(result.map { OperatorListCodec.fromResponse(it) })
        }
    }

    fun logout(onComplete: () -> Unit = {}) {
        val payload = Schema41.envelope(Schema41.newMessageId("logout"), deviceId()).apply {
            put("operatorSessionId", OperatorSessionHolder.currentSessionIdOrEmpty())
        }
        val topic = MqttTopics.deviceRequest(deviceId(), "reader_logout_requested")
        mqtt.publish(topic, payload.toString()) { throwable ->
            if (throwable != null) Log.w(TAG, "Logout publish failed (session cleared anyway)", throwable)
        }
        OperatorSessionHolder.clear()
        mainHandler.post { onComplete() }
    }

    private fun buildSession(response: JSONObject): Result<OperatorSession> {
        val operatorSessionId = response.optString("operatorSessionId", "")
        val sessionState = response.optString("sessionState", "")
        return when {
            operatorSessionId.isBlank() ->
                protocol("Station accepted the login but issued no session")
            // Accepting an already-closed session would strand the operator in a UI that
            // rejects every action.
            sessionState.equals("closed", ignoreCase = true) ->
                protocol("Station closed this session immediately")
            else -> {
                val session = OperatorSession(
                    operatorSessionId = operatorSessionId,
                    operatorId = response.optString("operatorId", ""),
                    operatorName = response.optString("displayName", ""),
                    role = response.optString("role", ""),
                    allowedActions = response.optJSONArray("allowedActions").toStringList(),
                    allowedTabs = response.optJSONArray("allowedTabs").toStringList(),
                )
                OperatorSessionHolder.set(session)
                Result.success(session)
            }
        }
    }

    /**
     * One schema 4.1 request/response round trip, with a timeout. The response is accepted only
     * when its inResponseToMessageId matches this request; rejections — on the response topic or
     * on res/request_rejected — surface as [AuthFailure]s classified by [step].
     * The callback fires exactly once, on the main thread.
     */
    private fun request(
        step: AuthFailure.Step,
        requestType: String,
        responseType: String,
        payload: JSONObject,
        onResult: (Result<JSONObject>) -> Unit,
    ) {
        if (!mqtt.isConnected()) {
            mainHandler.post {
                onResult(Result.failure(AuthFailure(AuthFailure.Kind.NOT_CONNECTED, "Not connected to the broker")))
            }
            return
        }

        val device = deviceId()
        val requestMessageId = payload.getString("messageId")
        val responseTopic = MqttTopics.deviceResponse(device, responseType)
        val rejectedTopic = MqttTopics.deviceResponse(device, REJECTED_SUFFIX)
        val done = AtomicBoolean(false)

        lateinit var timeoutRunnable: Runnable
        lateinit var onResponse: (Mqtt3Publish) -> Unit
        lateinit var onRejected: (Mqtt3Publish) -> Unit

        fun finish(result: Result<JSONObject>) {
            if (!done.compareAndSet(false, true)) return
            mainHandler.removeCallbacks(timeoutRunnable)
            mqtt.unsubscribe(responseTopic, onResponse)
            mqtt.unsubscribe(rejectedTopic, onRejected)
            result.exceptionOrNull()?.let { Log.w(TAG, "$requestType failed: ${it.message}") }
            mainHandler.post { onResult(result) }
        }

        timeoutRunnable = Runnable {
            finish(Result.failure(AuthFailure(AuthFailure.Kind.TIMEOUT, "$requestType: no response in ${REQUEST_TIMEOUT_MS} ms")))
        }

        // With correlation, a message that isn't ours (wrong device, wrong messageId, or
        // unparseable) is ignored rather than failing the request — the timeout covers silence.
        fun parseCorrelated(publish: Mqtt3Publish): JSONObject? = try {
            val json = JSONObject(String(publish.payloadAsBytes, StandardCharsets.UTF_8))
            json.takeIf { mqtt.isRelevantToThisScanner(it) && Schema41.isResponseTo(it, requestMessageId) }
        } catch (e: Exception) {
            Log.e(TAG, "Malformed payload on ${publish.topic}", e)
            null
        }

        onResponse = { publish ->
            parseCorrelated(publish)?.let { json ->
                if (Schema41.isAccepted(json)) {
                    finish(Result.success(json))
                } else {
                    finish(Result.failure(
                        AuthFailure.rejected(step, json.optString("errorCode", ""), json.optString("reason", ""))
                    ))
                }
            }
        }

        onRejected = { publish ->
            parseCorrelated(publish)?.let { json ->
                finish(Result.failure(
                    AuthFailure.envelopeRejected(step, json.optString("errorCode", ""), json.optString("reason", ""))
                ))
            }
        }

        mqtt.subscribe(responseTopic, onResponse)
        mqtt.subscribe(rejectedTopic, onRejected)
        mainHandler.postDelayed(timeoutRunnable, REQUEST_TIMEOUT_MS)

        mqtt.publish(MqttTopics.deviceRequest(device, requestType), payload.toString()) { throwable ->
            if (throwable != null) {
                finish(Result.failure(AuthFailure(AuthFailure.Kind.NOT_CONNECTED, "$requestType publish failed: ${throwable.message}")))
            }
        }
    }

    private fun protocol(detail: String): Result<Nothing> =
        Result.failure(AuthFailure(AuthFailure.Kind.PROTOCOL, detail))
}

private fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it, "").takeIf { s -> s.isNotBlank() } }
}
