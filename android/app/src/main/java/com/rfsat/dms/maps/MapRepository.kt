package com.rfsat.dms.maps

import android.content.Context
import com.rfsat.dms.util.DLog
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Local install record for one region (what version is on the device). */
data class InstalledMap(val id: String, val file: String, val version: Int, val dataDate: String)

/** The status of a region relative to what's installed. */
enum class MapState { NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE, UNSUPPORTED_SCHEMA }

data class RegionStatus(val region: MapRegion, val state: MapState, val installed: InstalledMap?)

/**
 * Tracks which map regions are installed on the device and at what version, and
 * compares against the server catalog to detect outdated maps. Install records
 * are kept in a small JSON file next to the .db files.
 */
class MapRepository(private val ctx: Context) {

    private val mapsDir = File(ctx.filesDir, "maps").apply { mkdirs() }
    private val recordsFile = File(mapsDir, "installed.json")

    companion object {
        private const val TAG = "MapRepository"
        // The .db schema versions this app build can read.
        const val SUPPORTED_DB_SCHEMA = 3
    }

    fun installed(): Map<String, InstalledMap> {
        if (!recordsFile.exists()) return emptyMap()
        return runCatching {
            val o = JSONObject(recordsFile.readText())
            val out = HashMap<String, InstalledMap>()
            val arr = o.getJSONArray("installed")
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                out[r.getString("id")] = InstalledMap(
                    r.getString("id"), r.getString("file"),
                    r.getInt("version"), r.optString("dataDate", ""))
            }
            out
        }.getOrElse { emptyMap() }
    }

    private fun saveInstalled(records: Collection<InstalledMap>) {
        val arr = org.json.JSONArray()
        for (m in records) arr.put(JSONObject().apply {
            put("id", m.id); put("file", m.file)
            put("version", m.version); put("dataDate", m.dataDate)
        })
        recordsFile.writeText(JSONObject().apply { put("installed", arr) }.toString())
    }

    /** Compare the catalog against installed records to produce per-region status. */
    fun statusFor(catalog: MapCatalog): List<RegionStatus> {
        val inst = installed()
        return catalog.regions.map { r ->
            val have = inst[r.id]
            val state = when {
                r.dbSchemaVersion > SUPPORTED_DB_SCHEMA -> MapState.UNSUPPORTED_SCHEMA
                have == null -> MapState.NOT_INSTALLED
                r.version > have.version -> MapState.UPDATE_AVAILABLE
                else -> MapState.INSTALLED
            }
            RegionStatus(r, state, have)
        }
    }

    fun recordInstalled(region: MapRegion) {
        val map = installed().toMutableMap()
        map[region.id] = InstalledMap(region.id, region.file, region.version, region.dataDate)
        saveInstalled(map.values)
    }

    fun delete(region: MapRegion) {
        runCatching { File(mapsDir, region.file).delete() }
        val map = installed().toMutableMap()
        map.remove(region.id)
        saveInstalled(map.values)
    }

    fun mapsDir(): File = mapsDir
}

/**
 * Downloads a region .db over HTTP with progress, into the app's maps dir, and
 * verifies the sha256 before committing it (so a partial/corrupt download cannot
 * replace a working map). Downloads to a .part file, then renames on success.
 */
class MapDownloader(private val repo: MapRepository) {

    companion object { private const val TAG = "MapDownloader" }

    sealed class Progress {
        data class Downloading(val bytes: Long, val total: Long) : Progress()
        object Verifying : Progress()
        object Done : Progress()
        data class Failed(val reason: String) : Progress()
    }

    /**
     * @param onProgress called on a background thread with progress updates.
     * Returns true on success.
     */
    fun download(catalog: MapCatalog, region: MapRegion,
                 onProgress: (Progress) -> Unit): Boolean {
        val url = catalog.baseUrl.trimEnd('/') + "/" + region.file
        val dir = repo.mapsDir()
        val part = File(dir, region.file + ".part")
        val dest = File(dir, region.file)

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000
            }
            if (conn.responseCode != 200) {
                onProgress(Progress.Failed("HTTP ${conn.responseCode}")); return false
            }
            val total = if (region.sizeBytes > 0) region.sizeBytes
                        else conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read: Int
                    var done = 0L
                    var lastReport = 0L
                    while (input.read(buf).also { read = it } >= 0) {
                        out.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        done += read
                        if (done - lastReport > 1_000_000) {   // report each ~1 MB
                            onProgress(Progress.Downloading(done, total)); lastReport = done
                        }
                    }
                }
            }

            // verify sha256 if the catalog provides one
            if (region.sha256.isNotBlank()) {
                onProgress(Progress.Verifying)
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (!hex.equals(region.sha256, ignoreCase = true)) {
                    part.delete()
                    onProgress(Progress.Failed("checksum mismatch")); return false
                }
            }

            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) { part.delete(); onProgress(Progress.Failed("rename failed")); return false }
            repo.recordInstalled(region)
            DLog.i(TAG, "downloaded ${region.id} v${region.version} -> ${dest.path}")
            onProgress(Progress.Done)
            return true
        } catch (e: Exception) {
            runCatching { part.delete() }
            DLog.e(TAG, "download failed: ${region.id}", e)
            onProgress(Progress.Failed(e.message ?: "error")); return false
        } finally {
            conn?.disconnect()
        }
    }

    /** Fetch and parse the catalog from the server. */
    fun fetchCatalog(indexUrl: String): MapCatalog? = runCatching {
        val conn = (URL(indexUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 15000
        }
        try {
            if (conn.responseCode != 200) return null
            MapCatalog.parse(conn.inputStream.bufferedReader().readText())
        } finally { conn.disconnect() }
    }.onFailure { DLog.e(TAG, "fetch catalog failed", it) }.getOrNull()
}
