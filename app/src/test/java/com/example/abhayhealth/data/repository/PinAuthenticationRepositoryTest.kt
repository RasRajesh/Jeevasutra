package com.example.abhayhealth.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.abhayhealth.core.common.TestDispatcherProvider
import com.example.abhayhealth.core.security.PinSecurityManager
import com.example.abhayhealth.domain.model.PinValidationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PinAuthenticationRepositoryTest {

    private lateinit var context: Context
    private lateinit var pinSecurityManager: PinSecurityManager
    private lateinit var repository: PinAuthenticationRepositoryImpl

    private val uid1 = "user_account_1"
    private val uid2 = "user_account_2"

    @Before
    fun setUp() {
        PinSecurityManager.pbkdf2Iterations = 100
        context = ApplicationProvider.getApplicationContext()
        pinSecurityManager = PinSecurityManager(context)
        repository = PinAuthenticationRepositoryImpl(
            pinSecurityManager = pinSecurityManager,
            dispatchers = TestDispatcherProvider()
        )
        pinSecurityManager.disablePin(uid1)
        pinSecurityManager.disablePin(uid2)
    }

    @Test
    fun setupPin_validInput_savesPin() = runBlocking {
        assertFalse(repository.isPinConfigured(uid1))

        val result = repository.setupPin(uid1, "123456")

        assertTrue(result.isSuccess)
        assertTrue(repository.isPinConfigured(uid1))
    }

    @Test
    fun setupPin_invalidPinLength_returnsFailure() = runBlocking {
        val result = repository.setupPin(uid1, "123")

        assertTrue(result.isFailure)
        assertFalse(repository.isPinConfigured(uid1))
    }

    @Test
    fun validatePin_correctPin_returnsSuccess() = runBlocking {
        repository.setupPin(uid1, "998877")

        val res = repository.validatePin(uid1, "998877")

        assertEquals(PinValidationResult.Success, res)
    }

    @Test
    fun changePin_validOldPin_updatesPin() = runBlocking {
        repository.setupPin(uid1, "111111")

        val changeRes = repository.changePin(uid1, "111111", "222222")

        assertTrue(changeRes.isSuccess)
        assertEquals(PinValidationResult.Success, repository.validatePin(uid1, "222222"))
    }

    @Test
    fun changePin_invalidOldPin_fails() = runBlocking {
        repository.setupPin(uid1, "111111")

        val changeRes = repository.changePin(uid1, "000000", "222222")

        assertTrue(changeRes.isFailure)
        assertEquals(PinValidationResult.Success, repository.validatePin(uid1, "111111"))
    }

    @Test
    fun disablePin_removesPin() = runBlocking {
        repository.setupPin(uid1, "123456")
        assertTrue(repository.isPinConfigured(uid1))

        val disableRes = repository.disablePin(uid1)

        assertTrue(disableRes.isSuccess)
        assertFalse(repository.isPinConfigured(uid1))
    }

    @Test
    fun resetPinWithOtp_resetsPinAndClearsLockout() = runBlocking {
        repository.setupPin(uid1, "111111")
        repeat(5) {
            repository.validatePin(uid1, "000000")
        }

        val lockoutRes = repository.validatePin(uid1, "111111")
        assertTrue(lockoutRes is PinValidationResult.LockedOut)

        val resetRes = repository.resetPinWithOtp(uid1, "333333")

        assertTrue(resetRes.isSuccess)
        assertEquals(PinValidationResult.Success, repository.validatePin(uid1, "333333"))
    }
}
