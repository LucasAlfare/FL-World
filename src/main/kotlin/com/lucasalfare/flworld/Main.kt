@file:Suppress("unused")

package com.lucasalfare.flworld

/**
 * Unique identifier of a world.
 *
 * A [WorldId] distinguishes [World] instances and is preserved across
 * snapshots and restorations. FL-World does not interpret the value;
 * the application defines its meaning (name, UUID, save key, etc.).
 */
@JvmInline
value class WorldId(val value: String)

/**
 * Unique identifier of an entity in the world.
 *
 * Entities are first-class elements: player, NPC, monster, item,
 * vehicle, inanimate object, or any other domain concept.
 * FL-World treats all of them generically; meaning is provided by the application.
 */
@JvmInline
value class WorldEntityId(val value: String)

/**
 * Unique identifier of a location (node of the spatial graph).
 *
 * A location represents a place where entities can exist.
 * It has no coordinates or geometry; the spatial structure is the graph.
 */
@JvmInline
value class WorldLocationId(val value: String)

/**
 * Unique identifier of a connection (directed edge of the graph).
 *
 * Connections are unidirectional: `A → B` does not imply `B → A`.
 * Multiple distinct connections between the same nodes are allowed.
 */
@JvmInline
value class WorldConnectionId(val value: String)

/**
 * Unique identifier of a location group.
 *
 * Groups (forest, region, dungeon, star system, etc.) are not nodes
 * of the graph; they only reference existing locations.
 */
@JvmInline
value class WorldGroupId(val value: String)

/**
 * Unique identifier of a scheduled event.
 */
@JvmInline
value class WorldScheduledEventId(val value: String)

/**
 * Unique identifier of a temporal recurrence definition.
 */
@JvmInline
value class WorldRecurrenceId(val value: String)

/**
 * Generic entity that exists in the world.
 *
 * Represents any element the application wants to place in the world
 * (player, NPC, item, object, etc.). FL-World does not distinguish
 * domain types; it only manages identity, lifecycle, location,
 * movement and event propagation.
 *
 * @property id Stable identity while the entity exists in the world.
 * @property isEventPropagationEnabled When `true`, domain events
 *   published by the application via [World.publishEntityEvent] are
 *   delivered to global observers. FL-World never inspects the domain;
 *   the application decides what to propagate.
 */
data class WorldEntity(
  val id: WorldEntityId, val isEventPropagationEnabled: Boolean = false
)

/**
 * Location (node) of the spatial graph.
 *
 * Contains only the identity required. Domain attributes
 * (name, description, terrain type, etc.) stay outside FL-World.
 */
data class WorldLocation(val id: WorldLocationId)

/**
 * Directed connection between two locations.
 *
 * @property id Connection identity.
 * @property from Origin location.
 * @property to Destination location.
 */
data class WorldConnection(
  val id: WorldConnectionId, val from: WorldLocationId, val to: WorldLocationId
)

/**
 * Observable event that has already occurred in the world.
 *
 * Unlike [WorldScheduledEvent] (something that *will* happen),
 * a [WorldEvent] represents something that *has happened*.
 * It is delivered to observers registered with [World.addObserver].
 *
 * @property instant Instant at which the event occurred.
 * @property type Textual type identifier (e.g. "EntityRegistered").
 * @property data Optional associated data (may be domain-specific).
 * @property sourceId Originating entity, when applicable.
 * @property snapshot Immutable world state immediately after the event.
 *   Later mutations of the world do not affect this snapshot.
 */
data class WorldEvent(
  val instant: WorldInstant,
  val type: String,
  val data: Any? = null,
  val sourceId: WorldEntityId? = null,
  val snapshot: WorldSnapshot
)

/**
 * Observer of world events.
 *
 * The application implements this interface (or uses a lambda) to
 * receive every relevant transition produced by FL-World and any
 * entity events that were explicitly propagated.
 */
fun interface WorldObserver {
  /**
   * Called for every published [WorldEvent].
   *
   * @param event The occurred event, including the post-event snapshot.
   */
  fun onEvent(event: WorldEvent)
}

/**
 * Core of FL-World: a world composed of entities, space (graph),
 * state, time, movement, scheduled events and observation.
 *
 * The library provides structure; the application provides meaning.
 * [World] controls entity lifecycle, deterministic temporal evolution
 * (`advance` / `step`), production of [WorldEvent]s and snapshot/restore.
 *
 * @property id World identity.
 * @property calendar Optional calendar used to interpret instants.
 *   May be `null` if the application does not need dates.
 */
class World(val id: WorldId, val calendar: WorldCalendar? = null) {
  private val entities = mutableMapOf<WorldEntityId, WorldEntity>()
  private val groups = mutableMapOf<WorldGroupId, WorldGroup>()
  private val movements = mutableMapOf<WorldEntityId, WorldMovement>()
  private val observers = mutableListOf<WorldObserver>()

  /** Indicates that the world is being restored; events are suppressed. */
  internal var isRestoring = false

  /**
   * Spatial graph of the world (locations and connections).
   *
   * Represents structure only; entities are not stored here.
   */
  val graph = WorldGraph().apply {
    eventPublisher = ::notifyObservers
    locationRemovalHandler = ::handleLocationRemoval
    connectionRemovalHandler = ::handleConnectionRemoval
    restoreValidator = ::validateGraphRestore
  }

  /**
   * Dynamic spatial state of entities (`entity → location`).
   *
   * Separated from the graph: permanent structure lives in [graph];
   * current entity positions live here.
   */
  val state = WorldState().apply {
    eventPublisher = ::notifyObservers
    entityValidator = ::hasEntity
    locationValidator = { graph.getLocation(it) != null }
    locationChangeValidator = ::validateEntityLocationChange
  }

  /**
   * Simulation clock.
   *
   * Controls the current world instant. Does not use the system clock.
   */
  val clock = WorldClock().apply {
    eventPublisher = ::notifyObservers
  }

  /**
   * Scheduler of future events and recurrences.
   */
  val scheduler = WorldScheduler().apply {
    eventPublisher = ::notifyObservers
    currentInstantProvider = { clock.currentInstant }
  }

  /**
   * Registers an event observer.
   *
   * Duplicate observers are ignored. During snapshot restoration
   * events are silenced.
   *
   * @param observer Observer to add.
   */
  fun addObserver(observer: WorldObserver) {
    if (!observers.contains(observer)) {
      observers.add(observer)
    }
  }

  /**
   * Removes a previously registered observer.
   *
   * @param observer Observer to remove.
   */
  fun removeObserver(observer: WorldObserver) {
    observers.remove(observer)
  }

  /**
   * Removes all observers that satisfy the predicate.
   *
   * @param predicate Removal predicate.
   */
  fun removeObservers(predicate: (WorldObserver) -> Boolean) {
    observers.removeAll(predicate)
  }

  /**
   * Removes every registered observer.
   */
  fun clearObservers() {
    observers.clear()
  }

  /**
   * Publishes an internal FL-World event to all observers.
   *
   * Suppressed during restoration or when no observers are present.
   * The attached snapshot reflects the state immediately after the transition.
   */
  internal fun notifyObservers(type: String, data: Any? = null, sourceId: WorldEntityId? = null) {
    if (isRestoring || observers.isEmpty()) return
    val event = WorldEvent(
      instant = clock.currentInstant, type = type, data = data, sourceId = sourceId, snapshot = snapshot()
    )
    observers.toList().forEach { it.onEvent(event) }
  }

  /**
   * Enables propagation of domain events originated by the entity.
   *
   * After enabling, calls to [publishEntityEvent] generate
   * [WorldEvent]s delivered to observers.
   *
   * @param entityId Entity whose propagation will be enabled.
   */
  fun enableEntityEventPropagation(entityId: WorldEntityId) {
    setEntityEventPropagation(entityId, true)
  }

  /**
   * Disables propagation of domain events for the entity.
   *
   * @param entityId Entity whose propagation will be disabled.
   */
  fun disableEntityEventPropagation(entityId: WorldEntityId) {
    setEntityEventPropagation(entityId, false)
  }

  /**
   * Indicates whether the entity has event propagation enabled.
   *
   * @param entityId Entity being queried.
   * @return `true` if propagation is enabled.
   */
  fun isEntityEventPropagationEnabled(entityId: WorldEntityId): Boolean {
    return entities[entityId]?.isEventPropagationEnabled ?: false
  }

