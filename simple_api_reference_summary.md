# API Reference — FL-World

Kotlin library for simulation worlds composed of entities, a directed spatial graph, dynamic state, deterministic time,
scheduled events, movement, and observation. FL-World provides structure; the application provides meaning.

---

## Identifiers

| Type                    | Definition                                                        | Purpose                                                                        |
|-------------------------|-------------------------------------------------------------------|--------------------------------------------------------------------------------|
| `WorldId`               | `@JvmInline value class WorldId(val value: String)`               | Unique world identity. Preserved across snapshots/restorations.                |
| `WorldEntityId`         | `@JvmInline value class WorldEntityId(val value: String)`         | Unique entity identity (player, NPC, item, vehicle, etc.).                     |
| `WorldLocationId`       | `@JvmInline value class WorldLocationId(val value: String)`       | Unique location (node) identity. No coordinates; structure is the graph.       |
| `WorldConnectionId`     | `@JvmInline value class WorldConnectionId(val value: String)`     | Unique directed connection (edge) identity. Unidirectional; multiples allowed. |
| `WorldGroupId`          | `@JvmInline value class WorldGroupId(val value: String)`          | Unique location-group identity (region, dungeon, etc.).                        |
| `WorldScheduledEventId` | `@JvmInline value class WorldScheduledEventId(val value: String)` | Unique scheduled-event identity.                                               |
| `WorldRecurrenceId`     | `@JvmInline value class WorldRecurrenceId(val value: String)`     | Unique temporal-recurrence identity.                                           |

---

## Core Types

### `WorldEntity`

```kotlin
data class WorldEntity(
  val id: WorldEntityId,
  val isEventPropagationEnabled: Boolean = false
)
```

**Purpose:** Generic entity that exists in the world. FL-World does not distinguish domain types; it only manages
identity, lifecycle, location, movement and event propagation.

| Property                    | Type            | Description                                                                   |
|-----------------------------|-----------------|-------------------------------------------------------------------------------|
| `id`                        | `WorldEntityId` | Stable identity while present in the world                                    |
| `isEventPropagationEnabled` | `Boolean`       | When `true`, domain events published via `publishEntityEvent` reach observers |

---

### `WorldLocation`

```kotlin
data class WorldLocation(val id: WorldLocationId)
```

**Purpose:** Location (node) of the spatial graph. Contains only identity; domain attributes stay outside.

---

### `WorldConnection`

```kotlin
data class WorldConnection(
  val id: WorldConnectionId,
  val from: WorldLocationId,
  val to: WorldLocationId
)
```

**Purpose:** Directed edge between two locations.

---

### `WorldEvent`

```kotlin
data class WorldEvent(
  val instant: WorldInstant,
  val type: String,
  val data: Any? = null,
  val sourceId: WorldEntityId? = null,
  val snapshot: WorldSnapshot
)
```

**Purpose:** Observable event that **has already occurred**. Delivered to observers registered with `World.addObserver`.
Contrasts with `WorldScheduledEvent` (something that *will* happen).

| Property   | Type             | Description                                         |
|------------|------------------|-----------------------------------------------------|
| `instant`  | `WorldInstant`   | When the event occurred                             |
| `type`     | `String`         | Textual type identifier (e.g. `"EntityRegistered"`) |
| `data`     | `Any?`           | Optional associated data                            |
| `sourceId` | `WorldEntityId?` | Originating entity, when applicable                 |
| `snapshot` | `WorldSnapshot`  | Immutable world state immediately after the event   |

---

### `WorldObserver` (fun interface)

```kotlin
fun interface WorldObserver {
  fun onEvent(event: WorldEvent)
}
```

**Purpose:** Receives every relevant transition produced by FL-World and any entity events that were explicitly
propagated.

---

## World (Core)

### `World`

```kotlin
class World(val id: WorldId, val calendar: WorldCalendar? = null)
```

**Purpose:** Core of FL-World. Controls entity lifecycle, deterministic temporal evolution (`advance` / `step`),
production of `WorldEvent`s, and snapshot/restore.

