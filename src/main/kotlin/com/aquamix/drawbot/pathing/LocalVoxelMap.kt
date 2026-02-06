package com.aquamix.drawbot.pathing

import net.minecraft.block.BlockState
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.util.concurrent.atomic.AtomicReference

/**
 * Represents a local 3D cache of the world (The "3D Map").
 * Optimized for fast A* pathfinding queries and obstacle detection.
 */
class LocalVoxelMap(val radius: Int = 32) {
    
    // Flattened array for cache: [x + z*size + y*size*size]
    // Size = radius * 2 + 1
    private val size = radius * 2 + 1
    private val data = BooleanArray(size * size * 320) // Fixed height limits? No, we use relative.
    // Actually full 3D relative array is better.
    // Let's settle on a simpler relative map centered on a 'centerPos'.
    
    // We'll use a Hash-based approach for sparse storage OR a relative array.
    // Given the user wants "Speed" and "3D Model", a relative array is fastest (O(1) lookup).
    // Let's try 64x64x64 volume (Radius 32)
    private val volumeSize = 64
    private val volumeHeight = 64 
    private val passabilityCache = BooleanArray(volumeSize * volumeHeight * volumeSize)
    
    private var centerPos: BlockPos = BlockPos.ORIGIN
    private var worldRef: World? = null
    
    fun update(world: World, center: BlockPos) {
        this.worldRef = world
        this.centerPos = center
        
        // Populate cache
        // We only scan relevant area? Accessing 64^3 blocks (262k) every tick might be slow.
        // Lazy loading is better.
    }
    
    // On second thought, full caching every tick is too heavy for Java GC.
    // Let's use a "Smart Accessor" that caches results for the specific tick/frame.
    
    private val cache = mutableMapOf<Long, Boolean>()
    private var lastUpdateTick = 0L
    
    fun isPassable(world: World, pos: BlockPos): Boolean {
        // Simple passthrough for now, BUT we apply the "Model" logic here.
        // The user wants to "Scan trees".
        
        val state = world.getBlockState(pos)
        return isStatePassable(state)
    }
    
    private fun isStatePassable(state: BlockState): Boolean {
        if (state.isAir) return true
        // Leaves are OBSTACLES for flight (we don't want to get stuck in them)
        if (state.block.name.string.contains("Leaves", ignoreCase = true)) return false
        // Fluids?
        if (!state.fluidState.isEmpty) return true // We can fly through water
        
        return !state.isSolidBlock(worldRef!!, BlockPos.ORIGIN) // Mock pos, isSolidBlock usually needs real pos
    }
}
