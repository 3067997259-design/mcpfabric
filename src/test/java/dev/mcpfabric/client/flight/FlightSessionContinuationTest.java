package dev.mcpfabric.client.flight;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Continuation-feasibility regressions (R4 client review 2026-09-21, ruling 2).
 *
 * The candidate decision order is: collision and medium, then whether the
 * prediction can keep flying (channel lower bound, surface clearance, steering
 * energy), then progress, and firework cost last. A fire candidate must win
 * when every unpowered alternative loses the ability to continue.
 */
class FlightSessionContinuationTest {
	@Test
	void ignitionUsesAnOfferedRecipeInsteadOfTheDefaultHandRecipe() {
		// ROOT CAUSE: prediction only knew the default hand's duration and
		// returned a boolean ignition. A different physical recipe could not
		// be requested or correlated with the simulated action.
		var params = new FlightSession.Params(); params.simBudgetMs = 1000;
		var pilot = new FlightSession(
				List.of(new FlightSession.Waypoint(0, 66, 0), new FlightSession.Waypoint(0, 64, 200)),
				RIVER, System.currentTimeMillis() + 60000, params);
		pilot.setRocketFlightDuration(1);
		pilot.setAvailableRocketDurations(new int[] { 3 });
		var decision = pilot.tick(0, 66, 0, .5, -.1, -.4, 180, -6, 64, 0, 0, false);
		assertTrue(decision.applicable, decision.note);
		assertTrue(decision.fireRocket, decision.note);
		org.junit.jupiter.api.Assertions.assertEquals(3, decision.rocketFlightDuration);
	}

	@Test
	void levelRouteDoesNotRewardAnUnnecessaryLateralTurn() {
		// ROOT CAUSE: distance to a future waypoint included forward travel.
		// A -15 degree turn won on a straight route with zero lateral error.
		var decision = levelTrackingDecision(0, 10);
		assertTrue(decision.applicable, decision.note);
		assertTrue(Math.abs(decision.predictedEndX) < .1, decision.chosenPolicy);
		assertFalse(decision.fireRocket);
	}

	@Test
	void smallHorizontalGainDoesNotEraseVerticalTrackingError() {
		// ROOT CAUSE: strict horizontal-first ranking chose a 1.22-block
		// climb although AIM stayed within 0.13 blocks of the level route.
		var decision = levelTrackingDecision(3, 25);
		assertTrue(decision.applicable, decision.note);
		assertTrue(Math.abs(decision.predictedEndY - 70) < .2, decision.chosenPolicy);
		assertTrue(Math.abs(decision.predictedEndX) < .1, decision.chosenPolicy);
	}

	private static FlightSession.Decision levelTrackingDecision(double x, int boost) {
		var route = new java.util.ArrayList<FlightSession.Waypoint>();
		for (int z = 0; z <= 150; z++) route.add(new FlightSession.Waypoint(0, 70, z));
		var params = new FlightSession.Params(); params.simBudgetMs = 1000;
		var pilot = new FlightSession(route, (a, b, c) -> b < 60 ? "stone" : "air",
				System.currentTimeMillis() + 60000, params);
		return pilot.tick(x, 70, 10, 0, 0, 1.5, 0, 0, 64, boost, 0, false);
	}

	/** The venue's river: water blocks up to y=62, air above. */
	private static final FlightSession.BlockQuery RIVER = (x, y, z) ->
			y <= 62 ? "minecraft:water" : "minecraft:air";

	private static FlightSession session() {
		return new FlightSession(
				List.of(new FlightSession.Waypoint(0, 66, 0), new FlightSession.Waypoint(0, 64, 200)),
				RIVER,
				System.currentTimeMillis() + 60_000,
				new FlightSession.Params());
	}

	@Test
	void firesWhenTheUnpoweredGlideWouldSinkBelowTheContinuationFloor() {
		// Just above the water, slow, already descending: every no-fire
		// trajectory ends below the surface clearance, so the driver must
		// spend a rocket even though the target is below and the speed is
		// above the old low-speed gate.
		FlightSession session = session();
		FlightSession.Decision decision = session.tick(
				0, 66, 0, 0.5, -0.1, -0.4,
				180f, -6f, 64, 0, 0, false);

		assertTrue(decision.applicable, "the session must produce a decision");
		assertTrue(decision.fireRocket, "a glide that cannot continue must fire: " + decision.note);
	}

