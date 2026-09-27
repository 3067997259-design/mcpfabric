package dev.mcpfabric.client.flight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Executes a finite, unpowered terminal plan from measured state.
 * The session owns this object and calls it on the control thread. A planner
 * may produce a plan elsewhere, but only the session can queue and adopt it.
 * Rejection returns control to normal route selection in the same tick.
 */
public final class FlightSequence {

	/** Pose before the first input, with velocity in blocks per native tick. */
	public record Origin(FlightDynamics.State state, float yaw, float pitch) {}

	/**
	 * Immutable planner result. startTick uses the receiving session's clock.
	 * snapshotId identifies planner evidence; it does not authorize geometry.
	 * Every command and the landing tail must pass the current block query.
	 */
	public record Plan(String routeId, long revision, long startTick, String snapshotId,
			Origin origin, List<FlightDynamics.Input> inputs) {
		public Plan {
			inputs = List.copyOf(inputs);
			if (routeId == null || routeId.isEmpty() || snapshotId == null || snapshotId.isEmpty()
					|| startTick < 0 || origin == null || !finite(origin)
					|| origin.state.rocketTicksRemaining != 0 || inputs.isEmpty() || inputs.size() > 64)
				throw new IllegalArgumentException("invalid terminal plan");
			for (FlightDynamics.Input input : inputs) {
				if (!Float.isFinite(input.yaw) || !Float.isFinite(input.pitch)
						|| Math.abs(input.pitch) > 60 || input.useRocket)
					throw new IllegalArgumentException("terminal plan requires finite unpowered controls");
			}
		}
	}

	record Step(FlightDynamics.Input input, FlightDynamics.State next, int checkedTicks,
			double[] preview) {}
	private record Offset(float yaw, float pitch) {}
	private record Verified(List<FlightDynamics.Input> inputs, FlightDynamics.State next,
			FlightDynamics.Input landing, double[] preview) {}

	// Small corrections decay over twelve ticks. Try the smallest combined
	// change first; a larger change earns no preference merely for progress.
	private static final List<Offset> CORRECTIONS = corrections();
	private final FlightSession session;
	private final FlightSession.Params params;
	private Plan pending;
	private List<FlightDynamics.Input> remaining = List.of();
	private FlightDynamics.Input landing;
	private long nextTick = -1;
	private int landingTicks;
	private int flightTicks;
	private boolean active;
	private String activeRouteId;
	private long activeRevision;
	private String status = "none";

	FlightSequence(FlightSession session, FlightSession.Params params) {
		this.session = session;
		this.params = params;
	}

	/** Queuing never replaces an active sequence or changes input ownership. */
	boolean offer(Plan plan, String routeId, long revision) {
		if (active || pending != null || !plan.routeId.equals(routeId) || plan.revision != revision)
			return false;
		pending = plan;
		status = "queued";
		return true;
	}

	String status() { return status; }
	void budgetExpired() { reject("budget"); }

	Step tick(Origin measured, long tick, String routeId, long revision, long deadlineNanos) {
		if (pending != null) {
			if (!pending.routeId.equals(routeId) || pending.revision != revision)
				return reject("identity");
			if (tick < pending.startTick) return null;
			if (tick != pending.startTick) return reject("late");
			if (!matches(pending.origin, measured)) return reject("origin");
			remaining = pending.inputs;
			activeRouteId = pending.routeId;
			activeRevision = pending.revision;
			pending = null;
			active = true;
			flightTicks = 0;
			landingTicks = 0;
			nextTick = tick;
		}
		if (!active) return null;
		if (!activeRouteId.equals(routeId) || activeRevision != revision) return reject("identity");
		if (tick != nextTick) return reject("tick_gap");
		if (!finite(measured) || measured.state.rocketTicksRemaining != 0) return reject("state");
		session.refreshSequenceGeometry();
		if (remaining.isEmpty()) {
			if (landing == null || landingTicks >= 14) return reject("touchdown_unconfirmed");
			FlightDynamics.Input input = limited(measured, landing, 0, 0);
			if (!session.sequenceLandingClear(measured.state, input, deadlineNanos))
				return reject(expired(deadlineNanos) ? "budget" : "landing_changed");
			landingTicks++;
			nextTick++;
			status = "landing";
			FlightDynamics.State next = FlightDynamics.step(measured.state, input);
			double[] preview = emptyPreview();
			preview[0] = next.x; preview[1] = next.y; preview[2] = next.z;
			return new Step(input, next, 14, preview);
		}
		for (Offset offset : CORRECTIONS) {
			if (expired(deadlineNanos)) return reject("budget");
			Verified verified = verify(measured, offset, deadlineNanos);
			if (verified == null) continue;
			// The first command is exactly the rate-limited command just swept.
			// Store the corrected suffix so the next tick checks the same plan.
			int checked = verified.inputs.size() + 14;
			FlightDynamics.Input input = verified.inputs.get(0);
			remaining = List.copyOf(verified.inputs.subList(1, verified.inputs.size()));
			landing = verified.landing;
			flightTicks++;
			nextTick++;
			boolean repaired = offset.yaw != 0 || offset.pitch != 0;
			status = repaired ? "corrected" : "tracking";
			return new Step(input, verified.next, checked, verified.preview);
		}
		return reject(expired(deadlineNanos) ? "budget" : "suffix_unverified");
	}

