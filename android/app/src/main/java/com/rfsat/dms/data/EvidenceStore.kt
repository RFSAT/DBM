package com.rfsat.dms.data

import android.content.Context
import android.graphics.Bitmap
import com.rfsat.dms.CameraRole
import com.rfsat.dms.RiskEventCandidate
import com.rfsat.dms.Severity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Evidential recorder. For each accepted risk event it stores an annotated
 * JPEG snapshot of the triggering frame in app-private storage, computes its
 * SHA-256, and writes an immutable Room row referencing both.
 *
 * Debouncing: identical (camera,type) events within DEBOUNCE_MS collapse into
 * one row, so a 5-second microsleep doesn't generate 75 records.
 *
 * Extension point: replace snapshots with pre/post video clips by keeping a
 * rolling MediaMuxer segment buffer per stream and copying segments on event.
 */
class EvidenceStore(private val ctx: Context) {

    private val db = DmsDatabase.get(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastWrite = HashMap<String, Long>()
    private val fmt = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    val dao get() = db.events()

    fun record(role: CameraRole, ev: RiskEventCandidate, frame: Bitmap?, tMs: Long) {
        val key = "${role.name}:${ev.type.name}"
        synchronized(lastWrite) {
            val last = lastWrite[key] ?: 0L
            val debounce = if (ev.severity == Severity.CRITICAL) 3000L else DEBOUNCE_MS
            if (tMs - last < debounce) return
            lastWrite[key] = tMs
        }
        // Copy bitmap before going async — caller's frame buffer is reused.
        val copy = frame?.copy(Bitmap.Config.ARGB_8888, false)
        scope.launch {
            var path: String? = null
            var sha: String? = null
            if (copy != null) {
                val dir = File(ctx.filesDir, "evidence/${role.name.lowercase()}").apply { mkdirs() }
                val f = File(dir, "${fmt.format(Date(tMs))}_${ev.type.name}.jpg")
                f.outputStream().use { copy.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                copy.recycle()
                path = f.absolutePath
                sha = sha256(f)
            }
            dao.insert(
                EventEntity(
                    timestampMs = tMs, cameraRole = role.name, type = ev.type.name,
                    severity = ev.severity.name, confidence = ev.confidence,
                    detail = ev.detail, evidencePath = path, evidenceSha256 = sha
                )
            )
            // Retention: prune events + files older than 30 days.
            dao.prune(tMs - 30L * 24 * 3600 * 1000)
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object { const val DEBOUNCE_MS = 10_000L }
}
