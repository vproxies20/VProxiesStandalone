package app.vproxies.sa.bg

import android.net.DnsResolver
import android.os.CancellationSignal
import android.system.ErrnoException
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine

/** "local" DNS for sing-box, answered by Android's resolver on the underlying (non-VPN) network. */
object LocalResolver : LocalDNSTransport {

    override fun raw(): Boolean = true

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val network = DefaultNetworkMonitor.defaultNetwork ?: error("missing default interface")
        runBlocking {
            suspendCoroutine<Unit> { continuation ->
                val signal = CancellationSignal()
                ctx.onCancel {
                    signal.cancel()
                    continuation.tryResumeWithException(CancellationException())
                }
                DnsResolver.getInstance().rawQuery(
                    network,
                    message,
                    DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(),
                    signal,
                    object : DnsResolver.Callback<ByteArray> {
                        override fun onAnswer(answer: ByteArray, rcode: Int) {
                            if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                            continuation.tryResume(Unit)
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            val cause = error.cause
                            if (cause is ErrnoException) {
                                ctx.errnoCode(cause.errno)
                                continuation.tryResume(Unit)
                            } else {
                                continuation.tryResumeWithException(error)
                            }
                        }
                    },
                )
            }
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val defaultNetwork = DefaultNetworkMonitor.defaultNetwork ?: error("missing default interface")
        runBlocking {
            suspendCoroutine<Unit> { continuation ->
                val signal = CancellationSignal()
                ctx.onCancel {
                    signal.cancel()
                    continuation.tryResumeWithException(CancellationException())
                }
                val callback = object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        if (rcode == 0) {
                            ctx.success(answer.mapNotNull { it.hostAddress }.joinToString("\n"))
                        } else {
                            ctx.errorCode(rcode)
                        }
                        continuation.tryResume(Unit)
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        val cause = error.cause
                        if (cause is ErrnoException) {
                            ctx.errnoCode(cause.errno)
                            continuation.tryResume(Unit)
                        } else {
                            continuation.tryResumeWithException(error)
                        }
                    }
                }
                val type = when {
                    network.endsWith("4") -> DnsResolver.TYPE_A
                    network.endsWith("6") -> DnsResolver.TYPE_AAAA
                    else -> null
                }
                if (type != null) {
                    DnsResolver.getInstance().query(
                        defaultNetwork, domain, type, DnsResolver.FLAG_NO_RETRY,
                        Dispatchers.IO.asExecutor(), signal, callback,
                    )
                } else {
                    DnsResolver.getInstance().query(
                        defaultNetwork, domain, DnsResolver.FLAG_NO_RETRY,
                        Dispatchers.IO.asExecutor(), signal, callback,
                    )
                }
            }
        }
    }

    private fun <T> Continuation<T>.tryResume(value: T) {
        try {
            resumeWith(Result.success(value))
        } catch (_: IllegalStateException) {
        }
    }

    private fun <T> Continuation<T>.tryResumeWithException(exception: Throwable) {
        try {
            resumeWith(Result.failure(exception))
        } catch (_: IllegalStateException) {
        }
    }
}
