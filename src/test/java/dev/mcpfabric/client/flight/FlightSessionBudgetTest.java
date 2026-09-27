package dev.mcpfabric.client.flight;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Budget-semantics regressions (ab-24 audit section 4).
 *
 * The horizon is conditional, the previous winner is re-checked first in one
 * global order, and an interrupted search is "unverified", never "no route".
 */
class FlightSessionBudgetTest {

	private static FlightSession session(List<FlightSession.Waypoint> path, FlightSession.Params params) {
		return new FlightSession(path, (x, y, z) -> y <= 60.0 ? "minecraft:stone" : "minecraft:air",
				System.currentTimeMillis() + 60_000, params, 5_000);
	}

	@Test
	void aNormalCandidateRunsExactlyTheHorizon() {
		FlightSession.Params params = new FlightSession.Params();
		params.horizonTicks = 20;
		params.simBudgetMs = 1_000;
		FlightSession session = session(
				List.of(new FlightSession.Waypoint(0, 70, 0), new FlightSession.Waypoint(400, 70, 0)), params);
		FlightSession.Decision decision = session.tick(0, 70, 0, 1.4, 0, 0, -90f, 0f, 0, 0, 0, false);

		assertTrue(decision.applicable, decision.note);
		assertEquals("horizon", decision.predictedEndReason);
		assertEquals(20, decision.predictedEndTicks,
				"a non-arriving candidate must not simulate the terminal lookahead: " + decision.note);
	}

	@Test
	void thePredictionUsesTheSamePassedPlaneRuleAsTheRealCursor() {
		// The middle entry is offset laterally, so the sphere alone would never
		// reach it (closest approach 10 > entryReach); the shared plane rule
		// advances the cursor during the prediction, exactly like the driver.
		FlightSession.Params params = new FlightSession.Params();
		params.simBudgetMs = 1_000;
		FlightSession session = session(
				List.of(new FlightSession.Waypoint(0, 70, 0),
						new FlightSession.Waypoint(40, 70, 10),
						new FlightSession.Waypoint(80, 70, 0)),
				params);
		FlightSession.Decision decision = session.tick(20, 70, 0, 2.5, 0, 0, -90f, 0f, 0, 0, 0, false);

		assertTrue(decision.applicable, decision.note);
		assertEquals(1, decision.cursor, "the real cursor plane-advanced past the first entry: " + decision.note);
		assertTrue(decision.predictedEndCursor >= 2,
				"the prediction advances the same way during its own sweep: " + decision.note);
	}

	@Test
	void thePreviousWinnerIsReCheckedFirstInTheGlobalOrder() {
		FlightSession.Params params = new FlightSession.Params();
		params.simBudgetMs = 1_000;
		FlightSession session = session(
				List.of(new FlightSession.Waypoint(0, 72, 0), new FlightSession.Waypoint(200, 70, 0)), params);
		FlightSession.Decision first = session.tick(0, 72, 0, 1.4, 0, 0, -90f, 0f, 0, 0, 0, false);
		assertTrue(first.applicable, first.note);

		// One evaluated candidate: it must be the previous winner (state and
		// parameters unchanged), not the first yaw of the first pass.
		params.maxCandidates = 1;
		FlightSession.Decision second = session.tick(0, 72, 0, 1.4, 0, 0, -90f, 0f, 0, 0, 1, false);
		assertEquals(1, second.evaluated, second.note);
		assertEquals(first.chosenPolicy, second.chosenPolicy,
				"the baseline policy is re-checked first: " + second.note);
	}

	@Test
	void anInterruptedSearchIsUnverifiedNotNoRoute() {
		// A wall rejects everything, and the cap stops the search after the
		// first candidate: the session must stay alive and report the gap
		// instead of claiming no route exists.
		FlightSession.Params params = new FlightSession.Params();
		params.maxCandidates = 1;
		FlightSession session = session(
				List.of(new FlightSession.Waypoint(0, 70, 0), new FlightSession.Waypoint(200, 70, 0)), params);
		session = new FlightSession(
				List.of(new FlightSession.Waypoint(0, 70, 0), new FlightSession.Waypoint(200, 70, 0)),
				(x, y, z) -> x >= 4.0 ? "minecraft:stone" : (y <= 60.0 ? "minecraft:stone" : "minecraft:air"),
				System.currentTimeMillis() + 60_000, params, 5_000);
		FlightSession.Decision decision = session.tick(0, 70, 0, 1.4, 0, 0, -90f, 0f, 0, 0, 0, false);

		assertFalse(decision.applicable, decision.note);
		assertTrue(decision.budgetExhausted, decision.note);
		assertEquals("candidate_cap", decision.budgetReason, decision.note);
		assertTrue(decision.note.contains("search_incomplete"), decision.note);
		assertFalse(session.isDone(), "an interrupted search is not proof that no route exists");

		// With the full search the same state gets a real verdict.
		params.maxCandidates = 64;
		params.simBudgetMs = 1_000;
		FlightSession.Decision full = session.tick(0, 70, 0, 1.4, 0, 0, -90f, 0f, 0, 0, 1, false);
		assertTrue(full.evaluated > 1, full.note);
		assertTrue(session.isDone(), "the complete search rejects the wall and ends as no route");
		assertEquals(FlightSession.EndReason.NO_VIABLE_TRAJECTORY, session.endReason());
	}
}
