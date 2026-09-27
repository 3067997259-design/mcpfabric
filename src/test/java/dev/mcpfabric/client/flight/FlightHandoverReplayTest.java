package dev.mcpfabric.client.flight;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same-state, same-route, only-the-session-changed replay (R4 client review
 * 2026-09-21, group 1).
 *
 * The live A/B changed the route length, the lowest-altitude bound, the active
 * target and the session state at once, so it can show a difference but cannot
 * attribute it to the handover. These tests keep the geometry identical and
 * only replace the session object, checking the state that must survive a
 * handover: fire cooldown, the active target, and the resulting decisions.
 *
 * The full controller-level replay needs the handover state machine extracted
 * from {@link FlightController}; until then this drives the same session APIs
 * the controller uses when it adopts a pending route.
 */
class FlightHandoverReplayTest {

	/** The venue's river: water blocks up to y=62, air above. */
	private static final FlightSession.BlockQuery RIVER = (x, y, z) ->
			y <= 62 ? "minecraft:water" : "minecraft:air";

	private static final List<FlightSession.Waypoint> ROUTE = List.of(
			new FlightSession.Waypoint(0, 70, 0),
			new FlightSession.Waypoint(0, 70, 20),
			new FlightSession.Waypoint(0, 70, 40),
			new FlightSession.Waypoint(0, 70, 60));

	private static FlightSession session(List<FlightSession.Waypoint> route) {
		// A generous sim budget: this suite compares decision sequences, so the
		// candidate set must not depend on how far a wall-clock budget got.
		FlightSession.Params params = new FlightSession.Params();
		params.simBudgetMs = 1_000;
		return new FlightSession(route, RIVER, System.currentTimeMillis() + 60_000, params);
	}

	@Test
	void handoverInheritsTheUnfinishedFireCooldown() {
		FlightSession first = session(ROUTE);
		first.inheritFireCooldown(6);
		assertTrue(first.fireCooldownActive(0), "the first session is still cooling");
		assertEquals(6, first.fireCooldownRemaining(0));

		// The controller hands the remaining cooldown to the adopted session.
		FlightSession second = session(ROUTE.subList(1, ROUTE.size()));
		second.inheritFireCooldown(first.fireCooldownRemaining(0));
		assertTrue(second.fireCooldownActive(0), "a handover must not clear the cooldown");
		assertEquals(6, second.fireCooldownRemaining(0));
	}

	@Test
	void handoverKeepsTheActiveTargetAndDoesNotReChasePassedEntries() {
		FlightSession first = session(ROUTE);
		// Stand on the first entry: the session advances to the second one.
		first.tick(0, 70, 0, 0, 0, -0.6, 180f, -3f, 64, 0, 0, false);
		FlightSession.Waypoint targetBefore = first.currentTarget();
		assertEquals(20.0, targetBefore.z(), 0.001, "the first entry must be passed");

		// The remaining route starts where the session is heading, not at the
		// first entry again: a handover must not re-chase a passed waypoint.
		FlightSession second = session(ROUTE.subList(1, ROUTE.size()));
		assertEquals(targetBefore.z(), second.currentTarget().z(), 0.001);
	}

	@Test
	void sameStateDecisionsMatchAcrossTheHandover() {
		FlightSession first = session(ROUTE);
		first.tick(0, 70, 0, 0, 0, -0.6, 180f, -3f, 64, 0, 0, false);
		FlightSession second = session(ROUTE.subList(1, ROUTE.size()));
		second.inheritFireCooldown(first.fireCooldownRemaining(1));

		// The same measured state, fed to both: the decisions must agree apart
		// from the session identity (R4 review group 1 acceptance).
		double x = 0;
		double y = 70;
		double z = 12;
		double vx = 0;
		double vy = -0.05;
		double vz = -0.6;
		FlightSession.Decision a = first.tick(x, y, z, vx, vy, vz, 180f, -3f, 64, 0, 1, false);
		FlightSession.Decision b = second.tick(x, y, z, vx, vy, vz, 180f, -3f, 64, 0, 1, false);

		assertEquals(a.applicable, b.applicable);
		// The TARGET must not jump at a handover. The raw entry index is
		// path-local (the new session's path starts at the current target), so
		// the contract to compare is the target itself.
		assertEquals(a.entryDistance, b.entryDistance, 0.001, "the active target must not jump at a handover");
		assertEquals(a.fireRocket, b.fireRocket, "cooldown and feasibility must match");
		assertEquals(a.yaw, b.yaw, 0.001);
		assertEquals(a.pitch, b.pitch, 0.001);
	}

	@Test
	void withoutInheritanceTheCooldownWouldReset() {
		// Guards the guard: a fresh session (the old controller behavior) is
		// immediately allowed to fire again, which is what the inheritance
		// exists to prevent.
		FlightSession fresh = session(ROUTE);
		assertFalse(fresh.fireCooldownActive(0), "a fresh session starts outside the cooldown window");
	}

