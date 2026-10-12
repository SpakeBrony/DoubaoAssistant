package com.spake.brony.doubaoassistant.data.repository

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

// 主机没有 Android Keystore；只替换密钥提供者，仍使用真实 AES-GCM 加解密。
object TestCredentialKeys {
    internal val keys = mutableMapOf<String, SecretKey>()
    fun install() {
        Security.removeProvider("AndroidKeyStore")
        Security.addProvider(object : Provider("AndroidKeyStore", 1.0, "Unit test key storage") {
            init {
                put("KeyStore.AndroidKeyStore", TestKeyStore::class.java.name)
                put("KeyGenerator.AES", TestAesKeyGenerator::class.java.name)
            }
        })
    }
}

class TestAesKeyGenerator : KeyGeneratorSpi() {
    private var alias = ""
    override fun engineInit(random: SecureRandom?) = Unit
    override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
    override fun engineInit(params: AlgorithmParameterSpec, random: SecureRandom?) {
        alias = (params as KeyGenParameterSpec).keystoreAlias
    }
    override fun engineGenerateKey(): SecretKey = SecretKeySpec(ByteArray(32).also {
        SecureRandom().nextBytes(it)
    }, "AES").also { TestCredentialKeys.keys[alias] = it }
}

class TestKeyStore : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? = TestCredentialKeys.keys[alias]
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCreationDate(alias: String): Date = Date()
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
        TestCredentialKeys.keys[alias] = key as SecretKey
    }
    override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) = error("Unused")
    override fun engineSetCertificateEntry(alias: String, cert: Certificate) = error("Unused")
    override fun engineDeleteEntry(alias: String) { TestCredentialKeys.keys.remove(alias) }
    override fun engineAliases() = Collections.enumeration(TestCredentialKeys.keys.keys)
    override fun engineContainsAlias(alias: String) = TestCredentialKeys.keys.containsKey(alias)
    override fun engineSize() = TestCredentialKeys.keys.size
    override fun engineIsKeyEntry(alias: String) = engineContainsAlias(alias)
    override fun engineIsCertificateEntry(alias: String) = false
    override fun engineGetCertificateAlias(cert: Certificate): String? = null
    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}
