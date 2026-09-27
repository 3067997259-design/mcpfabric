package dev.mcpfabric.client.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host landing-site validation regressions (ab-23 repair plan, C2).
 *
 * The host owns site selection; the client must prove, from the live state,
 * that the site's support is safe, the headroom exists and the current
 * velocity can actually reach the entry — a usable site is not automatically
 * a reachable one.
 */
class FlightLandingPlanTest {

	/** Flat ground at y=64 with open air above. */
	private static final FlightSession.BlockQuery FLAT = (x, y, z) ->
			y <= 63.0 ? "minecraft:stone" : "minecraft:air";

	private static FlightLandingPlan.Result validate(FlightSession.BlockQuery blocks,
			double x, double y, double z, double vx, double vy, double vz, FlightLandingPlan.Site site) {
		FlightSession.Params params = new FlightSession.Params();
		params.simBudgetMs = 1_000;
		return FlightLandingPlan.validate(blocks, System.currentTimeMillis() + 60_000,
				params, x, y, z, vx, vy, vz, -90f, 0f, 0, 0, site);
	}

	@Test
	void acceptsAReachableSiteOnFlatGround() {
		FlightLandingPlan.Result result = validate(FLAT, 0, 70, 0, 1.4, -0.2, 0,
				new FlightLandingPlan.Site(30, 65, 0, 64));
		assertTrue(result.accepted(), result.reason());
	}

	@Test
	void refusesASiteWhoseSupportIsWater() {
		FlightSession.BlockQuery flooded = (x, y, z) -> {
			if (y <= 60.0)
				return "minecraft:stone";
			if (y <= 63.0)
				return "minecraft:water";
			return "minecraft:air";
		};
		FlightLandingPlan.Result result = validate(flooded, 0, 70, 0, 1.4, -0.2, 0,
				new FlightLandingPlan.Site(30, 65, 0, 64));
		assertFalse(result.accepted());
		assertEquals("unsafe_support", result.reason());
	}

	@Test
	void refusesASiteWithoutStandingHeadroom() {
		FlightSession.BlockQuery roofed = (x, y, z) -> {
			if (y <= 63.0)
				return "minecraft:stone";
			return y >= 65.0 && y <= 70.0 ? "minecraft:stone" : "minecraft:air";
		};
		FlightLandingPlan.Result result = validate(roofed, 0, 70, 0, 1.4, -0.2, 0,
				new FlightLandingPlan.Site(30, 65, 0, 64));
		assertFalse(result.accepted());
		assertEquals("no_headroom", result.reason());
	}

	@Test
	void refusesASiteBehindTheCurrentHeading() {
		// The site sits behind the velocity: the bounded prediction cannot
		// reach it, so the client reports the gap instead of faking a landing.
		FlightLandingPlan.Result result = validate(FLAT, 0, 70, 0, 1.4, -0.2, 0,
				new FlightLandingPlan.Site(-200, 65, 0, 64));
		assertFalse(result.accepted());
		assertEquals("landing_unreachable", result.reason());
	}

	@Test
	void aVerifiedPlanMovesTheRecoveryIntoLanding() {
		FlightOwnership ownership = new FlightOwnership();
		ownership.startRoute(30_000);
		assertFalse(ownership.enterLanding(), "only a live recovery may enter LAND");
		ownership.routeFailed("no_viable_trajectory", 1_000, 6_000, 30_000);
		assertTrue(ownership.enterLanding());
		assertEquals(FlightOwnership.Phase.LAND, ownership.phase());
		assertEquals(FlightOwnership.RouteOutcome.FAILED, ownership.routeOutcome());
		assertTrue(ownership.hasLiveControl(2_000));
		assertEquals("landing", ownership.acceptBlockReason(2_000),
				"a landing approach still refuses a new route");
		assertFalse(ownership.hasLiveControl(30_001), "the hard deadline still bounds the landing");
	}
}
