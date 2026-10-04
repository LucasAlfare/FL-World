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

@JvmInline
value class WorldGroupId(val value: String)

@JvmInline
value class WorldScheduledEventId(val value: String)

@JvmInline
value class WorldRecurrenceId(val value: String)

data class WorldEntity(
  val id: WorldEntityId, val isEventPropagationEnabled: Boolean = false
)

data class WorldLocation(val id: WorldLocationId)

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

class World(val id: WorldId, val calendar: WorldCalendar? = null) {
  private val entities = mutableMapOf<WorldEntityId, WorldEntity>()
  private val groups = mutableMapOf<WorldGroupId, WorldGroup>()
  private val movements = mutableMapOf<WorldEntityId, WorldMovement>()
  private val observers = mutableListOf<WorldObserver>()

  internal var isRestoring = false

  val graph = WorldGraph().apply {
    eventPublisher = ::notifyObservers
    locationRemovalHandler = ::handleLocationRemoval
    connectionRemovalHandler = ::handleConnectionRemoval
    restoreValidator = ::validateGraphRestore
  }
  val state = WorldState().apply {
    eventPublisher = ::notifyObservers
    entityValidator = ::hasEntity
    locationValidator = { graph.getLocation(it) != null }
    locationChangeValidator = ::validateEntityLocationChange
  }
  val clock = WorldClock().apply {
    eventPublisher = ::notifyObservers
  }
  val scheduler = WorldScheduler().apply {
    eventPublisher = ::notifyObservers
    currentInstantProvider = { clock.currentInstant }
  }

  // TODO: implementar "remove" observador! Pra evitar ter entidades fantasmas aqui e erros posteriores
  fun addObserver(observer: WorldObserver) {
    observers.add(observer)
  }

  internal fun notifyObservers(type: String, data: Any? = null, sourceId: WorldEntityId? = null) {
    if (isRestoring || observers.isEmpty()) return
    val event = WorldEvent(
      instant = clock.currentInstant, type = type, data = data, sourceId = sourceId, snapshot = snapshot()
    )
    observers.toList().forEach { it.onEvent(event) }
  }

  fun enableEntityEventPropagation(entityId: WorldEntityId) {
    setEntityEventPropagation(entityId, true)
  }

  fun disableEntityEventPropagation(entityId: WorldEntityId) {
    setEntityEventPropagation(entityId, false)
  }

  fun isEntityEventPropagationEnabled(entityId: WorldEntityId): Boolean {
    return entities[entityId]?.isEventPropagationEnabled ?: false
  }

  private fun setEntityEventPropagation(entityId: WorldEntityId, enabled: Boolean) {
    val entity = entities[entityId] ?: return
    if (entity.isEventPropagationEnabled == enabled) return
    entities[entityId] = entity.copy(isEventPropagationEnabled = enabled)
    notifyObservers("EntityEventPropagationChanged", enabled, entityId)
  }

  fun publishEntityEvent(entityId: WorldEntityId, type: String, data: Any? = null) {
    val entity = entities[entityId] ?: return
    if (!entity.isEventPropagationEnabled || isRestoring || observers.isEmpty()) return
    val event = WorldEvent(
      instant = clock.currentInstant, type = type, data = data, sourceId = entityId, snapshot = snapshot()
    )
    observers.toList().forEach { it.onEvent(event) }
  }

  fun registerEntity(entity: WorldEntity) {
    require(entity.id !in entities) { "A entidade '${entity.id.value}' já está registada." }
    entities[entity.id] = entity
    notifyObservers("EntityRegistered", entity, entity.id)
  }

  fun getEntity(id: WorldEntityId): WorldEntity? {
    return entities[id]
  }

  fun hasEntity(id: WorldEntityId): Boolean {
    return entities.containsKey(id)
  }

  fun removeEntity(id: WorldEntityId) {
    if (id !in entities) return
    stopMovement(id)
    state.removeLocation(id)
    entities.remove(id)
    notifyObservers("EntityRemoved", id, id)
  }

