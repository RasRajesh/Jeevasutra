package com.example.abhayhealth.feature.pin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.abhayhealth.domain.model.PinValidationResult
import com.example.abhayhealth.domain.repository.PinAuthenticationRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PinLoginUiState {
    object Idle : PinLoginUiState
    object Loading : PinLoginUiState
    object Success : PinLoginUiState
    data class IncorrectPin(val remainingAttempts: Int) : PinLoginUiState
    data class LockedOut(val secondsRemaining: Long) : PinLoginUiState
}

class PinLoginViewModel(
    private val pinAuthenticationRepository: PinAuthenticationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<PinLoginUiState>(PinLoginUiState.Idle)
    val uiState: StateFlow<PinLoginUiState> = _uiState.asStateFlow()

    private val _pin = MutableStateFlow("")
    val pin: StateFlow<String> = _pin.asStateFlow()

    private var countdownJob: Job? = null

    fun onPinChanged(value: String) {
        if (value.length <= 6 && value.all { it.isDigit() }) {
            _pin.value = value
            if (_uiState.value is PinLoginUiState.IncorrectPin) {
                _uiState.value = PinLoginUiState.Idle
            }
        }
    }

    fun checkInitialLockout(accountUid: String) {
        val seconds = pinAuthenticationRepository.getLockoutSecondsRemaining(accountUid)
        if (seconds > 0) {
            startLockoutCountdown(seconds)
        }
    }

    fun submitPin(accountUid: String, onSuccess: () -> Unit) {
        val enteredPin = _pin.value.trim()
        if (enteredPin.length != 6) return

        viewModelScope.launch {
            _uiState.value = PinLoginUiState.Loading
            when (val result = pinAuthenticationRepository.validatePin(accountUid, enteredPin)) {
                PinValidationResult.Success -> {
                    _uiState.value = PinLoginUiState.Success
                    onSuccess()
                }
                is PinValidationResult.IncorrectPin -> {
                    _pin.value = ""
                    _uiState.value = PinLoginUiState.IncorrectPin(result.remainingAttempts)
                }
                is PinValidationResult.LockedOut -> {
                    _pin.value = ""
                    startLockoutCountdown(result.secondsRemaining)
                }
                PinValidationResult.NotConfigured -> {
                    _pin.value = ""
                    _uiState.value = PinLoginUiState.Idle
                }
            }
        }
    }

    private fun startLockoutCountdown(seconds: Long) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var current = seconds
            while (current > 0) {
                _uiState.value = PinLoginUiState.LockedOut(current)
                delay(1000L)
                current--
            }
            _uiState.value = PinLoginUiState.Idle
        }
    }
}
