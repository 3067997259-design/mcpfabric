package dev.mcpfabric.client.flight;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route-following and instrumentation regressions (ab-23 repair plan, B5).
 *
 * The candidate is a feedback policy now: the reference follows the route
 * progress, so passing a waypoint changes the aim instead of continuing the
 * old dive, and a turn is followed instead of the straight overshoot. The
 * bounded search reports what it did (evaluated, physics steps, block queries,
 * cache hits) so a budget shortfall can be attributed.
 */
class FlightSessionRoutePolicyTest {

	private static FlightSession session(List<FlightSession.Waypoint> path, FlightSession.BlockQuery blocks,
			FlightSession.Params params) {
		return new FlightSession(path, blocks, System.currentTimeMillis() + 60_000, params, 5_000);
	}

	@Test
	void passingAWaypointReplacesTheDiveWithTheNextAim() {
		// Descend to the middle waypoint, then the route is level. The state
		// aims 27 degrees down at the middle; a frozen-attitude prediction
		// would keep diving to the end of the horizon.
		FlightSession session = session(
				List.of(new FlightSession.Waypoint(36, 72, 0),
						new FlightSession.Waypoint(40, 70, 0),
						new FlightSession.Waypoint(64, 70, 0)),
				(x, y, z) -> y <= 60.0 ? "minecraft:stone" : "minecraft:air",
				new FlightSession.Params());
		FlightSession.Decision decision = session.tick(36, 72, 0, 1.5, 0, 0, -90f, 0f, 0, 0, 0, false);

		assertTrue(decision.applicable, decision.note);
		assertEquals(3, decision.predictedEndCursor, "the prediction must reach the terminal: " + decision.note);
		assertTrue(decision.predictedEndY > 68.0,
				"after passing the middle the prediction must level off, not keep diving: " + decision.note);
	}

	@Test
	void aTurnIsFollowedAndTheStraightOvershootIsBlocked() {
		// The route turns 90 degrees at the corner; a wall blocks the straight
		// extension. The state is at the corner with +x velocity, so the policy
		// must turn toward +z, and the straight path must be a real obstacle.
		FlightSession.BlockQuery blocks = (x, y, z) ->
				x >= 30.0 && y >= 66.0 && y <= 74.0 && Math.abs(z) <= 10.0
						? "minecraft:stone"
						: (y <= 60.0 ? "minecraft:stone" : "minecraft:air");
		FlightSession session = session(
				List.of(new FlightSession.Waypoint(0, 70, 0),
						new FlightSession.Waypoint(20, 70, 0),
						new FlightSession.Waypoint(20, 70, 30)),
				blocks,
				new FlightSession.Params());
		FlightSession.Decision decision = session.tick(18, 70, 0, 1.5, 0, 0, -90f, 0f, 0, 0, 0, false);

		assertTrue(decision.applicable, decision.note);
		// The bearing to the new reference is yaw 0 (+z). The first step turns
		// TOWARD it by at most the shared steering rate (B2), not with a snap:
		// a multi-tick turn is the intended, smooth behavior.
		assertTrue(Math.abs(decision.yaw) < Math.abs(-90f),
				"the policy must turn toward the route, not hold the straight heading: " + decision.note);
		assertTrue(Math.abs(decision.yaw - (-90f)) <= 25.01f,
				"the first step may only turn by the steering rate: " + decision.note);
		// The wall is real: a straight glide from the same state hits it.
		FlightDynamics.State straight = new FlightDynamics.State(18, 70, 0, 1.5, 0, 0, 0);
		boolean hit = false;
		for (int tick = 0; tick < 8; tick++) {
			straight = FlightDynamics.step(straight, new FlightDynamics.Input(-90f, 0f, false));
			if (straight.x >= 29.5) {
				hit = true;
				break;
			}
		}
		assertTrue(hit, "the straight overshoot must reach the wall");
	}

