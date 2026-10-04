# FL-World

A generic Kotlin/JVM library for modeling and simulating spatial-temporal worlds.

[![Kotlin](https://img.shields.io/badge/Kotlin-JVM-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

> **The library provides structure. The application provides meaning.**

FL-World provides the infrastructure required to represent entities, locations, connectivity, navigation, movement,
simulation time, scheduled events, observation, snapshots, and restoration.

It is intentionally **not** a game engine. The library remains independent of gameplay rules, narrative, rendering,
networking, persistence formats, and other domain-specific concerns.

## Overview

A world in FL-World is modeled as a combination of three fundamental dimensions:

- **Space** — locations and connections represented by a directed graph.
- **State** — dynamic relationships such as entity locations and active movements.
- **Time** — simulation instants, durations, clocks, calendars, and temporal events.

Entities are first-class elements of the world and remain independent of the spatial structure. An entity may
represent anything the application needs: a player, NPC, monster, animal, item, vehicle, structure, animated object,
inanimate object, or any other domain element.

The library does not distinguish between these meanings. The application does.

## Philosophy

### Structure, not meaning

FL-World models generic concepts such as `WorldEntity`, `WorldLocation`, `WorldConnection`, and `WorldMovement`. It does
not introduce domain classes such as `Player`, `NPC`, `Monster`, `Item`, `City`, or `Dungeon`.

### Space is a graph

The fundamental spatial abstraction is a directed graph:

- `WorldLocation` is a node.
- `WorldConnection` is a directed edge.
- `A → B` does not imply `B → A`.
- Multiple connections between the same locations remain distinct.

Maps, coordinates, and visual representations are application-level concerns.

### Structure and state are separate

`WorldGraph` describes the spatial structure of the world. `WorldState` describes dynamic spatial state, such as the
current location of an entity.

This separation allows topology and simulation state to evolve independently.

### Simulation time is explicit

`WorldInstant` and `WorldDuration` represent simulation time rather than the computer's wall clock. `WorldClock`
advances explicitly, allowing applications to control simulation progression deterministically.

### Domain rules remain external

Rules such as access restrictions, navigation costs, heuristics, movement profiles, and domain event payloads are
supplied by the application through generic APIs.

## Architecture

```text
                              ┌───────────────┐
                              │     World     │
                              └───────┬───────┘
                                      │
          ┌───────────────────────────┼───────────────────────────┐
          │                           │                           │
          ▼                           ▼                           ▼
   ┌─────────────┐             ┌─────────────┐             ┌─────────────┐
   │  Entities   │             │ WorldGraph  │             │ WorldState  │
   └─────────────┘             └─────────────┘             └─────────────┘
                                      │                           │
                                      └────────────┬──────────────┘
                                                   │
                         ┌─────────────────────────┼────────────────────────┐
                         ▼                         ▼                        ▼
                  ┌─────────────┐          ┌─────────────┐          ┌─────────────┐
                  │ WorldClock  │          │  Scheduler  │          │  Movement   │
                  └─────────────┘          └─────────────┘          └─────────────┘
                         │                         │                        │
                         └─────────────────────────┼────────────────────────┘
                                                   ▼
                                            ┌─────────────┐
                                            │ WorldEvent  │
                                            └──────┬──────┘
                                                   ▼
                                            ┌──────────────┐
                                            │ WorldSnapshot│
                                            └──────────────┘
```

`World` coordinates the simulation while the individual components keep their responsibilities focused.

## Features

- Generic entity identity and lifecycle.
- Directed spatial graphs with multiple connections between the same locations.
- Location groups independent from graph topology.
- Path representation and unweighted navigation.
- Access-aware navigation.
- Dijkstra and A* pathfinding with externally supplied costs and heuristics.
- Explicit simulation instants and durations.
- Customizable calendars.
- Scheduled and recurring events.
- Entity movement with temporal progress and completion.
- Deterministic simulation through `World.advance()` and `World.step()`.
- Global observation through `World.onEvent(...)`.
- Explicit propagation of application-originated entity events.
- Snapshots and restoration of the FL-World state.

## Core API

| Concept           | Main types                                                                         |
|-------------------|------------------------------------------------------------------------------------|
| World             | `World`, `WorldSnapshot`                                                           |
| Identity          | `WorldId`, `WorldEntityId`, `WorldLocationId`, `WorldConnectionId`                 |
| Entities          | `WorldEntity`                                                                      |
| Spatial structure | `WorldLocation`, `WorldConnection`, `WorldGraph`                                   |
| Groups            | `WorldGroup`                                                                       |
| State             | `WorldState`                                                                       |
| Paths             | `WorldPath`                                                                        |
| Navigation        | `WorldPathFinder`, `UnweightedPathFinder`, `DijkstraPathFinder`, `AStarPathFinder` |
| Access            | `WorldAccessEvaluator`, `WorldAccessContext`                                       |
| Costs             | `WorldNavigationCost`, `WorldHeuristic`                                            |
| Time              | `WorldInstant`, `WorldDuration`, `WorldClock`                                      |
| Calendar          | `WorldCalendar`, `CalendarDate`                                                    |
| Scheduling        | `WorldScheduler`, `WorldScheduledEvent`, `WorldRecurrence`                         |
| Movement          | `WorldMovement`, `WorldMovementState`                                              |
| Observation       | `WorldEvent`, `WorldObserver`                                                      |

## Getting started

FL-World is intended for Kotlin/JVM projects using Gradle Kotlin DSL.

### Add JitPack

In `settings.gradle.kts`, add JitPack to your dependency repositories:

```kotlin
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
  }
}
```

### Add FL-World

In the module's `build.gradle.kts`:

```kotlin
dependencies {
  implementation(">>>>TODO<<<<")
}
```

Only Gradle Kotlin DSL is documented here intentionally.

## Quick start

Create a world, define its spatial structure, register an entity, and place it in the world:

```kotlin
val world = World(WorldId("example"))

val village = WorldLocation(WorldLocationId("village"))
val castle = WorldLocation(WorldLocationId("castle"))

world.graph.registerLocation(village)
world.graph.registerLocation(castle)
world.graph.registerConnection(
  WorldConnection(
    id = WorldConnectionId("village-to-castle"),
    from = village.id,
    to = castle.id
  )
)

val traveler = WorldEntity(WorldEntityId("traveler"))
world.registerEntity(traveler)
world.state.setLocation(traveler.id, village.id)
```

At this point, FL-World knows that the entity exists and where it is. It does not need to know what a village, castle,
or traveler means to the application.

## Navigation

The graph can discover a path without imposing a domain-specific cost model:

```kotlin
val path = world.graph.findPath(
  from = village.id,
  to = castle.id
)
```

For weighted navigation, provide the cost externally:

```kotlin
val path = world.graph.findPath(
  from = village.id,
  to = castle.id,
  pathFinder = DijkstraPathFinder(costEvaluator),
  accessEvaluator = accessEvaluator,
  accessContext = WorldAccessContext(entityId = traveler.id)
)
```

This keeps access rules and navigation costs outside `WorldGraph`.

## Simulation time

Advance the simulation explicitly:

```kotlin
world.advance(WorldDuration(100))
```

Or progress one known temporal transition at a time:

```kotlin
val result = world.step()
```

Applications can build their own game loop, server loop, command loop, or presentation loop around these operations.
FL-World does not own the application's main loop.

## Events

Observe transitions produced by the world through `World.onEvent(...)`:

```kotlin
world.onEvent { event ->
  println("${event.type} at ${event.instant.value}")
}
```

Application-originated entity events are explicitly published rather than inferred by the library:

```kotlin
world.enableEntityEventPropagation(traveler.id)
world.publishEntityEvent(
  entityId = traveler.id,
  type = "TravelerAction",
  data = "opened-gate"
)
```

An emitted `WorldEvent` includes its simulation instant, type, optional data, optional entity source, and a snapshot of
the world immediately after the event.

## Snapshots

Capture the logical state of the simulation:

```kotlin
val snapshot = world.snapshot()
```

Restore it later:

```kotlin
val restoredWorld = World.restore(snapshot)
```

The application remains responsible for saving its own domain state, such as attributes, inventories, economy, quests,
combat state, or narrative data.

## Use cases

Because the core model is intentionally generic, the same infrastructure can represent very different domains,
including:

- cities connected by roads;
- dungeon rooms and passages;
- abstract regions and zones;
- systems and destinations in a larger spatial network;
- entities that are located or temporarily unlocated;
- moving entities;
- worlds with custom calendars and recurring events.

These are application models built **on top of** FL-World rather than concepts embedded inside it.

## Boundaries

FL-World is responsible for the infrastructure of the simulated world:

- entity identity, existence, lifecycle, location, and movement;
- locations, connections, graphs, groups, paths, and navigation;
- access evaluation, navigation cost, and duration estimation contracts;
- simulation time and calendars;
- scheduled and recurring events;
- deterministic simulation progression;
- observable world transitions;
- snapshots and restoration.

It is deliberately not responsible for:

- combat, damage, or attributes;
- inventories or economy;
- concrete player, NPC, or monster types;
- quests or narrative;
- input and commands;
- UI or rendering;
- networking;
- external persistence;
- domain-specific serialization;
- internal behavior of application entities;
- genre-specific rules.

This boundary is a core architectural constraint.

## Library design

FL-World is designed as a focused library rather than a framework. Public types represent real concepts in the world
model, while implementation details remain internal whenever they do not need to be exposed.

The API intentionally avoids unnecessary factories, builders, wrappers, layers, and domain abstractions. The goal is a
small and composable foundation that can support different applications without becoming a general-purpose game engine.

FL-World can also coexist with other independent libraries. For example, a combat system can operate on application
entities while remaining independent from FL-World's spatial and temporal infrastructure.

## License

FL-World is available under the [MIT License](LICENSE).
