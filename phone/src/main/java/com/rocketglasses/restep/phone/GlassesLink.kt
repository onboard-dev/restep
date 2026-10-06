package com.rocketglasses.restep.phone

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.net.*
import android.net.wifi.WifiNetworkSpecifier
import android.os.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@SuppressLint("MissingPermission")
class GlassesLink(private val context: Context, private val status: (String)->Unit,
                  private val ready: (Network, JSONObject)->Unit) {
    private val service = UUID.fromString("712e3100-92e5-4c52-8d0f-332baeef6001")
    private val info = UUID.fromString("712e3101-92e5-4c52-8d0f-332baeef6001")
    private val handler = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var gatt: BluetoothGatt? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var active = false
    private var found = false
    private val timeout = Runnable { if(active) { stop(); status("接続がタイムアウトしました。グラスを同期画面にして、もう一度試すか「手動で接続」を使ってください。") } }
    private val hint = Runnable { if(active && !found) status("グラスがまだ見つかりません。グラスが同期画面か確認するか、「手動で接続」を使ってください。") }
    private val scan = object: ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) { handler.post {
            if(!active || found) return@post
            found = true; adapter.bluetoothLeScanner.stopScan(this)
            status("グラスが見つかりました。Android の確認が出たらペアリングを許可してください…")
            gatt = result.device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE)
        } }
        override fun onScanFailed(errorCode: Int) { handler.post { stop(); status("Bluetooth の検索に失敗しました（$errorCode）。もう一度お試しください。") } }
    }
    private val callbacks = object: BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, code: Int, state: Int) {
            if(state == BluetoothProfile.STATE_CONNECTED && code == 0) g.discoverServices()
            else if(state == BluetoothProfile.STATE_DISCONNECTED) handler.post { if(active && networkCallback == null) { stop(); status("グラスとの接続が切れました。もう一度お試しください。") } }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, code: Int) {
            val ch = g.getService(service)?.getCharacteristic(info)
            if(code == 0 && ch != null) g.readCharacteristic(ch)
            else handler.post { stop(); status("グラスに ReStep の同期サービスが見つかりません。") }
        }
        @Deprecated("Legacy Android callback")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, code: Int) {
            if(Build.VERSION.SDK_INT < 33) received(g, ch.value ?: byteArrayOf(), code)
        }
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, code: Int) { received(g, value, code) }
    }
    private fun received(g: BluetoothGatt, bytes: ByteArray, code: Int) { handler.post {
        if(!active || g !== gatt) return@post
        if(code != BluetoothGatt.GATT_SUCCESS) { stop(); status("ペアリングが完了しませんでした。Bluetooth 設定でペアリングしてから再試行するか、「手動で接続」を使ってください。"); return@post }
        try {
            val value = JSONObject(bytes.toString(Charsets.UTF_8))
            validate(value)
            status("ReStep の Wi-Fi 接続を許可してください…")
            val spec = WifiNetworkSpecifier.Builder().setSsid(value.getString("ssid")).setWpa2Passphrase(value.getString("password")).build()
            val callback = object: ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { handler.post { if(active) { handler.removeCallbacks(timeout); ready(network, value) } } }
                override fun onUnavailable() { handler.post { stop(); status("Wi-Fi 接続が許可されなかったか、つながりませんでした。もう一度お試しください。") } }
                override fun onLost(network: Network) { handler.post { if(active) { stop(); status("グラスの Wi-Fi が切れました。") } } }
            }
            networkCallback = callback
            cm.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(spec).build(), callback)
        } catch(e: Exception) { stop(); status(e.message ?: "接続できません") }
    } }
    /** 手動で Wi-Fi につないだとき、同じネットワーク（IPの上位3桁が同じ Wi-Fi）を探す。見つからなければ null。 */
    fun wifiNetworkFor(host: String): Network? {
        val prefix = host.split('.').take(3)
        return cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                cm.getLinkProperties(network)?.linkAddresses?.any {
                    (it.address as? java.net.Inet4Address)?.hostAddress?.split('.')?.take(3) == prefix
                } == true
        }
    }

    fun start() {
        stop()
        check(adapter != null && adapter.isEnabled) { "先に Bluetooth と Wi-Fi をオンにしてください。" }
        active = true; found = false
        adapter.bluetoothLeScanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(service)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
        handler.postDelayed(timeout, 90000)
        handler.postDelayed(hint, 15000)
        status("グラスを探しています…（グラスで後ろへスワイプして同期画面を開いてください）")
    }
    fun stop() {
        active = false; handler.removeCallbacks(timeout); handler.removeCallbacks(hint)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scan) }
        gatt?.close(); gatt = null
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }; networkCallback = null
    }
    companion object {
        fun validate(value: JSONObject) {
            require(value.getInt("version") == 1 && value.getString("ssid").startsWith("DIRECT-RS-ReStep-")) { "ReStep の接続情報が正しくありません" }
            val parts = value.getString("host").split('.').map { it.toIntOrNull() ?: -1 }
            require(parts.size == 4 && parts.all { it in 0..255 } && (parts[0] == 10 || parts[0] == 192 && parts[1] == 168 || parts[0] == 172 && parts[1] in 16..31)) { "接続先のアドレスが正しくありません" }
            require(value.getInt("port") == 8765)
            UUID.fromString(value.getString("token"))
        }
        fun request(network: Network?, host: String, token: String, path: String, method: String = "GET"): ByteArray {
            val url = URL("http://$host:8765$path")
            val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            try {
                connection.connectTimeout = 10000; connection.readTimeout = 20000
                connection.instanceFollowRedirects = false; connection.requestMethod = method
                connection.setRequestProperty("X-Pair-Code", token)
                val code = connection.responseCode
                check(code in 200..299) { when(code) { 403 -> "ペアコードが違うか期限切れです。グラスの表示を確認してください。"; 409 -> "削除の前に、グラスで現在の写真を保存してください。"; else -> "グラスがエラーを返しました（HTTP $code）" } }
                return connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while(true) { val n = input.read(buffer); if(n < 0) break; check(out.size() + n <= 32 * 1024 * 1024) { "受信データが大きすぎます" }; out.write(buffer,0,n) }
                    out.toByteArray()
                }
            } finally { connection.disconnect() }
        }
    }
}
