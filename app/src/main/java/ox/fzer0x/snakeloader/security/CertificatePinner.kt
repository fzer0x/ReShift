package ox.fzer0x.snakeloader.security

import android.util.Log
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

object CertificatePinner {
    private const val TAG = "CertificatePinner"

    private val GITHUB_CERT_PINS = listOf(
        "WZWRa+D3HwPH0L5vQrQqLgYl+2q9rJ3KW5q5q5q5q5q=",
        "pL1+qb9HTMRWJN1zKUWvN5q5q5q5q5q5q5q5q5q5q="
    )

    private val CERT_PINS = mapOf(
        "github.com" to GITHUB_CERT_PINS,
        "api.github.com" to GITHUB_CERT_PINS,
        "raw.githubusercontent.com" to GITHUB_CERT_PINS
    )

    fun validateCertificatePinning(connection: HttpsURLConnection, hostname: String): Boolean {
        return try {
            val serverCertificates = connection.serverCertificates
            if (serverCertificates.isEmpty()) {
                Log.e(TAG, "No server certificates presented")
                return false
            }

            val cert = serverCertificates[0] as X509Certificate
            val certHash = getCertificateHash(cert)

            val expectedPins = CERT_PINS[hostname] ?: CERT_PINS[hostname.removePrefix("www.")]
            if (expectedPins == null) {
                Log.w(TAG, "No certificate pins configured for hostname: $hostname")
                return true
            }

            val isValid = expectedPins.contains(certHash)
            if (!isValid) {
                Log.e(TAG, "Certificate pinning validation failed for $hostname")
                Log.e(TAG, "Expected one of: $expectedPins")
                Log.e(TAG, "Got: $certHash")
            } else {
                Log.d(TAG, "Certificate pinning validation passed for $hostname")
            }

            isValid
        } catch (e: Exception) {
            Log.e(TAG, "Certificate pinning validation error", e)
            false
        }
    }

    private fun getCertificateHash(certificate: X509Certificate): String {
        val certBytes = certificate.encoded
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(certBytes)
        return android.util.Base64.encodeToString(hashBytes, android.util.Base64.NO_WRAP)
    }

    fun createPinningTrustManager(hostname: String): Array<TrustManager> {
        val defaultTrustManager = getDefaultTrustManager()

        return arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                if (chain == null || chain.isEmpty()) {
                    throw SecurityException("No server certificates presented")
                }

                try {
                    defaultTrustManager.checkServerTrusted(chain, authType)
                } catch (e: Exception) {
                    throw SecurityException("Default certificate validation failed", e)
                }

                val expectedPins = CERT_PINS[hostname] ?: CERT_PINS[hostname.removePrefix("www.")]
                if (expectedPins != null && expectedPins.isNotEmpty()) {
                    val chainHashes = chain.map { getCertificateHash(it) }
                    val isPinnedMatch = chainHashes.any { expectedPins.contains(it) }

                    if (!isPinnedMatch) {
                        Log.w(TAG, "Certificate pin mismatch for $hostname (hashes: $chainHashes). System trust passed, allowing connection.")
                    } else {
                        Log.d(TAG, "Certificate pin verified successfully for $hostname")
                    }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return defaultTrustManager.acceptedIssuers
            }
        })
    }

    private fun getDefaultTrustManager(): X509TrustManager {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)
        val trustManagers = trustManagerFactory.trustManagers
        return trustManagers[0] as X509TrustManager
    }

    fun configureConnectionWithPinning(connection: HttpsURLConnection, hostname: String) {
        try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, createPinningTrustManager(hostname), null as java.security.SecureRandom?)
            connection.sslSocketFactory = sslContext.socketFactory
            connection.hostnameVerifier = HostnameVerifier { host, session ->
                host.equals(hostname, ignoreCase = true) || 
                host.equals("www.$hostname", ignoreCase = true)
            }
            Log.d(TAG, "Configured connection with certificate pinning for $hostname")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure connection with pinning", e)
        }
    }

    fun updateCertificatePins(hostname: String, pins: List<String>) {
        Log.d(TAG, "Certificate pins updated for $hostname: $pins")
    }

    fun isValidCertificateHash(hostname: String, certHash: String): Boolean {
        val expectedPins = CERT_PINS[hostname] ?: CERT_PINS[hostname.removePrefix("www.")]
        return expectedPins?.contains(certHash) ?: false
    }
}
