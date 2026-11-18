package com.hbmzz.tools;

import com.hbmzz.client.screen.DfcScreen;
import com.hbmzz.dfc.DFCSnapshot;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Offline renderer for the monitor panel.
 *
 * <p>Runs the real {@link DfcScreen} with no OpenGL and no Minecraft: it only
 * skips the texture upload, so what it writes out is pixel-for-pixel what the
 * block face paints. That makes it possible to iterate on the layout - and to
 * prove a page actually fits - without launching the game.</p>
 */
public final class PreviewMain {

    private static final int COLS = 4;
    private static final int ROWS = 3;

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "dist");
        out.mkdirs();

        DFCSnapshot hot = sample();
        hot.temperature = 5820.0D;
        hot.stabilization = 2.4D;
        hot.collapsing = true;
        hot.explosionIn = 71.0D;
        hot.damageA = 88.0D;
        hot.damageB = 91.0D;

        DFCSnapshot cold = sample();
        cold.temperature = 42.0D;
        cold.stabilization = 0.0D;

        write(out, "panel-core", hotOrNormal(), 0, 40000L, -1L);
        write(out, "panel-parts", sample(), 1, 40000L, -1L);
        write(out, "panel-graph", sample(), 2, 40000L, -1L);
        write(out, "panel-boot", sample(), 0, 600L, -1L);
        // 3500ms is past the thirteenth POST line but still inside the console
        // phase (which ends at 3810ms), so the page is shown at full length.
        write(out, "panel-console", sample(), 0, 3500L, -1L);
        write(out, "panel-offline", cold, 0, 40000L, -1L);
        write(out, "panel-fault", sample(), 0, 40000L, 1500L);
        write(out, "panel-alert", hot, 0, 40000L, -1L);

        DfcScreen blank = new DfcScreen();
        blank.setGeometry(COLS, ROWS);
        blank.render(40000L, false, null, 0, 0.0D, -1L);
        ImageIO.write(blank.image(), "png", new File(out, "panel-off.png"));

        // Terminal window at a comfortable size and at the minimum size, to
        // prove the control column reflows instead of overlapping itself.
        terminal(out, "terminal-large", 560, 340, 3, 2, 210, 120, sample(), 0);
        terminal(out, "terminal-small", 300, 214, 3, 2, 60, 150, sample(), 0);
    }

    private static void terminal(File dir, String name, int w, int h, int cols, int rows,
                                 int mouseX, int mouseY, DFCSnapshot data, int page)
            throws Exception {
        com.hbmzz.client.screen.TerminalCanvas canvas =
                new com.hbmzz.client.screen.TerminalCanvas();
        canvas.resize(w, h);
        canvas.render(page, true, cols, rows, true, "(-12, 64, 3)", mouseX, mouseY, false);

        // The window only reserves the preview rectangle - the game fills it in
        // the GL pass. Headless has to composite that pass itself, otherwise the
        // screenshot shows a black box the player never actually sees.
        int[] rect = canvas.previewRect();
        if (rect[2] > 24 && rect[3] > 18) {
            DfcScreen preview = new DfcScreen();
            preview.setGeometry(cols, rows);
            preview.render(40000L, true, data, page, 0.25D, -1L);
            if (preview.hasTexture() || preview.image() != null) {
                BufferedImage panel = preview.image();
                float aspect = preview.textureAspect();
                int boxW = rect[2];
                int boxH = rect[3];
                int drawW = boxW;
                int drawH = Math.round(boxW / aspect);
                if (drawH > boxH) {
                    drawH = boxH;
                    drawW = Math.round(boxH * aspect);
                }
                int drawX = rect[0] + (boxW - drawW) / 2;
                int drawY = rect[1] + (boxH - drawH) / 2;

                // The canvas bitmap is supersampled, so the GUI-space rectangle
                // has to be scaled by the same factor before it lands on it.
                int ss = Math.max(1, canvas.image().getWidth() / Math.max(1, w));
                Graphics2D g = canvas.image().createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(panel, drawX * ss, drawY * ss, drawW * ss, drawH * ss, null);
                g.dispose();
            }
        }
        ImageIO.write(canvas.image(), "png", new File(dir, name + ".png"));
        System.out.println("wrote " + name + ".png  " + w + "x" + h);
    }

    private static DFCSnapshot hotOrNormal() {
        return sample();
    }

    private static void write(File dir, String name, DFCSnapshot data, int page,
                              long sinceOn, long fault) throws Exception {
        DfcScreen screen = new DfcScreen();
        screen.setGeometry(COLS, ROWS);
        // Two passes with a forced bucket change: the first paints, the second
        // tops up the chart history so the graph page has a curve to show.
        screen.render(sinceOn, true, data, page, 0.25D, fault);
        if (page == 2) {
            warmChart(screen, data);
        } else if (page == 0 && sinceOn > 10000L) {
            warmLog(screen, page);
        }
        screen.render(sinceOn, true, data, page, 0.25D, fault);
        ImageIO.write(screen.image(), "png", new File(dir, name + ".png"));
        System.out.println("wrote " + name + ".png  " + screen.image().getWidth()
                + "x" + screen.image().getHeight());
    }

    /**
     * Fills the log column through the real threshold-crossing path.
     *
     * <p>The canvas only records edge changes, and a headless run has just one
     * render before the shot - so without a strained intermediate state the
     * column would show nothing but the three connect lines while the card is
     * half empty. This feeds one honest crossing and lets the final render
     * record the recovery.</p>
     */
    private static void warmLog(DfcScreen screen, int page) {
        DFCSnapshot strained = sample();
        strained.temperature = 5320.0D;
        strained.stabilization = 4.4D;
        strained.collapsing = false;
        screen.render(40000L, true, strained, page, 0.25D, -1L);
    }

    /**
     * Fills the chart rings directly. The canvas deliberately only samples on
     * its own redraw clock, so a headless run has to nudge it once per sample.
     */
    private static void warmChart(DfcScreen screen, DFCSnapshot data) throws Exception {
        screen.clearHistory();
        java.lang.reflect.Field field = DfcScreen.class.getDeclaredField("tempSeries");
        field.setAccessible(true);
        Object temp = field.get(screen);
        java.lang.reflect.Field energyField = DfcScreen.class.getDeclaredField("energySeries");
        energyField.setAccessible(true);
        Object energy = energyField.get(screen);
        java.lang.reflect.Method add = temp.getClass().getDeclaredMethod("add", double.class);
        add.setAccessible(true);
        for (int i = 0; i < 290; i++) {
            double wave = Math.sin(i * 0.14D) * 260.0D + Math.sin(i * 0.41D) * 90.0D;
            add.invoke(temp, 640.0D + i * 1.2D + wave);
            add.invoke(energy, 12000.0D + Math.sin(i * 0.19D) * 4200.0D + i * 20.0D);
        }
    }

    private static DFCSnapshot sample() {
        DFCSnapshot d = new DFCSnapshot();
        d.coreFound = true;
        d.hasCore = true;
        d.meterBound = true;
        d.coreType = "PYROCLASTIC";
        d.temperature = 812.5D;
        d.meltingPoint = 6000.0D;
        d.stabilization = 9.72D;
        d.containedEnergy = 50.0D;
        d.expellingEnergy = 0.17D;
        d.potentialGain = 3.5D;
        d.fuel1Name = "DFC FUEL";
        d.fuel1Fill = 83.0D;
        d.fuel1Max = 100.0D;
        d.fuel2Name = "DHC";
        d.fuel2Fill = 7.0D;
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
}
