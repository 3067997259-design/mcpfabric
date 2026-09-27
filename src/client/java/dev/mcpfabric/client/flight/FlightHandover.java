package dev.mcpfabric.client.flight;

/**
 * The pending-route handover state machine (R4 client review 2026-09-21,
 * groups 1-3). Pure logic: the controller owns the Minecraft side (reading the
 * player, submitting to the bridge) and delegates every adoption decision and
 * the pending-route bookkeeping here, so the same-state replay and the timing
 * perturbations can drive the rules a live handover actually uses.
 *
 * The rules:
 *
 * - {@link #offer} keeps the NEWEST valid route: a duplicate id or an older
 *   revision never replaces a newer pending route.
 * - {@link #decide} keeps, drops, or adopts the pending route. A route older
 *   than {@link #MAX_AGE_MS} is dropped (a stale route is not flown), and an
 *   unconnectable route is dropped once the flying path is complete instead
 *   of being waited on.
 * - {@link #adopt} transfers the state that must survive: the unfinished fire
 *   cooldown (boost is measured per tick and needs no transfer).
 */
public final class FlightHandover {

	/** The pilot facts the handover checks read, without Minecraft types. */
	public record PilotState(
			double x, double y, double z,
			double vx, double vz,
			boolean gliding, boolean inWater) {}

	/** What the controller should do with the pending route this tick. */
	public enum Action {
		/** No pending route, or it is not usable yet: keep flying. */
		KEEP,
		/** The pending route is unusable (stale or unconnectable at completion). */
		DROP,
		/** Adopt the pending route now. */
		ADOPT,
	}

	/** A pending route's first waypoint must be this close to be offered. */
	public static final double ENTRY_LIMIT = 64.0;
	/** An entry above the glider by more than this cannot be glided to. */
	public static final double ENTRY_ABOVE = 4.0;
	/**
	 * Reach multiplier: a glider at `speed` blocks/tick can cover about this
	 * many ticks of travel while descending to the entry.
	 */
	public static final double SPEED_REACH_TICKS = 200.0;
	/** How long an offered route may wait for adoption, ms. */
	public static final long MAX_AGE_MS = 10_000;

	private FlightSession pending;
	private String pendingId;
	private long pendingRevision;
	private long pendingAcceptedAtMs;
	private int handoverCount;

	/**
	 * Offers a route for later adoption. Returns false (and keeps the current
	 * pending route) for a duplicate id, an older-or-equal revision than the
	 * pending route, or a revision not newer than the flying session: a
	 * repeated, stale, or out-of-order delivery must not overwrite a newer
	 * valid route (R4 review group 3).
	 */
	public synchronized boolean offer(String sessionId, long revision, FlightSession session, long nowMs, long currentRevision) {
		if (sessionId == null || session == null)
			return false;
		if (revision <= currentRevision)
			return false;
		if (pending != null && (pendingId.equals(sessionId) || revision <= pendingRevision))
			return false;
		pending = session;
		pendingId = sessionId;
		pendingRevision = revision;
		pendingAcceptedAtMs = nowMs;
		return true;
	}

	/**
	 * True when the measured state can connect to the session's first entry:
	 * flying, not in water, the entry not above the glide envelope, and the
	 * entry within a reach the current speed can cover.
	 */
	public static boolean canConnect(PilotState pilot, FlightSession candidate) {
		if (!pilot.gliding() || pilot.inWater())
			return false;
		FlightSession.Waypoint entry = candidate.currentTarget();
		if (entry.y() - pilot.y() > ENTRY_ABOVE)
			return false;
		double horizontal = Math.hypot(entry.x() - pilot.x(), entry.z() - pilot.z());
		double speed = Math.hypot(pilot.vx(), pilot.vz());
		double reach = Math.max(ENTRY_LIMIT, speed * SPEED_REACH_TICKS);
		return horizontal <= reach;
	}

	/**
	 * The adoption decision for this tick.
	 *
	 * @param currentDone true when the flying session has completed (or
	 *        failed); an unconnectable pending route is dropped then instead
	 *        of keeping the hold waiting for it
	 */
	public synchronized Action decide(PilotState pilot, FlightSession current, long nowMs, boolean currentDone) {
		if (pending == null)
			return Action.KEEP;
		if (nowMs - pendingAcceptedAtMs > MAX_AGE_MS) {
			dropPending();
			return Action.DROP;
		}
		if (!canConnect(pilot, pending)) {
			if (currentDone || current == null) {
				dropPending();
				return Action.DROP;
			}
			return Action.KEEP;
		}
		if (currentDone)
			return Action.ADOPT;
		FlightSession.Waypoint entry = pending.currentTarget();
		double distance = Math.sqrt(
				sq(entry.x() - pilot.x()) + sq(entry.y() - pilot.y()) + sq(entry.z() - pilot.z()));
		// An early-ready route may switch as soon as its entry is within two
		// reach radii: it must not wait for the current path to complete and
		// fall into an unmotivated hold (R4 review group 2).
		return distance <= pending.entryReach() * 2 ? Action.ADOPT : Action.KEEP;
	}

	/**
	 * Transfers the state that must survive a handover from {@code previous}
	 * to {@code next}: the unfinished fire cooldown (the next session's tick
	 * count starts at zero, so without it the driver could fire immediately
	 * and an unfinished boost would be double-spent).
	 */
	public static void adopt(FlightSession previous, FlightSession next, long previousSessionTick) {
		int cooldownRemaining = previous == null ? 0 : previous.fireCooldownRemaining(previousSessionTick);
		next.inheritFireCooldown(cooldownRemaining);
	}

	/** Marks the pending route adopted and counts the handover. */
	public synchronized void completeAdoption() {
		handoverCount += 1;
		dropPending();
	}

	/** Drops the pending route (stale, unconnectable at completion, or a new session). */
	public synchronized void dropPending() {
		pending = null;
		pendingId = null;
	}

	public synchronized FlightSession pending() {
		return pending;
	}

	public synchronized String pendingId() {
		return pendingId;
	}

	public synchronized long pendingRevision() {
		return pendingRevision;
	}

	public synchronized int handoverCount() {
		return handoverCount;
	}

	private static double sq(double value) {
		return value * value;
	}
}
