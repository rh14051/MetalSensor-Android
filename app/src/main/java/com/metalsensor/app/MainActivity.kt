package com.metalsensor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.metalsensor.app.ui.AppRoot
import com.metalsensor.app.ui.MainViewModel
import com.metalsensor.app.ui.theme.MetalSensorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MetalSensorTheme {
                val vm: MainViewModel = viewModel()
                AppRoot(vm)
            }
        }
    }
}