	@Test
	void aPassedEntryIsNotChasedBack() {
		// The glider flew past the second entry with an offset (distance >
		// entryReach): the sphere test alone would keep steering back to it.
		// Passed-by-plane must advance to the next entry instead (R4 ab-09
		// revision 4 turned around and splashed here).
		FlightSession session = session(ROUTE);
		session.tick(0, 70, 0, 0, 0, -0.6, 180f, -3f, 64, 0, 0, false);
		session.tick(0, 70, 25, 0, 0, -0.6, 180f, -3f, 64, 0, 1, false);
		assertEquals(40.0, session.currentTarget().z(), 0.001, "a passed entry must not be chased back");
	}

	@Test
	void controllerLevelReplayMatchesDecisionsAcrossAHandover() {
		// Group 1, controller level: the SAME route and the same measured
		// state sequence feed two arms; arm B adopts the same route again
		// through the real FlightHandover rules, so only the session identity
		// changes. Decisions must match exactly (ab-17 audit: a shorter route
		// changed the scoring horizon, which is a different experiment).
		List<FlightSession.Waypoint> full = ROUTE;
		FlightSession armA = session(full);
		FlightSession armB = session(full);
		FlightHandover handover = new FlightHandover();
		boolean adopted = false;
		int armBTick = 0;
		FlightDynamics.State state = new FlightDynamics.State(0, 70, 0, 0, 0, 0.6, 0);
		for (int tick = 0; tick < 80; tick++) {
			FlightSession.Decision a = armA.tick(
					state.x, state.y, state.z, state.vx, state.vy, state.vz,
					0f, -3f, 64, state.rocketTicksRemaining, tick, armA.fireCooldownActive(tick));
			if (!adopted && tick >= 5) {
				if (handover.pending() == null)
					handover.offer("leg-same", 2, session(full), tick, 1);
				boolean currentDone = armB.isPathComplete() || armB.isDone();
				FlightHandover.PilotState pilot = new FlightHandover.PilotState(
						state.x, state.y, state.z, state.vx, state.vz, true, false);
				FlightHandover.Action action = handover.decide(pilot, armB, tick, currentDone);
				if (action == FlightHandover.Action.ADOPT) {
					FlightHandover.adopt(armB, handover.pending(), armBTick);
					armB = handover.pending();
					handover.completeAdoption();
					adopted = true;
					armBTick = 0;
				}
			}
			FlightSession.Decision b = armB.tick(
					state.x, state.y, state.z, state.vx, state.vy, state.vz,
					0f, -3f, 64, state.rocketTicksRemaining, armBTick, armB.fireCooldownActive(armBTick));
			armBTick += 1;
			assertEquals(a.applicable, b.applicable, "tick " + tick + " applicability");
			if (a.applicable && b.applicable) {
				assertEquals(a.fireRocket, b.fireRocket, "tick " + tick + " fire");
				assertEquals(a.yaw, b.yaw, 0.001, "tick " + tick + " yaw");
				assertEquals(a.pitch, b.pitch, 0.001, "tick " + tick + " pitch");
				assertEquals(a.entryDistance, b.entryDistance, 0.001, "tick " + tick + " target");
			}
			if (!a.applicable)
				break;
			state = FlightDynamics.step(state, new FlightDynamics.Input(a.yaw, a.pitch, a.fireRocket));
		}
		assertTrue(adopted, "the remaining route must have been adopted during the replay");
	}

	@Test
	void aNewSessionsFirstPointIsSkippedWhenAlreadyBehind() {
		// R4 ab-11: revision 4's leg started behind the glider, the first point
		// could not trigger the passed-by-plane check (no predecessor), and the
		// driver turned around (yaw 174.7 -> 10.5) and chased it back. A leg
		// whose first point is already behind must start at its next point.
		FlightSession newLeg = session(List.of(
				new FlightSession.Waypoint(0, 70, 0),
				new FlightSession.Waypoint(0, 70, 20)));
		// The glider is 10 blocks past the leg's first point, still flying
		// along the leg direction.
		newLeg.tick(0, 70, 10, 0, 0, 0.6, 0f, -3f, 64, 0, 0, false);
		assertEquals(20.0, newLeg.currentTarget().z(), 0.001, "a behind first point must be skipped");
	}

	@Test
	void theTerminalEntryStillNeedsTheReach() {
		// Plane skipping must not complete the channel early: the terminal
		// entry is completed by the reach test only.
		FlightSession session = session(ROUTE);
		session.tick(0, 70, 0, 0, 0, -0.6, 180f, -3f, 64, 0, 0, false);
		session.tick(0, 70, 20, 0, 0, -0.6, 180f, -3f, 64, 0, 1, false);
		session.tick(0, 70, 40, 0, 0, -0.6, 180f, -3f, 64, 0, 2, false);
		// Past the terminal by 15 blocks: outside the entry reach, so the
		// channel must NOT complete (the terminal is completed by reach only).
		session.tick(0, 70, 75, 0, 0, -0.6, 180f, -3f, 64, 0, 3, false);
		assertFalse(session.isPathComplete(), "flying past the terminal entry must not complete the path");
		assertEquals(60.0, session.currentTarget().z(), 0.001, "the terminal entry stays the target");
	}
}

