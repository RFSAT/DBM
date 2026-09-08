package com.rfsat.dms.nav

import com.rfsat.dms.util.DLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Live fuel prices from FREE government open-data APIs, fetched STRICTLY ON
 * DEMAND — only when the user opens the info window on a fuel station. Nothing
 * is prefetched or polled, so no mobile data is spent unless the user asks.
 *
 * Design notes:
 *  - One provider per country (the feeds are national and all differ). The
 *    country comes from the active map region id, e.g. "france__alsace" -> FR.
 *  - Unsupported country -> no provider -> the panel simply shows no price.
 *  - Results are cached briefly (the feeds themselves only update every few
 *    minutes) so reopening the same station doesn't repeat the request.
 *  - Short timeouts and silent failure: a missing price must never block or
 *    break the info panel.
 *  - Every price carries the source's own timestamp, because a stale price is
 *    worse than no price.
 *
 * Only KEYLESS sources are implemented here. Germany (Tankerkönig) needs a
 * personal API key and CC-BY attribution, so it is deliberately not included.
 */

/** One fuel's price at a station. */
data class FuelPrice(
    val fuelType: String,      // "Diesel", "SP95", "E10", ...
    val price: Double,         // per litre, in `currency`
    val currency: String,      // "EUR"
    val updated: String?,      // source timestamp, shown to the user
)

/** The result of a price lookup: the prices plus who supplied them. */
data class FuelPriceResult(
    val prices: List<FuelPrice>,
    val source: String,        // shown as attribution, e.g. "prix-carburants.gouv.fr"
    val stationName: String?,
)

interface FuelPriceProvider {
    /** ISO-3166-1 alpha-2 country this provider serves. */
    val country: String
    /** Human-readable attribution for the data source. */
    val source: String
    /** Prices at/near the given station coordinates, or null if none found. */
    fun pricesAt(lat: Double, lon: Double): FuelPriceResult?
}

object FuelPrices {
    private const val TAG = "FuelPrices"
    private const val CACHE_MS = 10 * 60 * 1000L      // feeds update ~every 10 min
    private const val MATCH_M = 250.0                 // station match radius

    private val cache = HashMap<String, Pair<Long, FuelPriceResult?>>()

    private val providers: List<FuelPriceProvider> = listOf(
        FranceFuelProvider(),
        AustriaFuelProvider(),
        SpainFuelProvider(),
        // Italy (MIMIT) publishes only bulk daily CSV dumps with no geo-query
        // endpoint, so it cannot be fetched per-station without downloading the
        // whole country — deliberately omitted; Google is the fallback there.
    )

    /** Map a region id ("france__alsace", "spain__murcia", "greece") to a country
     *  code. The catalogue ids are the Geofabrik names, so the parent segment is
     *  the country. */
    fun countryOf(regionId: String?): String? {
        val id = regionId?.substringBefore("__")?.lowercase() ?: return null
        return when (id) {
            "france" -> "FR"
            "spain" -> "ES"
            "italy" -> "IT"
            "austria" -> "AT"
            "germany" -> "DE"
            else -> null
        }
    }

    /** True if we can serve prices for this region at all — lets the UI avoid
     *  showing a "fetching…" state where no source exists. */
    fun supports(regionId: String?): Boolean {
        val c = countryOf(regionId) ?: return false
        return providers.any { it.country == c }
    }

    /**
     * On-demand lookup. FREE government sources are tried FIRST; Google Places is
     * used only as a PAID FALLBACK when a free source is unavailable for the
     * country or returns no price — and only if the user has supplied their own
     * Google API key. Returns null when nothing is available; the caller then
     * just shows the POI without prices.
     * MUST be called off the main thread.
     */
    fun lookup(regionId: String?, lat: Double, lon: Double,
               googleApiKey: String? = null): FuelPriceResult? {
        val key = "%.4f:%.4f".format(lat, lon)
        synchronized(cache) {
            cache[key]?.let { (at, v) ->
                if (System.currentTimeMillis() - at < CACHE_MS) return v
            }
        }

        // 1) FREE government source for this country, if we have one.
        var res: FuelPriceResult? = null
        val c = countryOf(regionId)
        if (c != null) {
            val provider = providers.firstOrNull { it.country == c }
            if (provider != null) {
                res = runCatching { provider.pricesAt(lat, lon) }
                    .onFailure { DLog.i(TAG, "free source failed ($c): $it") }
                    .getOrNull()
            }
        }

        // 2) PAID fallback: Google Places, only when the free source gave nothing
        //    AND the user configured their own API key (their billing account).
        if ((res == null || res.prices.isEmpty()) && !googleApiKey.isNullOrBlank()) {
            res = runCatching { GoogleFuelProvider(googleApiKey).pricesAt(lat, lon) }
                .onFailure { DLog.i(TAG, "google fallback failed: $it") }
                .getOrNull()
        }

        synchronized(cache) { cache[key] = System.currentTimeMillis() to res }
        return res
    }

