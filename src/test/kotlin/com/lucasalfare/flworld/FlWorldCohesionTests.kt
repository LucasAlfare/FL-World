@file:Suppress("unused")

package com.lucasalfare.flworld

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cohesion and functionality tests for the public API of FL-World.
 *
 * Each test explicitly describes the scenario and which parts of the API
 * are expected to collaborate. The goal is to verify that the methods
 * actually work together consistently (space + state + time + movement +
 * events + snapshot), not only in isolation.
 */
class FlWorldCohesionTests {

  // ------------------------------------------------------------------
  // Creation helpers (keep tests readable and explicit)
  // ------------------------------------------------------------------

  private fun id(value: String) = WorldId(value)
  private fun entityId(value: String) = WorldEntityId(value)
  private fun locId(value: String) = WorldLocationId(value)
  private fun connId(value: String) = WorldConnectionId(value)
  private fun groupId(value: String) = WorldGroupId(value)
  private fun eventId(value: String) = WorldScheduledEventId(value)

  @Suppress("SameParameterValue")
  private fun recurrenceId(value: String) = WorldRecurrenceId(value)

  private fun instant(value: Long) = WorldInstant(value)
  private fun duration(value: Long) = WorldDuration(value)

  private fun location(value: String) = WorldLocation(locId(value))
  private fun connection(id: String, from: String, to: String) = WorldConnection(connId(id), locId(from), locId(to))

  private fun entity(value: String, propagate: Boolean = false) =
    WorldEntity(entityId(value), isEventPropagationEnabled = propagate)

  /** Collects events so we can verify collaboration with the observation system. */
  private class EventCollector : WorldObserver {
    val events = mutableListOf<WorldEvent>()
    override fun onEvent(event: WorldEvent) {
      events.add(event)
    }

    fun types() = events.map { it.type }
    fun clear() = events.clear()
  }

  // ------------------------------------------------------------------
  // 1. Entity lifecycle + spatial state (basic cohesion)
  // ------------------------------------------------------------------

  @Test
  fun `entity registration, location assignment and removal keep World, State and Graph consistent`() {
    // Scenario: an entity exists independently of having a location.
    // APIs involved: World.registerEntity / hasEntity / getEntity,
    //                WorldState.setLocation / getLocation / removeLocation,
    //                World.removeEntity (must clean state and any movements).

    val world = World(id("w1"))
    val e1 = entity("hero")
    val locA = location("A")

    world.graph.registerLocation(locA)
    world.registerEntity(e1)

    assertTrue(world.hasEntity(e1.id))
    assertNull(world.state.getLocation(e1.id), "Newly registered entity still has no location")

    world.state.setLocation(e1.id, locA.id)
    assertEquals(locA.id, world.state.getLocation(e1.id))

    // Removing the entity must also clear its location
    world.removeEntity(e1.id)
    assertFalse(world.hasEntity(e1.id))
    assertNull(world.state.getLocation(e1.id), "Entity removal must also remove its location from State")
  }

  // ------------------------------------------------------------------
  // 2. Graph + Pathfinding + Access (coherent navigation)
  // ------------------------------------------------------------------

  @Test
  fun `pathfinding respects graph connectivity and external access evaluator`() {
    // Scenario: A → B → C, with one connection blocked by an external rule.
    // APIs: WorldGraph.registerLocation/Connection, findPath, isReachable,
    //       WorldAccessEvaluator, UnweightedPathFinder.

    val world = World(id("w1"))
    val graph = world.graph

    listOf("A", "B", "C").forEach { graph.registerLocation(location(it)) }
    graph.registerConnection(connection("ab", "A", "B"))
    graph.registerConnection(connection("bc", "B", "C"))
    // extra connection that will be blocked
    graph.registerConnection(connection("ac", "A", "C"))

    // Without restriction: a path exists
    val freePath = graph.findPath(locId("A"), locId("C"))
    assertNotNull(freePath)
    assertEquals(locId("A"), freePath.origin)
    assertEquals(locId("C"), freePath.destination)

    // With evaluator that blocks the direct A→C connection
    val blocker = WorldAccessEvaluator { connection, _ ->
      connection.id != connId("ac")
    }
    val restrictedPath = graph.findPath(
      from = locId("A"), to = locId("C"), accessEvaluator = blocker
    )
    assertNotNull(restrictedPath)
    // Must have gone through B (two connections)
    assertEquals(2, restrictedPath.connections.size)
    assertTrue(restrictedPath.locations.contains(locId("B")))

    // Reachability also respects the evaluator
    assertTrue(graph.isReachable(locId("A"), locId("C"), accessEvaluator = blocker))
    // Blocking B→C as well makes C unreachable
    val fullBlocker = WorldAccessEvaluator { connection, _ ->
      connection.id != connId("ac") && connection.id != connId("bc")
    }
    assertFalse(graph.isReachable(locId("A"), locId("C"), accessEvaluator = fullBlocker))
  }

