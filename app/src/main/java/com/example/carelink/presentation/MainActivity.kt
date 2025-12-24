package com.example.carelink.presentation


import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.carelink.presentation.FallDetectService

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = Intent(this, FallDetectService::class.java)
        startForegroundService(intent)
        finish()

    }
}
