package ru.slavapmk.ignat


import java.io.IOException
import java.net.*

class SwitchProxySelector(private val proxyUrls: List<String>) : ProxySelector() {

    override fun select(uri: URI): List<Proxy>? {
        val proxies = mutableListOf<Proxy>()

        for (proxyUrl in proxyUrls) {
            try {
                val proxyInfo = ProxyInfo.parse(proxyUrl)
                val proxy = Proxy(proxyInfo.type, InetSocketAddress(proxyInfo.host, proxyInfo.port))
                proxies.add(proxy)
            } catch (e: IllegalArgumentException) {
                println("Invalid proxy URL: $proxyUrl")
            }
        }

        return if (proxies.isNotEmpty()) proxies else null
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) {
        println("Failed to connect to proxy: $sa")
    }

    data class ProxyInfo(val type: Proxy.Type, val host: String, val port: Int) {

        companion object {
            fun parse(proxyUrl: String): ProxyInfo {
                val url = URL(proxyUrl)
                val type = when (url.protocol) {
                    "http" -> Proxy.Type.HTTP
                    "socks" -> Proxy.Type.SOCKS
                    else -> throw IllegalArgumentException("Unsupported protocol: ${url.protocol}")
                }
                val host = url.host
                val port = url.port

                return ProxyInfo(type, host, port)
            }
        }
    }
}