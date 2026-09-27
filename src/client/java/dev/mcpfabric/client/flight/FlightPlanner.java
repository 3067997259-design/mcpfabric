package dev.mcpfabric.client.flight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Bounded terminal search on a private world snapshot. Never reads the live world.
 * The receiving session still validates identity, time, state and live geometry.
 */
public final class FlightPlanner {
	public record Result(FlightSequence.Plan plan, int rollouts, long elapsedMs, String reason) {}
	private record Trial(double score, double[] controls) {}

	/** Runs on a worker, with an immutable query and the predicted handoff state. */
	public static Result search(String routeId, long revision, long startTick, String snapshotId,
			FlightSequence.Origin origin, List<FlightSession.Waypoint> route,
			FlightSession.BlockQuery snapshot, long budgetMs) {
		long started = System.nanoTime(), deadline = started + budgetMs * 1_000_000L;
		var parameters = new FlightSession.Params();
		// Adoption permits 0.1 block per position axis. Reserve 0.2 blocks
		// inside the runtime's one-block goal sphere for that initial error
		// and native runout differences. Live checks still use the outer sphere.
		parameters.stopAtEnd = true; parameters.terminalReach = .8;
		var verifier = new FlightSession(route, snapshot, System.currentTimeMillis() + budgetMs + 1000, parameters, 0);
		var goal = route.get(route.size() - 1);
		int horizon = Math.hypot(origin.state().x - goal.x(), origin.state().z - goal.z()) > 24 ? 64 : 48;
		List<FlightDynamics.Input> seed = guidance(origin, route, horizon);
		double[] mean = new double[horizon / 2], deviation = new double[horizon / 2];
		for (int i = 0; i < deviation.length; i++) deviation[i] = i % 2 == 0 ? 45 : 24;
		Random random = new Random(240);
		int rollouts = 0;
		for (int generation = 0; generation < 16; generation++) {
			List<Trial> population = new ArrayList<>();
			for (int sample = 0; sample < 128; sample++) {
				if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
					return new Result(null, rollouts, (System.nanoTime() - started) / 1_000_000L, "budget");
				double[] controls = mean.clone();
				if (sample > 0) for (int i = 0; i < controls.length; i++) controls[i] += random.nextGaussian() * deviation[i];
				rollouts++;
				List<FlightDynamics.Input> inputs = new ArrayList<>();
				var frame = origin;
				double score = Double.POSITIVE_INFINITY;
				boolean collision = false;
				for (int tick = 0; tick < horizon; tick++) {
					if (System.nanoTime() >= deadline) break;
					var reference = seed.get(tick);
					float yaw = FlightSession.limitYaw(frame.yaw(), (float)(reference.yaw + controls[tick / 4 * 2]), 25);
					float pitch = FlightSession.limitPitch(frame.pitch(), (float)(reference.pitch + controls[tick / 4 * 2 + 1]), 8);
					var input = new FlightDynamics.Input(yaw, pitch, false);
					var state = FlightDynamics.step(frame.state(), input);
					if (!verifier.sequenceSweepClear(frame.state(), state)) { collision = true; break; }
					frame = new FlightSequence.Origin(state, yaw, pitch);
					inputs.add(input);
					double distance = Math.sqrt(Math.pow(state.x - goal.x(), 2) + Math.pow(state.y - goal.y(), 2) + Math.pow(state.z - goal.z(), 2));
					score = Math.min(score, distance + 3 * Math.abs(state.y - goal.y()) + 8 * Math.hypot(state.vx, state.vz));
					if (distance <= 1 && Math.hypot(state.vx, state.vz) < .8 && verifier.sequenceLanding(frame, deadline) != null)
						return new Result(new FlightSequence.Plan(routeId, revision, startTick, snapshotId, origin, inputs),
								rollouts, (System.nanoTime() - started) / 1_000_000L, "planned");
				}
				population.add(new Trial(score + (collision ? 2 : 0), controls));
			}
			population.sort(Comparator.comparingDouble(Trial::score));
			for (int axis = 0; axis < mean.length; axis++) {
				double average = 0, variance = 0;
				for (int i = 0; i < 12; i++) average += population.get(i).controls[axis] / 12;
				for (int i = 0; i < 12; i++) variance += Math.pow(population.get(i).controls[axis] - average, 2) / 12;
				mean[axis] = average;
				deviation[axis] = Math.max(axis % 2 == 0 ? 5 : 3, Math.sqrt(variance));
			}
		}
		return new Result(null, rollouts, (System.nanoTime() - started) / 1_000_000L, "no_sequence");
	}

	private static List<FlightDynamics.Input> guidance(FlightSequence.Origin origin, List<FlightSession.Waypoint> route, int horizon) {
		List<FlightDynamics.Input> seed = new ArrayList<>();
		var frame = origin;
		int cursor = 0;
		double nearest = Double.POSITIVE_INFINITY;
		for (int i = 0; i < route.size(); i++) {
			double distance = Math.hypot(origin.state().x - route.get(i).x(), origin.state().z - route.get(i).z());
			if (distance <= nearest) { nearest = distance; cursor = Math.min(i + 1, route.size() - 1); }
		}
		var goal = route.get(route.size() - 1);
		for (int tick = 0; tick < horizon; tick++) {
			var s = frame.state();
			while (cursor + 1 < route.size() && Math.hypot(s.x - route.get(cursor).x(), s.z - route.get(cursor).z()) < 6) cursor++;
			var target = route.get(cursor);
			double horizontal = Math.hypot(target.x() - s.x, target.z() - s.z);
			float yaw = (float)Math.toDegrees(Math.atan2(-(target.x() - s.x), target.z() - s.z));
			float pitch = (float)-Math.toDegrees(Math.atan2(target.y() - s.y, Math.max(.5, horizontal)));
			// The seed includes a braking phase; search can alter every block of
			// four controls. Geometry and terminal contact decide feasibility.
			if (Math.hypot(goal.x() - s.x, goal.z() - s.z) < Math.max(3, Math.hypot(s.vx, s.vz) * 10)) {
				yaw = (float)Math.toDegrees(Math.atan2(s.vx, -s.vz));
				pitch = -30;
			}
			yaw = FlightSession.limitYaw(frame.yaw(), yaw, 25);
			pitch = FlightSession.limitPitch(frame.pitch(), pitch, 8);
			var input = new FlightDynamics.Input(yaw, pitch, false);
			seed.add(input);
			frame = new FlightSequence.Origin(FlightDynamics.step(s, input), yaw, pitch);
		}
		return seed;
	}
}
