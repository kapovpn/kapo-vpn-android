/*
 * Copyright © 2026 KAPO VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * From Android 12, allowBackup="false" no longer
 * keeps app data out of a phone-to-phone transfer. kapo_prefs and KAPO.conf hold the
 * WireGuard private key, so the manifest must point at rules that exclude every domain
 * from both cloud backup and device transfer. Reads the source files (unit tests run
 * from the module directory).
 */
class DataExtractionRulesTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()

    @Test
    fun manifestDeclaresTheRules() {
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
    }

    @Test
    fun deviceTransferExcludesEveryDomain() {
        val transfer = rules.substringAfter("<device-transfer>").substringBefore("</device-transfer>")
        for (domain in listOf("root", "file", "database", "sharedpref", "external"))
            assertTrue("device-transfer must exclude $domain", transfer.contains("<exclude domain=\"$domain\" path=\".\" />"))
    }

    @Test
    fun cloudBackupExcludesEveryDomain() {
        val cloud = rules.substringAfter("<cloud-backup>").substringBefore("</cloud-backup>")
        for (domain in listOf("root", "file", "database", "sharedpref", "external"))
            assertTrue("cloud-backup must exclude $domain", cloud.contains("<exclude domain=\"$domain\" path=\".\" />"))
    }
}
