package dev.mcpfabric.client.flight;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightPlanningTest {
	@Test void failedWorkerReleasesItsPrefixBeforeTheAdoptionTick() throws Exception {
		// ROOT CAUSE: prefix-1-03 kept climbing for all twelve reserved ticks
		// after the worker had already returned no_sequence in 179 ms.
		// Unknown friction makes every landing invalid in this snapshot.
		FlightSession.BlockQuery noLanding = (x,y,z) -> y < 60 ? "stone" : "air";
		var planning = new FlightPlanning(session(), noLanding, List.of(new FlightSession.Waypoint(0,60.5,50)));
		var origin = new FlightSequence.Origin(new FlightDynamics.State(0,70,0,0,0,1,0),0,0);
		var input = new FlightDynamics.Input(0,0,false);
		try {
			for (int i=0;i<200;i++) planning.beforeTick(origin,0,System.nanoTime()+100_000_000L);
			planning.consider(origin,java.util.Collections.nCopies(20,input),0,"test",1,System.nanoTime()+100_000_000L);
			var next = new FlightSequence.Origin(FlightDynamics.step(origin.state(),input),0,0);
			long deadline = System.nanoTime()+2_000_000_000L;
			while (!planning.status().startsWith("failed_early") && System.nanoTime()<deadline) {
				planning.beforeTick(next,1,System.nanoTime()+100_000_000L);
				Thread.sleep(5);
			}
			assertTrue(planning.status().startsWith("failed_early"), planning.status());
			assertFalse(planning.canReserveShort(next, 1), "failure must not burn another request in the same slot");
			assertTrue(planning.canReserveShort(next, 12), "the next slot may use a fresh measured origin");
		} finally { planning.close(); }
	}
	@Test void shortReservationRequiresReadinessAndHandsOverAtEightTicks() {
		var planning = new FlightPlanning(session(), world, List.of(new FlightSession.Waypoint(0,60.5,50)));
		var origin = new FlightSequence.Origin(new FlightDynamics.State(0,70,0,0,0,1,0),0,0);
		// Eight reserved ticks plus four continuation ticks must suffice.
		// Requiring the normal twelve-tick lead plus four screened unused
		// controls after the short handoff and rejected valid bridges.
		var controls = java.util.Collections.nCopies(12,new FlightDynamics.Input(0,0,false));
		try {
			assertFalse(planning.reserveShort(origin,controls,0,"test",1,System.nanoTime()+100_000_000L));
			for (int i=0;i<200;i++) planning.beforeTick(origin,0,System.nanoTime()+100_000_000L);
			var boosted = new FlightSequence.Origin(new FlightDynamics.State(0,70,0,0,0,1,1),0,0);
			assertFalse(planning.canReserveShort(boosted,0));
			assertTrue(planning.reserveShort(origin,controls,0,"test",1,System.nanoTime()+100_000_000L));
			assertTrue(planning.status().contains("due=8"));
			assertFalse(planning.canReserveShort(origin,0));
			var next = new FlightSequence.Origin(FlightDynamics.step(origin.state(),controls.get(0)),0,0);
			assertNotNull(planning.beforeTick(next,1,System.nanoTime()+100_000_000L));
			assertTrue(planning.status().startsWith("reserved:1/8"));
			assertNull(planning.beforeTick(next,8,System.nanoTime()+100_000_000L));
			assertTrue(planning.status().startsWith("delivered"));
		} finally { planning.close(); }
	}
	@Test void reservationDoesNotExecuteTheCandidateTailAfterItsHandoff() {
		var planning = new FlightPlanning(session(), world, List.of(new FlightSession.Waypoint(0,60.5,50)));
		var origin = new FlightSequence.Origin(new FlightDynamics.State(0,70,0,0,0,1,0),0,0);
		var controls = new java.util.ArrayList<>(java.util.Collections.nCopies(20,new FlightDynamics.Input(0,0,false)));
		// The replacement sequence owns tick 12 onward. A later candidate
		// action is neither reserved nor sent to the background terminal plan.
		controls.set(12,new FlightDynamics.Input(0,0,true));
		try {
			for (int i=0;i<200;i++) planning.beforeTick(origin,0,System.nanoTime()+100_000_000L);
			planning.consider(origin,controls,0,"test",1,System.nanoTime()+100_000_000L);
			assertTrue(planning.status().startsWith("searching:"));
		} finally { planning.close(); }
	}
	@Test void intermediateExpiryCanHitACellMissedByNominalAndEndpointTrajectories() {
		// This cell is missed by expiry 0, 8 (nominal), and 20. Expiry 1
		// intersects it, so checking only nominal or both extremes is insufficient.
		FlightSession.BlockQuery blocks = (x,y,z) -> y < 60 || (Math.floor(x)==-1 && Math.floor(y)==73 && Math.floor(z)==13) ? "stone" : "air";
		var params = new FlightSession.Params(); params.stopAtEnd = true; params.terminalReach = 1;
		var pilot = new FlightSession(List.of(new FlightSession.Waypoint(0,70,0),new FlightSession.Waypoint(0,60.5,50)),
				blocks,System.currentTimeMillis()+60000,params,0);
		var origin = new FlightSequence.Origin(new FlightDynamics.State(0,70,0,0,-.2,.3,8),0,-20);
		assertFalse(pilot.planningPrefixClear(origin,
				java.util.Collections.nCopies(20,new FlightDynamics.Input(0,-20,false)),System.nanoTime()+100_000_000L));
		assertTrue(pilot.planningPrefixEvidence().contains("rejectedExpiry=1"));
	}
	@Test void reservationRejectsAClimbThatRequiresTheRocketToKeepBurning() {
		// ROOT CAUSE: a nominal eight-tick burn passes this climb, while an
		// immediate expiry loses the required floor reserve. Checking the next
		// actual tick does not justify reserving that nominal future today.
		var params = new FlightSession.Params(); params.stopAtEnd = true; params.terminalReach = 1;
		var pilot = new FlightSession(List.of(new FlightSession.Waypoint(0,65,0),new FlightSession.Waypoint(0,60.5,50)),
				world,System.currentTimeMillis()+60000,params,0);
		var origin = new FlightSequence.Origin(new FlightDynamics.State(0,65,0,0,-.2,.3,8),0,-20);
		assertFalse(pilot.planningPrefixClear(origin,
				java.util.Collections.nCopies(20,new FlightDynamics.Input(0,-20,false)),System.nanoTime()+100_000_000L));
		var planning = new FlightPlanning(pilot, world, List.of(new FlightSession.Waypoint(0,60.5,50)));
		try {
			for (int i=0;i<200;i++) planning.beforeTick(origin,0,System.nanoTime()+100_000_000L);
			planning.consider(origin,java.util.Collections.nCopies(20,new FlightDynamics.Input(0,-20,false)),
					0,"test",1,System.nanoTime()+100_000_000L);
			assertTrue(planning.status().startsWith("prefix_expiry_refused"));
		} finally { planning.close(); }
	}
	@Test void existingBoostCanExpireInsideTheVerifiedReservation() {
		// ROOT CAUSE: online-13/14 entered the last bend with 7-15 boost ticks.
		// Waiting for zero prevented any background request before route refusal.
		var pilot = session();
		var planning = new FlightPlanning(pilot, world, List.of(new FlightSession.Waypoint(0,60.5,50)));
		var origin = new FlightSequence.Origin(new FlightDynamics.State(0,70,0,0,0,1,8),0,0);
		var input = new FlightDynamics.Input(0,0,false);
		try {
			for (int i=0;i<200;i++) planning.beforeTick(origin,0,System.nanoTime()+100_000_000L);
			planning.consider(origin,java.util.Collections.nCopies(20,input),0,"test",1,System.nanoTime()+100_000_000L);
			assertTrue(planning.status().startsWith("searching:"));
			assertTrue(planning.status().contains("branches=13 rejectedExpiry=-1"));
			var next = new FlightSequence.Origin(FlightDynamics.step(origin.state(),input),0,0);
			var action = planning.beforeTick(next,1,System.nanoTime()+100_000_000L);
			assertNotNull(action);
			assertFalse(action.useRocket);
			// ROOT CAUSE: online-16 lost its attached rocket before the nominal
			// countdown ended. A collision-free prefix still predicts the wrong
			// worker origin. Release that reservation in this tick, not at delivery.
			var s = next.state();
			var expired = new FlightSequence.Origin(new FlightDynamics.State(s.x,s.y,s.z,s.vx,s.vy,s.vz,0),0,0);
			assertNull(planning.beforeTick(expired,2,System.nanoTime()+100_000_000L));
			assertTrue(planning.status().contains("boost_changed"));
		} finally {planning.close();}
	}
	private final FlightSession.BlockQuery world = new FlightSession.BlockQuery() {
		public String idAt(double x, double y, double z) { return y < 60 ? "stone" : "air"; }
		public double frictionAt(double x, double y, double z) { return .6; }
	};
	@Test void liveRocketPastNominalExpiryDoesNotSpendWorkerRequests() {
		var planning = new FlightPlanning(session(), world, List.of(new FlightSession.Waypoint(0,60.5,50)));
		var origin = new FlightSequence.Origin(new FlightDynamics.State(0,70,0,0,0,1,1),0,0);
		try {
			for (int i=0;i<200;i++) planning.beforeTick(origin,0,System.nanoTime()+100_000_000L);
			for (int tick=0;tick<10;tick++) planning.consider(origin,java.util.Collections.nCopies(20,new FlightDynamics.Input(0,0,false)),tick,"test",1,System.nanoTime()+100_000_000L);
			assertTrue(planning.status().startsWith("waiting_boost_expiry"));
			var expired = new FlightSequence.Origin(new FlightDynamics.State(0,70,0,0,0,1,0),0,0);
			planning.consider(expired,java.util.Collections.nCopies(20,new FlightDynamics.Input(0,0,false)),10,"test",1,System.nanoTime()+100_000_000L);
			assertTrue(planning.status().startsWith("searching:1"));
		} finally { planning.close(); }
	}
	private FlightSession session() {
		var p = new FlightSession.Params(); p.stopAtEnd = true; p.terminalReach = 1;
		return new FlightSession(List.of(new FlightSession.Waypoint(0, 65, 0),
				new FlightSession.Waypoint(0, 60.5, 20)), world, System.currentTimeMillis() + 60000, p, 0);
	}
	@Test void closedPlannerNeverQueriesTheLiveWorld() {
		var pilot = session();
		var planning = new FlightPlanning(pilot, (x,y,z) -> { fail("closed planner queried world"); return null; },
				List.of(new FlightSession.Waypoint(0,60.5,20)));
		planning.close();
		assertNull(planning.beforeTick(new FlightSequence.Origin(new FlightDynamics.State(0,65,0,0,0,1,0),0,0),
				0, System.nanoTime()+100_000_000L));
		assertTrue(planning.status().startsWith("closed"));
	}
	@Test void reservedPrefixRejectsWorldContactAndAnExpiredBudget() {
		var pilot = session();
		var origin = new FlightSequence.Origin(new FlightDynamics.State(0,65,0,0,0,1,0),0,0);
		assertFalse(pilot.planningPrefixClear(origin,List.of(new FlightDynamics.Input(0,0,false)),System.nanoTime()-1));
		var blocked = new FlightSequence.Origin(new FlightDynamics.State(0,59,0,0,0,1,0),0,0);
		assertFalse(pilot.planningPrefixClear(blocked,List.of(new FlightDynamics.Input(0,0,false)),System.nanoTime()+100_000_000L));
	}
	@Test void reservationUsesTheSameDescentToleranceAsTheScreenedCandidate() {
		// ROOT CAUSE: an absolute four-block reserve cancelled an already
		// screened prefix on its next exact-model tick. Cruise screening allows
		// the current altitude minus its bounded descent tolerance instead.
		var pilot = session();
		var origin = new FlightSequence.Origin(new FlightDynamics.State(0,62.8,0,0,0,1,0),0,0);
		assertTrue(pilot.planningPrefixClear(origin,List.of(new FlightDynamics.Input(0,0,false)),System.nanoTime()+100_000_000L));
	}
}
