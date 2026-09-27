package dev.mcpfabric.client.flight;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact voxel coverage of the inflated body, from the 2026-09-26 wool passage. */
class FlightVoxelBoundsTest {
    @Test
    void dominantAxisCollisionCannotBeHiddenByTheFinalDiagonalPosition() {
        // ROOT CAUSE: extension-04 t3335 had only 0.01 block of overlap with
        // the bank on X. Vanilla resolves the larger Z move before moving X
        // out of that overlap. A diagonal-only sweep falsely saw clear space.
        var flight = probe((x, y, z) -> x == -868 && y == 70 && z == -243 ? "orange_terracotta" : "air");
        assertFalse(flight.verifyFrame(-866.7099431415091, 70.37018712718701, -240.86599881503284,
                .39368192591222484, -.06901750660899514, -1.3072976393762274,
                0, -124.89626f, -1.1707416f, false, 1));
    }

    @Test
    void motionAlongAnExactlyTouchingWallDoesNotInventPenetration() {
        var flight = probe((x, y, z) -> x >= 1 ? "stone" : "air");
        assertTrue(flight.verifyFrame(.55, 73.5, .5, 0, 0, .5, 0, 0, 0, false, 1));
    }

    @Test
    void aShortCornerContactBetweenSweepSamplesIsStillRejected() {
        // ROOT CAUSE: extension-02 tick 1372 crossed red sand only between
        // t=0.14 and t=0.30 of the next tick. The 0,.333,.667,1 samples
        // missed it; an independent 0.25-block audit found the contact.
        var flight = probe((x, y, z) -> x == -867 && y == 70 && z == -223 ? "red_sand" : "air");
        assertFalse(flight.verifyFrame(-867.4920515277219, 71.10751326793621, -222.26805505045894,
                .3555474018614563, .12708309218680788, -1.3999542655694894,
                0, 177.14093f, -12, false, 1));
    }

    private FlightSession probe(FlightSession.BlockQuery query) {
        return new FlightSession(List.of(new FlightSession.Waypoint(0, 73.5, 0),
                new FlightSession.Waypoint(0, 73.5, 50)), query,
                System.currentTimeMillis() + 60_000, new FlightSession.Params(), 0);
    }

    @Test
    void ceilingAboveTheInflatedBoxDoesNotIntersectIt() {
        // ROOT CAUSE: ceil(top), sampled inclusively, added an untouched voxel.
        // At feet 73.5 the inflated standing box ends at 75.45, below wool at 76.
        var flight = probe((x, y, z) -> y >= 76 ? "minecraft:light_blue_wool" : "minecraft:air");
        assertTrue(flight.verifyFrame(.5, 73.5, .5, 0, 0, .5, 0, 0, 0, false, 1));
    }

    @Test
    void adjacentWallOutsideTheInflatedBoxDoesNotIntersectIt() {
        // ROOT CAUSE: the floating loop's +0.5 end allowance probed x=1.05
        // although the inflated box occupied [0.05, 0.95].
        var flight = probe((x, y, z) -> x >= 1 ? "minecraft:stone" : "minecraft:air");
        assertTrue(flight.verifyFrame(.5, 73.5, .5, 0, 0, .5, 0, 0, 0, false, 1));
    }

    @Test
    void negativeCoordinatesUseTheSameIntersectionRule() {
        var flight = probe((x, y, z) -> x >= 0 ? "minecraft:stone" : "minecraft:air");
        assertTrue(flight.verifyFrame(-.5, 73.5, .5, 0, 0, .5, 0, 0, 0, false, 1));
    }

    @Test
    void anActuallyIntersectingCeilingStillRejectsTheFlight() {
        var flight = probe((x, y, z) -> y >= 75 ? "minecraft:obsidian" : "minecraft:air");
        assertFalse(flight.verifyFrame(.5, 73.5, .5, 0, 0, .5, 0, 0, 0, false, 1));
    }
}