  fun registerGroup(group: WorldGroup) {
    require(group.id !in groups) { "O grupo '${group.id.value}' já está registado." }
    group.getLocations().forEach { locationId ->
      require(graph.getLocation(locationId) != null) {
        "A localização '${locationId.value}' deve estar registada no grafo."
      }
    }
    group.eventPublisher = ::notifyObservers
    group.locationValidator = { graph.getLocation(it) != null }
    groups[group.id] = group
    notifyObservers("GroupRegistered", group)
  }

  fun getGroup(id: WorldGroupId): WorldGroup? {
    return groups[id]
  }

  fun removeGroup(id: WorldGroupId) {
    val group = groups.remove(id) ?: return
    group.eventPublisher = null
    group.locationValidator = null
    notifyObservers("GroupRemoved", id)
  }

  fun startMovement(movement: WorldMovement) {
    require(movement.entityId in entities) {
      "A entidade '${movement.entityId.value}' deve existir para iniciar um movimento."
    }
    require(movement.entityId !in movements) {
      "A entidade '${movement.entityId.value}' já possui um movimento ativo."
    }
    require(movement.state == WorldMovementState.IN_PROGRESS) {
      "Um movimento iniciado deve estar em andamento."
    }
    require(movement.duration.value > 0L) {
      "A duração do movimento deve ser positiva."
    }
    require(movement.startInstant <= clock.currentInstant) {
      "O instante inicial do movimento não pode estar no futuro."
    }
    require(movement.completionInstant > clock.currentInstant) {
      "O movimento já deveria estar concluído no instante atual."
    }
    require(graph.getLocation(movement.origin) != null) {
      "A localização de origem '${movement.origin.value}' não existe no grafo."
    }
    require(graph.getLocation(movement.destination) != null) {
      "A localização de destino '${movement.destination.value}' não existe no grafo."
    }
    require(movement.path.origin == movement.origin && movement.path.destination == movement.destination) {
      "O caminho do movimento deve possuir a mesma origem e destino do movimento."
    }
    validatePath(movement.path)

    val currentLocation = state.getLocation(movement.entityId)
    require(currentLocation == null || currentLocation == movement.origin) {
      "A localização atual da entidade deve ser nula ou igual à origem do movimento."
    }

    movements[movement.entityId] = movement
    notifyObservers("MovementStarted", movement, movement.entityId)
  }

  fun getMovement(entityId: WorldEntityId): WorldMovement? {
    return movements[entityId]
  }

  fun stopMovement(entityId: WorldEntityId) {
    val movement = movements.remove(entityId) ?: return
    val interrupted = movement.copy(state = WorldMovementState.INTERRUPTED)
    notifyObservers("MovementInterrupted", interrupted, entityId)
  }

  fun getActiveMovements(): List<WorldMovement> {
    return movements.values.sortedBy { it.entityId.value }
  }

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

  fun advance(duration: WorldDuration): List<WorldScheduledEvent> {
    require(duration.value >= 0L) { "A duração do avanço não pode ser negativa." }
    if (duration.value == 0L) return emptyList()
    return advanceTo(clock.currentInstant + duration).processedEvents
  }

