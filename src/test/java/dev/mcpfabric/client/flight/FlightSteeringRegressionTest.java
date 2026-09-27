package dev.mcpfabric.client.flight;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressions from cave-ab-27 and cave-ab-28, recorded on 2026-09-22. */
class FlightSteeringRegressionTest {
    private static final FlightSession.BlockQuery OPEN = (x, y, z) ->
            y < 55 ? "minecraft:stone" : "minecraft:air";

    private static FlightSession session(List<FlightSession.Waypoint> path) {
        FlightSession.Params params = new FlightSession.Params();
        params.simBudgetMs = 1_000;
        params.entryReach = 8;
        return new FlightSession(path, OPEN, System.currentTimeMillis() + 60_000, params, 0);
    }

    @Test
    void ab28TurnsTowardTheBearingAcrossTheAngleSeam() {
        // ROOT CAUSE:
        // ab-28 tick 2854: -95 toward +164 was treated as +259 degrees.
        // The short turn is -101 degrees, so a 25-degree step must be -120.
        assertEquals(-120f, FlightSession.limitYaw(-95f, 164f, 25f), .0001f);
        assertEquals(120f, FlightSession.limitYaw(95f, -164f, 25f), .0001f);
    }

    @Test
    void submittingAnAlreadyLimitedFrameDoesNotReverseItsTurn() {
        // ROOT CAUSE:
        // applyFrame limited an already simulated yaw a second time. Across
        // +/-180 the old subtraction could reverse that checked first step.
        float planned = FlightSession.limitYaw(176f, -158f, 25f);
        assertEquals(-159f, planned, .0001f);
        assertEquals(planned, FlightSession.limitYaw(176f, planned, 25f), .0001f);
    }

    @Test
    void yawNormalizationDoesNotChangeThePhysicalCommand() {
        assertEquals(FlightSession.limitYaw(-95f, 164f, 25f),
                FlightSession.limitYaw(265f, -196f, 25f), .0001f);
        assertEquals(-179f, FlightSession.limitYaw(179f, -179f, 25f), .0001f);
    }

    @Test
    void ab27AltitudeErrorDoesNotMakeThePilotChaseAPassedEntry() {
        // ROOT CAUSE:
        // ab-27 tick 3867 was horizontally past the descending waypoint.
        // Its +8.19 altitude error made the 3D plane say "not yet passed".
        // Yaw then snapped backward by 128.8 degrees toward the old entry.
        FlightSession flight = session(List.of(
                new FlightSession.Waypoint(-996.5, 64.5, 36.5),
                new FlightSession.Waypoint(-996.5, 63.5, 31.5),
                new FlightSession.Waypoint(-1000.5, 63.5, 19.5)));
        FlightSession.Decision decision = flight.tick(
                -997.4917796564323, 71.68710962257042, 30.482078889520324,
                -.8977444937598, -.03155650838972399, -1.251389923997621,
                -173.0617f, 0f, 63, 0, 0, false);
        assertEquals(2, decision.cursor, decision.note);
        assertEquals(19.5, decision.targetZ, .0001);
        assertTrue(decision.targetZ < 30.482078889520324);
    }

    @Test
    void aPassedTerminalStillNeedsItsArrivalVolume() {
        FlightSession flight = session(List.of(
                new FlightSession.Waypoint(-996.5, 64.5, 36.5),
                new FlightSession.Waypoint(-996.5, 63.5, 31.5)));
        FlightSession.Decision decision = flight.tick(
                -997.4917796564323, 71.68710962257042, 30.482078889520324,
                -.8977444937598, -.03155650838972399, -1.251389923997621,
                -173.0617f, 0f, 63, 0, 0, false);
        assertEquals(1, decision.cursor);
        assertFalse(flight.isPathComplete());
        assertFalse(decision.applicable, "a missed terminal must not issue a chase-back command");
        assertEquals("terminal_missed", decision.rejectKind);
    }

    @Test
    void launchUsesTheFlightSegmentInsteadOfTheFootAnchor() {
        FlightSession flight = session(List.of(
                new FlightSession.Waypoint(-1006.5, 74.5, 79.5),
                new FlightSession.Waypoint(-1005.5, 73.5, 67.5),
                new FlightSession.Waypoint(-1002.5, 70.5, 57.5)));
        FlightSession.Waypoint target = flight.launchTarget(-1007, 79);
        assertEquals(67.5, target.z());
        assertEquals(0, flight.entryIndex(), "departure direction does not advance the route");
    }

    @Test
    void thePredictedFirstStepUsesTheLimitedPitch() {
        FlightSession flight = session(List.of(
                new FlightSession.Waypoint(0, 75, 0),
                new FlightSession.Waypoint(0, 73, -100)));
        FlightSession.Decision decision = flight.tick(
                0, 75, 0, 0, .5, -1.4, 180f, -35f, 63, 20, 0, false);
        assertTrue(decision.applicable, decision.note);
        assertTrue(Math.abs(decision.pitch + 35f) <= 8.001f, decision.note);
        FlightDynamics.State expected = FlightDynamics.step(
                new FlightDynamics.State(0, 75, 0, 0, .5, -1.4, 20),
                new FlightDynamics.Input(decision.yaw, decision.pitch, decision.fireRocket));
        // The preview is the production prediction, not a second test model.
        assertEquals(expected.x, decision.preview[0], 1.0E-9);
        assertEquals(expected.y, decision.preview[1], 1.0E-9);
        assertEquals(expected.z, decision.preview[2], 1.0E-9);
    }
}
