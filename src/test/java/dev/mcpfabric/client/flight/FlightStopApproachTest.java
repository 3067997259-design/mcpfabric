package dev.mcpfabric.client.flight;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightStopApproachTest {
    @Test
    void stopIgnitionCannotHideItsLastBoostTicksPastTheScreeningHorizon() {
        // ROOT CAUSE: terminal-01 accepted a 35-tick ignition after checking
        // only 20 ticks. Twelve ticks later every continuation was rejected.
        var params = new FlightSession.Params();
        params.stopAtEnd = true;
        params.terminalReach = 1;
        params.simBudgetMs = 1_000;
        var flight = new FlightSession(List.of(new FlightSession.Waypoint(0,68,0),
                new FlightSession.Waypoint(50,68,0)),
                (x,y,z) -> x >= 52 || Math.abs(z) >= 2 ? "stone" : y < 64 ? "water" : "air",
                System.currentTimeMillis() + 60_000, params, 0);
        // The historical 35-tick model represented a tier-two rocket. Keep
        // this long-burn regression independent of the default tier-one recipe.
        flight.setRocketFlightDuration(2);
        var action = flight.tick(0,68,0,.3,-.1,0,-90,0,64,0,0,false);
        assertFalse(action.applicable && action.fireRocket,
                "An ignition cannot be approved while its powered terminal is unverified: " + action.chosenPolicy);
    }

    @Test
    void terminalRadiusDoesNotInheritTheCruiseLookaheadDistance() {
        var params = new FlightSession.Params();
        params.stopAtEnd = true;
        params.entryReach = 6;
        params.terminalReach = 1;
        params.horizonTicks = 1;
        params.simBudgetMs = 1_000;
        var flight = new FlightSession(List.of(new FlightSession.Waypoint(0,70,0),
                new FlightSession.Waypoint(20,70,0)), (x,y,z) -> y < 64 ? "stone" : "air",
                System.currentTimeMillis() + 60_000, params, 5_000);
        var before = flight.tick(18,70,0,.3,0,0,-90,0,0,0,0,false);
        assertFalse(flight.isDone(), "Being inside the six-block cruise sphere is not final arrival");
        assertEquals(1, before.predictedEndCursor, "Prediction must use the same final radius");
        flight.tick(19.2,70,0,.3,0,0,-90,0,0,0,1,false);
        assertTrue(flight.isDone());
        assertEquals(FlightSession.EndReason.CHANNEL_COMPLETE, flight.endReason());
    }

    private FlightSession session(boolean stop, String surface) {
        var params = new FlightSession.Params();
        params.stopAtEnd = stop;
        params.simBudgetMs = 1_000;
        return new FlightSession(List.of(new FlightSession.Waypoint(0, 69.5, 0),
                new FlightSession.Waypoint(20, 67.5, 0), new FlightSession.Waypoint(40, 66.5, 0)),
                (x,y,z) -> y < 65 ? surface : "air", System.currentTimeMillis() + 60_000, params, 5_000);
    }

    @Test
    void slowFinalDescentDoesNotRequireCruiseClearanceAboveSolidGround() {
        // ROOT CAUSE: extension-05 rejected every unpowered descent on the
        // four-block cruise floor, although the body sweep was clear. The
        // thrust alternatives reached the stop too fast for recovery.
        var flight = session(true, "stone");
        var action = flight.tick(0, 69.5, 0, .6, -.1, 0, -90, 0, 64, 0, 0, false);
        assertTrue(action.applicable, action.note);
        assertFalse(action.fireRocket, action.chosenPolicy);
    }

    @Test
    void waterDoesNotBecomeALandingSurfaceForTheApproach() {
        var action = session(true, "water").tick(0, 69.5, 0, .6, -.1, 0, -90, 0, 0, 0, 0, false);
        assertFalse(action.applicable);
    }

    @Test
    void aThroughLegKeepsItsCruiseContinuationRule() {
        var action = session(false, "stone").tick(0, 69.5, 0, .6, -.1, 0, -90, 0, 0, 0, 0, false);
        assertFalse(action.applicable);
    }
}