  private fun setEntityEventPropagation(entityId: WorldEntityId, enabled: Boolean) {
    val entity = entities[entityId] ?: return
    if (entity.isEventPropagationEnabled == enabled) return
    entities[entityId] = entity.copy(isEventPropagationEnabled = enabled)
    notifyObservers("EntityEventPropagationChanged", enabled, entityId)
  }

  /**
   * Publishes an event originated by an entity.
   *
   * Generates a [WorldEvent] only if the entity’s propagation is enabled.
   * FL-World never inspects the domain; the application decides what to emit.
   *
   * @param entityId Originating entity.
   * @param type Textual type identifier of the event.
   * @param data Optional domain data.
   */
  fun publishEntityEvent(entityId: WorldEntityId, type: String, data: Any? = null) {
    val entity = entities[entityId] ?: return
    if (!entity.isEventPropagationEnabled || isRestoring || observers.isEmpty()) return
    val event = WorldEvent(
      instant = clock.currentInstant, type = type, data = data, sourceId = entityId, snapshot = snapshot()
    )
    observers.toList().forEach { it.onEvent(event) }
  }

  /**
   * Registers a new entity in the world.
   *
   * The entity becomes a first-class element.
   * Throws if the identifier is already in use.
   *
   * @param entity Entity to register.
   * @throws IllegalArgumentException if the id already exists.
   */
  fun registerEntity(entity: WorldEntity) {
    require(entity.id !in entities) { "Entity '${entity.id.value}' is already registered." }
    entities[entity.id] = entity
    notifyObservers("EntityRegistered", entity, entity.id)
  }

  /**
   * Returns the entity by identifier, or `null` if it does not exist.
   *
   * @param id Entity identifier.
   */
  fun getEntity(id: WorldEntityId): WorldEntity? {
    return entities[id]
  }

  /**
   * Checks whether the entity exists in the world.
   *
   * @param id Entity identifier.
   */
  fun hasEntity(id: WorldEntityId): Boolean {
    return entities.containsKey(id)
  }

  /**
   * Removes the entity from the world.
   *
   * Interrupts any active movement, removes its location and cleans up references.
   * The identity becomes invalid after removal.
   *
   * @param id Identifier of the entity to remove.
   */
  fun removeEntity(id: WorldEntityId) {
    if (id !in entities) return
    stopMovement(id)
    state.removeLocation(id)
    entities.remove(id)
    notifyObservers("EntityRemoved", id, id)
  }

  /**
   * Registers a location group.
   *
   * All locations referenced by the group must already exist in the graph.
   *
   * @param group Group to register.
   * @throws IllegalArgumentException if the id already exists or if any
   *   referenced location is missing from the graph.
   */
  fun registerGroup(group: WorldGroup) {
    require(group.id !in groups) { "Group '${group.id.value}' is already registered." }
    group.getLocations().forEach { locationId ->
      require(graph.getLocation(locationId) != null) {
        "Location '${locationId.value}' must be registered in the graph."
      }
    }
    group.eventPublisher = ::notifyObservers
    group.locationValidator = { graph.getLocation(it) != null }
    groups[group.id] = group
    notifyObservers("GroupRegistered", group)
  }

  /**
   * Returns the group by identifier, or `null` if it does not exist.
   *
   * @param id Group identifier.
   */
  fun getGroup(id: WorldGroupId): WorldGroup? {
    return groups[id]
  }

  /**
   * Removes a group from the world.
   *
   * Does not remove the locations from the graph; only the grouping.
   *
   * @param id Identifier of the group to remove.
   */
  fun removeGroup(id: WorldGroupId) {
    val group = groups.remove(id) ?: return
    group.eventPublisher = null
    group.locationValidator = null
    notifyObservers("GroupRemoved", id)
  }

  /**
   * Starts an entity movement along a path.
   *
   * Validates entity existence, absence of an active movement,
   * path validity, consistency with the graph and with the current
   * stable location. The movement is driven by the world clock.
   *
   * @param movement Movement to start (must be in [WorldMovementState.IN_PROGRESS]).
   * @throws IllegalArgumentException if any invariant is violated.
   */
  fun startMovement(movement: WorldMovement) {
    require(movement.entityId in entities) {
      "Entity '${movement.entityId.value}' must exist to start a movement."
    }
    require(movement.entityId !in movements) {
      "Entity '${movement.entityId.value}' already has an active movement."
    }
    require(movement.state == WorldMovementState.IN_PROGRESS) {
      "A started movement must be in progress."
    }
    require(movement.duration.value > 0L) {
      "Movement duration must be positive."
    }
    require(movement.startInstant <= clock.currentInstant) {
      "Movement start instant cannot be in the future."
    }
    require(movement.completionInstant > clock.currentInstant) {
      "Movement should already be completed at the current instant."
    }
    require(graph.getLocation(movement.origin) != null) {
      "Origin location '${movement.origin.value}' does not exist in the graph."
    }
    require(graph.getLocation(movement.destination) != null) {
      "Destination location '${movement.destination.value}' does not exist in the graph."
    }
    require(movement.path.origin == movement.origin && movement.path.destination == movement.destination) {
      "Movement path must have the same origin and destination as the movement."
    }
    validatePath(movement.path)

    val currentLocation = state.getLocation(movement.entityId)
    require(currentLocation == null || currentLocation == movement.origin) {
      "Current entity location must be null or equal to the movement origin."
    }

    movements[movement.entityId] = movement
    notifyObservers("MovementStarted", movement, movement.entityId)
  }

  /**
   * Returns the active movement of the entity, or `null` if none.
   *
   * @param entityId Entity being queried.
   */
  fun getMovement(entityId: WorldEntityId): WorldMovement? {
    return movements[entityId]
  }

  /**
   * Interrupts the active movement of the entity.
   *
   * Publishes a `MovementInterrupted` event. The stable location
   * remains the origin (the entity does not jump to the destination).
   *
   * @param entityId Entity whose movement will be interrupted.
   */
  fun stopMovement(entityId: WorldEntityId) {
    val movement = movements.remove(entityId) ?: return
    val interrupted = movement.copy(state = WorldMovementState.INTERRUPTED)
    notifyObservers("MovementInterrupted", interrupted, entityId)
  }

  /**
   * Lists all active movements, ordered by entity id.
   */
  fun getActiveMovements(): List<WorldMovement> {
    return movements.values.sortedBy { it.entityId.value }
  }

  /**
   * Produces an immutable snapshot of the complete world state.
   *
   * The snapshot is independent of mutable objects and sufficient
   * to reconstruct an equivalent [World] via [restore].
   * Domain data (attributes, inventory, quests, etc.) are not part
   * of the snapshot; the application is responsible for them.
   *
   * @return Complete snapshot of the current state.
   */
  fun snapshot(): WorldSnapshot {
    return WorldSnapshot(
      id = id,
      entities = entities.values.sortedBy { it.id.value }.map { it.copy() },
      entityLocations = state.snapshot(),
      graph = graph.snapshot(),
      groups = groups.values.sortedBy { it.id.value }.map { it.snapshot() },
      calendar = calendar,
      currentInstant = clock.currentInstant,
      scheduler = scheduler.snapshot(),
      activeMovements = getActiveMovements().map { it.copy() })
  }

  /**
   * Advances the simulation by an arbitrary duration.
   *
   * Processes, in deterministic order:
   * - scheduled events and recurrences;
   * - movement progression and completion;
   * - other temporal transitions.
   *
   * Every observable transition generates a [WorldEvent].
   *
   * @param duration Advance duration (must not be negative).
   * @return List of scheduled events processed during the advance.
   */
  fun advance(duration: WorldDuration): List<WorldScheduledEvent> {
    require(duration.value >= 0L) { "Advance duration cannot be negative." }
    if (duration.value == 0L) return emptyList()
    return advanceTo(clock.currentInstant + duration).processedEvents
  }

