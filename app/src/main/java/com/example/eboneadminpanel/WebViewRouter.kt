package com.example.eboneadminpanel

import android.content.Context
import android.content.Intent

object WebViewRouter {
    fun getTargetActivity(isp: String?, zone: String?): Class<*> {
        val cleanIsp = isp?.uppercase() ?: "EBONE"
        val cleanZone = if (zone.isNullOrBlank()) "Okara" else zone

        return when {
            cleanIsp == "EBONE" -> EboneWebViewActivity::class.java
            cleanIsp == "WATEEN" -> WateenWebViewActivity::class.java
            cleanIsp == "ZONG" && cleanZone.equals("Renala", ignoreCase = true) -> ZongRenalaWebViewActivity::class.java
            cleanIsp == "ZONG" -> ZongOkaraWebViewActivity::class.java
            else -> EboneWebViewActivity::class.java
        }
    }

    fun launch(context: Context, isp: String?, zone: String?, extras: Intent.() -> Unit = {}) {
        val cleanIsp = isp?.uppercase() ?: "EBONE"
        val cleanZone = if (zone.isNullOrBlank()) "Okara" else zone
        val intent = Intent(context, getTargetActivity(cleanIsp, cleanZone))
        intent.putExtra("selected_isp", cleanIsp)
        intent.putExtra("target_zone", cleanZone)
        intent.putExtra("zone", cleanZone)
        intent.apply(extras)
        context.startActivity(intent)
    }
}
