package br.com.pingreal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress

/** Resumo da rede atual do celular. Campos sem valor ficam como [NONE]. */
data class NetInfo(
    val gateway: String,
    val dns: String,
    val ip: String,
    val type: String
) {
    companion object {
        const val NONE = "—"
        val EMPTY = NetInfo(NONE, NONE, NONE, NONE)
        val UNAVAILABLE = NetInfo("N/D", "N/D", "N/D", "Indisponível")
    }
}

private val HOST_PATTERN = Regex("[A-Za-z0-9._:%-]+")

/** Devolve o host se ele for um IP/nome aceitável para o `ping` (sem opções disfarçadas de host). */
fun parseHost(candidate: String): String? =
    candidate.takeIf { it.isNotBlank() && !it.startsWith("-") && it.matches(HOST_PATTERN) }

/** Endereço de rede preenchido e utilizável (não "—" nem "N/D"). */
fun isUsableAddress(value: String): Boolean =
    value != NetInfo.NONE && value != "N/D" && parseHost(value) != null

private fun cleanAddress(address: InetAddress?): String? =
    address?.hostAddress?.substringBefore('%')

/** Lê IP, gateway, DNS e tipo da rede ativa. Com VPN, usa a rede física por baixo dela. */
@Suppress("DEPRECATION")
fun readNetworkInfo(context: Context): NetInfo {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val active = cm.activeNetwork ?: return NetInfo(NetInfo.NONE, NetInfo.NONE, NetInfo.NONE, "Sem conexão")
    val activeCaps = cm.getNetworkCapabilities(active)
    val viaVpn = activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    val base = if (viaVpn) {
        cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n)
            c != null &&
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: active
    } else {
        active
    }

    val caps = cm.getNetworkCapabilities(base)
    val props = cm.getLinkProperties(base)
    val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

    val baseType = when {
        isWifi -> "Wi-Fi"
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Móvel"
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> "Bluetooth"
        else -> null
    }
    val type = when {
        baseType != null && viaVpn -> "$baseType + VPN"
        baseType != null -> baseType
        viaVpn -> "VPN"
        else -> NetInfo.NONE
    }

    val dhcp = if (isWifi) {
        try {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).dhcpInfo
        } catch (_: Exception) {
            null
        }
    } else {
        null
    }
    fun dhcpIp(v: Int?): String? =
        if (v == null || v == 0) null
        else "${v and 0xff}.${(v shr 8) and 0xff}.${(v shr 16) and 0xff}.${(v shr 24) and 0xff}"

    val addresses = props?.linkAddresses?.map { it.address }.orEmpty()
    val ip = cleanAddress(addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress })
        ?: cleanAddress(addresses.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress })
        ?: NetInfo.NONE

    val gateways = props?.routes?.filter { it.isDefaultRoute }?.mapNotNull { it.gateway }.orEmpty()
    val gateway = cleanAddress(gateways.firstOrNull { it is Inet4Address && !it.isAnyLocalAddress })
        ?: cleanAddress(gateways.firstOrNull { !it.isAnyLocalAddress })
        ?: dhcpIp(dhcp?.gateway)
        ?: NetInfo.NONE

    val dnsList = props?.dnsServers.orEmpty().filter { !it.isAnyLocalAddress }
    val dns = cleanAddress(dnsList.firstOrNull { it is Inet4Address })
        ?: cleanAddress(dnsList.firstOrNull())
        ?: dhcpIp(dhcp?.dns1)
        ?: NetInfo.NONE

    return NetInfo(gateway, dns, ip, type)
}
