package app.orbit.vpn.service

import android.net.DnsResolver
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** System DNS resolver bound to the physical network (used by the "local" DNS server). */
class LocalResolver(private val monitor: DefaultNetworkMonitor) : LocalDNSTransport {
    private val executor = Executors.newCachedThreadPool()

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val network = monitor.defaultNetwork ?: error("missing default network")
        val done = CountDownLatch(1)
        var failure: Exception? = null
        val signal = CancellationSignal()
        ctx.onCancel { signal.cancel(); done.countDown() }
        DnsResolver.getInstance().rawQuery(
            network, message, DnsResolver.FLAG_NO_RETRY, executor, signal,
            object : DnsResolver.Callback<ByteArray> {
                override fun onAnswer(answer: ByteArray, rcode: Int) {
                    if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                    done.countDown()
                }

                override fun onError(error: DnsResolver.DnsException) {
                    val cause = error.cause
                    if (cause is ErrnoException) ctx.errnoCode(cause.errno) else failure = error
                    done.countDown()
                }
            },
        )
        done.await()
        failure?.let { throw it }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val defaultNetwork = monitor.defaultNetwork ?: error("missing default network")
        val addresses: Array<InetAddress> = try {
            defaultNetwork.getAllByName(domain)
        } catch (_: UnknownHostException) {
            ctx.errorCode(RCODE_NXDOMAIN)
            return
        }
        val filtered = addresses.filter {
            when {
                network.endsWith("4") -> it.address.size == 4
                network.endsWith("6") -> it.address.size == 16
                else -> true
            }
        }
        ctx.success(filtered.mapNotNull { it.hostAddress }.joinToString("\n"))
    }

    private companion object {
        const val RCODE_NXDOMAIN = 3
    }
}
