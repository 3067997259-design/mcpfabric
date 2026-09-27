package dev.mcpfabric.client.flight;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route-policy regressions (ab-23 repair plan, B5).
 *
 * The candidate set is a bounded feedback policy now: the reference and the
 * aim update every simulated step, and the pitch set contains absolute
 * alternatives (LEVEL, PULL_UP). The old set was aim-relative only, so a state
 * whose reference sat close and below produced nothing but dives — the ab-23
 * level-water failure.
 */
class FlightSessionPolicyTest {

	/** Water fills the valley up to y=64; the surface is 65. */
	private static final FlightSession.BlockQuery FLOODED = (x, y, z) ->
			y <= 64.0 ? "minecraft:water" : "minecraft:air";

	private static FlightSession session(FlightSession.BlockQuery blocks, FlightSession.Params params) {
		// The reference is 4 blocks ahead and 7 below: the aim is ~60 degrees
		// down, the ab-23 geometry.
		return new FlightSession(
				List.of(new FlightSession.Waypoint(0, 73, 0), new FlightSession.Waypoint(4, 66, 0),
						new FlightSession.Waypoint(80, 66, 0)),
				blocks,
				System.currentTimeMillis() + 60_000,
				params,
				5_000);
	}

	@Test
	void theLevelWaterStateFindsAnUnpoweredLevelPolicy() {
		// The old candidate set: aim-relative offsets only. At this geometry
		// the aim is ~60 degrees down, so every old candidate was a dive (the
		// shallowest still 32 degrees) and none of them could hold the water
		// surface. The steep ones sink into the water inside the horizon.
		float aim = (float) (-Math.toDegrees(Math.atan2(66.0 - 73.0, 4.0)));
		for (float oldPitch : new float[] { aim, aim - 12f, aim + 12f, aim - 28f }) {
			assertTrue(oldPitch >= 30f,
					"the old aim-relative set had no level option (pitch " + oldPitch + " is a dive)");
		}
		FlightDynamics.State state = new FlightDynamics.State(0, 73, 0, 1.4, -0.2, 0, 0);
		boolean hitWater = false;
		for (int tick = 0; tick < 20; tick++) {
			state = FlightDynamics.step(state, new FlightDynamics.Input(-90f, aim, false));
			if (state.y <= 64.2) {
				hitWater = true;
				break;
			}
		}
		assertTrue(hitWater, "the aim pitch must sink into the water");

		FlightSession.Params params = new FlightSession.Params();
		params.simBudgetMs = 1_000;
		FlightSession session = session(FLOODED, params);
		FlightSession.Decision decision = session.tick(0, 73, 0, 1.4, -0.2, 0, -90f, 0f, 0, 0, 0, false);

		assertTrue(decision.applicable,
				"the policy set must find a level or pull-up path over the water: " + decision.note);
		assertTrue(decision.feasible > 0,
				"a viable policy must exist where the old aim-relative set had none: " + decision.note);
	}

	@Test
	void theWinnerDoesNotDependOnTheCandidateEnumerationOrder() {
		FlightSession.Params forward = new FlightSession.Params();
		FlightSession.Params reversed = new FlightSession.Params();
		// A generous budget: the test is about the selection order, not about a
		// budget cut that legitimately depends on how far the search got.
		forward.simBudgetMs = 1_000;
		reversed.simBudgetMs = 1_000;
		reversed.yawOffsets = new float[] { 30, -30, 15, -15, 0 };
		FlightSession.BlockQuery terrain = (x, y, z) -> y <= 60.0 ? "minecraft:stone" : "minecraft:air";

		FlightSession first = session(terrain, forward);
		FlightSession second = session(terrain, reversed);
		FlightSession.Decision a = first.tick(0, 73, 0, 1.4, -0.2, 0, 0f, 0f, 0, 0, 0, false);
		FlightSession.Decision b = second.tick(0, 73, 0, 1.4, -0.2, 0, 0f, 0f, 0, 0, 0, false);

		assertTrue(a.applicable && b.applicable);
		assertFalse(a.budgetExhausted || b.budgetExhausted, "no budget cut: " + a.note);
		assertEquals(a.yaw, b.yaw, "the same state must select the same yaw regardless of enumeration order");
		assertEquals(a.pitch, b.pitch, "the same state must select the same pitch regardless of enumeration order");
		assertEquals(a.fireRocket, b.fireRocket);
	}
}

