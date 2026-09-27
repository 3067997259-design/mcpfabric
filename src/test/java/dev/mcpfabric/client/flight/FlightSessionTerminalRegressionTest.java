package dev.mcpfabric.client.flight;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Terminal-transition regressions from the ab-17 audit (item 2): the two
 * terminal states below enter the frozen route's terminal sphere one tick
 * after the recorded steer (entryReach 8, the value the host sends), and the
 * prediction must not be cut there. The old code broke out of the candidate
 * sweep at arrival, ran only a 0.5-clearance check at the arrival state, and
 * declared the candidate viable; the obstacle one or two ticks later was never
 * swept.
 *
 * <p>States and inputs are the recorded samples
 * {@code ab17-audit/samples.csv}: {@code full,19220} and
 * {@code segmented,19478}. The audit measured the one-tick prediction error at
 * ~1e-6 blocks, so the predicted path used to place the test wall is the same
 * path the live flight took. The wall is a half-space two blocks beyond the
 * arrival plane, below the arrival altitude: air above the ground lets the
 * arrival clearance check pass, and only the post-arrival continuation can
 * discover the wall.
 */
class FlightSessionTerminalRegressionTest {

	/** The frozen route's terminal (ab-17 audit section 1). */
	private static final double TERMINAL_X = -958.5;
	private static final double TERMINAL_Y = 81.5;
	private static final double TERMINAL_Z = -67.5;

	/** Ground below y=61; the wall is added by {@link #wallBeyond}. */
	private static FlightSession.BlockQuery ground() {
		return (x, y, z) -> y <= 60.0 ? "minecraft:stone" : "minecraft:air";
	}

	/**
	 * A solid half-space starting two blocks past the predicted arrival point
	 * along the flight bearing, wide across it and tall enough that no
	 * candidate of the same tick can climb over it.
	 */
	private static FlightSession.BlockQuery wallBeyond(double x, double y, double z,
			double vx, double vy, double vz, float yaw, float pitch) {
		FlightDynamics.State next = FlightDynamics.step(
				new FlightDynamics.State(x, y, z, vx, vy, vz, 0),
				new FlightDynamics.Input(yaw, pitch, false));
		double dirX = next.x - x;
		double dirZ = next.z - z;
		double length = Math.hypot(dirX, dirZ);
		dirX /= length;
		dirZ /= length;
		double wallX = next.x + dirX * 2.0;
		double wallZ = next.z + dirZ * 2.0;
		double wx = dirX;
		double wz = dirZ;
		return (bx, by, bz) -> {
			if (by <= 60.0)
				return "minecraft:stone";
			double along = (bx - wallX) * wx + (bz - wallZ) * wz;
			double perp = (bx - wallX) * (-wz) + (bz - wallZ) * wx;
			if (along >= 0 && Math.abs(perp) <= 12.0 && by >= 70.0 && by <= 100.0)
				return "minecraft:oak_leaves";
			return "minecraft:air";
		};
	}

	private static FlightSession session(FlightSession.BlockQuery blocks,
			double startX, double startY, double startZ) {
		FlightSession.Params params = new FlightSession.Params();
		// A generous sim budget: these regressions assert terminal decisions, so
		// a wall-clock cut must not turn them into search_incomplete.
		params.simBudgetMs = 1_000;
		// The host's channel entryReach (R4); 8 is what makes the 1-tick
		// arrival premise of the audit table hold.
		params.entryReach = 8.0;
		// Entry 0 sits on the recorded start state, so the session begins with
		// the terminal as its target — the ab-17 terminals were entries 19/19
		// and 4/4, i.e. every earlier entry was already passed.
		return new FlightSession(
				List.of(new FlightSession.Waypoint(startX, startY, startZ),
						new FlightSession.Waypoint(TERMINAL_X, TERMINAL_Y, TERMINAL_Z)),
				blocks,
				System.currentTimeMillis() + 60_000,
				params,
				5_000);
	}

