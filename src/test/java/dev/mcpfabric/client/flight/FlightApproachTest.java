package dev.mcpfabric.client.flight;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightApproachTest {
	@Test void distinctSteeringOffsetsAreNotMergedByDisplayRounding() {
		// ROOT CAUSE: formatted deduplication rounded distinct controls to one
		// decimal place. Preserve exact policies while removing exact duplicates.
		var p = new FlightSession.Params();
		p.yawOffsets = new float[] {0, 0.04f, 0.04f};
		p.simBudgetMs = 1000;
		var session = new FlightSession(List.of(new FlightSession.Waypoint(0,80,0),
				new FlightSession.Waypoint(0,80,200)), (x,y,z) -> y < 60 ? "stone" : "air",
				System.currentTimeMillis()+60000,p,0);
		var decision = session.tick(0,80,0,0,0,1,0,0,0,0,0,false);
		assertEquals(8, decision.evaluated);
		assertFalse(decision.budgetExhausted);
	}
	@Test void clearSupportedFinalApproachStillPermitsDescent() {
		var p = new FlightSession.Params(); p.stopAtEnd = true; p.terminalReach = 1;
		var session = new FlightSession(List.of(new FlightSession.Waypoint(0,67,0),new FlightSession.Waypoint(0,64.5,20)),
				(x,y,z) -> y < 64 ? "stone" : "air",System.currentTimeMillis()+60000,p,0);
		var origin = new FlightSequence.Origin(new FlightDynamics.State(0,67,0,0,-.2,.3,0),0,0);
		assertTrue(session.planningPrefixClear(origin,java.util.Collections.nCopies(3,new FlightDynamics.Input(0,0,false)),
				System.nanoTime()+100_000_000L));
	}
	@Test void nearbySupportedGoalBehindAWallDoesNotAuthorizeDescent() {
		// ROOT CAUSE: support at both ends was treated as a final approach,
		// although an intervening wall still required altitude and manoeuvring.
		var p = new FlightSession.Params();
		p.stopAtEnd = true; p.terminalReach = 1; p.simBudgetMs = 1000;
		FlightSession.BlockQuery world = (x,y,z) -> y < 64 || (z >= 10 && z < 12 && y < 70) ? "stone" : "air";
		var route = List.of(new FlightSession.Waypoint(0,67,0),new FlightSession.Waypoint(0,64.5,20));
		var session = new FlightSession(route,world,System.currentTimeMillis()+60000,p,0);
		var origin = new FlightSequence.Origin(new FlightDynamics.State(0,67,0,0,-.2,.3,0),0,0);
		assertFalse(session.planningPrefixClear(origin,java.util.Collections.nCopies(3,new FlightDynamics.Input(0,0,false)),
				System.nanoTime()+100_000_000L));
	}
}
