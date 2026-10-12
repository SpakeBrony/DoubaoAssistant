package com.spake.brony.doubaoassistant.data.network

import android.os.Looper
import android.os.NetworkOnMainThreadException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Address
import okhttp3.OkHttpClient
import okhttp3.Route
import okhttp3.internal.connection.RealConnection
import okhttp3.internal.connection.RealConnectionPool
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DoubaoResponsesClientTest {
    @Test fun closeFromMainEvictsConnectionsOffMainOnceWithoutBlockingServiceDestruction() {
        val responses = DoubaoResponsesClient(RuntimeEnvironment.getApplication())
        val http = responses.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(responses) as OkHttpClient
        val pool = http.connectionPool
        val delegate = pool.javaClass.getDeclaredField("delegate").apply { isAccessible = true }.get(pool) as RealConnectionPool
        val caller = Thread.currentThread()
        assertSame(Looper.getMainLooper().thread, caller)
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeThread = AtomicReference<Thread>()
        val closeCount = AtomicInteger()
        // 模拟 SSL 关闭需要网络 I/O：主线程调用就抛出与实机相同的异常，不建立真实连接。
        val socket = object : Socket() {
            override fun close() {
                closeCount.incrementAndGet()
                closeThread.set(Thread.currentThread())
                if (Thread.currentThread() == caller) throw NetworkOnMainThreadException()
                closing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val address = Address("localhost", 443, http.dns, http.socketFactory, http.sslSocketFactory,
            http.hostnameVerifier, http.certificatePinner, http.proxyAuthenticator, Proxy.NO_PROXY,
            http.protocols, http.connectionSpecs, http.proxySelector)
        val connection = RealConnection.newTestConnection(delegate,
            Route(address, Proxy.NO_PROXY, InetSocketAddress.createUnresolved("localhost", 443)), socket, System.nanoTime())
        synchronized(connection) { delegate.put(connection) }
        try {
            assertEquals(1, pool.idleConnectionCount())
            responses.close()
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertNotSame(caller, closeThread.get())
            // 清理尚未完成时再次销毁，也不能重复排队或抛 RejectedExecutionException。
            responses.close()
            assertEquals(1L, release.count)
        } finally {
            release.countDown()
            http.dispatcher.executorService.shutdown()
            assertTrue(http.dispatcher.executorService.awaitTermination(5, TimeUnit.SECONDS))
        }
        assertEquals(1, closeCount.get())
        assertEquals(0, pool.connectionCount())
        responses.close()
    }
}
