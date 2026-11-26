package com.miatemp.mail

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.*

class LoginActivity : AppCompatActivity() {

    private lateinit var etKey: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTelegram: TextView

    private val db = FirebaseDatabase.getInstance("https://mia-temp-default-rtdb.firebaseio.com")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etKey       = findViewById(R.id.et_key)
        btnLogin    = findViewById(R.id.btn_login)
        tvStatus    = findViewById(R.id.tv_status)
        progressBar = findViewById(R.id.progress_bar)
        tvTelegram  = findViewById(R.id.tv_telegram)

        btnLogin.setOnClickListener { attemptLogin() }
        tvTelegram.setOnClickListener {
            val intent = Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://t.me/YourSupportBot"))
            startActivity(intent)
        }
    }

    private fun attemptLogin() {
        val key = etKey.text.toString().trim()
        if (key.isEmpty()) {
            showStatus("Enter your access key", true)
            return
        }
        setLoading(true)
        val hwid = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)

        db.getReference("users").child(key)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        setLoading(false)
                        showStatus("Key not found", true)
                        return
                    }
                    val expiry  = snapshot.child("expiry").getValue(Long::class.java) ?: 0L
                    val status  = snapshot.child("status").getValue(String::class.java) ?: "active"
                    val storedHwid = snapshot.child("hwid").getValue(String::class.java) ?: ""

                    if (status == "blocked") {
                        setLoading(false)
                        showStatus("This key has been blocked", true)
                        return
                    }
                    if (expiry > 0 && expiry < System.currentTimeMillis()) {
                        setLoading(false)
                        showStatus("This key has expired", true)
                        return
                    }
                    if (storedHwid.isNotEmpty() && storedHwid != hwid) {
                        setLoading(false)
                        showStatus("Key already used on another device", true)
                        return
                    }
                    if (storedHwid.isEmpty()) {
                        db.getReference("users").child(key).child("hwid").setValue(hwid)
                    }
                    getSharedPreferences("mia_prefs", MODE_PRIVATE).edit()
                        .putString("saved_key", key)
                        .putString("saved_hwid", hwid)
                        .putLong("expiry", expiry)
                        .apply()

                    setLoading(false)
                    showStatus("Access granted!", false)
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                }
                override fun onCancelled(error: DatabaseError) {
                    setLoading(false)
                    showStatus("Connection failed", true)
                }
            })
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !loading
        btnLogin.text = if (loading) "Verifying..." else "Login"
    }

    private fun showStatus(msg: String, isError: Boolean) {
        tvStatus.text = msg
        tvStatus.visibility = View.VISIBLE
        tvStatus.setTextColor(if (isError) getColor(R.color.red) else getColor(R.color.green))
    }
}
