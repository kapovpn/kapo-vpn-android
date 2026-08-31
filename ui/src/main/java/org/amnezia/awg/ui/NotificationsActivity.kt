package org.amnezia.awg.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.amnezia.awg.databinding.ActivityNotificationsBinding

/**
 * News and announcements. Items are hardcoded for now; when the backend
 * exists this list is one fetch away - the card builder already takes
 * (title, body, date, isNew) so only the data source changes.
 */
class NotificationsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotificationsBinding

    private data class Item(val title: String, val body: String, val date: String, val isNew: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnNotifBack.setOnClickListener { finish() }

        val items = listOf(
            Item("Welcome to the new KAPO VPN",
                "The app has a brand new look: live route map, multi-hop flight paths and a fresh connect sphere. Tell us what you think.",
                "Today", true),
            Item("Multi-hop routing is live",
                "Route your traffic through up to 3 servers: Iceland, Switzerland and the Netherlands. Switch hops right from the home screen.",
                "This week", true),
            Item("Renewal reminder",
                "You have 291 days left on your plan. Add more time any moment at kapovpn.com - crypto accepted, no account details needed.",
                "2 weeks ago", false))

        items.forEach { addCard(it) }
    }

    private fun addCard(item: Item) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val card = KapoCard(this)
        card.cardStyle = if (item.isNew) KapoCard.Style.TEAL else KapoCard.Style.NEUTRAL
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        card.layoutParams = lp

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(14), dp(16), dp(14))

        val rounded = Typeface.create("sans-serif-rounded", Typeface.BOLD)

        val topRow = LinearLayout(this)
        topRow.orientation = LinearLayout.HORIZONTAL
        topRow.gravity = Gravity.CENTER_VERTICAL

        val title = TextView(this)
        title.text = item.title
        title.setTextColor(Color.parseColor("#ECEEF2"))
        title.textSize = 14f
        title.typeface = rounded
        title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        topRow.addView(title)

        if (item.isNew) {
            val badge = TextView(this)
            badge.text = "NEW"
            badge.setTextColor(Color.parseColor("#2E1D04"))
            badge.textSize = 8f
            badge.typeface = rounded
            badge.letterSpacing = 0.12f
            badge.setPadding(dp(8), dp(3), dp(8), dp(3))
            badge.setBackgroundResource(org.amnezia.awg.R.drawable.bg_signin_ready)
            topRow.addView(badge)
        }
        col.addView(topRow)

        val body = TextView(this)
        body.text = item.body
        body.setTextColor(Color.parseColor("#B9BECC"))
        body.textSize = 12f
        body.typeface = Typeface.create("sans-serif-rounded", Typeface.NORMAL)
        body.setLineSpacing(0f, 1.35f)
        val bodyLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        bodyLp.topMargin = dp(6)
        body.layoutParams = bodyLp
        col.addView(body)

        val date = TextView(this)
        date.text = item.date.uppercase()
        date.setTextColor(Color.parseColor("#8B90A0"))
        date.textSize = 8f
        date.typeface = rounded
        date.letterSpacing = 0.14f
        val dateLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        dateLp.topMargin = dp(8)
        date.layoutParams = dateLp
        col.addView(date)

        card.addView(col)
        binding.notifList.addView(card)
    }
}
