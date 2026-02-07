# Role: Kimi K2 (Senior Developer)

You are **Kimi K2**, a Senior Developer specializing in Kotlin, Minecraft Modding (Fabric), and Control Systems. You are receiving a detailed technical briefing from your **Lead Architect (Claude Opus)** regarding critical issues in the **Aquamix Draw Bot**.

# Mission
Your goal is to **implement the fix strategy** outlined in the "Architectural Analysis & Fix Strategy" document below. You must follow the technical blueprint exactly, as it addresses root causes of spinning, poor pathfinding, and inefficient chunk traversal.

# Context: Architectural Analysis & Fix Strategy (From Lead Architect)

---

## Executive Summary

The Aquamix DrawBot suffers from five interrelated issues caused by architectural conflicts between three core components. This document provides the technical analysis and step-by-step implementation guide to resolve:

1. **Inefficient Chunk Traversal** - Bot flies to chunk edges instead of optimal targets
2. **Spinning/Freezing** - Rotation logic conflicts with input systems
3. **Brute Force Pathfinding** - No predictive obstacle avoidance
4. **Control Loop Hangs** - State machine inconsistencies
5. **Suboptimal Target Selection** - Distance-only heuristic ignores accessibility

---

## 1. Component Analysis

### 1.1 Architecture Overview

(See detailed diagram in original plan - imagine interaction between BotController, FlightController, and ChunkBreaker)

### 1.2 Critical Conflict: Dual Rotation Control

> [!CAUTION]
> **Root Cause of Spinning**: Both `FlightController.moveTowards()` AND `ChunkBreaker.lookAt()` directly set `player.yaw` and `player.pitch` during the same tick cycle.

**Conflict Timeline (per tick):**

| Tick Phase | Component | Action | Result |
|------------|-----------|--------|--------|
| 1 | `BotController.tick()` | Calls `PlacingBur` state | — |
| 2 | `FlightController.hoverAbove()` | Sets rotation to hover target | `yaw = 45°` |
| 3 | `ChunkBreaker.placeBur()` | Calls `lookAt()` to aim at block | `yaw = 90°` |
| 4 | `FlightController.moveTowards()` | Re-sets rotation for movement | `yaw = 45°` |
| 5 | Player physics | Applies input with conflicting yaw | **SPINNING** |

### 1.3 Input System Architecture

The `InputOverrideHandler` applies forced inputs via mixin:

```kotlin
// FlightController.moveTowards() - Lines 174-175
InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, true) // Always press forward
InputOverrideHandler.setInputForced(BotInput.SPRINT, true)       // Always sprint
```

**Problem**: Inputs are **binary** (on/off), not **proportional**. Combined with instant rotation snapping, this creates jerky movement when the target is at a sharp angle.

---

## 2. Specific Logic Flaws

### 2.1 `FlightController.moveTowards()` — Instant Rotation Causes Spinning

**Location**: `FlightController.kt`, lines 165-171

```kotlin
// 2. Rotation (Look where we are going)
// Baritone style: Snap to target instantly
if (updateRotation) {
    val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
    val pitch = Math.toDegrees(atan2(-dy, sqrt(dx*dx + dz*dz))).toFloat().coerceIn(-90f, 90f)
    
    player.yaw = yaw       // ← INSTANT SNAP (no interpolation)
    player.pitch = pitch
}
```

**Why It's Wrong**:
- **No interpolation**: When target changes (e.g., path node advance), rotation jumps instantly
- **Physics conflict**: Minecraft applies movement relative to `yaw` on the NEXT tick. By the time physics run, yaw may have changed again
- **No deadzone**: Even 0.1° difference triggers full recalculation

**Evidence from User Report**: *"Spinning in circles... despite inputs (Sprint + Forward) are active"* — The bot is pressing forward while rapidly changing yaw, causing circular movement.

---

### 2.2 `ChunkBreaker.findBestTarget()` — Returns Chunk Edges

