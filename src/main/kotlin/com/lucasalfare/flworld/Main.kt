@file:Suppress("unused")

package com.lucasalfare.flworld

@JvmInline
value class WorldId(val value: String)

@JvmInline
value class WorldEntityId(val value: String)

@JvmInline
value class WorldLocationId(val value: String)

@JvmInline
value class WorldConnectionId(val value: String)

data class WorldEntity(
  val id: WorldEntityId, var isEventPropagationEnabled: Boolean = false
)

data class WorldLocation(
  val id: WorldLocationId
)

data class WorldConnection(
  val id: WorldConnectionId, val from: WorldLocationId, val to: WorldLocationId
)

data class WorldEvent(
  val instant: WorldInstant,
  val type: String,
  val data: Any? = null,
  val sourceId: WorldEntityId? = null,
  val snapshot: WorldSnapshot
)

fun interface WorldObserver {
  fun onEvent(event: WorldEvent)
}

class World(val id: WorldId) {
  private val entities = mutableMapOf<WorldEntityId, WorldEntity>()
  private val groups = mutableMapOf<WorldGroupId, WorldGroup>()
  private val movements = mutableMapOf<WorldEntityId, WorldMovement>()
  private val observers = mutableListOf<WorldObserver>()

  internal var isRestoring = false

  val graph = WorldGraph().apply { eventPublisher = ::publish }
  val state = WorldState().apply { eventPublisher = ::publish }
  val clock = WorldClock().apply { eventPublisher = ::publish }
  val scheduler = WorldScheduler().apply { eventPublisher = ::publish }

  var calendar: WorldCalendar? = null

  fun onEvent(observer: WorldObserver) {
    observers.add(observer)
  }

  internal fun publish(type: String, data: Any? = null, sourceId: WorldEntityId? = null) {
    if (isRestoring || observers.isEmpty()) return
    val event = WorldEvent(
      instant = clock.currentInstant, type = type, data = data, sourceId = sourceId, snapshot = snapshot()
    )
    observers.forEach { it.onEvent(event) }
  }

  fun enableEntityEventPropagation(entityId: WorldEntityId) {
    entities[entityId]?.isEventPropagationEnabled = true
  }

  fun disableEntityEventPropagation(entityId: WorldEntityId) {
    entities[entityId]?.isEventPropagationEnabled = false
  }

  fun isEntityEventPropagationEnabled(entityId: WorldEntityId): Boolean {
    return entities[entityId]?.isEventPropagationEnabled ?: false
  }

  fun publishEntityEvent(entityId: WorldEntityId, type: String, data: Any? = null) {
    val entity = entities[entityId] ?: return
    if (!entity.isEventPropagationEnabled) return
    if (isRestoring || observers.isEmpty()) return
    val event = WorldEvent(
      instant = clock.currentInstant, type = type, data = data, sourceId = entityId, snapshot = snapshot()
    )
    observers.forEach { it.onEvent(event) }
  }

  fun registerEntity(entity: WorldEntity) {
    entities[entity.id] = entity
    publish("EntityRegistered", entity, entity.id)
  }

  fun getEntity(id: WorldEntityId): WorldEntity? {
    return entities[id]
  }

  fun hasEntity(id: WorldEntityId): Boolean {
    return entities.containsKey(id)
  }

  fun removeEntity(id: WorldEntityId) {
    if (entities.remove(id) != null) {
      publish("EntityRemoved", id, id)
    }
  }

  fun registerGroup(group: WorldGroup) {
    group.eventPublisher = ::publish
    groups[group.id] = group
    publish("GroupRegistered", group)
  }

  fun getGroup(id: WorldGroupId): WorldGroup? {
    return groups[id]
  }

  fun removeGroup(id: WorldGroupId) {
    val group = groups.remove(id)
    if (group != null) {
      group.eventPublisher = null
      publish("GroupRemoved", id)
    }
  }

  fun startMovement(movement: WorldMovement) {
    movements[movement.entityId] = movement
    publish("MovementStarted", movement, movement.entityId)
  }

  fun getMovement(entityId: WorldEntityId): WorldMovement? {
    return movements[entityId]
  }

  fun stopMovement(entityId: WorldEntityId) {
    val movement = movements.remove(entityId)
    if (movement != null) {
      publish("MovementStopped", entityId, entityId)
    }
  }

