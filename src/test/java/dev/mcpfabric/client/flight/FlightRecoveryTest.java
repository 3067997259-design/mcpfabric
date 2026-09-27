package dev.mcpfabric.client.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recovery-policy regressions (ab-17 audit item 3: a {@code no_viable}
 * failure must not equal a safe end).
 *
 * The ab-20 full arm kept its heading after the failure, flew into the rising
 * river bank and took 9.97 damage; the segmented arm landed at zero damage.
 * The policy must now trade speed for height over rising terrain and arrest a
 * strong sink with one rocket, while never stacking a second rocket.
 */
class FlightRecoveryTest {
	@Test
	void flatGroundIsNotAnUphillObstacleJustBecauseThePilotIsLow() {
		assertEquals(FlightRecovery.FLARE_PITCH, FlightRecovery.decide(inputs(4, 4, -.1)).pitch);
	}

	private static FlightRecovery.Inputs inputs(double heightAbove, double clearanceAhead, double vy) {
		FlightRecovery.Inputs in = new FlightRecovery.Inputs();
		in.heightAbove = heightAbove;
		in.clearanceAhead = clearanceAhead;
		in.vy = vy;
		in.rocketAvailable = true;
		in.boostActive = false;
		return in;
	}

	@Test
	void risingTerrainAheadPitchesUp() {
		FlightRecovery.Action action = FlightRecovery.decide(inputs(20, 5, -0.1));
		assertEquals(FlightRecovery.CLIMB_PITCH, action.pitch);
		assertFalse(action.fire, "a shallow sink over a rise does not need a rocket");
	}

	@Test
	void aStrongSinkOntoRisingTerrainIsArrestedOnce() {
		FlightRecovery.Action action = FlightRecovery.decide(inputs(10, 4, -0.6));
		assertEquals(FlightRecovery.CLIMB_PITCH, action.pitch);
		assertTrue(action.fire, "a low, fast sink into a rise must be arrested");
	}

	@Test
	void anAttachedBoostIsNeverStacked() {
		FlightRecovery.Inputs in = inputs(10, 4, -0.6);
		in.boostActive = true;
		FlightRecovery.Action action = FlightRecovery.decide(in);
		assertFalse(action.fire, "no second rocket while a boost is active");
	}

	@Test
	void withoutARocketThePitchStillClimbs() {
		FlightRecovery.Inputs in = inputs(10, 4, -0.6);
		in.rocketAvailable = false;
		FlightRecovery.Action action = FlightRecovery.decide(in);
		assertEquals(FlightRecovery.CLIMB_PITCH, action.pitch);
		assertFalse(action.fire);
	}

	@Test
	void lowOverFlatGroundFlares() {
		FlightRecovery.Action action = FlightRecovery.decide(inputs(8, 30, -0.2));
		assertEquals(FlightRecovery.FLARE_PITCH, action.pitch);
		assertFalse(action.fire);
	}

	@Test
	void clearAndHighDescendsGently() {
		FlightRecovery.Action action = FlightRecovery.decide(inputs(30, 30, 0.0));
		assertEquals(FlightRecovery.DESCENT_PITCH, action.pitch);
		assertFalse(action.fire);
	}
}
