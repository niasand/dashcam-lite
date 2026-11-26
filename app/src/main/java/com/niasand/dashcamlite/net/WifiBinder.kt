package com.niasand.dashcamlite.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

/**
 * 记录仪热点没有互联网。用 requestNetwork 去掉 INTERNET 能力，
 * 再 bindProcessToNetwork，避免系统把流量改走蜂窝。
 * API 29+ 可用 WifiNetworkSpecifier 让系统加入指定热点。
 * 原版对应 com.tima.carnet.util.WiFiUtils.lockWiFiNetwork / bindNetwork。
 */
class WifiBinder(context: Context) {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun bindExistingWifi(onReady: (String?) -> Unit, onLost: (String) -> Unit) {
        release("rebind")
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val created = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                DashcamLink.set(network)
                connectivity.bindProcessToNetwork(network)
                val host = gateway(network)
                main.post { onReady(host) }
            }

            override fun onUnavailable() {
                main.post { onLost("没有可用的 Wi-Fi") }
            }

            override fun onLost(network: Network) {
                if (DashcamLink.current() == network) DashcamLink.set(null)
                connectivity.bindProcessToNetwork(null)
                main.post { onLost("记录仪 Wi-Fi 已断开") }
            }
        }
        callback = created
        connectivity.requestNetwork(request, created)
    }

    fun requestSsid(ssid: String, password: String, onReady: (String?) -> Unit, onLost: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) {
            bindExistingWifi(onReady, onLost)
            return
        }
        release("specifier")
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            if (password.isNotEmpty()) setWpa2Passphrase(password)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val created = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                DashcamLink.set(network)
                connectivity.bindProcessToNetwork(network)
                val host = gateway(network)
                main.post { onReady(host) }
            }

            override fun onUnavailable() {
                main.post { onLost("系统未加入热点 $ssid") }
            }

            override fun onLost(network: Network) {
                if (DashcamLink.current() == network) DashcamLink.set(null)
                connectivity.bindProcessToNetwork(null)
                main.post { onLost("热点 $ssid 已断开") }
            }
        }
        callback = created
        connectivity.requestNetwork(request, created)
    }

    fun currentWifiHost(): String? {
        val network = connectivity.allNetworks.firstOrNull { net ->
            val caps = connectivity.getNetworkCapabilities(net) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } ?: connectivity.activeNetwork
        return network?.let { gateway(it) }
    }

    /** 网关，以及这块网卡自己的 IPv4，供 CameraHost 丢掉“把手机自己的地址当成记录仪”。 */
    fun boundEndpoint(): Pair<String?, List<String>> {
        val network = DashcamLink.current() ?: return null to emptyList()
        val link = connectivity.getLinkProperties(network) ?: return null to emptyList()
        val gateway = link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
        val own = link.linkAddresses.mapNotNull { (it.address as? Inet4Address)?.hostAddress }
        return gateway to own
    }

    fun release(reason: String) {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        DashcamLink.set(null)
        connectivity.bindProcessToNetwork(null)
    }

    private fun gateway(network: Network): String? {
        val link = connectivity.getLinkProperties(network) ?: return null
        val route = link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
        return (route?.gateway as? Inet4Address)?.hostAddress
    }
}
