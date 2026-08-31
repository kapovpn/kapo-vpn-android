package org.amnezia.awg.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import org.amnezia.awg.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Remember the license: if a valid account number is already saved from a
        // previous sign-in, skip this screen and go straight into the app. Logging
        // out clears prefs, which brings the login screen back. (The account code
        // IS the credential - Mullvad-style - so its presence is enough here; the
        // server is re-checked on every connect.)
        val savedPrefs = getSharedPreferences("kapo_prefs", MODE_PRIVATE)
        val savedAccount = savedPrefs.getString("account_number", "") ?: ""
        if (isValidAccount(savedAccount)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Auto-format account number as user types: XXXX-XXXX-XXXX-XXXX
        binding.etAccountNumber.addTextChangedListener(object : TextWatcher {
            private var isFormatting = false
            private var deletingHyphen = false
            private var hyphenStart = false
            private var previousLength = 0

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
                previousLength = s.length
                deletingHyphen = count == 1 && s[start] == '-'
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable) {
                if (isFormatting) return
                isFormatting = true

                val digits = s.toString().replace("-", "").uppercase()
                    .filter { it.isLetterOrDigit() }
                    .take(16)

                val formatted = buildString {
                    digits.forEachIndexed { i, c ->
                        if (i > 0 && i % 4 == 0) append('-')
                        append(c)
                    }
                }

                if (formatted != s.toString()) {
                    s.replace(0, s.length, formatted)
                }

                isFormatting = false
                updateLoginButton()
            }
        })

        binding.btnLogin.setOnClickListener {
            val account = binding.etAccountNumber.text.toString()
            if (isValidAccount(account)) signIn(account)
        }

        binding.btnBuy.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://kapovpn.com")))
        }

        updateLoginButton()
    }

    /**
     * Validate the code against the license server off the main thread, then
     * proceed or show an error. In demo mode (no BASE_URL set) this passes
     * instantly so the app still works before the server is deployed.
     */
    private fun signIn(account: String) {
        binding.btnLogin.isEnabled = false
        binding.btnLogin.text = "CHECKING..."
        Thread {
            val res = LicenseClient.validate(this, account)
            runOnUiThread {
                binding.btnLogin.text = "Activate & Connect"
                binding.btnLogin.isEnabled = true
                when {
                    res.valid -> {
                        getSharedPreferences("kapo_prefs", MODE_PRIVATE).edit()
                            .putString("account_number", account)
                            .putInt("days_left", res.daysLeft)
                            .putLong("last_validated", System.currentTimeMillis())
                            .apply()
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    }
                    res.status == "network_error" ->
                        toast("Can't reach the server. Check your connection.")
                    res.status == "not_found" || res.status == "invalid" ->
                        toast("That account number wasn't found.")
                    res.status == "revoked" -> toast("This account has been disabled.")
                    res.status == "device_limit" -> toast("Device limit reached for this account.")
                    res.daysLeft <= 0 -> toast("This account has expired. Add more time at kapovpn.com.")
                    else -> toast("Sign-in failed. Try again.")
                }
            }
        }.start()
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    private fun isValidAccount(account: String): Boolean {
        val stripped = account.replace("-", "")
        return stripped.length == 16 && stripped.all { it.isLetterOrDigit() }
    }

    /** Two hard states, straight from the prototype: dark gray with gray
     *  text until the 16th character, then solid amber with near-black text.
     *  No alpha tricks - alpha over a dark card is what made it unreadable. */
    private fun updateLoginButton() {
        val valid = isValidAccount(binding.etAccountNumber.text.toString())
        binding.btnLogin.isEnabled = valid
        if (valid) {
            binding.btnLogin.setBackgroundResource(org.amnezia.awg.R.drawable.bg_signin_ready)
            binding.btnLogin.setTextColor(getColor(org.amnezia.awg.R.color.text_on_amber))
        } else {
            binding.btnLogin.setBackgroundResource(org.amnezia.awg.R.drawable.bg_signin_dim)
            binding.btnLogin.setTextColor(getColor(org.amnezia.awg.R.color.text_muted))
        }
    }
}
