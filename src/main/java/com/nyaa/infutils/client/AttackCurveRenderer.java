package com.nyaa.infutils.client;

import com.nyaa.infutils.NyaaInfiniteInfernalUtils;
import com.nyaa.infutils.config.ModConfig;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.Tessellator;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures particles spawned on the client (which includes every particle the
 * server sends) and, when several of them sit close together, re-renders them
 * as connected "attack curves" instead of the default particle sprites.
 * <p>
 * Rendering is driven from {@code WorldRenderEvents.AFTER_ENTITIES}. Geometry is
 * submitted in <b>camera-relative</b> coordinates (world position minus the
 * camera position): Minecraft renders the whole world relative to the camera, so
 * feeding absolute world coordinates would place the lines thousands of blocks
 * away and they would be culled — which is exactly why nothing showed up before.
 * <p>
 * We use the vanilla {@code LINES} / {@code LINES_TRANSLUCENT} pipelines (the
 * same immediate-mode path the vanilla debug renderer uses), which is drawn
 * correctly even with shader mods such as Iris.
 */
public final class AttackCurveRenderer {

    /** Representative colours (RGB) for particle types that have no explicit colour. */
    private static final Map<String, float[]> TYPE_COLORS = new HashMap<>();

    static {
        TYPE_COLORS.put("dragon_breath", rgb(0xB050C0));
        TYPE_COLORS.put("flame", rgb(0xFF9020));
        TYPE_COLORS.put("small_flame", rgb(0xFF9020));
        TYPE_COLORS.put("soul_fire_flame", rgb(0x2FE6E6));
        TYPE_COLORS.put("soul", rgb(0x9FE6E6));
        TYPE_COLORS.put("smoke", rgb(0x606060));
        TYPE_COLORS.put("large_smoke", rgb(0x505050));
        TYPE_COLORS.put("campfire_cosy_smoke", rgb(0x707070));
        TYPE_COLORS.put("portal", rgb(0x8020C0));
        TYPE_COLORS.put("witch", rgb(0xC030C0));
        TYPE_COLORS.put("happy_villager", rgb(0x30D030));
        TYPE_COLORS.put("angry_villager", rgb(0xB05020));
        TYPE_COLORS.put("heart", rgb(0xFF4060));
        TYPE_COLORS.put("crit", rgb(0xFFD040));
        TYPE_COLORS.put("enchanted_hit", rgb(0x40D0FF));
        TYPE_COLORS.put("electric_spark", rgb(0x60D0FF));
        TYPE_COLORS.put("end_rod", rgb(0xF0F0F0));
        TYPE_COLORS.put("firework", rgb(0xFFE060));
        TYPE_COLORS.put("dolphin", rgb(0x60A0FF));
        TYPE_COLORS.put("underwater", rgb(0x80B0FF));
        TYPE_COLORS.put("bubble", rgb(0x90C0FF));
        TYPE_COLORS.put("splash", rgb(0x90C0FF));
        TYPE_COLORS.put("spit", rgb(0xE0E0D0));
        TYPE_COLORS.put("snowflake", rgb(0xE0F0FF));
        TYPE_COLORS.put("wax_on", rgb(0xFFC040));
        TYPE_COLORS.put("glow", rgb(0x40E0C0));
        TYPE_COLORS.put("sculk_soul", rgb(0x2FE0C0));
        TYPE_COLORS.put("sculk_charge", rgb(0x20C0B0));
        TYPE_COLORS.put("wither", rgb(0x303030));
        TYPE_COLORS.put("lava", rgb(0xFF6010));
        TYPE_COLORS.put("dripping_lava", rgb(0xFF6010));
        TYPE_COLORS.put("cherry_leaves", rgb(0xFFC0D0));
    }

    private static float[] rgb(int hex) {
        return new float[]{((hex >> 16) & 0xFF) / 255F, ((hex >> 8) & 0xFF) / 255F, (hex & 0xFF) / 255F};
    }

    /** A captured particle: world position + colour + type id + the world tick it was seen. */
    public static record CapturedPoint(double x, double y, double z, float r, float g, float b,
                                       String type, long time) {
    }

    private static final List<CapturedPoint> captured = new ArrayList<>();

    // Stats for the debug HUD.
    private static int lastCaptured = 0;
    private static int lastPoints = 0;
    private static int lastCurves = 0;

    // Diagnostics.
    private static long totalCapturedParticles = 0;
    private static boolean loggedRenderFired = false;
    private static int loggedDrawCalls = 0;

    // --- Isolated particle fade-out: after normal lifetime, each isolated glow
    //     point waits a random delay, then fades out while drifting upward. ---
    private static final int ISOLATED_FADE_DELAY_TICKS = 4;    // max random delay (ticks)
    private static final int ISOLATED_FADE_DURATION_TICKS = 8; // fade-out duration (ticks)
    private static final float ISOLATED_FADE_DRIFT = 0.6F;      // upward drift (blocks)

