package com.rocketglasses.soberyobratno

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.util.UUID

/** A normal WPA2 network hosted by the glasses, bootstrapped over encrypted BLE. */
@SuppressLint("MissingPermission")
class DirectSync(private val context: Context, private val changed: () -> Unit) {
    companion object {
        val SERVICE: UUID = UUID.fromString("712e3100-92e5-4c52-8d0f-332baeef6001")
        val INFO: UUID = UUID.fromString("712e3101-92e5-4c52-8d0f-332baeef6001")
    }
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("direct-sync", Context.MODE_PRIVATE)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var gatt: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var ownedGroup = false
    private var generation = 0
    private val snapshots = mutableMapOf<String, ByteArray>()
    @Volatile var active = false; private set
    /** スマホが Bluetooth でつながっている間は true。 */
    @Volatile var bleConnected = false; private set
    @Volatile var token: String? = null; private set
    var state = "オフ"; private set
    var ssid = prefs.getString("ssid", null) ?: "DIRECT-RS-ReStep-${UUID.randomUUID().toString().take(4)}"
        private set
    // 手で入力しやすいよう、数字8桁（Wi-Fi の最短の長さ）。以前の長い英数字のパスワードは作り直す。
    private val password = prefs.getString("password", null)?.takeIf { it.matches(Regex("[0-9]{8}")) }
        ?: (10000000 + java.security.SecureRandom().nextInt(90000000)).toString()
    private var host = ""
    /** 手動で Wi-Fi 設定からつなぐときに画面へ表示する値。 */
    val wifiPassword: String get() = password
    val groupHost: String get() = host

    init { prefs.edit().putString("ssid", ssid).putString("password", password).apply() }

    private fun update(value: String) { state = value; Log.i("ReStepSync", value); changed() }

    fun start() {
        if (active) return
        active = true
        val run = ++generation
        token = UUID.randomUUID().toString()
        host = ""
        update("Wi-Fiを起動中")
        try {
            channel = channel ?: manager.initialize(context, Looper.getMainLooper()) {
                channel = null
                if (active) fail("Wi-Fi接続が切れました。再試行してください")
            }
            if (!wifi.isWifiEnabled) { fail("Wi-Fiをオンにして再試行してください"); return }
            manager.requestGroupInfo(channel) { existing ->
                if (run != generation || !active) return@requestGroupInfo
                if (android.os.Build.VERSION.SDK_INT < 29) { fail("直接同期にはAndroid 10以上が必要です"); return@requestGroupInfo }
                if (existing != null) {
                    // The OS may retain our group after an APK update or process death.
                    if (existing.isGroupOwner && existing.networkName == ssid && existing.passphrase == password) {
                        ownedGroup = true
                        waitForAddress(run, 0)
                    } else if (existing.isGroupOwner && existing.networkName == ssid) {
                        // パスワードを変えたので、古いグループを閉じて作り直す。
                        manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { handler.postDelayed({ createOwnGroup(run) }, 500) }
                            override fun onFailure(reason: Int) { if (run == generation && active) fail("Wi-Fiの作り直しに失敗 ($reason)") }
                        })
                    } else fail("Hi Rokidの転送を閉じて再試行してください")
                    return@requestGroupInfo
                }
                createOwnGroup(run)
            }
        } catch (e: Exception) { fail("同期: ${e.message?.take(65) ?: "利用できません"}") }
    }

    private fun createOwnGroup(run: Int) {
        if (run != generation || !active) return
        val config = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(password)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ).build()
        manager.createGroup(channel!!, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (run != generation || !active) {
                    manager.removeGroup(channel, null)
                    return
                }
                ownedGroup = true
                waitForAddress(run, 0)
            }
            override fun onFailure(reason: Int) {
                if (run == generation && active) fail("Wi-Fi起動に失敗 ($reason)")
            }
        })
    }

    private fun waitForAddress(run: Int, attempt: Int) {
        if (!active || run != generation) return
        manager.requestConnectionInfo(channel) { info ->
            if (!active || run != generation) return@requestConnectionInfo
            if (info.groupFormed && info.isGroupOwner && info.groupOwnerAddress != null) {
                host = info.groupOwnerAddress.hostAddress ?: ""
                // Wi-Fi が使えれば、Bluetooth が失敗しても手動接続（SSID・パスワード・IP）は使えるように残す。
                update("手動接続できます（自動接続を準備中）")
                startBluetooth()
            } else if (attempt < 20) handler.postDelayed({ waitForAddress(run, attempt + 1) }, 500)
            else fail("Wi-Fiタイムアウト。再試行してください")
        }
    }

    private fun startBluetooth() {
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            if (adapter == null || !adapter.isEnabled) { bluetoothUnavailable("Bluetoothがオフ。手動接続のみ"); return }
            advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser == null) { bluetoothUnavailable("Bluetooth不可。手動接続のみ"); return }
            val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(INFO,
                BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED))
            gatt = context.getSystemService(BluetoothManager::class.java).openGattServer(context,
                object : BluetoothGattServerCallback() {
                    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                        handler.post {
                            if (!active || gatt == null) return@post
                            if (status != BluetoothGatt.GATT_SUCCESS) { bluetoothUnavailable("Bluetooth起動失敗。手動接続のみ"); return@post }
                            advertiser?.startAdvertising(AdvertiseSettings.Builder()
                                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                                .setConnectable(true).setTimeout(0).build(),
                                AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).build(), advertising)
                        }
                    }
                    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) { bleConnected = true; handler.post { changed() } }
                        if (newState == BluetoothProfile.STATE_DISCONNECTED) { bleConnected = false; synchronized(snapshots) { snapshots.remove(device.address) }; handler.post { changed() } }
                    }
                    override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int,
                        offset: Int, characteristic: BluetoothGattCharacteristic) {
                        if (!active || characteristic.uuid != INFO || device.bondState != BluetoothDevice.BOND_BONDED) {
                            gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, offset, null)
                            return
                        }
                        val bytes = synchronized(snapshots) {
                            if (offset == 0) snapshots[device.address] = JSONObject()
                                .put("version", 1).put("ssid", ssid).put("password", password)
                                .put("host", host).put("port", 8765).put("token", token).toString().toByteArray()
                            snapshots[device.address]
                        }
                        if (bytes == null || offset > bytes.size) {
                            gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                        } else gatt?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes.copyOfRange(offset, bytes.size))
                    }
                })
            if (gatt?.addService(service) != true) bluetoothUnavailable("Bluetooth不可。手動接続のみ")
        } catch (e: Exception) { bluetoothUnavailable("Bluetooth異常。手動接続のみ") }
    }

    private val advertising = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { handler.post { if (active) update("スマホからの接続待ち") } }
        override fun onStartFailure(errorCode: Int) { handler.post { if (active) bluetoothUnavailable("Bluetooth広告失敗($errorCode)。手動接続のみ") } }
    }

    /** Bluetooth が使えなくても Wi-Fi のグループは閉じず、手動接続だけは使えるようにする。 */
    private fun bluetoothUnavailable(message: String) {
        try { advertiser?.stopAdvertising(advertising) } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        update(message)
    }

    private fun fail(message: String) {
        stop()
        update(message)
    }

    fun stop() {
        active = false
        generation++
        token = null
        try { advertiser?.stopAdvertising(advertising) } catch (_: Exception) {}
        advertiser = null
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        bleConnected = false
        synchronized(snapshots) { snapshots.clear() }
        if (ownedGroup) {
            try { manager.removeGroup(channel, null) } catch (_: Exception) {}
            ownedGroup = false
        }
        update("オフ")
    }
}