	private Verified verify(Origin measured, Offset offset, long deadline) {
		Origin frame = measured;
		FlightDynamics.State first = null;
		List<FlightDynamics.Input> inputs = new ArrayList<>(remaining.size());
		double[] preview = emptyPreview();
		// A measured vertical error may delay arrival by a few ticks. A fixed
		// nominal end time would reject a clear continuation above the target.
		// At most four extra inputs are swept, within 64 total airborne steps.
		int limit = Math.min(remaining.size() + 4, 64 - flightTicks);
		for (int i = 0; i < limit; i++) {
			if (expired(deadline)) return null;
			float decay = Math.max(0, 1 - i / 12f);
			FlightDynamics.Input input = limited(frame, remaining.get(Math.min(i, remaining.size() - 1)),
					offset.yaw * decay, offset.pitch * decay);
			FlightDynamics.State next = FlightDynamics.step(frame.state, input);
			if (!session.sequenceSweepClear(frame.state, next)) return null;
			if (first == null) first = next;
			if (i == 0 || i == 2 || i == 4) {
				int index = i / 2 * 3;
				preview[index] = next.x; preview[index + 1] = next.y; preview[index + 2] = next.z;
			}
			inputs.add(input);
			frame = new Origin(next, input.yaw, input.pitch);
			if (i >= remaining.size() - 1) {
				FlightDynamics.Input tail = session.sequenceLanding(frame, deadline);
				if (tail != null) return new Verified(inputs, first, tail, preview);
			}
		}
		return null;
	}

	private FlightDynamics.Input limited(Origin frame, FlightDynamics.Input input, float yaw, float pitch) {
		return new FlightDynamics.Input(
				FlightSession.limitYaw(frame.yaw, input.yaw + yaw, params.yawRateLimitDegrees),
				FlightSession.limitPitch(frame.pitch, input.pitch + pitch, params.pitchRateLimitDegrees), false);
	}

	private Step reject(String reason) {
		pending = null;
		remaining = List.of();
		landing = null;
		active = false;
		status = "rejected:" + reason;
		return null;
	}

	private static boolean expired(long deadline) { return System.nanoTime() >= deadline; }
	private static double[] emptyPreview() {
		// The ring formats these as text. NaN means unavailable after contact;
		// zero would claim a prediction at the world's origin.
		double[] preview = new double[9];
		java.util.Arrays.fill(preview, Double.NaN);
		return preview;
	}
	private static boolean finite(Origin o) {
		FlightDynamics.State s = o.state;
		return s != null && Double.isFinite(s.x) && Double.isFinite(s.y) && Double.isFinite(s.z)
				&& Double.isFinite(s.vx) && Double.isFinite(s.vy) && Double.isFinite(s.vz)
				&& Float.isFinite(o.yaw) && Float.isFinite(o.pitch);
	}
	private static boolean matches(Origin expected, Origin actual) {
		if (!finite(actual)) return false;
		FlightDynamics.State a = expected.state, b = actual.state;
		float yaw = Math.abs(((expected.yaw - actual.yaw) % 360 + 540) % 360 - 180);
		return Math.abs(a.x - b.x) <= .1 && Math.abs(a.y - b.y) <= .1 && Math.abs(a.z - b.z) <= .1
				&& Math.abs(a.vx - b.vx) <= .04 && Math.abs(a.vy - b.vy) <= .04 && Math.abs(a.vz - b.vz) <= .04
				&& yaw <= 2 && Math.abs(expected.pitch - actual.pitch) <= 2
				&& a.rocketTicksRemaining == b.rocketTicksRemaining;
	}
	private static List<Offset> corrections() {
		List<Offset> result = new ArrayList<>();
		for (float yaw : new float[] { 0, -1, 1, -2, 2, -4, 4, -8, 8 })
			for (float pitch : new float[] { 0, -1, 1, -2, 2, -4, 4, -8, 8 })
				result.add(new Offset(yaw, pitch));
		result.sort(Comparator.comparingDouble((Offset o) -> o.yaw * o.yaw + o.pitch * o.pitch)
				.thenComparingDouble(o -> Math.abs(o.yaw)).thenComparingDouble(o -> o.yaw).thenComparingDouble(o -> o.pitch));
		return List.copyOf(result);
	}
}
