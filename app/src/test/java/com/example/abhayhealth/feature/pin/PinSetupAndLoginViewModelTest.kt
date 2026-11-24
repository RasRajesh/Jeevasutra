package com.example.abhayhealth.feature.pin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.abhayhealth.core.common.TestDispatcherProvider
import com.example.abhayhealth.core.security.PinSecurityManager
import com.example.abhayhealth.data.repository.PinAuthenticationRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PinSetupAndLoginViewModelTest {

    private lateinit var context: Context
    private lateinit var pinSecurityManager: PinSecurityManager
    private lateinit var repository: PinAuthenticationRepositoryImpl
    private lateinit var setupViewModel: PinSetupViewModel
    private lateinit var loginViewModel: PinLoginViewModel

    private val testUid = "vm_test_uid_100"

    @Before
    fun setUp() {
        PinSecurityManager.pbkdf2Iterations = 100
        context = ApplicationProvider.getApplicationContext()
        pinSecurityManager = PinSecurityManager(context)
        repository = PinAuthenticationRepositoryImpl(
            pinSecurityManager = pinSecurityManager,
            dispatchers = TestDispatcherProvider()
        )
        setupViewModel = PinSetupViewModel(repository)
        loginViewModel = PinLoginViewModel(repository)
        pinSecurityManager.disablePin(testUid)
    }

    @Test
    fun setupViewModel_pinMismatch_setsErrorState() = runBlocking {
        setupViewModel.onPinChanged("123456")
        setupViewModel.onConfirmPinChanged("654321")

        var wasSuccessful = false
        setupViewModel.submitPin(testUid) { wasSuccessful = true }

        assertTrue(setupViewModel.uiState.value is PinSetupUiState.Error)
        val errorState = setupViewModel.uiState.value as PinSetupUiState.Error
        assertEquals(com.example.abhayhealth.R.string.pin_err_mismatch, errorState.messageResId)
        assertTrue(!wasSuccessful)
    }

    @Test
    fun setupViewModel_validPin_setsSuccessState() = runBlocking {
        setupViewModel.onPinChanged("123456")
        setupViewModel.onConfirmPinChanged("123456")

        var wasSuccessful = false
        setupViewModel.submitPin(testUid) { wasSuccessful = true }

        assertTrue(setupViewModel.uiState.value is PinSetupUiState.Success)
        assertTrue(wasSuccessful)
        assertTrue(repository.isPinConfigured(testUid))
    }

    @Test
    fun loginViewModel_validPin_unlocksSession() = runBlocking {
        repository.setupPin(testUid, "654321")

        loginViewModel.onPinChanged("654321")

        var wasUnlocked = false
        loginViewModel.submitPin(testUid) { wasUnlocked = true }

        assertTrue(loginViewModel.uiState.value is PinLoginUiState.Success)
        assertTrue(wasUnlocked)
    }

    @Test
    fun loginViewModel_incorrectPin_showsAttemptsRemaining() = runBlocking {
        repository.setupPin(testUid, "654321")

        loginViewModel.onPinChanged("111111")

        var wasUnlocked = false
        loginViewModel.submitPin(testUid) { wasUnlocked = true }

        assertTrue(loginViewModel.uiState.value is PinLoginUiState.IncorrectPin)
        val incorrect = loginViewModel.uiState.value as PinLoginUiState.IncorrectPin
        assertEquals(4, incorrect.remainingAttempts)
        assertTrue(!wasUnlocked)
    }
}
