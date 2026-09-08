/*
 * Copyright © 2026 KAPO VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.activity

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.amnezia.awg.R
import org.amnezia.awg.databinding.ActivitySelfTestBinding
import org.amnezia.awg.databinding.SelfTestRowBinding
import org.amnezia.awg.util.SelfTestRunner

/**
 * "Verify my protection" - runs [SelfTestRunner]'s passive checks and shows
 * the results. Reachable from Settings.
 */
class SelfTestActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySelfTestBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelfTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.self_test_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.selfTestRunAgain.setOnClickListener { runChecks() }
        runChecks()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun runChecks() {
        binding.selfTestRunAgain.isEnabled = false
        binding.selfTestProgress.visibility = View.VISIBLE
        binding.selfTestResults.removeAllViews()

        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) { SelfTestRunner.runAll() }
            binding.selfTestProgress.visibility = View.GONE
            binding.selfTestRunAgain.isEnabled = true
            val inflater = LayoutInflater.from(this@SelfTestActivity)
            for (result in results) {
                val row = SelfTestRowBinding.inflate(inflater, binding.selfTestResults, true)
                row.selfTestRowTitle.text = result.title
                row.selfTestRowDetail.text = result.detail
                val colorRes = if (result.passed) {
                    row.selfTestRowIcon.setImageResource(R.drawable.ic_check)
                    R.color.tunnel_status_connected
                } else {
                    row.selfTestRowIcon.setImageResource(R.drawable.ic_cross)
                    R.color.tunnel_status_disconnected
                }
                ImageViewCompat.setImageTintList(
                    row.selfTestRowIcon,
                    ColorStateList.valueOf(ResourcesCompat.getColor(resources, colorRes, theme))
                )
            }
        }
    }
}