    // Curve cache: the builder is O(n^2) (plus an O(m^3)-ish merge pass), so we
    // only rebuild when the captured set actually changes, and reuse the cached
    // geometry on every other frame. Running the builder every frame was the cause
    // of the frame drops while moving (many attack particles in flight at once).
    private static boolean curvesDirty = true;
    private static CurveBuild cachedCurves = new CurveBuild(new ArrayList<>(), new ArrayList<>());

    private AttackCurveRenderer() {
    }

    /** Called from {@link net.minecraft.client.world.ClientWorld} mixin.
     *  @param isServer true when the particle arrived on the 8-arg overload, i.e. it was
     *              sent by the server (ClientPlayNetworkHandler → ParticleS2CPacket). This is
     *              the reliable "this is an InfiniteInfernal attack / ability particle" signal.
     *              false means it is a client-local particle such as the End gateway beam or
     *              eating particles — those should not become curves.
     *  @return true if the particle was captured for a curve (caller may suppress the sprite). */
    public static boolean onServerParticle(ParticleEffect effect, double x, double y, double z, boolean isServer) {
        ModConfig config = NyaaInfiniteInfernalUtils.CONFIG;
        if (config == null || !config.attackCurve.enabled) {
            return false;
        }
        String typeId = Registries.PARTICLE_TYPE.getId(effect.getType()).toString();

        // --- Explicit allow-list: when the user listed particle types, honour it and
        //     capture those regardless of the force flag (power-user override). ---
        boolean inAllowList = false;
        if (!config.attackCurve.particleTypes.isEmpty()) {
            for (String t : config.attackCurve.particleTypes) {
                if (typeId.contains(t)) {
                    inAllowList = true;
                    break;
                }
            }
            if (!inAllowList) {
                return false;
            }
        }

        // --- Exclude list: never capture these (e.g. the End gateway portal effect). ---
        for (String t : config.attackCurve.excludeParticleTypes) {
            if (typeId.contains(t)) {
                return false;
            }
        }

        // --- Attack-only gate (default ON): only turn a particle into a curve when it was
        //     sent by the server (isServer). The InfiniteInfernal attack / ability particles
        //     all arrive on the 8-arg ClientWorld overload (isServer=true); vanilla
        //     world/player effects (End gateway beam, eating, sprinting, …) are spawned
        //     locally on the 6-arg overload (isServer=false) and are left alone. A user who
        //     instead filled particleTypes above has opted in explicitly and skips this gate.
        //     NOTE: the server's `forced` flag (force=true) is NOT used here — many attack
        //     particles arrive with force=false, so gating on it wrongly drops them. ---
        if (config.attackCurve.attackOnly && !inAllowList && !isServer) {
            // Local (non-server) particle: not an attack curve, just skip it.
            // NOTE: intentionally no logging here — local particles (End gateway
            // beam, eating, sprinting, …) arrive every frame and logging them
            // would flood the log.
            return false;
        }

        if (captured.size() >= config.attackCurve.maxTrackedParticles) {
            return false;
        }

        float[] c = extractColor(config, effect);
        long now = currentWorldTime();
        captured.add(new CapturedPoint(x, y, z, c[0], c[1], c[2], typeId, now));
        curvesDirty = true; // a new point arrived -> cached geometry is stale
        totalCapturedParticles++;
        if (totalCapturedParticles <= 5 || totalCapturedParticles % 500 == 0) {
            ParticleType<?> type = effect.getType();
            NyaaInfiniteInfernalUtils.LOGGER.info(
                    "[infutils][curve] captured particle #{} type={} pos=({},{},{}) color=({},{},{})",
                    totalCapturedParticles, Registries.PARTICLE_TYPE.getId(type), x, y, z, c[0], c[1], c[2]);
        }
        return true;
    }

    /** Current client world time in ticks (0 if not in a world). */
    private static long currentWorldTime() {
        net.minecraft.client.world.ClientWorld world = MinecraftClient.getInstance().world;
        return world != null ? world.getTime() : 0L;
    }

