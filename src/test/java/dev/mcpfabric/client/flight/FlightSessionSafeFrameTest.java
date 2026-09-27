package dev.mcpfabric.client.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded safe-search regressions (ab-29 review): the search must include the
 * zero offset and symmetric turns, apply the shared steering/pitch rate, check
 * the predicted END state (not only a short prefix), and report honestly when
 * nothing verifies instead of calling a held attitude "verified".
 */
class FlightSessionSafeFrameTest {

	/** Ground at y=64; a wall across +x from 30 blocks on, only 6 high. */
	private static FlightSession session(FlightSession.BlockQuery blocks) {
		return new FlightSession(
				java.util.List.of(new FlightSession.Waypoint(0, 70, 0), new FlightSession.Waypoint(200, 70, 0)),
				blocks,
				System.currentTimeMillis() + 60_000,
				new FlightSession.Params(),
				5_000);
	}

	@Test
	void aWallAheadIsAvoidedWithASymmetricTurn() {
		// A tall wall straight ahead: the zero offset fails, a left or right
		// turn has room. The chosen action must be a verified turn.
		FlightSession.BlockQuery blocks = (x, y, z) ->
				x >= 16.0 && y <= 78.0 && Math.abs(z) <= 2.0
						? "minecraft:stone"
						: (y <= 64.0 ? "minecraft:stone" : "minecraft:air");
		FlightSession session = session(blocks);
		FlightSession.SafeFrame frame = session.safeFrame(0, 70, 0, 1.2, 0, 0, 0, 0, -90f, 0f, 20);

		assertTrue(frame.verified > 0, "a side exit must verify: verified=" + frame.verified);
		assertFalse(frame.emergency);
		assertTrue(Math.abs(frame.yaw - (-90f)) <= 25.01f, "the first step respects the steering rate: " + frame.yaw);
		assertTrue(Math.abs(frame.yaw - (-90f)) > 0.01f, "the straight line is blocked, so the action turns");
	}

	@Test
	void aShortSafePrefixIsNotEnoughWhenTheEndStateHasNoRoom() {
		// A ceiling over the whole reachable area: every action's short prefix
		// is clear but the predicted end state is boxed in, so nothing may be
		// reported as verified.
		FlightSession.BlockQuery blocks = (x, y, z) ->
				y >= 71.0 ? "minecraft:stone" : (y <= 64.0 ? "minecraft:stone" : "minecraft:air");
		FlightSession session = session(blocks);
		FlightSession.SafeFrame frame = session.safeFrame(0, 70, 0, 1.4, 0, 0, 0, 0, -90f, 0f, 14);

		assertEquals(0, frame.verified, "boxed in must not verify: " + frame.verified);
		assertTrue(frame.emergency, "the emergency ranking supplies the action");
		assertTrue(frame.candidates > 0);
	}

	@Test
	void allBlockedReportsTheLatestContactAsEmergency() {
		FlightSession.BlockQuery blocks = (x, y, z) ->
				Math.abs(z) <= 30.0 && y <= 80.0 ? "minecraft:stone" : (y <= 64.0 ? "minecraft:stone" : "minecraft:air");
		FlightSession session = session(blocks);
		FlightSession.SafeFrame frame = session.safeFrame(0, 70, 0, 1.4, 0, 0, 0, 0, -90f, 0f, 14);

		assertEquals(0, frame.verified);
		assertTrue(frame.emergency);
		assertTrue(frame.contactTicks >= 0, "the emergency action names its predicted contact tick");
	}

	@Test
	void theSearchIsSymmetricAndRateLimited() {
		// Open air: the zero offset wins (no unnecessary turn) and the first
		// step is within the rate limit.
		FlightSession.BlockQuery blocks = (x, y, z) -> y <= 64.0 ? "minecraft:stone" : "minecraft:air";
		FlightSession session = session(blocks);
		FlightSession.SafeFrame frame = session.safeFrame(0, 70, 0, 1.4, 0, 0, 0, 0, -90f, 0f, 14);

		assertTrue(frame.verified > 0, "open air verifies");
		assertEquals(-90f, frame.yaw, 0.01f, "the zero offset is tried first and wins in open air");
	}
}