  /**
   * Advances the simulation to the target instant, processing all
   * events and movements that occur along the way.
   */
  private fun advanceTo(targetInstant: WorldInstant): AdvanceResult {
    require(targetInstant >= clock.currentInstant) { "Target instant cannot be in the past." }

    val processedEvents = mutableListOf<WorldScheduledEvent>()
    val completedMovements = mutableListOf<WorldMovement>()

    while (true) {
      val currentInstant = clock.currentInstant
      processedEvents += scheduler.processEventsUpTo(currentInstant)
      completedMovements += updateMovementsAt(currentInstant)

      if (currentInstant >= targetInstant) break

      val nextScheduledInstant = scheduler.getFutureEvents(currentInstant).minOfOrNull { it.instant }
      val nextMovementInstant = movements.values.filter { it.isInProgress && it.completionInstant > currentInstant }
        .minOfOrNull { it.completionInstant }
      val nextInstant = listOfNotNull(
        targetInstant, nextScheduledInstant, nextMovementInstant
      ).minOrNull() ?: targetInstant

      clock.advance(nextInstant - currentInstant)
    }

    return AdvanceResult(processedEvents, completedMovements)
  }

  /**
   * Updates the progress of all movements at the given instant,
   * completing those that have reached their destination.
   */
  private fun updateMovementsAt(instant: WorldInstant): List<WorldMovement> {
    val completed = mutableListOf<WorldMovement>()

    movements.values.sortedBy { it.entityId.value }.forEach { movement ->
      if (!movement.isInProgress) return@forEach

      val updated = movement.updateAt(instant)
      if (updated.isCompleted) {
        movements.remove(movement.entityId)
        state.setLocation(movement.entityId, movement.destination)
        completed.add(updated)
        notifyObservers("MovementCompleted", updated, movement.entityId)
      } else if (updated.progress != movement.progress) {
        movements[movement.entityId] = updated
        notifyObservers("MovementProgressed", updated, movement.entityId)
      }
    }

    return completed
  }

  /**
   * Executes one discrete simulation step.
   *
   * Locates the next future instant that contains a relevant transition
   * (scheduled event, movement completion, etc.), advances the clock
   * to that instant and processes every event at that point in time.
   *
   * If there are no pending future transitions, the clock does not advance.
   *
   * @return Step result indicating whether the simulation advanced and
   *   which events/movements were processed.
   */
  fun step(): WorldStepResult {
    val currentInstant = clock.currentInstant
    val hasDueEvents = scheduler.hasEventsAtOrBefore(currentInstant)
    val dueMovements = movements.values.filter { it.isInProgress && it.completionInstant <= currentInstant }

    if (hasDueEvents || dueMovements.isNotEmpty()) {
      val result = advanceTo(currentInstant)
      return WorldStepResult(
        advanced = false,
        previousInstant = currentInstant,
        currentInstant = clock.currentInstant,
        processedEvents = result.processedEvents,
        completedMovements = result.completedMovements
      )
    }

    val nextEventInstant = scheduler.getFutureEvents(currentInstant).minOfOrNull { it.instant }
    val nextMovementInstant = movements.values.filter { it.isInProgress }.minOfOrNull { it.completionInstant }
    val targetInstant = listOfNotNull(nextEventInstant, nextMovementInstant).filter { it > currentInstant }.minOrNull()
      ?: return WorldStepResult(
        advanced = false,
        previousInstant = currentInstant,
        currentInstant = currentInstant,
        processedEvents = emptyList(),
        completedMovements = emptyList()
      )

    val result = advanceTo(targetInstant)
    return WorldStepResult(
      advanced = true,
      previousInstant = currentInstant,
      currentInstant = clock.currentInstant,
      processedEvents = result.processedEvents,
      completedMovements = result.completedMovements
    )
  }

  /** Interrupts movements and removes locations affected by a location removal. */
  private fun handleLocationRemoval(locationId: WorldLocationId) {
    movements.values.filter { locationId in it.path.locations }.sortedBy { it.entityId.value }.map { it.entityId }
      .forEach(::stopMovement)

    entities.keys.filter { state.getLocation(it) == locationId }.sortedBy { it.value }.forEach(state::removeLocation)

    groups.values.filter { it.hasLocation(locationId) }.sortedBy { it.id.value }
      .forEach { it.removeLocation(locationId) }
  }

  /** Interrupts movements that use the removed connection. */
  private fun handleConnectionRemoval(connectionId: WorldConnectionId) {
    movements.values.filter { movement -> movement.path.connections.any { it.id == connectionId } }
      .sortedBy { it.entityId.value }.map { it.entityId }.forEach(::stopMovement)
  }

  /** Ensures an entity in movement can only have a stable location equal to the origin. */
  private fun validateEntityLocationChange(entityId: WorldEntityId, locationId: WorldLocationId) {
    val movement = movements[entityId] ?: return
    require(locationId == movement.origin) {
      "An entity in movement may only have the stable location corresponding to the movement origin."
    }
  }

  /** Validates that every connection of a path exists and matches the graph. */
  private fun validatePath(path: WorldPath) {
    path.connections.forEach { connection ->
      val graphConnection = graph.getConnection(connection.id)
      require(graphConnection == connection) {
        "Path connection '${connection.id.value}' does not match the connection registered in the graph."
      }
    }
  }

  /** Validates a graph snapshot before restoration, preserving world invariants. */
  private fun validateGraphRestore(snapshot: WorldGraphSnapshot) {
    val locationIds = snapshot.locations.map { it.id }
    require(locationIds.distinct().size == locationIds.size) {
      "Graph snapshot contains duplicate locations."
    }

    val locationSet = locationIds.toSet()
    snapshot.connections.forEach { connection ->
      require(connection.from in locationSet && connection.to in locationSet) {
        "Connection '${connection.id.value}' references a non-existent location."
      }
    }
    require(snapshot.connections.map { it.id }.distinct().size == snapshot.connections.size) {
      "Graph snapshot contains duplicate connections."
    }

    entities.values.forEach { entity ->
      state.getLocation(entity.id)?.let { locationId ->
        require(locationId in locationSet) {
          "Entity '${entity.id.value}' references a location missing from the graph snapshot."
        }
      }
    }
    groups.values.forEach { group ->
      group.getLocations().forEach { locationId ->
        require(locationId in locationSet) {
          "Group '${group.id.value}' references a location missing from the graph snapshot."
        }
      }
    }
    movements.values.forEach { movement ->
      require(movement.path.locations.all { it in locationSet }) {
        "Movement of entity '${movement.entityId.value}' references a location missing from the graph snapshot."
      }
      require(movement.path.connections.all { connection ->
        snapshot.connections.any { it == connection }
      }) {
        "Movement of entity '${movement.entityId.value}' references a connection missing from the graph snapshot."
      }
    }
  }

  /** Validates all invariants of a [WorldSnapshot] before restoration. */
  private fun validateWorldSnapshot(snapshot: WorldSnapshot) {
    val entityIds = snapshot.entities.map { it.id }.toSet()
    require(entityIds.size == snapshot.entities.size) {
      "Snapshot contains duplicate entities."
    }

    val groupIds = snapshot.groups.map { it.id }.toSet()
    require(groupIds.size == snapshot.groups.size) {
      "Snapshot contains duplicate groups."
    }

    require(snapshot.entityLocations.keys.all { it in entityIds }) {
      "Snapshot contains locations associated with non-existent entities."
    }

    val locationIds = snapshot.graph.locations.map { it.id }.toSet()
    val graphConnectionIds = snapshot.graph.connections.map { it.id }
    require(graphConnectionIds.distinct().size == graphConnectionIds.size) {
      "Snapshot contains duplicate connections."
    }
    snapshot.graph.connections.forEach { connection ->
      require(connection.from in locationIds && connection.to in locationIds) {
        "A snapshot connection references a non-existent location."
      }
    }

    require(snapshot.entityLocations.values.all { it in locationIds }) {
      "Snapshot contains entities associated with non-existent locations."
    }

    snapshot.groups.forEach { group ->
      require(group.locations.all { it in locationIds }) {
        "Snapshot contains groups associated with non-existent locations."
      }
    }

    val recurrenceIds = snapshot.scheduler.recurrences.map { it.id }
    require(recurrenceIds.distinct().size == recurrenceIds.size) {
      "Snapshot contains duplicate recurrences."
    }
    require(snapshot.scheduler.recurrences.all { it.interval.value > 0L }) {
      "Snapshot recurrences must have positive intervals."
    }

    val eventIds = snapshot.scheduler.events.map { it.id }
    require(eventIds.distinct().size == eventIds.size) {
      "Snapshot contains duplicate scheduled events."
    }
    val recurrenceIdSet = recurrenceIds.toSet()
    require(snapshot.scheduler.events.all { it.recurrenceId == null || it.recurrenceId in recurrenceIdSet }) {
      "A scheduled event references a non-existent recurrence."
    }
    require(snapshot.scheduler.events.all { it.instant >= snapshot.currentInstant }) {
      "Snapshot contains scheduled events in the past."
    }

    require(snapshot.activeMovements.map { it.entityId }.distinct().size == snapshot.activeMovements.size) {
      "Snapshot contains duplicate movements for the same entity."
    }
    snapshot.activeMovements.forEach { movement ->
      require(movement.state == WorldMovementState.IN_PROGRESS) {
        "Only in-progress movements may appear as active in the snapshot."
      }
      require(movement.entityId in entityIds) {
        "A movement references a non-existent entity."
      }
      require(movement.origin in locationIds && movement.destination in locationIds) {
        "A movement references non-existent locations."
      }
      require(movement.path.origin == movement.origin && movement.path.destination == movement.destination) {
        "A movement has an origin or destination incompatible with its path."
      }
      require(movement.path.connections.all { connection ->
        snapshot.graph.connections.any { it == connection }
      }) {
        "A movement references a connection missing from the graph snapshot."
      }
      require(movement.startInstant <= snapshot.currentInstant && movement.completionInstant > snapshot.currentInstant) {
        "An active movement is incompatible with the snapshot’s current instant."
      }
      val currentLocation = snapshot.entityLocations[movement.entityId]
      require(currentLocation == null || currentLocation == movement.origin) {
        "Location of a moving entity must be null or equal to the origin."
      }
    }
  }