	@Test
	void aLowCeilingRejectsTheClimbingPolicies() {
		FlightSession.BlockQuery ceiling = (x, y, z) ->
				y >= 73.0 ? "minecraft:stone" : (y <= 60.0 ? "minecraft:stone" : "minecraft:air");
		FlightSession session = session(
				List.of(new FlightSession.Waypoint(0, 70, 0), new FlightSession.Waypoint(200, 70, 0)),
				ceiling,
				new FlightSession.Params());
		FlightSession.Decision decision = session.tick(0, 70, 0, 1.4, 0, 0, -90f, 0f, 0, 0, 0, false);

		assertTrue(decision.applicable, decision.note);
		assertTrue(decision.rejectCollisions > 0, "the climbing policies must hit the ceiling: " + decision.note);
		assertTrue(decision.pitch >= -5f,
				"the winner must not be a climb under the ceiling: " + decision.note);
	}

	@Test
	void theTerminalIsNotCompletedEarlyByThePassedPlaneRule() {
		// The middle entry is behind the state, so the cursor advances to the
		// terminal — but the TERMINAL is only completed when the prediction
		// actually reaches it.
		FlightSession session = session(
				List.of(new FlightSession.Waypoint(0, 70, 0),
						new FlightSession.Waypoint(20, 70, 0),
						new FlightSession.Waypoint(40, 70, 0)),
				(x, y, z) -> y <= 60.0 ? "minecraft:stone" : "minecraft:air",
				new FlightSession.Params());
		FlightSession.Decision decision = session.tick(22.5, 70, 0, 1.5, 0, 0, -90f, 0f, 0, 0, 0, false);

		assertTrue(decision.applicable, decision.note);
		assertEquals(2, decision.cursor, "the passed middle advances the cursor to the terminal");
		assertEquals(3, decision.predictedEndCursor, "the terminal completes only when reached");
		assertEquals("arrived_lookahead", decision.predictedEndReason);
	}

	@Test
	void fireCandidatesFollowTheRocketAndCooldownGates() {
		FlightSession.Params params = new FlightSession.Params();
		params.maxCandidates = 64;
		params.simBudgetMs = 1_000;
		FlightSession session = session(
				List.of(new FlightSession.Waypoint(0, 70, 0), new FlightSession.Waypoint(200, 70, 0)),
				(x, y, z) -> y <= 60.0 ? "minecraft:stone" : "minecraft:air",
				params);

		// No rockets: the fire variants are not evaluated at all.
		FlightSession.Decision noRockets = session.tick(0, 70, 0, 1.4, 0, 0, -90f, 0f, 0, 0, 0, false);
		assertEquals(20, noRockets.evaluated, "5 yaw x 4 policies, no fire without rockets");

		// Rockets in hand and no active boost: the fire variants join.
		FlightSession.Decision withRockets = session.tick(0, 70, 0, 1.4, 0, 0, -90f, 0f, 3, 0, 1, false);
		assertEquals(40, withRockets.evaluated, "5 yaw x 4 policies x 2 fire variants");

		// An active boost forbids a second ignition (no stacking).
		FlightSession.Decision boosted = session.tick(0, 70, 0, 1.4, 0, 0, -90f, 0f, 3, 20, 2, false);
		assertEquals(20, boosted.evaluated, "no stacking while a boost is active");
	}

	@Test
	void theSearchReportsItsCost() {
		FlightSession session = session(
				List.of(new FlightSession.Waypoint(0, 70, 0), new FlightSession.Waypoint(200, 70, 0)),
				(x, y, z) -> y <= 60.0 ? "minecraft:stone" : "minecraft:air",
				new FlightSession.Params());
		FlightSession.Decision decision = session.tick(0, 70, 0, 1.4, 0, 0, -90f, 0f, 0, 0, 0, false);

		assertTrue(decision.evaluated > 0, decision.note);
		assertTrue(decision.physicsSteps > 0, "the simulation steps are counted: " + decision.note);
		assertTrue(decision.blockQueries > 0, "the block queries are counted: " + decision.note);
		assertTrue(decision.cacheHits >= 0);
		assertTrue(decision.simMs >= 0);
	}
}



