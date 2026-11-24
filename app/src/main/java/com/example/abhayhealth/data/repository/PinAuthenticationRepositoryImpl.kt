package com.example.abhayhealth.data.repository

import com.example.abhayhealth.core.common.DefaultDispatcherProvider
import com.example.abhayhealth.core.common.DispatcherProvider
import com.example.abhayhealth.core.security.PinSecurityManager
import com.example.abhayhealth.domain.model.PinValidationResult
import com.example.abhayhealth.domain.repository.PinAuthenticationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class PinAuthenticationRepositoryImpl(
    private val pinSecurityManager: PinSecurityManager,
    private val dispatchers: DispatcherProvider = DefaultDispatcherProvider()
) : PinAuthenticationRepository {

    private val _pinStateFlowMap = mutableMapOf<String, MutableStateFlow<Boolean>>()

    override fun isPinConfiguredFlow(accountUid: String): Flow<Boolean> {
        val flow = _pinStateFlowMap.getOrPut(accountUid) {
            MutableStateFlow(pinSecurityManager.isPinConfigured(accountUid))
        }
        flow.value = pinSecurityManager.isPinConfigured(accountUid)
        return flow.asStateFlow()
    }

    override fun isPinConfigured(accountUid: String): Boolean {
        if (accountUid.isBlank()) return false
        return pinSecurityManager.isPinConfigured(accountUid)
    }

    override suspend fun setupPin(accountUid: String, pin: String): Result<Unit> = withContext(dispatchers.io) {
        try {
            if (accountUid.isBlank()) return@withContext Result.failure(IllegalArgumentException("Invalid account"))
            if (pin.length != 6 || !pin.all { it.isDigit() }) {
                return@withContext Result.failure(IllegalArgumentException("PIN must be 6 digits"))
            }

            val success = pinSecurityManager.setupPin(accountUid, pin)
            if (success) {
                notifyPinStateChanged(accountUid, true)
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Failed to save PIN verifier"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun validatePin(accountUid: String, pin: String): PinValidationResult = withContext(dispatchers.io) {
        val managerResult = pinSecurityManager.validatePin(accountUid, pin)
        when (managerResult) {
            PinSecurityManager.ValidationResult.NotConfigured -> PinValidationResult.NotConfigured
            PinSecurityManager.ValidationResult.Success -> PinValidationResult.Success
            is PinSecurityManager.ValidationResult.IncorrectPin -> PinValidationResult.IncorrectPin(managerResult.remainingAttempts)
            is PinSecurityManager.ValidationResult.LockedOut -> PinValidationResult.LockedOut(managerResult.secondsRemaining)
        }
    }

    override suspend fun changePin(accountUid: String, oldPin: String, newPin: String): Result<Unit> = withContext(dispatchers.io) {
        try {
            val validation = pinSecurityManager.validatePin(accountUid, oldPin)
            if (validation !is PinSecurityManager.ValidationResult.Success) {
                return@withContext Result.failure(IllegalArgumentException("Incorrect existing PIN"))
            }

            val success = pinSecurityManager.setupPin(accountUid, newPin)
            if (success) {
                notifyPinStateChanged(accountUid, true)
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Failed to update PIN verifier"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun disablePin(accountUid: String): Result<Unit> = withContext(dispatchers.io) {
        try {
            val success = pinSecurityManager.disablePin(accountUid)
            if (success) {
                notifyPinStateChanged(accountUid, false)
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Failed to disable PIN"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun resetPinWithOtp(accountUid: String, newPin: String): Result<Unit> = withContext(dispatchers.io) {
        try {
            val success = pinSecurityManager.resetLockoutAndSetupNewPin(accountUid, newPin)
            if (success) {
                notifyPinStateChanged(accountUid, true)
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("Failed to reset PIN"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getLockoutSecondsRemaining(accountUid: String): Long {
        return pinSecurityManager.getLockoutSecondsRemaining(accountUid)
    }

    private fun notifyPinStateChanged(accountUid: String, isConfigured: Boolean) {
        _pinStateFlowMap[accountUid]?.value = isConfigured
    }
}