| Property    | Type             | Description                                 |
|-------------|------------------|---------------------------------------------|
| `id`        | `WorldId`        | World identity                              |
| `calendar`  | `WorldCalendar?` | Optional calendar for interpreting instants |
| `graph`     | `WorldGraph`     | Spatial structure (locations + connections) |
| `state`     | `WorldState`     | Dynamic entity → location mapping           |
| `clock`     | `WorldClock`     | Simulation clock                            |
| `scheduler` | `WorldScheduler` | Future events and recurrences               |

#### Observers

| Method                       | Input                        | Output | Description                             |
|------------------------------|------------------------------|--------|-----------------------------------------|
| `addObserver(observer)`      | `WorldObserver`              | —      | Registers observer (duplicates ignored) |
| `removeObserver(observer)`   | `WorldObserver`              | —      | Removes a registered observer           |
| `removeObservers(predicate)` | `(WorldObserver) -> Boolean` | —      | Removes matching observers              |
| `clearObservers()`           | —                            | —      | Removes every observer                  |

#### Entity event propagation

| Method                                      | Input                             | Output    | Description                                             |
|---------------------------------------------|-----------------------------------|-----------|---------------------------------------------------------|
| `enableEntityEventPropagation(entityId)`    | `WorldEntityId`                   | —         | Enables domain-event propagation                        |
| `disableEntityEventPropagation(entityId)`   | `WorldEntityId`                   | —         | Disables domain-event propagation                       |
| `isEntityEventPropagationEnabled(entityId)` | `WorldEntityId`                   | `Boolean` | Whether propagation is enabled                          |
| `publishEntityEvent(entityId, type, data)`  | `WorldEntityId`, `String`, `Any?` | —         | Publishes a domain event only if propagation is enabled |

#### Entity lifecycle

| Method                   | Input           | Output         | Description                                     |
|--------------------------|-----------------|----------------|-------------------------------------------------|
| `registerEntity(entity)` | `WorldEntity`   | —              | Registers entity. Throws if id already exists   |
| `getEntity(id)`          | `WorldEntityId` | `WorldEntity?` | Entity by id, or `null`                         |
| `hasEntity(id)`          | `WorldEntityId` | `Boolean`      | Whether the entity exists                       |
| `removeEntity(id)`       | `WorldEntityId` | —              | Removes entity, stops movement, clears location |

#### Groups

| Method                 | Input          | Output        | Description                                                              |
|------------------------|----------------|---------------|--------------------------------------------------------------------------|
| `registerGroup(group)` | `WorldGroup`   | —             | Registers group. All referenced locations must exist. Throws on conflict |
| `getGroup(id)`         | `WorldGroupId` | `WorldGroup?` | Group by id, or `null`                                                   |
| `removeGroup(id)`      | `WorldGroupId` | —             | Removes group (locations stay in the graph)                              |

#### Movement

| Method                    | Input           | Output                | Description                                                                                       |
|---------------------------|-----------------|-----------------------|---------------------------------------------------------------------------------------------------|
| `startMovement(movement)` | `WorldMovement` | —                     | Starts movement. Validates entity, path, graph consistency, current location. Throws on violation |
| `getMovement(entityId)`   | `WorldEntityId` | `WorldMovement?`      | Active movement, or `null`                                                                        |
| `stopMovement(entityId)`  | `WorldEntityId` | —                     | Interrupts active movement; stable location remains origin                                        |
| `getActiveMovements()`    | —               | `List<WorldMovement>` | All active movements, ordered by entity id                                                        |

#### Time & simulation

| Method              | Input           | Output                      | Description                                                                                    |
|---------------------|-----------------|-----------------------------|------------------------------------------------------------------------------------------------|
| `advance(duration)` | `WorldDuration` | `List<WorldScheduledEvent>` | Advances by duration (≥ 0). Processes scheduled events and movements. Returns processed events |
| `step()`            | —               | `WorldStepResult`           | Discrete step to the next relevant future instant (or processes due work at current instant)   |

