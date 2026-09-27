package dev.mcpfabric.client.flight;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hold-target regressions (R4 client review 2026-09-21).
 *
 * ROOT CAUSE of the cave-diag-16 water entry: {@code beginHold} declared a
 * local {@code double holdY}, which shadowed the session field, so the field
 * stayed 0 and every hold steered at y=0 — straight down into the river.
 *
 * These tests pin the field write and the surface clearance so the same
 * mistake fails the build instead of a live flight.
 */
class FlightSessionHoldTest {

	/** The venue's river: water blocks up to y=62, air above. */
	private static final FlightSession.BlockQuery RIVER = (x, y, z) ->
			y <= 62 ? "minecraft:water" : "minecraft:air";

	private static FlightSession session() {
		return new FlightSession(
				List.of(new FlightSession.Waypoint(0, 74, 0), new FlightSession.Waypoint(0, 74, 20)),
				RIVER,
				System.currentTimeMillis() + 60_000,
				new FlightSession.Params());
	}

	@Test
	void holdTargetIsWrittenToTheFieldAndKeepsClearance() {
		FlightSession session = session();
		session.beginHold(0, 65, 0, 0.5, 0, 5_000);

		assertTrue(session.isHolding(), "beginHold must enter the hold state");
		FlightSession.Waypoint target = session.holdTarget();
		assertTrue(target.y() > 0, "hold target y must be the field value, not the shadowed local (was 0)");
		// Surface top at y=63; the hold keeps HOLD_CLEARANCE (6) above it.
		assertEquals(69.0, target.y(), 0.001, "hold must stay clear of the water surface");
	}

	@Test
	void lowApproachKeepsAltitudeInsteadOfDiving() {
		FlightSession session = session();
		// 2 blocks above the surface: too little clearance to trade height for
		// time, so the hold must keep the current altitude (fire may hold it).
		session.beginHold(0, 65, 0, 0.5, 0, 5_000);
		FlightSession.Waypoint target = session.holdTarget();
		assertTrue(target.y() >= 65.0 - 0.001, "a low hold must not target below the current altitude");
	}

	@Test
	void holdNeverTargetsBelowTheScannedSurface() {
		FlightSession session = session();
		// Deep descent allowed by the generic rule still stops above the water.
		session.beginHold(0, 80, 0, 0.5, 0, 5_000);
		FlightSession.Waypoint target = session.holdTarget();
		assertTrue(target.y() >= 69.0 - 0.001, "hold must keep HOLD_CLEARANCE above the surface, got " + target.y());
	}

	@Test
	void theHoldFollowsTheHeadingInsteadOfOrbitingAFixedPoint() {
		// R4 lane-pert-timeout: the hold target was fixed at hold start, the
		// pilot flew to it and then orbited it for the rest of the grace,
		// firing to stay up. The target must stay ahead of the CURRENT
		// position along the current heading.
		FlightSession session = session();
		session.beginHold(200, 70, -17, -1.5, 0, 5_000);
		double firstX = session.holdTarget().x();
		// The glider advanced 30 blocks west while holding.
		session.tick(170, 69, -17, -1.5, 0, 0, 90f, -3f, 64, 0, 0, false);
		double secondX = session.holdTarget().x();
		assertTrue(secondX < firstX - 20, "the hold target must move with the glider (was " + firstX + ", now " + secondX + ")");
		assertEquals(170 - 48, secondX, 0.001, "the target stays ahead of the current position");
	}
}
