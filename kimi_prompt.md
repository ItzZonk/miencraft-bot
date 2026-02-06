# Role
You are a Staff Software Engineer specializing in **Kotlin**, **Minecraft Fabric Modding**, and **Autonomous Agent Pathfinding**.

# Task
Analyze the source code of the **Aquamix Draw Bot**, specifically focusing on the movement and interaction modules. The user is experiencing edge-case issues where the bot sometimes clips into trees or handles obstacle avoidance inefficiently.

# Context
The bot is designed to fly autonomously through a Minecraft world and place "BURs" (End Portal Frames) to clear chunks.
- **Methodology**: It uses "Input Injection" (simulating key presses) rather than direct packet manipulation for movement, making it robust but physics-dependent.
- **Documentation**: Please read the `README.md` first to understand the "BUR" mechanic and the architecture.

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

# Reference Files
- `README.md`: System overview.
- `src/main/kotlin/com/aquamix/drawbot/automation/FlightController.kt`: Movement logic.
- `src/main/kotlin/com/aquamix/drawbot/pathing/PathFinder.kt`: A* logic types.
- `src/main/kotlin/com/aquamix/drawbot/automation/ChunkBreaker.kt`: Block interaction logic.

# Repository Access
The full source code is available here:
- **GitHub Repo**: [View Code](https://github.com/ItzZonk/miencraft-bot)
- **Source Tree**: `src/main/kotlin/com/aquamix/drawbot/`

Please refer to the repository for the complete context of imports and helper classes.

Please provide a detailed analysis and specific code snippets for recommended fixes.