  private fun advanceTo(targetInstant: WorldInstant): AdvanceResult {
    require(targetInstant >= clock.currentInstant) { "O instante alvo não pode estar no passado." }

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

  private fun handleLocationRemoval(locationId: WorldLocationId) {
    movements.values.filter { locationId in it.path.locations }.sortedBy { it.entityId.value }.map { it.entityId }
      .forEach(::stopMovement)

    entities.keys.filter { state.getLocation(it) == locationId }.sortedBy { it.value }.forEach(state::removeLocation)

    groups.values.filter { it.hasLocation(locationId) }.sortedBy { it.id.value }
      .forEach { it.removeLocation(locationId) }
  }

  private fun handleConnectionRemoval(connectionId: WorldConnectionId) {
    movements.values.filter { movement -> movement.path.connections.any { it.id == connectionId } }
      .sortedBy { it.entityId.value }.map { it.entityId }.forEach(::stopMovement)
  }

  private fun validateEntityLocationChange(entityId: WorldEntityId, locationId: WorldLocationId) {
    val movement = movements[entityId] ?: return
    require(locationId == movement.origin) {
      "Uma entidade em movimento só pode possuir a localização estável correspondente à origem do movimento."
    }
  }

  private fun validatePath(path: WorldPath) {
    path.connections.forEach { connection ->
      val graphConnection = graph.getConnection(connection.id)
      require(graphConnection == connection) {
        "A conexão '${connection.id.value}' do caminho não corresponde à conexão registada no grafo."
      }
    }
  }

  private fun validateGraphRestore(snapshot: WorldGraphSnapshot) {
    val locationIds = snapshot.locations.map { it.id }
    require(locationIds.distinct().size == locationIds.size) {
      "O snapshot do grafo possui localidades duplicadas."
    }

    val locationSet = locationIds.toSet()
    snapshot.connections.forEach { connection ->
      require(connection.from in locationSet && connection.to in locationSet) {
        "A conexão '${connection.id.value}' referencia uma localização inexistente."
      }
    }
    require(snapshot.connections.map { it.id }.distinct().size == snapshot.connections.size) {
      "O snapshot do grafo possui conexões duplicadas."
    }

    entities.values.forEach { entity ->
      state.getLocation(entity.id)?.let { locationId ->
        require(locationId in locationSet) {
          "A entidade '${entity.id.value}' referencia uma localização inexistente no snapshot do grafo."
        }
      }
    }
    groups.values.forEach { group ->
      group.getLocations().forEach { locationId ->
        require(locationId in locationSet) {
          "O grupo '${group.id.value}' referencia uma localização inexistente no snapshot do grafo."
        }
      }
    }
    movements.values.forEach { movement ->
      require(movement.path.locations.all { it in locationSet }) {
        "O movimento da entidade '${movement.entityId.value}' referencia uma localização inexistente no snapshot do grafo."
      }
      require(movement.path.connections.all { connection ->
        snapshot.connections.any { it == connection }
      }) {
        "O movimento da entidade '${movement.entityId.value}' referencia uma conexão inexistente no snapshot do grafo."
      }
    }
  }

  private fun validateWorldSnapshot(snapshot: WorldSnapshot) {
    require(snapshot.entities.map { it.id }.distinct().size == snapshot.entities.size) {
      "O snapshot possui entidades duplicadas."
    }
    require(snapshot.groups.map { it.id }.distinct().size == snapshot.groups.size) {
      "O snapshot possui grupos duplicados."
    }
    require(snapshot.entityLocations.keys.all { entityId -> snapshot.entities.any { it.id == entityId } }) {
      "O snapshot possui localizações associadas a entidades inexistentes."
    }

    val locationIds = snapshot.graph.locations.map { it.id }.toSet()
    val graphConnectionIds = snapshot.graph.connections.map { it.id }
    require(graphConnectionIds.distinct().size == graphConnectionIds.size) {
      "O snapshot possui conexões duplicadas."
    }
    snapshot.graph.connections.forEach { connection ->
      require(connection.from in locationIds && connection.to in locationIds) {
        "Uma conexão do snapshot referencia uma localização inexistente."
      }
    }

    require(snapshot.entityLocations.values.all { it in locationIds }) {
      "O snapshot possui entidades associadas a localizações inexistentes."
    }

    snapshot.groups.forEach { group ->
      require(group.locations.all { it in locationIds }) {
        "O snapshot possui grupos associados a localizações inexistentes."
      }
    }

    val recurrenceIds = snapshot.scheduler.recurrences.map { it.id }
    require(recurrenceIds.distinct().size == recurrenceIds.size) {
      "O snapshot possui recorrências duplicadas."
    }
    require(snapshot.scheduler.recurrences.all { it.interval.value > 0L }) {
      "As recorrências do snapshot devem possuir intervalos positivos."
    }

    val eventIds = snapshot.scheduler.events.map { it.id }
    require(eventIds.distinct().size == eventIds.size) {
      "O snapshot possui acontecimentos agendados duplicados."
    }
    val recurrenceIdSet = recurrenceIds.toSet()
    require(snapshot.scheduler.events.all { it.recurrenceId == null || it.recurrenceId in recurrenceIdSet }) {
      "Um acontecimento agendado referencia uma recorrência inexistente."
    }
    require(snapshot.scheduler.events.all { it.instant >= snapshot.currentInstant }) {
      "O snapshot possui acontecimentos agendados no passado."
    }

    require(snapshot.activeMovements.map { it.entityId }.distinct().size == snapshot.activeMovements.size) {
      "O snapshot possui movimentos duplicados para a mesma entidade."
    }
    snapshot.activeMovements.forEach { movement ->
      require(movement.state == WorldMovementState.IN_PROGRESS) {
        "Somente movimentos em andamento podem aparecer como ativos no snapshot."
      }
      require(movement.entityId in snapshot.entities.map { it.id }) {
        "Um movimento referencia uma entidade inexistente."
      }
      require(movement.origin in locationIds && movement.destination in locationIds) {
        "Um movimento referencia localidades inexistentes."
      }
      require(movement.path.origin == movement.origin && movement.path.destination == movement.destination) {
        "Um movimento possui origem ou destino incompatível com seu caminho."
      }
      require(movement.path.connections.all { connection ->
        snapshot.graph.connections.any { it == connection }
      }) {
        "Um movimento referencia uma conexão inexistente no snapshot do grafo."
      }
      require(movement.startInstant <= snapshot.currentInstant && movement.completionInstant > snapshot.currentInstant) {
        "Um movimento ativo não é compatível com o instante atual do snapshot."
      }
      val currentLocation = snapshot.entityLocations[movement.entityId]
      require(currentLocation == null || currentLocation == movement.origin) {
        "A localização da entidade em movimento deve ser nula ou igual à origem."
      }
    }
  }

  private data class AdvanceResult(
    val processedEvents: List<WorldScheduledEvent>, val completedMovements: List<WorldMovement>
  )

  companion object {
    fun restore(snapshot: WorldSnapshot, calendar: WorldCalendar? = snapshot.calendar): World {
      val world = World(snapshot.id, calendar)
      world.validateWorldSnapshot(snapshot)
      world.isRestoring = true

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
  init {
    if (connections.isEmpty()) {
      require(origin == destination) {
        "Um caminho sem conexões deve ter a mesma origem e destino."
      }
    } else {
      require(connections.first().from == origin) {
        "A primeira conexão do caminho deve partir da origem."
      }
      require(connections.last().to == destination) {
        "A última conexão do caminho deve chegar ao destino."
      }
      connections.zipWithNext().forEach { (current, next) ->
        require(current.to == next.from) {
          "As conexões consecutivas do caminho devem ser conectadas."
        }
      }
    }
  }

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
        require(!cost.isNaN() && cost >= 0.0) { "O custo de navegação deve ser um número não negativo." }

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
      "A heurística deve ser um número não negativo."
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
        require(!cost.isNaN() && cost >= 0.0) { "O custo de navegação deve ser um número não negativo." }

        val next = connection.to
        val tentativeGScore = currentGScore + cost
        if (tentativeGScore < gScore.getValue(next)) {
          val nextHeuristic = heuristic.estimate(next, to)
          require(!nextHeuristic.isNaN() && nextHeuristic >= 0.0) {
            "A heurística deve ser um número não negativo."
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

private data class QueueEntry(
  val location: WorldLocationId, val gScore: Double, val score: Double
)

private fun reconstructPath(
  from: WorldLocationId, to: WorldLocationId, previousConnection: Map<WorldLocationId, WorldConnection>
): WorldPath {
  val pathConnections = mutableListOf<WorldConnection>()
  var current = to
  while (current != from) {
    val connection = previousConnection[current] ?: error("Não foi possível reconstruir o caminho encontrado.")
    pathConnections.add(connection)
    current = connection.from
  }
  pathConnections.reverse()
  return WorldPath(from, to, pathConnections)
}

data class WorldGraphSnapshot(
  val locations: List<WorldLocation>, val connections: List<WorldConnection>
)

class WorldGraph {
  internal var eventPublisher: ((String, Any?, WorldEntityId?) -> Unit)? = null
  internal var locationRemovalHandler: ((WorldLocationId) -> Unit)? = null
  internal var connectionRemovalHandler: ((WorldConnectionId) -> Unit)? = null
  internal var restoreValidator: ((WorldGraphSnapshot) -> Unit)? = null

  private val locations = mutableMapOf<WorldLocationId, WorldLocation>()
  private val connections = mutableMapOf<WorldConnectionId, WorldConnection>()
  private val outgoingConnections = mutableMapOf<WorldLocationId, MutableList<WorldConnection>>()

  fun registerLocation(location: WorldLocation) {
    require(location.id !in locations) { "A localização '${location.id.value}' já está registada." }
    locations[location.id] = location
    eventPublisher?.invoke("LocationRegistered", location, null)
  }

  fun removeLocation(id: WorldLocationId) {
    if (id !in locations) return
    locationRemovalHandler?.invoke(id)

    connections.values.filter { it.from == id || it.to == id }.sortedBy { it.id.value }.map { it.id }
      .forEach(::removeConnection)

    locations.remove(id)
    eventPublisher?.invoke("LocationRemoved", id, null)
  }

  fun registerConnection(connection: WorldConnection) {
    require(connection.id !in connections) { "A conexão '${connection.id.value}' já está registada." }
    require(locations.containsKey(connection.from)) {
      "A localização de origem '${connection.from.value}' deve estar registada no grafo."
    }
    require(locations.containsKey(connection.to)) {
      "A localização de destino '${connection.to.value}' deve estar registada no grafo."
    }
    connections[connection.id] = connection
    outgoingConnections.getOrPut(connection.from) { mutableListOf() }.add(connection)
    eventPublisher?.invoke("ConnectionRegistered", connection, null)
  }

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

  fun getLocation(id: WorldLocationId): WorldLocation? {
    return locations[id]
  }

  fun getConnection(id: WorldConnectionId): WorldConnection? {
    return connections[id]
  }

  fun getOutgoingConnections(locationId: WorldLocationId): List<WorldConnection> {
    return outgoingConnections[locationId]?.sortedBy { it.id.value }?.toList() ?: emptyList()
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
    return WorldGraphSnapshot(
      locations = locations.values.sortedBy { it.id.value }.map { it.copy() },
      connections = connections.values.sortedBy { it.id.value }.map { it.copy() })
  }

  fun restore(snapshot: WorldGraphSnapshot) {
    restoreValidator?.invoke(snapshot)

    val locationIds = snapshot.locations.map { it.id }
    require(locationIds.distinct().size == locationIds.size) {
      "O snapshot do grafo possui localidades duplicadas."
    }
    val connectionIds = snapshot.connections.map { it.id }
    require(connectionIds.distinct().size == connectionIds.size) {
      "O snapshot do grafo possui conexões duplicadas."
    }
    val locationSet = locationIds.toSet()
    snapshot.connections.forEach { connection ->
      require(connection.from in locationSet && connection.to in locationSet) {
        "A conexão '${connection.id.value}' referencia uma localização inexistente."
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

class WorldState {
  internal var eventPublisher: ((String, Any?, WorldEntityId?) -> Unit)? = null
  internal var entityValidator: ((WorldEntityId) -> Boolean)? = null
  internal var locationValidator: ((WorldLocationId) -> Boolean)? = null
  internal var locationChangeValidator: ((WorldEntityId, WorldLocationId) -> Unit)? = null

  private val entityLocations = mutableMapOf<WorldEntityId, WorldLocationId>()

  fun setLocation(entityId: WorldEntityId, locationId: WorldLocationId) {
    require(entityValidator?.invoke(entityId) != false) {
      "A entidade '${entityId.value}' deve existir para receber uma localização."
    }
    require(locationValidator?.invoke(locationId) != false) {
      "A localização '${locationId.value}' deve existir para ser atribuída."
    }
    locationChangeValidator?.invoke(entityId, locationId)

    if (entityLocations[entityId] == locationId) return
    entityLocations[entityId] = locationId
    eventPublisher?.invoke(
      "LocationChanged", mapOf("entityId" to entityId, "locationId" to locationId), entityId
    )
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
    return entityLocations.entries.sortedBy { it.key.value }.associate { it.key to it.value }
  }

  fun restore(snapshot: Map<WorldEntityId, WorldLocationId>) {
    snapshot.forEach { (entityId, locationId) ->
      require(entityValidator?.invoke(entityId) != false) {
        "A entidade '${entityId.value}' do estado restaurado não existe."
      }
      require(locationValidator?.invoke(locationId) != false) {
        "A localização '${locationId.value}' do estado restaurado não existe."
      }
    }

    entityLocations.clear()
    entityLocations.putAll(snapshot)
  }
}

data class WorldGroupSnapshot(
  val id: WorldGroupId, val locations: Set<WorldLocationId>
)

class WorldGroup(val id: WorldGroupId) {
  internal var eventPublisher: ((String, Any?, WorldEntityId?) -> Unit)? = null
  internal var locationValidator: ((WorldLocationId) -> Boolean)? = null

  private val locations = mutableSetOf<WorldLocationId>()

  fun addLocation(locationId: WorldLocationId) {
    require(locationValidator?.invoke(locationId) != false) {
      "A localização '${locationId.value}' deve existir para ser adicionada ao grupo."
    }
    if (locations.add(locationId)) {
      eventPublisher?.invoke(
        "GroupLocationAdded", mapOf("groupId" to id, "locationId" to locationId), null
      )
    }
  }

  fun removeLocation(locationId: WorldLocationId) {
    if (locations.remove(locationId)) {
      eventPublisher?.invoke(
        "GroupLocationRemoved", mapOf("groupId" to id, "locationId" to locationId), null
      )
    }
  }

  fun hasLocation(locationId: WorldLocationId): Boolean {
    return locationId in locations
  }

  fun getLocations(): Set<WorldLocationId> {
    return locations.toSet()
  }

  fun snapshot(): WorldGroupSnapshot {
    return WorldGroupSnapshot(id, locations.toSet())
  }

  fun restore(snapshot: WorldGroupSnapshot) {
    snapshot.locations.forEach { locationId ->
      require(locationValidator?.invoke(locationId) != false) {
        "A localização '${locationId.value}' deve existir para ser restaurada no grupo."
      }
    }
    locations.clear()
    locations.addAll(snapshot.locations)
  }
}

@JvmInline
value class WorldDuration(val value: Long) : Comparable<WorldDuration> {
  override fun compareTo(other: WorldDuration): Int {
    return value.compareTo(other.value)
  }
}

@JvmInline
value class WorldInstant(val value: Long) : Comparable<WorldInstant> {
  override fun compareTo(other: WorldInstant): Int {
    return value.compareTo(other.value)
  }

  operator fun minus(other: WorldInstant): WorldDuration {
    return WorldDuration(value - other.value)
  }

  operator fun plus(duration: WorldDuration): WorldInstant {
    return WorldInstant(value + duration.value)
  }

  operator fun minus(duration: WorldDuration): WorldInstant {
    return WorldInstant(value - duration.value)
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
    require(duration.value >= 0L) { "O relógio não pode retroceder através de advance()." }
    if (duration.value == 0L) return
    currentInstant += duration
    eventPublisher?.invoke("ClockAdvanced", duration, null)
  }

  fun restore(instant: WorldInstant) {
    currentInstant = instant
  }
}

interface WorldCalendar {
  fun toDate(instant: WorldInstant): CalendarDate
}

data class WorldRecurrence(
  val id: WorldRecurrenceId, val interval: WorldDuration
) {
  init {
    require(interval.value > 0L) { "O intervalo da recorrência deve ser positivo." }
  }
}

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

data class WorldSchedulerSnapshot(
  val events: List<WorldScheduledEvent>, val recurrences: List<WorldRecurrence>
)

class WorldScheduler {
  internal var eventPublisher: ((String, Any?, WorldEntityId?) -> Unit)? = null
  internal var currentInstantProvider: (() -> WorldInstant)? = null

  private val events = mutableMapOf<WorldScheduledEventId, WorldScheduledEvent>()
  private val recurrences = mutableMapOf<WorldRecurrenceId, WorldRecurrence>()

  fun schedule(event: WorldScheduledEvent) {
    require(event.id !in events) { "O acontecimento '${event.id.value}' já está agendado." }
    currentInstantProvider?.invoke()?.let { currentInstant ->
      require(event.instant >= currentInstant) {
        "Um acontecimento não pode ser agendado no passado."
      }
    }
    event.recurrenceId?.let { recurrenceId ->
      require(recurrenceId in recurrences) {
        "A recorrência '${recurrenceId.value}' deve existir antes de ser referenciada."
      }
    }
    events[event.id] = event
    eventPublisher?.invoke("EventScheduled", event, null)
  }

  fun cancel(eventId: WorldScheduledEventId) {
    if (events.remove(eventId) != null) {
      eventPublisher?.invoke("EventCanceled", eventId, null)
    }
  }

  fun defineRecurrence(recurrence: WorldRecurrence) {
    require(recurrence.id !in recurrences) { "A recorrência '${recurrence.id.value}' já está definida." }
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

  internal fun hasEventsAtOrBefore(instant: WorldInstant): Boolean {
    return events.values.any { it.instant <= instant }
  }

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

  fun snapshot(): WorldSchedulerSnapshot {
    return WorldSchedulerSnapshot(
      events = events.values.sorted().map { it.copy() },
      recurrences = recurrences.values.sortedBy { it.id.value }.map { it.copy() })
  }

  fun restore(snapshot: WorldSchedulerSnapshot) {
    val eventIds = snapshot.events.map { it.id }
    val recurrenceIds = snapshot.recurrences.map { it.id }
    require(eventIds.distinct().size == eventIds.size) { "O snapshot possui acontecimentos agendados duplicados." }
    require(recurrenceIds.distinct().size == recurrenceIds.size) { "O snapshot possui recorrências duplicadas." }
    require(snapshot.recurrences.all { it.interval.value > 0L }) {
      "As recorrências do snapshot devem possuir intervalos positivos."
    }

    val recurrenceSet = recurrenceIds.toSet()
    require(snapshot.events.all { it.recurrenceId == null || it.recurrenceId in recurrenceSet }) {
      "Um acontecimento agendado referencia uma recorrência inexistente."
    }
    currentInstantProvider?.invoke()?.let { currentInstant ->
      require(snapshot.events.all { it.instant >= currentInstant }) {
        "O snapshot possui acontecimentos agendados no passado."
      }
    }

    events.clear()
    recurrences.clear()
    snapshot.recurrences.forEach { recurrences[it.id] = it.copy() }
    snapshot.events.forEach { events[it.id] = it.copy() }
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
    require(duration.value > 0L) { "A duração do movimento deve ser positiva." }
    require(state == WorldMovementState.IN_PROGRESS || progress >= 1.0 || state == WorldMovementState.INTERRUPTED) {
      "Um movimento concluído deve possuir progresso total."
    }
  }

  val completionInstant: WorldInstant
    get() = startInstant + duration

  val isInProgress: Boolean
    get() = state == WorldMovementState.IN_PROGRESS

  val isCompleted: Boolean
    get() = state == WorldMovementState.COMPLETED

  val isInterrupted: Boolean
    get() = state == WorldMovementState.INTERRUPTED

  fun progressAt(currentInstant: WorldInstant): Double {
    if (state == WorldMovementState.INTERRUPTED) return progress
    if (currentInstant <= startInstant) return 0.0
    if (currentInstant >= completionInstant) return 1.0

    val elapsed = (currentInstant - startInstant).value.toDouble()
    val total = duration.value.toDouble()
    return (elapsed / total).coerceIn(0.0, 1.0)
  }

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