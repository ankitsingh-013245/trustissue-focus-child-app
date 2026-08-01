package com.trustissue.child

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsPacketCodecTest {
    @Test
    fun queryMetadataIsParsed() {
        val query = requireNotNull(
            DnsPacketCodec.buildQuery("www.google.com", transactionId = 0x1234)
        )

        assertEquals("www.google.com", DnsPacketCodec.queryName(query))
        assertEquals(1, DnsPacketCodec.queryType(query))
    }

    @Test
    fun cnameResponseHasExpectedTtlAndTransaction() {
        val query = requireNotNull(
            DnsPacketCodec.buildQuery("www.google.com", transactionId = 0x1234)
        )
        val response = requireNotNull(
            DnsPacketCodec.buildCnameResponse(
                query,
                "forcesafesearch.google.com"
            )
        )

        assertTrue(DnsPacketCodec.isResponseFor(query, response))
        assertEquals(300L, DnsPacketCodec.minimumAnswerTtlSeconds(response))
        assertEquals(0, DnsPacketCodec.responseCode(response))
    }

    @Test
    fun serverFailureIsNotAcceptedAsAnotherTransaction() {
        val first = requireNotNull(
            DnsPacketCodec.buildQuery("example.com", transactionId = 1)
        )
        val second = requireNotNull(
            DnsPacketCodec.buildQuery("example.com", transactionId = 2)
        )
        val failure = DnsPacketCodec.buildServerFailure(first)

        assertEquals(2, DnsPacketCodec.responseCode(failure))
        assertTrue(DnsPacketCodec.isResponseFor(first, failure))
        assertFalse(DnsPacketCodec.isResponseFor(second, failure))
        assertNull(DnsPacketCodec.minimumAnswerTtlSeconds(failure))
    }

    @Test
    fun policyRefusalUsesRefusedResponseCodeWithoutCacheTtl() {
        val query = requireNotNull(
            DnsPacketCodec.buildQuery("blocked.example", transactionId = 3)
        )
        val refusal = DnsPacketCodec.buildRefused(query)

        assertEquals(5, DnsPacketCodec.responseCode(refusal))
        assertTrue(DnsPacketCodec.isResponseFor(query, refusal))
        assertNull(DnsPacketCodec.minimumAnswerTtlSeconds(refusal))
    }

    @Test
    fun zeroAddressSinkholeIsDistinguishedFromRealAndMissingDomains() {
        val query = requireNotNull(
            DnsPacketCodec.buildQuery("filtered.example", transactionId = 4)
        )

        assertTrue(
            DnsPacketCodec.hasZeroAddressAnswer(
                ipv4Response(query, byteArrayOf(0, 0, 0, 0))
            )
        )
        assertFalse(
            DnsPacketCodec.hasZeroAddressAnswer(
                ipv4Response(query, byteArrayOf(203.toByte(), 0, 113, 8))
            )
        )
        assertFalse(
            DnsPacketCodec.hasZeroAddressAnswer(
                DnsPacketCodec.buildNxDomain(query)
            )
        )
    }

    private fun ipv4Response(query: ByteArray, address: ByteArray): ByteArray {
        require(address.size == 4)
        val response = ByteArray(query.size + 16)
        query.copyInto(response)
        response[2] = 0x81.toByte()
        response[3] = 0x80.toByte()
        response[6] = 0
        response[7] = 1
        val answerOffset = query.size
        byteArrayOf(
            0xC0.toByte(), 0x0C,
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00, 0x00, 0x3C,
            0x00, 0x04
        ).copyInto(response, answerOffset)
        address.copyInto(response, answerOffset + 12)
        return response
    }
}
