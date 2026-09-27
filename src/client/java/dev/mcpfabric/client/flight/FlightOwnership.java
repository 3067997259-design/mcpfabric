package dev.mcpfabric.client.flight;

/**
 * Route outcome and physical control phase, kept as two separate facts
 * (ab-23 repair plan, batch A2).
 *
 * <p>{@code routeOutcome} says what happened to the ROUTE (running, completed,
 * failed, revoked); {@code phase} says who owns the aircraft RIGHT NOW
 * (prepare, track, hold, recover, land, settled, released). A failed route
 * enters {@code recover} and can later settle — settling never turns the route
 * outcome back into a success, and the first failure wins.
 *
 * <p>The recovery window is a policy deadline that bounds how long the
 * recovery phase keeps ownership; the controller's hard control deadline stays
 * authoritative and is checked separately. Pure and side-effect free so the
 * transitions can be tested without a game client.
 */
public final class FlightOwnership {
	public enum RouteOutcome { RUNNING, COMPLETED, FAILED, REVOKED }
	public enum Phase { PREPARE, TRACK, HOLD, RECOVER, LAND, SETTLED, RELEASED }

	private RouteOutcome routeOutcome = RouteOutcome.RUNNING;
	private Phase phase = Phase.RELEASED;
	private String failureReason = "";
	private String releaseReason = "";
	private long recoveryStartedMs;
	private long recoveryUntilMs;
	/** The latest control deadline the host authorization allows (C3). */
	private long hardDeadlineMs = Long.MAX_VALUE;

	public RouteOutcome routeOutcome() {
		return routeOutcome;
	}

	public Phase phase() {
		return phase;
	}

	public String failureReason() {
		return failureReason;
	}

	/** Why the phase was released; typed failures live here (C3). */
	public String releaseReason() {
		return releaseReason;
	}

	public long hardDeadlineMs() {
		return hardDeadlineMs;
	}

	public long recoveryStartedMs() {
		return recoveryStartedMs;
	}

	public long recoveryUntilMs() {
		return recoveryUntilMs;
	}

	public boolean recovering() {
		return phase == Phase.RECOVER;
	}

	/** A new route was accepted: it starts in the prepare phase. */
	public void startRoute(long hardDeadlineMs) {
		routeOutcome = RouteOutcome.RUNNING;
		phase = Phase.PREPARE;
		failureReason = "";
		releaseReason = "";
		this.hardDeadlineMs = hardDeadlineMs;
	}

	public void markTrack() {
		if (phase == Phase.PREPARE)
			phase = Phase.TRACK;
	}

	public void markHold() {
		if (phase == Phase.TRACK)
			phase = Phase.HOLD;
	}

	public void routeCompleted() {
		if (routeOutcome == RouteOutcome.RUNNING)
			routeOutcome = RouteOutcome.COMPLETED;
	}

	/**
	 * The first failure wins the outcome; every call (re)enters the recovery
	 * phase and restarts the policy window. The window never extends past the
	 * hard control deadline (C3).
	 */
	public void routeFailed(String reason, long nowMs, long windowMs, long hardDeadlineMs) {
		if (routeOutcome == RouteOutcome.RUNNING) {
			routeOutcome = RouteOutcome.FAILED;
			failureReason = reason == null ? "" : reason;
		}
		this.hardDeadlineMs = hardDeadlineMs;
		phase = Phase.RECOVER;
		recoveryStartedMs = nowMs;
		recoveryUntilMs = Math.min(nowMs + Math.max(0L, windowMs), hardDeadlineMs);
	}

	/**
	 * The policy window expired while the hard control deadline still holds:
	 * re-select and keep ownership instead of releasing (C3). Never extends
	 * the hard deadline; returns false when only the hard deadline remains.
	 */
	public boolean rearmRecovery(long nowMs, long windowMs) {
		if (nowMs > hardDeadlineMs)
			return false;
		recoveryUntilMs = Math.min(nowMs + Math.max(0L, windowMs), hardDeadlineMs);
		return recoveryUntilMs > nowMs;
	}

	/**
	 * A host-supplied landing plan was verified and the recovery moves into
	 * its LAND phase (C2). The route outcome stays failed; only a live
	 * recovery may enter it.
	 */
	public boolean enterLanding() {
		if (phase != Phase.RECOVER)
			return false;
		phase = Phase.LAND;
		return true;
	}

	public void settle() {
		phase = Phase.SETTLED;
	}

	/** Explicit revoke: it cancels the recovery too. */
	public void revoke() {
		routeOutcome = RouteOutcome.REVOKED;
		phase = Phase.RELEASED;
		releaseReason = "revoked";
	}

	/** Releases the phase with a typed reason (C3). */
	public void release(String reason) {
		phase = Phase.RELEASED;
		releaseReason = reason == null ? "" : reason;
	}

	public boolean recoveryWindowOpen(long nowMs) {
		// Ownership and new-route refusal depend on the PHASE and the HARD
		// deadline, never on the soft policy window: the soft window expiring
		// while the hard deadline still holds must not drop the protection
		// (ab-24 audit section 3: live=false / acceptBlock=null at 7001 with
		// hard=30000).
		return (phase == Phase.RECOVER || phase == Phase.LAND) && nowMs <= hardDeadlineMs;
	}

	/** The soft policy window expired: the controller must re-decide (C3). */
	public boolean recoveryPolicyExpired(long nowMs) {
		return (phase == Phase.RECOVER || phase == Phase.LAND) && nowMs > recoveryUntilMs;
	}

	public boolean hasLiveControl(long nowMs) {
		// The hard deadline is authoritative: a recovery that re-armed inside
		// the window still stops at the authorization's end.
		return (phase == Phase.PREPARE || phase == Phase.TRACK || phase == Phase.HOLD
				|| recoveryWindowOpen(nowMs)) && nowMs <= hardDeadlineMs;
	}

	/** The reason a new route cannot start now, or {@code null} when it can. */
	public String acceptBlockReason(long nowMs) {
		if (phase == Phase.LAND && nowMs <= hardDeadlineMs)
			return "landing";
		if (phase == Phase.RECOVER && nowMs <= hardDeadlineMs)
			return "recovering";
		return null;
	}

	/**
	 * A landing leg ended without a stable end while still airborne: the
	 * recovery keeps ownership and re-selects instead of releasing the
	 * aircraft (ab-24 audit section 3). Never extends the hard deadline.
	 */
	public boolean returnToRecovery(long nowMs, long windowMs) {
		if (nowMs > hardDeadlineMs)
			return false;
		phase = Phase.RECOVER;
		recoveryUntilMs = Math.min(nowMs + Math.max(0L, windowMs), hardDeadlineMs);
		return true;
	}
}
