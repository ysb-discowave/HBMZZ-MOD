package com.hbmzz.tools;

import com.hbmzz.client.ConsoleModel;
import com.hbmzz.client.ConsoleModel.Face;
import com.hbmzz.client.screen.DfcScreen;
import com.hbmzz.dfc.ConsoleRig;
import com.hbmzz.dfc.DFCSnapshot;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Offline renderer for the console hardware.
 *
 * <p>Projects the exact quads {@link ConsoleModel} hands the in-game renderer,
 * with the real {@link DfcScreen} image textured onto the glass. Orthographic
 * projection means a triangle-to-triangle affine map is exact, so the panel
 * comes out correctly wrapped around the tilted face instead of sheared.</p>
 *
 * <p>What this is for: the geometry used to be verified by launching Minecraft
 * and walking up to the block, which is why a back-face winding error once
 * shipped. Now the machine can be looked at in two seconds.</p>
 */
public final class RigPreview {

    private static final int W = 1100;
    private static final int H = 780;

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "dist");
        out.mkdirs();

        BufferedImage screen = panelImage();

        // Ceiling mount, seen from below and to the side: the angle a player
        // standing under the screen actually gets. Negative elevation is below
        // the machine - a positive one looks down on it, which flatters the
        // bracket and hides what the player really sees.
        shot(out, "rig-hung", 4, 3, ConsoleRig.TILT_CEILING, true, screen, 26, -20);
        // Straight up from the floor, to check the ears and the underside.
        shot(out, "rig-under", 4, 3, ConsoleRig.TILT_CEILING, true, screen, 8, -46);
        // Wall mount: no ceiling, flat panel.
        shot(out, "rig-wall", 4, 3, ConsoleRig.TILT_WALL, false, screen, 30, 12);
        // The smallest panel, to prove the frame does not collapse.
        shot(out, "rig-small", 1, 1, ConsoleRig.TILT_WALL, false, screen, 40, -8);
        // Deepest notch a 2-tall panel can take.
        shot(out, "rig-deep", 2, 2, ConsoleRig.maxNotch(2), true, screen, 32, -14);

        // Orthographic checks, no perspective trickery: the front view answers
        // "does it fill its cells", the side view answers "does it stay inside
        // the cell depth". Both were unanswerable from the isometric shots,
        // where the projection mixes y and z and makes a forward panel look
        // like it is riding up over the cells above it.
        shot(out, "rig-front", 4, 3, ConsoleRig.TILT_CEILING, true, screen, 0, 0);
        shot(out, "rig-side", 4, 3, ConsoleRig.TILT_CEILING, true, screen, 90, 0);
    }

    // ------------------------------------------------------------------ scenes

    private static void shot(File dir, String name, int cols, int rows, int tilt, boolean ceiling,
                             BufferedImage screen, double azimuth, double elevation) throws Exception {
        int notch = ConsoleRig.clampNotch(tilt, rows);
        List<Face> faces = ConsoleModel.build(cols, rows, notch, true, ceiling);
        float[][] panel = ConsoleModel.panelCorners(cols, rows, notch);

        // Everything that has to be drawn, tagged with the depth used to sort it.
        List<Poly> polys = new ArrayList<>();
        for (Face face : faces) {
            polys.add(Poly.of(face));
        }
        // The glass sits between the frame halves; give it a depth just behind
        // the frame's front face so the frame overlaps it correctly.
        polys.add(Poly.panel(panel, screen));

        // The block above the machine. Not decoration: it is the occluder that
        // decides what a player standing in the room can actually see, and
        // judging the bracket without it is how a slab floating over the panel
        // got as far as a screenshot.
        if (ceiling) {
            Poly.box(polys, -cols * 0.5F - 0.02F, rows * 0.5F, -0.02F,
                    cols * 0.5F + 0.02F, rows * 0.5F + 1.0F, 1.02F, 0xFF3C4147);
        }

        // Project twice: once to measure, once to place.
        double[] bounds = new double[]{Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Poly p : polys) {
            p.project(azimuth, elevation);
            for (int i = 0; i < 4; i++) {
                bounds[0] = Math.min(bounds[0], p.px[i]);
                bounds[1] = Math.min(bounds[1], p.py[i]);
                bounds[2] = Math.max(bounds[2], p.px[i]);
                bounds[3] = Math.max(bounds[3], p.py[i]);
            }
        }
        // The cells the console claims, as a wireframe. Without this there is
        // nothing in the picture to say where the machine is *supposed* to be,
        // which is why 1.7.0 shipped with the whole console a block in front of
        // its own cells: the preview looked fine, because it framed whatever it
        // was given.
        for (double[] corner : cellCorners(cols, rows)) {
            double[] q = projPoint(corner[0], corner[1], corner[2], azimuth, elevation);
            bounds[0] = Math.min(bounds[0], q[0]);
            bounds[1] = Math.min(bounds[1], q[1]);
            bounds[2] = Math.max(bounds[2], q[0]);
            bounds[3] = Math.max(bounds[3], q[1]);
        }
        double spanX = Math.max(0.001, bounds[2] - bounds[0]);
        double spanY = Math.max(0.001, bounds[3] - bounds[1]);
        double scale = Math.min((W - 130) / spanX, (H - 130) / spanY);
        double offX = (W - spanX * scale) / 2.0 - bounds[0] * scale;
        double offY = (H - spanY * scale) / 2.0 - bounds[1] * scale;

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        backdrop(g, name, cols, rows, notch, ceiling);

        // The claimed cells, behind everything else so the machine occludes them
        // where it should.
        drawCells(g, cols, rows, azimuth, elevation, scale, offX, offY);

        // Painter's algorithm: farthest quad first.
        polys.sort(Comparator.comparingDouble((Poly p) -> -p.depth));

        for (Poly p : polys) {
            p.toScreen(scale, offX, offY);
            if (p.isPanel()) {
                g.setColor(new Color(0xFF0A0908));
                g.fill(p.path());
                p.drawTexture(g);
            } else {
                g.setColor(p.shaded());
                g.fill(p.path());
                g.setColor(new Color(0, 0, 0, 34));
                g.setStroke(new BasicStroke(0.7F));
                g.draw(p.path());
            }
        }

        g.dispose();
        ImageIO.write(img, "png", new File(dir, name + ".png"));
        System.out.println("wrote " + name + ".png   tilt=" + ConsoleRig.tiltDegrees(notch, rows)
                + "deg  faces=" + faces.size());
    }

    /** The eight corners of the block volume the console occupies, in model space. */
    private static List<double[]> cellCorners(int cols, int rows) {
        List<double[]> out = new ArrayList<>();
        double hw = cols * 0.5D;
        double hh = rows * 0.5D;
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? -hw : hw;
            double y = (i & 2) == 0 ? -hh : hh;
            double z = (i & 4) == 0 ? -0.5 : 0.5;
            out.add(new double[]{x, y, z});
        }
        return out;
    }

    /** Orthographic projection of one point, matching {@link Poly#project}. */
    private static double[] projPoint(double x, double y, double z, double azimuth, double elevation) {
        double a = Math.toRadians(azimuth);
        double e = Math.toRadians(elevation);
        double ca = Math.cos(a), sa = Math.sin(a), ce = Math.cos(e), se = Math.sin(e);
        double rx = x * ca - z * sa;
        double rz = x * sa + z * ca;
        double ry = y * ce - rz * se;
        return new double[]{-rx, -ry};
    }

    /**
     * Wireframe of the cells the panel claims.
     *
     * <p>Deliberately drawn before the machine: if the housing hides it, the
     * housing is where it belongs.</p>
     */
    private static void drawCells(Graphics2D g, int cols, int rows,
                                  double azimuth, double elevation,
                                  double scale, double offX, double offY) {
        double hw = cols * 0.5D;
        double hh = rows * 0.5D;
        double[][] corner = {
                {-hw, -hh, -0.5}, {-hw, -hh, 0.5}, {-hw, hh, -0.5}, {-hw, hh, 0.5},
                {hw, -hh, -0.5}, {hw, -hh, 0.5}, {hw, hh, -0.5}, {hw, hh, 0.5}
        };
        double[][] p = new double[8][];
        for (int i = 0; i < 8; i++) {
            double[] q = projPoint(corner[i][0], corner[i][1], corner[i][2], azimuth, elevation);
            p[i] = new double[]{q[0] * scale + offX, q[1] * scale + offY};
        }
        int[][] edges = {
                {0, 1}, {2, 3}, {4, 5}, {6, 7},   // depth edges
                {0, 2}, {1, 3}, {4, 6}, {5, 7},   // vertical edges
                {0, 4}, {1, 5}, {2, 6}, {3, 7}    // horizontal edges
        };

        g.setStroke(new BasicStroke(1.1F));
        // The rear face, where the wall would be, in a dimmer tone.
        g.setColor(new Color(0x33B9C2CA));
        g.draw(frontFace(p, 1, 3, 7, 5));
        for (int[] e : edges) {
            g.setColor(new Color(0x55B9C2CA));
            g.drawLine((int) p[e[0]][0], (int) p[e[0]][1], (int) p[e[1]][0], (int) p[e[1]][1]);
        }
        // Mark the controller cell's top edge: that is the block the player
        // placed, and the one whose top face is the hinge plane. The controller
        // is the panel's top-LEFT cell, so it spans [-hw, -hw+1].
        double[] a = projPoint(-hw, hh, -0.5, azimuth, elevation);
        double[] b = projPoint(-hw + Math.min(1, cols), hh, -0.5, azimuth, elevation);
        g.setColor(new Color(0xAAFFB03A));
        g.setStroke(new BasicStroke(2.2F));
        g.drawLine((int) (a[0] * scale + offX), (int) (a[1] * scale + offY),
                (int) (b[0] * scale + offX), (int) (b[1] * scale + offY));
    }

    private static java.awt.Shape frontFace(double[][] p, int a, int b, int c, int d) {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(p[a][0], p[a][1]);
        path.lineTo(p[b][0], p[b][1]);
        path.lineTo(p[c][0], p[c][1]);
        path.lineTo(p[d][0], p[d][1]);
        path.closePath();
        return path;
    }

    private static void backdrop(Graphics2D g, String name, int cols, int rows, int notch, boolean ceiling) {
        g.setColor(new Color(0xFF101214));
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(0xFF191D20));
        for (int x = 0; x < W; x += 40) {
            g.drawLine(x, 0, x, H);
        }
        for (int y = 0; y < H; y += 40) {
            g.drawLine(0, y, W, y);
        }

        g.setColor(new Color(0xFFFFB03A));
        g.setFont(new Font("Consolas", Font.BOLD, 22));
        g.drawString("AHBSSMFI-OS  CONSOLE RIG", 36, 48);
        g.setColor(new Color(0xFF8A8F94));
        g.setFont(new Font("Consolas", Font.PLAIN, 15));
        g.drawString(cols + " x " + rows + " blocks   tilt " + Math.round(ConsoleRig.tiltDegrees(notch, rows))
                + " deg   " + (ceiling ? "ceiling mount" : "wall mount"), 36, 72);
        g.drawString(name, 36, H - 28);
    }

    private static BufferedImage panelImage() {
        DfcScreen canvas = new DfcScreen();
        canvas.setGeometry(4, 3);
        canvas.setLinkAge(400L);
        DFCSnapshot snap = sample();
        canvas.render(52000L, true, snap, 0, 0.0D, -1L);
        return canvas.image();
    }

    private static DFCSnapshot sample() {
        DFCSnapshot d = new DFCSnapshot();
        d.coreFound = true;
        d.hasCore = true;
        d.meterBound = true;
        d.coreType = "PYROCLASTIC";
        d.temperature = 3412.0D;
        d.meltingPoint = 6000.0D;
        d.stabilization = 7.4D;
        d.containedEnergy = 50.0D;
        d.expellingEnergy = 4.1D;
        d.potentialGain = 1.18D;
        d.fuel1Name = "DFC FUEL";
        d.fuel1Fill = 83.0D;
        d.fuel1Max = 100.0D;
        d.fuel2Name = "DHC";
        d.fuel2Fill = 41.0D;
        d.fuel2Max = 100.0D;
        d.stabilizers = 4;
        d.absorbers = 2;
        d.pulsers = 3;
        d.emitters = 7;
        d.injectors = 3;
        d.exchangers = 1;
        d.cemiters = 2;
        d.damageA = 12.0D;
        d.damageB = 34.0D;
        d.tick = 12345660L;
        d.meterPerTick = 241L;
        d.meterPerSecond = 4820L;
        d.predictionMode = 2;
        d.predictedSeconds = 1830;
        return d;
    }

    // ------------------------------------------------------------------ polygons

    /** One quad, projected and shaded. */
    private static final class Poly {
        final float[] xs = new float[4];
        final float[] ys = new float[4];
        final float[] zs = new float[4];
        final int argb;
        final BufferedImage texture;
        final float[] u = new float[4];
        final float[] v = new float[4];

        final float[] px = new float[4];
        final float[] py = new float[4];
        double depth;
        boolean doubleSided;

        private Poly(int argb, BufferedImage texture) {
            this.argb = argb;
            this.texture = texture;
        }

        static Poly of(Face face) {
            Poly p = new Poly(face.argb, null);
            System.arraycopy(face.xs, 0, p.xs, 0, 4);
            System.arraycopy(face.ys, 0, p.ys, 0, 4);
            System.arraycopy(face.zs, 0, p.zs, 0, 4);
            p.doubleSided = true;
            return p;
        }

        static Poly panel(float[][] corners, BufferedImage texture) {
            Poly p = new Poly(0xFFFFFFFF, texture);
            for (int i = 0; i < 4; i++) {
                p.xs[i] = corners[i][0];
                p.ys[i] = corners[i][1];
                p.zs[i] = corners[i][2];
            }
            // Must match ConsoleRig.panelUv and RenderDFCMonitor.drawPanel
            // corner for corner, or the preview shows mirrored text while the
            // game shows it correctly - and then the preview is worthless.
            p.u[0] = 0.0F;
            p.u[1] = 1.0F;
            p.u[2] = 1.0F;
            p.u[3] = 0.0F;
            p.v[0] = 1.0F;
            p.v[1] = 1.0F;
            p.v[2] = 0.0F;
            p.v[3] = 0.0F;
            return p;
        }

        boolean isPanel() {
            return texture != null;
        }

        /** Axis-aligned box as six quads, for scenery around the machine. */
        static void box(List<Poly> out, float x0, float y0, float z0,
                        float x1, float y1, float z1, int argb) {
            out.add(quad(argb, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0));
            out.add(quad(argb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1));
            out.add(quad(argb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0));
            out.add(quad(argb, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0));
            out.add(quad(argb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1));
            out.add(quad(argb, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1));
        }

        static Poly quad(int argb, float ax, float ay, float az,
                         float bx, float by, float bz,
                         float cx, float cy, float cz,
                         float dx, float dy, float dz) {
            Poly p = new Poly(argb, null);
            p.xs[0] = ax; p.ys[0] = ay; p.zs[0] = az;
            p.xs[1] = bx; p.ys[1] = by; p.zs[1] = bz;
            p.xs[2] = cx; p.ys[2] = cy; p.zs[2] = cz;
            p.xs[3] = dx; p.ys[3] = dy; p.zs[3] = dz;
            p.doubleSided = true;
            return p;
        }

        /** Orthographic camera: yaw, then pitch, then drop z. */
        void project(double azimuth, double elevation) {
            double a = Math.toRadians(azimuth);
            double e = Math.toRadians(elevation);
            double ca = Math.cos(a), sa = Math.sin(a), ce = Math.cos(e), se = Math.sin(e);
            double sum = 0;
            for (int i = 0; i < 4; i++) {
                double rx = xs[i] * ca - zs[i] * sa;
                double rz = xs[i] * sa + zs[i] * ca;
                double ry = ys[i] * ce - rz * se;
                double rz2 = ys[i] * se + rz * ce;
                // The camera looks along +z, so its right vector is -x: screen
                // X is the negative of model x, and screen Y is the negative of
                // model y because screen coordinates grow downward.
                px[i] = (float) -rx;
                py[i] = (float) -ry;
                sum += rz2;
            }
            depth = sum / 4.0;
            // Second pass for the shade, which needs the rotated normal.
            this.rotAz = a;
            this.rotEl = e;
        }

        private double rotAz;
        private double rotEl;

        void toScreen(double scale, double offX, double offY) {
            for (int i = 0; i < 4; i++) {
                px[i] = (float) (px[i] * scale + offX);
                py[i] = (float) (py[i] * scale + offY);
            }
        }

        Path2D path() {
            Path2D.Float p = new Path2D.Float();
            p.moveTo(px[0], py[0]);
            for (int i = 1; i < 4; i++) {
                p.lineTo(px[i], py[i]);
            }
            p.closePath();
            return p;
        }

        /** Flat colour lit by one directional light, so the box reads as solid. */
        Color shaded() {
            double[] n = normal();
            // Light from the viewer's upper left, slightly in front.
            double lx = -0.45, ly = 0.62, lz = -0.64;
            double len = Math.sqrt(lx * lx + ly * ly + lz * lz);
            double d = (n[0] * lx + n[1] * ly + n[2] * lz) / len;
            if (doubleSided && d < 0) {
                d = -d;
            }
            double k = 0.46 + 0.54 * Math.max(0.0, Math.min(1.0, d));
            int a = (argb >>> 24) & 255;
            int r = (int) Math.min(255, ((argb >> 16) & 255) * k);
            int g = (int) Math.min(255, ((argb >> 8) & 255) * k);
            int b = (int) Math.min(255, (argb & 255) * k);
            return new Color(r, g, b, Math.max(90, a));
        }

        private double[] normal() {
            double ax = xs[1] - xs[0], ay = ys[1] - ys[0], az = zs[1] - zs[0];
            double bx = xs[2] - xs[0], by = ys[2] - ys[0], bz = zs[2] - zs[0];
            double nx = ay * bz - az * by;
            double ny = az * bx - ax * bz;
            double nz = ax * by - ay * bx;
            // Rotate the normal the same way the points were rotated.
            double ca = Math.cos(rotAz), sa = Math.sin(rotAz), ce = Math.cos(rotEl), se = Math.sin(rotEl);
            double rx = nx * ca - nz * sa;
            double rz = nx * sa + nz * ca;
            double ry = ny * ce - rz * se;
            double rz2 = ny * se + rz * ce;
            double len = Math.sqrt(rx * rx + ry * ry + rz2 * rz2);
            if (len < 1e-6) {
                return new double[]{0, 1, 0};
            }
            return new double[]{rx / len, ry / len, rz2 / len};
        }

        /**
         * Textures the quad as two triangles. Under an orthographic camera an
         * affine map per triangle is exact, so this is not an approximation -
         * the glass shows the same pixels it will in game.
         */
        void drawTexture(Graphics2D g) {
            int iw = texture.getWidth();
            int ih = texture.getHeight();
            triangle(g, 0, 1, 2, iw, ih);
            triangle(g, 0, 2, 3, iw, ih);
        }

        private void triangle(Graphics2D g, int a, int b, int c, int iw, int ih) {
            double p0x = u[a] * iw, p0y = v[a] * ih;
            double p1x = u[b] * iw, p1y = v[b] * ih;
            double p2x = u[c] * iw, p2y = v[c] * ih;

            double det = (p1x - p0x) * (p2y - p0y) - (p2x - p0x) * (p1y - p0y);
            if (Math.abs(det) < 1e-6) {
                return;
            }
            double q0x = px[a], q0y = py[a];
            double q1x = px[b], q1y = py[b];
            double q2x = px[c], q2y = py[c];

            double m00 = ((q1x - q0x) * (p2y - p0y) - (q2x - q0x) * (p1y - p0y)) / det;
            double m01 = ((q2x - q0x) * (p1x - p0x) - (q1x - q0x) * (p2x - p0x)) / det;
            double m10 = ((q1y - q0y) * (p2y - p0y) - (q2y - q0y) * (p1y - p0y)) / det;
            double m11 = ((q2y - q0y) * (p1x - p0x) - (q1y - q0y) * (p2x - p0x)) / det;
            double m02 = q0x - m00 * p0x - m01 * p0y;
            double m12 = q0y - m10 * p0x - m11 * p0y;

            AffineTransform at = new AffineTransform(m00, m10, m01, m11, m02, m12);

            Path2D.Float clip = new Path2D.Float();
            clip.moveTo(q0x, q0y);
            clip.lineTo(q1x, q1y);
            clip.lineTo(q2x, q2y);
            clip.closePath();

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setClip(clip);
            g2.setComposite(AlphaComposite.SrcOver);
            g2.drawImage(texture, at, null);
            g2.dispose();
        }
    }
}