#### Snapshot / restore

| Method                                         | Input                                                    | Output          | Description                                     |
|------------------------------------------------|----------------------------------------------------------|-----------------|-------------------------------------------------|
| `snapshot()`                                   | —                                                        | `WorldSnapshot` | Complete immutable snapshot of current state    |
| `World.restore(snapshot, calendar, observers)` | `WorldSnapshot`, `WorldCalendar?`, `List<WorldObserver>` | `World`         | Reconstructs a functional world from a snapshot |

---

### `WorldStepResult`

```kotlin
data class WorldStepResult(
  val advanced: Boolean,
  val previousInstant: WorldInstant,
  val currentInstant: WorldInstant,
  val processedEvents: List<WorldScheduledEvent>,
  val completedMovements: List<WorldMovement>
)
```

**Purpose:** Result of a `World.step()` call.

| Property             | Description                                      |
|----------------------|--------------------------------------------------|
| `advanced`           | `true` if the clock advanced to a future instant |
| `previousInstant`    | Instant before the step                          |
| `currentInstant`     | Instant after the step                           |
| `processedEvents`    | Scheduled events that were processed             |
| `completedMovements` | Movements completed during the step              |

---

## Spatial Graph & Navigation

### `WorldPath`

```kotlin
data class WorldPath(
  val origin: WorldLocationId,
  val destination: WorldLocationId,
  val connections: List<WorldConnection> = emptyList()
)
```

**Purpose:** Valid path between two locations as an ordered sequence of connections. Empty connections require
`origin == destination`. Consecutive connections must form a continuous chain.

| Derived     | Type                    | Description                                                    |
|-------------|-------------------------|----------------------------------------------------------------|
| `locations` | `List<WorldLocationId>` | Ordered locations traversed (origin + successive destinations) |

---

### `WorldAccessContext`

```kotlin
data class WorldAccessContext(
  val entityId: WorldEntityId? = null,
  val locationId: WorldLocationId? = null,
  val state: WorldState? = null,
  val payload: Any? = null
)
```

**Purpose:** Context supplied to access evaluation during navigation. FL-World does not interpret the content.

---

### `WorldAccessEvaluator` (fun interface)

```kotlin
fun interface WorldAccessEvaluator {
  fun canTraverse(connection: WorldConnection, context: WorldAccessContext): Boolean
}
```

**Purpose:** External evaluator of connection traversability (keys, level, faction, quest, etc.).

---

### `WorldPathFinder` (interface)

```kotlin
interface WorldPathFinder {
  fun findPath(
    graph: WorldGraph,
    from: WorldLocationId,
    to: WorldLocationId,
    accessEvaluator: WorldAccessEvaluator? = null,
    accessContext: WorldAccessContext = WorldAccessContext()
  ): WorldPath?
}
```

**Purpose:** Path-finding strategy on the graph. All standard implementations respect the access evaluator when
supplied.

---

### Pathfinder implementations

| Class                                       | Algorithm | Notes                                       |
|---------------------------------------------|-----------|---------------------------------------------|
| `UnweightedPathFinder`                      | BFS       | Fewest connections                          |
| `DijkstraPathFinder(costEvaluator)`         | Dijkstra  | Least cost via `WorldNavigationCost`        |
| `AStarPathFinder(costEvaluator, heuristic)` | A*        | Combines cost + admissible `WorldHeuristic` |

---

### `WorldNavigationCost` (fun interface)

```kotlin
fun interface WorldNavigationCost {
  fun getCost(connection: WorldConnection, context: WorldAccessContext): Double
}
```

**Purpose:** Per-connection cost (≥ 0). Units defined by the application.

---

### `WorldHeuristic` (fun interface)

```kotlin
fun interface WorldHeuristic {
  fun estimate(from: WorldLocationId, to: WorldLocationId): Double
}
```