	private static FlightSession.Decision tick(FlightSession session,
			double x, double y, double z, double vx, double vy, double vz, float yaw, float pitch) {
		return session.tick(x, y, z, vx, vy, vz, yaw, pitch, 0, 0, 0, false);
	}

	@Test
	void fullArm19220ArrivalDoesNotHideTheObstacleJustPastTheTerminal() {
		// samples.csv full,19220 (last steer; terminal reached at t1).
		double x = -959.8226691069283;
		double y = 88.81102913554756;
		double z = -64.4217371874942;
		double vx = 0.86975048427051;
		double vy = -0.4593681131566472;
		double vz = -1.4379383108271315;
		float yaw = -156.74776f;
		float pitch = 60f;

		// Control: with clear air below, the same state arrives and holds.
		FlightSession clear = session(ground(), x, y, z);
		FlightSession.Decision clearDecision = tick(clear, x, y, z, vx, vy, vz, yaw, pitch);
		assertTrue(clearDecision.applicable, "a clear terminal must stay viable: " + clearDecision.note);
		assertEquals("hold", clearDecision.terminalAction,
				"the arrival must carry a verified terminal action: " + clearDecision.note);

		// Regression: the wall blocks the post-arrival dive; the truncating
		// implementation accepted this state as arrived and viable.
		FlightSession blocked = session(wallBeyond(x, y, z, vx, vy, vz, yaw, pitch), x, y, z);
		FlightSession.Decision decision = tick(blocked, x, y, z, vx, vy, vz, yaw, pitch);
		assertFalse(decision.applicable,
				"an obstacle past the terminal must reject the candidate: " + decision.note);
		assertEquals(FlightSession.EndReason.NO_VIABLE_TRAJECTORY, blocked.endReason());
		assertTrue(decision.rejectKind.equals("collision") || decision.rejectKind.equals("terminal_action"),
				"the rejection must name the post-arrival failure: " + decision.note);
		assertTrue(decision.rejectY <= y - 0.5,
				"the rejection must lie past the arrival altitude: " + decision.note);
	}

	@Test
	void anArrivalWhoseHoldOnlyReachesTheGroundIsNotAVerifiedLanding() {
		// ab-21 regression, tightened by ab-24 audit section 5: the hold walks
		// level for its window and meets a rising plateau at glide speed. That
		// contact is not a VERIFIED landing (a landing needs a limited
		// horizontal impact speed and a descending contact), so the candidate
		// is refused — the viable alternatives are the hold paths that clear
		// the plateau, not a fast crash into it.
		double x = -959.8226691069283;
		double y = 88.81102913554756;
		double z = -64.4217371874942;
		double vx = 0.86975048427051;
		double vy = -0.4593681131566472;
		double vz = -1.4379383108271315;
		float yaw = -156.74776f;
		float pitch = 60f;
		FlightDynamics.State next = FlightDynamics.step(
				new FlightDynamics.State(x, y, z, vx, vy, vz, 0),
				new FlightDynamics.Input(yaw, pitch, false));
		double dirX = next.x - x;
		double dirZ = next.z - z;
		double length = Math.hypot(dirX, dirZ);
		dirX /= length;
		dirZ /= length;
		double plateauX = next.x + dirX * 15.0;
		double plateauZ = next.z + dirZ * 15.0;
		double wx = dirX;
		double wz = dirZ;
		FlightSession.BlockQuery blocks = (bx, by, bz) -> {
			double along = (bx - plateauX) * wx + (bz - plateauZ) * wz;
			double perp = (bx - plateauX) * (-wz) + (bz - plateauZ) * wx;
			if (along >= 0 && Math.abs(perp) <= 12.0 && by >= 61.0 && by <= 84.0)
				return "minecraft:grass_block";
			return by <= 60.0 ? "minecraft:stone" : "minecraft:air";
		};

		FlightSession session = session(blocks, x, y, z);
		FlightSession.Decision decision = tick(session, x, y, z, vx, vy, vz, yaw, pitch);
		assertFalse(decision.applicable,
			"a glide-speed contact with a rising support is not a verified landing: " + decision.note);
		// More candidates than the bounded terminal-verify budget: the tail is
		// unverified, not rejected, so the session stays alive (ab-24 audit 4).
		assertFalse(session.isDone());
		assertTrue(decision.note.contains("terminal_unverified"), decision.note);
	}