	@Test
	void aPredictedDiveIntoWaterIsRejectedEvenWithRocketsInHand() {
		// R4 cave-ab-13 full arm: the pilot chose a dive into the river with
		// rockets unused because the sweep treated water as passable and the
		// continuation floor read the riverbed below the surface. A candidate
		// that enters the water cannot continue flying and must be rejected;
		// With bounded steering this measured state cannot turn and flatten
		// quickly enough. Refusal is safer than inventing an instant level turn.
		FlightSession.Params params = new FlightSession.Params();
		params.simBudgetMs = 1_000;
		FlightSession session = new FlightSession(
				List.of(new FlightSession.Waypoint(0, 70, 0), new FlightSession.Waypoint(0, 62, 60)),
				RIVER,
				System.currentTimeMillis() + 60_000,
				params);
		// Low and nose-down toward a submerged entry: the no-fire dives are
		// invalid, so the surviving decision must not be a further dive into
		// the water.
		FlightSession.Decision decision = session.tick(
				0, 65, 0, 0.2, -0.05, -1.0,
				0f, 25f, 64, 0, 0, false);
		assertFalse(decision.budgetExhausted, decision.note);
		assertFalse(decision.applicable, "a dive into the water must not be chosen: " + decision.note);
		assertTrue(decision.rejectCollisions > 0, "water must reject the dive: " + decision.note);
	}

	@Test
	void flyingJustAboveTheWaterIsStillValid() {
		// The water surface top is y=63; a body at y=63.6 keeps its pose box
		// out of the water and must remain flyable (the river route hugs it).
		FlightSession session = new FlightSession(
				List.of(new FlightSession.Waypoint(0, 63.6, 0), new FlightSession.Waypoint(0, 63.6, 60)),
				RIVER,
				System.currentTimeMillis() + 60_000,
				new FlightSession.Params());
		FlightSession.Decision decision = session.tick(
				0, 63.6, 0, 0, 0, -0.6,
				180f, -3f, 64, 0, 0, false);
		assertTrue(decision.applicable, "flying above the water must stay possible: " + decision.note);
	}

	@Test
	void theTerminalLookaheadSeesAnObstacleJustPastTheTerminus() {
		// ab-17 audit item 2: the terminal was reached after 1 tick and the old
		// break ended the prediction there, so the leaves hit at t1 were never
		// swept. A wall right past the terminus must now reject the candidates.
		FlightSession.BlockQuery wallPastTerminus = (x, y, z) ->
				(z >= 8 && y >= 60 && y <= 72) ? "minecraft:oak_leaves" : "minecraft:air";
		FlightSession session = new FlightSession(
				List.of(new FlightSession.Waypoint(0, 66, 0), new FlightSession.Waypoint(0, 66, 20)),
				wallPastTerminus,
				System.currentTimeMillis() + 60_000,
				new FlightSession.Params());
		// Fast and one tick from the terminus: the post-terminal lookahead must
		// sweep through the wall and reject every candidate.
		FlightSession.Decision decision = session.tick(
				0, 66, 0, 0, 0, 2.0,
				0f, 0f, 64, 0, 0, false);
		assertFalse(decision.applicable, "an obstacle past the terminus must not be invisible: " + decision.note);
		assertTrue(decision.note.contains("no_viable"), decision.note);
	}

	@Test
	void doesNotFireWhileABoostIsStillActive() {
		// No stacking: with a rocket still pushing, a second ignition would add
		// a thrust the single-countdown model cannot express (ab-17 audit 4).
		FlightSession session = new FlightSession(
				List.of(new FlightSession.Waypoint(0, 72, 0), new FlightSession.Waypoint(0, 72, 200)),
				RIVER,
				System.currentTimeMillis() + 60_000,
				new FlightSession.Params());
		// Slow and descending with plenty of rockets in hand: the old policy
		// would consider firing, but an active boost must forbid it.
		FlightSession.Decision decision = session.tick(
				0, 72, 0, 0.1, -0.2, -0.3,
				0f, -3f, 64, 20, 0, false);
		assertTrue(decision.applicable, "the session must produce a decision: " + decision.note);
		assertFalse(decision.fireRocket, "a second rocket must wait for the boost to end: " + decision.note);
	}

	@Test
	void doesNotFireWhenTheGlideIsAlsoTheBetterProgress() {
		// The entry is close and above the water clearance: the unpowered
		// glide reaches it, and a boost would not improve the progress. The
		// explicit order compares firework cost last, so the glide wins
		// (R4 review 3).
		FlightSession session = new FlightSession(
				List.of(new FlightSession.Waypoint(0, 72, 0), new FlightSession.Waypoint(0, 72, 15)),
				RIVER,
				System.currentTimeMillis() + 60_000,
				new FlightSession.Params());
		FlightSession.Decision decision = session.tick(
				0, 72, 0, 0.6, 0, 0.6,
				0f, -3f, 64, 0, 0, false);

		assertTrue(decision.applicable, "the session must produce a decision: " + decision.note);
		assertFalse(decision.fireRocket, "a cheaper candidate with >= progress must win: " + decision.note);
	}
}
