package dev.mcpfabric.client.flight;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group 2 (segmented semantics) and group 3 (timing perturbations) of the R4
 * client review 2026-09-21, at the rule level the controller actually uses:
 * early-ready routes switch without an unmotivated hold, late-but-connectable
 * routes join, stale or unconnectable ones fail explicitly, and duplicate,
 * stale or out-of-order deliveries never replace a newer valid route.
 */
class FlightHandoverRulesTest {

	private static final FlightSession.BlockQuery RIVER = (x, y, z) ->
			y <= 62 ? "minecraft:water" : "minecraft:air";

	private static FlightSession session(double z) {
		return new FlightSession(
				List.of(new FlightSession.Waypoint(0, 70, z), new FlightSession.Waypoint(0, 70, z + 40)),
				RIVER,
				System.currentTimeMillis() + 60_000,
				new FlightSession.Params());
	}

	private static FlightHandover.PilotState pilot(double x, double y, double z, double speed, boolean gliding) {
		return new FlightHandover.PilotState(x, y, z, 0, speed, gliding, false);
	}

	@Test
	void anEarlyReadyRouteSwitchesAsSoonAsItsEntryIsWithinTwoReaches() {
		// Group 2: an early-arriving next leg must not wait for the current
		// path to complete and fall into a hold. The current session is still
		// flying; the pending entry is within two entry reaches.
		FlightHandover handover = new FlightHandover();
		FlightSession current = session(0);
		FlightSession pending = session(30);
		assertTrue(handover.offer("leg-2", 2, pending, 0, 1));
		// Distance to the pending entry (0,70,30) is 12 blocks < 2*entryReach.
		assertEquals(FlightHandover.Action.ADOPT, handover.decide(pilot(0, 70, 18, 1.2, true), current, 0, false));
	}

	@Test
	void aLateButConnectableRouteJoinsAfterCompletion() {
		// Group 3: the next leg arrives after the path completed but inside
		// the hold's wait. Connectable + currentDone must adopt, not time out.
		FlightHandover handover = new FlightHandover();
		FlightSession pending = session(20);
		assertTrue(handover.offer("leg-2", 2, pending, 0, 1));
		assertEquals(FlightHandover.Action.ADOPT, handover.decide(pilot(0, 70, 10, 1.0, true), null, 5_000, true));
	}

	@Test
	void anUnconnectableRouteAtCompletionIsDropped() {
		// Group 3: once the current path is complete, a pending route that
		// cannot be connected must be dropped explicitly (the hold must not
		// wait on it), so the timeout owns the ending.
		FlightHandover handover = new FlightHandover();
		assertTrue(handover.offer("leg-2", 2, session(20), 0, 1));
		// 13 blocks below the entry: above the glide envelope.
		assertEquals(FlightHandover.Action.DROP, handover.decide(pilot(0, 60, 10, 1.0, true), null, 1_000, true));
	}

	@Test
	void aStaleRouteExpiresAndIsDropped() {
		FlightHandover handover = new FlightHandover();
		assertTrue(handover.offer("leg-2", 2, session(10), 0, 1));
		assertEquals(FlightHandover.Action.DROP,
				handover.decide(pilot(0, 70, 0, 1.0, true), session(0), FlightHandover.MAX_AGE_MS + 1, false));
	}

	@Test
	void aDuplicateDeliveryKeepsTheNewerPendingRoute() {
		FlightHandover handover = new FlightHandover();
		FlightSession newer = session(40);
		assertTrue(handover.offer("leg-2", 2, newer, 0, 1));
		// A repeated delivery of the same id must not replace it.
		assertFalse(handover.offer("leg-2", 2, session(80), 100, 1));
		assertEquals(2, handover.pendingRevision());
		assertNotNull(handover.pending());
	}

	@Test
	void anOutOfOrderOlderRevisionKeepsTheNewerPendingRoute() {
		FlightHandover handover = new FlightHandover();
		assertTrue(handover.offer("leg-3", 3, session(40), 0, 1));
		assertFalse(handover.offer("leg-2", 2, session(10), 100, 1));
		assertEquals(3, handover.pendingRevision());
	}

	@Test
	void aNewerRevisionReplacesThePendingRoute() {
		FlightHandover handover = new FlightHandover();
		assertTrue(handover.offer("leg-2", 2, session(20), 0, 1));
		assertTrue(handover.offer("leg-3", 3, session(40), 100, 1));
		assertEquals(3, handover.pendingRevision());
	}

	@Test
	void aRevisionNotNewerThanTheFlyingSessionIsRefused() {
		FlightHandover handover = new FlightHandover();
		assertFalse(handover.offer("leg-1", 1, session(20), 0, 1));
	}

	@Test
	void aWaterStateCannotConnect() {
		FlightHandover handover = new FlightHandover();
		assertTrue(handover.offer("leg-2", 2, session(30), 0, 1));
		FlightHandover.PilotState inWater = new FlightHandover.PilotState(0, 70, 18, 0, 1.2, true, true);
		assertEquals(FlightHandover.Action.KEEP, handover.decide(inWater, session(0), 0, false));
	}
}
