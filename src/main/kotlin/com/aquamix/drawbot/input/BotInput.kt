package com.aquamix.drawbot.input

/**
 * Enum representing all controllable player inputs.
 * Based on Baritone's Input enum pattern.
 */
enum class BotInput {
    /** Move forward */
    MOVE_FORWARD,
    /** Move backward */
    MOVE_BACK,
    /** Strafe left */
    MOVE_LEFT,
    /** Strafe right */
    MOVE_RIGHT,
    /** Jump / Fly up */
    JUMP,
    /** Sneak / Fly down */
    SNEAK,
    /** Sprint */
    SPRINT,
    /** Left click (attack/break) */
    CLICK_LEFT,
    /** Right click (use/place) */
    CLICK_RIGHT,
    /** Attack (Hold Left Click) */
    ATTACK,
    /** Use (Hold Right Click) */
    USE
}
