package dev.mcpfabric.client.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared-geometry regressions (ab-23 repair plan, C1/C5).
 *
 * The route driver, the terminal verification and the recovery must answer
 * "what is the surface below" the same way: water is a surface (never the
 * riverbed), unknown cells are solid, leaves are a collision surface, and a
 * block at y occupies [y, y+1) so its top is y+1.
 */
class FlightGeometryTest {
	@Test
	void fractionalFlightHeightDoesNotMoveTheBlockSurface() {
		assertEquals(64, FlightGeometry.surfaceBelow((x, y, z) ->
				Math.floor(y) < 64 ? "stone" : "air", 0, 0, 70.65, 40));
		assertTrue(FlightGeometry.isUnsafeSupport("minecraft:air"));
	}

	/** Stone below 61, water up to 62, air above. */
	private static final FlightSession.BlockQuery RIVER = (x, y, z) -> {
		if (y <= 60.0)
			return "minecraft:stone";
		if (y <= 62.0)
			return "minecraft:water";
		return "minecraft:air";
	};

	@Test
	void waterIsASurfaceNotTheRiverbed() {
		assertEquals(63.0, FlightGeometry.surfaceBelow(RIVER, 0, 0, 70, 40),
				"the water top is 63 (the block at 62 occupies [62, 63))");
	}

	@Test
	void unknownCellsStopTheScanAsSolid() {
		FlightSession.BlockQuery unknown = (x, y, z) -> y <= 60.0 ? "minecraft:stone" : null;
		assertEquals(70.0, FlightGeometry.surfaceBelow(unknown, 0, 0, 70, 40),
				"an unknown cell directly below is never air");
	}

	@Test
	void leavesAreACollisionSurface() {
		FlightSession.BlockQuery leaves = (x, y, z) -> {
			if (y >= 65.0 && y <= 65.0)
				return "minecraft:oak_leaves";
			return y <= 60.0 ? "minecraft:stone" : "minecraft:air";
		};
		assertEquals(66.0, FlightGeometry.surfaceBelow(leaves, 0, 0, 70, 40));
		assertTrue(FlightGeometry.isUnsafeSupport("minecraft:oak_leaves"));
	}

	@Test
	void waterAndPlantsAreNeverLandingSupports() {
		assertTrue(FlightGeometry.isUnsafeSupport("minecraft:water"));
		assertTrue(FlightGeometry.isUnsafeSupport("minecraft:lava"));
		assertTrue(FlightGeometry.isUnsafeSupport("minecraft:fire"));
		assertTrue(FlightGeometry.isUnsafeSupport("passable"));
		assertTrue(FlightGeometry.isUnsafeSupport(null));
		assertFalse(FlightGeometry.isUnsafeSupport("minecraft:stone"));
		assertFalse(FlightGeometry.isUnsafeSupport("minecraft:grass_block"));
	}

	@Test
	void waterIsNotFlyableButAirAndPassableAre() {
		assertFalse(FlightGeometry.isFlyableThrough("minecraft:water"));
		assertFalse(FlightGeometry.isFlyableThrough("minecraft:oak_leaves"));
		assertFalse(FlightGeometry.isFlyableThrough(null));
		assertTrue(FlightGeometry.isFlyableThrough("minecraft:air"));
		assertTrue(FlightGeometry.isFlyableThrough("passable"));
	}

	@Test
	void aLateralWallWithASlowSinkStillPitchesUp() {
		// vy near zero is not evidence of safety: the wall ahead is what
		// matters (C5: a slow sink into a bank is still a collision).
		FlightRecovery.Inputs in = new FlightRecovery.Inputs();
		in.heightAbove = 20;
		in.clearanceAhead = 2;
		in.vy = -0.02;
		in.rocketAvailable = true;
		in.boostActive = false;

		assertEquals(FlightRecovery.CLIMB_PITCH, FlightRecovery.decide(in).pitch);
	}
}
