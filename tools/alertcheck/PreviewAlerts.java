package com.hbmzz.tools;

import com.hbmzz.alert.AlertCode;
import com.hbmzz.client.screen.AlertScreen;
import com.hbmzz.client.screen.NukeScreen;

import javax.imageio.ImageIO;
import java.io.File;

/**
 * Offline renderer for the two new wall panels.
 *
 * <p>No OpenGL and no Minecraft: both screens are pure AWT, and the texture
 * upload lives in a separate class, so the exact pixels the block face shows can
 * be written to a PNG and looked at. The alternative is launching a game to find
 * out that a 50-pixel code word pushed the meaning line off the bottom.</p>
 */
public final class PreviewAlerts {

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "dist/alerts");
        out.mkdirs();

        AlertScreen alert = new AlertScreen();

        // GREEN, one event: the idle case, which is what most screens are in.
        alert.render(0L, AlertCode.GREEN.id(), 4000L, 5400L, "代码广播器", "",
                new int[]{AlertCode.GREEN.id()}, new long[]{4000L}, new String[]{"代码广播器"}, 1, true);
        save(alert, out, "alert-green");

        // RED with a history: the frame that has to stay readable.
        int[] codes = {AlertCode.RED.id(), AlertCode.ORANGE.id(), AlertCode.YELLOW.id(), AlertCode.GREEN.id()};
        long[] ticks = {5400L, 4800L, 4200L, 3600L};
        String[] sources = {"代码广播器", "代码广播器", "核弹信号控制器", "设施总线"};
        alert.render(0L, AlertCode.RED.id(), 5400L, 6000L, "代码广播器", "Yang", codes, ticks, sources, 4, true);
        save(alert, out, "alert-red");

        // DELTA, blink on, no link: the worst case, plus the dead-feed label.
        alert.render(0L, AlertCode.DELTA.id(), 100L, 20000L, "核装置", "", codes, ticks, sources, 4, false);
        save(alert, out, "alert-delta-nolink");

        // ALPHA mid-blink (the other half of the 500 ms square wave).
        alert.render(500L, AlertCode.ALPHA.id(), 900L, 1000L, "核弹信号控制器", "", codes, ticks, sources, 2, true);
        save(alert, out, "alert-alpha");

        NukeScreen nuke = new NukeScreen();

        // Bound and idle.
        nuke.render(0L, true, 1, 100, 100, 0, 128, 64, -300, 0, true, 120, 64, -290, 0,
                AlertCode.GREEN.id(), true);
        save(nuke, out, "nuke-idle");

        // Counting down: 62 ticks left of a 100-tick fuse.
        nuke.render(0L, true, 2, 250, 100, 62, 128, 64, -300, 0, true, 120, 64, -290, 0,
                AlertCode.ALPHA.id(), true);
        save(nuke, out, "nuke-countdown");

        // Nothing bound: the instructions frame.
        nuke.render(0L, false, 0, 0, 0, 0, 0, 0, 0, 0, false, 0, 0, 0, 0,
                AlertCode.GREEN.id(), true);
        save(nuke, out, "nuke-unbound");
    }

    private static void save(AlertScreen screen, File dir, String name) throws Exception {
        ImageIO.write(screen.image(), "png", new File(dir, name + ".png"));
        System.out.println("wrote " + name + ".png  " + screen.image().getWidth() + "x" + screen.image().getHeight());
    }

    private static void save(NukeScreen screen, File dir, String name) throws Exception {
        ImageIO.write(screen.image(), "png", new File(dir, name + ".png"));
        System.out.println("wrote " + name + ".png  " + screen.image().getWidth() + "x" + screen.image().getHeight());
    }

    private PreviewAlerts() {
    }
}
