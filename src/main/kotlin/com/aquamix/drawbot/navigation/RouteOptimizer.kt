package com.aquamix.drawbot.navigation

import com.aquamix.drawbot.automation.ChunkPos

/**
 * Оптимизатор маршрута для обхода чанков
 * Решает задачу коммивояжёра (TSP) эвристическими методами
 */
class RouteOptimizer {
    
    /**
     * Оптимизировать маршрут обхода чанков
     * Использует Nearest Neighbor + 2-opt улучшение
     * 
     * @param chunks список чанков для обхода
     * @param start начальная позиция
     * @return оптимизированный список чанков
     */
    fun optimize(chunks: List<ChunkPos>, start: ChunkPos): List<ChunkPos> {
        if (chunks.isEmpty()) return emptyList()
        if (chunks.size == 1) return chunks
        if (chunks.size == 2) return chunks.sortedBy { it.distanceTo(start) }
        
        // Шаг 1: Жадный алгоритм ближайшего соседа
        val route = nearestNeighbor(chunks, start)
        
        // Шаг 2: Улучшение 2-opt (для маршрутов до 100 чанков)
        return if (route.size <= 100) {
            twoOptImprove(route)
        } else {
            route
        }
    }
    
    /**
     * Жадный алгоритм ближайшего соседа
     * O(n²) сложность
     */
    private fun nearestNeighbor(chunks: List<ChunkPos>, start: ChunkPos): MutableList<ChunkPos> {
        val remaining = chunks.toMutableSet()
        val route = mutableListOf<ChunkPos>()
        var current = start
        
        while (remaining.isNotEmpty()) {
            // Находим ближайший чанк
            val nearest = remaining.minByOrNull { it.distanceTo(current) }!!
            route.add(nearest)
            remaining.remove(nearest)
            current = nearest
        }
        
        return route
    }
    
    /**
     * Алгоритм 2-opt для улучшения маршрута
     * Итеративно устраняет пересечения рёбер
     */
    private fun twoOptImprove(route: MutableList<ChunkPos>): MutableList<ChunkPos> {
        if (route.size < 4) return route
        
        var improved = true
        var iterations = 0
        val maxIterations = 1000 // Ограничение для больших маршрутов
        
        while (improved && iterations < maxIterations) {
            improved = false
            iterations++
            
            for (i in 0 until route.size - 2) {
                for (j in i + 2 until route.size) {
                    if (shouldSwap(route, i, j)) {
                        reverse(route, i + 1, j)
                        improved = true
                    }
                }
            }
        }
        
        return route
    }
    
    /**
     * Проверяет, улучшит ли замена сегмента общую длину
     */
    private fun shouldSwap(route: List<ChunkPos>, i: Int, j: Int): Boolean {
        val a = route[i]
        val b = route[i + 1]
        val c = route[j]
        val d = if (j + 1 < route.size) route[j + 1] else route[0]
        
        // Текущая длина: a-b + c-d
        // Новая длина: a-c + b-d
        val currentDist = a.distanceTo(b) + c.distanceTo(d)
        val newDist = a.distanceTo(c) + b.distanceTo(d)
        
        return newDist < currentDist - 0.001 // Небольшой epsilon для избежания зацикливания
    }
    
    /**
     * Разворачивает сегмент маршрута [start, end]
     */
    private fun reverse(route: MutableList<ChunkPos>, start: Int, end: Int) {
        var i = start
        var j = end
        while (i < j) {
            val temp = route[i]
            route[i] = route[j]
            route[j] = temp
            i++
            j--
        }
    }
    
    /**
     * Рассчитать общую длину маршрута
     */
    fun calculateRouteLength(route: List<ChunkPos>): Double {
        if (route.size < 2) return 0.0
        
        var total = 0.0
        for (i in 0 until route.size - 1) {
            total += route[i].distanceTo(route[i + 1])
        }
        return total
    }
    
    /**
     * Рассчитать приблизительное время выполнения (в секундах)
     * Предполагаем ~10 секунд на чанк (полёт + действия + ожидание)
     */
    fun estimateTime(route: List<ChunkPos>): Int {
        val baseTimePerChunk = 10 // секунд
        val flightTimePerChunk = 2 // дополнительно на полёт
        
        val routeLength = calculateRouteLength(route)
        val flightTime = (routeLength * flightTimePerChunk).toInt()
        
        return route.size * baseTimePerChunk + flightTime
    }
}