  fun getActiveMovements(): List<WorldMovement> {
    return movements.values.toList()
  }

  fun snapshot(): WorldSnapshot {
    return WorldSnapshot(
      id = id,
      entities = entities.values.toList(),
      entityLocations = state.snapshot(),
      graph = graph.snapshot(),
      groups = groups.values.map { it.snapshot() },
      currentInstant = clock.currentInstant,
      scheduler = scheduler.snapshot(),
      activeMovements = movements.values.toList()
    )
  }

  fun advance(duration: WorldDuration): List<WorldScheduledEvent> {
    if (duration.value <= 0) return emptyList()
    val targetInstant = clock.currentInstant + duration

    clock.advance(duration)
    val processedEvents = scheduler.processEventsUpTo(targetInstant)

    val activeMovements = movements.values.toList()
    for (movement in activeMovements) {
      val updated = movement.updateAt(targetInstant)
      if (updated.isCompleted) {
        movements.remove(movement.entityId)
        state.setLocation(movement.entityId, movement.destination)
        publish("MovementCompleted", updated, movement.entityId)
      } else if (updated.isInProgress) {
        movements[movement.entityId] = updated
      }
    }

    return processedEvents
  }

  fun step(): WorldStepResult {
    val currentInstant = clock.currentInstant

    val futureEvents = scheduler.getFutureEvents(currentInstant)
    val nextEventInstant = futureEvents.minOfOrNull { it.instant }

    val nextMovementInstant = movements.values.filter { it.isInProgress }.minOfOrNull { it.completionInstant }

    val nextInstants = listOfNotNull(nextEventInstant, nextMovementInstant).filter { it > currentInstant }
    val targetInstant = nextInstants.minOrNull() ?: return WorldStepResult(
      advanced = false,
      previousInstant = currentInstant,
      currentInstant = currentInstant,
      processedEvents = emptyList(),
      completedMovements = emptyList()
    )

    val duration = targetInstant - currentInstant
    val previousInstant = currentInstant

    val targetMovementsBefore = movements.values.filter { it.isInProgress && it.completionInstant == targetInstant }
    val processedEvents = advance(duration)

    return WorldStepResult(
      advanced = true,
      previousInstant = previousInstant,
      currentInstant = clock.currentInstant,
      processedEvents = processedEvents,
      completedMovements = targetMovementsBefore.map { it.copy(state = WorldMovementState.COMPLETED, progress = 1.0) })
  }

  companion object {
    fun restore(snapshot: WorldSnapshot, calendar: WorldCalendar? = null): World {
      val world = World(snapshot.id)
      world.isRestoring = true
      world.calendar = calendar

      snapshot.entities.forEach { world.registerEntity(it) }
      world.state.restore(snapshot.entityLocations)
      world.graph.restore(snapshot.graph)

      snapshot.groups.forEach { groupSnap ->
        val group = WorldGroup(groupSnap.id)
        group.restore(groupSnap)
        world.registerGroup(group)
      }

      world.clock.restore(snapshot.currentInstant)
      world.scheduler.restore(snapshot.scheduler)

      snapshot.activeMovements.forEach { world.startMovement(it) }

      world.isRestoring = false
      world.publish("WorldRestored", snapshot)

      return world
    }
  }
}

data class WorldStepResult(
  val advanced: Boolean,
  val previousInstant: WorldInstant,
  val currentInstant: WorldInstant,
  val processedEvents: List<WorldScheduledEvent>,
  val completedMovements: List<WorldMovement>
)

data class WorldPath(
  val origin: WorldLocationId, val destination: WorldLocationId, val connections: List<WorldConnection> = emptyList()
) {
  val locations: List<WorldLocationId>
    get() {
      if (connections.isEmpty()) return listOf(origin)
      val result = ArrayList<WorldLocationId>(connections.size + 1)
      result.add(origin)
      connections.mapTo(result) { it.to }
      return result
    }
}

data class WorldAccessContext(
  val entityId: WorldEntityId? = null,
  val locationId: WorldLocationId? = null,
  val state: WorldState? = null,
  val payload: Any? = null
)

fun interface WorldAccessEvaluator {
  fun canTraverse(connection: WorldConnection, context: WorldAccessContext): Boolean
}

interface WorldPathFinder {
  fun findPath(
    graph: WorldGraph,
    from: WorldLocationId,
    to: WorldLocationId,
    accessEvaluator: WorldAccessEvaluator? = null,
    accessContext: WorldAccessContext = WorldAccessContext()
  ): WorldPath?
}

