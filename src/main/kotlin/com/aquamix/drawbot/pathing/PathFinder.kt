package com.aquamix.drawbot.pathing

import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import java.util.PriorityQueue
import kotlin.math.sqrt

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
 * Custom 3D Flight A* PathFinder
 * "The Line in the Head"
 */
class PathFinder(private val world: World) {
    
    /**
     * Finds a flight path from start to end block
     */
    fun findPath(start: BlockPos, end: BlockPos, maxNodes: Int = 5000): List<BlockPos>? {
        if (!isPassable(end)) {
            // Target is solid? Try to find adjacent air
            // For now, assume target IS valid or we want to get adjacent
        }
        
        val openSet = PriorityQueue<PathNode>()
        val closedSet = mutableSetOf<BlockPos>()
        val gScore = mutableMapOf<BlockPos, Double>()
        
        val startNode = PathNode(start, null, 0.0, heuristic(start, end))
        openSet.add(startNode)
        gScore[start] = 0.0
        
        var iterations = 0
        
        while (openSet.isNotEmpty()) {
            if (iterations++ > maxNodes) break
            
            val current = openSet.poll()
            
            if (current.pos == end || current.pos.getSquaredDistance(end) < 2.0) {
                return reconstructPath(current)
            }
            
            closedSet.add(current.pos)
            
            // Neighbors: 26 directions (3x3x3 cube)
            for (x in -1..1) {
                for (y in -1..1) {
                    for (z in -1..1) {
                        if (x == 0 && y == 0 && z == 0) continue
                        
                        val neighborPos = current.pos.add(x, y, z)
                        
                        if (closedSet.contains(neighborPos)) continue
                        
                        if (!isPassable(neighborPos)) continue
                        
                        // Cost: 1.0 straight, 1.41 diagonal, 1.73 cubic diagonal
                        val moveCost = sqrt((x*x + y*y + z*z).toDouble())
                        val tentativeGCost = current.gCost + moveCost
                        
                        if (tentativeGCost < (gScore[neighborPos] ?: Double.MAX_VALUE)) {
                            gScore[neighborPos] = tentativeGCost
                            val neighborNode = PathNode(
                                neighborPos,
                                current,
                                tentativeGCost,
                                heuristic(neighborPos, end)
                            )
                            openSet.add(neighborNode)
                        }
                    }
                }
            }
        }
        
        return null // No path found
    }
    
    private fun heuristic(a: BlockPos, b: BlockPos): Double {
        // Euclidean distance for flight
        return sqrt(a.getSquaredDistance(b))
    }
    
    // Check if player fits here (0.6 x 1.8 x 0.6 box)
    // Simplified: Check head and feet blocks
    private fun isPassable(pos: BlockPos): Boolean {
        // Helper to check if block is "solid obstacle"
        // User Fix: Treat Grass/Flowers as passable (Air)
        fun isObstacle(pos: BlockPos): Boolean {
            val state = world.getBlockState(pos)
            if (state.isAir) return false
            if (!state.fluidState.isEmpty) return false // Water is fine
            
            // Check if replaceable (grass, flowers, kelp)
            if (state.isReplaceable || 
                state.block.name.string.contains("grass", ignoreCase = true) ||
                state.block.name.string.contains("flower", ignoreCase = true) ||
                state.block.name.string.contains("kelp", ignoreCase = true) ||
                state.block.name.string.contains("fern", ignoreCase = true)
               ) {
                return false
            }
            
            // Otherwise, check physical collision
            if (state.isSolidBlock(world, pos)) return true
            
            // Explicitly avoid Leaves and Logs (Tree Avoidance)
            val name = state.block.name.string.lowercase()
            if (name.contains("leaves") || name.contains("log") || name.contains("planks")) {
                return true
            }
            return false
        }
        
        return !isObstacle(pos) && !isObstacle(pos.up())
    }
    
    private fun reconstructPath(node: PathNode): List<BlockPos> {
        val path = mutableListOf<BlockPos>()
        var current: PathNode? = node
        while (current != null) {
            path.add(current.pos)
            current = current.parent
        }
        // Simplify path? (Raycast optimization)
        // For now, raw path is fine for "Visual Line"
        return path.reversed()
    }
}