    /** True if ANY source could serve this location — a free provider for the
     *  country, or a Google key for the paid fallback. */
    fun canLookup(regionId: String?, googleApiKey: String?): Boolean =
        supports(regionId) || !googleApiKey.isNullOrBlank()

    // ---- shared helpers ----------------------------------------------------

    internal fun httpGet(url: String, timeoutMs: Int = 4000): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "DBM/1.0 (RFSAT driver monitor)")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            DLog.i(TAG, "http error: $e"); null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    internal fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/**
 * FRANCE — data.economie.gouv.fr "prix des carburants, flux instantané v2".
 * Keyless, Licence Ouverte 2.0, refreshed every ~10 minutes. Stations are
 * legally required to report, so coverage is ~9,800 of ~11,000 forecourts.
 */
private class FranceFuelProvider : FuelPriceProvider {
    override val country = "FR"
    override val source = "prix-carburants.gouv.fr"

    // Opendatasoft Explore API v2.1: nearest record within MATCH_M of the point.
    private val base = "https://data.economie.gouv.fr/api/explore/v2.1/catalog/" +
        "datasets/prix-des-carburants-en-france-flux-instantane-v2/records"

    // dataset column -> label shown to the user
    private val fuels = listOf(
        "gazole" to "Diesel", "sp95" to "SP95", "sp98" to "SP98",
        "e10" to "E10", "e85" to "E85", "gplc" to "LPG")

    override fun pricesAt(lat: Double, lon: Double): FuelPriceResult? {
        val where = FuelPrices.enc(
            "distance(geom, geom'POINT($lon $lat)', 250m)")
        val body = FuelPrices.httpGet("$base?where=$where&limit=1") ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val results = root.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.optJSONObject(0) ?: return null

        val out = ArrayList<FuelPrice>()
        for ((col, label) in fuels) {
            // v2 exposes "<fuel>_prix" and "<fuel>_maj" (last update)
            val p = r.opt("${col}_prix")
            val price = when (p) {
                is Number -> p.toDouble()
                is String -> p.toDoubleOrNull()
                else -> null
            } ?: continue
            if (price <= 0.0) continue
            val upd = r.optString("${col}_maj", "").takeIf { it.isNotBlank() }
            out.add(FuelPrice(label, price, "EUR", upd))
        }
        if (out.isEmpty()) return null
        val name = listOf("name", "enseignes", "adresse")
            .firstNotNullOfOrNull { r.optString(it, "").takeIf { s -> s.isNotBlank() } }
        return FuelPriceResult(out, source, name)
    }
}

/**
 * GOOGLE PLACES (New) — PAID FALLBACK ONLY.
 *
 * Used only when no free government source covers the country, or the free
 * source returned no price, AND the user has supplied their own Google API key
 * (so the cost lands on their billing account, not RFSAT's).
 *
 * COST WARNING: fuelOptions is "price data", which prices the call at the
 * Enterprise SKU (~$35 per 1,000 calls at low volume, after the monthly free
 * allowance). We therefore:
 *   - use ONE Nearby Search call with a minimal field mask (not the two-call
 *     search-then-details pattern, which would bill twice),
 *   - request ONLY the fields we display, since the field mask sets the SKU,
 *   - restrict to gas stations within a small radius,
 *   - and rely on the shared cache so re-opening a station doesn't re-bill.
 *
 * Uses the REST endpoint (no extra SDK dependency).
 */
private class GoogleFuelProvider(private val apiKey: String) : FuelPriceProvider {
    override val country = "*"          // global fallback
    override val source = "Google"