  private data class AdvanceResult(
    val processedEvents: List<WorldScheduledEvent>, val completedMovements: List<WorldMovement>
  )

  companion object {
    /**
     * Reconstructs a [World] from a snapshot.
     *
     * The restored world is equivalent to the saved state and continues
     * to function normally (can advance, receive movements, etc.).
     * Observers may be re-registered; a restoration event is published
     * after reconstruction.
     *
     * @param snapshot Snapshot to restore.
     * @param calendar Calendar to use (default: the one from the snapshot).
     * @param observers Observers to register on the restored world.
     * @return Functional world starting from the snapshot state.
     */
    fun restore(
      snapshot: WorldSnapshot,
      calendar: WorldCalendar? = snapshot.calendar,
      observers: List<WorldObserver> = emptyList()
    ): World {
      val world = World(snapshot.id, calendar)
      world.validateWorldSnapshot(snapshot)
      world.isRestoring = true

      observers.forEach { world.addObserver(it) }

      snapshot.entities.forEach { world.registerEntity(it) }
      world.graph.restore(snapshot.graph)
      world.state.restore(snapshot.entityLocations)

      snapshot.groups.forEach { groupSnapshot ->
        val group = WorldGroup(groupSnapshot.id)
        group.restore(groupSnapshot)
        world.registerGroup(group)
      }

      world.clock.restore(snapshot.currentInstant)
      world.scheduler.restore(snapshot.scheduler)
      snapshot.activeMovements.forEach { world.startMovement(it) }

      world.isRestoring = false
      world.notifyObservers("WorldRestored", snapshot)
      return world
    }
  }
}

/**
 * Result of a [World.step] call.
 *
 * @property advanced `true` if the clock advanced to a future instant.
 * @property previousInstant Instant before the step.
 * @property currentInstant Instant after the step.
 * @property processedEvents Scheduled events that were processed.
 * @property completedMovements Movements completed during the step.
 */
data class WorldStepResult(
  val advanced: Boolean,
  val previousInstant: WorldInstant,
  val currentInstant: WorldInstant,
  val processedEvents: List<WorldScheduledEvent>,
  val completedMovements: List<WorldMovement>
)

/**
 * Valid path between two locations, represented as a sequence
 * of graph connections.
 *
 * A path with no connections is valid only when origin == destination
 * (trivial path). Paths with connections must form a continuous chain
 * (each `to` matches the next `from`).
 *
 * @property origin Starting location.
 * @property destination Ending location.
 * @property connections Ordered sequence of connections that form the path.
 */
data class WorldPath(
  val origin: WorldLocationId, val destination: WorldLocationId, val connections: List<WorldConnection> = emptyList()
) {
  init {
    if (connections.isEmpty()) {
      require(origin == destination) {
        "A path with no connections must have the same origin and destination."
      }
    } else {
      require(connections.first().from == origin) {
        "The first connection of the path must start from the origin."
      }
      require(connections.last().to == destination) {
        "The last connection of the path must end at the destination."
      }
      connections.zipWithNext().forEach { (current, next) ->
        require(current.to == next.from) {
          "Consecutive connections of the path must be linked."
        }
      }
    }
  }

  /**
   * Ordered list of locations traversed by the path
   * (origin + successive destinations of the connections).
   */
  val locations: List<WorldLocationId>
    get() {
      if (connections.isEmpty()) return listOf(origin)
      val result = ArrayList<WorldLocationId>(connections.size + 1)
      result.add(origin)
      connections.mapTo(result) { it.to }
      return result
    }
}

/**
 * Context supplied to access evaluation during navigation.
 *
 * The application may fill the fields relevant to its rules
 * (entity, current location, world state, arbitrary payload).
 * FL-World does not interpret the content.
 *
 * @property entityId Entity attempting to traverse (optional).
 * @property locationId Starting location of the connection
 *   (filled automatically by pathfinders).
 * @property state Spatial state of the world (optional).
 * @property payload Arbitrary domain data.
 */
data class WorldAccessContext(
  val entityId: WorldEntityId? = null,
  val locationId: WorldLocationId? = null,
  val state: WorldState? = null,
  val payload: Any? = null
)

/**
 * External evaluator of connection traversability.
 *
 * Access rules belong to the application (key, level, faction,
 * quest, etc.). FL-World only consults the evaluator while
 * discovering paths.
 */
fun interface WorldAccessEvaluator {
  /**
   * Determines whether the connection can be traversed in the given context.
   *
   * @param connection Candidate connection.
   * @param context Access context (entity, state, etc.).
   * @return `true` if traversal is allowed.
   */
  fun canTraverse(connection: WorldConnection, context: WorldAccessContext): Boolean
}

/**
 * Path-finding strategy on the graph.
 *
 * Standard implementations: [UnweightedPathFinder], [DijkstraPathFinder],
 * [AStarPathFinder]. All respect the access evaluator when supplied.
 */
interface WorldPathFinder {
  /**
   * Finds a path from [from] to [to], or `null` if none exists.
   *
   * @param graph Graph to query.
   * @param from Origin location.
   * @param to Destination location.
   * @param accessEvaluator Optional access evaluator.
   * @param accessContext Context passed to the evaluator.
   * @return Found path or `null`.
   */
  fun findPath(
    graph: WorldGraph,
    from: WorldLocationId,
    to: WorldLocationId,
    accessEvaluator: WorldAccessEvaluator? = null,
    accessContext: WorldAccessContext = WorldAccessContext()
  ): WorldPath?
}

/**
 * Unweighted pathfinder: BFS that finds the path with the fewest
 * connections (or any path if several exist).
 */
class UnweightedPathFinder : WorldPathFinder {
  override fun findPath(
    graph: WorldGraph,
    from: WorldLocationId,
    to: WorldLocationId,
    accessEvaluator: WorldAccessEvaluator?,
    accessContext: WorldAccessContext
  ): WorldPath? {
    if (graph.getLocation(from) == null || graph.getLocation(to) == null) return null
    if (from == to) return WorldPath(from, to)

    val queue = ArrayDeque<WorldLocationId>()
    val parentConnection = mutableMapOf<WorldLocationId, WorldConnection>()
    val visited = mutableSetOf<WorldLocationId>()

    queue.add(from)
    visited.add(from)

    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      if (current == to) break

      for (connection in graph.getOutgoingConnections(current)) {
        val context = accessContext.copy(locationId = current)
        if (accessEvaluator != null && !accessEvaluator.canTraverse(connection, context)) continue

        val next = connection.to
        if (visited.add(next)) {
          parentConnection[next] = connection
          queue.add(next)
        }
      }
    }

    if (to !in parentConnection) return null

    val pathConnections = mutableListOf<WorldConnection>()
    var current = to
    while (current != from) {
      val connection = parentConnection[current] ?: return null
      pathConnections.add(connection)
      current = connection.from
    }
    pathConnections.reverse()

    return WorldPath(from, to, pathConnections)
  }
}

/**
 * Navigation cost function supplied by the application.
 *
 * Cost must be non-negative. FL-World imposes no units
 * (distance, time, energy, etc.); the application decides.
 */
