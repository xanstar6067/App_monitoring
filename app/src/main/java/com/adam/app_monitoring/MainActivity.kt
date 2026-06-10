package com.adam.app_monitoring

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.adam.app_monitoring.data.ThemePreference
import com.adam.app_monitoring.ui.MonitoringApp
import com.adam.app_monitoring.ui.TrafficViewModel
import com.adam.app_monitoring.ui.theme.App_monitoringTheme

class MainActivity : ComponentActivity() {
    private val viewModel: TrafficViewModel by viewModels {
        TrafficViewModel.factory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.state.collectAsState()
            val darkTheme = when (state.settings.theme) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            App_monitoringTheme(
                darkTheme = darkTheme,
                dynamicColor = false
            ) {
                MonitoringApp(state = state, viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }
}
