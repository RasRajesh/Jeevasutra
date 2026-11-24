package com.example.abhayhealth.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import org.json.JSONObject

/**
 * Android KeyStore-backed PIN Security Manager for JeevaSutra.
 *
 * Implements PBKDF2-HMAC-SHA256 salted PIN verifier hashing, AES-256 GCM metadata
 * encryption via KeyStore master key, constant-time validation, and progressive lockout throttling.
 */
class PinSecurityManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "abhay_pin_security_encrypted"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "JeevaSutraPinMasterKey"
        private const val AES_GCM_NO_PADDING = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val SALT_LENGTH_BYTES = 16
        private const val HASH_BIT_LENGTH = 256

        private const val MAX_FAILED_ATTEMPTS = 5
        private const val INITIAL_LOCKOUT_MS = 30_000L // 30 seconds

        @Volatile
        var pbkdf2Iterations: Int = 10000
    }

    /**
     * Checks if a PIN is configured for the given account UID.
     */
    fun isPinConfigured(accountUid: String): Boolean {
        if (accountUid.isBlank()) return false
        val encryptedData = prefs.getString(getPrefsKey(accountUid), null) ?: return false
        val json = decryptPayload(encryptedData) ?: return false
        return json.optString("account_uid") == accountUid && json.optString("verifier_hex").isNotBlank()
    }

    /**
     * Stores a newly configured 6-digit PIN for the given account UID.
     */
    fun setupPin(accountUid: String, pin: String): Boolean {
        require(accountUid.isNotBlank()) { "Account UID cannot be blank" }
        require(pin.length == 6 && pin.all { it.isDigit() }) { "PIN must be exactly 6 numeric digits" }

        val salt = generateSalt()
        val verifierHash = computePbkdf2Hash(pin, salt)

        val json = JSONObject().apply {
            put("account_uid", accountUid)
            put("salt_hex", toHex(salt))
            put("verifier_hex", toHex(verifierHash))
            put("failed_attempts", 0)
            put("lockout_until_ms", 0L)
        }

        val encryptedPayload = encryptPayload(json.toString()) ?: return false
        return prefs.edit().putString(getPrefsKey(accountUid), encryptedPayload).commit()
    }

    /**
     * Result of validating a PIN.
     */
    sealed interface ValidationResult {
        object Success : ValidationResult
        data class IncorrectPin(val remainingAttempts: Int) : ValidationResult
        data class LockedOut(val secondsRemaining: Long) : ValidationResult
        object NotConfigured : ValidationResult
    }

    /**
     * Validates an entered PIN against the stored verifier for [accountUid].
     */
    fun validatePin(accountUid: String, pin: String): ValidationResult {
        if (!isPinConfigured(accountUid)) {
            return ValidationResult.NotConfigured
        }

        val encryptedData = prefs.getString(getPrefsKey(accountUid), null)
            ?: return ValidationResult.NotConfigured
        val json = decryptPayload(encryptedData) ?: return ValidationResult.NotConfigured

        val now = System.currentTimeMillis()
        val lockoutUntil = json.optLong("lockout_until_ms", 0L)

        if (now < lockoutUntil) {
            val secondsRemaining = ((lockoutUntil - now) / 1000L).coerceAtLeast(1L)
            return ValidationResult.LockedOut(secondsRemaining)
        }

        val salt = fromHex(json.optString("salt_hex"))
        val storedVerifier = fromHex(json.optString("verifier_hex"))
        val enteredVerifier = computePbkdf2Hash(pin, salt)

        val isValid = MessageDigest.isEqual(storedVerifier, enteredVerifier)

        if (isValid) {
            json.put("failed_attempts", 0)
            json.put("lockout_until_ms", 0L)
            val updatedPayload = encryptPayload(json.toString())
            if (updatedPayload != null) {
                prefs.edit().putString(getPrefsKey(accountUid), updatedPayload).apply()
            }
            return ValidationResult.Success
        } else {
            val failedAttempts = json.optInt("failed_attempts", 0) + 1
            var newLockoutUntil = 0L

            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                val lockoutMultiplier = (failedAttempts - MAX_FAILED_ATTEMPTS + 1)
                val lockoutDurationMs = INITIAL_LOCKOUT_MS * lockoutMultiplier
                newLockoutUntil = now + lockoutDurationMs
            }

            json.put("failed_attempts", failedAttempts)
            json.put("lockout_until_ms", newLockoutUntil)
            val updatedPayload = encryptPayload(json.toString())
            if (updatedPayload != null) {
                prefs.edit().putString(getPrefsKey(accountUid), updatedPayload).apply()
            }

            if (newLockoutUntil > now) {
                val secondsRemaining = ((newLockoutUntil - now) / 1000L).coerceAtLeast(1L)
                return ValidationResult.LockedOut(secondsRemaining)
            }

            val remainingAttempts = (MAX_FAILED_ATTEMPTS - failedAttempts).coerceAtLeast(0)
            return ValidationResult.IncorrectPin(remainingAttempts)
        }
    }

    /**
     * Resets failed attempts and lockout timers when user re-authenticates via Mobile OTP.
     */
    fun resetLockoutAndSetupNewPin(accountUid: String, newPin: String): Boolean {
        return setupPin(accountUid, newPin)
    }

    /**
     * Disables and removes PIN configuration for [accountUid].
     */
    fun disablePin(accountUid: String): Boolean {
        if (accountUid.isBlank()) return false
        return prefs.edit().remove(getPrefsKey(accountUid)).commit()
    }

    /**
     * Gets seconds remaining if account is currently locked out, otherwise returns 0.
     */
    fun getLockoutSecondsRemaining(accountUid: String): Long {
        val encryptedData = prefs.getString(getPrefsKey(accountUid), null) ?: return 0L
        val json = decryptPayload(encryptedData) ?: return 0L
        val lockoutUntil = json.optLong("lockout_until_ms", 0L)
        val now = System.currentTimeMillis()
        return if (now < lockoutUntil) ((lockoutUntil - now) / 1000L).coerceAtLeast(1L) else 0L
    }

    // --- Cryptographic Helpers ---

    private fun getPrefsKey(accountUid: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(accountUid.toByteArray(Charsets.UTF_8))
        return "pin_config_" + toHex(digest).take(16)
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun computePbkdf2Hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, pbkdf2Iterations, HASH_BIT_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    @Volatile
    private var inMemoryFallbackKey: SecretKey? = null

    private fun getOrCreateMasterKey(): SecretKey {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                if (entry != null) {
                    return entry.secretKey
                }
            }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            SecurityAudit.logError("PinSecurityManager", "AndroidKeyStore unavailable, using fallback KeyGenerator", e)
            if (inMemoryFallbackKey == null) {
                synchronized(this) {
                    if (inMemoryFallbackKey == null) {
                        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES)
                        keyGenerator.init(256)
                        inMemoryFallbackKey = keyGenerator.generateKey()
                    }
                }
            }
            inMemoryFallbackKey!!
        }
    }

    private fun encryptPayload(plainText: String): String? {
        return try {
            val secretKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

            val combined = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            SecurityAudit.logError("PinSecurityManager", "Failed to encrypt PIN payload", e)
            null
        }
    }

    private fun decryptPayload(encryptedBase64: String): JSONObject? {
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return null

            val iv = ByteArray(GCM_IV_LENGTH)
            val cipherText = ByteArray(combined.size - GCM_IV_LENGTH)
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.size)

            val secretKey = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(AES_GCM_NO_PADDING)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            val plainTextBytes = cipher.doFinal(cipherText)
            JSONObject(String(plainTextBytes, Charsets.UTF_8))
        } catch (e: Exception) {
            SecurityAudit.logError("PinSecurityManager", "Failed to decrypt PIN payload", e)
            null
        }
    }

    private fun toHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun fromHex(hex: String): ByteArray {
        if (hex.length % 2 != 0) return ByteArray(0)
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }
}