**Purpose:** Admissible heuristic for A* (non-negative, never overestimates remaining true cost).

---

### `WorldGraph`

```kotlin
class WorldGraph
```

**Purpose:** Directed spatial graph. Responsible only for permanent structure (locations + connections). Entities and
dynamic state live in `WorldState`.

#### Public methods

| Method                                 | Input                           | Output                  | Description                                                  |
|----------------------------------------|---------------------------------|-------------------------|--------------------------------------------------------------|
| `registerLocation(location)`           | `WorldLocation`                 | —                       | Registers location. Throws if id exists                      |
| `removeLocation(id)`                   | `WorldLocationId`               | —                       | Removes location + incident connections; notifies World      |
| `registerConnection(connection)`       | `WorldConnection`               | —                       | Registers directed connection. Origin/destination must exist |
| `removeConnection(id)`                 | `WorldConnectionId`             | —                       | Removes connection; notifies World                           |
| `getLocation(id)`                      | `WorldLocationId`               | `WorldLocation?`        | Location by id                                               |
| `getConnection(id)`                    | `WorldConnectionId`             | `WorldConnection?`      | Connection by id                                             |
| `getOutgoingConnections(locationId)`   | `WorldLocationId`               | `List<WorldConnection>` | Outgoing connections, ordered by id                          |
| `getNeighbors(locationId)`             | `WorldLocationId`               | `List<WorldLocationId>` | Neighbouring locations                                       |
| `isReachable(from, to, pathFinder, …)` | ids + optional finder/evaluator | `Boolean`               | Whether a path exists                                        |
| `findPath(from, to, pathFinder, …)`    | ids + optional finder/evaluator | `WorldPath?`            | Found path, or `null`                                        |
| `snapshot()`                           | —                               | `WorldGraphSnapshot`    | Immutable snapshot of structure                              |
| `restore(snapshot)`                    | `WorldGraphSnapshot`            | —                       | Completely replaces previous state                           |

---

### `WorldGraphSnapshot`

```kotlin
data class WorldGraphSnapshot(
  val locations: List<WorldLocation>,
  val connections: List<WorldConnection>
)
```

**Purpose:** Immutable snapshot of the spatial structure.

---

### `WorldState`

```kotlin
class WorldState
```

**Purpose:** Dynamic spatial state of entities (`entity → location`). An entity may exist without a location. Separated
from the permanent graph.

| Method                              | Input           | Output                                | Description                                                            |
|-------------------------------------|-----------------|---------------------------------------|------------------------------------------------------------------------|
| `setLocation(entityId, locationId)` | ids             | —                                     | Places/re-places entity. Validates existence and movement restrictions |
| `getLocation(entityId)`             | `WorldEntityId` | `WorldLocationId?`                    | Current location, or `null`                                            |
| `removeLocation(entityId)`          | `WorldEntityId` | —                                     | Clears location (entity continues to exist)                            |
| `snapshot()`                        | —               | `Map<WorldEntityId, WorldLocationId>` | Immutable mapping                                                      |
| `restore(snapshot)`                 | `Map<…>`        | —                                     | Restores mapping                                                       |

---

### `WorldGroup`

```kotlin
class WorldGroup(val id: WorldGroupId)
```

**Purpose:** Grouping of locations (region, forest, dungeon, …). Not a graph node; only references existing locations. A
location may belong to several groups.

| Method                       | Input                | Output                 | Description                         |
|------------------------------|----------------------|------------------------|-------------------------------------|
| `addLocation(locationId)`    | `WorldLocationId`    | —                      | Adds location (must exist in graph) |
| `removeLocation(locationId)` | `WorldLocationId`    | —                      | Removes location from group         |
| `hasLocation(locationId)`    | `WorldLocationId`    | `Boolean`              | Membership check                    |
| `getLocations()`             | —                    | `Set<WorldLocationId>` | Immutable set of locations          |
| `snapshot()`                 | —                    | `WorldGroupSnapshot`   | Immutable snapshot                  |
| `restore(snapshot)`          | `WorldGroupSnapshot` | —                      | Restores group                      |

