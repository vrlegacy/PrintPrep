package io.printprep.app.print

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import java.io.IOException

/** What the app knows about the USB printer right now. */
sealed interface PrinterState {
    /** Nothing printer-like is plugged in. */
    data object Disconnected : PrinterState
    /** A printer is plugged in but the user hasn't allowed USB access yet. */
    data class NeedsPermission(val name: String) : PrinterState
    /** Ready to print. [status] is the printer's own report (paper out, error), if it gives one. */
    data class Ready(val name: String, val isCanon: Boolean, val status: PortStatus?) : PrinterState
}

/** USB printer-class port status (one byte): paper empty, selected (online), no error. */
data class PortStatus(val raw: Int) {
    val paperEmpty: Boolean get() = raw and 0x20 != 0
    val selected: Boolean get() = raw and 0x10 != 0
    val error: Boolean get() = raw and 0x08 == 0
    val summary: String get() = when {
        paperEmpty -> "Out of paper"
        error -> "Printer reports a problem (paper jam, cover open, ink or other error)"
        else -> "Ready"
    }
}

/**
 * Finds the USB printer, asks for permission once, reports status and sends raw printer data.
 * Works for any USB printer-class device; Canon (vendor 0x04A9) is what we target.
 */
class UsbPrinter(private val context: Context) {

    var state by mutableStateOf<PrinterState>(PrinterState.Disconnected)
        private set
    var lastDeviceId by mutableStateOf<String?>(null)
        private set

    /** True while a job is being sent; status reads are skipped so they never interrupt it. */
    @Volatile var busy = false

    private val usb = context.getSystemService(UsbManager::class.java)
    private var device: UsbDevice? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = refresh()
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun close() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** Re-scans USB devices and updates [state]. Cheap; call whenever something may have changed. */
    fun refresh() {
        val d = usb.deviceList.values.firstOrNull { findPrinterInterface(it) != null }
        device = d
        state = when {
            d == null -> PrinterState.Disconnected
            !usb.hasPermission(d) -> PrinterState.NeedsPermission(displayName(d, false))
            else -> PrinterState.Ready(
                displayName(d, true),
                d.vendorId == CANON_VENDOR_ID,
                if (busy) (state as? PrinterState.Ready)?.status else runCatching { readStatus() }.getOrNull(),
            )
        }
    }

