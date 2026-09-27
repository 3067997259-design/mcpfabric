package dev.mcpfabric.client.flight;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns snapshot capture, one reserved prefix, and its bounded worker result.
 * Live queries occur only in beforeTick. The worker receives a completed snapshot.
 * Route replacement/revoke cancels the reservation; a late result cannot acquire input.
 */
final class FlightPlanning {
	// Finish an already attached boost inside the verified reservation. The
	// terminal plan itself remains unpowered, including at its adoption tick.
	private static final int LEAD_TICKS = 12;
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "elytra-terminal-planner");
		thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread;
	});
	private final FlightSession session;
	private final FlightSession.BlockQuery live;
	private final List<FlightSession.Waypoint> route;
	private final Snapshot snapshot;
	private CompletableFuture<FlightPlanner.Result> pending;
	private List<FlightDynamics.Input> reserved = List.of();
	private long reservationTick, dueTick, retryAfter;
	private int requests;
	private int reservationBoost;
	private int reservationLength;
	private String status = "idle";
	private String lastResult = "none";
	private boolean closed;

	FlightPlanning(FlightSession session, FlightSession.BlockQuery live, List<FlightSession.Waypoint> route) {
		this.session = session; this.live = live; this.route = List.copyOf(route);
		this.snapshot = new Snapshot(route.get(route.size() - 1));
	}

	String status() { return status + " result=" + lastResult; }

	FlightDynamics.Input beforeTick(FlightSequence.Origin origin, long tick, long deadline) {
		if (closed) return null;
		var goal = route.get(route.size() - 1);
		if (!snapshot.complete() && Math.hypot(origin.state().x - goal.x(), origin.state().z - goal.z()) <= 200) {
			snapshot.capture(live, Math.min(deadline, System.nanoTime() + 1_000_000L));
			status = "snapshot:" + snapshot.cursor + "/" + snapshot.ids.length;
		}
		if (pending == null) return null;
		// Measured thrust invalidates the worker's origin before its result
		// is considered, including a failure received in the same tick.
		int expectedBoost = Math.max(0, reservationBoost - (int)(tick - reservationTick));
		if (origin.state().rocketTicksRemaining != expectedBoost) {
			pending.cancel(true); pending = null; reserved = List.of();
			lastResult = "boost_changed"; status = "aborted"; retryAfter = tick;
			return null;
		}
		// A successful plan belongs to its exact future origin. A failed
		// search has no such adoption contract: stop following its prefix as
		// soon as the result arrives instead of consuming manoeuvring room.
		if (tick < dueTick && pending.isDone()) {
			FlightPlanner.Result result = pending.isCompletedExceptionally() ? null : pending.join();
			if (result == null || result.plan() == null) {
				lastResult = result == null ? "worker_failed"
						: result.reason() + ":" + result.rollouts() + ":" + result.elapsedMs() + "ms";
				pending = null; reserved = List.of(); status = "failed_early"; retryAfter = tick;
				return null;
			}
		}
		if (tick >= dueTick) {
			if (tick == dueTick && pending.isDone() && !pending.isCompletedExceptionally()) {
				var result = pending.join();
				lastResult = result.reason() + ":" + result.rollouts() + ":" + result.elapsedMs() + "ms";
				if (result.plan() != null) session.offerTerminalSequence(result.plan());
			} else lastResult = "late";
			pending.cancel(true); pending = null; reserved = List.of();
			status = "delivered";
			retryAfter = tick + 2;
			return null;
		}
		int index = (int)(tick - reservationTick);
		if (index < 1 || index >= reserved.size()
				|| !session.planningPrefixClear(origin, reserved.subList(index, reserved.size()), deadline)) {
			pending.cancel(true); pending = null; reserved = List.of();
			lastResult = "prefix_changed" + session.planningPrefixEvidence(); status = "aborted"; retryAfter = tick + 2;
			return null;
		}
		status = "reserved:" + index + "/" + reservationLength + session.planningPrefixEvidence();
		var input = reserved.get(index);
		return new FlightDynamics.Input(FlightSession.limitYaw(origin.yaw(), input.yaw, 25),
				FlightSession.limitPitch(origin.pitch(), input.pitch, 8), false);
	}

	void consider(FlightSequence.Origin origin, List<FlightDynamics.Input> controls,
			long tick, String routeId, long revision, long deadline) {
		reserve(origin, controls, tick, routeId, revision, deadline, LEAD_TICKS);
	}

	/** A short unpowered bridge is useful only when a worker can start now. */
	boolean canReserveShort(FlightSequence.Origin origin, long tick) {
		var goal = route.get(route.size() - 1);
		double distance = Math.hypot(origin.state().x - goal.x(), origin.state().z - goal.z());
		return !closed && pending == null && snapshot.complete() && tick >= retryAfter
				&& requests < 6 && origin.state().rocketTicksRemaining == 0 && distance >= 8 && distance <= 56;
	}

	boolean reserveShort(FlightSequence.Origin origin, List<FlightDynamics.Input> controls,
			long tick, String routeId, long revision, long deadline) {
		if (!canReserveShort(origin, tick)) return false;
		return reserve(origin, controls, tick, routeId, revision, deadline, 8);
	}

	private boolean reserve(FlightSequence.Origin origin, List<FlightDynamics.Input> controls,
			long tick, String routeId, long revision, long deadline, int leadTicks) {
		if (closed || pending != null || !snapshot.complete() || tick < retryAfter || controls.size() < leadTicks + 4
				|| origin.state().rocketTicksRemaining > leadTicks || requests >= 6) return false;
		// A live entity beyond its nominal lifetime stays at one estimated tick.
		// Do not spend a worker request each tick predicting that it disappears.
		if (origin.state().rocketTicksRemaining == 1) {
			status = "waiting_boost_expiry";
			return false;
		}
		var goal = route.get(route.size() - 1);
		double distance = Math.hypot(origin.state().x - goal.x(), origin.state().z - goal.z());
		if (distance > 56 || distance < 8) return false;
		var prefix = List.copyOf(controls.subList(0, leadTicks));
		// The first action executes in this tick, before beforeTick can recheck.
		// Do not create an optimistic reservation whose alternatives already fail.
		if (!session.planningPrefixClear(origin, prefix, deadline)) {
			status = System.nanoTime() >= deadline ? "prefix_budget" : "prefix_expiry_refused";
			status += session.planningPrefixEvidence();
			return false;
		}
		var future = origin;
		for (int i = 0; i < leadTicks; i++) {
			var input = prefix.get(i);
			if (input.useRocket) return false;
			future = new FlightSequence.Origin(FlightDynamics.step(future.state(), input), input.yaw, input.pitch);
		}
		// No future world query captures live state. Snapshot storage is sealed
		// when complete; only the controller thread advances the reservation.
		var handoff = future;
		long startTick = tick + leadTicks;
		String snapshotId = routeId + ":" + revision + ":" + tick;
		pending = CompletableFuture.supplyAsync(() -> FlightPlanner.search(routeId, revision, startTick,
				snapshotId, handoff, route, snapshot, 300), WORKER);
		requests++;
		reserved = prefix; reservationTick = tick; dueTick = startTick;
		reservationBoost = origin.state().rocketTicksRemaining;
		reservationLength = leadTicks;
		status = "searching:" + requests + " due=" + dueTick + session.planningPrefixEvidence();
		return true;
	}

	void close() {
		closed = true;
		if (pending != null) pending.cancel(true);
		pending = null; reserved = List.of(); status = "closed";
	}

	void rejectReservation() {
		if (pending != null) pending.cancel(true);
		pending = null; reserved = List.of(); status = "aborted"; lastResult = "budget";
	}

	private static final class Snapshot implements FlightSession.BlockQuery {
		private final int x0, y0, z0;
		private final String[] ids = new String[81 * 25 * 81];
		private final double[] friction = new double[ids.length];
		private int cursor;
		Snapshot(FlightSession.Waypoint goal) {
			x0 = (int)Math.floor(goal.x()) - 40;
			y0 = (int)Math.floor(goal.y()) - 8;
			z0 = (int)Math.floor(goal.z()) - 40;
			java.util.Arrays.fill(friction, Double.NaN);
		}
		boolean complete() { return cursor == ids.length; }
		void capture(FlightSession.BlockQuery live, long deadline) {
			int limit = Math.min(ids.length, cursor + 2048);
			while (cursor < limit && System.nanoTime() < deadline) {
				int x = x0 + cursor % 81, z = z0 + cursor / 81 % 81, y = y0 + cursor / (81 * 81);
				String id = live.idAt(x, y, z);
				ids[cursor] = id;
				if (id != null && !FlightGeometry.isFlyableThrough(id)) friction[cursor] = live.frictionAt(x, y, z);
				cursor++;
			}
		}
		private int index(double px, double py, double pz) {
			int x = (int)Math.floor(px) - x0, y = (int)Math.floor(py) - y0, z = (int)Math.floor(pz) - z0;
			return x < 0 || x >= 81 || y < 0 || y >= 25 || z < 0 || z >= 81 ? -1 : x + 81 * (z + 81 * y);
		}
		public String idAt(double x, double y, double z) { int i = index(x, y, z); return i < 0 ? null : ids[i]; }
		public double frictionAt(double x, double y, double z) { int i = index(x, y, z); return i < 0 ? Double.NaN : friction[i]; }
	}
}
