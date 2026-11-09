package io.printprep.app.print

/**
 * What the printer itself says on its USB back channel (Canon IVEC "GetStatusResponse").
 * Field meanings follow the open-source canon-universal-driver (GPL-3.0), verified on a G3010:
 * status = idle / processing / printing / stopped / busying / canceling …,
 * detail = MediaEmpty / MediaJam / DoorOpen / MarkerSupplyAttention / DeviceBusy / AttentionRequired.
 */
data class CanonStatus(val status: String, val detail: String, val code: String, val at: Long) {

    val idle: Boolean get() = status == "idle"

    /** A problem the user has to fix at the printer, or null. */
    val problem: String? get() {
        val c = if (code.isNotEmpty() && code != "0") " (printer code $code)" else ""
        return when {
            detail == "MediaEmpty" || code == "1000" -> "The printer is out of paper$c. Load paper in the rear tray, then press the printer's Black or Color button to carry on."
            detail == "MediaJam" || code.startsWith("13") -> "Paper is jammed$c. Pull the sheet out gently, then press the printer's Black or Color button (or Stop to cancel)."
            detail == "DoorOpen" -> "The printer's cover is open$c. Close it to carry on."
            detail == "MarkerSupplyAttention" -> "The printer reports an ink problem$c. Check the ink tanks and the printer's lights."
            detail == "AttentionRequired" || status == "stopped" || status == "notready" ->
                "The printer has stopped$c. Check its lights and paper, then press the Black or Color button to carry on."
            else -> null
        }
    }

    /** Short label for the progress line. */
    val label: String get() = when (status) {
        "idle" -> "ready"
        "processing", "printing", "waitingprint" -> "printing"
        "busying" -> "busy"
        "canceling" -> "cancelling"
        "stopped", "notready" -> "stopped"
        else -> status
    }

    companion object {
        const val GET_STATUS =
            "<?xml version=\"1.0\" encoding=\"utf-8\" ?><cmd xmlns:ivec=\"http://www.canon.com/ns/cmd/2008/07/common/\">" +
                "<ivec:contents><ivec:operation>GetStatus</ivec:operation><ivec:param_set servicetype=\"print\"></ivec:param_set>" +
                "</ivec:contents></cmd>"

        /** Gutenprint's Canon job header always uses job ID 00000001. */
        const val CANCEL_JOB =
            "<?xml version=\"1.0\" encoding=\"utf-8\" ?><cmd xmlns:ivec=\"http://www.canon.com/ns/cmd/2008/07/common/\">" +
                "<ivec:contents><ivec:operation>CancelJob</ivec:operation><ivec:param_set servicetype=\"print\">" +
                "<ivec:jobID>00000001</ivec:jobID></ivec:param_set></ivec:contents></cmd>"

        private fun tag(doc: String, name: String): String? {
            val open = "<ivec:$name>"
            val i = doc.lastIndexOf(open).takeIf { it >= 0 } ?: return null
            val j = doc.indexOf("</ivec:$name>", i)
            return if (j < 0) null else doc.substring(i + open.length, j).trim()
        }

        /** Parses one complete <cmd>…</cmd> document; null unless it is a successful status reply. */
        fun parse(doc: String, now: Long = System.currentTimeMillis()): CanonStatus? {
            if (tag(doc, "operation") != "GetStatusResponse") return null
            if (tag(doc, "response") != "OK") return null
            val status = tag(doc, "status") ?: return null
            return CanonStatus(
                status = status,
                detail = tag(doc, "status_detail").orEmpty(),
                code = tag(doc, "current_support_code") ?: tag(doc, "error_code").orEmpty(),
                at = now,
            )
        }
    }
}

/** Collects back-channel bytes (arbitrary chunk boundaries) and keeps the latest status. */
class CanonStatusDecoder {
    private val buf = StringBuilder()
    @Volatile var latest: CanonStatus? = null
        private set
    @Volatile var everAnswered = false
        private set

    fun feed(bytes: ByteArray, n: Int) {
        if (n <= 0) return
        everAnswered = true
        buf.append(String(bytes, 0, n, Charsets.UTF_8))
        while (true) {
            val start = buf.indexOf("<cmd")
            if (start < 0) { keepTail(); return }
            val end = buf.indexOf("</cmd>", start)
            if (end < 0) {
                if (start > 0) buf.delete(0, start)
                if (buf.length > 64 * 1024) buf.setLength(0)
                return
            }
            CanonStatus.parse(buf.substring(start, end + 6))?.let { latest = it }
            buf.delete(0, end + 6)
        }
    }

    private fun keepTail() {
        if (buf.length > 8) buf.delete(0, buf.length - 8)
    }
}
