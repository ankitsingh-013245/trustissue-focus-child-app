package com.trustissue.child

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal IPv4/UDP/DNS codec for the split-tunnel Web Protection interface.
 *
 * Only packets sent to the VPN's synthetic DNS address are parsed. All web
 * page traffic remains on Android's normal network path.
 */
object DnsPacketCodec {
    data class Request(
        val sourceAddress: ByteArray,
        val destinationAddress: ByteArray,
        val sourcePort: Int,
        val destinationPort: Int,
        val dnsPayload: ByteArray,
        val ipIdentification: Int
    )

    fun parseRequest(packet: ByteArray, length: Int): Request? {
        if (length < 28) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null
        val headerLength = (packet[0].toInt() and 0x0F) * 4
        if (headerLength < 20 || length < headerLength + 8) return null
        if ((packet[9].toInt() and 0xFF) != 17) return null
        val totalLength = readU16(packet, 2).coerceAtMost(length)
        val udpOffset = headerLength
        val sourcePort = readU16(packet, udpOffset)
        val destinationPort = readU16(packet, udpOffset + 2)
        val udpLength = readU16(packet, udpOffset + 4)
        val payloadOffset = udpOffset + 8
        val payloadLength = (udpLength - 8)
            .coerceAtMost(totalLength - payloadOffset)
        if (destinationPort != 53 || payloadLength < 12) return null
        return Request(
            sourceAddress = packet.copyOfRange(12, 16),
            destinationAddress = packet.copyOfRange(16, 20),
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            dnsPayload = packet.copyOfRange(payloadOffset, payloadOffset + payloadLength),
            ipIdentification = readU16(packet, 4)
        )
    }

    fun queryName(dnsPayload: ByteArray): String? {
        if (dnsPayload.size < 17 || readU16(dnsPayload, 4) < 1) return null
        var offset = 12
        val labels = mutableListOf<String>()
        var totalLength = 0
        while (offset < dnsPayload.size) {
            val labelLength = dnsPayload[offset].toInt() and 0xFF
            offset += 1
            if (labelLength == 0) break
            if (labelLength and 0xC0 != 0 || labelLength > 63) return null
            if (offset + labelLength > dnsPayload.size) return null
            val label = dnsPayload.copyOfRange(offset, offset + labelLength)
                .toString(Charsets.US_ASCII)
            if (label.any { !(it.isLetterOrDigit() || it == '-' || it == '_') }) {
                return null
            }
            labels.add(label)
            totalLength += labelLength + 1
            if (totalLength > 253) return null
            offset += labelLength
        }
        return labels.joinToString(".").takeIf { it.isNotBlank() }
    }

    fun queryType(dnsPayload: ByteArray): Int? {
        val end = questionEnd(dnsPayload) ?: return null
        return readU16(dnsPayload, end - 4)
    }

    fun responseCode(dnsPayload: ByteArray): Int? {
        if (dnsPayload.size < 12) return null
        return readU16(dnsPayload, 2) and 0x0F
    }

    /**
     * Returns the shortest answer TTL. Malformed messages are rejected instead
     * of being cached with guessed offsets.
     */
    fun minimumAnswerTtlSeconds(dnsPayload: ByteArray): Long? {
        if (dnsPayload.size < 12) return null
        val questionCount = readU16(dnsPayload, 4)
        val answerCount = readU16(dnsPayload, 6)
        if (answerCount <= 0) return null

        var offset = 12
        repeat(questionCount) {
            offset = skipDnsName(dnsPayload, offset) ?: return null
            if (offset + 4 > dnsPayload.size) return null
            offset += 4
        }

        var minimum: Long? = null
        repeat(answerCount) {
            offset = skipDnsName(dnsPayload, offset) ?: return null
            if (offset + 10 > dnsPayload.size) return null
            val ttl = readU32(dnsPayload, offset + 4)
            val dataLength = readU16(dnsPayload, offset + 8)
            offset += 10
            if (offset + dataLength > dnsPayload.size) return null
            offset += dataLength
            minimum = minimum?.coerceAtMost(ttl) ?: ttl
        }
        return minimum
    }

    /**
     * Family DNS resolvers use an all-zero A/AAAA answer as an explicit
     * sinkhole for policy-blocked domains. Detect only that positive signal;
     * an ordinary NXDOMAIN is deliberately not classified as a policy block
     * because it may simply be a misspelled or nonexistent domain.
     */
    fun hasZeroAddressAnswer(dnsPayload: ByteArray): Boolean {
        if (dnsPayload.size < 12) return false
        val questionCount = readU16(dnsPayload, 4)
        val answerCount = readU16(dnsPayload, 6)
        if (answerCount <= 0) return false

        var offset = 12
        repeat(questionCount) {
            offset = skipDnsName(dnsPayload, offset) ?: return false
            if (offset + 4 > dnsPayload.size) return false
            offset += 4
        }

        repeat(answerCount) {
            offset = skipDnsName(dnsPayload, offset) ?: return false
            if (offset + 10 > dnsPayload.size) return false
            val recordType = readU16(dnsPayload, offset)
            val recordClass = readU16(dnsPayload, offset + 2)
            val dataLength = readU16(dnsPayload, offset + 8)
            val dataOffset = offset + 10
            if (dataOffset + dataLength > dnsPayload.size) return false
            val addressLength = when (recordType) {
                1 -> 4
                28 -> 16
                else -> 0
            }
            if (
                recordClass == 1 &&
                dataLength == addressLength &&
                addressLength > 0 &&
                (dataOffset until dataOffset + dataLength).all {
                    dnsPayload[it] == 0.toByte()
                }
            ) {
                return true
            }
            offset = dataOffset + dataLength
        }
        return false
    }

    fun buildNxDomain(query: ByteArray): ByteArray {
        return buildError(query, 3)
    }

