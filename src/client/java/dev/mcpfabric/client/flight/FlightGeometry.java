package dev.mcpfabric.client.flight;

/**
 * The one geometry boundary shared by the route driver, the terminal
 * verification and the recovery (ab-23 repair plan, C1).
 *
 * <p>Three different questions must not share one answer:
 *
 * <ul>
 * <li>Is an air trajectory legal? Water is a forbidden medium and leaves are
 * obstacles — {@link #isFlyableThrough}.</li>
 * <li>What is the support/surface height below? Water counts as a surface (do
 * not read through to the riverbed); leaves are a collision surface but never
 * a safe landing — {@link #surfaceBelow} plus the landing checks in
 * {@code FlightSession}.</li>
 * <li>Has the player actually entered water? That is the measured medium on
 * the player, not a block query.</li>
 * </ul>
 *
 * <p>Whole-block conservative semantics: a water block at y=62 occupies
 * [62, 63), so its top is 63 — the block coordinate is never the real
 * clearance.
 */
public final class FlightGeometry {
	private FlightGeometry() {}

	/** Cells a prediction may pass through: air and other empty-collision cells. */
	public static boolean isFlyableThrough(String id) {
		return isAirLike(id);
	}

	/**
	 * Cells that count as air for surface scans: water is a surface, not air,
	 * so a scan below a low glider stops at the water top.
	 */
	public static boolean isAirLike(String id) {
		return id != null && (id.isEmpty() || id.endsWith("air") || id.equals("passable"));
	}

	/** Water by id, including the client's normalized {@code minecraft:water}. */
	public static boolean isWater(String id) {
		return id != null && id.endsWith("water");
	}

	/** Blocks that must never count as a landing support (B4/C1). */
	public static boolean isUnsafeSupport(String id) {
		if (id == null || id.equals("unknown") || isAirLike(id))
			return true;
		return id.endsWith("water") || id.endsWith("leaves") || id.endsWith("lava")
				|| id.endsWith("fire") || id.endsWith("sweet_berry_bush");
	}

	/**
	 * The highest solid top at or below {@code fromY}, scanned at most
	 * {@code depth} blocks down. Water and unknown cells stop the scan (a
	 * surface), leaves stop it too (a collision surface).
	 */
	public static double surfaceBelow(FlightSession.BlockQuery blocks, double x, double z,
			double fromY, double depth) {
		// Query integer voxels: a fractional aircraft height must not move
		// the surface plane by that same fractional amount.
		for (double probe = Math.ceil(fromY) - 1; probe > fromY - depth; probe -= 1.0) {
			String id = blocks.idAt(x, probe, z);
			if (id == null || !isAirLike(id))
				return probe + 1.0;
		}
		return fromY - depth;
	}
}
