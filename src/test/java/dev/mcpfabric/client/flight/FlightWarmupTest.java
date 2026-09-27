package dev.mcpfabric.client.flight;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FlightWarmupTest {
	@Test void constructionDoesNotUseTheLiveWorldForSyntheticWarmup() {
		// ROOT CAUSE: the old warm-up ran partial policies against the caller's
		// terrain, but left ordering and full decision assembly cold.
		int[] reads = {0};
		new FlightSession(List.of(new FlightSession.Waypoint(0,70,0),new FlightSession.Waypoint(0,70,150)),
				(x,y,z) -> { reads[0]++; return y < 60 ? "stone" : "air"; },
				System.currentTimeMillis()+60000,new FlightSession.Params(),0);
		assertEquals(0,reads[0]);
	}
}