    /** Policy refusal is intentionally non-authoritative and short-lived. */
    fun buildRefused(query: ByteArray): ByteArray {
        return buildError(query, 5)
    }

    fun buildServerFailure(query: ByteArray): ByteArray {
        return buildError(query, 2)
    }

    fun buildQuery(
        domain: String,
        transactionId: Int = 0x5452
    ): ByteArray? {
        val encodedName = encodeDnsName(domain) ?: return null
        val header = ByteArray(12)
        writeU16(header, 0, transactionId)
        writeU16(header, 2, 0x0100)
        writeU16(header, 4, 1)
        return ByteArrayOutputStream(12 + encodedName.size + 4).apply {
            write(header)
            write(encodedName)
            write(byteArrayOf(0x00, 0x01, 0x00, 0x01))
        }.toByteArray()
    }

    fun buildCnameResponse(query: ByteArray, canonicalName: String): ByteArray? {
        val questionEnd = questionEnd(query) ?: return null
        val encodedName = encodeDnsName(canonicalName) ?: return null
        val output = ByteArrayOutputStream(questionEnd + encodedName.size + 16)
        val header = query.copyOfRange(0, questionEnd)
        val requestFlags = readU16(query, 2)
        writeU16(header, 2, 0x8000 or (requestFlags and 0x0100) or 0x0080)
        writeU16(header, 6, 1)
        writeU16(header, 8, 0)
        writeU16(header, 10, 0)
        output.write(header)
        output.write(byteArrayOf(0xC0.toByte(), 0x0C))
        output.write(byteArrayOf(0x00, 0x05))
        output.write(byteArrayOf(0x00, 0x01))
        output.write(byteArrayOf(0x00, 0x00, 0x01, 0x2C))
        output.write(byteArrayOf(
            ((encodedName.size ushr 8) and 0xFF).toByte(),
            (encodedName.size and 0xFF).toByte()
        ))
        output.write(encodedName)
        return output.toByteArray()
    }

    fun withTransactionId(response: ByteArray, query: ByteArray): ByteArray {
        if (response.size < 2 || query.size < 2) return response
        return response.copyOf().apply {
            this[0] = query[0]
            this[1] = query[1]
        }
    }

    fun isResponseFor(query: ByteArray, response: ByteArray): Boolean {
        if (query.size < 12 || response.size < 12) return false
        val transactionMatches =
            query[0] == response[0] && query[1] == response[1]
        val isResponse = readU16(response, 2) and 0x8000 != 0
        return transactionMatches && isResponse
    }

    fun wrapResponse(request: Request, dnsResponse: ByteArray): ByteArray {
        val totalLength = 20 + 8 + dnsResponse.size
        val packet = ByteArray(totalLength)
        packet[0] = 0x45
        packet[1] = 0
        writeU16(packet, 2, totalLength)
        writeU16(packet, 4, request.ipIdentification)
        writeU16(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = 17
        request.destinationAddress.copyInto(packet, 12)
        request.sourceAddress.copyInto(packet, 16)
        writeU16(packet, 20, request.destinationPort)
        writeU16(packet, 22, request.sourcePort)
        writeU16(packet, 24, 8 + dnsResponse.size)
        writeU16(packet, 26, 0)
        dnsResponse.copyInto(packet, 28)
        writeU16(packet, 10, ipChecksum(packet, 0, 20))
        return packet
    }

    private fun buildError(query: ByteArray, responseCode: Int): ByteArray {
        val end = questionEnd(query) ?: query.size.coerceAtMost(12)
        val response = query.copyOfRange(0, end)
        if (response.size < 12) return response
        val requestFlags = readU16(query, 2)
        val responseFlags =
            0x8000 or (requestFlags and 0x0100) or 0x0080 or (responseCode and 0x0F)
        writeU16(response, 2, responseFlags)
        writeU16(response, 6, 0)
        writeU16(response, 8, 0)
        writeU16(response, 10, 0)
        return response
    }

    private fun questionEnd(query: ByteArray): Int? {
        if (query.size < 17 || readU16(query, 4) < 1) return null
        var offset = 12
        while (offset < query.size) {
            val labelLength = query[offset].toInt() and 0xFF
            offset += 1
            if (labelLength == 0) break
            if (labelLength and 0xC0 != 0 || labelLength > 63) return null
            offset += labelLength
            if (offset > query.size) return null
        }
        return (offset + 4).takeIf { it <= query.size }
    }

    private fun skipDnsName(message: ByteArray, startOffset: Int): Int? {
        var offset = startOffset
        var labels = 0
        while (offset < message.size) {
            val length = message[offset].toInt() and 0xFF
            if (length and 0xC0 == 0xC0) {
                return (offset + 2).takeIf { it <= message.size }
            }
            if (length and 0xC0 != 0 || length > 63) return null
            offset += 1
            if (length == 0) return offset
            if (offset + length > message.size || labels++ > 127) return null
            offset += length
        }
        return null
    }

    private fun encodeDnsName(name: String): ByteArray? {
        val labels = name.trim('.').split('.')
        if (labels.isEmpty()) return null
        val output = ByteArrayOutputStream()
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) return null
            output.write(bytes.size)
            output.write(bytes)
        }
        output.write(0)
        return output.toByteArray()
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 1 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 3 >= bytes.size) return 0L
        return ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        if (offset < 0 || offset + 1 >= bytes.size) return
        bytes[offset] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 1] = (value and 0xFF).toByte()
    }

    private fun ipChecksum(bytes: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        val buffer = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.BIG_ENDIAN)
        while (buffer.remaining() >= 2) {
            sum += buffer.short.toInt() and 0xFFFF
        }
        if (buffer.remaining() == 1) {
            sum += (buffer.get().toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }
}