class UnweightedPathFinder : WorldPathFinder {
  override fun findPath(
    graph: WorldGraph,
    from: WorldLocationId,
    to: WorldLocationId,
    accessEvaluator: WorldAccessEvaluator?,
    accessContext: WorldAccessContext
  ): WorldPath? {
    if (graph.getLocation(from) == null || graph.getLocation(to) == null) return null
    if (from == to) return WorldPath(from, to, emptyList())

    val queue = ArrayDeque<WorldLocationId>()
    val parentConnection = mutableMapOf<WorldLocationId, WorldConnection>()
    val visited = mutableSetOf<WorldLocationId>()

    queue.add(from)
    visited.add(from)

    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      if (current == to) break

      for (conn in graph.getOutgoingConnections(current)) {
        val currentCtx =
          if (accessContext.locationId == null) accessContext.copy(locationId = current) else accessContext
        if (accessEvaluator != null && !accessEvaluator.canTraverse(conn, currentCtx)) {
          continue
        }
        val next = conn.to
        if (next !in visited) {
          visited.add(next)
          parentConnection[next] = conn
          queue.add(next)
        }
      }
    }

    if (to !in parentConnection) return null

    val pathConnections = mutableListOf<WorldConnection>()
    var curr = to
    while (curr != from) {
      val conn = parentConnection[curr] ?: break
      pathConnections.add(conn)
      curr = conn.from
    }
    pathConnections.reverse()

    return WorldPath(from, to, pathConnections)
  }
}

fun interface WorldNavigationCost {
  fun getCost(connection: WorldConnection, context: WorldAccessContext): Double
}

fun interface WorldHeuristic {
  fun estimate(from: WorldLocationId, to: WorldLocationId): Double
}

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
    if (from == to) return WorldPath(from, to, emptyList())

    val distances = mutableMapOf<WorldLocationId, Double>().withDefault { Double.POSITIVE_INFINITY }
    val previousConnection = mutableMapOf<WorldLocationId, WorldConnection>()
    val unvisited = java.util.PriorityQueue<Pair<WorldLocationId, Double>>(compareBy { it.second })
    val visited = mutableSetOf<WorldLocationId>()

    distances[from] = 0.0
    unvisited.add(from to 0.0)

    while (unvisited.isNotEmpty()) {
      val (current, currentDist) = unvisited.poll()

      if (current == to) break
      if (!visited.add(current)) continue

      for (conn in graph.getOutgoingConnections(current)) {
        val currentCtx =
          if (accessContext.locationId == null) accessContext.copy(locationId = current) else accessContext
        if (accessEvaluator != null && !accessEvaluator.canTraverse(conn, currentCtx)) {
          continue
        }

        val cost = costEvaluator.getCost(conn, currentCtx)
        require(cost >= 0.0) { "O custo de navegação não pode ser negativo." }

        val newDist = currentDist + cost
        val next = conn.to

        if (newDist < distances.getValue(next)) {
          distances[next] = newDist
          previousConnection[next] = conn
          unvisited.add(next to newDist)
        }
      }
    }

    if (to !in previousConnection) return null

    val pathConnections = mutableListOf<WorldConnection>()
    var curr = to
    while (curr != from) {
      val conn = previousConnection[curr] ?: break
      pathConnections.add(conn)
      curr = conn.from
    }
    pathConnections.reverse()

    return WorldPath(from, to, pathConnections)
  }
}

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
    if (from == to) return WorldPath(from, to, emptyList())

    val gScore = mutableMapOf<WorldLocationId, Double>().withDefault { Double.POSITIVE_INFINITY }
    val previousConnection = mutableMapOf<WorldLocationId, WorldConnection>()
    val openSet = java.util.PriorityQueue<Pair<WorldLocationId, Double>>(compareBy { it.second })
    val visited = mutableSetOf<WorldLocationId>()

    gScore[from] = 0.0
    openSet.add(from to heuristic.estimate(from, to))

    while (openSet.isNotEmpty()) {
      val (current, _) = openSet.poll()

      if (current == to) break
      if (!visited.add(current)) continue

      val currentGScore = gScore.getValue(current)

      for (conn in graph.getOutgoingConnections(current)) {
        val currentCtx =
          if (accessContext.locationId == null) accessContext.copy(locationId = current) else accessContext
        if (accessEvaluator != null && !accessEvaluator.canTraverse(conn, currentCtx)) {
          continue
        }

        val cost = costEvaluator.getCost(conn, currentCtx)
        require(cost >= 0.0) { "O custo de navegação não pode ser negativo." }

        val tentativeGScore = currentGScore + cost
        val next = conn.to

        if (tentativeGScore < gScore.getValue(next)) {
          previousConnection[next] = conn
          gScore[next] = tentativeGScore
          val fScore = tentativeGScore + heuristic.estimate(next, to)
          openSet.add(next to fScore)
        }
      }
    }

    if (to !in previousConnection) return null

    val pathConnections = mutableListOf<WorldConnection>()
    var curr = to
    while (curr != from) {
      val conn = previousConnection[curr] ?: break
      pathConnections.add(conn)
      curr = conn.from
    }
    pathConnections.reverse()

    return WorldPath(from, to, pathConnections)
  }
}

