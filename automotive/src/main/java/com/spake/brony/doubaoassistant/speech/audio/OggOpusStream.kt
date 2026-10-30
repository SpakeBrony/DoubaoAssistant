package com.spake.brony.doubaoassistant.speech.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// SDK 的网络分包不等于 Ogg 页或 Opus 包；这里只拆容器，音频解码交给系统 MediaCodec。
internal class OggOpusStream(
    private val onHeader: (ByteArray) -> Unit,
    private val onPacket: (ByteArray) -> Unit,
    private val onLastGranule: (Long) -> Unit,
    private val onEnd: () -> Unit,
) {
    private var pending = ByteArray(0)
    private val packet = ByteArrayOutputStream()
    private var headers = 0
    private var serial: Int? = null
    private var sequence = 0
    private var ended = true

    fun append(data: ByteArray) {
        require(data.size <= 1024 * 1024) { "Excessive Ogg chunk" }
        pending += data
        var offset = 0
        while (pending.size - offset >= 27) {
            check(String(pending, offset, 4, Charsets.US_ASCII) == "OggS" && pending[offset + 4] == 0.toByte()) {
                "SDK audio is not a supported Ogg stream"
            }
            val segments = pending[offset + 26].toInt() and 255
            if (pending.size - offset < 27 + segments) break
            val lengths = (0 until segments).map { pending[offset + 27 + it].toInt() and 255 }
            val pageSize = 27 + segments + lengths.sum()
            if (pending.size - offset < pageSize) break
            val header = ByteBuffer.wrap(pending, offset, 27).slice().order(ByteOrder.LITTLE_ENDIAN)
            val flags = pending[offset + 5].toInt() and 255
            val pageSerial = header.getInt(14)
            val pageSequence = header.getInt(18)
            if (flags and 2 != 0) {
                check(ended && packet.size() == 0 && pageSequence == 0) { "Invalid Ogg stream start" }
                serial = pageSerial
                sequence = 0
                headers = 0
                ended = false
            }
            check(!ended && serial == pageSerial && pageSequence == sequence++) { "Missing or interleaved Ogg page" }
            check((flags and 1 != 0) == (packet.size() != 0)) { "Invalid continued Opus packet" }
            if (flags and 4 != 0) {
                val granule = header.getLong(6)
                check(granule >= 0) { "Invalid final Opus sample position" }
                onLastGranule(granule)
            }
            var body = offset + 27 + segments
            for (length in lengths) {
                check(packet.size() + length <= 65536) { "Excessive Opus packet" }
                packet.write(pending, body, length)
                body += length
                if (length < 255) {
                    val complete = packet.toByteArray()
                    packet.reset()
                    when (headers) {
                        0 -> {
                            check(complete.size == 19 && complete.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray())) {
                                "Missing Opus identification header"
                            }
                            check(complete[8].toInt() in 0..15 && complete[9].toInt() in 1..2 && complete[18] == 0.toByte()) {
                                "Unsupported Opus channel mapping"
                            }
                            onHeader(complete)
                        }
                        1 -> check(complete.size >= 8 && complete.copyOfRange(0, 8).contentEquals("OpusTags".toByteArray())) {
                            "Missing Opus comment header"
                        }
                        else -> { check(complete.isNotEmpty()); onPacket(complete) }
                    }
                    headers++
                }
            }
            if (flags and 4 != 0) {
                check(packet.size() == 0 && headers >= 2) { "Incomplete final Opus packet" }
                onEnd()
                ended = true
            }
            offset += pageSize
        }
        pending = pending.copyOfRange(offset, pending.size)
        check(pending.size <= 65307) { "Excessive incomplete Ogg page" }
    }

    fun finish() {
        check(pending.isEmpty() && packet.size() == 0 && headers >= 2) { "Truncated Ogg audio" }
        if (!ended) { onEnd(); ended = true }
    }

    companion object {
        fun durationUs(packet: ByteArray): Long {
            require(packet.isNotEmpty())
            val toc = packet[0].toInt() and 255
            val config = toc shr 3
            val frame = when {
                config >= 16 -> 2500L shl (config and 3)
                config >= 12 -> 10000L shl (config and 1)
                config and 3 == 3 -> 60000L
                else -> 10000L shl (config and 3)
            }
            val frames = when (toc and 3) {
                0 -> 1
                1, 2 -> 2
                else -> { require(packet.size >= 2); packet[1].toInt() and 63 }
            }
            require(frames > 0 && frame * frames <= 120000) { "Invalid Opus packet duration" }
            return frame * frames
        }
    }
}
