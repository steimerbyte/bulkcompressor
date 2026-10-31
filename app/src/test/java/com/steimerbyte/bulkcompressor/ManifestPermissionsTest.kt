package com.steimerbyte.bulkcompressor

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Prueft am zusammengefuehrten Manifest, was der Dienst zur Laufzeit
 * voraussetzt. In 1.0.0 und 1.1.0 fehlte `WAKE_LOCK`: der erste
 * Kompressionslauf warf eine SecurityException aus `onStartCommand` und
 * nahm den Prozess mit. Der Fehler ist von aussen nur am Geraet sichtbar,
 * deshalb steht die Berechtigung hier als Test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ManifestPermissionsTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun requestedPermissions(): Array<String> {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS
        )
        return info.requestedPermissions ?: emptyArray()
    }

    @Test
    fun `wake lock ist deklariert`() {
        val permissions = requestedPermissions().toList()
        assertTrue(
            "WAKE_LOCK fehlt im Manifest - jeder Lauf stirbt mit SecurityException. " +
                "Deklariert: $permissions",
            permissions.contains(android.Manifest.permission.WAKE_LOCK)
        )
    }

    @Test
    fun `vordergrunddienst hat den passenden typ und die passende berechtigung`() {
        val permissions = requestedPermissions().toList()
        assertTrue(
            "FOREGROUND_SERVICE_DATA_SYNC fehlt: startForeground(type=dataSync) wirft " +
                "ab Android 14 eine SecurityException",
            permissions.contains(android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
        )

        val component = ComponentName(context, com.steimerbyte.bulkcompressor.job.CompressionService::class.java)
        val info: ServiceInfo = context.packageManager.getServiceInfo(component, 0)
        assertNotNull("Dienst ist nicht im Manifest registriert", info)
        assertEquals(
            "foregroundServiceType muss dataSync sein - sonst passt die Berechtigung nicht",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            info.foregroundServiceType
        )
    }
}
