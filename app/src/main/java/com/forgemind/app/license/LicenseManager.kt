package com.forgemind.app.license

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface LicenseTokenStorage {
    fun getToken(): String?
    fun setToken(token: String?)
}

enum class LicenseStatus {
    NONE,
    ACTIVE,
    EXPIRED,
    INVALID
}

data class LicenseState(
    val status: LicenseStatus = LicenseStatus.NONE,
    val payload: LicensePayload? = null,
    val maskedToken: String? = null,
    val rejectReason: LicenseRejectReason? = null,
    val onlineCheckInProgress: Boolean = false,
    val onlineCheckResult: OnlineVerifyResult? = null
) {
    val isActive: Boolean
        get() = status == LicenseStatus.ACTIVE && payload != null

    fun hasFeature(feature: String): Boolean =
        isActive && payload?.hasFeature(feature) == true
}

/**
 * Coordinates local Ed25519 license verification, encrypted on-device storage,
 * reactive UI state, and optional fallback online verification.
 */
class LicenseManager(
    private val storage: LicenseTokenStorage,
    private val verifier: LicenseVerifier = LicenseVerifier(),
    private val networkClient: LicenseNetworkClient = LicenseNetworkClient(),
    private val clockEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
) {
    private val _state = MutableStateFlow(evaluateStoredToken())
    val state: StateFlow<LicenseState> = _state.asStateFlow()

    val isActive: Boolean
        get() = hasActiveLicenseNow()

    /**
     * Authoritative offline feature check for paid screens and features.
     * Re-evaluates expiration against the current clock so long-lived sessions
     * reflect expiry immediately without requiring an app restart.
     */
    fun hasFeature(feature: String): Boolean {
        val current = _state.value
        val payload = current.payload ?: return false
        if (current.status != LicenseStatus.ACTIVE) return false
        if (payload.isExpiredAt(clockEpochSeconds())) {
            _state.value = current.copy(
                status = LicenseStatus.EXPIRED,
                rejectReason = LicenseRejectReason.EXPIRED
            )
            return false
        }
        return payload.hasFeature(feature)
    }

    private fun hasActiveLicenseNow(): Boolean {
        val current = _state.value
        val payload = current.payload ?: return false
        if (current.status != LicenseStatus.ACTIVE) return false
        if (payload.isExpiredAt(clockEpochSeconds())) {
            _state.value = current.copy(
                status = LicenseStatus.EXPIRED,
                rejectReason = LicenseRejectReason.EXPIRED
            )
            return false
        }
        return true
    }

    /**
     * Re-reads and locally verifies the persisted token (e.g. on startup or after .fms restore).
     */
    fun refreshFromStorage() {
        _state.value = evaluateStoredToken()
    }

    /**
     * Verifies [rawToken] offline with Ed25519. If valid, persists it on the phone and activates it.
     * If invalid, leaves any previously saved valid license intact and returns the rejection reason.
     */
    fun activate(rawToken: String): LicenseVerificationResult {
        val result = verifier.verify(rawToken, clockEpochSeconds())
        if (result is LicenseVerificationResult.Valid) {
            storage.setToken(result.token)
            _state.value = LicenseState(
                status = LicenseStatus.ACTIVE,
                payload = result.payload,
                maskedToken = maskToken(result.token),
                rejectReason = null,
                onlineCheckInProgress = false,
                onlineCheckResult = null
            )
        }
        return result
    }

    fun clear() {
        storage.setToken(null)
        _state.value = LicenseState(status = LicenseStatus.NONE)
    }

    /**
     * Optional secondary check via POST https://silentfish.dev/api/license/verify.
     * Never activates an unsigned/invalid key and never revokes a locally valid key on network error.
     */
    suspend fun verifyOnline(): OnlineVerifyResult {
        val stored = storage.getToken()?.let(LicenseVerifier::normalizeToken).orEmpty()
        if (stored.isEmpty()) {
            val res = OnlineVerifyResult.Rejected(0, "No license key saved")
            _state.value = _state.value.copy(onlineCheckInProgress = false, onlineCheckResult = res)
            return res
        }
        _state.value = _state.value.copy(onlineCheckInProgress = true)
        val result = networkClient.verifyOnline(stored)
        _state.value = _state.value.copy(
            onlineCheckInProgress = false,
            onlineCheckResult = result
        )
        return result
    }

    private fun evaluateStoredToken(): LicenseState {
        val raw = storage.getToken()?.let(LicenseVerifier::normalizeToken).orEmpty()
        if (raw.isEmpty()) {
            return LicenseState(status = LicenseStatus.NONE)
        }
        return when (val result = verifier.verify(raw, clockEpochSeconds())) {
            is LicenseVerificationResult.Valid -> LicenseState(
                status = LicenseStatus.ACTIVE,
                payload = result.payload,
                maskedToken = maskToken(result.token)
            )

            is LicenseVerificationResult.Invalid -> {
                val status = if (result.reason == LicenseRejectReason.EXPIRED) {
                    LicenseStatus.EXPIRED
                } else {
                    LicenseStatus.INVALID
                }
                LicenseState(
                    status = status,
                    payload = null,
                    maskedToken = maskToken(raw),
                    rejectReason = result.reason
                )
            }
        }
    }

    companion object {
        fun maskToken(token: String): String {
            val clean = LicenseVerifier.normalizeToken(token)
            return if (clean.length <= 18) clean else "${clean.take(10)}…${clean.takeLast(6)}"
        }
    }
}
