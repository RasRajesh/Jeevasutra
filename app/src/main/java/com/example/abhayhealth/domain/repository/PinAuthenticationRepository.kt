package com.example.abhayhealth.domain.repository

import com.example.abhayhealth.domain.model.PinValidationResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing Mobile Login PIN setup, validation, change, disable, and lockout recovery.
 * Enforces account UID isolation.
 */
interface PinAuthenticationRepository {
    fun isPinConfiguredFlow(accountUid: String): Flow<Boolean>
    fun isPinConfigured(accountUid: String): Boolean
    suspend fun setupPin(accountUid: String, pin: String): Result<Unit>
    suspend fun validatePin(accountUid: String, pin: String): PinValidationResult
    suspend fun changePin(accountUid: String, oldPin: String, newPin: String): Result<Unit>
    suspend fun disablePin(accountUid: String): Result<Unit>
    suspend fun resetPinWithOtp(accountUid: String, newPin: String): Result<Unit>
    fun getLockoutSecondsRemaining(accountUid: String): Long
}
