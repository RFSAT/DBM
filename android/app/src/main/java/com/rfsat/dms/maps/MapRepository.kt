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
        const val SUPPORTED_DB_SCHEMA = 5
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
        // Compressed maps are hosted as "<file>.gz" and streamed through a gunzip
        // so they land as the plain .db. sha256/sizeBytes always refer to the
        // decompressed .db, so integrity is checked on the final file regardless.
        val serverName = if (region.compressed) region.file + ".gz" else region.file
        val url = catalog.baseUrl.trimEnd('/') + "/" + serverName
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
            // Progress is measured on the COMPRESSED bytes actually transferred.
            val total = if (region.compressed && region.compressedSize > 0)
                            region.compressedSize
                        else if (!region.compressed && region.sizeBytes > 0)
                            region.sizeBytes
                        else conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            // Count transferred (compressed) bytes for progress via a wrapper, but
            // hash the DECOMPRESSED bytes for verification.
            var done = 0L
            var lastReport = 0L
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        done += n
                        if (done - lastReport > 500_000) {
                            onProgress(Progress.Downloading(done, total)); lastReport = done
                        }
                    }
                    return n
                }
            }
            val source: java.io.InputStream =
                if (region.compressed) java.util.zip.GZIPInputStream(counting)
                else counting
            source.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read: Int
                    while (input.read(buf).also { read = it } >= 0) {
                        out.write(buf, 0, read)
                        digest.update(buf, 0, read)   // hash of the decompressed .db
                    }
                }
            }

            // verify sha256 (of the decompressed .db) if the catalog provides one
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
            DLog.i(TAG, "downloaded ${region.id} v${region.version} -> ${dest.path}" +
                (if (region.compressed) " (gz)" else ""))
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

    /** Local cache file for the catalog JSON (kept beside the maps). */
    private fun cacheFile(): File = File(repo.mapsDir(), "index.cache.json")

    /**
     * Fetch the catalog and, on success, cache the raw JSON to disk so the list
     * can be shown instantly next time without a network round-trip. Returns the
     * parsed catalog (or null on any network/parse failure — the cache is left
     * untouched so we never lose a good copy).
     */
    fun fetchAndCacheCatalog(indexUrl: String): MapCatalog? = runCatching {
        val conn = (URL(indexUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 15000
        }
        val raw = try {
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
        val cat = MapCatalog.parse(raw)           // parse first; only cache if valid
        runCatching {
            val f = cacheFile()
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeText(raw)
            if (f.exists()) f.delete()
            tmp.renameTo(f)                        // atomic-ish replace
        }.onFailure { DLog.e(TAG, "cache write failed", it) }
        cat
    }.onFailure { DLog.e(TAG, "fetch+cache catalog failed", it) }.getOrNull()

    /** Load the last cached catalog from disk, or null if none/parse fails. */
    fun loadCachedCatalog(): MapCatalog? = runCatching {
        val f = cacheFile()
        if (!f.exists()) return null
        MapCatalog.parse(f.readText())
    }.onFailure { DLog.e(TAG, "cache read failed", it) }.getOrNull()

    // ---- region border polygons (borders.json, served like index.json) ------
    private fun bordersCacheFile(): File = File(repo.mapsDir(), "borders.cache.json")

    /** Fetch borders.json and cache it; returns parsed borders or null. */
    fun fetchAndCacheBorders(bordersUrl: String): RegionBorders? = runCatching {
        val conn = (URL(bordersUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 15000
        }
        val raw = try {
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().readText()
        } finally { conn.disconnect() }
        val parsed = RegionBorders.parse(raw)     // validate before caching
        runCatching {
            val f = bordersCacheFile()
            val tmp = File(f.parentFile, f.name + ".part")
            tmp.writeText(raw)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }.onFailure { DLog.e(TAG, "borders cache write failed", it) }
        parsed
    }.onFailure { DLog.e(TAG, "fetch+cache borders failed", it) }.getOrNull()

    /** Load cached borders from disk, or null if none/parse fails. */
    fun loadCachedBorders(): RegionBorders? = runCatching {
        val f = bordersCacheFile()
        if (!f.exists()) return null
        RegionBorders.parse(f.readText())
    }.onFailure { DLog.e(TAG, "borders cache read failed", it) }.getOrNull()
}

/**
 * Simplified border polygons per region id, loaded from borders.json
 * (served on rfsat.com beside index.json). Format:
 *   { "regionId": [ [ [lon,lat], [lon,lat], ... ] (ring), ...more rings ], ... }
 * A region may have several rings (islands / multi-part). Regions absent here
 * simply have no border and the UI falls back to their bbox.
 */
class RegionBorders(private val map: Map<String, List<List<Pair<Float, Float>>>>) {
    fun border(id: String): List<List<Pair<Float, Float>>>? = map[id]
    val size: Int get() = map.size

    companion object {
        fun parse(json: String): RegionBorders {
            val o = org.json.JSONObject(json)
            val out = HashMap<String, List<List<Pair<Float, Float>>>>()
            val ids = o.keys()
            while (ids.hasNext()) {
                val id = ids.next()
                val ringsJson = o.getJSONArray(id)
                val rings = ArrayList<List<Pair<Float, Float>>>(ringsJson.length())
                for (i in 0 until ringsJson.length()) {
                    val ringJson = ringsJson.getJSONArray(i)
                    val ring = ArrayList<Pair<Float, Float>>(ringJson.length())
                    for (j in 0 until ringJson.length()) {
                        val pt = ringJson.getJSONArray(j)   // [lon, lat]
                        ring.add(pt.getDouble(0).toFloat() to pt.getDouble(1).toFloat())
                    }
                    if (ring.size >= 3) rings.add(ring)
                }
                if (rings.isNotEmpty()) out[id] = rings
            }
            return RegionBorders(out)
        }
    }
}
