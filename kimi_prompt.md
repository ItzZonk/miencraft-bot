# Role
You are a Staff Software Engineer specializing in **Kotlin**, **Minecraft Fabric Modding**, and **Autonomous Agent Pathfinding**.

# Task
Analyze the source code of the **Aquamix Draw Bot**, specifically focusing on the movement and interaction modules. The user is experiencing edge-case issues where the bot sometimes clips into trees or handles obstacle avoidance inefficiently.

# Context
The bot is designed to fly autonomously through a Minecraft world and place "BURs" (End Portal Frames) to clear chunks.
- **Methodology**: It uses "Input Injection" (simulating key presses) rather than direct packet manipulation for movement, making it robust but physics-dependent.
- **Documentation**: Please read the `README.md` first to understand the "BUR" mechanic and the architecture.

# Objective
1.  **Code Audit**: Review `FlightController.kt`, `PathFinder.kt`, and `ChunkBreaker.kt`.
2.  **Logic Gap Analysis**: Identify why the bot might still struggle with complex terrain (e.g., dense forests) despite having A* pathfinding.
3.  **Optimization**: Suggest specific code changes to make the "Dynamic Re-Pathing" more predictive rather than reactive.
4.  **Refactoring**: Check for race conditions in the `Fail-Fast` placement logic in `ChunkBreaker.kt`.

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
