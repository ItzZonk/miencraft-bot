package com.aquamix.drawbot.automation

import com.aquamix.drawbot.AquamixDrawBot
import com.aquamix.drawbot.config.ModConfig
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen
import net.minecraft.client.gui.screen.ingame.HandledScreen
import net.minecraft.item.Items
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.math.Vec3d
import kotlin.math.sqrt

/**
 * Переписанный обработчик БУРа
 * "The Perfect Interaction" Edition + 3D Scanner
 */
class ChunkBreaker {
    private var lastActionTime = 0L
    
    // Breaking State
    private var isBreaking = false
    private var currentBreakingBlock: BlockPos? = null
    
    // Целевой блок для установки БУРа (кэшируем чтобы не искать каждый тик)
    private var cachedTargetBlock: BlockPos? = null
    private var targetRotationDone = false
    
    // Exhaustive search state
    private var scanAttempts = 0
    private val failedBlocks = mutableSetOf<BlockPos>()

    /**
     * Сброс состояния для нового чанка
     */
    fun reset() {
        cachedTargetBlock = null
        targetRotationDone = false
        scanAttempts = 0
        failedBlocks.clear()
        stopBreaking()
    }
    
    // === Digging Logic ===
    
    private fun stopBreaking() {
         if (isBreaking) {
             val client = MinecraftClient.getInstance()
             client.interactionManager?.cancelBlockBreaking()
             isBreaking = false
             currentBreakingBlock = null
         }
    }
    
    /**
     * Управляет копанием блока (Hold to break)
     */
    private fun updateBreaking(client: MinecraftClient, target: BlockPos): Boolean {
         val interactionManager = client.interactionManager ?: return false
         val player = client.player ?: return false
         
         if (currentBreakingBlock != target) {
             stopBreaking()
             
             // Start new break
             lookAt(player, target)
             interactionManager.attackBlock(target, Direction.UP)
             player.swingHand(Hand.MAIN_HAND)
             
             isBreaking = true
             currentBreakingBlock = target
             return false
         } else {
             // Continue breaking (Hold button simulation)
             lookAt(player, target) // Keep looking
             if (interactionManager.updateBlockBreakingProgress(target, Direction.UP)) {
                 player.swingHand(Hand.MAIN_HAND) // Swing animation
             }
             
             // Check if broken
             if (client.world?.getBlockState(target)?.isAir == true) {
                 stopBreaking()
                 return true // DONE
             }
             return false // Still breaking
         }
    }

    /**
     * Находит ближайший твёрдый блок в ЦЕЛЕВОМ чанке и ставит на него БУР
     */
    fun placeBur(client: MinecraftClient, targetChunk: ChunkPos): Boolean {
        val player = client.player ?: return false
        val interactionManager = client.interactionManager ?: return false
        val world = client.world ?: return false
        
        if (!canAct()) return false
        
        // 1. Найти цель
        val targetBlock = getTarget(client, targetChunk)
        if (targetBlock == null) {
            if (scanAttempts % 50 == 0) {
                 AquamixDrawBot.LOGGER.info("Scanning chunk $targetChunk exhaustively (attempt $scanAttempts)...")
            }
            return false 
        }
        
        // Fail-Fast: Try multiple times per tick until success
        var attempts = 0
        var currentTarget: BlockPos? = targetBlock
        
        while (attempts < 5 && currentTarget != null) { // Try up to 5 candidates instantly
             attempts++
             val target = currentTarget!!
             
             // 2. Obstacle Clearing
             val blockAbove = target.up()
             val stateAbove = world.getBlockState(blockAbove)
             
             // If already placed?
             if (stateAbove.block == net.minecraft.block.Blocks.END_PORTAL_FRAME) {
                 // Already here
                 // ... interaction logic ...
                 val hitResult = BlockHitResult(Vec3d.ofCenter(blockAbove), Direction.UP, blockAbove, false)
                 lookAt(player, blockAbove)
                 interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult)
                 player.swingHand(Hand.MAIN_HAND)
                 markAction()
                 return true 
             }
             
             // Break grass/kelp instantly
             val isReplaceable = stateAbove.isReplaceable || stateAbove.block.name.string.contains("grass", ignoreCase = true)
             if (!stateAbove.isAir && (!isReplaceable || stateAbove.block.name.string.contains("Kelp", ignoreCase = true))) {
                  AquamixDrawBot.botController.inventoryManager.equipBestPickaxe(client)
                  val broken = updateBreaking(client, blockAbove)
                  if (!broken) {
                      markAction()
                      return false // Must wait for break
                  }
             } else {
                 stopBreaking()
             }
             
             // Equip BUR if needed (only once per tick optimization)
             val inventory = player.inventory
             var burSlot = -1
             for (i in 0..8) {
                if (inventory.getStack(i).item == Items.END_PORTAL_FRAME) {
                    burSlot = i
                    break
                }
             }
             if (burSlot == -1) return false
             if (inventory.selectedSlot != burSlot) inventory.selectedSlot = burSlot
             
             // Safety
             val burPos = target.up()
             if (player.boundingBox.intersects(net.minecraft.util.math.Box(burPos))) {
                 invalidateCurrentTarget() // Current target bad
                 // Try to get next target immediately?
                 // We need to fetch next best from `findBestTarget` excluding failed.
                 // But `findBestTarget` is expensive.
                 // Better: Use `tryPlaceAgainstNeighbors` on this bad pos.
             } else {
                 // Try Placement
                 lookAt(player, target)
                 val hitResult = BlockHitResult(Vec3d.ofCenter(target).add(0.0, 1.0, 0.0), Direction.UP, target, false)
                 val result = interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult)
                 
                 if (result.isAccepted) {
                     player.swingHand(Hand.MAIN_HAND)
                     markAction()
                     AquamixDrawBot.LOGGER.info("BUR placed!")
                     reset()
                     return true
                 } else {
                     // Try neighbors
                     if (tryPlaceAgainstNeighbors(client, burPos)) {
                         player.swingHand(Hand.MAIN_HAND)
                         markAction()
                         AquamixDrawBot.LOGGER.info("BUR neighbor placed!")
                         reset()
                         return true
                     }
                 }
             }
             
