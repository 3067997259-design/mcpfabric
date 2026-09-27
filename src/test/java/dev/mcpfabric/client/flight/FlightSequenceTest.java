package dev.mcpfabric.client.flight;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightSequenceTest {
	@Test void slowResidualRunoutStillCountsAgainstTheGoalRadius() {
		// ROOT CAUSE: stopping the prediction at speed 0.05 discarded the
		// remaining slide. A pose inside the sphere then drifted outside it.
		var s = session();
		var state = new FlightDynamics.State(.67,66.1,0,.06,-.2,0,0);
		assertFalse(s.sequenceLandingClear(state,new FlightDynamics.Input(0,0,false),System.nanoTime()+100_000_000L));
	}
	@Test void supportPhysicsIsReadOncePerCellButRefreshedForTheNextTick() {
		var reads = new java.util.HashMap<String,Integer>();
		double[] friction = { .6 };
		FlightSession.BlockQuery world = new FlightSession.BlockQuery() {
			public String idAt(double x,double y,double z) { return y < 66 ? "stone" : "air"; }
			public double frictionAt(double x,double y,double z) {
				reads.merge(x + ":" + y + ":" + z,1,Integer::sum);
				return friction[0];
			}
		};
		var p = new FlightSession.Params(); p.stopAtEnd = true; p.terminalReach = 1; p.simBudgetMs = 1000;
		var s = new FlightSession(List.of(new FlightSession.Waypoint(-8,67,0),new FlightSession.Waypoint(0,66.5,0)),
				world,System.currentTimeMillis()+60000,p,0);
		s.setRouteIdentity("test",1);
		reads.clear();
		assertTrue(s.offerTerminalSequence(plan("test",1,0)));
		var first = tick(s,ORIGIN,0);
		assertTrue(first.applicable);
		assertFalse(reads.isEmpty());
		assertTrue(reads.values().stream().allMatch(n -> n == 1));
		friction[0] = Double.NaN;
		tick(s,after(ORIGIN,first),1);
		assertTrue(s.terminalSequenceStatus().startsWith("rejected:"));
	}
	@Test void rejectedLandingInsideTheGoalDoesNotBecomeCompletion() {
		var s = session();
		assertTrue(s.offerTerminalSequence(plan("test", 1, 0)));
		var first = tick(s, ORIGIN, 0);
		changed = true;
		tick(s, after(ORIGIN, first), 1);
		assertNotEquals(FlightSession.EndReason.CHANNEL_COMPLETE, s.endReason());
	}
	@Test void settledGoalRadiusIncludesTheHeightAboveSupport() {
		// ROOT CAUSE: online-01 landed 0.97 blocks from the goal horizontally,
		// but 1.09 in 3D. The air arrival and settled contact must use one sphere.
		var s = session();
		var origin = new FlightSequence.Origin(new FlightDynamics.State(.95, 66.4, 0, 0, -.2, 0, 0), 0, 0);
		assertNull(s.sequenceLanding(origin, System.nanoTime() + 100_000_000L));
	}
	private static final FlightSequence.Origin ORIGIN = new FlightSequence.Origin(
			new FlightDynamics.State(0, 66.8, 0, .03, -.32, 0, 0), 0, 0);
	private boolean changed;
	private final FlightSession.BlockQuery blocks = new FlightSession.BlockQuery() {
		public String idAt(double x, double y, double z) {
			return changed || y < 66 ? "stone" : "air";
		}
		public double frictionAt(double x, double y, double z) { return .6; }
	};

	private FlightSession session() {
		var params = new FlightSession.Params();
		params.stopAtEnd = true;
		params.terminalReach = 1;
		// Contract tests do not assert wall-clock speed. The snapshot replay
		// separately uses the production 12 ms cap on all 65 trajectories.
		params.simBudgetMs = 1000;
		var session = new FlightSession(List.of(new FlightSession.Waypoint(-8, 67, 0),
				new FlightSession.Waypoint(0, 66.5, 0)), blocks, System.currentTimeMillis() + 60000, params, 0);
		session.setRouteIdentity("test", 1);
		return session;
	}
	private FlightSequence.Plan plan(String id, long revision, long tick) {
		return new FlightSequence.Plan(id, revision, tick, "world-1", ORIGIN,
				List.of(new FlightDynamics.Input(0, 0, false)));
	}
	private FlightSession.Decision tick(FlightSession session, FlightSequence.Origin origin, long tick) {
		var s = origin.state();
		return session.tick(s.x, s.y, s.z, s.vx, s.vy, s.vz, origin.yaw(), origin.pitch(),
				0, s.rocketTicksRemaining, tick, false);
	}
	private FlightSequence.Origin after(FlightSequence.Origin origin, FlightSession.Decision action) {
		return new FlightSequence.Origin(FlightDynamics.step(origin.state(),
				new FlightDynamics.Input(action.yaw, action.pitch, false)), action.yaw, action.pitch);
	}

	@Test void rejectedIdentityDoesNotReplaceTheQueuedPlan() {
		var s = session();
		assertFalse(s.offerTerminalSequence(plan("other", 1, 0)));
		assertFalse(s.offerTerminalSequence(plan("test", 2, 0)));
		assertTrue(s.offerTerminalSequence(plan("test", 1, 0)));
		assertFalse(s.offerTerminalSequence(plan("test", 1, 0)));
		assertTrue(tick(s, ORIGIN, 0).chosenPolicy.startsWith("sequence:"));
	}

	@Test void latePlanIsNotShiftedToTheCurrentTick() {
		var s = session();
		s.offerTerminalSequence(plan("test", 1, 0));
		tick(s, ORIGIN, 1);
		assertEquals("rejected:late", s.terminalSequenceStatus());
	}

	@Test void originMismatchDoesNotReplayTheFirstInput() {
		var s = session();
		s.offerTerminalSequence(plan("test", 1, 0));
		var wrong = new FlightSequence.Origin(new FlightDynamics.State(.2, 66.8, 0, .03, -.32, 0, 0), 0, 0);
		assertFalse(tick(s, wrong, 0).chosenPolicy.startsWith("sequence:"));
		assertEquals("rejected:origin", s.terminalSequenceStatus());
	}

	@Test void contactTailKeepsControlInsideTheArrivalSphere() {
		// ROOT CAUSE: a point-radius arrival used to discard the certified
		// landing input. A different recovery action then owned touchdown.
		var s = session();
		s.offerTerminalSequence(plan("test", 1, 0));
		var first = tick(s, ORIGIN, 0);
		assertTrue(first.applicable);
		assertFalse(s.isDone());
		var next = after(ORIGIN, first);
		var tail = tick(s, next, 1);
		assertEquals("sequence:landing", tail.chosenPolicy);
		assertFalse(s.isDone());
		assertFalse(tail.fireRocket);
	}

	@Test void revisedRouteAndMissingNativeTickRejectActivePlans() {
		var s = session();s.offerTerminalSequence(plan("test", 1, 0));
		var next = after(ORIGIN, tick(s, ORIGIN, 0));
		s.setRouteIdentity("test", 2);
		tick(s, next, 1);
		assertEquals("rejected:identity", s.terminalSequenceStatus());
		s = session();s.offerTerminalSequence(plan("test", 1, 0));
		next = after(ORIGIN, tick(s, ORIGIN, 0));
		tick(s, next, 2);
		assertEquals("rejected:tick_gap", s.terminalSequenceStatus());
	}

	@Test void changedWorldCannotReuseCachedLandingApproval() {
		var s = session();s.offerTerminalSequence(plan("test", 1, 0));
		var next = after(ORIGIN, tick(s, ORIGIN, 0));
		changed = true;
		var action = tick(s, next, 1);
		assertEquals("rejected:landing_changed", s.terminalSequenceStatus());
		assertFalse(action.chosenPolicy.startsWith("sequence:"));
	}

	@Test void plannerCannotMutateQueuedCommandsOrSmuggleIgnition() {
		var commands = new ArrayList<FlightDynamics.Input>();
		commands.add(new FlightDynamics.Input(0, 0, false));
		var plan = new FlightSequence.Plan("test", 1, 0, "world-1", ORIGIN, commands);
		commands.clear();
		assertEquals(1, plan.inputs().size());
		assertThrows(IllegalArgumentException.class, () -> new FlightSequence.Plan("test", 1, 0, "world-1", ORIGIN,
				List.of(new FlightDynamics.Input(0, 0, true))));
		assertThrows(IllegalArgumentException.class, () -> new FlightSequence.Plan("test", 1, 0, "world-1", ORIGIN,
				List.of(new FlightDynamics.Input(Float.NaN, 0, false))));
	}

	@Test void lastContactTickUsesSinkSpeedNotUnclippedDepth() {
		// ROOT CAUSE: sequence-replay-01 certified the nominal landing tail,
		// then rejected its last tick because the air endpoint was >0.3 below
		// the floor. Native collision clips it to the floor. Sink, support,
		// body clearance and runout checks are the relevant contact tests.
		var s = session();
		var frame = s.recoveryFrame(0, 66.04, 0, .02, -.39, .08, 0, 0, 17, -48, 17, -48, 14);
		assertTrue(frame.landingContact);
		assertTrue(frame.verified > 0);
		var hard = s.recoveryFrame(0, 66.04, 0, .02, -.7, .08, 0, 0, 17, -48, 17, -48, 14);
		assertFalse(hard.landingContact);
	}

	@Test void arrivalTimeMayExtendOnlyAfterCheckingTheExtraInputs() {
		var origin = new FlightSequence.Origin(new FlightDynamics.State(0, 68.2, 0, .03, -.32, 0, 0), 0, 0);
		var params = new FlightSession.Params();
		params.stopAtEnd = true; params.terminalReach = 1; params.simBudgetMs = 1000;
		var s = new FlightSession(List.of(new FlightSession.Waypoint(-8, 68, 0),
				new FlightSession.Waypoint(0, 66.5, 1)), blocks, System.currentTimeMillis() + 60000, params, 0);
		s.setRouteIdentity("test", 1);
		s.offerTerminalSequence(new FlightSequence.Plan("test", 1, 0, "world-1", origin,
				List.of(new FlightDynamics.Input(0, 0, false))));
		boolean landing = false;
		for (int tick = 0; tick < 6; tick++) {
			var action = tick(s, origin, tick);
			assertTrue(action.chosenPolicy.startsWith("sequence:"), action.note);
			if (action.chosenPolicy.equals("sequence:landing")) { landing = true; break; }
			origin = after(origin, action);
		}
		assertTrue(landing, "Extra airborne steps must end in a checked landing tail");
	}

	@Test void budgetRefusalInsideArrivalSphereDoesNotClaimCompletionOrLatchUndecided() {
		var pause = new java.util.concurrent.atomic.AtomicBoolean(false);
		var query = new FlightSession.BlockQuery() {
			public String idAt(double x, double y, double z) {
				if (pause.getAndSet(false)) {
					try { Thread.sleep(5); }
					catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
				}
				return blocks.idAt(x, y, z);
			}
			public double frictionAt(double x, double y, double z) { return .6; }
		};
		var params = new FlightSession.Params();
		params.stopAtEnd = true; params.terminalReach = 1; params.simBudgetMs = 1;
		var s = new FlightSession(List.of(new FlightSession.Waypoint(-8, 67, 0),
				new FlightSession.Waypoint(0, 66.5, 0)), query, System.currentTimeMillis() + 60000, params, 0);
		s.setRouteIdentity("test", 1);
		s.offerTerminalSequence(plan("test", 1, 0));
		pause.set(true);
		var refused = tick(s, ORIGIN, 0);
		assertFalse(refused.applicable);
		assertTrue(refused.budgetExhausted);
		assertFalse(s.isDone(), "The controller must receive an undecided result and keep the route owner alive");
		var next = tick(s, ORIGIN, 1);
		assertFalse(next.budgetExhausted, "A previous sequence budget refusal must not latch the undecided branch");
	}
}
