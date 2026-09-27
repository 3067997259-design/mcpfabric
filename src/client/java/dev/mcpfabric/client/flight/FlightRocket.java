package dev.mcpfabric.client.flight;

/** Recipe-dependent lifetime estimates. Server randomness and packet age remain unknown.
 * Vanilla 1.21.1 uses 10 * (1 + flightDuration) + nextInt(6) + nextInt(7),
 * then expires after life exceeds lifetime. These are model inputs, not expiry promises.
 */
public final class FlightRocket {
	private FlightRocket() {}
	public static int minimumTicks(int duration) { return 10 * (1 + Math.max(0, duration)) + 1; }
	public static int maximumTicks(int duration) { return minimumTicks(duration) + 11; }
	public static int nominalTicks(int duration) { return minimumTicks(duration) + 5; }
	/** An observed live entity must never become an unpowered state by countdown alone. */
	public static int remainingEstimate(int duration, int observedAge) {
		return Math.max(1, nominalTicks(duration) - Math.max(0, observedAge));
	}
}