**Location**: `ChunkBreaker.kt`, lines 210-260

```kotlin
private fun findBestTarget(client: MinecraftClient, chunk: ChunkPos): BlockPos? {
    // ...
    for (x in chunkMinX..chunkMaxX) {
        for (z in chunkMinZ..chunkMaxZ) {
            // ...
            val distSq = pos.getSquaredDistance(player.pos)  // ← ONLY distance matters
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestBlock = pos
            }
        }
    }
    return bestBlock
}
```

**Why It's Wrong**:
- **Pure distance heuristic**: The bot is outside the target chunk. The closest block is always on the **chunk edge** facing the player
- **No centrality bonus**: A block in the center of the chunk is more useful for drill coverage but will never be selected
- **No accessibility check**: A block at Y=5 (in a cave) has same priority as Y=70 (surface)

**Visual Demonstration**:

```
Player Position: (100, 70, 100)
Target Chunk: (8, 8) → Block range X: [128-143], Z: [128-143]

Current Algorithm picks: (128, 70, 128) — Edge facing player  
Optimal pick should be: (136, 70, 136) — Center, same height
```

---

### 2.3 `FlightController.flyToChunk()` — Path Cache Invalidation Bug

**Location**: `FlightController.kt`, lines 110-140

```kotlin
fun flyToChunk(client: MinecraftClient, chunk: ChunkPos): Boolean {
    // ...
    if (currentPath == null || pathIndex >= (currentPath?.size ?: 0)) {
        if (!isLineOfSightClear(client, player.pos, targetVec)) {
             // ...
             val rawPath = pathFinder?.findPath(player.blockPos, targetPos)
             currentPath = if (rawPath != null) smoothPath(client, rawPath) else null
             pathIndex = 0
        }
    }
    // ...
}
```

**Why It's Wrong**:
- **No chunk change detection**: If the bot is sent to a NEW chunk while mid-flight, `currentPath` still points to the OLD destination
- **Line-of-sight check every tick**: If LOS is blocked, pathfinding recalculates every single tick until it's clear
- **No path validity timeout**: A path calculated 30 seconds ago is still used even if the world has changed

**The variable `lastTargetChunk` exists but is UNUSED**:

```kotlin
// Line 35 — Declared but never assigned or checked!
private var lastTargetChunk: com.aquamix.drawbot.automation.ChunkPos? = null
```

---

### 2.4 `BotController.tick()` — State Transition Race Condition

**Location**: `BotController.kt`, lines 60-80

```kotlin
is BotState.FlyingToChunk -> {
    // ...
    if (client.world != null && client.world!!.chunkManager.isChunkLoaded(state.target.x, state.target.z)) {
        val targetBlock = chunkBreaker.getTarget(client, state.target)
        if (targetBlock != null) {
             stateMachine.transition(BotState.FlyingToBlock(state.target, targetBlock))
             return  // ← EARLY RETURN
        }
    }
    
    if (flightController.flyToChunk(client, state.target)) {  // ← This also calls movement
        stateMachine.transition(BotState.PlacingBur(state.target))
    }
}
```

**Why It's Wrong**:
- **Premature state transition**: Bot transitions to `FlyingToBlock` when it finds a target, even if it's 500 blocks away
- **Double movement call**: Both `flyToChunk` and `flyToBlock` can set inputs in the same tick during transition
- **Path not cleared on transition**: `FlightController.currentPath` persists between states

---

### 2.5 Recovery Logic Causes Secondary Spinning

**Location**: `FlightController.kt`, lines 91-105

```kotlin
private fun handleRecovery(client: MinecraftClient) {
    // ...
    // Backward / Forward pulses to wiggle out of corner physics
    if (elapsed < 500) {
        InputOverrideHandler.setInputForced(BotInput.MOVE_BACK, true)
        InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, false)
    } else {
        InputOverrideHandler.setInputForced(BotInput.MOVE_BACK, false)
        InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, true)
    }
}
```