data class WorldGraphSnapshot(
  val locations: List<WorldLocation>, val connections: List<WorldConnection>
)

class WorldGraph {
  internal var eventPublisher: ((String, Any?, WorldEntityId?) -> Unit)? = null

  private val locations = mutableMapOf<WorldLocationId, WorldLocation>()
  private val connections = mutableMapOf<WorldConnectionId, WorldConnection>()
  private val outgoingConnections = mutableMapOf<WorldLocationId, MutableList<WorldConnection>>()

  fun registerLocation(location: WorldLocation) {
    locations[location.id] = location
    eventPublisher?.invoke("LocationRegistered", location, null)
  }

  fun registerConnection(connection: WorldConnection) {
    require(locations.containsKey(connection.from)) { "A localização de origem '${connection.from.value}' deve estar registada no grafo." }
    require(locations.containsKey(connection.to)) { "A localização de destino '${connection.to.value}' deve estar registada no grafo." }
    connections[connection.id] = connection
    outgoingConnections.getOrPut(connection.from) { mutableListOf() }.add(connection)
    eventPublisher?.invoke("ConnectionRegistered", connection, null)
  }

  fun getLocation(id: WorldLocationId): WorldLocation? {
    return locations[id]
  }

  fun getConnection(id: WorldConnectionId): WorldConnection? {
    return connections[id]
  }

  fun getOutgoingConnections(locationId: WorldLocationId): List<WorldConnection> {
    return outgoingConnections[locationId]?.toList() ?: emptyList()
  }

  fun getNeighbors(locationId: WorldLocationId): List<WorldLocationId> {
    return getOutgoingConnections(locationId).map { it.to }.distinct()
  }

  fun isReachable(
    from: WorldLocationId,
    to: WorldLocationId,
    pathFinder: WorldPathFinder = UnweightedPathFinder(),
    accessEvaluator: WorldAccessEvaluator? = null,
    accessContext: WorldAccessContext = WorldAccessContext()
  ): Boolean {
    return pathFinder.findPath(this, from, to, accessEvaluator, accessContext) != null
  }

  fun findPath(
    from: WorldLocationId,
    to: WorldLocationId,
    pathFinder: WorldPathFinder = UnweightedPathFinder(),
    accessEvaluator: WorldAccessEvaluator? = null,
    accessContext: WorldAccessContext = WorldAccessContext()
  ): WorldPath? {
    return pathFinder.findPath(this, from, to, accessEvaluator, accessContext)
  }

  fun snapshot(): WorldGraphSnapshot {
    return WorldGraphSnapshot(locations.values.toList(), connections.values.toList())
  }

  fun restore(snapshot: WorldGraphSnapshot) {
    locations.clear()
    connections.clear()
    outgoingConnections.clear()
    snapshot.locations.forEach { registerLocation(it) }
    snapshot.connections.forEach { registerConnection(it) }
  }
}

class WorldState {
  internal var eventPublisher: ((String, Any?, WorldEntityId?) -> Unit)? = null

  private val entityLocations = mutableMapOf<WorldEntityId, WorldLocationId>()

  fun setLocation(entityId: WorldEntityId, locationId: WorldLocationId) {
    entityLocations[entityId] = locationId
    eventPublisher?.invoke("LocationChanged", mapOf("entityId" to entityId, "locationId" to locationId), entityId)
  }

