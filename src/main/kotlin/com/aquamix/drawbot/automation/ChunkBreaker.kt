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
 * ИСПРАВЛЕНО: убрана осцилляция камеры при повороте к блоку
 */
class ChunkBreaker {
    private var lastActionTime = 0L
    
    // Целевой блок для установки БУРа (кэшируем чтобы не искать каждый тик)
    private var cachedTargetBlock: BlockPos? = null
    private var targetRotationDone = false
    
    // Exhaustive search state - scan entire chunk systematically
    private var currentScanIndex = 0  // Index in chunk (0-255 for 16x16 grid)
    private var scanAttempts = 0
    
    /**
     * Сброс состояния для нового чанка
     */
    fun reset() {
        cachedTargetBlock = null
        targetRotationDone = false
        currentScanIndex = 0
        scanAttempts = 0
    }
    
    /**
     * Получить текущую цель (или найти новую) в указанном чанке
     * NEVER GIVES UP - будет искать до победного конца
     */
    fun getTarget(client: MinecraftClient, targetChunk: ChunkPos): BlockPos? {
        if (cachedTargetBlock == null) {
            cachedTargetBlock = findBestTarget(client, targetChunk)
            scanAttempts++
        }
        // NO TIMEOUT - мы всегда пытаемся найти
        return cachedTargetBlock
    }
    
    /**
     * Находит ближайший твёрдый блок в ЦЕЛЕВОМ чанке и ставит на него БУР
     */
    fun placeBur(client: MinecraftClient, targetChunk: ChunkPos): Boolean {
        val player = client.player ?: return false
        val interactionManager = client.interactionManager ?: return false
        val world = client.world ?: return false
        
        if (!canAct()) return false
        
        // 1. Найти цель в целевом чанке (exhaustive search - никогда не сдаёмся)
        val targetBlock = getTarget(client, targetChunk)
        if (targetBlock == null) {
            // Ищем дальше - сканируем весь чанк, ломаем препятствия если нужно
            if (scanAttempts % 50 == 0) {
                 AquamixDrawBot.LOGGER.info("Scanning chunk $targetChunk exhaustively (attempt $scanAttempts)...")
            }
            return false 
        }
        
        
        // 2. Проверяем препятствие сверху
        val blockAbove = targetBlock.up()
        val stateAbove = world.getBlockState(blockAbove)
        val isWater = stateAbove.fluidState.isStill && !stateAbove.block.name.string.contains("Lava")
        val isReplaceable = stateAbove.isReplaceable || stateAbove.block.name.string.contains("grass", ignoreCase = true) || 
                            stateAbove.block.name.string.contains("plant", ignoreCase = true) ||
                            stateAbove.block.name.string.contains("flower", ignoreCase = true) ||
                            stateAbove.block.name.string.contains("sapling", ignoreCase = true)
        
        if (!stateAbove.isAir && !isWater && !isReplaceable) { 
            // Solid block - need pickaxe
            AquamixDrawBot.botController.inventoryManager.equipBestPickaxe(client)
            if (player.squaredDistanceTo(Vec3d.ofCenter(blockAbove)) > 25.0) {
                 // Too far - forget this target and find closer one
                 cachedTargetBlock = null
                 return false
            }

            lookAt(player, blockAbove)
            interactionManager.attackBlock(blockAbove, Direction.UP)
            player.swingHand(Hand.MAIN_HAND)
            markAction()
            return false
        }
        
        if (isReplaceable && !stateAbove.isAir) {
            // Grass/plant - break with BUR in hand
            val inventory = player.inventory
            var burSlot = -1
            
            for (i in 0..8) {
                val stack = inventory.getStack(i)
                if (stack.item == Items.END_PORTAL_FRAME) {
                    burSlot = i
                    break
                }
            }
            
            if (burSlot == -1) {
                AquamixDrawBot.LOGGER.warn("BUR not found in hotbar!")
                return false
            }
            
            if (inventory.selectedSlot != burSlot) {
                inventory.selectedSlot = burSlot
                markAction()
                return false
            }
            
            // Break grass/plant
            lookAt(player, blockAbove)
            interactionManager.attackBlock(blockAbove, Direction.UP)
            player.swingHand(Hand.MAIN_HAND)
            markAction()
            return false
        }
        
        // 3. Ставим БУР
        val inventory = player.inventory
        var burSlot = -1
        
        for (i in 0..8) {
            val stack = inventory.getStack(i)
            if (stack.item == Items.END_PORTAL_FRAME) {
                burSlot = i
                break
            }
        }
        
        if (burSlot == -1) {
            AquamixDrawBot.LOGGER.warn("BUR not found in hotbar!")
            return false
        }
        
        if (inventory.selectedSlot != burSlot) {
            inventory.selectedSlot = burSlot
            markAction()
            return false
        }
        
        // Поворот к блоку
        val distSq = player.squaredDistanceTo(Vec3d.ofCenter(targetBlock))
        if (distSq > 36.0) { 
            // Too far - find closer target
            cachedTargetBlock = null
            return false
        }
        
        if (!targetRotationDone) {
            lookAt(player, targetBlock)
            targetRotationDone = true
            markAction()
            return false
        }
        
        // Ставим
        val hitResult = BlockHitResult(
            Vec3d.ofCenter(targetBlock).add(0.0, 1.0, 0.0), 
            Direction.UP,
            targetBlock,
            false 
        )
        
        val result = interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult)
        