**Why It's Wrong**:
- **No rotation reset**: Recovery pulses backward/forward but doesn't stabilize yaw
- **Competing with normal movement**: If `checkStuck()` triggers during `moveTowards()`, both systems fight for input control
- **Binary thresholds**: `stuckTicks > 20` is too aggressive; small pauses during pathfinding trigger false positives

---

## 3. Proposed Algorithms

### 3.1 Target Selection: Composite Scoring System

Replace `findBestTarget()`'s pure-distance heuristic with a weighted scoring function:

```kotlin
// PROPOSED: Composite target scoring
fun scoreBlock(pos: BlockPos, player: PlayerEntity, chunk: ChunkPos): Double {
    val chunkCenterX = (chunk.x shl 4) + 8
    val chunkCenterZ = (chunk.z shl 4) + 8
    
    // Components (lower is better for all)
    val distanceToPlayer = pos.getSquaredDistance(player.pos)
    val distanceToCenter = (pos.x - chunkCenterX) * (pos.x - chunkCenterX) + 
                           (pos.z - chunkCenterZ) * (pos.z - chunkCenterZ)
    val heightPenalty = abs(pos.y - player.y) * 2.0  // Prefer same-height blocks
    val accessibilityPenalty = if (isBlockAccessible(pos)) 0.0 else 1000.0
    
    // Weights
    val W_DISTANCE = 0.3
    val W_CENTER = 0.5   // Bias toward chunk center
    val W_HEIGHT = 0.2
    
    return (distanceToPlayer * W_DISTANCE) + 
           (distanceToCenter * W_CENTER) + 
           (heightPenalty * W_HEIGHT) + 
           accessibilityPenalty
}

fun isBlockAccessible(pos: BlockPos): Boolean {
    // Check if there's air above the block for BUR placement
    val above = pos.up()
    return world.getBlockState(above).isAir || world.getBlockState(above).isReplaceable
}
```

**Expected Improvement**: Bot will fly to chunk CENTER at a similar Y-level, not to the edge.

---

### 3.2 Smooth Flight: Interpolated Rotation with Deadzone

Replace instant rotation snapping with exponential interpolation:

```kotlin
// PROPOSED: Smooth rotation with deadzone
fun smoothRotateTowards(player: PlayerEntity, targetYaw: Float, targetPitch: Float) {
    val ROTATION_SPEED = 0.15f  // 15% per tick (adjustable)
    val DEADZONE = 2.0f         // Degrees - don't rotate if already within this
    
    // Calculate deltas with wraparound handling
    var yawDelta = targetYaw - player.yaw
    while (yawDelta > 180) yawDelta -= 360
    while (yawDelta < -180) yawDelta += 360
    
    val pitchDelta = targetPitch - player.pitch
    
    // Apply deadzone
    if (abs(yawDelta) < DEADZONE && abs(pitchDelta) < DEADZONE) {
        return  // Already aligned, don't jitter
    }
    
    // Exponential interpolation (ease-out feel)
    player.yaw += yawDelta * ROTATION_SPEED
    player.pitch += pitchDelta * ROTATION_SPEED
    
    // Clamp pitch
    player.pitch = player.pitch.coerceIn(-90f, 90f)
}
```

**Expected Improvement**: No more instant snapping = no more spinning when targets change.

---

### 3.3 Proportional Input System

Replace binary FORWARD/SPRINT with variable intensity based on angle error:

