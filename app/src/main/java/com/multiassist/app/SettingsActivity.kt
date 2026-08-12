package com.multiassist.app

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

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
