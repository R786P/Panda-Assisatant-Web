package com.r786p.pandaassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService

class MainActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_CAPTURE = 4101
        private const val REQUEST_NOTIFICATION = 4102
        private const val REQUEST_AUDIO = 4103
        private const val PANDA_BACKEND = "https://panda-assisatant-web.onrender.com"
    }

    private var waitingForOverlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO)
        }
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 50, 40, 40)
        }
        root.addView(TextView(this).apply { text = "🐼 Panda Assistant"; textSize = 28f }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "Phone ki screen live dikhao aur kisi bhi app/website ke baare mein poochho. Panda bubble me Hindi chat aur Hindi voice reply dono milenge.\n\n☁️ Backend: Panda Assistant (Render)\n🧠 AI: Gemini"
            textSize = 16f
            setPadding(0, 20, 0, 20)
        }, LinearLayout.LayoutParams(-1, -2))
        root.addView(TextView(this).apply {
            text = "Connected to:\n$PANDA_BACKEND"
            textSize = 12f
            setPadding(0, 0, 0, 8)
        }, LinearLayout.LayoutParams(-1, -2))
        val start = Button(this).apply { text = "🔴 Start Live Screen"; setOnClickListener { startLiveFlow() } }
        root.addView(start, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 30 })
        root.addView(TextView(this).apply {
            text = "Pehli baar screen-capture, microphone aur floating-bubble permission maangega. Start ke baad app ko background mein rakhkar Instagram, Chrome, YouTube ya koi bhi app khol sakte ho. Panda bubble par tap = chat, 🎙️ = Hindi voice question. Gemini ka jawab Hindi voice me sunai dega."
            textSize = 14f
            setPadding(0, 25, 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    private fun startLiveFlow() {
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlay = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        requestScreenCapture()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlay && Settings.canDrawOverlays(this)) {
            waitingForOverlay = false
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService<MediaProjectionManager>() ?: return
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("MediaProjection consent flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE || resultCode != Activity.RESULT_OK || data == null) return
        val serviceIntent = Intent(this, LiveScreenService::class.java).apply {
            putExtra(LiveScreenService.EXTRA_RESULT_CODE, resultCode)
            putExtra(LiveScreenService.EXTRA_RESULT_DATA, data)
            putExtra(LiveScreenService.EXTRA_BACKEND_URL, PANDA_BACKEND)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        finish()
    }
}
