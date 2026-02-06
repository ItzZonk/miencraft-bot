# Aquamix Draw Bot 🤖

## Overview
**Aquamix Draw Bot** is a specialized Minecraft fabric mod designed to automate large-scale chunk clearing and building operations on the Aquamix server. It operates by autonomously flying to specific chunks and placing a special tool—the **BUR** (End Portal Frame)—which triggers server-side world editing/clearing events.

## Core Mechanics

### 1. The "BUR" (Block Update Request) 🔮
- **Item**: End Portal Frame (`minecraft:end_portal_frame`).
- **Function**: On this specific server, placing a BUR on a block triggers a chunk-clearing or building operation.
- **Bot Behavior**: The bot scans the target chunk for a valid solid block (closest to the top but within range), checks for obstacles, and places the BUR. If the placement fails or is obstructed, it attempts to clear obstacles (grass/kelp) or find a neighbor block.

### 2. Flight & Movement ✈️
The bot uses a **custom flight controller** (`FlightController.kt`) that simulates player input to achieve 3D movement.
- **Input Override**: It hijacks standard player inputs (`InputOverrideHandler.kt`) using a Baritone-like approach, sending `W, A, S, D, JUMP, SNEAK` packets to the client.
- **Fail-Safe Gliding**: It constantly ensures `abilities.flying` is true. If the bot falls, it detects the descent and recovers by flying up.
- **Anti-Spin**: If stuck, it ascends vertically rather than rotating blindly.
- **Cinematic Turns**: Rotation (`yaw`/`pitch`) is smoothed (Lerped) to look natural and avoid anti-cheat triggers.

### 3. Pathfinding & Navigation 🧭
- **A* Algorithm**: `PathFinder.kt` implements a 3D A* search to find paths between chunks.
- **Obstacle Avoidance**: It treats `Leaves`, `Logs`, and `Planks` as **SOLID OBSTACLES** to avoid getting stuck in trees during low-altitude flight.
- **Dynamic Re-Pathing**: Before every movement segment, it Raycasts ahead. If a tree or wall suddenly appears (chunk load), it invalidates the current path and recalculates instantly.

### 4. 3D Scanning 📡
- **Target Selection**: `ChunkBreaker.kt` scans the target chunk to find the optimal placement spot for the BUR.
- **Fail-Fast**: If a placement attempt fails (e.g., server lag, invisible wall), it retries up to 5 alternative spots **in the same tick** to ensure maximum efficiency.

## Architecture

- **`BotController.kt`**: Main brain. Manages the state machine (Flying -> Breaker -> Next Chunk).
- **`FlightController.kt`**: Low-level movement logic. Handles physics, rotation, and path following.
- **`ChunkBreaker.kt`**: Interaction logic. Scans blocks, handles tools (Pickaxe vs Hand), clears grass, and places BURs.
- **`InputOverrideHandler.kt`**: Middleware that forces input states into the Minecraft client.
- **`PathFinder.kt`**: Utility for calculating 3D paths.

## Key Features for Review

- **Global Sneak Lock**: To prevent the bot from moving slowly, `SNEAK` is forcefully disabled in the input handler whenever the bot is sprinting horizontally.
- **Fluid Handling**: The bot treats water as valid terrain for movement but ensures it hovers *above* it when placing blocks.
- **Grass Passability**: Grass and Flowers are treated as "Air" by the pathfinder (Cost 0), allowing the bot to fly through fields without detouring, breaking them if they block placement.

## Building & Running
1. Clone the repo.
2. Run `./gradlew build`.
3. The jar will be in `build/libs/`.

## Repository & Source Code 📂
- **GitHub Repository**: [View on GitHub](<YOUR_GITHUB_REPO_URL>)
- **Source Tree**: [Browse Code](<YOUR_GITHUB_REPO_URL>/tree/master/src/main/kotlin/com/aquamix/drawbot)

*Please replace `<YOUR_GITHUB_REPO_URL>` with your actual repository URL before sharing.*
