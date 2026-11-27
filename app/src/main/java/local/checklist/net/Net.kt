package local.checklist.net

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * 枚举**全部**局域网 IPv4，而不是取第一个。
 * 多网卡、开 VPN、开热点时，"第一个"经常是错的，展示出去就是一个永远连不上的地址。
 * 排序只做偏好（wlan 优先），最终让用户自己挑。
 */
object Net {

    fun lanIps(): List<String> {
        val found = ArrayList<Pair<Int, String>>()
        try {
            val nifs = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in nifs) {
                if (isExcluded(nif)) continue
                val name = nif.name ?: ""
                val rank = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("ap") || name.startsWith("swlan") -> 1
                    name == "eth0" || name.startsWith("eth") -> 2
                    else -> 3
                }
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val ip = addr.hostAddress ?: continue
                    found.add(rank to ip)
                }
            }
        } catch (_: Exception) {
            // 权限或接口异常时退化为空列表，调用方会用 127.0.0.1
        }
        return found.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun isExcluded(nif: NetworkInterface): Boolean {
        return try {
            !nif.isUp || nif.isLoopback || nif.isVirtual
        } catch (_: Exception) {
            true
        }
    }

    fun primary(): String = lanIps().firstOrNull() ?: "127.0.0.1"
}
