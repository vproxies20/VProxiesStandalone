package app.vproxies.sa.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Base64
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Real proxy handshake check (CONNECT www.cloudflare.com:443), same as the Windows edition. */
object ProxyTester {
    private const val TARGET_HOST = "www.cloudflare.com"
    private const val TARGET_PORT = 443
    private const val TIMEOUT_MS = 10_000

    suspend fun test(proxy: LocalProxy): Long = withContext(Dispatchers.IO) {
        require(proxy.host.isNotBlank()) { "Enter a proxy host or IP address." }
        require(proxy.port in 1..65535) { "Proxy port must be between 1 and 65535." }
        val started = System.nanoTime()
        Socket().use { raw ->
            raw.connect(InetSocketAddress(proxy.host, proxy.port), TIMEOUT_MS)
            raw.soTimeout = TIMEOUT_MS
            var socket: Socket = raw
            if (proxy.protocol == ProxyProtocol.HTTPS) {
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, proxy.sni.ifBlank { proxy.host }, proxy.port, true) as SSLSocket
                tls.soTimeout = TIMEOUT_MS
                tls.startHandshake()
                socket = tls
            }
            socket.use { s ->
                val input = s.getInputStream()
                val output = s.getOutputStream()
                when (proxy.protocol) {
                    ProxyProtocol.HTTP, ProxyProtocol.HTTPS -> httpConnect(proxy, input, output)
                    ProxyProtocol.SOCKS5 -> socks5(proxy, input, output)
                    ProxyProtocol.SOCKS4 -> socks4a(proxy, input, output)
                }
            }
        }
        (System.nanoTime() - started) / 1_000_000
    }

    private fun httpConnect(proxy: LocalProxy, input: InputStream, output: OutputStream) {
        val auth = if (proxy.username.isEmpty()) "" else
            "Proxy-Authorization: Basic " + Base64.getEncoder().encodeToString("${proxy.username}:${proxy.password}".toByteArray()) + "\r\n"
        output.write("CONNECT $TARGET_HOST:$TARGET_PORT HTTP/1.1\r\nHost: $TARGET_HOST:$TARGET_PORT\r\n${auth}Connection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        output.flush()
        val buffer = ByteArray(256)
        val count = input.read(buffer)
        if (count <= 0) throw EOFException("Proxy closed the connection.")
        val status = String(buffer, 0, count, Charsets.US_ASCII)
        if (!status.contains(" 200 ")) throw IOException("HTTP CONNECT was rejected: " + status.lineSequence().first().trim())
    }

    private fun socks5(proxy: LocalProxy, input: InputStream, output: OutputStream) {
        output.write(if (proxy.username.isEmpty()) byteArrayOf(5, 1, 0) else byteArrayOf(5, 1, 2))
        output.flush()
        val hello = readExact(input, 2)
        when (hello[1].toInt()) {
            2 -> {
                val user = proxy.username.toByteArray()
                val pass = proxy.password.toByteArray()
                require(user.size <= 255 && pass.size <= 255) { "SOCKS5 credentials are too long." }
                output.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
                output.flush()
                if (readExact(input, 2)[1].toInt() != 0) throw IOException("SOCKS5 authentication failed.")
            }
            0 -> Unit
            else -> throw IOException("SOCKS5 authentication method was rejected.")
        }
        val host = TARGET_HOST.toByteArray(Charsets.US_ASCII)
        output.write(byteArrayOf(5, 1, 0, 3, host.size.toByte()) + host + byteArrayOf(1, 187.toByte()))
        output.flush()
        val response = readExact(input, 4)
        if (response[1].toInt() != 0) throw IOException("SOCKS5 connect failed (${response[1].toInt() and 0xff}).")
    }

    private fun socks4a(proxy: LocalProxy, input: InputStream, output: OutputStream) {
        val host = TARGET_HOST.toByteArray(Charsets.US_ASCII)
        val user = proxy.username.toByteArray()
        output.write(byteArrayOf(4, 1, 1, 187.toByte(), 0, 0, 0, 1) + user + byteArrayOf(0) + host + byteArrayOf(0))
        output.flush()
        val response = readExact(input, 8)
        if ((response[1].toInt() and 0xff) != 90) throw IOException("SOCKS4A connect failed (${response[1].toInt() and 0xff}).")
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) throw EOFException("Proxy closed the connection.")
            offset += read
        }
        return buffer
    }
}
