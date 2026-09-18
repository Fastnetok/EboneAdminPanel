package com.example.eboneadminpanel

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class ZongRenalaBalanceSyncActivity : AppCompatActivity() {

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val balance = result.data?.getDoubleExtra("checked_balance", Double.NaN)
                ?: Double.NaN

            if (result.resultCode == RESULT_OK && !balance.isNaN()) {
                ZongRenalaBalanceManager.updateBalance(balance) {
                    finish()
                }
            } else {
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        launcher.launch(
            Intent(this, ZongRenalaWebViewActivity::class.java).apply {
                putExtra("selected_isp", "ZONG")
                putExtra("manual_action", "CHECK_BALANCE")
                putExtra("target_zone", "Renala")
                putExtra("balance_sync_mode", true)
            }
        )
    }
}
