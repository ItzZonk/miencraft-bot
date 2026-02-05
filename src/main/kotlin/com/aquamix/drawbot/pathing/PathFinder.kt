package com.aquamix.drawbot.pathing

import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import kotlin.math.abs
import com.aquamix.drawbot.AquamixDrawBot
import java.util.PriorityQueue

/**
 * Node for A* pathfinding
 */
data class PathNode(
    val pos: BlockPos,
    val parent: PathNode?,
    val gCost: Double, // Cost from start
    val hCost: Double  // Heuristic to end
) : Comparable<PathNode> {
    val fCost: Double get() = gCost + hCost
    
    override fun compareTo(other: PathNode): Int {
        val cmp = fCost.compareTo(other.fCost)
        return if (cmp == 0) hCost.compareTo(other.hCost) else cmp
    }
}

/**
 * 3D Flight Pathfinding using A*
 */
class PathFinder(private val world: World) {
    
    /**
     * Find path from start to target Y level (any X/Z at that height)
     * Prioritizing staying close to centerX/centerZ
     */
    fun findPathToHeight(
        start: BlockPos, 
        targetY: Int, 
        centerX: Int, 
        centerZ: Int,
        maxNodes: Int = 1000
    ): List<BlockPos>? {
        val openSet = PriorityQueue<PathNode>()
        val closedSet = mutableSetOf<BlockPos>()
        
        val startNode = PathNode(start, null, 0.0, heuristic(start, targetY, centerX, centerZ))
        openSet.add(startNode)
        
        var iterations = 0
        
        while (openSet.isNotEmpty()) {
            if (iterations++ > maxNodes) break
            
            val current = openSet.poll()
            
            // Goal reached? (At target height)
            if (current.pos.y >= targetY) {
                return reconstructPath(current)
            }
            
            closedSet.add(current.pos)
            
            // Neighbors (3x3x3 area)
            for (x in -1..1) {
                for (y in -1..1) {
                    for (z in -1..1) {
                        if (x == 0 && y == 0 && z == 0) continue
                        
                        val neighborPos = current.pos.add(x, y, z)
                        
                        if (closedSet.contains(neighborPos)) continue
                        
                        // Check collision
                        if (!isPassable(neighborPos)) continue
                        
                        // Cost calculation
                        val moveCost = if (x != 0 && y != 0 && z != 0) 1.73 else if ((x != 0 && y != 0) || (x != 0 && z != 0) || (y != 0 && z != 0)) 1.41 else 1.0
                        val newGCost = current.gCost + moveCost
                        
                        val neighborNode = PathNode(
                            neighborPos,
                            current,
                            newGCost,
                            heuristic(neighborPos, targetY, centerX, centerZ)
                        )
                        
                        // Check if better path exists (simplified: just add if not in open)
                        // Optimization: For true A* we should check openSet, but for Minecraft local pathing often ok to skip
                        openSet.add(neighborNode)
                    }
                }
            }
        }
        
        return null // No path found
    }
    
    private fun heuristic(pos: BlockPos, targetY: Int, centerX: Int, centerZ: Int): Double {
        val dy = (targetY - pos.y).toDouble()
        val distToCenter = kotlin.math.sqrt(
            ((pos.x - centerX).toDouble() * (pos.x - centerX)) + 
            ((pos.z - centerZ).toDouble() * (pos.z - centerZ))
        )
        return dy + (distToCenter * 0.5) // Prefer moving up, but keep somewhat centered
    }
    
    private fun isPassable(pos: BlockPos): Boolean {
        // Check 2 blocks for player height
        val state1 = world.getBlockState(pos)
        val state2 = world.getBlockState(pos.up())
        
        // Simple check: Blocks must be air or passable fluids
        // Need to be careful about "passable" (flowers/grass = ok, stone = no)
        return (!state1.isSolidBlock(world, pos) && !state2.isSolidBlock(world, pos.up()))
    }
    
    private fun reconstructPath(node: PathNode): List<BlockPos> {
        val path = mutableListOf<BlockPos>()
        var current: PathNode? = node
        while (current != null) {
            path.add(current.pos)
            current = current.parent
        }
        return path.reversed()
    }
}
