package com.example.deskpet.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.core.app.NotificationCompat

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Cat is on screen~"))
        setupOverlay()
        startNotificationRotation()
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
                            elapsed > 600 -> callJs("window.petEngine && window.petEngine.onLongPress()")
                            System.currentTimeMillis() - lastTapTime < 300 -> {
                                callJs("window.petEngine && window.petEngine.onDoubleTap()")
                                lastTapTime = 0
                            }
                            else -> {
                                lastTapTime = System.currentTimeMillis()
                                callJs("window.petEngine && window.petEngine.onTap()")
                            }
                        }
                    } else {
                        val dx = (lastTouchX - initialTouchX).toInt()
                        val dy = (lastTouchY - initialTouchY).toInt()
                        val velocity = Math.sqrt((dx * dx + dy * dy).toDouble())
                        if (velocity > 200 && elapsed < 400) {
                            callJs("window.petEngine && window.petEngine.onFling($dx, $dy)")
                        } else {
                            callJs("window.petEngine && window.petEngine.onDragEnd()")
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