  fun getLocation(entityId: WorldEntityId): WorldLocationId? {
    return entityLocations[entityId]
  }

  fun removeLocation(entityId: WorldEntityId) {
    if (entityLocations.remove(entityId) != null) {
      eventPublisher?.invoke("LocationRemoved", entityId, entityId)
    }
  }

  fun snapshot(): Map<WorldEntityId, WorldLocationId> {
    return entityLocations.toMap()
  }

  fun restore(snapshot: Map<WorldEntityId, WorldLocationId>) {
    entityLocations.clear()
    entityLocations.putAll(snapshot)
  }
}

@JvmInline
value class WorldGroupId(val value: String)

data class WorldGroupSnapshot(
  val id: WorldGroupId, val locations: Set<WorldLocationId>
)

class WorldGroup(val id: WorldGroupId) {
  internal var eventPublisher: ((String, Any?, WorldEntityId?) -> Unit)? = null

  private val locations = mutableSetOf<WorldLocationId>()

  fun addLocation(locationId: WorldLocationId) {
    if (locations.add(locationId)) {
      eventPublisher?.invoke("GroupLocationAdded", mapOf("groupId" to id, "locationId" to locationId), null)
    }
  }

  fun removeLocation(locationId: WorldLocationId) {
    if (locations.remove(locationId)) {
      eventPublisher?.invoke("GroupLocationRemoved", mapOf("groupId" to id, "locationId" to locationId), null)
    }
  }

  fun hasLocation(locationId: WorldLocationId): Boolean {
    return locations.contains(locationId)
  }

  fun getLocations(): Set<WorldLocationId> {
    return locations.toSet()
  }

  fun snapshot(): WorldGroupSnapshot {
    return WorldGroupSnapshot(id, locations.toSet())
  }

  fun restore(snapshot: WorldGroupSnapshot) {
    locations.clear()
    locations.addAll(snapshot.locations)
  }
}

@JvmInline
value class WorldDuration(val value: Long) : Comparable<WorldDuration> {
  override fun compareTo(other: WorldDuration): Int {
    return this.value.compareTo(other.value)
  }
}

@JvmInline
value class WorldInstant(val value: Long) : Comparable<WorldInstant> {
  override fun compareTo(other: WorldInstant): Int {
    return this.value.compareTo(other.value)
  }

  operator fun minus(other: WorldInstant): WorldDuration {
    return WorldDuration(this.value - other.value)
  }

  operator fun plus(duration: WorldDuration): WorldInstant {
    return WorldInstant(this.value + duration.value)
  }

  operator fun minus(duration: WorldDuration): WorldInstant {
    return WorldInstant(this.value - duration.value)
  }
}

data class CalendarDate(
  val year: Long, val month: Int, val day: Int, val hour: Int, val minute: Int, val second: Int
)

class WorldClock(initialInstant: WorldInstant = WorldInstant(0)) {
  internal var eventPublisher: ((String, Any?, WorldEntityId?) -> Unit)? = null

  var currentInstant: WorldInstant = initialInstant
    private set

  fun advance(duration: WorldDuration) {
    if (duration.value > 0) {
      currentInstant += duration
      eventPublisher?.invoke("ClockAdvanced", duration, null)
    }
  }

  fun restore(instant: WorldInstant) {
    currentInstant = instant
  }
}

interface WorldCalendar {
  fun toDate(instant: WorldInstant): CalendarDate
}

@JvmInline
value class WorldScheduledEventId(val value: String)

@JvmInline
value class WorldRecurrenceId(val value: String)

data class WorldRecurrence(
  val id: WorldRecurrenceId, val interval: WorldDuration
)

data class WorldScheduledEvent(
  val id: WorldScheduledEventId,
  val instant: WorldInstant,
  val type: String,
  val payload: Any? = null,
  val recurrenceId: WorldRecurrenceId? = null
) : Comparable<WorldScheduledEvent> {
  override fun compareTo(other: WorldScheduledEvent): Int {
    val instantComparison = this.instant.compareTo(other.instant)
    if (instantComparison != 0) return instantComparison
    return this.id.value.compareTo(other.id.value)
  }
}

data class WorldSchedulerSnapshot(
  val events: List<WorldScheduledEvent>, val recurrences: List<WorldRecurrence>
)

class WorldScheduler {
  internal var eventPublisher: ((String, Any?, WorldEntityId?) -> Unit)? = null

