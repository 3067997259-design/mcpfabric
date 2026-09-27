package dev.mcpfabric.client.flight;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightRocketTest {
	@Test void recipeLevelsHaveRandomLifetimesRatherThanFixedSeconds() {
		assertEquals(21, FlightRocket.minimumTicks(1));
		assertEquals(32, FlightRocket.maximumTicks(1));
		assertEquals(31, FlightRocket.minimumTicks(2));
		assertEquals(42, FlightRocket.maximumTicks(2));
		assertEquals(41, FlightRocket.minimumTicks(3));
		assertEquals(52, FlightRocket.maximumTicks(3));
		assertEquals(11, FlightRocket.minimumTicks(0));
	}
	@Test void aLiveRocketNeverBecomesUnpoweredFromAnExpiredEstimate() {
		assertEquals(1, FlightRocket.remainingEstimate(1, 100));
		assertEquals(1, FlightRocket.remainingEstimate(3, 100));
	}
	@Test void ignitionUsesSelectedRecipeAndKeepsThatDurationThroughLaterTicks() {
		var state = new FlightDynamics.State(0,80,0,0,0,1,0);
		var shortBoost = FlightDynamics.step(state,new FlightDynamics.Input(0,0,true,FlightRocket.nominalTicks(1)));
		var longBoost = FlightDynamics.step(state,new FlightDynamics.Input(0,0,true,FlightRocket.nominalTicks(3)));
		assertEquals(20,longBoost.rocketTicksRemaining-shortBoost.rocketTicksRemaining);
		var next = FlightDynamics.step(longBoost,new FlightDynamics.Input(0,0,false));
		assertEquals(longBoost.rocketTicksRemaining-1,next.rocketTicksRemaining);
	}
}
