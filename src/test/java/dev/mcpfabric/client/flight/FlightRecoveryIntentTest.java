package dev.mcpfabric.client.flight;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Recovery must execute its landing intent, not silently replace it with level flight. */
class FlightRecoveryIntentTest {
    @Test
    void stopApproachStillUsesThrustWhenEveryGlideLosesWaterClearance() {
        var params = new FlightSession.Params();
        params.stopAtEnd = true;
        params.simBudgetMs = 1_000;
        var session = new FlightSession(List.of(new FlightSession.Waypoint(0, 66, 0),
                new FlightSession.Waypoint(0, 64, 80)), (x, y, z) -> y <= 62 ? "water" : "air",
                System.currentTimeMillis() + 60_000, params, 5_000);
        var decision = session.tick(0, 66, 0, .5, -.1, -.4, 180, -6, 64, 0, 0, false);
        assertTrue(decision.applicable, decision.note);
        assertTrue(decision.fireRocket, "The approach preference must not forbid necessary thrust");
    }

    @Test
    void stopApproachPreservesAGlideInsteadOfBuyingProgressWithANewBoost() {
        // ROOT CAUSE: the cruise progress ranking ignited another 35-tick
        // boost just before the cave approach. A viable glide lost because
        // it covered less distance inside the short prediction window.
        var params = new FlightSession.Params();
        params.stopAtEnd = true;
        params.simBudgetMs = 1_000;
        var session = new FlightSession(List.of(new FlightSession.Waypoint(0, 70, 0),
                new FlightSession.Waypoint(80, 70, 0)), (x, y, z) -> y < 64 ? "stone" : "air",
                System.currentTimeMillis() + 60_000, params, 5_000);
        var decision = session.tick(0, 70, 0, 1.4, 0, 0, -90, 0, 64, 0, 0, false);
        assertTrue(decision.applicable, decision.note);
        assertFalse(decision.fireRocket, "A safe glide must win in the final boost-and-screening window");
    }

    @Test
    void finalStopPredictsRecoveryInsteadOfFlyingStraightBeyondTheArrival() {
        // ROOT CAUSE: a stop route switches to recovery at arrival, but its
        // screening rollout kept flying the route policy for six more ticks.
        // A narrow wall past the endpoint then rejected a safe sideways exit.
        var params = new FlightSession.Params();
        params.stopAtEnd = true;
        params.yawOffsets = new float[] { 0 };
        params.entryReach = 6;
        params.simBudgetMs = 1_000;
        var session = new FlightSession(List.of(new FlightSession.Waypoint(0, 70, 0),
                new FlightSession.Waypoint(14, 70, 0)),
                (x, y, z) -> y < 64 || y >= 75 || x >= 14 && Math.abs(z) < 1 ? "stone" : "air",
                System.currentTimeMillis() + 60_000, params, 5_000);
        var decision = session.tick(0, 70, 0, 1.4, 0, 0, -90, 0, 0, 0, 0, false);
        assertTrue(decision.applicable, decision.note);
        assertEquals("recover", decision.terminalAction);
        var noGrace = new FlightSession(List.of(new FlightSession.Waypoint(0, 70, 0),
                new FlightSession.Waypoint(14, 70, 0)),
                (x, y, z) -> y < 64 || y >= 75 || x >= 14 && Math.abs(z) < 1 ? "stone" : "air",
                System.currentTimeMillis() + 60_000, params, 0);
        var sameStop = noGrace.tick(0, 70, 0, 1.4, 0, 0, -90, 0, 0, 0, 0, false);
        assertEquals(decision.terminalAction, sameStop.terminalAction,
                "A stop must verify recovery even when no handover grace was requested");
    }

    @Test
    void anExplicitFinalStopDoesNotSpendFiveSecondsFlyingPastItsEndpoint() {
        var params = new FlightSession.Params();
        params.stopAtEnd = true;
        var session = new FlightSession(List.of(new FlightSession.Waypoint(0, 70, 0),
                new FlightSession.Waypoint(10, 70, 0)), (x, y, z) -> y < 64 ? "stone" : "air",
                System.currentTimeMillis() + 60_000, params, 5_000);
        session.tick(10, 70, 0, 1, 0, 0, -90, 0, 0, 0, 0, false);
        assertTrue(session.isDone());
        assertFalse(session.isHolding());
        assertEquals(FlightSession.EndReason.CHANNEL_COMPLETE, session.endReason());
    }

