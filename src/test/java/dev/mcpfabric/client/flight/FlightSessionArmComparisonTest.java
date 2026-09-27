package dev.mcpfabric.client.flight;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-arm vs split-leg comparison regressions (ab-23 repair plan, D2).
 *
 * "From the same polyline" does not make the two arms the same control
 * problem: the remaining lower bound and the terminal hold both depend on how
 * much of the route the session can still see. The comparison fixes the same
 * measured state, the same visible prefix and the same suffix inside the
 * prediction range, then varies only the session envelope:
 *
 * <ul>
 * <li>the split terminal outside the prediction range: the visible suffix is
 * identical, so target, progress and first action must match;</li>
 * <li>the split terminal inside the prediction range: the leg reaches its
 * terminal while the full arm still sees more route. That difference is
 * "route visibility / terminal semantics", not a handover bug.</li>
 * </ul>
 */
class FlightSessionArmComparisonTest {

	private static FlightSession.Waypoint w(double x, double y, double z) {
		return new FlightSession.Waypoint(x, y, z);
	}

	private static FlightSession session(List<FlightSession.Waypoint> path) {
		return new FlightSession(path, (x, y, z) -> y <= 60.0 ? "minecraft:stone" : "minecraft:air",
				System.currentTimeMillis() + 60_000, new FlightSession.Params(), 5_000);
	}

	@Test
	void aSplitTerminalOutsideThePredictionRangeKeepsTheFirstActionIdentical() {
		// Full arm: A, B, C, D. Split leg: the same prefix with C as its
		// terminal — both terminals sit beyond the ~30-block prediction range
		// from the measured state, so the visible suffix is identical.
		List<FlightSession.Waypoint> full = List.of(w(0, 72, 0), w(40, 70, 0), w(80, 70, 0), w(120, 70, 0));
		List<FlightSession.Waypoint> leg = List.of(w(0, 72, 0), w(40, 70, 0), w(80, 70, 0));

		FlightSession fullArm = session(full);
		FlightSession splitArm = session(leg);
		FlightSession.Decision a = fullArm.tick(0, 72, 0, 1.5, 0, 0, -90f, 0f, 0, 0, 0, false);
		FlightSession.Decision b = splitArm.tick(0, 72, 0, 1.5, 0, 0, -90f, 0f, 0, 0, 0, false);

		assertTrue(a.applicable && b.applicable, a.note + " / " + b.note);
		assertEquals(a.cursor, b.cursor, "the visible target must match: " + a.note + " / " + b.note);
		assertEquals(a.yaw, b.yaw, "the first action must match: " + a.note + " / " + b.note);
		assertEquals(a.pitch, b.pitch, "the first action must match: " + a.note + " / " + b.note);
		assertEquals(a.fireRocket, b.fireRocket);
		assertEquals(a.progress, b.progress, 1.0E-6, "the global progress must match: " + a.note + " / " + b.note);
	}

	@Test
	void aSplitTerminalInsideThePredictionRangeIsARouteVisibilityDifference() {
		// The measured state is 20 blocks from B: the leg terminates at B, the
		// full arm continues to C. Both are correct for their own envelope.
		List<FlightSession.Waypoint> full = List.of(w(0, 72, 0), w(40, 70, 0), w(80, 70, 0), w(120, 70, 0));
		List<FlightSession.Waypoint> leg = List.of(w(0, 72, 0), w(40, 70, 0));

		FlightSession fullArm = session(full);
		FlightSession splitArm = session(leg);
		FlightSession.Decision a = fullArm.tick(20, 71, 0, 1.5, 0, 0, -90f, 0f, 0, 0, 0, false);
		FlightSession.Decision b = splitArm.tick(20, 71, 0, 1.5, 0, 0, -90f, 0f, 0, 0, 0, false);

		assertTrue(a.applicable && b.applicable, a.note + " / " + b.note);
		assertEquals(2, b.predictedEndCursor, "the leg terminal completes inside the horizon: " + b.note);
		assertEquals("arrived_lookahead", b.predictedEndReason);
		assertEquals(2, a.predictedEndCursor, "the full arm's cursor advances past B but not to its terminal");
		assertNotEquals("arrived_lookahead", a.predictedEndReason,
				"the full arm still sees route beyond B, so its terminal is not reached: " + a.note);
	}
}
