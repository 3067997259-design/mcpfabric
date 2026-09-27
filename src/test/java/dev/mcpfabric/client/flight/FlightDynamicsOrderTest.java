package dev.mcpfabric.client.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dynamics boundary-order regressions (ab-17 audit 2026-09-21).
 *
 * The live telemetry is sampled after the controller writes the tick's input.
 * On 139 boosted ticks the next recorded position matched a glide with the
 * PRE-thrust velocity; the rocket's pull reached the velocity only after that
 * displacement (single-step position error P95 0.54 -> 0.00002 blocks). These
 * tests pin the order so a future refactor cannot silently revert it.
 */
class FlightDynamicsOrderTest {

	@Test
	void theDisplacementUsesThePreThrustVelocity() {
		FlightDynamics.State state = new FlightDynamics.State(0, 100, 0, 0.4, -0.1, 1.0, 35);
		FlightDynamics.Input input = new FlightDynamics.Input(0, -20, false);
		FlightDynamics.State boosted = FlightDynamics.step(state, input);

		// The same glide with no rocket active is the position reference.
		FlightDynamics.State airOnly = FlightDynamics.step(
				new FlightDynamics.State(0, 100, 0, 0.4, -0.1, 1.0, 0),
				new FlightDynamics.Input(0, -20, false));

		assertEquals(airOnly.x, boosted.x, 1.0E-9, "x displacement must ignore this tick's thrust");
		assertEquals(airOnly.y, boosted.y, 1.0E-9, "y displacement must ignore this tick's thrust");
		assertEquals(airOnly.z, boosted.z, 1.0E-9, "z displacement must ignore this tick's thrust");
		// The thrust still lands on the velocity, with the glide-updated base.
		assertTrue(Math.abs(boosted.vz - airOnly.vz) > 1.0E-3, "the thrust must reach the velocity");
		assertEquals(34, boosted.rocketTicksRemaining);
	}

	@Test
	void aNewIgnitionMovesTheVelocityButNotThePositionOfItsOwnTick() {
		FlightDynamics.State state = new FlightDynamics.State(0, 100, 0, 0.4, -0.1, 1.0, 0);
		FlightDynamics.Input input = new FlightDynamics.Input(0, -20, true);
		FlightDynamics.State ignited = FlightDynamics.step(state, input);

		FlightDynamics.State airOnly = FlightDynamics.step(
				new FlightDynamics.State(0, 100, 0, 0.4, -0.1, 1.0, 0),
				new FlightDynamics.Input(0, -20, false));

		assertEquals(airOnly.x, ignited.x, 1.0E-9, "the ignition tick's displacement precedes its thrust");
		assertEquals(airOnly.z, ignited.z, 1.0E-9, "the ignition tick's displacement precedes its thrust");
		assertTrue(Math.abs(ignited.vz - airOnly.vz) > 1.0E-3, "the ignition must boost the velocity");
		assertEquals(FlightDynamics.ROCKET_BOOST_TICKS - 1, ignited.rocketTicksRemaining);
	}
}
