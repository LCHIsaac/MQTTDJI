package com.dji.dronecontrol.core.mqtt

import com.dji.dronecontrol.core.model.MqttTlsConfig
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

object MqttTlsSocketFactory {

    fun createSocketFactory(tls: MqttTlsConfig): SSLSocketFactory {
        val keyStore = KeyStore.getInstance("PKCS12")
        ByteArrayInputStream(tls.pkcs12Bytes).use { input ->
            keyStore.load(input, tls.pkcs12Password.toCharArray())
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, tls.pkcs12Password.toCharArray())

        val trustManagers = if (tls.customCaPemBytes != null && tls.customCaPemBytes.isNotEmpty()) {
            val trustStore = KeyStore.getInstance(KeyStore.getDefaultType())
            trustStore.load(null, null)
            val cf = CertificateFactory.getInstance("X.509")
            val cert = ByteArrayInputStream(tls.customCaPemBytes).use { cf.generateCertificate(it) }
            trustStore.setCertificateEntry("mqtt-ca", cert)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(trustStore)
            tmf.trustManagers
        } else {
            null
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(
            kmf.keyManagers,
            trustManagers,
            null,
        )
        return sslContext.socketFactory
    }
}
