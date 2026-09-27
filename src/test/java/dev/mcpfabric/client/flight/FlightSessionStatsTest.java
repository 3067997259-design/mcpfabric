package dev.mcpfabric.client.flight;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evaluation-accounting regressions (ab-23 repair plan, A4/A5).
 *
 * The ab-23 segmented arm reported `ta=139` because the terminal-rejection
 * counter was never reset between ticks, so one tick's number looked like the
 * whole run's. The per-tick counters must start at zero every tick, the
 * cumulative totals must live in their own fields, and the first-reject
 * categories must add up to the rejected candidate count.
 */
class FlightSessionStatsTest {

	/** A wall right in front: every candidate is rejected. */
	private static final FlightSession.BlockQuery WALL = (x, y, z) ->
			x >= 4.0 ? "minecraft:stone" : (y <= 60.0 ? "minecraft:stone" : "minecraft:air");

	private static FlightSession session(FlightSession.BlockQuery blocks) {
		return new FlightSession(
				List.of(new FlightSession.Waypoint(0, 70, 0), new FlightSession.Waypoint(200, 70, 0)),
				blocks,
				System.currentTimeMillis() + 60_000,
				new FlightSession.Params());
	}

	@Test
	void theRejectCategoriesAddUpToTheRejectedCandidates() {
		FlightSession session = session(WALL);
		FlightSession.Decision decision = session.tick(0, 70, 0, 1.0, 0, 0, -90f, 0f, 0, 0, 0, false);

		assertFalse(decision.applicable, "the wall must refuse every candidate: " + decision.note);
		assertTrue(decision.evaluated > 0, "candidates were attempted");
		assertEquals(decision.evaluated - decision.feasible, decision.rejected);
		assertEquals(decision.rejected,
				decision.rejectCollisions + decision.rejectFloor + decision.rejectSpeed + decision.rejectTerminal,
				"every rejected candidate counts in exactly one category: " + decision.note);
		assertTrue(decision.rejectCollisions > 0, "the wall is a collision refusal: " + decision.note);
		assertEquals("collision", decision.rejectKind);
	}

	@Test
	void perTickCountersResetWhileTheCumulativeTotalsAccumulate() {
		// A ceiling ahead rejects the climbing policies (AIM_PULL, PULL_UP)
		// while the level ones stay feasible, so the session stays alive for a
		// second tick.
		// Feet 70 plus the inflated height end at 71.95. The ceiling at 72
		// is clear at level and actually intersects a climb; 73 only rejected
		// because the old box enumerator added an untouched top voxel.
		FlightSession.BlockQuery lowCeiling = (x, y, z) ->
				x >= 6.0 && y >= 72.0 ? "minecraft:stone" : (y <= 60.0 ? "minecraft:stone" : "minecraft:air");
		FlightSession session = session(lowCeiling);
		FlightSession.Decision first = session.tick(0, 70, 0, 1.0, 0, 0, -90f, 0f, 0, 0, 0, false);
		FlightSession.Decision second = session.tick(0, 70, 0, 1.0, 0, 0, -90f, 0f, 0, 0, 1, false);

		assertTrue(first.applicable, "some candidate must survive the low ceiling: " + first.note);
		assertTrue(first.rejected > 0, "the low ceiling rejects the climbing candidates: " + first.note);
		// The second tick is a fresh decision: its per-tick counters describe
		// only that tick, never the first one's.
		assertEquals(second.rejected,
				second.rejectCollisions + second.rejectFloor + second.rejectSpeed + second.rejectTerminal);
		assertEquals(first.rejected + second.rejected, second.cumulativeRejects,
				"the cumulative total owns the run's rejections: " + second.note);
		assertEquals(first.rejectCollisions + second.rejectCollisions, second.cumulativeCollisions);
		assertEquals(0, second.rejectTerminal, "a fresh tick starts its terminal counter at zero");
	}

	@Test
	void aBudgetStopIsReportedInsteadOfFakingAFullEvaluation() {
		FlightSession.Params params = new FlightSession.Params();
		params.maxCandidates = 2;
		FlightSession session = new FlightSession(
				List.of(new FlightSession.Waypoint(0, 70, 0), new FlightSession.Waypoint(200, 70, 0)),
				WALL,
				System.currentTimeMillis() + 60_000,
				params);
		FlightSession.Decision decision = session.tick(0, 70, 0, 1.0, 0, 0, -90f, 0f, 0, 0, 0, false);

		assertTrue(decision.budgetExhausted, "the search must report the candidate-cap stop: " + decision.note);
		assertTrue(decision.unevaluated > 0, "unevaluated candidates are reported: " + decision.note);
		assertFalse(decision.applicable);
	}

	@Test
	void aViableTickReportsTheFeasibleCount() {
		FlightSession session = session((x, y, z) -> y <= 60.0 ? "minecraft:stone" : "minecraft:air");
		FlightSession.Decision decision = session.tick(0, 70, 0, 1.0, 0, 0, -90f, 0f, 0, 0, 0, false);

		assertTrue(decision.applicable, decision.note);
		assertTrue(decision.feasible > 0, "at least the winning candidate is feasible: " + decision.note);
		assertEquals(0, decision.rejected);
		assertEquals(0, decision.rejectCollisions + decision.rejectFloor + decision.rejectSpeed + decision.rejectTerminal);
	}
}
