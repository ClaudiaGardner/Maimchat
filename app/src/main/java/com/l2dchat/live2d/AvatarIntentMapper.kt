package com.l2dchat.live2d

import com.l2dchat.chat.AvatarIntent

data class AvatarRenderPlan(
        val expressionCandidates: List<String>,
        val motionCandidates: List<String>,
        val motionIndex: Int,
        val loop: Boolean
)

/**
 * Converts renderer-neutral Amaidesu-style intents into names commonly used by Live2D models.
 * The lifecycle manager selects the first candidate that actually exists in the active model.
 */
object AvatarIntentMapper {
    fun map(intent: AvatarIntent): AvatarRenderPlan {
        val parameters = intent.action?.parameters.orEmpty()
        val explicitExpression = parameters.value("expression", "expression_name")
        val explicitGroup = parameters.value("group", "motion_group")
        val actionName = intent.action?.name?.substringAfterLast('.')?.trim().orEmpty()
        val actionKey = actionName.lowercase()
        val emotionName = intent.emotion?.name?.substringAfterLast('.')?.trim().orEmpty()
        val emotionKey = emotionName.lowercase()

        val expressionCandidates =
                buildList {
                            explicitExpression?.let(::add)
                            addAll(expressionAliases(emotionKey))
                            if (emotionName.isNotBlank()) add(emotionName)
                        }
                        .distinctNames()

        val actionCandidates =
                buildList {
                            explicitGroup?.let(::add)
                            addAll(motionAliases(actionKey))
                            if (actionName.isNotBlank() && actionKey != "motion") add(actionName)
                        }
                        .distinctNames()
        val emotionCandidates =
                if (actionCandidates.isEmpty()) motionAliases(emotionKey) else emptyList()

        return AvatarRenderPlan(
                expressionCandidates = expressionCandidates,
                motionCandidates =
                        (actionCandidates + emotionCandidates + DEFAULT_MOTION_FALLBACKS)
                                .distinctNames(),
                motionIndex =
                        parameters.value("index", "motion_index")
                                ?.toIntOrNull()
                                ?.coerceIn(0, 999)
                                ?: 0,
                loop =
                        parameters.value("loop", "repeat")
                                ?.equals("true", ignoreCase = true)
                                ?: false
        )
    }

    private fun expressionAliases(key: String): List<String> =
            when (key) {
                "happy", "joy", "joyful", "excited", "smile", "开心", "高兴" ->
                        listOf("Happy", "Smile", "Joy")
                "sad", "sorrow", "upset", "难过", "伤心" ->
                        listOf("Sad", "Sorrow")
                "angry", "mad", "annoyed", "生气" ->
                        listOf("Angry", "Mad")
                "surprised", "surprise", "shocked", "惊讶" ->
                        listOf("Surprise", "Surprised")
                "thinking", "think", "confused", "思考" ->
                        listOf("Think", "Thinking", "Confused")
                "shy" -> listOf("Shy", "Blush")
                "love" -> listOf("Love", "Heart", "Happy")
                "scared" -> listOf("Scared", "Fear", "Surprise")
                "relaxed" -> listOf("Relaxed", "Neutral", "Normal")
                "sleepy", "sleep", "tired", "困倦" ->
                        listOf("Sleep", "Sleepy", "Tired")
                "neutral", "normal", "平静" ->
                        listOf("Neutral", "Normal")
                else -> emptyList()
            }

    private fun motionAliases(key: String): List<String> =
            when (key) {
                "wave", "greet", "greeting", "hello", "挥手", "打招呼" ->
                        listOf("Wave", "Greeting", "TapBody")
                "nod", "agree", "yes", "点头" ->
                        listOf("Nod", "Yes", "TapBody")
                "shake", "disagree", "no", "摇头" ->
                        listOf("Shake", "No", "TapBody")
                "dance", "跳舞" ->
                        listOf("Dance", "Happy", "TapBody")
                "happy", "joy", "joyful", "excited", "smile", "laugh", "开心", "高兴" ->
                        listOf("Happy", "Smile", "TapBody")
                "sad", "sorrow", "upset", "cry", "难过", "伤心" ->
                        listOf("Sad", "Sorrow", "TapBody")
                "angry", "mad", "annoyed", "生气" ->
                        listOf("Angry", "Mad", "TapBody")
                "surprised", "surprise", "shocked", "惊讶" ->
                        listOf("Surprise", "TapBody")
                "thinking", "think", "confused", "思考" ->
                        listOf("Think", "Thinking")
                "shy" -> listOf("Shy", "Blush", "TapBody")
                "love" -> listOf("Love", "Heart", "Happy", "TapBody")
                "scared" -> listOf("Scared", "Fear", "Surprise", "TapBody")
                "relaxed" -> listOf("Relaxed", "Idle")
                "sleepy", "sleep", "tired", "困倦" ->
                        listOf("Sleep")
                "idle", "neutral", "normal", "平静" ->
                        listOf("Idle")
                "motion", "" -> emptyList()
                else -> emptyList()
            }

    private fun Map<String, String>.value(vararg keys: String): String? {
        for (key in keys) {
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
                    ?.value
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
        }
        return null
    }

    private fun List<String>.distinctNames(): List<String> {
        val seen = mutableSetOf<String>()
        return filter { it.isNotBlank() && seen.add(it.lowercase()) }
    }

    private val DEFAULT_MOTION_FALLBACKS = listOf("TapBody", "Idle")
}