    private FlightSession pilot(FlightSession.BlockQuery query) {
        return new FlightSession(List.of(new FlightSession.Waypoint(0, 70, 0),
                new FlightSession.Waypoint(100, 70, 0)), new FlightSession.BlockQuery() {
                    public String idAt(double x, double y, double z) { return query.idAt(x, y, z); }
                    public double frictionAt(double x, double y, double z) { return .6; }
                },
                System.currentTimeMillis() + 60_000, new FlightSession.Params(), 0);
    }

    @Test
    void clearRecoveryKeepsTheRequestedDescentAndHeading() {
        // ROOT CAUSE: the controller computed a landing heading and pitch but
        // called a search that only knew the current attitude. LEVEL won ties.
        var session = pilot((x, y, z) -> y < 64 ? "stone" : "air");
        var frame = session.recoveryFrame(0, 90, 0, 1, 0, 0, 0, 8,
                -90, 0, -78, 8, 14);
        assertTrue(frame.verified > 0);
        assertEquals(-78, frame.yaw, .01);
        assertEquals(8, frame.pitch, .01);
        assertFalse(frame.fire);
    }

    @Test
    void descentCanFinishOnSolidGroundWithoutCallingContactAnEmergency() {
        var session = pilot((x, y, z) -> y < 64 ? "stone" : "air");
        var frame = session.recoveryFrame(0, 64.3, 0, .35, -.12, 0, 0, 0,
                -90, 0, -90, 8, 14);
        assertTrue(frame.verified > 0);
        assertTrue(frame.landingContact);
        assertFalse(frame.emergency);
    }

    @Test
    void slowLandingFitsAThreeBlockPadAfterGroundFriction() {
        var session = pilot((x, y, z) -> y < 64 && x >= -1 && x < 2 && z >= -1 && z < 2 ? "stone" : "air");
        var frame = session.recoveryFrame(-.2, 64.1, .5, .4, -.12, 0, 0, 0,
                -90, 0, -90, 0, 14);
        assertTrue(frame.landingContact, "Ground runout must include friction after contact");
        assertFalse(frame.emergency);
    }

    @Test
    void missingFrictionOrANarrowLedgeCannotCertifyLanding() {
        var missing = new FlightSession(List.of(new FlightSession.Waypoint(0, 70, 0),
                new FlightSession.Waypoint(100, 70, 0)), (x, y, z) -> y < 64 ? "stone" : "air",
                System.currentTimeMillis() + 60_000, new FlightSession.Params(), 0);
        assertFalse(missing.recoveryFrame(-.2, 64.1, .5, .4, -.12, 0, 0, 0,
                -90, 0, -90, 0, 14).landingContact);
        var ledge = pilot((x, y, z) -> y < 64 && x < .5 ? "stone" : "air");
        assertFalse(ledge.recoveryFrame(-.2, 64.1, .5, .4, -.12, 0, 0, 0,
                -90, 0, -90, 0, 14).landingContact);
    }

    @Test
    void unverifiedRecoveryCannotCommitAnotherIrreversibleBoost() {
        var session = pilot((x, y, z) -> x >= 12 || Math.abs(z) >= 2 ? "stone" : y < 64 ? "water" : "air");
        var frame = session.recoveryFrame(0, 64.8, 0, .4, -.2, 0, 0, 64,
                -90, 0, -90, 8, 14);
        assertEquals(0, frame.verified);
        assertTrue(frame.emergency);
        assertFalse(frame.fire, "Delaying an inevitable contact is not a verified ignition");
    }

    @Test
    void waterNeverQualifiesAsLandingContact() {
        var session = pilot((x, y, z) -> y < 64 ? "water" : "air");
        var frame = session.recoveryFrame(0, 64.3, 0, .35, -.12, 0, 0, 0,
                -90, 0, -90, 8, 14);
        assertFalse(frame.landingContact);
    }

    @Test
    void obstacleAboveTheContactPatchStillRejectsIt() {
        var session = pilot((x, y, z) -> y < 64 || y >= 65 ? "stone" : "air");
        var frame = session.recoveryFrame(0, 64.3, 0, .35, -.12, 0, 0, 0,
                -90, 0, -90, 8, 14);
        assertEquals(0, frame.verified);
        assertFalse(frame.landingContact);
    }

    @Test
    void anUnsafeLandingBearingCannotRemoveTheStraightEscapeCandidate() {
        // The requested landing heading is sideways, beside a longitudinal
        // wall. Centering every fallback on that request removes straight.
        var session = pilot((x, y, z) -> y < 64 || z > 1.5 ? "stone" : "air");
        var frame = session.recoveryFrame(0, 70, 0, 1.4, 0, 0, 20, 0,
                -90, 0, 0, 8, 14);
        assertTrue(frame.verified > 0);
        assertFalse(frame.emergency);
        assertTrue(frame.yaw <= -90);
    }
}