  // ------------------------------------------------------------------
  // 3. Movement + Time + State (main cohesion pipeline)
  // ------------------------------------------------------------------

  @Test
  fun `started movement advanced by the clock and completed updates location and emits events`() {
    // Full displacement scenario:
    // 1. Build graph and place entity at origin
    // 2. Create path and movement
    // 3. startMovement
    // 4. advance / step process time
    // 5. On completion, State receives the new location
    // 6. Observers receive MovementStarted → (MovementProgressed) → MovementCompleted

    val world = World(id("w1"))
    val collector = EventCollector()
    world.addObserver(collector)

    val e1 = entity("hero")
    world.registerEntity(e1)

    listOf("A", "B").forEach { world.graph.registerLocation(location(it)) }
    world.graph.registerConnection(connection("ab", "A", "B"))

    world.state.setLocation(e1.id, locId("A"))

    val path = world.graph.findPath(locId("A"), locId("B"))!!
    val start = world.clock.currentInstant // 0
    val moveDuration = duration(10)

    val movement = WorldMovement(
      entityId = e1.id,
      origin = locId("A"),
      destination = locId("B"),
      path = path,
      startInstant = start,
      duration = moveDuration
    )

    world.startMovement(movement)
    assertEquals(1, world.getActiveMovements().size)
    assertEquals(locId("A"), world.state.getLocation(e1.id), "While moving, the stable location remains the origin")

    // Partial advance
    world.advance(duration(4))
    val mid = world.getMovement(e1.id)
    assertNotNull(mid)
    assertTrue(mid.progress > 0.0 && mid.progress < 1.0)
    assertTrue(mid.isInProgress)
    assertEquals(locId("A"), world.state.getLocation(e1.id))

    // Complete the movement
    world.advance(duration(6))
    assertNull(world.getMovement(e1.id), "Completed movement is removed from active movements")
    assertEquals(locId("B"), world.state.getLocation(e1.id), "Movement completion must update State")

    // Expected events (approximate order)
    val types = collector.types()
    assertTrue(types.contains("MovementStarted"))
    assertTrue(types.contains("MovementCompleted"))
    // LocationChanged may appear both on the initial set and on completion
    assertTrue(types.contains("LocationChanged") || types.contains("MovementCompleted"))
  }

  @Test
  fun `step advances exactly to the next relevant event (movement or scheduled)`() {
    // Verifies that step() and advance() correctly collaborate with Clock, Scheduler and Movements.
    // step() must jump to the instant of the next known occurrence.

    val world = World(id("w1"))
    val e1 = entity("hero")
    world.registerEntity(e1)

    listOf("A", "B").forEach { world.graph.registerLocation(location(it)) }
    world.graph.registerConnection(connection("ab", "A", "B"))
    world.state.setLocation(e1.id, locId("A"))

    val path = WorldPath(locId("A"), locId("B"), listOf(connection("ab", "A", "B")))
    val movement = WorldMovement(
      entityId = e1.id,
      origin = locId("A"),
      destination = locId("B"),
      path = path,
      startInstant = instant(0),
      duration = duration(20)
    )
    world.startMovement(movement)

    // Also schedule an event at instant 10
    world.scheduler.schedule(
      WorldScheduledEvent(
        id = eventId("ev1"), instant = instant(10), type = "Something"
      )
    )

    // step() should go to 10 (event) and process it, without completing the movement yet
    val step1 = world.step()
    assertTrue(step1.advanced)
    assertEquals(instant(10), step1.currentInstant)
    assertEquals(1, step1.processedEvents.size)
    assertEquals("Something", step1.processedEvents.first().type)
    assertTrue(world.getMovement(e1.id)!!.isInProgress)

    // Next step should go to 20 (movement completion)
    val step2 = world.step()
    assertTrue(step2.advanced)
    assertEquals(instant(20), step2.currentInstant)
    assertEquals(1, step2.completedMovements.size)
    assertNull(world.getMovement(e1.id))
    assertEquals(locId("B"), world.state.getLocation(e1.id))
  }

