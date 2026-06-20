package com.dwinovo.tulpa.client.path;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;

/**
 * Draws every companion's path overlay in the world — our port of Baritone's
 * {@code PathRenderer}: a coloured poly-line through the path nodes, red boxes
 * on blocks to break, green boxes on blocks to place, and a green box on the
 * goal. Default-on, like Baritone ({@code renderPath = true}).
 *
 * <p>Loader-neutral: each loader's client init calls {@link #render} from its
 * post-translucent world-render hook with that frame's {@link PoseStack}; the
 * camera and line buffer are pulled from {@link Minecraft} here so the body is
 * written once.
 */
public final class PathVizRenderer {

    private static final int PATH_COLOR  = 0xFFFF0000; // red
    private static final int BREAK_COLOR = 0xFFFF0000; // red
    private static final int PLACE_COLOR = 0xFF00FF00; // green
    private static final int GOAL_COLOR  = 0xFF00FF00; // green
    private static final float LINE_WIDTH = 5.0F;   // Baritone pathRenderLineWidthPixels default
    /** Baritone renderPathAsLine=false: the path is a thin vertical ribbon, the line
     *  plus a parallel edge 0.03 above it, joined at each segment's ends. */
    private static final double RIBBON = 0.03;
    /** Box inset so the wireframe hugs faces without z-fighting (Baritone uses .002). */
    private static final double BOX_INSET = 0.002;

    private PathVizRenderer() {}

    public static void render(PoseStack poseStack) {
        var active = ClientPathViz.all();
        if (active.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        ResourceLocation here = mc.level.dimension().location();
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        PoseStack.Pose pose = poseStack.last();

        for (ClientPathViz.Viz v : active) {
            // Baritone skips paths for bots in another dimension; ours are at world
            // coords that only make sense in their own dimension.
            if (!here.equals(v.dimension())) continue;
            drawPathLine(vc, pose, v.nodes(), cam, v.companion());
            for (BlockPos b : v.toBreak()) drawBox(vc, pose, b, cam, BREAK_COLOR);
            for (BlockPos p : v.toPlace()) drawBox(vc, pose, p, cam, PLACE_COLOR);
            // Every target the body will work through — for mining, the whole ore
            // field (Baritone boxes every GoalComposite member); for a move, the
            // single destination. Real cells, never a floating goal-centroid.
            for (BlockPos t : v.targets()) drawBox(vc, pose, t, cam, GOAL_COLOR);
        }
        // Flush only our line batch (not endLastBatch, which could flush a foreign
        // open batch in the shared buffer source at this render stage).
        buffers.endBatch(RenderType.lines());
    }

    /**
     * Poly-line through block centres (Baritone drawPath), drawn from the body's
     * CURRENT position onward — Baritone redraws every frame starting at the
     * executor's path index, so the walked-over portion vanishes and the line
     * shrinks as the body advances. We approximate that index with the path node
     * nearest the body's live position; the already-travelled prefix isn't drawn.
     */
    private static void drawPathLine(VertexConsumer vc, PoseStack.Pose pose, List<BlockPos> nodes,
                                     Vec3 cam, java.util.UUID companion) {
        int start = renderBegin(nodes, companion);
        for (int i = start + 1; i < nodes.size(); i++) {
            BlockPos a = nodes.get(i - 1);
            BlockPos b = nodes.get(i);
            double ax = a.getX() + 0.5 - cam.x, ay = a.getY() + 0.5 - cam.y, az = a.getZ() + 0.5 - cam.z;
            double bx = b.getX() + 0.5 - cam.x, by = b.getY() + 0.5 - cam.y, bz = b.getZ() + 0.5 - cam.z;
            // Baritone emitPathLine (renderPathAsLine=false): the segment plus a 3-sided
            // ribbon — vertical up at the far end, a parallel edge RIBBON above back to
            // the near end, vertical down — so the path reads as a thin standing band.
            line(vc, pose, ax, ay, az, bx, by, bz, PATH_COLOR);
            line(vc, pose, bx, by, bz, bx, by + RIBBON, bz, PATH_COLOR);
            line(vc, pose, bx, by + RIBBON, bz, ax, ay + RIBBON, az, PATH_COLOR);
            line(vc, pose, ax, ay + RIBBON, az, ax, ay, az, PATH_COLOR);
        }
    }

    /** The node nearest the body's live position — our stand-in for Baritone's
     *  per-frame {@code renderBegin} (the executor's current path index). 0 if the
     *  body can't be resolved (just drawn the whole path, as before). */
    private static int renderBegin(List<BlockPos> nodes, java.util.UUID companion) {
        var body = com.dwinovo.tulpa.client.agent.ClientTulpaLookup.resolve(companion);
        if (body == null) return 0;
        Vec3 p = body.position();
        int best = 0;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < nodes.size(); i++) {
            BlockPos n = nodes.get(i);
            double dx = n.getX() + 0.5 - p.x;
            double dy = n.getY() + 0.5 - p.y;
            double dz = n.getZ() + 0.5 - p.z;
            double sq = dx * dx + dy * dy + dz * dz;
            if (sq < bestSq) {
                bestSq = sq;
                best = i;
            }
        }
        return best;
    }

    /** The 12 edges of a unit block at {@code pos} (Baritone drawManySelectionBoxes). */
    private static void drawBox(VertexConsumer vc, PoseStack.Pose pose, BlockPos pos, Vec3 cam, int color) {
        double x0 = pos.getX() - cam.x + BOX_INSET, y0 = pos.getY() - cam.y + BOX_INSET, z0 = pos.getZ() - cam.z + BOX_INSET;
        double x1 = pos.getX() - cam.x + 1 - BOX_INSET, y1 = pos.getY() - cam.y + 1 - BOX_INSET, z1 = pos.getZ() - cam.z + 1 - BOX_INSET;
        // bottom rectangle
        line(vc, pose, x0, y0, z0, x1, y0, z0, color);
        line(vc, pose, x1, y0, z0, x1, y0, z1, color);
        line(vc, pose, x1, y0, z1, x0, y0, z1, color);
        line(vc, pose, x0, y0, z1, x0, y0, z0, color);
        // top rectangle
        line(vc, pose, x0, y1, z0, x1, y1, z0, color);
        line(vc, pose, x1, y1, z0, x1, y1, z1, color);
        line(vc, pose, x1, y1, z1, x0, y1, z1, color);
        line(vc, pose, x0, y1, z1, x0, y1, z0, color);
        // vertical pillars
        line(vc, pose, x0, y0, z0, x0, y1, z0, color);
        line(vc, pose, x1, y0, z0, x1, y1, z0, color);
        line(vc, pose, x1, y0, z1, x1, y1, z1, color);
        line(vc, pose, x0, y0, z1, x0, y1, z1, color);
    }

    /** One GL line segment, with the per-vertex normal + width the lines render type wants. */
    private static void line(VertexConsumer vc, PoseStack.Pose pose,
                             double x1, double y1, double z1, double x2, double y2, double z2, int color) {
        Vector3f n = new Vector3f((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1));
        if (n.lengthSquared() > 1.0e-6F) n.normalize();
        else n.set(0.0F, 1.0F, 0.0F);
        vc.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(color).setNormal(pose, n.x(), n.y(), n.z());
        vc.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(color).setNormal(pose, n.x(), n.y(), n.z());
    }
}
