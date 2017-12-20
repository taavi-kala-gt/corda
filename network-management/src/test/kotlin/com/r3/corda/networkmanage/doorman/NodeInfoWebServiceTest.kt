package com.r3.corda.networkmanage.doorman

import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.times
import com.nhaarman.mockito_kotlin.verify
import com.r3.corda.networkmanage.common.persistence.NetworkMapStorage
import com.r3.corda.networkmanage.common.persistence.NodeInfoStorage
import com.r3.corda.networkmanage.common.utils.withCert
import com.r3.corda.networkmanage.doorman.webservice.NodeInfoWebService
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.SecureHash
import net.corda.core.crypto.sha256
import net.corda.core.crypto.sign
import net.corda.core.identity.CordaX500Name
import net.corda.core.internal.cert
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.serialize
import net.corda.core.utilities.NetworkHostAndPort
import net.corda.core.utilities.seconds
import net.corda.nodeapi.internal.SignedNodeInfo
import net.corda.nodeapi.internal.crypto.CertificateType
import net.corda.nodeapi.internal.crypto.X509Utilities
import net.corda.nodeapi.internal.network.NetworkMap
import net.corda.nodeapi.internal.network.SignedNetworkMap
import net.corda.testing.SerializationEnvironmentRule
import net.corda.testing.internal.createNodeInfoAndSigned
import org.bouncycastle.asn1.x500.X500Name
import org.junit.Rule
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.ws.rs.core.MediaType
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NodeInfoWebServiceTest {
    @Rule
    @JvmField
    val testSerialization = SerializationEnvironmentRule(true)

    private val testNetwotkMapConfig =  NetworkMapConfig(10.seconds.toMillis(), 10.seconds.toMillis())

    @Test
    fun `submit nodeInfo`() {
        // Create node info.
        val (_, signedNodeInfo) = createNodeInfoAndSigned(CordaX500Name("Test", "London", "GB"))

        NetworkManagementWebServer(NetworkHostAndPort("localhost", 0), NodeInfoWebService(mock(), mock(), testNetwotkMapConfig)).use {
            it.start()
            val registerURL = URL("http://${it.hostAndPort}/${NodeInfoWebService.NETWORK_MAP_PATH}/publish")
            val nodeInfoAndSignature = signedNodeInfo.serialize().bytes
            // Post node info and signature to doorman, this should pass without any exception.
            doPost(registerURL, nodeInfoAndSignature)
        }
    }

    @Test
    fun `get network map`() {
        val rootCAKey = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
        val rootCACert = X509Utilities.createSelfSignedCACertificate(CordaX500Name(locality = "London", organisation = "R3 LTD", country = "GB", commonName = "Corda Node Root CA"), rootCAKey)
        val intermediateCAKey = Crypto.generateKeyPair(X509Utilities.DEFAULT_TLS_SIGNATURE_SCHEME)
        val intermediateCACert = X509Utilities.createCertificate(CertificateType.INTERMEDIATE_CA, rootCACert, rootCAKey, X500Name("CN=Corda Node Intermediate CA,L=London"), intermediateCAKey.public)

        val networkMap = NetworkMap(listOf(SecureHash.randomSHA256(), SecureHash.randomSHA256()), SecureHash.randomSHA256())
        val serializedNetworkMap = networkMap.serialize()
        val networkMapStorage: NetworkMapStorage = mock {
            on { getCurrentNetworkMap() }.thenReturn(SignedNetworkMap(serializedNetworkMap, intermediateCAKey.sign(serializedNetworkMap).withCert(intermediateCACert.cert)))
        }
        NetworkManagementWebServer(NetworkHostAndPort("localhost", 0), NodeInfoWebService(mock(), networkMapStorage, testNetwotkMapConfig)).use {
            it.start()
            val conn = URL("http://${it.hostAndPort}/${NodeInfoWebService.NETWORK_MAP_PATH}").openConnection() as HttpURLConnection
            val signedNetworkMap = conn.inputStream.readBytes().deserialize<SignedNetworkMap>()
            verify(networkMapStorage, times(1)).getCurrentNetworkMap()
            assertEquals(signedNetworkMap.verified(rootCACert.cert), networkMap)
        }
    }

    @Test
    fun `get node info`() {
        val (nodeInfo, signedNodeInfo) = createNodeInfoAndSigned(CordaX500Name("Test", "London", "GB"))

        val nodeInfoHash = nodeInfo.serialize().sha256()

        val nodeInfoStorage: NodeInfoStorage = mock {
            on { getNodeInfo(nodeInfoHash) }.thenReturn(signedNodeInfo)
        }

        NetworkManagementWebServer(NetworkHostAndPort("localhost", 0), NodeInfoWebService(nodeInfoStorage, mock(), testNetwotkMapConfig)).use {
            it.start()
            val nodeInfoURL = URL("http://${it.hostAndPort}/${NodeInfoWebService.NETWORK_MAP_PATH}/node-info/$nodeInfoHash")
            val conn = nodeInfoURL.openConnection()
            val nodeInfoResponse = conn.inputStream.readBytes().deserialize<SignedNodeInfo>()
            verify(nodeInfoStorage, times(1)).getNodeInfo(nodeInfoHash)
            assertEquals(nodeInfo, nodeInfoResponse.verified())

            assertFailsWith(FileNotFoundException::class) {
                URL("http://${it.hostAndPort}/${NodeInfoWebService.NETWORK_MAP_PATH}/${SecureHash.randomSHA256()}").openConnection().getInputStream()
            }
        }
    }

    private fun doPost(url: URL, payload: ByteArray) {
        val conn = url.openConnection() as HttpURLConnection
        conn.doOutput = true
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", MediaType.APPLICATION_OCTET_STREAM)
        conn.outputStream.write(payload)

        return try {
            conn.inputStream.bufferedReader().use { it.readLine() }
        } catch (e: IOException) {
            throw IOException(conn.errorStream.bufferedReader().readLine(), e)
        }
    }
}