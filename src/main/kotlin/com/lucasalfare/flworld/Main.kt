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
  val id: WorldEntityId
)

data class WorldLocation(
  val id: WorldLocationId
)

data class WorldConnection(
  val id: WorldConnectionId, val from: WorldLocationId, val to: WorldLocationId
)

class World(val id: WorldId) {
  private val entities = mutableMapOf<WorldEntityId, WorldEntity>()

  fun registerEntity(entity: WorldEntity) {
    entities[entity.id] = entity
  }

  fun getEntity(id: WorldEntityId): WorldEntity? {
    return entities[id]
  }

  fun hasEntity(id: WorldEntityId): Boolean {
    return entities.containsKey(id)
  }

  fun removeEntity(id: WorldEntityId) {
    entities.remove(id)
  }
}

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

class WorldGraph {
  private val locations = mutableMapOf<WorldLocationId, WorldLocation>()
  private val connections = mutableMapOf<WorldConnectionId, WorldConnection>()
  private val outgoingConnections = mutableMapOf<WorldLocationId, MutableList<WorldConnection>>()

  fun registerLocation(location: WorldLocation) {
    locations[location.id] = location
  }

  fun registerConnection(connection: WorldConnection) {
    connections[connection.id] = connection
    outgoingConnections.getOrPut(connection.from) { mutableListOf() }.add(connection)
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
}

class WorldState {
  private val entityLocations = mutableMapOf<WorldEntityId, WorldLocationId>()

  fun setLocation(entityId: WorldEntityId, locationId: WorldLocationId) {
    entityLocations[entityId] = locationId
  }

  fun getLocation(entityId: WorldEntityId): WorldLocationId? {
    return entityLocations[entityId]
  }

  fun removeLocation(entityId: WorldEntityId) {
    entityLocations.remove(entityId)
  }
}

@JvmInline
value class WorldGroupId(val value: String)

class WorldGroup(val id: WorldGroupId) {
  private val locations = mutableSetOf<WorldLocationId>()

  fun addLocation(locationId: WorldLocationId) {
    locations.add(locationId)
  }

  fun removeLocation(locationId: WorldLocationId) {
    locations.remove(locationId)
  }

  fun hasLocation(locationId: WorldLocationId): Boolean {
    return locations.contains(locationId)
  }

  fun getLocations(): Set<WorldLocationId> {
    return locations.toSet()
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
  var currentInstant: WorldInstant = initialInstant
    private set

  fun advance(duration: WorldDuration) {
    currentInstant += duration
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

class WorldScheduler {
  private val events = mutableMapOf<WorldScheduledEventId, WorldScheduledEvent>()
  private val recurrences = mutableMapOf<WorldRecurrenceId, WorldRecurrence>()

  fun schedule(event: WorldScheduledEvent) {
    events[event.id] = event
  }

  fun cancel(eventId: WorldScheduledEventId) {
    events.remove(eventId)
  }

  fun defineRecurrence(recurrence: WorldRecurrence) {
    recurrences[recurrence.id] = recurrence
  }

  fun cancelRecurrence(recurrenceId: WorldRecurrenceId) {
    recurrences.remove(recurrenceId)
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

  val isInProgress: Boolean get() = state == WorldMovementState.IN_PROGRESS
  val isCompleted: Boolean get() = state == WorldMovementState.COMPLETED
  val isInterrupted: Boolean get() = state == WorldMovementState.INTERRUPTED
}