package com.l2dchat.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun derivesHttpsManifestFromWssEndpoint() {
        assertEquals(
                "https://maimchat.example.com/maimchat/updates/manifest.json",
                AppUpdateManager.resolveManifestUrl(
                        explicitUrl = null,
                        webSocketUrl = "wss://maimchat.example.com/maibot/ws?token=ignored"
                )
        )
    }

    @Test
    fun explicitManifestWins() {
        assertEquals(
                        "https://cdn.example.com/manifest.json",
                        AppUpdateManager.resolveManifestUrl(
                                "https://cdn.example.com/manifest.json",
                                "ws://127.0.0.1:8000/ws"
                        )
                )
    }

    @Test
    fun rejectsNonWebSocketSource() {
        assertNull(AppUpdateManager.resolveManifestUrl(null, "https://example.com/ws"))
    }
}
