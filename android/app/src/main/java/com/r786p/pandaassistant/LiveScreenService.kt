package com.r786p.pandaassistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class LiveScreenService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_BACKEND_URL = "backend_url"
        private const val CHANNEL_ID = "panda_live_screen"
        private const val NOTIFICATION_ID = 7001
        private const val FRAME_INTERVAL_MS = 1200L
        private const val MAX_FRAME_WIDTH = 720
        private const val AUDIO_SAMPLE_RATE = 24000
    }

    private val httpClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private var webSocket: WebSocket? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var bubble: PandaBubbleView? = null
    private var answerView: TextView? = null
    private var questionInput: EditText? = null
    private var backendUrl = ""
    private var lastFrameAt = 0L
    private var stopped = false
    private var audioTrack: AudioTrack? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createAudioTrack()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopped) return START_NOT_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        backendUrl = intent?.getStringExtra(EXTRA_BACKEND_URL)?.trimEnd('/') ?: backendUrl
        if (resultCode == 0 || resultData == null || backendUrl.isBlank()) return START_STICKY
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        startProjection(resultCode, resultData)
        fetchEphemeralTokenAndConnect()
        return START_STICKY
    }

    private fun createAudioTrack() {
        try {
            val minBuffer = AudioTrack.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(AUDIO_SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(minBuffer, AUDIO_SAMPLE_RATE * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
        } catch (_: Exception) { audioTrack = null }
    }

    private fun playAudio(base64Audio: String) {
        try {
            val pcm = Base64.decode(base64Audio, Base64.DEFAULT)
            audioTrack?.write(pcm, 0, pcm.size)
        } catch (_: Exception) { }
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return
        mediaProjection = manager.getMediaProjection(resultCode, resultData)
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        captureThread = HandlerThread("panda-screen-capture").also { it.start() }
        captureHandler = Handler(captureThread!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
            image?.let { handleImage(it) }
        }, captureHandler)
        virtualDisplay = mediaProjection?.createVirtualDisplay("PandaAssistantLive", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, captureHandler)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, captureHandler)
        showOverlay()
    }

    private fun fetchEphemeralTokenAndConnect() {
        val request = Request.Builder().url("$backendUrl/live-token")
            .post(ByteArray(0).toRequestBody("application/json".toMediaType())).build()
        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { showAnswer("🔴 Live token error: ${e.message ?: "network error"}") }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val detail = it.body?.string().orEmpty()
                        showAnswer("🔴 Live token error: HTTP ${it.code} ${detail.take(300)}")
                        return
                    }
                    try {
                        val json = JSONObject(it.body?.string().orEmpty())
                        connectGemini(json.getString("token"), json.optString("model", "gemini-3.1-flash-live-preview"))
                    } catch (_: Exception) { showAnswer("🔴 Gemini Live token response invalid hai.") }
                }
            }
        })
    }

    private fun connectGemini(token: String, model: String) {
        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=$token"
        webSocket = httpClient.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val setupRoot = JSONObject().put("setup", JSONObject())
                val setup = setupRoot.getJSONObject("setup")
                setup.put("model", if (model.startsWith("models/")) model else "models/$model")
                setup.put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO")).put("temperature", 0.2))
                setup.put("outputAudioTranscription", JSONObject())
                val instruction = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Tum Panda Assistant ho. User ke phone screen ke live frames ko dekho. User Hindi/Hinglish me sawaal kare to concise natural Hindi/Hinglish me jawab do. Visible buttons, errors, text aur UI ko explain karo. Jawab Hindi voice me do. Passwords, OTPs, API keys aur private secrets ko repeat mat karo.")))
                setup.put("systemInstruction", instruction)
                webSocket.send(setupRoot.toString())
                showAnswer("🟢 Live connected. Screen dekh raha hoon. Panda bubble par 🎙️ dabakar bolo ya bubble tap karke chat kholo.")
            }
            override fun onMessage(webSocket: WebSocket, text: String) { parseGeminiMessage(text) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { showAnswer("🔴 Gemini Live connection error: ${t.message ?: "unknown error"}") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { showAnswer("🔴 Live connection closed.") }
        })
    }

    private fun parseGeminiMessage(raw: String) {
        try {
            val root = JSONObject(raw)
            val serverContent = root.optJSONObject("serverContent") ?: return
            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                    val audio = inlineData?.optString("data", "").orEmpty()
                    if (audio.isNotBlank()) playAudio(audio)
                    val text = part.optString("text", "")
                    if (text.isNotBlank()) showAnswer(text)
                }
            }
            val outputTranscription = serverContent.optJSONObject("outputTranscription")
            val transcript = outputTranscription?.optString("text", "").orEmpty()
            if (transcript.isNotBlank()) showAnswer(transcript)
        } catch (_: Exception) { }
    }

    private fun handleImage(image: Image) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < FRAME_INTERVAL_MS || webSocket == null) return
            lastFrameAt = now
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val paddedWidth = image.width + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            val cropped = if (paddedWidth != image.width) Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height) else bitmap
            val scaled = if (cropped.width > MAX_FRAME_WIDTH) Bitmap.createScaledBitmap(cropped, MAX_FRAME_WIDTH, cropped.height * MAX_FRAME_WIDTH / cropped.width, true) else cropped
            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 55, output)
            val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            webSocket?.send(JSONObject().put("realtimeInput", JSONObject().put("video", JSONObject().put("mimeType", "image/jpeg").put("data", encoded))).toString())
            if (scaled !== cropped) scaled.recycle()
            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()
        } catch (_: Exception) { } finally { image.close() }
    }

    private fun showOverlay() {
        if (bubble != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = PandaBubbleView(this).apply {
            setOnBubbleClickListener { togglePanel() }
            setOnMicClickListener { startVoiceInput() }
        }
        val params = WindowManager.LayoutParams(
            88, 88,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; x = 12; y = 0 }
        try { windowManager?.addView(bubble, params) } catch (_: Exception) { stopSelf() }
    }

    private fun togglePanel() {
        val current = overlayView
        if (current?.parent != null) {
            windowManager?.removeView(current)
            return
        }
        if (current == null) buildPanel()
        val panel = overlayView ?: return
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.84).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        try { windowManager?.addView(panel, params) } catch (_: Exception) { }
    }

    private fun buildPanel() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 18)
            setBackgroundColor(Color.rgb(25, 25, 28))
        }
        answerView = TextView(this).apply {
            text = "Live screen active"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(10, 10, 10, 10)
        }
        container.addView(answerView, LinearLayout.LayoutParams(-1, 0, 1f))
        questionInput = EditText(this).apply {
            hint = "Chat me sawaal likho..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setSingleLine(false)
        }
        container.addView(questionInput, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ask = Button(this).apply {
            text = "Send"
            setOnClickListener {
                val q = questionInput?.text?.toString()?.trim().orEmpty()
                if (q.isNotBlank()) { sendText(q); questionInput?.setText("") }
            }
        }
        val close = Button(this).apply {
            text = "Close"
            setOnClickListener { overlayView?.let { if (it.parent != null) windowManager?.removeView(it) } }
        }
        row.addView(ask, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(close, LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(row)
        overlayView = container
    }

    private fun startVoiceInput() {
        if (listening) {
            recognizer?.stopListening()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showAnswer("Voice input phone par available nahi hai.")
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; bubble?.setListening(true); showAnswer("🎙️ Sun raha hoon...") }
            override fun onBeginningOfSpeech() { listening = true; bubble?.setListening(true) }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false; bubble?.setListening(false) }
            override fun onError(error: Int) { listening = false; bubble?.setListening(false); showAnswer("🎙️ Voice samajh nahi aayi. Dobara panda ke 🎙️ par tap karo.") }
            override fun onResults(results: Bundle?) {
                listening = false
                bubble?.setListening(false)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) sendText(text) else showAnswer("🎙️ Kuch sunai nahi diya.")
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try { recognizer?.startListening(intent) } catch (_: Exception) { listening = false; bubble?.setListening(false) }
    }

    private fun sendText(question: String) {
        val socket = webSocket
        if (socket == null) {
            showAnswer("🔴 Live connection ready nahi hai. Panda ko restart karke dobara try karo.")
            return
        }
        val message = JSONObject()
            .put("clientContent", JSONObject()
                .put("turns", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", question)))
                ))
                .put("turnComplete", true)
            )
        if (socket.send(message.toString())) {
            showAnswer("You: $question\n\n⏳ Soch raha hoon...")
        } else {
            showAnswer("🔴 Message send nahi hua. Live connection dobara start karo.")
        }
    }

    private fun showAnswer(text: String) {
        Handler(mainLooper).post { answerView?.text = text }
    }

    private fun buildNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Panda Assistant")
        .setContentText("Screen live analysis active")
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Panda Live Screen", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        stopped = true
        recognizer?.destroy(); recognizer = null; listening = false
        webSocket?.close(1000, "User stopped Live Screen"); webSocket = null
        try { audioTrack?.stop() } catch (_: Exception) { }
        audioTrack?.release(); audioTrack = null
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        mediaProjection?.stop(); mediaProjection = null
        captureThread?.quitSafely(); captureThread = null; captureHandler = null
        try {
            overlayView?.let { if (it.parent != null) windowManager?.removeView(it) }
            bubble?.let { if (it.parent != null) windowManager?.removeView(it) }
        } catch (_: Exception) { }
        overlayView = null; bubble = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class PandaBubbleView(context: android.content.Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var listening = false
        private var onBubbleClick: (() -> Unit)? = null
        private var onMicClick: (() -> Unit)? = null
        private var downX = 0f
        private var downY = 0f

        init { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }
        fun setOnBubbleClickListener(listener: () -> Unit) { onBubbleClick = listener }
        fun setOnMicClickListener(listener: () -> Unit) { onMicClick = listener }
        fun setListening(value: Boolean) { listening = value; invalidate() }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; return true }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX; val dy = event.y - downY
                    if (dx * dx + dy * dy > 100) return true
                    val cx = width * 0.72f; val cy = height * 0.78f
                    val r = width * 0.20f
                    if ((event.x - cx) * (event.x - cx) + (event.y - cy) * (event.y - cy) <= r * r) onMicClick?.invoke()
                    else onBubbleClick?.invoke()
                    return true
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2f; val cy = h / 2f
            paint.shader = RadialGradient(cx - 10f, cy - 12f, w * .72f, intArrayOf(Color.rgb(255,255,255), Color.rgb(210,218,230), Color.rgb(125,135,150)), floatArrayOf(0f,.48f,1f), Shader.TileMode.CLAMP)
            paint.setShadowLayer(12f, 0f, 5f, Color.argb(150, 0, 0, 0))
            canvas.drawCircle(cx, cy, w * .40f, paint)
            paint.clearShadowLayer(); paint.shader = null
            paint.color = Color.rgb(35,35,38)
            canvas.drawCircle(cx - w*.22f, cy - h*.20f, w*.16f, paint)
            canvas.drawCircle(cx + w*.22f, cy - h*.20f, w*.16f, paint)
            paint.shader = RadialGradient(cx - 8f, cy - 12f, w*.38f, Color.WHITE, Color.rgb(205,210,218), Shader.TileMode.CLAMP)
            canvas.drawOval(cx - w*.30f, cy - h*.28f, cx + w*.30f, cy + h*.30f, paint)
            paint.shader = null
            paint.color = Color.rgb(30,30,32)
            canvas.drawOval(cx - w*.17f, cy - h*.04f, cx - w*.08f, cy + h*.08f, paint)
            canvas.drawOval(cx + w*.08f, cy - h*.04f, cx + w*.17f, cy + h*.08f, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(cx - w*.135f, cy - h*.005f, w*.028f, paint)
            canvas.drawCircle(cx + w*.135f, cy - h*.005f, w*.028f, paint)
            paint.color = Color.rgb(35,35,38)
            canvas.drawOval(cx - w*.06f, cy + h*.08f, cx + w*.06f, cy + h*.18f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
            canvas.drawArc(cx - w*.12f, cy + h*.10f, cx + w*.12f, cy + h*.27f, 15f, 150f, false, paint)
            paint.style = Paint.Style.FILL
            val micCx = w*.72f; val micCy = h*.78f; val micR = w*.19f
            paint.shader = LinearGradient(0f, micCy-micR, 0f, micCy+micR, if (listening) Color.rgb(244,80,105) else Color.rgb(91,91,247), Color.rgb(35,35,90), Shader.TileMode.CLAMP)
            paint.setShadowLayer(7f, 0f, 2f, Color.argb(160,0,0,0))
            canvas.drawCircle(micCx, micCy, micR, paint)
            paint.clearShadowLayer(); paint.shader = null
            paint.color = Color.WHITE
            paint.strokeWidth = 2.5f; paint.style = Paint.Style.STROKE
            canvas.drawRoundRect(micCx-w*.055f, micCy-w*.09f, micCx+w*.055f, micCy+w*.06f, w*.055f, w*.055f, paint)
            canvas.drawArc(micCx-w*.10f, micCy-w*.015f, micCx+w*.10f, micCy+w*.12f, 0f, 180f, false, paint)
            canvas.drawLine(micCx, micCy+w*.12f, micCx, micCy+w*.17f, paint)
            paint.style = Paint.Style.FILL
        }
    }
}
