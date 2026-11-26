package com.niasand.dashcamlite.net

import android.net.Network
import com.niasand.dashcamlite.protocol.HttpTransport
import com.niasand.dashcamlite.protocol.OkHttpTransport
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 控制指令、缩略图、下载和 MJPEG 都走这块网卡的 SocketFactory。
 * 只靠 bindProcessToNetwork 时，OkHttp 仍可能沿用默认网络的连接。
 */
object DashcamLink {
    private val bound = AtomicReference<Network?>(null)

    fun set(network: Network?) {
        bound.set(network)
    }

    fun current(): Network? = bound.get()

    fun transport(): HttpTransport = OkHttpTransport(client())

    fun client(): OkHttpClient {
        val network = bound.get()
        val seconds = TimeUnit.SECONDS
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, seconds)
            .readTimeout(120, seconds)
            .writeTimeout(60, seconds)
            .retryOnConnectionFailure(true)
        if (network != null) {
            builder.socketFactory(network.socketFactory)
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    network.getAllByName(hostname).toList()
            })
        }
        return builder.build()
    }
}
