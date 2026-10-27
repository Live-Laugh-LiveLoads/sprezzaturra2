package com.ideaforge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ideaforge.ui.AppRoot
import com.ideaforge.ui.theme.IdeaForgeTheme
import dagger.hilt.android.AndroidEntryPoint

/** The single activity. Notification taps arrive as [EXTRA_ROUTE] and are handed to navigation. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only consume the deep link on a fresh start; after rotation navigation state is restored.
        if (savedInstanceState == null) pendingRoute = intent.getStringExtra(EXTRA_ROUTE)
        setContent {
            IdeaForgeTheme {
                AppRoot(deepLink = pendingRoute, onDeepLinkHandled = { pendingRoute = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute = it }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
