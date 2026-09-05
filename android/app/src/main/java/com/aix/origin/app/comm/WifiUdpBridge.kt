package com.aix.origin.app.comm

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.aix.origin.app.comm.GatewayParser.Message
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * WiFi UDP 桥 —— 与网关 ESP32-S3（STA 模式接入同一路由器）在同一局域网内通信。
 * - 下行：监听 UDP_PORT，解析网关广播的 JSON 灾情帧。
 * - 上行：向网段广播地址发送 GPS/SOS JSON（网关端监听）。
 *
 * 需要 CHANGE_WIFI_MULTICAST_STATE 权限才能收到广播/组播包。
 */
class WifiUdpBridge(private val context: Context) {

    sealed class Event {
        data class Status(val text: String) : Event()
        data class Frame(val msg: Message) : Event()
    }

    var onEvent: (Event) -> Unit = {}

    @Volatile
    private var running = false
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val buffer = ByteArray(2048)

    fun start(port: Int = UDP_PORT) {
        if (running) return
        running = true
        acquireMulticastLock()
        thread(name = "wifi-udp-listen", isDaemon = true) {
            try {
                socket = DatagramSocket(port).also { it.soTimeout = 2000 }
                onEventSafe(Event.Status("WiFi 网桥已监听 :$port"))
                while (running) {
                    val pkt = DatagramPacket(buffer, buffer.size)
                    try {
                        socket?.receive(pkt)
                        val text = String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8).trim()
                        if (text.isNotEmpty()) onLine(text)
                    } catch (e: SocketException) {
                        // 超时/关闭
                    }
                }
            } catch (e: Exception) {
                onEventSafe(Event.Status("WiFi 网桥异常: ${e.message}"))
            } finally {
                releaseMulticastLock()
                onEventSafe(Event.Status("WiFi 网桥已停止"))
            }
        }
    }

    private fun onLine(line: String) {
        val msg = GatewayParser.parse(line)
        if (msg is Message.Unsupported) {
            Log.d(TAG, "未识别 UDP 帧: $line")
        }
        onEventSafe(Event.Frame(msg))
    }

    /** 上行：向网关广播（若已知网关 IP 可用 [sendTo]） */
    fun sendBroadcast(data: String) {
        thread(name = "wifi-udp-send", isDaemon = true) {
            try {
                val bytes = data.toByteArray(Charsets.UTF_8)
                val sock = DatagramSocket()
                sock.broadcast = true
                val target = InetAddress.getByName(BROADCAST_ADDR)
                sock.send(DatagramPacket(bytes, bytes.size, target, UDP_PORT))
                sock.close()
            } catch (e: Exception) {
                Log.w(TAG, "UDP 广播失败: ${e.message}")
            }
        }
    }

    fun sendTo(ip: String, data: String) {
        thread(name = "wifi-udp-send", isDaemon = true) {
            try {
                val bytes = data.toByteArray(Charsets.UTF_8)
                val sock = DatagramSocket()
                sock.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(ip), UDP_PORT))
                sock.close()
            } catch (e: Exception) {
                Log.w(TAG, "UDP 发送失败: ${e.message}")
            }
        }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("aix-udp-bridge").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "无法获取 MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    private fun onEventSafe(ev: Event) {
        try {
            onEvent(ev)
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "WifiUdpBridge"
        const val UDP_PORT = 50123
        const val BROADCAST_ADDR = "255.255.255.255"
    }
}