---

### `WorldGroupSnapshot`

```kotlin
data class WorldGroupSnapshot(
  val id: WorldGroupId,
  val locations: Set<WorldLocationId>
)
```

---

## Time

### `WorldDuration`

```kotlin
@JvmInline
value class WorldDuration(val value: Long) : Comparable<WorldDuration>
```

**Purpose:** Non-negative quantity of simulation time. Units defined by the application.

---

### `WorldInstant`

```kotlin
@JvmInline
value class WorldInstant(val value: Long) : Comparable<WorldInstant>
```

**Purpose:** Point in simulation time. Independent of the system clock.

| Operator             | Description                       |
|----------------------|-----------------------------------|
| `instant - other`    | `WorldDuration` (may be negative) |
| `instant + duration` | `WorldInstant`                    |
| `instant - duration` | `WorldInstant`                    |

---

### `CalendarDate`

```kotlin
data class CalendarDate(
  val year: Long, val month: Int, val day: Int,
  val hour: Int, val minute: Int, val second: Int
)
```

**Purpose:** Date interpreted from a `WorldInstant` according to a `WorldCalendar`. Fields do not assume terrestrial
conventions.

---

### `WorldClock`

```kotlin
class WorldClock(initialInstant: WorldInstant = WorldInstant(0))
```

**Purpose:** Simulation clock. Holds the current instant; does not use the OS clock.

| Property / Method   | Description                                         |
|---------------------|-----------------------------------------------------|
| `currentInstant`    | Current simulation instant (read-only externally)   |
| `advance(duration)` | Advances by duration ≥ 0. Publishes `ClockAdvanced` |
| `restore(instant)`  | Sets instant (used during snapshot restoration)     |

---

### `WorldCalendar` (interface)

```kotlin
interface WorldCalendar {
  fun toDate(instant: WorldInstant): CalendarDate
}
```

**Purpose:** Interprets a simulation instant as a calendar date. Implementation supplied by the application.

---

## Scheduling

### `WorldRecurrence`

```kotlin
data class WorldRecurrence(
  val id: WorldRecurrenceId,
  val interval: WorldDuration
)
```

**Purpose:** Temporal recurrence definition. Interval must be positive.

---

### `WorldScheduledEvent`

```kotlin
data class WorldScheduledEvent(
  val id: WorldScheduledEventId,
  val instant: WorldInstant,
  val type: String,
  val payload: Any? = null,
  val recurrenceId: WorldRecurrenceId? = null
) : Comparable<WorldScheduledEvent>
```

**Purpose:** Event scheduled to occur at a future instant (“something that *will* happen”). Comparable by instant, then
id.

---

### `WorldScheduler`

```kotlin
class WorldScheduler
```

**Purpose:** Scheduler of future events and recurrences. Deterministic. Events in the past cannot be scheduled.
Processing an event with a recurrence automatically schedules the next occurrence.

| Method                              | Input                    | Output                      | Description                                                            |
|-------------------------------------|--------------------------|-----------------------------|------------------------------------------------------------------------|
| `schedule(event)`                   | `WorldScheduledEvent`    | —                           | Schedules future event. Throws on conflict / past / missing recurrence |
| `cancel(eventId)`                   | `WorldScheduledEventId`  | —                           | Cancels scheduled event                                                |
| `defineRecurrence(recurrence)`      | `WorldRecurrence`        | —                           | Defines recurrence. Throws if id exists                                |
| `cancelRecurrence(recurrenceId)`    | `WorldRecurrenceId`      | —                           | Cancels recurrence (already-scheduled events are not auto-cancelled)   |
| `getRecurrence(recurrenceId)`       | `WorldRecurrenceId`      | `WorldRecurrence?`          | Recurrence by id                                                       |
| `getFutureEvents(afterInstant)`     | `WorldInstant`           | `List<WorldScheduledEvent>` | Future events after the instant, ordered                               |
| `processEventsUpTo(currentInstant)` | `WorldInstant`           | `List<WorldScheduledEvent>` | Processes all due events; reschedules recurrences                      |
| `snapshot()`                        | —                        | `WorldSchedulerSnapshot`    | Immutable snapshot                                                     |
| `restore(snapshot)`                 | `WorldSchedulerSnapshot` | —                           | Restores scheduler                                                     |