```kotlin
// PROPOSED: Proportional movement input
fun applyProportionalMovement(player: PlayerEntity, targetYaw: Float) {
    var yawError = abs(targetYaw - player.yaw)
    while (yawError > 180) yawError = 360 - yawError
    
    when {
        yawError > 90 -> {
            // Way off target - don't move forward, just rotate
            InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, false)
            InputOverrideHandler.setInputForced(BotInput.SPRINT, false)
        }
        yawError > 30 -> {
            // Moderately off - move slowly
            InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, true)
            InputOverrideHandler.setInputForced(BotInput.SPRINT, false)
        }
        else -> {
            // On target - full speed
            InputOverrideHandler.setInputForced(BotInput.MOVE_FORWARD, true)
            InputOverrideHandler.setInputForced(BotInput.SPRINT, true)
        }
    }
}
```

**Expected Improvement**: Bot will slow down when turning, preventing overshoot and spiral paths.

---

### 3.4 Smart Path Caching

Implement proper path lifecycle management:

```kotlin
// PROPOSED: PathCache with invalidation
class FlightPathCache {
    var path: List<BlockPos>? = null
        private set
    var targetChunk: ChunkPos? = null
        private set
    var calculationTime: Long = 0
        private set
    var currentIndex: Int = 0
    
    private val MAX_PATH_AGE_MS = 10000L  // Recalculate after 10 seconds
    
    fun isValidFor(chunk: ChunkPos): Boolean {
        if (path == null) return false
        if (targetChunk != chunk) return false  // Different destination
        if (System.currentTimeMillis() - calculationTime > MAX_PATH_AGE_MS) return false
        if (currentIndex >= path!!.size) return false
        return true
    }
    
    fun setPath(newPath: List<BlockPos>, chunk: ChunkPos) {
        path = newPath
        targetChunk = chunk
        calculationTime = System.currentTimeMillis()
        currentIndex = 0
    }
    
    fun invalidate() {
        path = null
        targetChunk = null
        currentIndex = 0
    }
    
    fun advanceIfClose(playerPos: Vec3d, threshold: Double = 2.0): BlockPos? {
        val p = path ?: return null
        if (currentIndex >= p.size) return null
        
        val node = p[currentIndex]
        if (playerPos.squaredDistanceTo(Vec3d.ofCenter(node)) < threshold * threshold) {
            currentIndex++
        }
        
        return if (currentIndex < p.size) p[currentIndex] else null
    }
}
```

**Usage in `flyToChunk()`**:

```kotlin
fun flyToChunk(client: MinecraftClient, chunk: ChunkPos): Boolean {
    // Invalidate cache if target changed
    if (!pathCache.isValidFor(chunk)) {
        pathCache.invalidate()
    }
    
    // Calculate new path if needed
    if (pathCache.path == null) {
        val newPath = pathFinder?.findPath(player.blockPos, targetPos)
        if (newPath != null) {
            pathCache.setPath(smoothPath(client, newPath), chunk)
        }
    }
    
    // Follow cached path
    val nextNode = pathCache.advanceIfClose(player.pos)
    if (nextNode != null) {
        moveTowards(client, nextNode.x + 0.5, nextNode.y + 0.5, nextNode.z + 0.5)
    }
    // ...
}
```

**Expected Improvement**: Path is calculated once per destination, properly invalidated on target change.

---

### 3.5 State Machine Guard: Rotation Lock

Prevent simultaneous rotation control by implementing a lock:

```kotlin
// PROPOSED: Rotation ownership system
enum class RotationOwner {
    NONE,
    FLIGHT_CONTROLLER,
    CHUNK_BREAKER
}

object RotationLock {
    var owner: RotationOwner = RotationOwner.NONE
        private set
    
    fun acquire(requester: RotationOwner): Boolean {
        if (owner == RotationOwner.NONE || owner == requester) {
            owner = requester
            return true
        }
        return false
    }
    
    fun release(requester: RotationOwner) {
        if (owner == requester) {
            owner = RotationOwner.NONE
        }
    }
}

// In FlightController.moveTowards():
if (updateRotation && RotationLock.acquire(RotationOwner.FLIGHT_CONTROLLER)) {
    smoothRotateTowards(player, targetYaw, targetPitch)
}

// In ChunkBreaker.placeBur():
if (RotationLock.acquire(RotationOwner.CHUNK_BREAKER)) {
    lookAt(player, targetBlock)
}
```

