package app.plait.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap

/**
 * Listens for Bluetooth Low Energy advertisements and runs classic discovery at the same time. Results are merged per
 * address (an advertisement and its scan response arrive separately), fingerprinted, and published a couple of times a
 * second so a busy room does not make the screen redraw for every packet.
 */
class BtScanner(context: Context) {
    private val ctx = context.applicationContext
    private val adapter: BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val found = ConcurrentHashMap<String, BtDevice>()
    @Volatile private var numbers: BtNumbers? = null
    @Volatile private var oui: Oui? = null
    @Volatile private var dirty = false
    @Volatile private var hints: Map<String, String> = emptyMap()
    private var receiverOn = false

    var devices by mutableStateOf(listOf<BtDevice>())
        private set
    var scanning by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun vendor(address: String): String? = oui?.vendor(address)
    fun numbers(): BtNumbers? = numbers

    val available get() = adapter != null
    val enabled get() = adapter?.isEnabled == true

    init {
        Thread {
            numbers = BtNumbers.load(ctx)
            oui = Oui.load(ctx)
            dirty = true
        }.start()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val a = adapter ?: return
        if (scanning) return
        if (!a.isEnabled) {
            error = "Bluetooth is off"
            return
        }
        error = null
        scanning = true
        runCatching { a.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setLegacy(false).build(), callback) }
            .onFailure { error = "Could not start the scan: ${it.message}" }
        if (!receiverOn) {
            ctx.registerReceiver(classic, IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            })
            receiverOn = true
        }
        runCatching { a.startDiscovery() }
        // paired devices are listed even when they are not advertising right now
        runCatching { a.bondedDevices.forEach { d -> merge(d.address, heard = false) { it.copy(name = it.name ?: d.name, paired = true, classic = d.type != BluetoothDevice.DEVICE_TYPE_LE, le = d.type != BluetoothDevice.DEVICE_TYPE_CLASSIC, deviceClass = d.bluetoothClass?.deviceClass) } } }
        handler.post(publish)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        val a = adapter
        scanning = false
        runCatching { a?.bluetoothLeScanner?.stopScan(callback) }
        runCatching { a?.cancelDiscovery() }
        if (receiverOn) {
            runCatching { ctx.unregisterReceiver(classic) }
            receiverOn = false
        }
        handler.removeCallbacks(publish)
        publishNow()
    }

    /** Names from `dumpsys bluetooth_manager`, keyed by the last two address bytes (Android masks the rest). */
    fun applyHints(map: Map<String, String>) {
        hints = map
        dirty = true
        publishNow()
    }

    fun clear() {
        found.clear()
        devices = emptyList()
    }

    private val publish = object : Runnable {
        override fun run() {
            if (dirty) publishNow()
            if (scanning) handler.postDelayed(this, 600)
        }
    }

    private fun publishNow() {
        dirty = false
        val now = System.currentTimeMillis()
        val n = numbers
        devices = found.values.map { d ->
            val aged0 = if (d.nearby && now - d.lastSeen > 30_000) d.copy(nearby = false) else d
            val aged = if (aged0.name == null) aged0.copy(hintName = hints[aged0.address.takeLast(5).lowercase()]) else aged0.copy(hintName = null)
            if (n != null) aged.copy(identity = BtFingerprint.classify(aged, n, oui)) else aged
        }
            .sortedWith(compareByDescending<BtDevice> { it.nearby }.thenByDescending { it.rssi })
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) = handle(r)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
        override fun onScanFailed(errorCode: Int) {
            error = "The scan failed (code $errorCode)"
            scanning = false
        }
    }

    @SuppressLint("MissingPermission")
    private val classic = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val cls = i.getParcelableExtra<BluetoothClass>(BluetoothDevice.EXTRA_CLASS)
                    val name = i.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    merge(d.address) { it.copy(name = name ?: it.name, rssi = if (rssi != Short.MIN_VALUE.toInt()) rssi else it.rssi, classic = true, deviceClass = cls?.deviceClass ?: it.deviceClass) }
                }
                // classic discovery ends after about 12 s; keep going while scanning
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (scanning) runCatching { adapter?.startDiscovery() }
            }
        }
    }

    private fun handle(r: ScanResult) {
        val rec = r.scanRecord
        val raw = rec?.bytes ?: ByteArray(0)
        val ads = AdParser.parse(raw)
        val manufacturer = HashMap<Int, ByteArray>()
        val s16 = HashSet<Int>()
        val s128 = HashSet<String>()
        val sdata = HashMap<Int, ByteArray>()
        var appearance: Int? = null
        var flags: Int? = null
        var tx: Int? = null
        for (ad in ads) when (ad.type) {
            0x01 -> flags = ad.data.firstOrNull()?.toInt()?.and(0xff)
            0x02, 0x03 -> for (i in 0 until ad.data.size / 2) s16 += ad.data.u16le(i * 2)
            0x06, 0x07 -> for (i in 0 until ad.data.size / 16) s128 += ad.data.copyOfRange(i * 16, i * 16 + 16).reversedArray().hex().let { "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" }
            0x0A -> tx = ad.data.firstOrNull()?.toInt()
            0x16 -> if (ad.data.size >= 2) sdata[ad.data.u16le(0)] = ad.data.copyOfRange(2, ad.data.size)
            0x19 -> if (ad.data.size >= 2) appearance = ad.data.u16le(0)
            0xFF -> if (ad.data.size >= 2) manufacturer[ad.data.u16le(0)] = ad.data.copyOfRange(2, ad.data.size)
        }
        val name = rec?.deviceName
        merge(r.device.address) { old ->
            old.copy(
                name = name ?: old.name,
                rssi = if (old.sightings == 0 || !old.le) r.rssi else (old.rssi * 7 + r.rssi * 3) / 10,
                raw = if (raw.size >= old.raw.size) raw else old.raw,
                manufacturer = old.manufacturer + manufacturer, services16 = old.services16 + s16, services128 = old.services128 + s128,
                serviceData = old.serviceData + sdata, txPower = tx ?: old.txPower, appearance = appearance ?: old.appearance, flags = flags ?: old.flags,
                connectable = old.connectable || r.isConnectable, le = true,
            )
        }
    }

    /** [heard] is false for devices we only know about (paired), which must not look like they are nearby. */
    private fun merge(address: String, heard: Boolean = true, change: (BtDevice) -> BtDevice) {
        val now = System.currentTimeMillis()
        val old = found[address] ?: BtDevice(address, firstSeen = now, lastSeen = 0, nearby = false)
        var d = change(old)
        if (heard) d = d.copy(sightings = old.sightings + 1, lastSeen = now, nearby = true)
        found[address] = d
        dirty = true
    }
}
