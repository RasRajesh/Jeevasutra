package com.example.abhayhealth.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
class PinSecurityManagerTest {

    private lateinit var context: Context
    private lateinit var pinSecurityManager: PinSecurityManager

    private val testUid = "test_user_uid_12345"
    private val testUid2 = "test_user_uid_67890"

    @Before
    fun setUp() {
        PinSecurityManager.pbkdf2Iterations = 100
        context = ApplicationProvider.getApplicationContext()
        pinSecurityManager = PinSecurityManager(context)
        pinSecurityManager.disablePin(testUid)
        pinSecurityManager.disablePin(testUid2)
    }

    @Test
    fun setupPin_validPin_configuresSuccessfully() {
        assertFalse(pinSecurityManager.isPinConfigured(testUid))

        val result = pinSecurityManager.setupPin(testUid, "123456")

        assertTrue(result)
        assertTrue(pinSecurityManager.isPinConfigured(testUid))
    }

    @Test(expected = IllegalArgumentException::class)
    fun setupPin_invalidLength_throwsException() {
        pinSecurityManager.setupPin(testUid, "12345")
    }

    @Test(expected = IllegalArgumentException::class)
    fun setupPin_nonDigits_throwsException() {
        pinSecurityManager.setupPin(testUid, "12345a")
    }

    @Test
    fun validatePin_correctPin_returnsSuccess() {
        pinSecurityManager.setupPin(testUid, "654321")

        val result = pinSecurityManager.validatePin(testUid, "654321")

        assertTrue(result is PinSecurityManager.ValidationResult.Success)
    }

    @Test
    fun validatePin_incorrectPin_returnsIncorrectResult() {
        pinSecurityManager.setupPin(testUid, "112233")

        val result = pinSecurityManager.validatePin(testUid, "999999")

        assertTrue(result is PinSecurityManager.ValidationResult.IncorrectPin)
        val incorrect = result as PinSecurityManager.ValidationResult.IncorrectPin
        assertEquals(4, incorrect.remainingAttempts)
    }

    @Test
    fun validatePin_fiveIncorrectAttempts_triggersLockout() {
        pinSecurityManager.setupPin(testUid, "888888")

        repeat(4) {
            val res = pinSecurityManager.validatePin(testUid, "000000")
            assertTrue(res is PinSecurityManager.ValidationResult.IncorrectPin)
        }

        val fifthAttempt = pinSecurityManager.validatePin(testUid, "000000")
        assertTrue(fifthAttempt is PinSecurityManager.ValidationResult.LockedOut)

        val seconds = pinSecurityManager.getLockoutSecondsRemaining(testUid)
        assertTrue(seconds > 0)
    }

    @Test
    fun accountIsolation_uid1PinDoesNotUnlockUid2() {
        pinSecurityManager.setupPin(testUid, "111111")
        pinSecurityManager.setupPin(testUid2, "222222")

        val res1 = pinSecurityManager.validatePin(testUid, "222222")
        assertTrue(res1 is PinSecurityManager.ValidationResult.IncorrectPin)

        val res2 = pinSecurityManager.validatePin(testUid2, "222222")
        assertTrue(res2 is PinSecurityManager.ValidationResult.Success)
    }

    @Test
    fun disablePin_removesPinConfiguration() {
        pinSecurityManager.setupPin(testUid, "123456")
        assertTrue(pinSecurityManager.isPinConfigured(testUid))

        val disabled = pinSecurityManager.disablePin(testUid)

        assertTrue(disabled)
        assertFalse(pinSecurityManager.isPinConfigured(testUid))
    }
}