fun interface WorldNavigationCost {
  /**
   * Computes the cost of traversing the connection in the given context.
   *
   * @param connection Connection being evaluated.
   * @param context Access context.
   * @return Cost ≥ 0.
   */
  fun getCost(connection: WorldConnection, context: WorldAccessContext): Double
}

/**
 * Admissible heuristic for A*.
 *
 * Must be non-negative and never overestimate the remaining true cost.
 */
fun interface WorldHeuristic {
  /**
   * Estimates the remaining cost from [from] to [to].
   *
   * @param from Current location.
   * @param to Destination location.
   * @return Estimate ≥ 0.
   */
  fun estimate(from: WorldLocationId, to: WorldLocationId): Double
}

/**
 * Least-cost pathfinder based on Dijkstra.
 *
 * Uses a [WorldNavigationCost] supplied by the application.
 * Respects the access evaluator when present.
 *
 * @param costEvaluator Per-connection cost function.
 */
class DijkstraPathFinder(
  private val costEvaluator: WorldNavigationCost
) : WorldPathFinder {
  override fun findPath(
    graph: WorldGraph,
    from: WorldLocationId,
    to: WorldLocationId,
    accessEvaluator: WorldAccessEvaluator?,
    accessContext: WorldAccessContext
  ): WorldPath? {
    if (graph.getLocation(from) == null || graph.getLocation(to) == null) return null
    if (from == to) return WorldPath(from, to)

    val distances = mutableMapOf<WorldLocationId, Double>().withDefault { Double.POSITIVE_INFINITY }
    val previousConnection = mutableMapOf<WorldLocationId, WorldConnection>()
    val queue = java.util.PriorityQueue(compareBy<QueueEntry>({ it.score }, { it.location.value }))

    distances[from] = 0.0
    queue.add(QueueEntry(from, 0.0, 0.0))

    while (queue.isNotEmpty()) {
      val currentEntry = queue.poll()
      val current = currentEntry.location
      val currentDist = currentEntry.gScore
      if (currentDist != distances.getValue(current)) continue
      if (current == to) break

      for (connection in graph.getOutgoingConnections(current)) {
        val context = accessContext.copy(locationId = current)
        if (accessEvaluator != null && !accessEvaluator.canTraverse(connection, context)) continue

        val cost = costEvaluator.getCost(connection, context)
        require(!cost.isNaN() && cost >= 0.0) { "Navigation cost must be a non-negative number." }

        val next = connection.to
        val newDistance = currentDist + cost
        if (newDistance < distances.getValue(next)) {
          distances[next] = newDistance
          previousConnection[next] = connection
          queue.add(QueueEntry(next, newDistance, newDistance))
        }
      }
    }

    if (to !in previousConnection) return null
    return reconstructPath(from, to, previousConnection)
  }
}

/**
 * Least-cost pathfinder based on A*.
 *
 * Combines real cost ([WorldNavigationCost]) with a heuristic
 * ([WorldHeuristic]). The heuristic must be admissible.
 *
 * @param costEvaluator Per-connection cost function.
 * @param heuristic Remaining-cost estimate.
 */
class AStarPathFinder(
  private val costEvaluator: WorldNavigationCost, private val heuristic: WorldHeuristic
) : WorldPathFinder {
  override fun findPath(
    graph: WorldGraph,
    from: WorldLocationId,
    to: WorldLocationId,
    accessEvaluator: WorldAccessEvaluator?,
    accessContext: WorldAccessContext
  ): WorldPath? {
    if (graph.getLocation(from) == null || graph.getLocation(to) == null) return null
    if (from == to) return WorldPath(from, to)

    val gScore = mutableMapOf<WorldLocationId, Double>().withDefault { Double.POSITIVE_INFINITY }
    val previousConnection = mutableMapOf<WorldLocationId, WorldConnection>()
    val openSet = java.util.PriorityQueue(compareBy<QueueEntry>({ it.score }, { it.location.value }))

    val initialHeuristic = heuristic.estimate(from, to)
    require(!initialHeuristic.isNaN() && initialHeuristic >= 0.0) {
      "Heuristic must be a non-negative number."
    }

    gScore[from] = 0.0
    openSet.add(QueueEntry(from, 0.0, initialHeuristic))

    while (openSet.isNotEmpty()) {
      val currentEntry = openSet.poll()
      val current = currentEntry.location
      if (current == to) break

      val currentGScore = gScore.getValue(current)
      if (currentEntry.gScore != currentGScore) continue

      for (connection in graph.getOutgoingConnections(current)) {
        val context = accessContext.copy(locationId = current)
        if (accessEvaluator != null && !accessEvaluator.canTraverse(connection, context)) continue

        val cost = costEvaluator.getCost(connection, context)
        require(!cost.isNaN() && cost >= 0.0) { "Navigation cost must be a non-negative number." }

        val next = connection.to
        val tentativeGScore = currentGScore + cost
        if (tentativeGScore < gScore.getValue(next)) {
          val nextHeuristic = heuristic.estimate(next, to)
          require(!nextHeuristic.isNaN() && nextHeuristic >= 0.0) {
            "Heuristic must be a non-negative number."
          }

          previousConnection[next] = connection
          gScore[next] = tentativeGScore
          openSet.add(QueueEntry(next, tentativeGScore, tentativeGScore + nextHeuristic))
        }
      }
    }

    if (to !in previousConnection) return null
    return reconstructPath(from, to, previousConnection)
  }
}

/** Internal entry used by the priority queues of the pathfinders. */
private data class QueueEntry(
  val location: WorldLocationId, val gScore: Double, val score: Double
)

/** Reconstructs a [WorldPath] from the predecessor map. */
private fun reconstructPath(
  from: WorldLocationId, to: WorldLocationId, previousConnection: Map<WorldLocationId, WorldConnection>
): WorldPath {
  val pathConnections = mutableListOf<WorldConnection>()
  var current = to
  while (current != from) {
    val connection = previousConnection[current] ?: error("Could not reconstruct the found path.")
    pathConnections.add(connection)
    current = connection.from
  }
  pathConnections.reverse()
  return WorldPath(from, to, pathConnections)
}

/**
 * Immutable snapshot of the spatial structure (locations + connections).
 */
data class WorldGraphSnapshot(
  val locations: List<WorldLocation>, val connections: List<WorldConnection>
)

/**
 * Directed spatial graph of the world.
 *
 * Responsible only for the permanent structure of locations and
 * connections. Entities and dynamic state live in [WorldState].
 * Publishes registration/removal events and notifies [World] of
 * removals so that movements and groups stay consistent.
 */
class WorldGraph {
  internal var eventPublisher: ((String, Any?, WorldEntityId?) -> Unit)? = null
  internal var locationRemovalHandler: ((WorldLocationId) -> Unit)? = null
  internal var connectionRemovalHandler: ((WorldConnectionId) -> Unit)? = null
  internal var restoreValidator: ((WorldGraphSnapshot) -> Unit)? = null

  private val locations = mutableMapOf<WorldLocationId, WorldLocation>()
  private val connections = mutableMapOf<WorldConnectionId, WorldConnection>()
  private val outgoingConnections = mutableMapOf<WorldLocationId, MutableList<WorldConnection>>()

  /**
   * Registers a new location in the graph.
   *
   * @param location Location to register.
   * @throws IllegalArgumentException if the id already exists.
   */
  fun registerLocation(location: WorldLocation) {
    require(location.id !in locations) { "Location '${location.id.value}' is already registered." }
    locations[location.id] = location
    eventPublisher?.invoke("LocationRegistered", location, null)
  }

  /**
   * Removes a location and every incident connection.
   *
   * Notifies [World] to interrupt movements and clean entity/group
   * locations that are affected.
   *
   * @param id Identifier of the location to remove.
   */
  fun removeLocation(id: WorldLocationId) {
    if (id !in locations) return
    locationRemovalHandler?.invoke(id)

    connections.values.filter { it.from == id || it.to == id }.sortedBy { it.id.value }.map { it.id }
      .forEach(::removeConnection)

    locations.remove(id)
    eventPublisher?.invoke("LocationRemoved", id, null)
  }