    /** Resolve the colour to draw a captured particle with. */
    private static float[] extractColor(ModConfig config, ParticleEffect effect) {
        if (config.attackCurve.useParticleColor) {
            if (effect instanceof DustParticleEffect dust) {
                Vector3f col = dust.getColor();
                return new float[]{col.x(), col.y(), col.z()};
            }
            if (effect instanceof DustColorTransitionParticleEffect dust) {
                Vector3f col = dust.getFromColor();
                return new float[]{col.x(), col.y(), col.z()};
            }
            String path = Registries.PARTICLE_TYPE.getId(effect.getType()).getPath();
            float[] known = TYPE_COLORS.get(path);
            if (known != null) {
                return known.clone();
            }
            // Fallback: stable pleasant colour derived from the type id hash.
            int h = path.hashCode();
            float hue = ((h & 0x7FFFFFFF) % 360) / 360F;
            return hsvToRgb(hue, 0.55F, 1.0F);
        }
        // Custom fixed colour.
        int color = config.attackCurve.curveColor;
        return rgb(color);
    }

    private static float[] hsvToRgb(float h, float s, float v) {
        float r = 0, g = 0, b = 0;
        int i = (int) (h * 6);
        float f = h * 6 - i;
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            case 5 -> { r = v; g = p; b = q; }
        }
        return new float[]{r, g, b};
    }

    /** Called from WorldRenderEvents.AFTER_ENTITIES. */
    public static void render(WorldRenderContext context) {
        if (!FeatureGate.active()) {
            captured.clear();
            curvesDirty = true;
            return;
        }
        ModConfig config = NyaaInfiniteInfernalUtils.CONFIG;
        if (config == null || !config.attackCurve.enabled) {
            captured.clear();
            curvesDirty = true;
            return;
        }
        if (!loggedRenderFired) {
            loggedRenderFired = true;
            NyaaInfiniteInfernalUtils.LOGGER.info("[infutils][curve] render (AFTER_ENTITIES) is firing");
        }

        // Forget points that are older than the extended lifetime (normal +
        // fade delay + fade duration) so the band trails behind the attack and
        // isolated points have time to fade out instead of popping.
        long now = currentWorldTime();
        long lifetime = config.attackCurve.particleLifetimeTicks;
        long extendedLifetime = lifetime + ISOLATED_FADE_DELAY_TICKS + ISOLATED_FADE_DURATION_TICKS;
        int sizeBefore = captured.size();
        captured.removeIf(p -> now - p.time() > extendedLifetime);
        if (captured.size() != sizeBefore) {
            curvesDirty = true; // some points expired -> cached geometry is stale
        }

        lastCaptured = captured.size();
        if (captured.isEmpty()) {
            return;
        }

        // Rebuild the curves only when the captured set actually changed; otherwise
        // reuse the cached geometry. The builder is O(n^2) (and worse with the
        // merge pass), so running it every frame is what dropped frames while moving.
        CurveBuild result;
        if (curvesDirty) {
            result = buildCurves(captured,
                    config.attackCurve.maxSegmentDistance,
                    config.attackCurve.minCurvePoints);
            cachedCurves = result;
            curvesDirty = false;
        } else {
            result = cachedCurves;
        }
        lastPoints = captured.size();
        lastCurves = result.curves.size();

        Vec3d cam = MinecraftClient.getInstance().gameRenderer.getCamera().getCameraPos();
        Matrix4f mat = context.matrices().peek().getPositionMatrix();

        // Continuous, nearby-connected particles -> a glowing attack curve.
        if (!result.curves.isEmpty()) {
            drawBands(mat, cam, result.curves, config.attackCurve);
        }
        // Particles that never connected to anything (e.g. the scattered burst
        // around a summoned creature) -> render each as a glowing shape.
        if (!result.isolated.isEmpty()) {
            drawGlowShapes(mat, cam, result.isolated, config.attackCurve, now, lifetime);
        }
    }

    /**
     * Builds continuous attack curves from captured particles.
     * <p>
     * For every untaken point we grow a chain in <b>both</b> directions by
     * nearest compatible neighbour, so a trail is consumed as one ordered
     * polyline instead of leaving scattered "behind" points that would otherwise
     * render as a gap in the middle of the band. A second merge pass then links
     * curve ends that are still close & colour-compatible — this stitches back
     * together two halves of the same stroke that a single dropped point had
     * briefly split. Finally, only chains long enough are kept as bands; the
     * rest fall back to isolated glow shapes.
     */
    private static CurveBuild buildCurves(List<CapturedPoint> points, double maxDist, int minPts) {
        double maxSq = maxDist * maxDist;
        boolean[] taken = new boolean[points.size()];

        List<List<CapturedPoint>> curves = new ArrayList<>();
        for (int s = 0; s < points.size(); s++) {
            if (taken[s]) {
                continue;
            }
            List<CapturedPoint> curve = new ArrayList<>();
            curve.add(points.get(s));
            taken[s] = true;
            extendChain(curve, points, taken, maxSq, true);
            extendChain(curve, points, taken, maxSq, false);
            curves.add(curve);
        }

        // Stitch nearby curve ends together (a single dropped particle briefly split
        // the same stroke). This MUST respect the same minimum connection distance
        // (maxSq) as the per-point connection — otherwise two unrelated, continuously
        // spawned trails that merely happen to end within a few blocks would get
        // welded into one band, which is exactly the "everything connects" bug.
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < curves.size(); i++) {
                for (int j = 0; j < curves.size(); j++) {
                    if (i == j) {
                        continue;
                    }
                    List<CapturedPoint> mergedCurve = tryMerge(curves.get(i), curves.get(j), maxSq);
                    if (mergedCurve != null) {
                        curves.set(i, mergedCurve);
                        curves.remove(j);
                        merged = true;
                        break outer;
                    }
                }
            }
        }

        List<List<CapturedPoint>> bands = new ArrayList<>();
        List<CapturedPoint> isolated = new ArrayList<>();
        for (List<CapturedPoint> curve : curves) {
            if (curve.size() >= minPts) {
                bands.add(curve);
            } else {
                isolated.addAll(curve);
            }
        }
        return new CurveBuild(bands, isolated);
    }

    /** Grows {@code curve} by one endpoint (forward tail or backward head). */
    private static void extendChain(List<CapturedPoint> curve, List<CapturedPoint> points,
                                   boolean[] taken, double maxSq, boolean forward) {
        boolean extended = true;
        while (extended) {
            extended = false;
            CapturedPoint current = forward ? curve.get(curve.size() - 1) : curve.get(0);
            int bestIdx = -1;
            double bestSq = maxSq;
            for (int i = 0; i < points.size(); i++) {
                if (taken[i]) {
                    continue;
                }
                CapturedPoint cand = points.get(i);
                if (!canConnect(current, cand, maxSq)) {
                    continue;
                }
                double d = distSq(current, cand);
                if (d <= bestSq) {
                    bestSq = d;
                    bestIdx = i;
                }
            }
            if (bestIdx >= 0) {
                taken[bestIdx] = true;
                if (forward) {
                    curve.add(points.get(bestIdx));
                } else {
                    curve.add(0, points.get(bestIdx));
                }
                extended = true;
            }
        }
    }

    /**
     * If two curves meet end-to-end within {@code maxSq} and share type/colour,
     * returns the merged ordered list; otherwise null. Tries every end combination
     * (including reversing either curve) so orientation does not matter.
     */
    private static List<CapturedPoint> tryMerge(List<CapturedPoint> a, List<CapturedPoint> b, double linkSq) {
        CapturedPoint aHead = a.get(0);
        CapturedPoint aTail = a.get(a.size() - 1);
        CapturedPoint bHead = b.get(0);
        CapturedPoint bTail = b.get(b.size() - 1);

        List<CapturedPoint> result = null;
        if (canConnect(aTail, bHead, linkSq)) {
            result = new ArrayList<>(a);
            result.addAll(b);
        } else if (canConnect(aTail, bTail, linkSq)) {
            result = new ArrayList<>(a);
            List<CapturedPoint> rev = new ArrayList<>(b);
            java.util.Collections.reverse(rev);
            result.addAll(rev);
        } else if (canConnect(aHead, bTail, linkSq)) {
            List<CapturedPoint> rev = new ArrayList<>(a);
            java.util.Collections.reverse(rev);
            result = new ArrayList<>(rev);
            result.addAll(b);
        } else if (canConnect(aHead, bHead, linkSq)) {
            List<CapturedPoint> rev = new ArrayList<>(a);
            java.util.Collections.reverse(rev);
            result = new ArrayList<>(rev);
            List<CapturedPoint> revB = new ArrayList<>(b);
            java.util.Collections.reverse(revB);
            result.addAll(revB);
        }
        return result;
    }

    private record CurveBuild(List<List<CapturedPoint>> curves, List<CapturedPoint> isolated) {
    }

    /**
     * Two points may be linked only if same particle type and spatially close.
     * Colour is intentionally NOT a barrier: a single attack's particles often
     * fade / gradient between tints (e.g. dust_colour_transition). Requiring
     * near-identical colour here was splitting one continuous arc into several
     * disconnected pieces — the "breaking in the middle" the user saw. Colour is
     * still used purely for shading, so dropping the constraint does not change
     * how the curve looks, only that it stays connected.
     */
    private static boolean canConnect(CapturedPoint a, CapturedPoint b, double maxSq) {
        if (a.type == null || !a.type.equals(b.type)) {
            return false;
        }
        return distSq(a, b) <= maxSq;
    }

    private static double distSq(CapturedPoint a, CapturedPoint b) {
        double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Draws each curve as a solid, camera-independent TUBE (a cylinder of triangles
     * around the resampled centerline) instead of a flat billboard ribbon.
     * <p>
     * The old ribbon was a 2-vertex strip whose normal was {@code tangent × viewDir}.
     * When an attack is aimed straight at (or away from) the camera the tangent lines
     * up with the view direction, the ribbon turns edge-on and collapses to a thin
     * line — exactly the "flat line" the user reported. A tube, by contrast, has real
     * volume from every viewing angle because its cross-section is built from a basis
     * {@code (U, V)} orthogonal to the tangent, so the band is a full ring, not a
     * single facing plane.
     * <p>
     * Every segment is first resampled with a Catmull-Rom spline into dense
     * sub-points (so the tube follows the curve smoothly), the half-width tapers
     * along the stroke (thin ends, thick middle), and two concentric tubes (a wider
     * faint glow + a narrower bright core driven by {@code opacity}) fake the additive
     * "energized" look. Uses the vanilla LIGHTNING layer (additive, no texture).
     */
    private static void drawBands(Matrix4f mat, Vec3d cam, List<List<CapturedPoint>> curves,
                                  ModConfig.AttackCurveSettings s) {
        float baseHalf = Math.max(0.04F, s.lineWidth * 0.12F * s.scaleBandWidth);
        int subSteps = 4;   // sub-segments per captured point pair (smoothing)
        int radial = 7;     // tube cross-section sides (>=3 gives real volume)
        float coreOpacity = Math.max(0.05F, Math.min(1.0F, s.opacity));
        float glowOpacity = coreOpacity * 0.45F;

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder builder = tessellator.begin(
                VertexFormat.DrawMode.TRIANGLES, RenderLayers.lightning().getVertexFormat());
        for (List<CapturedPoint> curve : curves) {
            int n = curve.size();
            if (n < 2) {
                continue;
            }
            // Resample the curve into a dense, smooth centerline. Each entry is
            // {x, y, z, halfWidth, r, g, b} in WORLD coordinates.
            List<float[]> cl = new ArrayList<>();
            for (int i = 0; i < n - 1; i++) {
                CapturedPoint p0 = curve.get(Math.max(i - 1, 0));
                CapturedPoint p1 = curve.get(i);
                CapturedPoint p2 = curve.get(i + 1);
                CapturedPoint p3 = curve.get(Math.min(i + 2, n - 1));
                float wA = halfWidth(i, n, baseHalf);
                float wB = halfWidth(i + 1, n, baseHalf);
                float r1 = p1.r, g1 = p1.g, b1 = p1.b;
                float r2 = p2.r, g2 = p2.g, b2 = p2.b;
                int startK = (i == 0) ? 0 : 1; // skip duplicate of previous tail
                for (int k = startK; k <= subSteps; k++) {
                    float u = (float) k / subSteps;
                    float[] cp = catmull(p0, p1, p2, p3, u);
                    float hw = wA + (wB - wA) * u;
                    float r = r1 + (r2 - r1) * u;
                    float g = g1 + (g2 - g1) * u;
                    float bb = b1 + (b2 - b1) * u;
                    cl.add(new float[]{cp[0], cp[1], cp[2], hw, r, g, bb});
                }
            }

            int m = cl.size();
            if (m < 2) {
                continue;
            }

            // Camera-relative centerline positions + per-point half-width.
            float[][] pos = new float[m][3];
            float[] hw = new float[m];
            for (int i = 0; i < m; i++) {
                float[] c = cl.get(i);
                pos[i][0] = (float) (c[0] - cam.x);
                pos[i][1] = (float) (c[1] - cam.y);
                pos[i][2] = (float) (c[2] - cam.z);
                hw[i] = c[3];
            }

            // Outer glow tube first, then the bright core on top (additive → brightens).
            emitTube(builder, mat, cl, pos, hw, m, radial, 1.9F, glowOpacity);
            emitTube(builder, mat, cl, pos, hw, m, radial, 1.0F, coreOpacity);
        }
        RenderLayers.lightning().draw(builder.end());
    }

    /**
     * Emits a tube of {@code radial} sides around the resampled centerline. For each
     * centerline point we compute a tangent (from its neighbours) and build a ring
     * basis {@code (U, V)} orthogonal to it, so the cross-section is a full ring in
     * the plane perpendicular to the stroke — giving the band real volume from any
     * camera angle. {@code radiusMul} scales the ring radius (1.0 = core, >1 = glow).
     */
    private static void emitTube(BufferBuilder b, Matrix4f mat, List<float[]> cl,
                                float[][] pos, float[] hw, int m, int radial,
                                float radiusMul, float opacity) {
        float[][] uAxis = new float[m][3];
        float[][] vAxis = new float[m][3];
        for (int i = 0; i < m; i++) {
            float[] nxt = pos[Math.min(i + 1, m - 1)];
            float[] prv = pos[Math.max(i - 1, 0)];
            float tx = nxt[0] - prv[0], ty = nxt[1] - prv[1], tz = nxt[2] - prv[2];
            float tlen = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            if (tlen < 1e-6F) { tx = 0F; ty = 1F; tz = 0F; tlen = 1F; }
            float inv = 1F / tlen; tx *= inv; ty *= inv; tz *= inv;
            // Reference vector least parallel to the tangent.
            float ax = Math.abs(tx), ay = Math.abs(ty), az = Math.abs(tz);
            float rx, ry, rz;
            if (ax <= ay && ax <= az) { rx = 1F; ry = 0F; rz = 0F; }
            else if (ay <= az)       { rx = 0F; ry = 1F; rz = 0F; }
            else                     { rx = 0F; ry = 0F; rz = 1F; }
            // U = normalize(T x R)
            float ux = ty * rz - tz * ry, uy = tz * rx - tx * rz, uz = tx * ry - ty * rx;
            float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
            if (ul < 1e-6F) { ux = 1F; uy = 0F; uz = 0F; ul = 1F; }
            float uinv = 1F / ul; ux *= uinv; uy *= uinv; uz *= uinv;
            // V = T x U
            float vx = ty * uz - tz * uy, vy = tz * ux - tx * uz, vz = tx * uy - ty * ux;
            uAxis[i][0] = ux; uAxis[i][1] = uy; uAxis[i][2] = uz;
            vAxis[i][0] = vx; vAxis[i][1] = vy; vAxis[i][2] = vz;
        }

        double step = 2.0 * Math.PI / radial;
        for (int i = 0; i < m - 1; i++) {
            float rMid = (cl.get(i)[4] + cl.get(i + 1)[4]) * 0.5F;
            float gMid = (cl.get(i)[5] + cl.get(i + 1)[5]) * 0.5F;
            float bMid = (cl.get(i)[6] + cl.get(i + 1)[6]) * 0.5F;
            float rA = hw[i] * radiusMul, rB = hw[i + 1] * radiusMul;
            for (int k = 0; k < radial; k++) {
                int k2 = (k + 1) % radial;
                float cA = (float) Math.cos(k * step), sA = (float) Math.sin(k * step);
                float cB = (float) Math.cos(k2 * step), sB = (float) Math.sin(k2 * step);
                // Ring A point k and k+1.
                float ax = pos[i][0]   + (uAxis[i][0] * cA + vAxis[i][0] * sA) * rA;
                float ay = pos[i][1]   + (uAxis[i][1] * cA + vAxis[i][1] * sA) * rA;
                float az = pos[i][2]   + (uAxis[i][2] * cA + vAxis[i][2] * sA) * rA;
                float bx = pos[i][0]   + (uAxis[i][0] * cB + vAxis[i][0] * sB) * rA;
                float by = pos[i][1]   + (uAxis[i][1] * cB + vAxis[i][1] * sB) * rA;
                float bz = pos[i][2]   + (uAxis[i][2] * cB + vAxis[i][2] * sB) * rA;
                // Ring B (i+1) point k and k+1.
                float cx = pos[i + 1][0] + (uAxis[i + 1][0] * cA + vAxis[i + 1][0] * sA) * rB;
                float cy = pos[i + 1][1] + (uAxis[i + 1][1] * cA + vAxis[i + 1][1] * sA) * rB;
                float cz = pos[i + 1][2] + (uAxis[i + 1][2] * cA + vAxis[i + 1][2] * sA) * rB;
                float dx = pos[i + 1][0] + (uAxis[i + 1][0] * cB + vAxis[i + 1][0] * sB) * rB;
                float dy = pos[i + 1][1] + (uAxis[i + 1][1] * cB + vAxis[i + 1][1] * sB) * rB;
                float dz = pos[i + 1][2] + (uAxis[i + 1][2] * cB + vAxis[i + 1][2] * sB) * rB;
                emitTri(b, mat, ax, ay, az, cx, cy, cz, bx, by, bz, rMid, gMid, bMid, opacity);
                emitTri(b, mat, bx, by, bz, cx, cy, cz, dx, dy, dz, rMid, gMid, bMid, opacity);
            }
        }
    }

    /** Emits one triangle (CCW winding) in camera-relative coordinates. */
    private static void emitTri(BufferBuilder b, Matrix4f mat,
                                float ax, float ay, float az,
                                float bx, float by, float bz,
                                float cx, float cy, float cz,
                                float r, float g, float bb, float a) {
        b.vertex(mat, ax, ay, az).color(r, g, bb, a);
        b.vertex(mat, bx, by, bz).color(r, g, bb, a);
        b.vertex(mat, cx, cy, cz).color(r, g, bb, a);
    }

    /** Half-width (in blocks) at control point i of n: thin at the ends, thick in the middle. */
    private static float halfWidth(int i, int n, float baseHalf) {
        float t = (float) i / (n - 1);
        return baseHalf * (0.35F + 0.65F * (float) Math.sin(t * Math.PI));
    }

    /** Catmull-Rom interpolation between p1 and p2 using p0..p3 as context. */
    private static float[] catmull(CapturedPoint p0, CapturedPoint p1, CapturedPoint p2,
                                   CapturedPoint p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        float x = (float) (0.5 * ((2 * p1.x)
                + (-p0.x + p2.x) * t
                + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2
                + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3));
        float y = (float) (0.5 * ((2 * p1.y)
                + (-p0.y + p2.y) * t
                + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2
                + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3));
        float z = (float) (0.5 * ((2 * p1.z)
                + (-p0.z + p2.z) * t
                + (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * t2
                + (-p0.z + 3 * p1.z - 3 * p2.z + p3.z) * t3));
        return new float[]{x, y, z};
    }

    /**
     * Scattered, isolated particles (e.g. the burst of sparks around a summoned
     * creature) are drawn as a small camera-facing glowing sphere at each point
     * instead of being wasted on a broken curve. Each particle becomes a billboard
     * disc: a triangle fan from the bright centre out to a rim, so it reads as a
     * filled round glow (the standard Minecraft particle look) from any angle. Two
     * concentric passes (a faint wide halo + a bright core) give it volume.
     */
    private static void drawGlowShapes(Matrix4f mat, Vec3d cam, List<CapturedPoint> points,
                                       ModConfig.AttackCurveSettings s, long now, long lifetime) {
        if (points.isEmpty()) {
            return;
        }
        // Camera-facing basis (right/up in screen space) so the disc always faces us.
        Camera camera = MinecraftClient.getInstance().gameRenderer.getCamera();
        Quaternionf rot = camera.getRotation();
        Vector3f right = new Vector3f(1.0F, 0.0F, 0.0F).rotate(rot);
        Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F).rotate(rot);
        // Diameter is 1/3 of the previous size: radius = max(0.15/3, lineWidth*0.18/3).
        float radius = Math.max(0.05F, s.lineWidth * 0.06F);
        int segments = 12;

        // Pre-compute visible particles (skip fully-faded ones) so we only begin
        // the BufferBuilder when there is at least one disc to draw — calling
        // end() on an empty buffer throws IllegalStateException.
        List<float[]> visible = new ArrayList<>();
        for (CapturedPoint p : points) {
            long age = now - p.time();
            float opacityMul = 1.0F;
            float yOffset = 0.0F;

            // Past normal lifetime: random delay, then fade out + float upward.
            if (age > lifetime) {
                long overTime = age - lifetime;
                // Deterministic per-particle random delay (stable across frames).
                int hash = (int) (Double.doubleToLongBits(p.x) ^ Double.doubleToLongBits(p.y)
                        ^ Double.doubleToLongBits(p.z) ^ p.time());
                int delay = Math.abs(hash) % ISOLATED_FADE_DELAY_TICKS;
                if (overTime > delay) {
                    float fadeProgress = Math.min(1.0F,
                            (float) (overTime - delay) / ISOLATED_FADE_DURATION_TICKS);
                    opacityMul = 1.0F - fadeProgress;
                    yOffset = fadeProgress * ISOLATED_FADE_DRIFT;
                }
            }
            if (opacityMul <= 0.001F) {
                continue;
            }
            float cx = (float) (p.x - cam.x);
            float cy = (float) (p.y - cam.y) + yOffset;
            float cz = (float) (p.z - cam.z);
            visible.add(new float[]{cx, cy, cz, p.r, p.g, p.b, opacityMul});
        }
        if (visible.isEmpty()) {
            return;
        }

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder builder = tessellator.begin(
                VertexFormat.DrawMode.TRIANGLES, RenderLayers.lightning().getVertexFormat());
        for (float[] v : visible) {
            // Faint wide halo + bright core -> a soft spherical glow.
            addGlowDisc(builder, mat, v[0], v[1], v[2], right, up, radius * 2.2F, segments,
                    v[3], v[4], v[5], 0.20F * v[6], 0.05F * v[6]);
            addGlowDisc(builder, mat, v[0], v[1], v[2], right, up, radius, segments,
                    v[3], v[4], v[5], 0.95F * v[6], 0.30F * v[6]);
        }
        RenderLayers.lightning().draw(builder.end());
    }

    /**
     * Emits a filled camera-facing disc (triangle fan) centred at (cx,cy,cz) in
     * camera-relative coordinates. The centre vertex is bright ({@code centerA})
     * and the rim vertices fade to {@code edgeA}, producing a spherical glow falloff.
     */
    private static void addGlowDisc(BufferBuilder b, Matrix4f mat,
                                    float cx, float cy, float cz,
                                    Vector3f right, Vector3f up, float r, int segments,
                                    float cr, float cg, float cb,
                                    float centerA, float edgeA) {
        double step = 2.0 * Math.PI / segments;
        for (int i = 0; i < segments; i++) {
            float a0 = (float) (i * step);
            float a1 = (float) ((i + 1) * step);
            float ox0 = (float) Math.cos(a0) * r, oy0 = (float) Math.sin(a0) * r;
            float ox1 = (float) Math.cos(a1) * r, oy1 = (float) Math.sin(a1) * r;
            float x0 = cx + right.x * ox0 + up.x * oy0;
            float y0 = cy + right.y * ox0 + up.y * oy0;
            float z0 = cz + right.z * ox0 + up.z * oy0;
            float x1 = cx + right.x * ox1 + up.x * oy1;
            float y1 = cy + right.y * ox1 + up.y * oy1;
            float z1 = cz + right.z * ox1 + up.z * oy1;
            // Triangle (centre, rimA, rimB) — a proper fan slice, not a broken line.
            b.vertex(mat, cx, cy, cz).color(cr, cg, cb, centerA);
            b.vertex(mat, x0, y0, z0).color(cr, cg, cb, edgeA);
            b.vertex(mat, x1, y1, z1).color(cr, cg, cb, edgeA);
        }
    }

    /**
     * One line segment for the LINES / LINES_TRANSLUCENT pipelines. Those pipelines
     * expand each segment into a screen-space quad: the {@code Normal} attribute must
     * point from this vertex to the other endpoint, and {@code LineWidth} is the pixel
     * width. Coordinates here are already camera-relative.
     */
    public static record LineSeg(double x0, double y0, double z0, double x1, double y1, double z1,
                                float r, float g, float b, float a, float w) {
    }

    /**
     * Draws a batch of line segments directly via {@link Tessellator} and
     * {@link RenderLayer#draw} — the same immediate-mode path the vanilla debug
     * renderer uses. Coordinates must already be camera-relative.
     */
    public static void drawLines(Matrix4f mat, RenderLayer layer, List<LineSeg> segs) {
        if (segs.isEmpty()) {
            return;
        }
        if (loggedDrawCalls < 10) {
            loggedDrawCalls++;
            NyaaInfiniteInfernalUtils.LOGGER.info(
                    "[infutils][curve] drawLines: {} segments on layer {}", segs.size(), layer);
        }
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder builder = tessellator.begin(VertexFormat.DrawMode.LINES, layer.getVertexFormat());
        for (LineSeg s : segs) {
            builder.vertex(mat, (float) s.x0, (float) s.y0, (float) s.z0)
                    .color(s.r, s.g, s.b, s.a)
                    .normal((float) (s.x1 - s.x0), (float) (s.y1 - s.y0), (float) (s.z1 - s.z0))
                    .lineWidth(s.w);
            builder.vertex(mat, (float) s.x1, (float) s.y1, (float) s.z1)
                    .color(s.r, s.g, s.b, s.a)
                    .normal((float) (s.x0 - s.x1), (float) (s.y0 - s.y1), (float) (s.z0 - s.z1))
                    .lineWidth(s.w);
        }
        layer.draw(builder.end());
    }

    /** Called from HudRenderCallback to draw the debug HUD (if enabled). */
    public static void renderDebug(DrawContext graphics, RenderTickCounter tickCounter) {
        if (!FeatureGate.active()) {
            return;
        }
        ModConfig config = NyaaInfiniteInfernalUtils.CONFIG;
        if (config == null || !config.attackCurve.enabled || !config.attackCurve.showDebug) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        int x = 4;
        int y = 144;
        graphics.drawText(client.textRenderer,
                Text.literal("[AttackCurve debug]"),
                x, y, 0xFF66CCFF, true);
        graphics.drawText(client.textRenderer,
                Text.literal(String.format("  captured this frame: %d", lastCaptured)),
                x, y + 10, 0xFFAADDDD, true);
        graphics.drawText(client.textRenderer,
                Text.literal(String.format("  points / curves: %d / %d", lastPoints, lastCurves)),
                x, y + 20, 0xFFAADDDD, true);
        graphics.drawText(client.textRenderer,
                Text.literal(String.format("  maxSegDist: %.2f  maxTracked: %d",
                        config.attackCurve.maxSegmentDistance, config.attackCurve.maxTrackedParticles)),
                // Note: maxSegmentDistance is the single source of truth for the minimum
                // connection distance (per-point and end-to-end stitching).
                x, y + 30, 0xFFAADDDD, true);
    }
}
