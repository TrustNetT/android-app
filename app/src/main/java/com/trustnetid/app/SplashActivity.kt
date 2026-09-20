package com.trustnetid.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.trustnetid.AppVersion

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hide the action bar for a clean splash screen
        supportActionBar?.hide()
        setContentView(R.layout.activity_splash)
        
        // Set version text from AppVersion (dynamically, not hardcoded)
        val splashVersion = findViewById<TextView>(R.id.splashVersion)
        splashVersion.text = AppVersion.getVersionString()
        
        // Show splash screen for 3 seconds before launching MainActivity
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 3000)
    }
}