        if (result.isAccepted) {
            player.swingHand(Hand.MAIN_HAND)
            markAction()
            AquamixDrawBot.LOGGER.info("BUR placed in chunk $targetChunk at ${targetBlock.toShortString()}")
            reset() 
            return true
        } else {
             // Interaction failed - retry with different block
             cachedTargetBlock = null
             AquamixDrawBot.LOGGER.warn("Interaction failed ($result), finding another target...")
             return false
        }
    }
    
    // REMOVED: blacklistCurrent() - мы никогда не сдаёмся
    
    // ... lookAt ...

    private fun lookAt(player: net.minecraft.entity.player.PlayerEntity, target: BlockPos) {
        val blockCenter = Vec3d.ofCenter(target)
        val eyePos = player.eyePos
        
        val dx = blockCenter.x - eyePos.x
        val dy = blockCenter.y - eyePos.y
        val dz = blockCenter.z - eyePos.z
        
        val horizontalDist = sqrt(dx * dx + dz * dz)
        val targetYaw = Math.toDegrees(kotlin.math.atan2(-dx, dz)).toFloat()
        val targetPitch = Math.toDegrees(kotlin.math.atan2(-dy, horizontalDist)).toFloat()
            .coerceIn(-90f, 90f)
        
        player.yaw = targetYaw
        player.pitch = targetPitch
    }

    /**
     * Находит лучший твёрдый блок в СТРОГО УКАЗАННОМ чанке
     */
    private fun findBestTarget(client: MinecraftClient, chunk: ChunkPos): BlockPos? {
        val world = client.world ?: return null
        val player = client.player ?: return null
        
        // Используем переданный чанк, а не игрока!
        val chunkX = chunk.x
        val chunkZ = chunk.z
        
        var bestBlock: BlockPos? = null
        var bestScore = Double.MAX_VALUE
        
        val centerX = (chunkX shl 4) + 8
        val centerZ = (chunkZ shl 4) + 8
        
        // Сканируем 16x16
        for (x in 0..15) {
            for (z in 0..15) {
                val worldX = (chunkX shl 4) + x
                val worldZ = (chunkZ shl 4) + z
                
                // Полный скан высоты!
                val startY = world.topY - 1 
                
                for (y in startY downTo world.bottomY) {
                    val pos = BlockPos(worldX, y, worldZ)
                    
                    val state = world.getBlockState(pos)
                    
                    // BUR can be placed on ANY block: slabs, stairs, solid, etc.
                    // Only skip air and fluids
                    if (!state.isAir && state.fluidState.isEmpty) {
                        // Оценка:
                        // 1. Расстояние от ИГРОКА (а не центра чанка), чтобы не лететь лишнего
                        val dx = (worldX - player.x)
                        val dz = (worldZ - player.z)
                        val distToPlayer = sqrt(dx * dx + dz * dz)
                        
                        // 2. Чистота сверху
                        val above = pos.up()
                        val stateAbove = world.getBlockState(above)
                        val isWater = stateAbove.fluidState.isStill && !stateAbove.block.name.string.contains("Lava")
                        
                        // Check if player is occupying the space (Collision Check)
                        val candidateBox = net.minecraft.util.math.Box(
                            above.x.toDouble(), above.y.toDouble(), above.z.toDouble(),
                            above.x.toDouble() + 1.0, above.y.toDouble() + 1.0, above.z.toDouble() + 1.0
                        )
                        if (player.boundingBox.intersects(candidateBox)) {
                             // Player is standing here! Invalid target.
                             continue
                        }
                        
                        val obstructionPenalty = if (!stateAbove.isAir && !isWater) 500.0 else 0.0
                        
                        // 3. Вертикальный штраф (предпочитаем блоки на нашей высоте или чуть ниже)
                        // Если блок сильно ниже - штраф, чтобы не нырять глубоко без нужды
                        // Если блок сильно выше - тоже штраф
                        val dy = kotlin.math.abs(player.y - y)
                        val vertPenalty = dy * 1.5
                        
                        val score = distToPlayer + obstructionPenalty + vertPenalty
                        
                        if (score < bestScore) {
                            bestScore = score
                            bestBlock = pos
                        }
                        
                        break
                    }
                }
            }
        }
        
        return bestBlock
    }

    // ... (isBurMenuOpen, clickBreakAll, closeMenu, canAct, markAction remain same)
    
    /**
     * Проверяет, открыто ли меню БУРа
     */
    fun isBurMenuOpen(client: MinecraftClient): Boolean {
        // ... (rest is unchanged, keeping for safety)
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
        // Fallback
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
    
    private fun canAct(): Boolean {
        val delay = ModConfig.data.timing.actionCooldown
        return System.currentTimeMillis() - lastActionTime > delay
    }
    
    private fun markAction() {
        lastActionTime = System.currentTimeMillis()
    }
}