  // ------------------------------------------------------------------
  // 4. Scheduler + Recurrence + advance/step
  // ------------------------------------------------------------------

  @Test
  fun `scheduled and recurring events are processed in order and re-scheduled correctly`() {
    val world = World(id("w1"))
    val collector = EventCollector()
    world.addObserver(collector)

    val recId = recurrenceId("every5")
    world.scheduler.defineRecurrence(WorldRecurrence(recId, duration(5)))

    world.scheduler.schedule(
      WorldScheduledEvent(
        id = eventId("tick"), instant = instant(5), type = "Tick", recurrenceId = recId
      )
    )

    // Advance to 12 → should process the ones at 5 and 10
    val processed = world.advance(duration(12))
    assertEquals(2, processed.size)
    assertEquals(instant(5), processed[0].instant)
    assertEquals(instant(10), processed[1].instant)

    // The next one (15) must still be scheduled
    val future = world.scheduler.getFutureEvents(world.clock.currentInstant)
    assertEquals(1, future.size)
    assertEquals(instant(15), future[0].instant)
  }

  // ------------------------------------------------------------------
  // 5. Snapshot + Restore + continuation of the simulation
  // ------------------------------------------------------------------

  @Test
  fun `snapshot followed by restore produces an equivalent world that continues the simulation correctly`() {
    // Maximum cohesion: all parts (entities, locations, movements,
    // scheduler, clock, groups) must be captured and restored so that
    // the simulation can continue without divergence.

    val original = World(id("w1"))
    val e1 = entity("hero", propagate = true)
    original.registerEntity(e1)

    listOf("A", "B", "C").forEach { original.graph.registerLocation(location(it)) }
    original.graph.registerConnection(connection("ab", "A", "B"))
    original.graph.registerConnection(connection("bc", "B", "C"))
    original.state.setLocation(e1.id, locId("A"))

    val group = WorldGroup(groupId("region1"))
    group.addLocation(locId("A"))
    group.addLocation(locId("B"))
    original.registerGroup(group)

    // Movement in progress
    val path = original.graph.findPath(locId("A"), locId("B"))!!
    original.startMovement(
      WorldMovement(
        entityId = e1.id,
        origin = locId("A"),
        destination = locId("B"),
        path = path,
        startInstant = instant(0),
        duration = duration(10)
      )
    )

    // Future event
    original.scheduler.schedule(
      WorldScheduledEvent(eventId("future"), instant(15), "FutureEvent")
    )

    // Advance a little
    original.advance(duration(3))
    assertEquals(instant(3), original.clock.currentInstant)
    assertTrue(original.getMovement(e1.id)!!.progress > 0.0)

    // Snapshot
    val snap = original.snapshot()

    // Restore into a new World
    val restored = World.restore(snap)

    // Equivalent state
    assertEquals(original.id, restored.id)
    assertEquals(original.clock.currentInstant, restored.clock.currentInstant)
    assertTrue(restored.hasEntity(e1.id))
    assertEquals(locId("A"), restored.state.getLocation(e1.id))
    assertNotNull(restored.getMovement(e1.id))
    assertEquals(1, restored.getActiveMovements().size)
    assertNotNull(restored.getGroup(groupId("region1")))
    assertEquals(1, restored.scheduler.getFutureEvents(restored.clock.currentInstant).size)

    // Continuation must work
    restored.advance(duration(7)) // completes the movement (7 remaining)
    assertNull(restored.getMovement(e1.id))
    assertEquals(locId("B"), restored.state.getLocation(e1.id))
    assertEquals(instant(10), restored.clock.currentInstant)

    // The future event is still there
    val stillFuture = restored.scheduler.getFutureEvents(restored.clock.currentInstant)
    assertEquals(1, stillFuture.size)
    assertEquals(instant(15), stillFuture[0].instant)
  }

  // ------------------------------------------------------------------
  // 6. Cascading removals (location / connection)
  // ------------------------------------------------------------------

