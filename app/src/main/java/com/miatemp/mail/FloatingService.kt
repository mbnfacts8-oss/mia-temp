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
                    isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragX; val dy = event.rawY - dragY
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) isDragging = true
                    floatParams!!.x = (initX + dx).toInt()
                    floatParams!!.y = (initY + dy).toInt()
                    wm.updateViewLayout(floatBtn, floatParams); true
                }
                MotionEvent.ACTION_UP -> { if (!isDragging) togglePanel(); true }
                else -> false
            }
        }
        wm.addView(floatBtn, floatParams)
    }

    private fun setupPanel() {
        panel = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        panelParams = WindowManager.LayoutParams(
            dpToPx(320), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 100 }

        panel.visibility = View.GONE
        tvEmail   = panel.findViewById(R.id.tv_email)
        tvTimer   = panel.findViewById(R.id.tv_timer)
        lvInbox   = panel.findViewById(R.id.lv_inbox)
        tvEmpty   = panel.findViewById(R.id.tv_empty)
        btnCopy   = panel.findViewById(R.id.btn_copy)
        btnRefresh = panel.findViewById(R.id.btn_refresh)
        btnClose  = panel.findViewById(R.id.btn_close)
        tvExpiry  = panel.findViewById(R.id.tv_expiry)

        btnCopy.setOnClickListener { copyEmail() }
        btnRefresh.setOnClickListener { refreshIdentity() }
        btnClose.setOnClickListener { stopSelf() }

        val header = panel.findViewById<View>(R.id.panel_header)
        var hDragX = 0f; var hDragY = 0f; var hInitX = 0; var hInitY = 0
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { hDragX = event.rawX; hDragY = event.rawY
                    hInitX = panelParams!!.x; hInitY = panelParams!!.y; true }
                MotionEvent.ACTION_MOVE -> { panelParams!!.x = (hInitX + event.rawX - hDragX).toInt()
                    panelParams!!.y = (hInitY + event.rawY - hDragY).toInt()
                    wm.updateViewLayout(panel, panelParams); true }
                else -> false
            }
        }
        wm.addView(panel, panelParams)
    }

    private fun togglePanel() {
        isPanelOpen = !isPanelOpen
        panel.visibility = if (isPanelOpen) View.VISIBLE else View.GONE
    }

    private fun initMail() {
        scope.launch {
            tvEmail.text = "Generating..."
            try {
                createMailAccount()
                updateEmailUI()
                startTimer()
                startPolling()
            } catch (e: Exception) {
                tvEmail.text = "Error — tap refresh"
            }
        }
    }

    private suspend fun createMailAccount() = withContext(Dispatchers.IO) {
        val domainsJson = get("$MAIL_API/domains")
        val domain = domainsJson.getAsJsonArray("hydra:member")
            .get(0).asJsonObject.get("domain").asString
        val user = "mia" + (100000..999999).random()
        val addr = "$user@$domain"
        val pass = "Mia" + (100000..999999).random() + "!x"
        post("$MAIL_API/accounts", JSONObject().apply { put("address", addr); put("password", pass) })
        val tokenResp = post("$MAIL_API/token", JSONObject().apply { put("address", addr); put("password", pass) })
        mailToken = tokenResp.get("token").asString
        mailAddress = addr
        val me = getAuth("$MAIL_API/me")
        mailAccountId = me.get("id").asString
    }

    private fun updateEmailUI() {
        tvEmail.text = mailAddress
        val prefs = getSharedPreferences("mia_prefs", MODE_PRIVATE)
        val expiry = prefs.getLong("expiry", 0L)
        if (expiry > 0) {
            val days = ((expiry - System.currentTimeMillis()) / 86400000).toInt()
            tvExpiry.text = "Key expires in $days days"
            tvExpiry.visibility = View.VISIBLE
        }
    }

    private fun startTimer() {
        timerSec = 600
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive) {
                delay(1000)
                timerSec--
                val m = timerSec / 60; val s = timerSec % 60
                tvTimer.text = String.format("%02d:%02d", m, s)
                if (timerSec <= 0) refreshIdentity()
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) { fetchMessages(); delay(5000) }
        }
    }

    private suspend fun fetchMessages() {
        if (mailToken.isEmpty()) return
        try {
            val data = withContext(Dispatchers.IO) { getAuth("$MAIL_API/messages") }
            val arr = data.getAsJsonArray("hydra:member")
            val newMsgs = mutableListOf<MailMessage>()
            var hasNew = false
            for (el in arr) {
                val obj = el.asJsonObject
                val id = obj.get("id").asString
                val from = obj.getAsJsonObject("from")
                newMsgs.add(MailMessage(
                    id = id,
                    fromName = from?.get("name")?.asString ?: "",
                    fromEmail = from?.get("address")?.asString ?: "",
                    subject = obj.get("subject")?.asString ?: "(no subject)",
                    intro = obj.get("intro")?.asString ?: ""
                ))
                if (!knownIds.contains(id)) { knownIds.add(id); hasNew = true }
            }
            messages = newMsgs
            renderInbox()
            if (hasNew && messages.size > 1) showToast("New message!")
        } catch (_: Exception) {}
    }

    private fun renderInbox() {
        if (messages.isEmpty()) {
            lvInbox.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
            lvInbox.visibility = View.VISIBLE
            val adapter = object : ArrayAdapter<MailMessage>(this, 0, messages) {
                override fun getView(pos: Int, cv: View?, parent: ViewGroup): View {
                    val v = cv ?: LayoutInflater.from(context)
                        .inflate(R.layout.item_message, parent, false)
                    val msg = getItem(pos)!!
                    v.findViewById<TextView>(R.id.tv_from).text =
                        msg.fromName.ifEmpty { msg.fromEmail }
                    v.findViewById<TextView>(R.id.tv_subject).text = msg.subject
                    v.findViewById<TextView>(R.id.tv_snippet).text = msg.intro
                    v.findViewById<TextView>(R.id.tv_avatar).text =
                        (msg.fromName.ifEmpty { msg.fromEmail }).firstOrNull()?.uppercase() ?: "?"
                    return v
                }
            }
            lvInbox.adapter = adapter
        }
    }

    private fun refreshIdentity() {
        timerJob?.cancel(); pollJob?.cancel()
        messages.clear(); knownIds.clear()
        tvEmail.text = "Generating..."
        renderInbox()
        scope.launch {
            try {
                if (mailAccountId.isNotEmpty()) withContext(Dispatchers.IO) {
                    delete("$MAIL_API/accounts/$mailAccountId")
                }
                createMailAccount()
                updateEmailUI()
                startTimer()
                startPolling()
                showToast("New identity active")
            } catch (_: Exception) { tvEmail.text = "Error — retry" }
        }
    }

    private fun copyEmail() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("email", mailAddress))
        showToast("Address copied!")
    }

    private fun showToast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun get(url: String): JsonObject {
        val req = Request.Builder().url(url).build()
        return http.newCall(req).execute().use { gson.fromJson(it.body!!.string(), JsonObject::class.java) }
    }

    private fun getAuth(url: String): JsonObject {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $mailToken").build()
        return http.newCall(req).execute().use { gson.fromJson(it.body!!.string(), JsonObject::class.java) }
    }

    private fun post(url: String, body: JSONObject): JsonObject {
        val rb = body.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(rb).build()
        return http.newCall(req).execute().use { gson.fromJson(it.body!!.string(), JsonObject::class.java) }
    }

    private fun delete(url: String) {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $mailToken").delete().build()
        http.newCall(req).execute().close()
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}

data class MailMessage(
    val id: String,
    val fromName: String,
    val fromEmail: String,
    val subject: String,
    val intro: String
)
