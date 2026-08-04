/**
 * Universal SSL Pinning Bypass
 */
(function() {
    const TAG = "SSL-Bypass";
    GlobalLogger.info("Initializing Universal SSL Bypass...", TAG);

    Java.perform(function() {
        // TrustManager Bypass
        try {
            const TrustManagerImpl = Java.use('com.android.org.conscrypt.TrustManagerImpl');
            TrustManagerImpl.checkTrustedRecursive.implementation = function(a, b, c, d, e, f) {
                GlobalLogger.success("Bypassed TrustManagerImpl check", TAG);
                return Array.use('java.util.ArrayList').$new();
            };
        } catch (e) {
            GlobalLogger.warn("TrustManagerImpl not found", TAG);
        }

        // OkHttp3 Bypass (Common in Games/Apps)
        try {
            const CertificatePinner = Java.use('okhttp3.CertificatePinner');
            CertificatePinner.check.overload('java.lang.String', 'java.util.List').implementation = function(host, certs) {
                GlobalLogger.success("Bypassed OkHttp3 Pinning for: " + host, TAG);
            };
        } catch (e) {
            GlobalLogger.warn("OkHttp3 not found, skipping...", TAG);
        }

        // Network Security Config Bypass
        try {
            const NetworkSecurityConfig = Java.use('android.security.net.config.NetworkSecurityConfig');
            NetworkSecurityConfig.isCleartextTrafficPermitted.overload().implementation = function() {
                return true;
            };
        } catch (e) {}

        registerHook("ssl_bypass", "Multiple", () => GlobalLogger.info("SSL Bypass is permanent", TAG));
    });
})();