  @Test
  fun `location removal interrupts movements, clears entity state and groups`() {
    val world = World(id("w1"))
    val e1 = entity("hero")
    world.registerEntity(e1)

    listOf("A", "B").forEach { world.graph.registerLocation(location(it)) }
    world.graph.registerConnection(connection("ab", "A", "B"))
    world.state.setLocation(e1.id, locId("A"))

    val path = WorldPath(locId("A"), locId("B"), listOf(connection("ab", "A", "B")))
    world.startMovement(
      WorldMovement(
        entityId = e1.id,
        origin = locId("A"),
        destination = locId("B"),
        path = path,
        startInstant = instant(0),
        duration = duration(10)
      )
    )

    val group = WorldGroup(groupId("g1"))
    group.addLocation(locId("A"))
    world.registerGroup(group)

    // Remove the origin location
    world.graph.removeLocation(locId("A"))

    // Movement must have been interrupted
    assertNull(world.getMovement(e1.id))
    // Entity location cleared from state
    assertNull(world.state.getLocation(e1.id))
    // Group lost the location
    assertFalse(world.getGroup(groupId("g1"))!!.hasLocation(locId("A")))
    // Location no longer exists in the graph
    assertNull(world.graph.getLocation(locId("A")))
  }

  @Test
  fun `connection removal interrupts movements that use it`() {
    val world = World(id("w1"))
    val e1 = entity("hero")
    world.registerEntity(e1)

    listOf("A", "B").forEach { world.graph.registerLocation(location(it)) }
    val conn = connection("ab", "A", "B")
    world.graph.registerConnection(conn)
    world.state.setLocation(e1.id, locId("A"))

    val path = WorldPath(locId("A"), locId("B"), listOf(conn))
    world.startMovement(
      WorldMovement(
        entityId = e1.id,
        origin = locId("A"),
        destination = locId("B"),
        path = path,
        startInstant = instant(0),
        duration = duration(10)
      )
    )

    world.graph.removeConnection(connId("ab"))
    assertNull(world.getMovement(e1.id), "Movement that uses the removed connection must be interrupted")
  }

  // ------------------------------------------------------------------
  // 7. Observation system and entity-originated events
  // ------------------------------------------------------------------

  @Test
  fun `observers receive library transition events and entity events when propagation is enabled`() {
    val world = World(id("w1"))
    val collector = EventCollector()
    world.addObserver(collector)

    val e1 = entity("hero") // propagation off by default
    world.registerEntity(e1)
    assertTrue(collector.types().contains("EntityRegistered"))

    // Publishing with propagation disabled does not generate an event
    world.publishEntityEvent(e1.id, "Attack", "sword")
    assertFalse(collector.types().contains("Attack"))

    // Enable and publish
    world.enableEntityEventPropagation(e1.id)
    world.publishEntityEvent(e1.id, "Attack", "sword")
    val attackEvent = collector.events.last { it.type == "Attack" }
    assertEquals(e1.id, attackEvent.sourceId)
    assertEquals("sword", attackEvent.data)
    assertNotNull(attackEvent.snapshot)

    // Event snapshot must reflect the state at that moment (entity exists)
    assertTrue(attackEvent.snapshot.entities.any { it.id == e1.id })
  }

  @Test
  fun `removeObserver prevents the observer from receiving further events`() {
    val world = World(id("w1"))
    val collector = EventCollector()
    world.addObserver(collector)

    world.registerEntity(entity("e1"))
    assertTrue(collector.events.isNotEmpty())

    world.removeObserver(collector)
    collector.clear()

    world.registerEntity(entity("e2"))
    assertTrue(collector.events.isEmpty(), "After removeObserver no events should arrive")
  }

  // ------------------------------------------------------------------
  // 8. Invariants and defensive collaboration between APIs
  // ------------------------------------------------------------------

  @Test
  fun `startMovement rejects paths or states inconsistent with the graph and current state`() {
    val world = World(id("w1"))
    val e1 = entity("hero")
    world.registerEntity(e1)

    listOf("A", "B").forEach { world.graph.registerLocation(location(it)) }
    world.graph.registerConnection(connection("ab", "A", "B"))

    val path = WorldPath(locId("A"), locId("B"), listOf(connection("ab", "A", "B")))

    // WorldPath itself only validates structural consistency of the connections.
    // Rejection of non-existent locations/connections happens inside startMovement.
    val pathToUnknown = WorldPath(
      locId("A"), locId("Z"), listOf(connection("az", "A", "Z"))
    )
    assertFailsWith<IllegalArgumentException> {
      world.startMovement(
        WorldMovement(
          entityId = e1.id,
          origin = locId("A"),
          destination = locId("Z"),
          path = pathToUnknown,
          startInstant = instant(0),
          duration = duration(5)
        )
      )
    }

    // Movement whose origin does not match the entity's current location
    world.state.setLocation(e1.id, locId("B")) // entity is at B
    assertFailsWith<IllegalArgumentException> {
      world.startMovement(
        WorldMovement(
          entityId = e1.id, origin = locId("A"), // origin different from current location
          destination = locId("B"), path = path, startInstant = instant(0), duration = duration(5)
        )
      )
    }
  }