---

### `WorldSchedulerSnapshot`

```kotlin
data class WorldSchedulerSnapshot(
  val events: List<WorldScheduledEvent>,
  val recurrences: List<WorldRecurrence>
)
```

---

## Movement

### `WorldDurationEstimator` (interface)

```kotlin
interface WorldDurationEstimator {
  fun estimateDuration(path: WorldPath, cost: Double, profile: Any? = null): WorldDuration
}
```

**Purpose:** External estimator of movement duration. Speeds and domain rules stay outside FL-World.

---

### `WorldMovementState` (enum)

| Value         | Meaning                       |
|---------------|-------------------------------|
| `IN_PROGRESS` | Active movement               |
| `COMPLETED`   | Reached destination           |
| `INTERRUPTED` | Interrupted before completion |

---

### `WorldMovement`

```kotlin
data class WorldMovement(
  val entityId: WorldEntityId,
  val origin: WorldLocationId,
  val destination: WorldLocationId,
  val path: WorldPath,
  val startInstant: WorldInstant,
  val duration: WorldDuration,
  val progress: Double = 0.0,
  val state: WorldMovementState = WorldMovementState.IN_PROGRESS
)
```

**Purpose:** Representation of an entity displacement along a path. While in progress, the stable location in
`WorldState` remains the origin.

| Derived / Method                                 | Description                                                 |
|--------------------------------------------------|-------------------------------------------------------------|
| `completionInstant`                              | `startInstant + duration`                                   |
| `isInProgress` / `isCompleted` / `isInterrupted` | State predicates                                            |
| `progressAt(currentInstant)`                     | Progress ∈ [0.0, 1.0] at the given instant                  |
| `updateAt(currentInstant)`                       | Updated copy; becomes `COMPLETED` when progress reaches 1.0 |

**Constraints:** `progress ∈ [0.0, 1.0]`, `duration > 0`, completed movements must have full progress.

---

## Snapshot

### `WorldSnapshot`

```kotlin
data class WorldSnapshot(
  val id: WorldId,
  val entities: List<WorldEntity>,
  val entityLocations: Map<WorldEntityId, WorldLocationId>,
  val graph: WorldGraphSnapshot,
  val groups: List<WorldGroupSnapshot>,
  val calendar: WorldCalendar?,
  val currentInstant: WorldInstant,
  val scheduler: WorldSchedulerSnapshot,
  val activeMovements: List<WorldMovement>
)
```

**Purpose:** Immutable and complete snapshot of a `World` state. Sufficient to reconstruct an equivalent world via
`World.restore`. Does **not** include domain data (attributes, inventory, quests, etc.).

---

## Architecture Overview

```
World
 ├── WorldGraph          permanent structure (locations + connections)
 ├── WorldState           dynamic entity → location mapping
 ├── WorldClock           simulation instant
 ├── WorldScheduler       future events + recurrences
 ├── entities / groups / movements
 └── observers ← WorldEvent (with post-event WorldSnapshot)

Simulation loop:
  advance(duration) / step()
    → process scheduled events (reschedule recurrences)
    → update / complete movements
    → emit WorldEvents
```

Clear separation of responsibilities:

- **WorldGraph** → permanent spatial structure
- **WorldState** → dynamic positions
- **WorldClock / WorldScheduler** → deterministic time
- **WorldMovement** → in-progress displacement (stable location stays origin)
- **WorldPathFinder** → pluggable navigation (BFS / Dijkstra / A*)
- **WorldObserver / WorldEvent** → observation of transitions
- **WorldSnapshot / restore** → full state capture and reconstruction