    fun requestPermission() {
        val d = device ?: return refresh()
        if (usb.hasPermission(d)) return refresh()
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_PERMISSION).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
        usb.requestPermission(d, pi)
    }

    /** Reads the one-byte port status. Throws if the printer isn't available. */
    fun readStatus(): PortStatus? = withConnection { conn, intf ->
        val buf = ByteArray(1)
        val n = conn.controlTransfer(REQTYPE_IN_CLASS_INTERFACE, REQ_GET_PORT_STATUS, 0, intf.id shl 8, buf, 1, 2_000)
        if (n == 1) PortStatus(buf[0].toInt() and 0xFF) else null
    }

    /** Reads the IEEE-1284 Device ID ("MFG:Canon;MDL:G2010 series;CMD:…"). */
    fun readDeviceId(): String? = withConnection { conn, intf ->
        val buf = ByteArray(1024)
        val n = conn.controlTransfer(REQTYPE_IN_CLASS_INTERFACE, REQ_GET_DEVICE_ID, 0, (intf.id shl 8) or intf.alternateSetting, buf, buf.size, 3_000)
        if (n < 2) return@withConnection null
        val len = (((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)).coerceIn(2, n)
        String(buf, 2, len - 2, Charsets.ISO_8859_1).trim()
    }.also { lastDeviceId = it }

    /** Human-readable USB details for the diagnostics screen. */
    fun describe(): String {
        val d = device ?: return "No USB printer found."
        return buildString {
            appendLine("Vendor:Product  %04X:%04X%s".format(d.vendorId, d.productId, if (d.vendorId == CANON_VENDOR_ID) " (Canon)" else ""))
            if (usb.hasPermission(d)) {
                runCatching { appendLine("Name            ${d.manufacturerName ?: "-"} ${d.productName ?: ""}") }
            } else appendLine("(details hidden until USB access is allowed)")
            for (i in 0 until d.interfaceCount) {
                val intf = d.getInterface(i)
                appendLine("Interface ${intf.id} alt ${intf.alternateSetting}: class ${intf.interfaceClass}/${intf.interfaceSubclass}/${intf.interfaceProtocol}")
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    appendLine("  EP 0x%02X %s type %d max %d".format(ep.address, if (ep.direction == UsbConstants.USB_DIR_IN) "IN " else "OUT", ep.type, ep.maxPacketSize))
                }
            }
        }.trimEnd()
    }

    /**
     * Streams a file of raw printer data to the printer. [onProgress] gets bytes sent so far;
     * return false from [shouldContinue] to stop between chunks.
     *
     * When the printer stops taking data (paper jam, out of paper, still busy with the last page)
     * we don't push harder: we wait with short timeouts, read its status and report it through
     * [onStall] (null once it moves again), like a desktop driver does. Fixing the printer and
     * pressing its Resume/Stop button lets the job carry on from where it stopped.
     */
    fun send(
        file: File,
        onProgress: (Long) -> Unit,
        shouldContinue: () -> Boolean,
        onStall: (status: PortStatus?, seconds: Int) -> Unit = { _, _ -> },
    ): Long {
        return withConnection { conn, intf ->
            val out = bulkOut(intf) ?: throw IOException("The printer has no data endpoint.")
            // One queued request per chunk, waited on in short slices. Unlike bulkTransfer() with a
            // timeout, a slow printer never makes us abandon a half-delivered chunk and send it
            // again (which corrupted the page data and left the printer stuck).
            val req = UsbRequest()
            if (!req.initialize(conn, out)) throw IOException("Couldn't open the printer's data channel.")
            try {
                file.inputStream().use { input ->
                    val buf = ByteArray(CHUNK)
                    var total = 0L
                    while (true) {
                        if (!shouldContinue()) throw CancelledException()
                        val read = input.read(buf)
                        if (read < 0) break
                        if (read == 0) continue
                        if (!req.queue(ByteBuffer.wrap(buf, 0, read))) throw IOException("The printer refused the data.")
                        var stalledSince = 0L
                        while (true) {
                            val done = try {
                                conn.requestWait(STALL_TIMEOUT_MS.toLong())
                            } catch (_: TimeoutException) {
                                null
                            }
                            if (done === req) break
                            if (done != null) continue
                            // Still waiting: the printer isn't taking data right now.
                            if (!stillAttached()) throw IOException("The printer was disconnected while printing.")
                            if (!shouldContinue()) {
                                req.cancel()
                                throw CancelledException()
                            }
                            val now = System.currentTimeMillis()
                            if (stalledSince == 0L) stalledSince = now
                            val secs = ((now - stalledSince) / 1000).toInt() + STALL_TIMEOUT_MS / 1000
                            onStall(runCatching { portStatus(conn, intf) }.getOrNull(), secs)
                            if (secs >= MAX_STALL_S) {
                                req.cancel()
                                throw IOException("The printer hasn't taken any data for ${MAX_STALL_S / 60} minutes, so printing was stopped.")
                            }
                        }
                        if (stalledSince != 0L) onStall(null, 0)
                        total += read
                        onProgress(total)
                    }
                    total
                }
            } finally {
                req.close()
            }
        } ?: throw IOException("The printer isn't connected or USB access wasn't allowed.")
    }

    /**
     * USB printer-class SOFT_RESET: drops whatever is still queued in the printer's USB buffer, so a
     * half-sent page doesn't sit there and get mixed up with the next job. Best effort.
     */
    fun softReset(): Boolean = withConnection { conn, intf ->
        conn.controlTransfer(REQTYPE_OUT_CLASS_INTERFACE, REQ_SOFT_RESET, 0, intf.id, null, 0, 3_000) >= 0
    } ?: false

    private fun stillAttached(): Boolean {
        val d = device ?: return false
        return usb.deviceList.values.any { it.deviceName == d.deviceName }
    }

    class CancelledException : IOException("Cancelled")

    // ------------------------------------------------------------------

    private fun <T> withConnection(block: (UsbDeviceConnection, UsbInterface) -> T): T? {
        val d = device ?: return null
        if (!usb.hasPermission(d)) return null
        val intf = findPrinterInterface(d) ?: return null
        val conn = usb.openDevice(d) ?: return null
        try {
            if (!conn.claimInterface(intf, true)) return null
            return block(conn, intf)
        } finally {
            runCatching { conn.releaseInterface(intf) }
            conn.close()
        }
    }

    private fun portStatus(conn: UsbDeviceConnection, intf: UsbInterface): PortStatus? {
        val buf = ByteArray(1)
        val n = conn.controlTransfer(REQTYPE_IN_CLASS_INTERFACE, REQ_GET_PORT_STATUS, 0, intf.id shl 8, buf, 1, 2_000)
        return if (n == 1) PortStatus(buf[0].toInt() and 0xFF) else null
    }

    private fun displayName(d: UsbDevice, permitted: Boolean): String {
        val product = if (permitted) runCatching { d.productName }.getOrNull() else null
        return when {
            !product.isNullOrBlank() -> (if (d.vendorId == CANON_VENDOR_ID && !product.contains("Canon", true)) "Canon " else "") + product
            d.vendorId == CANON_VENDOR_ID -> "Canon printer"
            else -> "USB printer"
        }
    }

    companion object {
        const val CANON_VENDOR_ID = 0x04A9
        private const val ACTION_PERMISSION = "io.printprep.app.USB_PERMISSION"
        private const val CHUNK = 16 * 1024
        /** Short waits so we notice a stuck printer quickly and can tell the user why. */
        private const val STALL_TIMEOUT_MS = 5_000
        /** Give up only after this long without progress (the user can cancel any time). */
        private const val MAX_STALL_S = 10 * 60
        private const val REQTYPE_OUT_CLASS_INTERFACE = 0x21
        private const val REQ_SOFT_RESET = 2
        private const val REQTYPE_IN_CLASS_INTERFACE = 0xA1
        private const val REQ_GET_DEVICE_ID = 0
        private const val REQ_GET_PORT_STATUS = 1

        /** Printer class (7) interface with a bulk OUT endpoint; never the IPP-over-USB one (protocol 4). */
        fun findPrinterInterface(d: UsbDevice): UsbInterface? =
            (0 until d.interfaceCount).map { d.getInterface(it) }
                .filter { it.interfaceClass == UsbConstants.USB_CLASS_PRINTER && it.interfaceProtocol != 4 && bulkOut(it) != null }
                .minByOrNull { if (it.interfaceProtocol == 2) 0 else 1 }

        private fun bulkOut(intf: UsbInterface): UsbEndpoint? = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            .firstOrNull { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK && it.direction == UsbConstants.USB_DIR_OUT }
    }
}