  @Test
  fun `Dijkstra and AStar find lowest-cost paths when cost is supplied externally`() {
    // Cohesion with the cost abstraction (stage 11): the library does not know
    // the meaning of the cost; it only uses the provided function.

    val world = World(id("w1"))
    listOf("A", "B", "C").forEach { world.graph.registerLocation(location(it)) }
    // A→B cost 10, A→C cost 1, C→B cost 1  → cheapest path is A-C-B
    world.graph.registerConnection(connection("ab", "A", "B"))
    world.graph.registerConnection(connection("ac", "A", "C"))
    world.graph.registerConnection(connection("cb", "C", "B"))

    val cost = WorldNavigationCost { connection, _ ->
      when (connection.id.value) {
        "ab" -> 10.0
        "ac" -> 1.0
        "cb" -> 1.0
        else -> 1.0
      }
    }

    val dijkstra = DijkstraPathFinder(cost)
    val pathD = world.graph.findPath(locId("A"), locId("B"), pathFinder = dijkstra)!!
    assertEquals(2, pathD.connections.size)
    assertTrue(pathD.locations.contains(locId("C")))

    val heuristic = WorldHeuristic { _, _ -> 0.0 } // zero heuristic = Dijkstra
    val astar = AStarPathFinder(cost, heuristic)
    val pathA = world.graph.findPath(locId("A"), locId("B"), pathFinder = astar)!!
    assertEquals(2, pathA.connections.size)
    assertTrue(pathA.locations.contains(locId("C")))
  }

  // ------------------------------------------------------------------
  // 9. Groups and interaction with the rest of the world
  // ------------------------------------------------------------------

  @Test
  fun `groups register valid locations and are cleaned when locations are removed`() {
    val world = World(id("w1"))
    listOf("A", "B").forEach { world.graph.registerLocation(location(it)) }

    val group = WorldGroup(groupId("forest"))
    group.addLocation(locId("A"))
    group.addLocation(locId("B"))
    world.registerGroup(group)

    assertTrue(world.getGroup(groupId("forest"))!!.hasLocation(locId("A")))

    // Location removal propagates to the group
    world.graph.removeLocation(locId("A"))
    assertFalse(world.getGroup(groupId("forest"))!!.hasLocation(locId("A")))
    assertTrue(world.getGroup(groupId("forest"))!!.hasLocation(locId("B")))
  }

  // ------------------------------------------------------------------
  // 10. Basic determinism of advance and step
  // ------------------------------------------------------------------

  @Test
  fun `two identical worlds advanced the same way produce the same final state`() {
    fun buildWorld(): World {
      val w = World(id("det"))
      val e = entity("e1")
      w.registerEntity(e)
      listOf("A", "B").forEach { w.graph.registerLocation(location(it)) }
      w.graph.registerConnection(connection("ab", "A", "B"))
      w.state.setLocation(e.id, locId("A"))
      val path = w.graph.findPath(locId("A"), locId("B"))!!
      w.startMovement(
        WorldMovement(
          entityId = e.id,
          origin = locId("A"),
          destination = locId("B"),
          path = path,
          startInstant = instant(0),
          duration = duration(8)
        )
      )
      w.scheduler.schedule(WorldScheduledEvent(eventId("ev"), instant(4), "Mid"))
      return w
    }

    val w1 = buildWorld()
    val w2 = buildWorld()

    w1.advance(duration(10))
    w2.advance(duration(10))

    assertEquals(w1.clock.currentInstant, w2.clock.currentInstant)
    assertEquals(w1.state.getLocation(entityId("e1")), w2.state.getLocation(entityId("e1")))
    assertEquals(w1.getActiveMovements().size, w2.getActiveMovements().size)
    assertEquals(
      w1.scheduler.getFutureEvents(w1.clock.currentInstant).size,
      w2.scheduler.getFutureEvents(w2.clock.currentInstant).size
    )
  }
}
