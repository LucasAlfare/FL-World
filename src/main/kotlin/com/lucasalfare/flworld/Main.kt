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
  val id: WorldConnectionId,
  val from: WorldLocationId,
  val to: WorldLocationId
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
  val origin: WorldLocationId,
  val destination: WorldLocationId,
  val connections: List<WorldConnection> = emptyList()
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

  fun isReachable(from: WorldLocationId, to: WorldLocationId): Boolean {
    return findPath(from, to) != null
  }

  fun findPath(from: WorldLocationId, to: WorldLocationId): WorldPath? {
    if (getLocation(from) == null || getLocation(to) == null) return null
    if (from == to) return WorldPath(from, to, emptyList())

    val queue = ArrayDeque<WorldLocationId>()
    val parentConnection = mutableMapOf<WorldLocationId, WorldConnection>()
    val visited = mutableSetOf<WorldLocationId>()

    queue.add(from)
    visited.add(from)

    while (queue.isNotEmpty()) {
      val current = queue.removeFirst()
      if (current == to) break

      for (conn in getOutgoingConnections(current)) {
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
  val year: Long,
  val month: Int,
  val day: Int,
  val hour: Int,
  val minute: Int,
  val second: Int
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
  val id: WorldRecurrenceId,
  val interval: WorldDuration
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