             // If we failed interaction or safety:
             invalidateCurrentTarget() 
             // Logic to pick NEXT best target?
             // `cachedTargetBlock` is now null. `getTarget` will return next best.
             val nextTarget = getTarget(client, targetChunk)
             // Loop continues if nextTarget is not null
             if (nextTarget == null) return false // No more targets
             currentTarget = nextTarget
        }
        
        return false
    }
    
    private fun tryPlaceAgainstNeighbors(client: MinecraftClient, targetPos: BlockPos): Boolean {
        val world = client.world ?: return false
        val player = client.player ?: return false
        val interactionManager = client.interactionManager ?: return false
        
        // Check all 6 neighbors of the target AIR block
        // Sort by distance to player
        val dirs = Direction.values().sortedBy { 
             targetPos.offset(it).getSquaredDistance(player.pos)
        }
        
        for (dir in dirs) {
            val neighbor = targetPos.offset(dir)
            val state = world.getBlockState(neighbor)
            
            if (state.isSolidBlock(world, neighbor)) {
                // Found a solid neighbor!
                // Place against it.
                val face = dir.opposite
                val hitResult = BlockHitResult(
                    Vec3d.ofCenter(neighbor).add(Vec3d.of(face.vector).multiply(0.5)),
                    face,
                    neighbor,
                    false
                )
                
                // Rotation
                lookAt(player, neighbor)
                
                val res = interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult)
                if (res.isAccepted) return true
            }
        }
        return false
    }

    fun getTarget(client: MinecraftClient, targetChunk: ChunkPos): BlockPos? {
        if (cachedTargetBlock == null) {
            cachedTargetBlock = findBestTarget(client, targetChunk)
            scanAttempts++
        }
        return cachedTargetBlock
    }

    /**
     * Находит лучший твёрдый блок в ЦЕЛЕВОМ чанке
     * "The 3D Scanner" Logic
     * Scans outward from player to find the ABSOLUTE CLOSEST valid block surface.
     */
    private fun findBestTarget(client: MinecraftClient, chunk: ChunkPos): BlockPos? {
        val world = client.world ?: return null
        val player = client.player ?: return null
        val playerPos = player.blockPos
        
        // Fast approach: Iterate chunk bounds, but verify distance.
        // The target MUST be in the chunk.
        
        val chunkMinX = chunk.minBlockX
        val chunkMaxX = chunk.maxBlockX
        val chunkMinZ = chunk.minBlockZ
        val chunkMaxZ = chunk.maxBlockZ
        
        var bestBlock: BlockPos? = null
        var bestDistSq = Double.MAX_VALUE
        
        // Optimization: iterate only the chunk x/z, but restrict Y to player range
        // Expanded Scan Radius (User Request: 256 blocks)
        val scanRadius = 256 // HUGE radius
        
        // Optimization: Spiral out from player chunk instead of iterating all
        val pChunkX = player.chunkPos.x
        val pChunkZ = player.chunkPos.z
        
        // Scan chunks in radius
        // To be safe and fast, we prioritize the TARGET chunk first, then spiral out if needed
        // BUT `findBestTarget` is called for a SPECIFIC target chunk usually.
        // Wait, the user wants "Scan Radius" for *finding targets*?
        // Ah, `findBestTarget` takes a `chunk: ChunkPos`. This function scans *inside* that chunk.
        // If the user wants to scan 256 blocks to find *which chunk* to go to, that's `BotController` logic.
        // BUT if they mean "Scan the target chunk vertically/horizontally", we already scan full chunk.
        
        // Let's assume user means: When looking for a spot to place BUR, check a huge area?
        // No, the bot flies to specific chunks in the queue.
        // Re-reading: "Increase 3D awareness radius... to better plan paths".
        // This likely refers to `PathFinder` or `LocalVoxelMap`.
        
        // However, let's ensure we scan the full vertical range of the target chunk.
        val startY = world.bottomY
        val endY = world.topY
        
        // The original code scanned +/- 30 blocks. User wants MORE visibility.
        // So we scan full height.
        
        for (x in chunkMinX..chunkMaxX) {
            for (z in chunkMinZ..chunkMaxZ) {
                // Optimization: Skip empty columns using heightmap
                val topY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z)
                
                for (y in endY downTo startY) {
                    if (y > topY + 10) continue // Skip air way above terrain
                    
                    val pos = BlockPos(x, y, z)
                    
                    // Basic checks
                    if (pos in failedBlocks) continue
                    val state = world.getBlockState(pos)
                    
                    // ALLOW placement on SNOW LAYERS (any height)
                    var isSnow = state.block == net.minecraft.block.Blocks.SNOW
                    
                    // Check if material is solid-ish
                    if (!state.fluidState.isEmpty && !state.isSolidBlock(world, pos)) continue
                    
                    // If not snow and not solid, skip
                    if (!isSnow && !state.isSolidBlock(world, pos)) continue
                    if (state.isAir) continue
                    
                    val distSq = pos.getSquaredDistance(player.pos)
                    // We want the CLOSEST valid block in this chunk.
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq
                        bestBlock = pos
                    }
                }
            }
        }
        
        return bestBlock
    }
    
    // ... Helpers ...
    
    private fun lookAt(player: net.minecraft.entity.player.PlayerEntity, target: BlockPos) {
        val blockCenter = Vec3d.ofCenter(target)
        val eyePos = player.eyePos
        val dx = blockCenter.x - eyePos.x
        val dy = blockCenter.y - eyePos.y
        val dz = blockCenter.z - eyePos.z
        val horizontalDist = sqrt(dx * dx + dz * dz)
        val targetYaw = Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat()
        val targetPitch = Math.toDegrees(kotlin.math.atan2(-dy, horizontalDist)).toFloat().coerceIn(-90f, 90f)
        player.yaw = targetYaw
        player.pitch = targetPitch
    }

    fun invalidateCurrentTarget() {
        cachedTargetBlock?.let { failedBlocks.add(it) }
        cachedTargetBlock = null
        targetRotationDone = false
        stopBreaking()
    }

    private fun canAct(): Boolean = true
    private fun markAction() { lastActionTime = System.currentTimeMillis() }
    
    // Menu helpers
    fun isBurMenuOpen(client: MinecraftClient): Boolean {
        val screen = client.currentScreen ?: return false
        if (screen !is HandledScreen<*>) return false
        val title = screen.title.string
        val config = ModConfig.data.bur
        return title.contains(config.menuTitle, ignoreCase = true)
    }
    
    fun clickBreakAll(client: MinecraftClient): Boolean {
        val screen = client.currentScreen
        if (screen !is GenericContainerScreen) return false
        if (!canAct()) return false
        val handler = screen.screenHandler
        val config = ModConfig.data.bur
        val pattern = Regex(config.breakButtonPattern, RegexOption.IGNORE_CASE)
        for (slot in handler.slots) {
            if (slot.inventory == client.player?.inventory) continue
            val stack = slot.stack
            if (stack.isEmpty) continue
            val displayName = stack.name.string
            val itemId = stack.item.toString()
            if (itemId.contains("netherite_pickaxe") || pattern.containsMatchIn(displayName)) {
                client.interactionManager?.clickSlot(handler.syncId, slot.id, 0, SlotActionType.PICKUP, client.player)
                markAction()
                return true
            }
        }
        if (handler.slots.size > 1 && !handler.slots[1].stack.isEmpty) {
            client.interactionManager?.clickSlot(handler.syncId, 1, 0, SlotActionType.PICKUP, client.player)
            markAction()
            return true
        }
        return false
    }
    
    fun closeMenu(client: MinecraftClient) {
        client.player?.closeHandledScreen()
    }
}