	@Test
	void aWaterContactPastTheNearTermWindowIsNotALanding() {
		// The same arrival that lands on a support plateau, but the far terrain
		// is water: a water surface is not a support, so the terminal action is
		// not provable and every candidate must be refused (B4/C5).
		double x = -959.8226691069283;
		double y = 88.81102913554756;
		double z = -64.4217371874942;
		double vx = 0.86975048427051;
		double vy = -0.4593681131566472;
		double vz = -1.4379383108271315;
		float yaw = -156.74776f;
		float pitch = 60f;
		FlightDynamics.State next = FlightDynamics.step(
				new FlightDynamics.State(x, y, z, vx, vy, vz, 0),
				new FlightDynamics.Input(yaw, pitch, false));
		double dirX = next.x - x;
		double dirZ = next.z - z;
		double length = Math.hypot(dirX, dirZ);
		dirX /= length;
		dirZ /= length;
		double plateauX = next.x + dirX * 15.0;
		double plateauZ = next.z + dirZ * 15.0;
		double wx = dirX;
		double wz = dirZ;
		FlightSession.BlockQuery blocks = (bx, by, bz) -> {
			double along = (bx - plateauX) * wx + (bz - plateauZ) * wz;
			double perp = (bx - plateauX) * (-wz) + (bz - plateauZ) * wx;
			if (along >= 0 && Math.abs(perp) <= 12.0 && by >= 61.0 && by <= 84.0)
				return "minecraft:water";
			return by <= 60.0 ? "minecraft:stone" : "minecraft:air";
		};

		FlightSession session = session(blocks, x, y, z);
		FlightSession.Decision decision = tick(session, x, y, z, vx, vy, vz, yaw, pitch);
		assertFalse(decision.applicable,
			"a water contact must not be accepted as a landing: " + decision.note);
		assertFalse(session.isDone());
		assertTrue(decision.note.contains("terminal_unverified"), decision.note);
	}

	@Test
	void segmentedArm19478ArrivalDoesNotHideTheObstacleJustPastTheTerminal() {
		// samples.csv segmented,19478 (last steer; terminal reached at t1).
		double x = -962.9308725957482;
		double y = 86.95904304584886;
		double z = -61.11276747301669;
		double vx = 0.8719539777042608;
		double vy = -0.8829445151056933;
		double vz = -1.1718539343713128;
		float yaw = -145.25066f;
		float pitch = 35.078495f;

		FlightSession clear = session(ground(), x, y, z);
		FlightSession.Decision clearDecision = tick(clear, x, y, z, vx, vy, vz, yaw, pitch);
		assertTrue(clearDecision.applicable, "a clear terminal must stay viable: " + clearDecision.note);
		assertEquals("hold", clearDecision.terminalAction,
				"the arrival must carry a verified terminal action: " + clearDecision.note);

		FlightSession blocked = session(wallBeyond(x, y, z, vx, vy, vz, yaw, pitch), x, y, z);
		FlightSession.Decision decision = tick(blocked, x, y, z, vx, vy, vz, yaw, pitch);
		assertFalse(decision.applicable,
				"an obstacle past the terminal must reject the candidate: " + decision.note);
		assertEquals(FlightSession.EndReason.NO_VIABLE_TRAJECTORY, blocked.endReason());
		assertTrue(decision.rejectKind.equals("collision") || decision.rejectKind.equals("terminal_action"),
				"the rejection must name the post-arrival failure: " + decision.note);
		assertTrue(decision.rejectY <= y - 0.5,
				"the rejection must lie past the arrival altitude: " + decision.note);
	}
}




