package com.example.abhayhealth.feature.pin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.abhayhealth.domain.repository.PinAuthenticationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PinSetupUiState {
    object Idle : PinSetupUiState
    object Loading : PinSetupUiState
    object Success : PinSetupUiState
    data class Error(val messageResId: Int) : PinSetupUiState
}

class PinSetupViewModel(
    private val pinAuthenticationRepository: PinAuthenticationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<PinSetupUiState>(PinSetupUiState.Idle)
    val uiState: StateFlow<PinSetupUiState> = _uiState.asStateFlow()

    private val _pin = MutableStateFlow("")
    val pin: StateFlow<String> = _pin.asStateFlow()

    private val _confirmPin = MutableStateFlow("")
    val confirmPin: StateFlow<String> = _confirmPin.asStateFlow()

    fun onPinChanged(value: String) {
        if (value.length <= 6 && value.all { it.isDigit() }) {
            _pin.value = value
            _uiState.value = PinSetupUiState.Idle
        }
    }

    fun onConfirmPinChanged(value: String) {
        if (value.length <= 6 && value.all { it.isDigit() }) {
            _confirmPin.value = value
            _uiState.value = PinSetupUiState.Idle
        }
    }

    fun clearError() {
        _uiState.value = PinSetupUiState.Idle
    }

    fun submitPin(
        accountUid: String,
        isResetMode: Boolean = false,
        onSuccess: () -> Unit
    ) {
        val currentPin = _pin.value.trim()
        val currentConfirm = _confirmPin.value.trim()

        if (currentPin.length != 6) {
            _uiState.value = PinSetupUiState.Error(com.example.abhayhealth.R.string.pin_err_length)
            return
        }

        if (currentPin != currentConfirm) {
            _uiState.value = PinSetupUiState.Error(com.example.abhayhealth.R.string.pin_err_mismatch)
            return
        }

        viewModelScope.launch {
            _uiState.value = PinSetupUiState.Loading
            val result = if (isResetMode) {
                pinAuthenticationRepository.resetPinWithOtp(accountUid, currentPin)
            } else {
                pinAuthenticationRepository.setupPin(accountUid, currentPin)
            }

            result.fold(
                onSuccess = {
                    _uiState.value = PinSetupUiState.Success
                    onSuccess()
                },
                onFailure = {
                    _uiState.value = PinSetupUiState.Error(com.example.abhayhealth.R.string.pin_err_length)
                }
            )
        }
    }
}
