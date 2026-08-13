package com.multiassist.app

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"
        
        toolbar.setNavigationOnClickListener {
            finish()
        }

        supportFragmentManager
            .beginTransaction()
            .replace(R.id.settings_container, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            val themePref = findPreference<androidx.preference.ListPreference>("theme_override")
            themePref?.setOnPreferenceChangeListener { _, newValue ->
                when (newValue) {
                    "LIGHT" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    "DARK" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
                true
            }

            val logoutPref = findPreference<Preference>("logout")
            logoutPref?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Logout / Wipe Sessions")
                    .setMessage("Are you sure you want to clear all cookies and sessions? You will be logged out of all AIs.")
                    .setPositiveButton("Logout") { _, _ ->
                        wipeAllSessions()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            
            val keepAlivePref = findPreference<androidx.preference.SwitchPreferenceCompat>("keep_alive_enabled")
            keepAlivePref?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                if (enabled) {
                    KeepAliveService.startService(requireContext())
                } else {
                    KeepAliveService.stopService(requireContext())
                }
                true
            }

            val exportPref = findPreference<Preference>("export_backup")
            exportPref?.setOnPreferenceClickListener {
                exportBackup()
                true
            }

            val importPref = findPreference<Preference>("import_backup")
            importPref?.setOnPreferenceClickListener {
                importBackupLauncher.launch(arrayOf("*/*"))
                true
            }
        }

        private val importBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    val bytes = requireContext().contentResolver.openInputStream(uri)?.readBytes()
                    if (bytes != null) {
                        val key = SecretKeySpec("OmniAppBackup123".toByteArray(), "AES")
                        val cipher = Cipher.getInstance("AES")
                        cipher.init(Cipher.DECRYPT_MODE, key)
                        val decrypted = String(cipher.doFinal(bytes))
                        
                        val parts = decrypted.split("\n---COOKIE_SPLIT---\n")
                        if (parts.size == 2) {
                            val prefsStr = parts[0]
                            val cookiesStr = parts[1]
                            
                            // Restore cookies
                            val cookieManager = CookieManager.getInstance()
                            cookiesStr.split("\n").forEach { line ->
                                if (line.contains("=")) {
                                    val domainAndCookie = line.split("|", limit = 2)
                                    if (domainAndCookie.size == 2) {
                                        cookieManager.setCookie(domainAndCookie[0], domainAndCookie[1])
                                    }
                                }
                            }
                            cookieManager.flush()
                            Toast.makeText(context, "Backup restored successfully!", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to restore backup: invalid file or key", Toast.LENGTH_LONG).show()
                }
            }
        }

        private fun exportBackup() {
            try {
                val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                val prefsData = prefs.all.toString()
                
                // Export cookies
                val cookieManager = CookieManager.getInstance()
                val domains = listOf("https://chatgpt.com", "https://claude.ai", "https://gemini.google.com", "https://chat.deepseek.com", "https://kimi.moonshot.cn")
                val cookieData = StringBuilder()
                domains.forEach { domain ->
                    val cookie = cookieManager.getCookie(domain)
                    if (cookie != null) {
                        cookieData.append("$domain|$cookie\n")
                    }
                }
                
                val content = "$prefsData\n---COOKIE_SPLIT---\n$cookieData"
                val key = SecretKeySpec("OmniAppBackup123".toByteArray(), "AES")
                val cipher = Cipher.getInstance("AES")
                cipher.init(Cipher.ENCRYPT_MODE, key)
                val encrypted = cipher.doFinal(content.toByteArray())
                
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val backupFile = File(downloadsDir, "omni_session_backup.omni")
                backupFile.writeBytes(encrypted)
                
                Toast.makeText(context, "Encrypted backup saved to Downloads: omni_session_backup.omni", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        private fun wipeAllSessions() {
            // Clear WebStorage (Local Storage)
            WebStorage.getInstance().deleteAllData()

            // Clear Cookies
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies { success ->
                if (success) {
                    cookieManager.flush()
                    Toast.makeText(context, "All sessions wiped successfully", Toast.LENGTH_SHORT).show()
                    
                    // Restart MainActivity to reload webviews
                    val intent = Intent(requireContext(), MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    requireActivity().finish()
                } else {
                    Toast.makeText(context, "Failed to wipe sessions", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