  /**
   * Registers a directed connection.
   *
   * Origin and destination must already exist in the graph.
   *
   * @param connection Connection to register.
   * @throws IllegalArgumentException if the id already exists or if
   *   origin/destination are not registered.
   */
  fun registerConnection(connection: WorldConnection) {
    require(connection.id !in connections) { "Connection '${connection.id.value}' is already registered." }
    require(locations.containsKey(connection.from)) {
      "Origin location '${connection.from.value}' must be registered in the graph."
    }
    require(locations.containsKey(connection.to)) {
      "Destination location '${connection.to.value}' must be registered in the graph."
    }
    connections[connection.id] = connection
    outgoingConnections.getOrPut(connection.from) { mutableListOf() }.add(connection)
    eventPublisher?.invoke("ConnectionRegistered", connection, null)
  }

  /**
   * Removes a connection from the graph.
   *
   * Notifies [World] to interrupt movements that use it.
   *
   * @param id Identifier of the connection to remove.
   */
  fun removeConnection(id: WorldConnectionId) {
    if (id !in connections) return
    connectionRemovalHandler?.invoke(id)
    val connection = connections.remove(id) ?: return
    outgoingConnections[connection.from]?.let { outgoing ->
      outgoing.removeIf { it.id == id }
      if (outgoing.isEmpty()) outgoingConnections.remove(connection.from)
    }
    eventPublisher?.invoke("ConnectionRemoved", connection, null)
  }

  /**
   * Returns the location by id, or `null` if it does not exist.
   */
  fun getLocation(id: WorldLocationId): WorldLocation? {
    return locations[id]
  }

  /**
   * Returns the connection by id, or `null` if it does not exist.
   */
  fun getConnection(id: WorldConnectionId): WorldConnection? {
    return connections[id]
  }

  /**
   * Lists the connections that leave the location, ordered by id.
   *
   * @param locationId Origin location.
   */
  fun getOutgoingConnections(locationId: WorldLocationId): List<WorldConnection> {
    return outgoingConnections[locationId]?.sortedBy { it.id.value }?.toList() ?: emptyList()
  }

  /**
   * Lists neighbouring locations (destinations of outgoing connections).
   *
   * @param locationId Origin location.
   */
  fun getNeighbors(locationId: WorldLocationId): List<WorldLocationId> {
    return getOutgoingConnections(locationId).map { it.to }.distinct()
  }

  /**
   * Checks whether a path from [from] to [to] exists, respecting
   * the access evaluator when supplied.
   *
   * @param pathFinder Path-finding strategy (default: unweighted).
   */
  fun isReachable(
    from: WorldLocationId,
    to: WorldLocationId,
    pathFinder: WorldPathFinder = UnweightedPathFinder(),
    accessEvaluator: WorldAccessEvaluator? = null,
    accessContext: WorldAccessContext = WorldAccessContext()
  ): Boolean {
    return pathFinder.findPath(this, from, to, accessEvaluator, accessContext) != null
  }

  /**
   * Finds a path from [from] to [to], or `null` if none exists.
   *
   * @param pathFinder Path-finding strategy (default: unweighted).
   */
  fun findPath(
    from: WorldLocationId,
    to: WorldLocationId,
    pathFinder: WorldPathFinder = UnweightedPathFinder(),
    accessEvaluator: WorldAccessEvaluator? = null,
    accessContext: WorldAccessContext = WorldAccessContext()
  ): WorldPath? {
    return pathFinder.findPath(this, from, to, accessEvaluator, accessContext)
  }

  /**
   * Produces an immutable snapshot of the spatial structure.
   */
  fun snapshot(): WorldGraphSnapshot {
    return WorldGraphSnapshot(
      locations = locations.values.sortedBy { it.id.value }.map { it.copy() },
      connections = connections.values.sortedBy { it.id.value }.map { it.copy() })
  }

  /**
   * Restores the graph from a snapshot, completely replacing
   * the previous state.
   *
   * @param snapshot Snapshot to restore.
   */
  fun restore(snapshot: WorldGraphSnapshot) {
    restoreValidator?.invoke(snapshot)

    val locationIds = snapshot.locations.map { it.id }
    require(locationIds.distinct().size == locationIds.size) {
      "Graph snapshot contains duplicate locations."
    }
    val connectionIds = snapshot.connections.map { it.id }
    require(connectionIds.distinct().size == connectionIds.size) {
      "Graph snapshot contains duplicate connections."
    }
    val locationSet = locationIds.toSet()
    snapshot.connections.forEach { connection ->
      require(connection.from in locationSet && connection.to in locationSet) {
        "Connection '${connection.id.value}' references a non-existent location."
      }
    }

    locations.clear()
    connections.clear()
    outgoingConnections.clear()

    snapshot.locations.forEach { locations[it.id] = it.copy() }
    snapshot.connections.forEach { connection ->
      val copy = connection.copy()
      connections[copy.id] = copy
      outgoingConnections.getOrPut(copy.from) { mutableListOf() }.add(copy)
    }
  }
}

/**
 * Dynamic spatial state of entities.
 *
 * Maintains the mapping `WorldEntityId → WorldLocationId` when the
 * entity is located. An entity may exist without a location.
 * Separated from [WorldGraph] (permanent structure vs. dynamic state).
 */
class WorldState {
  internal var eventPublisher: ((String, Any?, WorldEntityId?) -> Unit)? = null
  internal var entityValidator: ((WorldEntityId) -> Boolean)? = null
  internal var locationValidator: ((WorldLocationId) -> Boolean)? = null
  internal var locationChangeValidator: ((WorldEntityId, WorldLocationId) -> Unit)? = null

  private val entityLocations = mutableMapOf<WorldEntityId, WorldLocationId>()

  /**
   * Places (or re-places) an entity at a location.
   *
   * Validates entity and location existence and respects restrictions
   * for entities that are currently moving.
   *
   * @param entityId Entity to place.
   * @param locationId Target location.
   */
  fun setLocation(entityId: WorldEntityId, locationId: WorldLocationId) {
    require(entityValidator?.invoke(entityId) != false) {
      "Entity '${entityId.value}' must exist to receive a location."
    }
    require(locationValidator?.invoke(locationId) != false) {
      "Location '${locationId.value}' must exist to be assigned."
    }
    locationChangeValidator?.invoke(entityId, locationId)

    if (entityLocations[entityId] == locationId) return
    entityLocations[entityId] = locationId
    eventPublisher?.invoke(
      "LocationChanged", mapOf("entityId" to entityId, "locationId" to locationId), entityId
    )
  }

  /**
   * Returns the current location of the entity, or `null` if unlocated.
   *
   * @param entityId Entity being queried.
   */
  fun getLocation(entityId: WorldEntityId): WorldLocationId? {
    return entityLocations[entityId]
  }

  /**
   * Removes the location of the entity (the entity continues to exist).
   *
   * @param entityId Entity whose location will be removed.
   */
  fun removeLocation(entityId: WorldEntityId) {
    if (entityLocations.remove(entityId) != null) {
      eventPublisher?.invoke("LocationRemoved", entityId, entityId)
    }
  }

  /**
   * Produces an immutable snapshot of the entity → location mapping.
   */
  fun snapshot(): Map<WorldEntityId, WorldLocationId> {
    return entityLocations.entries.sortedBy { it.key.value }.associate { it.key to it.value }
  }

  /**
   * Restores the spatial state from a snapshot.
   *
   * @param snapshot Mapping to restore.
   */
  fun restore(snapshot: Map<WorldEntityId, WorldLocationId>) {
    snapshot.forEach { (entityId, locationId) ->
      require(entityValidator?.invoke(entityId) != false) {
        "Entity '${entityId.value}' from the restored state does not exist."
      }
      require(locationValidator?.invoke(locationId) != false) {
        "Location '${locationId.value}' from the restored state does not exist."
      }
    }

    entityLocations.clear()
    entityLocations.putAll(snapshot)
  }
}

/**
 * Immutable snapshot of a location group.
 */
data class WorldGroupSnapshot(
  val id: WorldGroupId, val locations: Set<WorldLocationId>
)

/**
 * Grouping of locations (region, forest, dungeon, etc.).
 *
 * Not a node of the graph; only references existing locations.
 * A location may belong to several groups.
 *
 * @property id Group identity.
 */
class WorldGroup(val id: WorldGroupId) {
  internal var eventPublisher: ((String, Any?, WorldEntityId?) -> Unit)? = null
  internal var locationValidator: ((WorldLocationId) -> Boolean)? = null

  private val locations = mutableSetOf<WorldLocationId>()

