package com.moyeon.boardandgo.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val controller: AppController by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFFB300),
                    background = Color(0xFF101114),
                    surface = Color(0xFF1B1D22),
                )
            ) {
                val state by controller.state.collectAsStateWithLifecycle()
                if (state.passengerScreen) {
                    PassengerScreen(state, onExit = { controller.setPassengerScreen(false) })
                } else {
                    DriverScreen(state, controller)
                }
            }
        }
    }
}
