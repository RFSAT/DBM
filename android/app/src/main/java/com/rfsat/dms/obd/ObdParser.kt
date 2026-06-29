package com.rfsat.dms.obd

/**
 * Parses raw ELM327 ASCII responses into usable byte arrays.
 *
 * An ELM327 reply to "010D" looks like (whitespace/case vary by adapter):
 *     "41 0D 1F\r\r>"
 * where "41" = 0x40 + request mode (response to mode 01), "0D" = echoed PID,
 * and the remaining bytes are the data. We strip the prompt, echoes, and the
 * mode/PID header, returning just the data bytes.
 *
 * DEFENSIVE BY DESIGN: cheap ELM327 clones add spurious whitespace, send
 * "SEARCHING...", "NO DATA", "?", or "UNABLE TO CONNECT", echo the command back,
 * or split a reply across lines. All of those are handled as "no usable data"
 * (null) rather than crashing or returning garbage.
 */
object ObdParser {

    /** Adapter status strings that mean "no usable data this time". */
    private val NON_DATA = listOf(
        "NODATA", "STOPPED", "SEARCHING", "UNABLE", "ERROR", "?", "BUFFERFULL",
        "CANERROR", "BUSINIT", "BUSBUSY", "FBERROR", "DATAERROR",
    )

    /**
     * Parse a raw response for an expected PID. Returns the decoded data bytes
     * (excluding the "41 XX" header), or null if the response is an error/empty
     * or doesn't match the expected mode+PID.
     *
     * @param raw the raw text read from the adapter
     * @param expected the PID we asked for (to validate the echo header)
     */
    fun parseData(raw: String, expected: Pid): IntArray? {
        val cleaned = raw.uppercase()
            .replace(">", " ")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
        if (cleaned.isEmpty()) return null

        val compact = cleaned.replace(" ", "")
        if (NON_DATA.any { compact.contains(it) }) return null

        // Tokens that are valid 2-hex-digit bytes.
        val tokens = cleaned.split(" ").filter { it.isNotBlank() }
        val bytes = ArrayList<Int>(tokens.size)
        for (t in tokens) {
            if (t.length == 2 && t.all { it.isDigit() || it in 'A'..'F' }) {
                bytes.add(t.toInt(16))
            } else if (t.length > 2 && t.length % 2 == 0 &&
                t.all { it.isDigit() || it in 'A'..'F' }) {
                // some adapters concatenate bytes without spaces: "410D1F"
                var i = 0
                while (i < t.length) { bytes.add(t.substring(i, i + 2).toInt(16)); i += 2 }
            }
        }
        if (bytes.isEmpty()) return null

        // Find the response header: 0x40 + mode, then the echoed PID.
        val respMode = 0x40 + expected.mode
        val idx = bytes.indexOfFirst { it == respMode }
        if (idx < 0 || idx + 1 >= bytes.size) return null
        if (bytes[idx + 1] != expected.pid) return null

        val data = bytes.subList(idx + 2, bytes.size)
        if (data.isEmpty()) return null
        return data.toIntArray()
    }

    /**
     * Parse a 4-byte support bitmask response (PID 0x00 / 0x20 / 0x40) into the
     * set of supported PID NUMBERS in that block. Bit ordering per SAE J1979:
     * the MSB of byte A corresponds to the FIRST pid after the base, descending.
     *
     * @param base 0x00 -> covers 0x01..0x20, 0x20 -> 0x21..0x40, etc.
     */
    fun parseSupportMask(raw: String, supportPid: Pid): Set<Int>? {
        val data = parseData(raw, supportPid) ?: return null
        if (data.size < 4) return null
        val base = supportPid.pid   // 0x00, 0x20, 0x40
        val supported = mutableSetOf<Int>()
        // 32 bits, MSB first across the 4 bytes.
        var bit = 0
        for (byteVal in data.take(4)) {
            for (b in 7 downTo 0) {
                bit++
                if ((byteVal shr b) and 1 == 1) supported.add(base + bit)
            }
        }
        return supported
    }
}
