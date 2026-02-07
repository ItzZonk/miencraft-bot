# Role
You are a Principal Software Architect specializing in **High-Performance Minecraft Bots**, **Concurrency**, and **Control Theory**.

# Task
Your mission is to upgrade the **Aquamix Draw Bot** from a naive script to a sophisticated **Autonomous Agent (Phase 3)**. The current bot uses basic Vector Field Navigation and brute-force block scanning, which causes frame drops and jerky movement.

# Current State
- **Movement**: Vector Field Navigation (Attraction/Repulsion) implementation in `FlightController.kt`. Functional but lacks inertia handling.
- **Scanning**: Main-thread block iteration (`ChunkBreaker.kt`). Causes lag spikes.
- **Interaction**: Basic packet placement with fail-fast retries.

<<<<<<< HEAD
# Progress Update (Phase 1 Complete)
We have implemented your previous recommendations:
1.  **Predictive Look-Ahead**: `FlightController` now checks 3 nodes ahead.
2.  **Width-Awareness**: `PathFinder` checks 4 corners of the bot's hitbox.
3.  **State Machine**: `ChunkBreaker` now uses a strict `PlacementState` enum to avoid race conditions.
4.  **Forest Heuristic**: `PathFinder` penalizes horizontal movement in dense areas.

# Objective (Phase 2)
Please analyze the code again (check the latest commit) and provide **Advanced Optimizations**:
1.  **Performance**: Are there expensive operations in `onTick` that could be cached?
2.  **Safety**: Are there edge cases where the `PlacementState` machine could get stuck (zombie state)?
3.  **Next-Level Logic**: How can we make the bot "smarter" about choosing which chunk to go to next? currently it uses a simple distance sort.

Please provide code snippets for these "Phase 2" improvements.
=======
# Objectives (Phase 3 Roadmap)

1.  **Multi-threaded Spatial Indexing (CRITICAL)**:
    - Offload all block scanning to a background daemon thread (`Executors.newSingleThreadExecutor`).
    - Implement `ChunkSpatialIndex` to cache target blocks in a thread-safe manner.
    - Result: **Zero lag spikes** during flight/scanning.

2.  **PID Flight Controller**:
    - Replace the raw "Speed = 2.0" logic with a **PID Controller** for smooth velocity transitions.
    - Implement `FlightWaypoint` system with approach vectors to eliminate "stop-and-go" at chunk borders.
    - Goal: Cinematic, fluid movement that mimics a skilled player.

3.  **Predictive Lag Compensation**:
    - Implement `LagCompensator` using a **Kalman Filter** to estimate server latency.
    - Dynamically adjust `ClickDelay` and `BreakTimeout` based on ping jitter.
    - Goal: Resilience against 500ms+ lag spikes.

4.  **Behavioral Mimicry (Anti-Cheat)**:
    - Inject stochastic "human noise" into rotation (Simplex noise).
    - Implement "Micro-Pauses" (simulated distractions) and "Overshoot" (imperfect aiming).
>>>>>>> d4380ea (Save local progress and add prompts)

# Reference Files
- `src/main/kotlin/com/aquamix/drawbot/automation/FlightController.kt`: Movement logic (Target for PID).
- `src/main/kotlin/com/aquamix/drawbot/automation/ChunkBreaker.kt`: Interaction logic (Target for Threading).
- `README.md`: System overview.

# Repository Access
- **Source**: `src/main/kotlin/com/aquamix/drawbot/`

Please analyze the codebase and provide a concrete implementation plan for **Objective 1 (Multi-threading)** first.