  /**
   * Adds a location to the group.
   *
   * The location must exist in the graph.
   *
   * @param locationId Location to add.
   */
  fun addLocation(locationId: WorldLocationId) {
    require(locationValidator?.invoke(locationId) != false) {
      "Location '${locationId.value}' must exist to be added to the group."
    }
    if (locations.add(locationId)) {
      eventPublisher?.invoke(
        "GroupLocationAdded", mapOf("groupId" to id, "locationId" to locationId), null
      )
    }
  }

  /**
   * Removes a location from the group.
   *
   * @param locationId Location to remove.
   */
  fun removeLocation(locationId: WorldLocationId) {
    if (locations.remove(locationId)) {
      eventPublisher?.invoke(
        "GroupLocationRemoved", mapOf("groupId" to id, "locationId" to locationId), null
      )
    }
  }

  /**
   * Checks whether the location belongs to the group.
   */
  fun hasLocation(locationId: WorldLocationId): Boolean {
    return locationId in locations
  }

  /**
   * Immutable set of the group’s locations.
   */
  fun getLocations(): Set<WorldLocationId> {
    return locations.toSet()
  }

  /**
   * Produces an immutable snapshot of the group.
   */
  fun snapshot(): WorldGroupSnapshot {
    return WorldGroupSnapshot(id, locations.toSet())
  }

  /**
   * Restores the group from a snapshot.
   *
   * @param snapshot Snapshot to restore.
   */
  fun restore(snapshot: WorldGroupSnapshot) {
    snapshot.locations.forEach { locationId ->
      require(locationValidator?.invoke(locationId) != false) {
        "Location '${locationId.value}' must exist to be restored in the group."
      }
    }
    locations.clear()
    locations.addAll(snapshot.locations)
  }
}

/**
 * Quantity of simulation time.
 *
 * Represents a non-negative duration. Comparable and used in
 * arithmetic operations with [WorldInstant].
 *
 * @property value Numeric value of the duration (units defined by the application).
 */
@JvmInline
value class WorldDuration(val value: Long) : Comparable<WorldDuration> {
  override fun compareTo(other: WorldDuration): Int {
    return value.compareTo(other.value)
  }
}

/**
 * Point in simulation time.
 *
 * Independent of the system clock. Comparable and supporting
 * addition/subtraction of [WorldDuration].
 *
 * @property value Numeric value of the instant (units defined by the application).
 */
@JvmInline
value class WorldInstant(val value: Long) : Comparable<WorldInstant> {
  override fun compareTo(other: WorldInstant): Int {
    return value.compareTo(other.value)
  }

  /** Difference between this instant and another (may be negative). */
  operator fun minus(other: WorldInstant): WorldDuration {
    return WorldDuration(value - other.value)
  }

  /** Adds a duration to this instant. */
  operator fun plus(duration: WorldDuration): WorldInstant {
    return WorldInstant(value + duration.value)
  }

  /** Subtracts a duration from this instant. */
  operator fun minus(duration: WorldDuration): WorldInstant {
    return WorldInstant(value - duration.value)
  }
}

/**
 * Date interpreted from a [WorldInstant] according to the world calendar.
 *
 * Fields do not assume terrestrial conventions; the [WorldCalendar]
 * defines the meaning of year, month, day, etc.
 */
data class CalendarDate(
  val year: Long, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int
)

/**
 * Simulation clock.
 *
 * Holds the current instant and allows deterministic advancement.
 * Does not use the operating-system clock.
 *
 * @param initialInstant Initial instant (default: zero).
 */
class WorldClock(initialInstant: WorldInstant = WorldInstant(0)) {
  internal var eventPublisher: ((String, Any?, WorldEntityId?) -> Unit)? = null

  /**
   * Current simulation instant.
   * Only advanced via [advance] or restored via [restore].
   */
  var currentInstant: WorldInstant = initialInstant
    private set

  /**
   * Advances the clock by the given duration.
   *
   * Does not allow going backwards. Publishes a `ClockAdvanced` event.
   *
   * @param duration Advance duration (≥ 0).
   */
  fun advance(duration: WorldDuration) {
    require(duration.value >= 0L) { "Clock cannot go backwards through advance()." }
    if (duration.value == 0L) return
    currentInstant += duration
    eventPublisher?.invoke("ClockAdvanced", duration, null)
  }

  /**
   * Restores the clock to the given instant (used during snapshot restoration).
   *
   * @param instant Instant to restore.
   */
  fun restore(instant: WorldInstant) {
    currentInstant = instant
  }
}

/**
 * World calendar responsible for interpreting a [WorldInstant]
 * as a [CalendarDate].
 *
 * The implementation is supplied by the application and may model
 * terrestrial calendars, alien cycles, eras, etc.
 */
interface WorldCalendar {
  /**
   * Converts a simulation instant into a calendar date.
   *
   * @param instant Instant to interpret.
   * @return Corresponding date.
   */
  fun toDate(instant: WorldInstant): CalendarDate
}

/**
 * Temporal recurrence definition.
 *
 * Used by scheduled events to generate new occurrences automatically
 * after processing.
 *
 * @property id Recurrence identity.
 * @property interval Positive interval between occurrences.
 */
data class WorldRecurrence(
  val id: WorldRecurrenceId, val interval: WorldDuration
) {
  init {
    require(interval.value > 0L) { "Recurrence interval must be positive." }
  }
}

/**
 * Event scheduled to occur at a future instant.
 *
 * Represents “something that *will* happen”, in contrast to
 * [WorldEvent] (“something that *has happened*”). The content
 * ([payload]) belongs to the application; FL-World only manages scheduling.
 *
 * @property id Event identity.
 * @property instant Instant at which it should occur.
 * @property type Textual type identifier.
 * @property payload Optional domain data.
 * @property recurrenceId Associated recurrence (optional).
 */
data class WorldScheduledEvent(
  val id: WorldScheduledEventId,
  val instant: WorldInstant,
  val type: String,
  val payload: Any? = null,
  val recurrenceId: WorldRecurrenceId? = null
) : Comparable<WorldScheduledEvent> {
  override fun compareTo(other: WorldScheduledEvent): Int {
    val instantComparison = instant.compareTo(other.instant)
    if (instantComparison != 0) return instantComparison
    return id.value.compareTo(other.id.value)
  }
}

/**
 * Immutable snapshot of the scheduler state (events + recurrences).
 */
data class WorldSchedulerSnapshot(
  val events: List<WorldScheduledEvent>, val recurrences: List<WorldRecurrence>
)

/**
 * Scheduler of future events and recurrences.
 *
 * Allows scheduling, cancelling, querying and processing events
 * deterministically. Events in the past cannot be scheduled.
 * When an event with a recurrence is processed, a new occurrence
 * is automatically scheduled.
 */
class WorldScheduler {
  internal var eventPublisher: ((String, Any?, WorldEntityId?) -> Unit)? = null
  internal var currentInstantProvider: (() -> WorldInstant)? = null

  private val events = mutableMapOf<WorldScheduledEventId, WorldScheduledEvent>()
  private val recurrences = mutableMapOf<WorldRecurrenceId, WorldRecurrence>()

  /**
   * Schedules a future event.
   *
   * @param event Event to schedule.
   * @throws IllegalArgumentException if the id already exists, if the
   *   instant is in the past, or if the referenced recurrence does not exist.
   */
  fun schedule(event: WorldScheduledEvent) {
    require(event.id !in events) { "Event '${event.id.value}' is already scheduled." }
    currentInstantProvider?.invoke()?.let { currentInstant ->
      require(event.instant >= currentInstant) {
        "An event cannot be scheduled in the past."
      }
    }
    event.recurrenceId?.let { recurrenceId ->
      require(recurrenceId in recurrences) {
        "Recurrence '${recurrenceId.value}' must exist before being referenced."
      }
    }
    events[event.id] = event
    eventPublisher?.invoke("EventScheduled", event, null)
  }

  /**
   * Cancels a scheduled event.
   *
   * @param eventId Identifier of the event to cancel.
   */
  fun cancel(eventId: WorldScheduledEventId) {
    if (events.remove(eventId) != null) {
      eventPublisher?.invoke("EventCanceled", eventId, null)
    }
  }

  /**
   * Defines a temporal recurrence.
   *
   * @param recurrence Recurrence to define.
   * @throws IllegalArgumentException if the id already exists.
   */
  fun defineRecurrence(recurrence: WorldRecurrence) {
    require(recurrence.id !in recurrences) { "Recurrence '${recurrence.id.value}' is already defined." }
    recurrences[recurrence.id] = recurrence
    eventPublisher?.invoke("RecurrenceDefined", recurrence, null)
  }

