package dev.mcpfabric.client;

/**
 * Single input-ownership gate for the client (CD-0 §3.1).
 *
 * <p>One control session owns the player at a time. A control request carries
 * the session id and a monotonic sequence: an older sequence is rejected
 * instead of restarting the drive. When the guard clears the controls it
 * revokes the active session, and a plain observation request cannot revive a
 * cancelled drive because only control methods renew the heartbeat.
 *
 * <p>{@link #reset()} runs on every reconnect, so commands from the previous
 * connection need a new session and generation before they move the player.
 */
public final class ControlOwnership {
	private ControlOwnership() {}

	private static String activeSessionId;
	private static long lastSequence = -1;
	private static boolean revoked;

	/**
	 * Accepts a control request identity.
	 *
	 * @return false when the session was revoked or the sequence is stale.
	 * An unscoped caller (no session id) is accepted, because the mod cannot
	 * distinguish two legacy callers and must not silently drop control.
	 */
	public static synchronized boolean accept(String sessionId, long sequence) {
		if (sessionId == null || sessionId.isBlank())
			return true;
		if (revoked && sessionId.equals(activeSessionId))
			return false;
		if (!sessionId.equals(activeSessionId)) {
			activeSessionId = sessionId;
			lastSequence = sequence;
			revoked = false;
			return true;
		}
		if (sequence <= lastSequence)
			return false;
		lastSequence = sequence;
		return true;
	}

	/** Revokes the active session so no later request can revive it. */
	public static synchronized void revoke() {
		if (activeSessionId != null)
			revoked = true;
	}

	/** Forgets all ownership; called on reconnect so a new session is required. */
	public static synchronized void reset() {
		activeSessionId = null;
		lastSequence = -1;
		revoked = false;
	}

	/** True when the given session was revoked and is not active any more. */
	public static synchronized boolean isRevoked(String sessionId) {
		return revoked && sessionId != null && sessionId.equals(activeSessionId);
	}
}
