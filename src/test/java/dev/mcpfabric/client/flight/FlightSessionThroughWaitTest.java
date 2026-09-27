package dev.mcpfabric.client.flight;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The `through` waypoint kind (ab-30 plan §2): a mid-route handover point must
 * not trigger the terminal hold's descent, while a `stop` leg keeps it.
 */
class FlightSessionThroughWaitTest {

	/** Open air over a floor at y=64; nothing else to hit. */
	private static FlightSession.BlockQuery blocks() {
		return (x, y, z) -> y <= 64.0 ? "minecraft:stone" : "minecraft:air";
	}

	private static FlightSession session(FlightSession.Params params) {
		return new FlightSession(
				List.of(new FlightSession.Waypoint(0, 70, 0), new FlightSession.Waypoint(0, 70, 8)),
				blocks(),
				System.currentTimeMillis() + 60_000,
				params,
				5_000);
	}

	@Test
	void aThroughHandoverKeepsTheAltitudeWhileWaiting() {
		FlightSession.Params params = new FlightSession.Params();
		params.throughWaypoint = true;
		FlightSession session = session(params);
		// Start high enough that a descending hold would trade height for time.
		session.beginHold(0, 90, 8, 1.4, 0, 5_000, true);
		assertEquals(90.0, session.holdTarget().y(), 1.0E-6, "a through wait must not descend");
		assertTrue(session.isHolding());
	}

	@Test
	void aStopLegKeepsTheDescendingHold() {
		FlightSession session = session(new FlightSession.Params());
		session.beginHold(0, 90, 8, 1.4, 0, 5_000);
		assertTrue(session.holdTarget().y() < 90.0, "a stop leg may trade height for time");
		assertTrue(session.isHolding());
	}
}