  /**
   * Cancels a recurrence.
   *
   * Already-scheduled events that reference it are not cancelled automatically.
   *
   * @param recurrenceId Identifier of the recurrence to cancel.
   */
  fun cancelRecurrence(recurrenceId: WorldRecurrenceId) {
    if (recurrences.remove(recurrenceId) != null) {
      eventPublisher?.invoke("RecurrenceCanceled", recurrenceId, null)
    }
  }

  /**
   * Returns the recurrence by id, or `null` if it does not exist.
   */
  fun getRecurrence(recurrenceId: WorldRecurrenceId): WorldRecurrence? {
    return recurrences[recurrenceId]
  }

  /**
   * Lists future events after the given instant, ordered.
   *
   * @param afterInstant Reference instant (exclusive).
   */
  fun getFutureEvents(afterInstant: WorldInstant): List<WorldScheduledEvent> {
    return events.values.filter { it.instant > afterInstant }.sorted()
  }

  /** Indicates whether there are events with instant ≤ the given instant. */
  internal fun hasEventsAtOrBefore(instant: WorldInstant): Boolean {
    return events.values.any { it.instant <= instant }
  }

  /**
   * Processes every event whose instant is ≤ the current instant.
   *
   * Processed events are removed; if they have a recurrence,
   * a new occurrence is scheduled automatically.
   *
   * @param currentInstant Current simulation instant.
   * @return List of processed events, in deterministic order.
   */
  fun processEventsUpTo(currentInstant: WorldInstant): List<WorldScheduledEvent> {
    val processed = mutableListOf<WorldScheduledEvent>()

    while (true) {
      val reached = events.values.filter { it.instant <= currentInstant }.sorted()
      if (reached.isEmpty()) break

      reached.forEach { event ->
        events.remove(event.id)
        processed.add(event)
        eventPublisher?.invoke("EventProcessed", event, null)

        event.recurrenceId?.let { recurrenceId ->
          recurrences[recurrenceId]?.let { recurrence ->
            schedule(event.copy(instant = event.instant + recurrence.interval))
          }
        }
      }
    }

    return processed
  }

  /**
   * Produces an immutable snapshot of the scheduler.
   */
  fun snapshot(): WorldSchedulerSnapshot {
    return WorldSchedulerSnapshot(
      events = events.values.sorted().map { it.copy() },
      recurrences = recurrences.values.sortedBy { it.id.value }.map { it.copy() })
  }

  /**
   * Restores the scheduler from a snapshot.
   *
   * @param snapshot Snapshot to restore.
   */
  fun restore(snapshot: WorldSchedulerSnapshot) {
    val eventIds = snapshot.events.map { it.id }
    val recurrenceIds = snapshot.recurrences.map { it.id }
    require(eventIds.distinct().size == eventIds.size) { "Snapshot contains duplicate scheduled events." }
    require(recurrenceIds.distinct().size == recurrenceIds.size) { "Snapshot contains duplicate recurrences." }
    require(snapshot.recurrences.all { it.interval.value > 0L }) {
      "Snapshot recurrences must have positive intervals."
    }

    val recurrenceSet = recurrenceIds.toSet()
    require(snapshot.events.all { it.recurrenceId == null || it.recurrenceId in recurrenceSet }) {
      "A scheduled event references a non-existent recurrence."
    }
    currentInstantProvider?.invoke()?.let { currentInstant ->
      require(snapshot.events.all { it.instant >= currentInstant }) {
        "Snapshot contains scheduled events in the past."
      }
    }

    events.clear()
    recurrences.clear()
    snapshot.recurrences.forEach { recurrences[it.id] = it.copy() }
    snapshot.events.forEach { events[it.id] = it.copy() }
  }
}

/**
 * External estimator of movement duration.
 *
 * Receives the path, cost/distance and a movement profile
 * (speed, penalties, etc.) and returns the corresponding [WorldDuration].
 * Speeds and domain rules stay outside FL-World.
 */
interface WorldDurationEstimator {
  /**
   * Estimates the duration of a displacement.
   *
   * @param path Path to be travelled.
   * @param cost Cost or distance associated with the path.
   * @param profile Movement profile (optional, defined by the application).
   * @return Estimated duration.
   */
  fun estimateDuration(
    path: WorldPath, cost: Double, profile: Any? = null
  ): WorldDuration
}

/**
 * State of an entity movement.
 *
 * - [IN_PROGRESS]: active movement.
 * - [COMPLETED]: reached the destination.
 * - [INTERRUPTED]: interrupted before completion.
 */
enum class WorldMovementState {
  IN_PROGRESS, COMPLETED, INTERRUPTED
}

/**
 * Representation of an entity displacement along a path.
 *
 * The entity’s stable location (in [WorldState]) and the movement
 * progress are distinct concepts: while the movement is in progress,
 * the stable location remains the origin.
 *
 * @property entityId Entity in movement.
 * @property origin Starting location.
 * @property destination Ending location.
 * @property path Path being travelled.
 * @property startInstant Start instant.
 * @property duration Total duration of the displacement.
 * @property progress Normalised progress ∈ [0.0, 1.0].
 * @property state Current movement state.
 */
data class WorldMovement(
  val entityId: WorldEntityId,
  val origin: WorldLocationId,
  val destination: WorldLocationId,
  val path: WorldPath,
  val startInstant: WorldInstant,
  val duration: WorldDuration,
  val progress: Double = 0.0,
  val state: WorldMovementState = WorldMovementState.IN_PROGRESS
) {
  init {
    require(progress in 0.0..1.0) { "Movement progress must be between 0.0 and 1.0." }
    require(duration.value > 0L) { "Movement duration must be positive." }
    require(state == WorldMovementState.IN_PROGRESS || progress >= 1.0 || state == WorldMovementState.INTERRUPTED) {
      "A completed movement must have full progress."
    }
  }

  /** Instant at which the movement should complete. */
  val completionInstant: WorldInstant
    get() = startInstant + duration

  /** Indicates whether the movement is still in progress. */
  val isInProgress: Boolean
    get() = state == WorldMovementState.IN_PROGRESS

  /** Indicates whether the movement has completed. */
  val isCompleted: Boolean
    get() = state == WorldMovementState.COMPLETED

  /** Indicates whether the movement was interrupted. */
  val isInterrupted: Boolean
    get() = state == WorldMovementState.INTERRUPTED

  /**
   * Computes the progress at the given instant.
   *
   * - Before start → 0.0
   * - After completion → 1.0
   * - Interrupted → progress frozen at the moment of interruption
   *
   * @param currentInstant Query instant.
   * @return Progress ∈ [0.0, 1.0].
   */
  fun progressAt(currentInstant: WorldInstant): Double {
    if (state == WorldMovementState.INTERRUPTED) return progress
    if (currentInstant <= startInstant) return 0.0
    if (currentInstant >= completionInstant) return 1.0

    val elapsed = (currentInstant - startInstant).value.toDouble()
    val total = duration.value.toDouble()
    return (elapsed / total).coerceIn(0.0, 1.0)
  }

  /**
   * Produces an updated copy of the movement at the given instant.
   *
   * If progress reaches 1.0 the state becomes [COMPLETED].
   *
   * @param currentInstant Update instant.
   * @return Movement with updated progress and state.
   */
  fun updateAt(currentInstant: WorldInstant): WorldMovement {
    if (!isInProgress) return this
    val currentProgress = progressAt(currentInstant)
    val newState = if (currentProgress >= 1.0) {
      WorldMovementState.COMPLETED
    } else {
      WorldMovementState.IN_PROGRESS
    }
    return copy(progress = currentProgress, state = newState)
  }
}

/**
 * Immutable and complete snapshot of a [World] state.
 *
 * Sufficient to reconstruct an equivalent world via [World.restore].
 * Does not include domain data (attributes, inventory, quests, etc.);
 * the application is responsible for its own state snapshot.
 *
 * @property id World identity.
 * @property entities Existing entities.
 * @property entityLocations Entity → location mapping.
 * @property graph Spatial structure.
 * @property groups Location groups.
 * @property calendar Calendar (maybe `null`).
 * @property currentInstant Current instant.
 * @property scheduler Scheduler state.
 * @property activeMovements In-progress movements.
 */
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