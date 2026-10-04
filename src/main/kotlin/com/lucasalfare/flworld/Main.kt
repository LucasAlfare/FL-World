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
}