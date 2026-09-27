package dev.mcpfabric.client.flight;

import java.util.List;

/**
 * Validates a host-supplied landing site from the live client state (ab-23
 * repair plan, C2).
 *
 * <p>The host owns site selection (its {@code flight/landing-site.ts} rules);
 * the client owns the entry and execution check. A usable site does not mean
 * the current velocity can reach it, so the client runs the same bounded
 * prediction the route driver uses: a two-point leg from the measured state to
 * the site entry, swept with the body box and the shared geometry, and the
 * site's support surface must be safe with standing headroom.
 */
public final class FlightLandingPlan {
	private FlightLandingPlan() {}

	/** The landing approach horizon, longer than the route screening horizon. */
	private static final int LANDING_PROBE_HORIZON = 40;

	/** A landing site in world coordinates; {@code y} is the entry altitude. */
	public record Site(double x, double y, double z, double contactY) {}

	/** The validation outcome; {@code reason} is empty when accepted. */
	public record Result(boolean accepted, String reason) {
		static Result accept() {
			return new Result(true, "");
		}

		static Result refuse(String reason) {
			return new Result(false, reason);
		}
	}

	/**
	 * Runs the bounded reachability and support check for one site.
	 *
	 * @param blocks the same geometry boundary the route driver uses
	 * @param params the driver's parameters (candidate set and limits)
	 * @param deadlineMs the hard control deadline, so the probe cannot outlive
	 *        the authorization
	 * @param rocketTicks remaining boost ticks at the measured state
	 * @param rockets usable rockets at the measured state
	 */
	public static Result validate(FlightSession.BlockQuery blocks, long deadlineMs, FlightSession.Params params,
			double x, double y, double z, double vx, double vy, double vz,
			float yaw, float pitch, int rocketTicks, int rockets, Site site) {
		if (site == null)
			return Result.refuse("no_site");
		if (Double.isNaN(site.x()) || Double.isNaN(site.y()) || Double.isNaN(site.z()))
			return Result.refuse("bad_site");
		// The support the host reports must be a real one from the client's
		// own view: water, leaves and plants are never a landing.
		String support = blocks.idAt(site.x(), site.contactY() - 1.0, site.z());
		if (FlightGeometry.isUnsafeSupport(support))
			return Result.refuse("unsafe_support");
		// Standing headroom above the contact.
		for (double probe = site.contactY(); probe < site.contactY() + 3.0; probe += 1.0) {
			if (!FlightGeometry.isFlyableThrough(blocks.idAt(site.x(), probe, site.z())))
				return Result.refuse("no_headroom");
		}
		FlightSession.Params probeParams = new FlightSession.Params();
		probeParams.yawOffsets = params.yawOffsets;
		probeParams.aimPullDegrees = params.aimPullDegrees;
		probeParams.pullUpDegrees = params.pullUpDegrees;
		probeParams.maxCandidates = params.maxCandidates;
		probeParams.yawRateLimitDegrees = params.yawRateLimitDegrees;
		probeParams.pitchRateLimitDegrees = params.pitchRateLimitDegrees;
		// The approach horizon is longer than the route screening horizon: a
		// landing site tens of blocks out is a legitimate approach, not an
		// unreachable one (the route horizon screens near-term obstacles only).
		probeParams.horizonTicks = Math.max(params.horizonTicks, LANDING_PROBE_HORIZON);
		probeParams.simBudgetMs = Math.max(params.simBudgetMs, 250);
		FlightSession probe = new FlightSession(
				List.of(new FlightSession.Waypoint(x, y, z),
						new FlightSession.Waypoint(site.x(), site.y(), site.z())),
				blocks,
				deadlineMs,
				probeParams,
				6_000L);
		// A bounded turn starts from the measured pose, just as LAND does.
		FlightSession.Decision decision = probe.tick(x, y, z, vx, vy, vz, yaw, pitch, rockets, rocketTicks, 0, false);
		if (!decision.applicable)
			return Result.refuse("landing_unreachable");
		if (decision.predictedEndCursor < 2)
			return Result.refuse("landing_unreachable");
		return Result.accept();
	}
}