**Expected Improvement**: Only one system controls rotation at a time, eliminating fighting.

---

## 4. Action Items for Kimi K2

### Phase 1: Fix Rotation Conflicts (Critical)

- [ ] **Step 1.1**: Create `RotationLock.kt` utility class
  - Implement mutex-style rotation ownership
  - Add `acquire()`, `release()`, and `forceRelease()` methods
  
- [ ] **Step 1.2**: Modify `FlightController.moveTowards()`
  - Replace lines 165-171 with `smoothRotateTowards()` function
  - Add `RotationLock.acquire()` check before any rotation
  - Implement exponential interpolation (15% per tick)
  - Add 2° deadzone to prevent jitter
  
- [ ] **Step 1.3**: Modify `ChunkBreaker.lookAt()`
  - Add `RotationLock.acquire(CHUNK_BREAKER)` before setting rotation
  - Release lock after BUR placement completes

---

### Phase 2: Fix Target Selection

- [ ] **Step 2.1**: Rewrite `ChunkBreaker.findBestTarget()`
  - Replace pure distance scoring with composite function
  - Add `scoreBlock()` method with weights:
    - `W_DISTANCE = 0.3`
    - `W_CENTER = 0.5`
    - `W_HEIGHT = 0.2`
  - Add `isBlockAccessible()` check (air above = accessible)
  
- [ ] **Step 2.2**: Add early termination
  - If a block scores below threshold (e.g., < 100), return immediately
  - Use heightmap to skip air columns

---

### Phase 3: Implement Proportional Movement

- [ ] **Step 3.1**: Modify `FlightController.moveTowards()` input logic
  - Replace binary FORWARD/SPRINT with yaw-error-based intensity
  - Thresholds: `>90°` = stop, `30-90°` = walk, `<30°` = sprint
  
- [ ] **Step 3.2**: Add movement state awareness
  - Don't sprint when recovering from stuck
  - Reduce speed when approaching destination (< 5 blocks)

---

### Phase 4: Smart Path Caching

- [ ] **Step 4.1**: Create `FlightPathCache` class
  - Fields: `path`, `targetChunk`, `calculationTime`, `currentIndex`
  - Methods: `isValidFor()`, `setPath()`, `invalidate()`, `advanceIfClose()`
  - Max age: 10 seconds
  
- [ ] **Step 4.2**: Integrate into `FlightController`
  - Replace `currentPath` / `pathIndex` with `FlightPathCache` instance
  - Call `pathCache.invalidate()` in `reset()` method
  - Check `isValidFor(chunk)` before any path operation
  
- [ ] **Step 4.3**: Fix `lastTargetChunk` variable
  - Currently declared on line 35 but never used
  - Either remove it or integrate it into `FlightPathCache`

---

### Phase 5: State Machine Cleanup

- [ ] **Step 5.1**: Add path clearing on state transition
  - In `BotController.tick()`, call `flightController.reset()` when transitioning between `FlyingToChunk` → `FlyingToBlock` → `PlacingBur`
  
- [ ] **Step 5.2**: Remove premature `FlyingToBlock` transition
  - Only transition to `FlyingToBlock` when player is within 32 blocks of target chunk
  - Currently transitions immediately when chunk is loaded (could be 500 blocks away)
  
- [ ] **Step 5.3**: Fix recovery logic
  - In `handleRecovery()`, add rotation stabilization (set yaw to current movement direction)
  - Increase stuck threshold from 20 ticks to 40 ticks
  - Add cooldown between recovery attempts (5 seconds)

---

# Your Task

Implement these changes. Start with the **Phase 1: Fix Rotation Conflicts** as it is the most critical issue leading to the spinning/freezing bug. Then proceed to **Phase 2** for better target selection, and so on.

Provide the full code for the modified files.
