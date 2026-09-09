package com.szurgot.prenotasniper

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Minimal SNTP client. Returns the offset (serverMillis - deviceMillis) so we can
 * fire the snipe at the true release instant regardless of device clock drift.
 */
object NtpClient {
    private const val NTP_PORT = 123
    private const val NTP_PACKET_SIZE = 48
    private const val NTP_MODE_CLIENT = 3
    private const val NTP_VERSION = 3
    // Seconds between 1900 (NTP epoch) and 1970 (Unix epoch).
    private const val OFFSET_1900_TO_1970 = 2208988800L

    /** @return offset in ms to add to System.currentTimeMillis(), or null on failure. */
    fun fetchOffsetMs(host: String = "time.google.com", timeoutMs: Int = 3000): Long? {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val address = InetAddress.getByName(host)
                val buf = ByteArray(NTP_PACKET_SIZE)
                buf[0] = (NTP_MODE_CLIENT or (NTP_VERSION shl 3)).toByte()

                val requestTime = System.currentTimeMillis()
                val request = DatagramPacket(buf, buf.size, address, NTP_PORT)
                socket.send(request)

                val response = DatagramPacket(buf, buf.size)
                socket.receive(response)
                val responseTime = System.currentTimeMillis()

                val seconds = readUInt32(buf, 40)
                val fraction = readUInt32(buf, 44)
                val serverMillis = (seconds - OFFSET_1900_TO_1970) * 1000L +
                        (fraction * 1000L / 0x100000000L)

                // Correct for round-trip: assume symmetric latency.
                val roundTrip = responseTime - requestTime
                serverMillis + roundTrip / 2 - responseTime
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readUInt32(buf: ByteArray, i: Int): Long {
        val b0 = buf[i].toLong() and 0xFF
        val b1 = buf[i + 1].toLong() and 0xFF
        val b2 = buf[i + 2].toLong() and 0xFF
        val b3 = buf[i + 3].toLong() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }
}
