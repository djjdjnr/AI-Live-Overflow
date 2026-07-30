package com.example.deskpet.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.BatteryManager
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat
import java.io.File

class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: WebView? = null
    private var params: WindowManager.LayoutParams? = null

    companion object {
        private const val CHANNEL_ID = "cat_pet_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PET_SIZE_DP = 180
        private const val PET_HEIGHT_DP = 240
    }

    private val notificationTexts = listOf(
        "meow~ thinking of you",
        "whatcha looking at?",
        "so bored... tap me!",
        "you havent touched me in ages",
        "oh? watching tiktok again?"
    )

    private var notifIndex = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastAiState = ""
    private var lastBatteryPct = -1
    private var lastCharging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Cat is on screen~"))
        setupOverlay()
        startNotificationRotation()
        startStatePoller()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        params = WindowManager.LayoutParams(
            dpToPx(PET_SIZE_DP),
            dpToPx(PET_HEIGHT_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(20)
            y = dpToPx(100)
        }

        overlayView = WebView(this).apply {
            setBackgroundColor(0x00000000)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                mediaPlaybackRequiresUserGesture = false
            }
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/pet.html")
            setOnTouchListener(createTouchListener())
        }

        windowManager?.addView(overlayView, params)
    }

    // === AI State Polling (read from file) ===

    private fun startStatePoller() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    checkAiStateFile()
                    checkBattery()
                } catch (_: Exception) {}
                mainHandler.postDelayed(this, 3000)
            }
        }, 3000)
    }

    private fun checkAiStateFile() {
        val file = File("/sdcard/Operit/catpet_state.json")
        if (!file.exists()) return
        val content = file.readText().trim()
        if (content.isEmpty() || content == lastAiState) return
        lastAiState = content

        try {
            val json = org.json.JSONObject(content)
            val state = json.optString("state", "")
            val text = json.optString("text", "")
            val app = json.optString("app", "")

            if (state.isNotEmpty()) {
                callJs("window.petEngine.setStateFromAI('$state', '${escapeJs(text)}')")
            } else if (text.isNotEmpty()) {
                callJs("window.petEngine.showNotification('${escapeJs(text)}')")
            }
            if (app.isNotEmpty()) {
                callJs("window.petEngine.showNotification('${escapeJs(getAppReaction(app))}')")
            }
        } catch (_: Exception) {}
    }

    private fun checkBattery() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING

        if (pct != lastBatteryPct || charging != lastCharging) {
            lastBatteryPct = pct
            lastCharging = charging
            if (charging) {
                if (pct >= 100) callJs("window.petEngine.showNotification('充满啦！')")
                else callJs("window.petEngine.showNotification('在充电呢~ $pct%')")
            } else if (pct <= 20) {
                callJs("window.petEngine.showNotification('快没电了… $pct%')")
            } else if (pct <= 50) {
                callJs("window.petEngine.showNotification('电量$pct%了哦')")
            }
        }
    }

    private fun getAppReaction(pkg: String): String {
        return when (pkg) {
            "com.ss.android.ugc.aweme" -> "又在刷抖音！"
            "com.tencent.mobileqq" -> "在和谁聊天呢"
            "com.tencent.mm" -> "在和谁聊天呢"
            "com.xingin.xhs" -> "在看什么好东西"
            "com.netease.cloudmusic" -> "听歌呢~"
            "com.quark.browser" -> "在上网呀"
            "com.example.deskpet" -> "喂？你在看我？"
            "com.ai.assistance.operit" -> "在和哥哥聊天呀"
            else -> {
                val name = pkg.substringAfterLast('.')
                "在看$name"
            }
        }
    }

    private fun escapeJs(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    // === GESTURE HANDLING ===

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var lastTapTime = 0L
    private var touchStartTime = 0L
    private var hasMoved = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private fun createTouchListener(): View.OnTouchListener {
        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        hasMoved = true
                        params?.x = initialX + dx
                        params?.y = initialY + dy
                        windowManager?.updateViewLayout(overlayView, params)
                    }
                    lastTouchX = event.rawX
                    lastTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!hasMoved) {
                        when {
                            elapsed > 600 -> callJs("window.petEngine.onLongPress()")
                            System.currentTimeMillis() - lastTapTime < 300 -> {
                                callJs("window.petEngine.onDoubleTap()")
                                lastTapTime = 0
                            }
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                callJs("window.petEngine.onTap()")
                            }
                        }
                    } else {
                        val dx = (lastTouchX - initialTouchX).toInt()
                        val dy = (lastTouchY - initialTouchY).toInt()
                        val velocity = Math.sqrt((dx * dx + dy * dy).toDouble())
                        if (velocity > 200 && elapsed < 400) {
                            callJs("window.petEngine.onFling($dx, $dy)")
                        } else {
                            callJs("window.petEngine.onDragEnd()")
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun callJs(script: String) {
        mainHandler.post {
            overlayView?.evaluateJavascript(script, null)
        }
    }

    // === NOTIFICATION ROTATION ===

    private fun startNotificationRotation() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    val notification = NotificationCompat.Builder(this@OverlayService, CHANNEL_ID)
                        .setContentTitle("CatPet")
                        .setContentText(notificationTexts[notifIndex % notificationTexts.size])
                        .setSmallIcon(android.R.drawable.ic_menu_compass)
                        .setOngoing(true)
                        .setSilent(true)
                        .build()
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(NOTIFICATION_ID, notification)
                    notifIndex++
                } catch (_: Exception) {}
                mainHandler.postDelayed(this, 3600000)
            }
        }, 3600000)
    }

    // === NOTIFICATION ===

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CatPet")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cat",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    // === UTILS ===

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        overlayView?.let {
            windowManager?.removeView(it)
            it.destroy()
        }
        overlayView = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}