package com.l2dchat.live2d

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class Live2DViewTransformTest {
    @Test
    fun matrixRoundTripKeepsUserFacingValues() {
        val transform = Live2DViewTransform(scale = 1.4f, offsetX = -0.25f, offsetY = 0.75f)

        assertEquals(transform, Live2DViewTransform.fromMatrix(transform.toMatrix()))
    }

    @Test
    fun transformKeysAreStableAndOrientationSpecific() {
        val modelPath = "Hiyori"
        val portrait =
                Live2DModelLifecycleManager.buildTransformContextKey(
                        modelPath,
                        Configuration.ORIENTATION_PORTRAIT
                )
        val landscape =
                Live2DModelLifecycleManager.buildTransformContextKey(
                        modelPath,
                        Configuration.ORIENTATION_LANDSCAPE
                )

        assertEquals(
                portrait,
                Live2DModelLifecycleManager.buildTransformContextKey(
                        modelPath,
                        Configuration.ORIENTATION_PORTRAIT
                )
        )
        assertNotEquals(portrait, landscape)
    }
}