    override fun pricesAt(lat: Double, lon: Double): FuelPriceResult? {
        val url = "https://places.googleapis.com/v1/places:searchNearby"
        // Minimal field mask: only what we render. Every extra field can raise
        // the SKU, so keep this tight.
        val mask = "places.displayName,places.fuelOptions"
        val body = """
            {"includedTypes":["gas_station"],"maxResultCount":1,
             "locationRestriction":{"circle":{"center":
               {"latitude":$lat,"longitude":$lon},"radius":200.0}}}
        """.trimIndent()

        var conn: HttpURLConnection? = null
        val text = try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000; readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Goog-Api-Key", apiKey)
                setRequestProperty("X-Goog-FieldMask", mask)
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) {
                DLog.i("FuelPrices", "google HTTP ${conn.responseCode}")
                null
            } else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            DLog.i("FuelPrices", "google error: $e"); null
        } finally {
            runCatching { conn?.disconnect() }
        } ?: return null

        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val places = root.optJSONArray("places") ?: return null
        if (places.length() == 0) return null
        val place = places.optJSONObject(0) ?: return null
        val fo = place.optJSONObject("fuelOptions") ?: return null
        val list = fo.optJSONArray("fuelPrices") ?: return null

        val out = ArrayList<FuelPrice>()
        for (i in 0 until list.length()) {
            val fp = list.optJSONObject(i) ?: continue
            val money = fp.optJSONObject("price") ?: continue
            // Money: units (string int) + nanos (int) + currencyCode
            val units = money.optString("units", "0").toDoubleOrNull() ?: 0.0
            val nanos = money.optInt("nanos", 0) / 1_000_000_000.0
            val value = units + nanos
            if (value <= 0.0) continue
            out.add(FuelPrice(
                fuelType = prettyFuel(fp.optString("type", "")),
                price = value,
                currency = money.optString("currencyCode", ""),
                updated = fp.optString("updateTime", "").takeIf { it.isNotBlank() }))
        }
        if (out.isEmpty()) return null
        val name = place.optJSONObject("displayName")?.optString("text")
            ?.takeIf { it.isNotBlank() }
        return FuelPriceResult(out, source, name)
    }

    private fun prettyFuel(t: String): String = when (t) {
        "DIESEL" -> "Diesel"
        "REGULAR_UNLEADED" -> "Unleaded"
        "MIDGRADE" -> "Midgrade"
        "PREMIUM" -> "Premium"
        "SP91", "SP91_E10", "SP92", "SP95", "SP95_E10", "SP98", "SP99", "SP100" ->
            t.replace('_', ' ')
        "LPG" -> "LPG"
        "E80", "E85", "E100" -> t
        "METHANE" -> "Methane"
        "BIO_DIESEL" -> "Bio-diesel"
        "TRUCK_DIESEL" -> "Truck diesel"
        else -> t.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
    }
}

/**
 * AUSTRIA — E-Control "Spritpreisrechner" (api.e-control.at).
 * Keyless, statutory price-transparency database (Preistransparenzgesetz),
 * queried directly by lat/lon — ideal for an on-demand per-station lookup.
 *
 * Note on coverage: Austrian law only mandates Diesel and Super-95 (plus CNG),
 * and the service is only permitted to publish the CHEAPEST offers, so a given
 * station may legitimately have no price. We match the nearest returned station
 * to the tapped one and show whatever it has.
 */
private class AustriaFuelProvider : FuelPriceProvider {
    override val country = "AT"
    override val source = "spritpreisrechner.at (E-Control)"

    private val base = "https://api.e-control.at/sprit/1.0/search/gas-stations/by-address"
    // API fuel codes -> display label
    private val fuels = listOf("DIE" to "Diesel", "SUP" to "Super 95", "GAS" to "CNG")

    override fun pricesAt(lat: Double, lon: Double): FuelPriceResult? {
        val out = ArrayList<FuelPrice>()
        var name: String? = null
        for ((code, label) in fuels) {
            val body = FuelPrices.httpGet(
                "$base?latitude=$lat&longitude=$lon&fuelType=$code&includeClosed=true")
                ?: continue
            // response is a JSON ARRAY of stations, nearest-ish first
            val arr = runCatching { org.json.JSONArray(body) }.getOrNull() ?: continue
            // pick the station closest to the tapped point
            var best: JSONObject? = null
            var bestD = Double.MAX_VALUE
            for (i in 0 until arr.length()) {
                val st = arr.optJSONObject(i) ?: continue
                val loc = st.optJSONObject("location") ?: continue
                val sLat = loc.optDouble("latitude", Double.NaN)
                val sLon = loc.optDouble("longitude", Double.NaN)
                if (sLat.isNaN() || sLon.isNaN()) continue
                val d = haversine(lat, lon, sLat, sLon)
                if (d < bestD) { bestD = d; best = st }
            }
            val st = best ?: continue
            if (bestD > 250.0) continue          // not the station the user tapped
            if (name == null) name = st.optString("name", "").takeIf { it.isNotBlank() }
            val prices = st.optJSONArray("prices") ?: continue
            val p0 = prices.optJSONObject(0) ?: continue
            val amount = p0.optDouble("amount", 0.0)
            if (amount <= 0.0) continue
            out.add(FuelPrice(label, amount, "EUR",
                st.optString("observedAt", "").takeIf { it.isNotBlank() }))
        }
        return if (out.isEmpty()) null else FuelPriceResult(out, source, name)
    }

