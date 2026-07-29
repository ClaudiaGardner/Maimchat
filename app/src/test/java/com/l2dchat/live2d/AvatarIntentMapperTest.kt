package com.l2dchat.live2d

import com.l2dchat.chat.AvatarAction
import com.l2dchat.chat.AvatarEmotion
import com.l2dchat.chat.AvatarIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarIntentMapperTest {
    @Test
    fun mapsQualifiedActionAndExplicitMotionParameters() {
        val plan =
                AvatarIntentMapper.map(
                        AvatarIntent(
                                emotion = AvatarEmotion("happy", 0.8f),
                                action =
                                        AvatarAction(
                                                name = "android.wave",
                                                parameters =
                                                        mapOf(
                                                                "group" to "CustomWave",
                                                                "index" to "2",
                                                                "loop" to "true"
                                                        )
                                        )
                        )
                )

        assertEquals("CustomWave", plan.motionCandidates.first())
        assertTrue(plan.motionCandidates.contains("Wave"))
        assertEquals(2, plan.motionIndex)
        assertTrue(plan.loop)
        assertEquals("Happy", plan.expressionCandidates.first())
    }

    @Test
    fun fallsBackToGroupsAvailableInOfficialDemoModels() {
        val plan =
                AvatarIntentMapper.map(
                        AvatarIntent(emotion = AvatarEmotion("surprised"))
                )

        assertTrue(plan.motionCandidates.contains("TapBody"))
        assertTrue(plan.motionCandidates.contains("Idle"))
        assertTrue(plan.expressionCandidates.contains("Surprise"))
    }
}