  private val events = mutableMapOf<WorldScheduledEventId, WorldScheduledEvent>()
  private val recurrences = mutableMapOf<WorldRecurrenceId, WorldRecurrence>()

  fun schedule(event: WorldScheduledEvent) {
    events[event.id] = event
    eventPublisher?.invoke("EventScheduled", event, null)
  }

  fun cancel(eventId: WorldScheduledEventId) {
    if (events.remove(eventId) != null) {
      eventPublisher?.invoke("EventCanceled", eventId, null)
    }
  }

  fun defineRecurrence(recurrence: WorldRecurrence) {
    recurrences[recurrence.id] = recurrence
    eventPublisher?.invoke("RecurrenceDefined", recurrence, null)
  }

  fun cancelRecurrence(recurrenceId: WorldRecurrenceId) {
    if (recurrences.remove(recurrenceId) != null) {
      eventPublisher?.invoke("RecurrenceCanceled", recurrenceId, null)
    }
  }

  fun getRecurrence(recurrenceId: WorldRecurrenceId): WorldRecurrence? {
    return recurrences[recurrenceId]
  }

  fun getFutureEvents(afterInstant: WorldInstant): List<WorldScheduledEvent> {
    return events.values.filter { it.instant > afterInstant }.sorted()
  }

  fun processEventsUpTo(currentInstant: WorldInstant): List<WorldScheduledEvent> {
    val processed = mutableListOf<WorldScheduledEvent>()

    while (true) {
      val reached = events.values.filter { it.instant <= currentInstant }.sorted()
      if (reached.isEmpty()) break

      for (event in reached) {
        events.remove(event.id)
        processed.add(event)

        eventPublisher?.invoke("EventProcessed", event, null)

        event.recurrenceId?.let { recId ->
          recurrences[recId]?.let { recurrence ->
            if (recurrence.interval.value > 0L) {
              val nextInstant = event.instant + recurrence.interval
              schedule(event.copy(instant = nextInstant))
            }
          }
        }
      }
    }

    return processed
  }

  fun snapshot(): WorldSchedulerSnapshot {
    return WorldSchedulerSnapshot(events.values.toList(), recurrences.values.toList())
  }

  fun restore(snapshot: WorldSchedulerSnapshot) {
    events.clear()
    recurrences.clear()
    snapshot.events.forEach { schedule(it) }
    snapshot.recurrences.forEach { defineRecurrence(it) }
  }
}

interface WorldDurationEstimator {
  fun estimateDuration(
    path: WorldPath, cost: Double, profile: Any? = null
  ): WorldDuration
}

enum class WorldMovementState {
  IN_PROGRESS, COMPLETED, INTERRUPTED
}

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
    require(progress in 0.0..1.0) { "O progresso do movimento deve estar entre 0.0 e 1.0." }
  }

  val completionInstant: WorldInstant
    get() = startInstant + duration

  val isInProgress: Boolean get() = state == WorldMovementState.IN_PROGRESS
  val isCompleted: Boolean get() = state == WorldMovementState.COMPLETED
  val isInterrupted: Boolean get() = state == WorldMovementState.INTERRUPTED

  fun progressAt(currentInstant: WorldInstant): Double {
    if (state == WorldMovementState.INTERRUPTED) return progress
    if (duration.value <= 0L) return 1.0
    if (currentInstant <= startInstant) return 0.0
    if (currentInstant >= completionInstant) return 1.0

    val elapsed = (currentInstant - startInstant).value.toDouble()
    val total = duration.value.toDouble()
    return (elapsed / total).coerceIn(0.0, 1.0)
  }

  fun updateAt(currentInstant: WorldInstant): WorldMovement {
    if (state == WorldMovementState.INTERRUPTED) return this
    val currentProgress = progressAt(currentInstant)
    val newState = if (currentProgress >= 1.0) WorldMovementState.COMPLETED else WorldMovementState.IN_PROGRESS
    return copy(progress = currentProgress, state = newState)
  }
}

data class WorldSnapshot(
  val id: WorldId,
  val entities: List<WorldEntity>,
  val entityLocations: Map<WorldEntityId, WorldLocationId>,
  val graph: WorldGraphSnapshot,
  val groups: List<WorldGroupSnapshot>,
  val currentInstant: WorldInstant,
  val scheduler: WorldSchedulerSnapshot,
  val activeMovements: List<WorldMovement>
)