    private fun haversine(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val dLat = Math.toRadians(la2 - la1); val dLon = Math.toRadians(lo2 - lo1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}

/**
 * SPAIN — Ministry (MINETUR) price service.
 *
 * IMPORTANT LIMITATION: the national endpoint returns EVERY station in Spain
 * (~12,000) in one response — several MB. Downloading that per tap would be
 * exactly the mobile-data waste we set out to avoid, so we use the PROVINCE
 * filter endpoint instead, which returns only that province's stations. The
 * province is resolved from the coordinates by a small bounding-box table, and
 * the response is cached by the caller for 10 minutes.
 *
 * If the province can't be resolved we return null rather than fall back to the
 * whole-country download.
 */
private class SpainFuelProvider : FuelPriceProvider {
    override val country = "ES"
    override val source = "geoportalgasolineras.es (MINETUR)"

    private val base = "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/" +
        "PreciosCarburantes/EstacionesTerrestres/FiltroProvincia/"

    // MINETUR province IDs with rough bounding boxes (minLat,maxLat,minLon,maxLon).
    // Coarse on purpose: it only has to pick the right province for a lookup.
    private val provinces = listOf(
        Triple("28", "Madrid", doubleArrayOf(39.88, 41.17, -4.58, -3.05)),
        Triple("08", "Barcelona", doubleArrayOf(41.20, 42.33, 1.36, 2.78)),
        Triple("46", "Valencia", doubleArrayOf(38.71, 40.20, -1.53, -0.907)),
        Triple("41", "Sevilla", doubleArrayOf(36.93, 38.13, -6.55, -4.53)),
        Triple("29", "Malaga", doubleArrayOf(36.29, 37.28, -5.62, -3.75)),
        Triple("48", "Bizkaia", doubleArrayOf(43.02, 43.46, -3.45, -2.42)),
        Triple("50", "Zaragoza", doubleArrayOf(41.00, 42.28, -2.10, -0.30)),
        Triple("30", "Murcia", doubleArrayOf(37.35, 38.77, -2.35, -0.65)),
        Triple("03", "Alicante", doubleArrayOf(37.85, 38.90, -1.30, 0.20)),
        Triple("15", "A Coruna", doubleArrayOf(42.65, 43.79, -9.30, -7.85)),
    )

    private val fuels = listOf(
        "Precio Gasoleo A" to "Diesel",
        "Precio Gasolina 95 E5" to "SP95",
        "Precio Gasolina 98 E5" to "SP98",
        "Precio Gases licuados del petroleo" to "LPG")

    override fun pricesAt(lat: Double, lon: Double): FuelPriceResult? {
        val prov = provinces.firstOrNull { (_, _, b) ->
            lat >= b[0] && lat <= b[1] && lon >= b[2] && lon <= b[3]
        } ?: return null                      // outside the covered provinces
        val body = FuelPrices.httpGet(base + prov.first, timeoutMs = 6000) ?: return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val list = root.optJSONArray("ListaEESSPrecio") ?: return null

        var best: JSONObject? = null
        var bestD = Double.MAX_VALUE
        for (i in 0 until list.length()) {
            val st = list.optJSONObject(i) ?: continue
            val sLat = st.optString("Latitud", "").replace(',', '.').toDoubleOrNull()
                ?: continue
            val sLon = st.optString("Longitud (WGS84)", "").replace(',', '.')
                .toDoubleOrNull() ?: continue
            val d = Math.hypot((sLat - lat) * 111_320.0,
                               (sLon - lon) * 111_320.0 *
                                   Math.cos(Math.toRadians(lat)))
            if (d < bestD) { bestD = d; best = st }
        }
        val st = best ?: return null
        if (bestD > 250.0) return null

        val out = ArrayList<FuelPrice>()
        for ((field, label) in fuels) {
            val v = st.optString(field, "").replace(',', '.').toDoubleOrNull()
                ?: continue
            if (v <= 0.0) continue
            out.add(FuelPrice(label, v, "EUR", null))
        }
        if (out.isEmpty()) return null
        val name = st.optString("Rotulo", "").takeIf { it.isNotBlank() }
        return FuelPriceResult(out, source, name)
    }
}
