package dev.mcpfabric.client.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ownership regressions (ab-23 repair plan, batch A2/C3): the route outcome
 * and the physical control phase are separate facts, a recovery keeps a live
 * control claim, a new route is refused with {@code recovering} while the
 * recovery window is open, and the 6 s policy window never extends the hard
 * control deadline.
 */
class FlightOwnershipTest {

	private static final long WINDOW_MS = 6_000;
	private static final long HARD_DEADLINE_MS = 30_000;

	@Test
	void aFailureFreezesTheOutcomeAndEntersRecover() {
		FlightOwnership ownership = new FlightOwnership();
		ownership.startRoute(HARD_DEADLINE_MS);
		ownership.markTrack();
		ownership.routeFailed("no_viable_trajectory", 1_000, WINDOW_MS, HARD_DEADLINE_MS);

		assertEquals(FlightOwnership.RouteOutcome.FAILED, ownership.routeOutcome());
		assertEquals(FlightOwnership.Phase.RECOVER, ownership.phase());
		assertEquals("no_viable_trajectory", ownership.failureReason());
		assertEquals(1_000, ownership.recoveryStartedMs());
		assertEquals(7_000, ownership.recoveryUntilMs());
		assertEquals(HARD_DEADLINE_MS, ownership.hardDeadlineMs());
	}

	@Test
	void settlingKeepsTheFailureAndTheWindow() {
		FlightOwnership ownership = new FlightOwnership();
		ownership.startRoute(HARD_DEADLINE_MS);
		ownership.routeFailed("no_viable_trajectory", 1_000, WINDOW_MS, HARD_DEADLINE_MS);
		ownership.settle();

		assertEquals(FlightOwnership.RouteOutcome.FAILED, ownership.routeOutcome());
		assertEquals(FlightOwnership.Phase.SETTLED, ownership.phase());
		assertFalse(ownership.recovering());
		assertFalse(ownership.hasLiveControl(2_000));
	}

	@Test
	void aSecondFailureDoesNotRewriteTheFirstOne() {
		FlightOwnership ownership = new FlightOwnership();
		ownership.startRoute(HARD_DEADLINE_MS);
		ownership.routeFailed("no_viable_trajectory", 1_000, WINDOW_MS, HARD_DEADLINE_MS);
		ownership.routeFailed("damage", 2_000, WINDOW_MS, HARD_DEADLINE_MS);

		assertEquals("no_viable_trajectory", ownership.failureReason());
		assertEquals(2_000, ownership.recoveryStartedMs(), "the window restarts for the new recovery attempt");
	}

	@Test
	void aNewRouteIsRefusedWithRecoveringWhileTheHardDeadlineHolds() {
		FlightOwnership ownership = new FlightOwnership();
		ownership.startRoute(HARD_DEADLINE_MS);
		ownership.routeFailed("no_viable_trajectory", 1_000, WINDOW_MS, HARD_DEADLINE_MS);

		assertEquals("recovering", ownership.acceptBlockReason(1_000));
		assertEquals("recovering", ownership.acceptBlockReason(6_999));
		// Past the soft window but inside the hard deadline the refusal stays:
		// the old expectation here encoded the ownership leak (ab-24 audit).
		assertEquals("recovering", ownership.acceptBlockReason(7_001));
		assertNull(ownership.acceptBlockReason(HARD_DEADLINE_MS + 1));
	}

	@Test
	void theRecoveryWindowKeepsALiveControlClaim() {
		FlightOwnership ownership = new FlightOwnership();
		ownership.startRoute(HARD_DEADLINE_MS);
		ownership.routeFailed("no_viable_trajectory", 1_000, WINDOW_MS, HARD_DEADLINE_MS);

		assertTrue(ownership.hasLiveControl(6_999));
		assertTrue(ownership.hasLiveControl(7_001), "the soft window is not a release proof");
		assertFalse(ownership.hasLiveControl(HARD_DEADLINE_MS + 1));
	}

	@Test
	void thePolicyWindowRearmsInsideTheHardDeadline() {
		FlightOwnership ownership = new FlightOwnership();
		ownership.startRoute(10_000);
		ownership.routeFailed("no_viable_trajectory", 1_000, WINDOW_MS, 10_000);

		// The 6 s window ends at 7 000; the hard deadline still holds, so the
		// recovery re-arms instead of releasing (C3) — and never past 10 000.
		assertTrue(ownership.rearmRecovery(7_000, WINDOW_MS));
		assertEquals(10_000, ownership.recoveryUntilMs());
		assertTrue(ownership.recovering());
		assertTrue(ownership.hasLiveControl(9_999));
		assertFalse(ownership.hasLiveControl(10_001));
		// With only the hard deadline left there is nothing to re-arm.
		assertFalse(ownership.rearmRecovery(10_001, WINDOW_MS));
	}

