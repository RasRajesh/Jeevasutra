package com.example.abhayhealth.domain.model

/**
 * Result of validating a 6-digit Mobile Login PIN for JeevaSutra.
 */
sealed interface PinValidationResult {
    object Success : PinValidationResult
    data class IncorrectPin(val remainingAttempts: Int) : PinValidationResult
    data class LockedOut(val secondsRemaining: Long) : PinValidationResult
    object NotConfigured : PinValidationResult
}
