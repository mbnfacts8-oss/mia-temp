package com.miatemp.mail

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var floatBtn: View
    private lateinit var panel: View
    private var floatParams: WindowManager.LayoutParams? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var isPanelOpen = false

    private lateinit var tvEmail: TextView
    private lateinit var tvTimer: TextView
    private lateinit var lvInbox: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var btnCopy: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnClose: Button
    private lateinit var tvExpiry: TextView

    private val http = OkHttpClient()
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mailToken = ""
    private var mailAddress = ""
    private var mailAccountId = ""
    private var messages = mutableListOf<MailMessage>()
    private var knownIds = mutableSetOf<String>()
    private var timerSec = 600
    private var timerJob: Job? = null
    private var pollJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    private var dragX = 0f; private var dragY = 0f
    private var initX = 0; private var initY = 0
    private var isDragging = false

    companion object {
        const val CHANNEL_ID = "mia_float_channel"
        const val NOTIF_ID = 101
        const val MAIL_API = "https://api.mail.tm"
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupFloatButton()
        setupPanel()
        initMail()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { wm.removeView(floatBtn) } catch (_: Exception) {}
        try { wm.removeView(panel) } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Mia Temp Floating",
            NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mia Temp Active")
            .setContentText("Floating inbox running")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun setupFloatButton() {
        floatBtn = LayoutInflater.from(this).inflate(R.layout.overlay_float_btn, null)
        floatParams = WindowManager.LayoutParams(
            dpToPx(60), dpToPx(60),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 300 }

        floatBtn.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragX = event.rawX; dragY = event.rawY
                    initX = floatParams!!.x; initY = floatParams!!.y
