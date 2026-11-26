package com.fqapp.fqapp

import android.content.Context
import android.net.ConnectivityManager
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

// 报告当前默认网络的 WiFi HTTP 代理，供 Dart 的下载通道（漫剧/听书缓存）使用。
// Dart HttpClient 不会像 Java 栈那样自动读取 WiFi 代理：需要代理出网的网络
// 上，取源（走 Rust/FFI）与播放（走 ExoPlayer）都能成功，唯独缓存下载直连
// 失败。见 lib/services/download_http_client.dart。
//
// ConnectivityManager.getDefaultProxy（API 23+）是权威来源，反映当前默认
// 网络的 ProxyInfo；部分 ROM/旧 WebView 属性栈在它为空时仍通过
// System.getProperty 暴露 WiFi 代理，作为兜底再读一次。
class NetworkProxyPlugin : FlutterPlugin {
    private var context: Context? = null
    private var methods: MethodChannel? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methods = MethodChannel(binding.binaryMessenger, "fqapp/network_proxy").also {
            it.setMethodCallHandler { call, result ->
                when (call.method) {
                    "getSystemProxy" -> result.success(currentProxy())
                    else -> result.notImplemented()
                }
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methods?.setMethodCallHandler(null)
        methods = null
        context = null
    }

    private fun currentProxy(): Map<String, Any>? {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // defaultProxy 描述当前默认网络的代理；飞行模式/无网时为 null，此时
        // 下载本来就会失败，报告 null（直连）是正确行为。
        val info = runCatching { cm?.defaultProxy }.getOrNull()
        val host = info?.host?.takeIf { it.isNotBlank() }
        val port = info?.port?.takeIf { it in 1..65535 }
        if (host != null && port != null) {
            return mapOf("host" to host, "port" to port)
        }
        // 兜底：ConnectivityManager 未暴露但系统属性有值（少数 ROM 的行为）。
        val propHost = System.getProperty("http.proxyHost")?.takeIf { it.isNotBlank() }
        val propPort = System.getProperty("http.proxyPort")?.toIntOrNull()?.takeIf { it in 1..65535 }
        if (propHost != null && propPort != null) {
            return mapOf("host" to propHost, "port" to propPort)
        }
        return null
    }
}
