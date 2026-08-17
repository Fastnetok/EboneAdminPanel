package com.example.eboneadminpanel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local "diary" of every customer ID across Ebone / Wateen / Zong, synced
 * from each ISP panel's full customer list. Search inside the app reads
 * from this cache — instant, no network call — instead of hitting the
 * panel on every keystroke.
 *
 * Uses plain SharedPreferences (not encrypted) since this cache only
 * holds customer IDs/names — no passwords or sensitive data — so no
 * new library dependency is needed.
 *
 * Cache format per ISP: JSON array of {"id": "...", "name": "..."}
 */
object CustomerCacheManager {

    private const val PREFS = "customer_sync_cache"
    private const val KEY_PREFIX_LIST = "list_"       // + ISP name
    private const val KEY_PREFIX_SYNCED = "synced_at_" // + ISP name

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class CachedCustomer(val id: String, val name: String, val isp: String)

    /** Overwrites the cached list for one ISP with a freshly synced list. */
    fun saveList(context: Context, isp: String, customers: List<Pair<String, String>>) {
        val arr = JSONArray()
        for ((id, name) in customers) {
            arr.put(JSONObject().apply { put("id", id); put("name", name) })
        }
        prefs(context).edit()
            .putString(KEY_PREFIX_LIST + isp, arr.toString())
            .putLong(KEY_PREFIX_SYNCED + isp, System.currentTimeMillis())
            .apply()
    }

    fun getList(context: Context, isp: String): List<CachedCustomer> {
        val raw = prefs(context).getString(KEY_PREFIX_LIST + isp, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                CachedCustomer(obj.getString("id"), obj.optString("name", ""), isp)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getAll(context: Context): List<CachedCustomer> {
        return listOf("EBONE", "WATEEN", "ZONG").flatMap { getList(context, it) }
    }

    fun getLastSyncTime(context: Context, isp: String): Long =
        prefs(context).getLong(KEY_PREFIX_SYNCED + isp, 0L)

    fun getLastSyncTimeAny(context: Context): Long =
        listOf("EBONE", "WATEEN", "ZONG").maxOfOrNull { getLastSyncTime(context, it) } ?: 0L

    /** Live local search — no network call, filters the cached diary. */
    fun search(context: Context, query: String, ispFilter: String = "ALL"): List<CachedCustomer> {
        val source = if (ispFilter == "ALL") getAll(context) else getList(context, ispFilter)
        if (query.isBlank()) return source.take(20)
        return source.filter {
            it.id.contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true)
        }.sortedWith(
            compareBy(
                { !it.id.startsWith(query, ignoreCase = true) },
                { it.id.lowercase() }
            )
        ).take(30)
    }
}