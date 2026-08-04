package ox.fzer0x.snakeloader.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptionManager {
    private const val TAG = "EncryptionManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "reshift_encryption_key"
    private const val ENCRYPTION_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
    private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
    private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE
    private const val TRANSFORMATION = "$ENCRYPTION_ALGORITHM/$BLOCK_MODE/$PADDING"
    
    private var keyStore: KeyStore? = null
    private var isInitialized = false

    fun initialize(context: Context) {
        if (isInitialized) return
        
        try {
            keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore!!.load(null)
            
            if (!keyStore!!.containsAlias(KEY_ALIAS)) {
                generateKey()
                Log.d(TAG, "Encryption key generated")
            } else {
                Log.d(TAG, "Encryption key loaded from keystore")
            }
            
            isInitialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize EncryptionManager", e)
        }
    }

    private fun generateKey() {
        try {
            val keyGenerator = KeyGenerator.getInstance(
                ENCRYPTION_ALGORITHM,
                ANDROID_KEYSTORE
            )
            
            val keyGenSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(BLOCK_MODE)
                .setEncryptionPaddings(PADDING)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
            
            keyGenerator.init(keyGenSpec)
            keyGenerator.generateKey()
            
            Log.d(TAG, "AES-256-GCM key generated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate encryption key", e)
            throw SecurityException("Failed to generate encryption key", e)
        }
    }

    fun encrypt(data: String): String {
        if (!isInitialized) {
            Log.e(TAG, "EncryptionManager not initialized")
            return data
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = getSecretKey()
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            
            val combined = iv + encryptedBytes
            
            Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            data
        }
    }

    fun decrypt(encryptedData: String): String {
        if (!isInitialized || encryptedData.isEmpty()) {
            return encryptedData
        }

        return try {
            val combined = Base64.getDecoder().decode(encryptedData)
            
            if (combined.size < 12) return encryptedData

            val iv = combined.sliceArray(0..11)
            val encryptedBytes = combined.sliceArray(12 until combined.size)
            
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val secretKey = getSecretKey()
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed: ${e.message}")
            encryptedData
        }
    }

    private fun getSecretKey(): SecretKey {
        return try {
            val secretKeyEntry = keyStore!!.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            secretKeyEntry.secretKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get secret key", e)
            throw SecurityException("Failed to get secret key", e)
        }
    }

    fun isAvailable(): Boolean = isInitialized

    fun encryptBoolean(value: Boolean): String {
        return encrypt(value.toString())
    }

    fun decryptBoolean(encryptedData: String): Boolean {
        return try {
            decrypt(encryptedData).toBoolean()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt boolean", e)
            false
        }
    }

    fun encryptInt(value: Int): String {
        return encrypt(value.toString())
    }

    fun decryptInt(encryptedData: String): Int {
        return try {
            decrypt(encryptedData).toInt()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt int", e)
            0
        }
    }

    fun migrateToEncrypted(plaintext: String, isAlreadyEncrypted: Boolean = false): String {
        if (isAlreadyEncrypted || !isAvailable()) {
            return plaintext
        }
        return encrypt(plaintext)
    }

    fun deleteKey() {
        try {
            keyStore?.deleteEntry(KEY_ALIAS)
            Log.d(TAG, "Encryption key deleted")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete encryption key", e)
        }
    }
}
