package com.eta.autoledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.eta.autoledger.ui.LedgerApp

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN_AI_SETTINGS = "open_ai_settings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LedgerApp(openAiSettings = intent.getBooleanExtra(EXTRA_OPEN_AI_SETTINGS, false))
        }
    }
}