	@Test
	void theHardDeadlineReleaseCarriesATypedReason() {
		FlightOwnership ownership = new FlightOwnership();
		ownership.startRoute(10_000);
		ownership.routeFailed("no_viable_trajectory", 1_000, WINDOW_MS, 10_000);
		ownership.release("control_expired_airborne");

		assertEquals(FlightOwnership.Phase.RELEASED, ownership.phase());
		assertEquals("control_expired_airborne", ownership.releaseReason());
		assertEquals(FlightOwnership.RouteOutcome.FAILED, ownership.routeOutcome(),
				"an expired control does not rewrite the route outcome");
	}

	@Test
	void theSoftWindowExpiryKeepsOwnershipWhileTheHardDeadlineHolds() {
		// ab-24 audit section 3, reproduced directly: at 7001 the soft window
		// (until 7000) is over but the hard deadline (30000) is not. Ownership
		// and the new-route refusal must both survive.
		FlightOwnership ownership = new FlightOwnership();
		ownership.startRoute(30_000);
		ownership.routeFailed("no_viable_trajectory", 1_000, WINDOW_MS, 30_000);

		assertEquals(7_000, ownership.recoveryUntilMs());
		assertTrue(ownership.hasLiveControl(7_001), "the hard deadline still holds the aircraft");
		assertEquals("recovering", ownership.acceptBlockReason(7_001));
		assertTrue(ownership.recoveryPolicyExpired(7_001), "the soft window asks for a re-decision");
		assertFalse(ownership.hasLiveControl(30_001), "only the hard deadline ends the claim");
		assertNull(ownership.acceptBlockReason(30_001));
	}

	@Test
	void landingKeepsOwnershipPastTheSoftWindowToo() {
		FlightOwnership ownership = new FlightOwnership();
		ownership.startRoute(30_000);
		ownership.routeFailed("no_viable_trajectory", 1_000, WINDOW_MS, 30_000);
		assertTrue(ownership.enterLanding());

		assertTrue(ownership.hasLiveControl(7_001), "LAND has no periodic re-arm, so it must not depend on the soft window");
		assertEquals("landing", ownership.acceptBlockReason(7_001));
	}

	@Test
	void aFailedLandingLegReturnsToRecoveryInsteadOfReleasing() {
		FlightOwnership ownership = new FlightOwnership();
		ownership.startRoute(30_000);
		ownership.routeFailed("no_viable_trajectory", 1_000, WINDOW_MS, 30_000);
		assertTrue(ownership.enterLanding());

		assertTrue(ownership.returnToRecovery(8_000, WINDOW_MS));
		assertEquals(FlightOwnership.Phase.RECOVER, ownership.phase());
		assertEquals(14_000, ownership.recoveryUntilMs());
		assertTrue(ownership.hasLiveControl(9_000));
		assertFalse(ownership.returnToRecovery(30_001, WINDOW_MS),
				"the hard deadline cannot be re-armed past");
	}

	@Test
	void revokeCancelsTheRecovery() {
		FlightOwnership ownership = new FlightOwnership();
		ownership.startRoute(HARD_DEADLINE_MS);
		ownership.routeFailed("no_viable_trajectory", 1_000, WINDOW_MS, HARD_DEADLINE_MS);
		ownership.revoke();

		assertEquals(FlightOwnership.RouteOutcome.REVOKED, ownership.routeOutcome());
		assertEquals(FlightOwnership.Phase.RELEASED, ownership.phase());
		assertEquals("revoked", ownership.releaseReason());
		assertNull(ownership.acceptBlockReason(1_100));
		assertFalse(ownership.hasLiveControl(1_100));
	}

	@Test
	void completingARouteDoesNotBlockALaterFailure() {
		FlightOwnership ownership = new FlightOwnership();
		ownership.startRoute(HARD_DEADLINE_MS);
		ownership.markTrack();
		ownership.markHold();
		ownership.routeCompleted();

		assertEquals(FlightOwnership.RouteOutcome.COMPLETED, ownership.routeOutcome());
		ownership.routeFailed("touchdown_failure", 5_000, WINDOW_MS, HARD_DEADLINE_MS);
		assertEquals(FlightOwnership.RouteOutcome.COMPLETED, ownership.routeOutcome(),
				"a completed route is not rewritten by a later failure");
		assertEquals(FlightOwnership.Phase.RECOVER, ownership.phase());
	}

	@Test
	void phaseAdvancesPrepareTrackHold() {
		FlightOwnership ownership = new FlightOwnership();
		ownership.startRoute(HARD_DEADLINE_MS);
		assertEquals(FlightOwnership.Phase.PREPARE, ownership.phase());
		ownership.markTrack();
		assertEquals(FlightOwnership.Phase.TRACK, ownership.phase());
		ownership.markHold();
		assertEquals(FlightOwnership.Phase.HOLD, ownership.phase());
	}
}
