/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.client.screen;

import com.hbmzz.dfc.DFCSnapshot;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.hbmzz.panel.Channel;
import com.hbmzz.panel.PanelLayout;
import com.hbmzz.panel.Widget;
import com.hbmzz.panel.WidgetType;
import com.hbmzz.tile.TileEntityMonitorCamera;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The panel image: everything the monitor shows is drawn with AWT onto one
 * {@link BufferedImage}, uploaded once as a texture and painted as a single
 * quad in the world.
 *
 * <p><b>AHBSSMFI-OS</b> is the operator station that lives on that panel. Its
 * language is deliberately not a dashboard of cards: an industrial console
 * reads as rings, racks and a mimic diagram, because that is what the hardware
 * it replaces actually looked like. Three views:</p>
 * <ul>
 *   <li><b>01 核心</b> - a two-ring gauge (temperature against the core's own
 *       melting point, stabilization inside it) plus a seven-channel rack of
 *       segmented lamps for the reactor structure.</li>
 *   <li><b>02 部件</b> - a mimic diagram: the seven structure channels placed
 *       around the core and wired to it, then the two fuel columns.</li>
 *   <li><b>03 曲线</b> - one shared plot with a time ruler, temperature as a
 *       filled trace and generation as a thin line, then the session's
 *       statistics.</li>
 * </ul>
 *
 * <p><b>Layout contract:</b> every coordinate here is a 256x192 virtual canvas.
 * {@link #resetCanvas()} maps it onto whatever the block face is, uniformly
 * scaled and centred, so a 4x3 wall gets it 1:1 and a 2x2 wall gets it centred
 * with dark bars - nothing is ever stretched.</p>
 */
public final class DfcScreen {

    // ---------------------------------------------------------------- geometry

    /** Virtual canvas the whole layout is authored against. */
    public static final int BASE_W = 256;
    public static final int BASE_H = 192;

    /** Supersampling: the texture is 3x the canvas so on-screen text stays crisp. */
    private static final int SS = 3;

    /** The OS wordmark. Also printed on the boot screen and the terminal. */
    public static final String OS_NAME = "AHBSSMFI-OS";
    public static final String OS_VERSION = "1.8.0";

    // ------------------------------------------------------------------ palette

    private static final int BG = 0xFF0A0908;
    private static final int PANEL = 0xFF15120E;
    private static final int PANEL_HI = 0xFF1E1A14;
    private static final int LINE = 0xFF3A3021;
    private static final int TXT = 0xFFEDE1C4;
    private static final int DIM = 0xFF9A8967;
    private static final int AMBER = 0xFFFFB03A;
    private static final int AMBER_DEEP = 0xFF8A5C1C;
    private static final int MINT = 0xFF5FD9C0;
    private static final int OK = 0xFF8CD96A;
    private static final int WARN = 0xFFFFC24A;
    private static final int CRIT = 0xFFFF5C3C;
    private static final int NEEDLE = 0xFFFFE3A8;
    private static final int OFF_SEG = 0xFF262119;
    private static final int GRID = 0x14FFFFFF;

    private static final int ALERT_BG = 0xFF24100B;
    private static final int ALERT_LINE = 0xFF7E2A18;
    private static final int ALERT_TXT = 0xFFFF9C7E;

    private static final int FAULT_BG = 0xFF1A0A06;
    private static final int FAULT_TXT = 0xFFC08A72;

    // -------------------------------------------------------------------- pages

    /** Core telemetry: twin ring gauge and the structure rack. */
    public static final int PAGE_CORE = 0;
    /** Component telemetry: mimic diagram plus fuel columns. */
    public static final int PAGE_PARTS = 1;
    /** Sixty second history of temperature and generation. */
    public static final int PAGE_GRAPH = 2;
    /** The operator's own layout, driven by the panel studio. */
    public static final int PAGE_CUSTOM = 3;
    public static final int PAGE_COUNT = 4;

    private static final String[] PAGE_NAMES = {
            "\u6838\u5fc3", "\u90e8\u4ef6", "\u66f2\u7ebf", "\u81ea\u5b9a\u4e49"};
    private static final String[] PAGE_SUBTITLES = {
            "\u5806\u82af\u9065\u6d4b", "\u7ed3\u6784\u56fe\u8c31",
            "\u8d8b\u52bf\u8bb0\u5f55", "\u81ea\u7531\u5e03\u5c40"};

    // ------------------------------------------------------------------- phases

    private static final long BOOT_MS = 1100L;
    private static final long CONSOLE_LINE_MS = 170L;
    private static final long CONSOLE_TAIL_MS = 500L;
    private static final long FAULT_MS = 4000L;
    private static final long BLINK_MS = 800L;

    private static final int PHASE_BOOT = 0;
    private static final int PHASE_CONSOLE = 1;
    private static final int PHASE_MAIN = 2;

    /** Below this temperature the core has not lit off; readings of 0 are meaningless. */
    private static final double ONLINE_MIN_TEMP = 100.0D;

    /**
     * Gauge sweep: open at the bottom, the way every panel meter is drawn.
     * Angles are math angles (0 = 3 o'clock, counter-clockwise) while the
     * canvas is y-down, so the sweep is negative to travel over the top.
     */
    private static final double GAUGE_START = 210.0D;
    private static final double GAUGE_SWEEP = -240.0D;

    private static final String[] BOOT_LOG = {
            OS_NAME + " " + OS_VERSION + "   (build 20261029c)",
            "POST ................................. OK",
            "display controller ........ 4x3 LCD",
            "probing DFCLINK bus .................. OK",
            "  dev0  DARK FUSION CORE       [bound]",
            "mounting telemetry endpoints",
            "  core.temperature ............ online",
            "  core.stabilization .......... online",
            "  core.energy ................. online",
            "  core.structure .............. online",
            "loading gauge module ................. OK",
            "starting renderer ................... OK",
            "ready.",
    };

    // ------------------------------------------------------------------ layout

    private static final int HDR_H = 18;
    private static final int FOOT_Y = 179;
    private static final int BODY_TOP = 24;

    /** Right-hand rack column on the core page. */
    private static final int RACK_X = 134;
    private static final int RACK_W = 118;

    private static final int LOG_CAPACITY = 12;

    // -------------------------------------------------------------------- state

    private int cols = 2;
    private int rows = 2;
    private int texW;
    private int texH;

    private BufferedImage image;
    private int[] pixels;
    private Graphics2D g;
    private int texId;
    private ByteBuffer upload;
    /** Size currently allocated in video memory; the canvas may be resized later. */
    private int allocW;
    private int allocH;
    /** Last successful upload, used for the periodic refresh described in upload(). */
    private long lastUploadMs;

    private boolean dirty = true;
    private long lastBucket = Long.MIN_VALUE;

    /** Event log, newest last. Owned by the canvas so each screen keeps its own history. */
    private final List<Entry> log = new ArrayList<Entry>();
    private DFCSnapshot previous;

    /** Rendered phase bookkeeping, also used to decide the redraw cadence. */
    private int phase = PHASE_MAIN;
    private long frameMs;
    private boolean alert;

    /** Age of the newest telemetry sample, -1 when the caller does not track it. */
    private long linkAge = -1L;

    /** History rings for the chart page, one per channel. */
    private final Series tempSeries = new Series(300);
    private final Series energySeries = new Series(300);

    /**
     * Per-channel rings feeding the trend widgets.
     *
     * <p>Kept apart from the two chart rings above because those are fixed to
     * temperature and generation by the built-in graph page, while these follow
     * whatever the operator's layout happens to plot - including channels the
     * graph page never shows, like catalyst damage.</p>
     */
    private final Map<Channel, Series> channelSeries = new HashMap<Channel, Series>();
    /** The operator's layout; null until a tile entity has synced one. */
    private PanelLayout customLayout;
    /** Alarm painted over every page: -1 none, 0 banner, 1 warning, 2 alarm. */
    private int alarmLevel = -1;
    private String alarmText = "";

    private static final class Entry {
        final String text;
        final int color;

        Entry(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    /** Fixed-size ring of samples with a running min/max, for auto-ranged charts. */
    private static final class Series {
        final double[] values;
        int size;
        int head;

        Series(int capacity) {
            this.values = new double[capacity];
        }

        void add(double value) {
            this.values[this.head] = value;
            this.head = (this.head + 1) % this.values.length;
            if (this.size < this.values.length) {
                this.size++;
            }
        }

        void clear() {
            this.size = 0;
            this.head = 0;
        }

        /** Oldest-to-newest; index 0 is the oldest retained sample. */
        double at(int index) {
            int start = (this.head - this.size + this.values.length * 2) % this.values.length;
            return this.values[(start + index) % this.values.length];
        }

        double max() {
            double max = 0.0D;
            for (int i = 0; i < this.size; i++) {
                max = Math.max(max, at(i));
            }
            return max;
        }

        double min() {
            if (this.size == 0) {
                return 0.0D;
            }
            double min = Double.MAX_VALUE;
            for (int i = 0; i < this.size; i++) {
                min = Math.min(min, at(i));
            }
            return min;
        }

        double average() {
            if (this.size == 0) {
                return 0.0D;
            }
            double sum = 0.0D;
            for (int i = 0; i < this.size; i++) {
                sum += at(i);
            }
            return sum / this.size;
        }

        double last() {
            return this.size == 0 ? 0.0D : at(this.size - 1);
        }

        /** Oldest to newest as a dense array; the widget painter wants no ring math. */
        double[] toArray() {
            double[] out = new double[this.size];
            for (int i = 0; i < this.size; i++) {
                out[i] = at(i);
            }
            return out;
        }
    }

    // =========================================================== geometry / GL

    /**
     * Sets how many blocks the panel covers. Changing it reallocates the
     * backing image, so it is compared first and is a no-op in the common case.
     */
    public void setGeometry(int cols, int rows) {
        int c = Math.max(1, Math.min(8, cols));
        int r = Math.max(1, Math.min(8, rows));
        if (c == this.cols && r == this.rows && this.image != null) {
            return;
        }
        this.cols = c;
        this.rows = r;
        // Logical canvas follows the block aspect at 256px wide, clamped so an
        // extreme 1x4 strip does not allocate a 4000px tall texture.
        int logicalH = Math.round(BASE_W * (float) r / (float) c);
        logicalH = Math.max(BASE_H / 2, Math.min(BASE_H * 2, logicalH));
        this.texW = BASE_W * SS;
        this.texH = logicalH * SS;
        rebuildImage();
    }

    private void rebuildImage() {
        this.image = new BufferedImage(this.texW, this.texH, BufferedImage.TYPE_INT_ARGB);
        this.pixels = new int[this.texW * this.texH];
        this.g = this.image.createGraphics();
        this.g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        this.g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        this.g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        this.g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        this.g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        this.dirty = true;
    }

    public int textureId() {
        return this.texId;
    }

    public boolean hasTexture() {
        return this.texId != 0;
    }

    /** Texture width / height, for letterboxing the preview inside the terminal. */
    public float textureAspect() {
        return this.texH <= 0 ? 4.0F / 3.0F : (float) this.texW / (float) this.texH;
    }

    /** Backing image: used by the offline preview tool, never by the renderer. */
    public BufferedImage image() {
        ensureImage();
        return this.image;
    }

    private void ensureImage() {
        if (this.image == null) {
            setGeometry(this.cols, this.rows);
        }
    }

    /** How old the newest telemetry sample is; the panel shows it as a link lamp. */
    public void setLinkAge(long millis) {
        this.linkAge = millis;
    }

    /**
     * Hands the canvas the operator's layout and the alarm currently running.
     *
     * @param level -1 for none, 0 for a banner, 1 for a warning, 2 for an alarm
     */
    public void setCustom(PanelLayout layout, int level, String text) {
        String safe = text == null ? "" : text;
        if (this.alarmLevel != level || !this.alarmText.equals(safe)) {
            // The alarm frame blinks and the banner text changes: both are drawn
            // on a canvas that may otherwise be idle, so they must dirty it.
            this.dirty = true;
        }
        this.customLayout = layout;
        this.alarmLevel = level;
        this.alarmText = safe;
    }

    private Series trendOf(Channel channel) {
        Series series = this.channelSeries.get(channel);
        if (series == null) {
            series = new Series(40);
            this.channelSeries.put(channel, series);
        }
        return series;
    }

    /**
     * Uploads the CPU image to the GPU.
     *
     * <p>Called from the TESR, i.e. on the render thread, which is the only
     * place a texture upload is legal.</p>
     *
     * <h3>Three things here are not optional</h3>
     * <ol>
     *   <li><b>Bind on texture unit 0, through {@link GlStateManager}.</b> The
     *       active unit belongs to whoever drew last; another mod may well have
     *       left unit 1 or 2 selected, and an upload there fills a texture that
     *       nothing samples while unit 0 keeps the terrain atlas. The quad then
     *       reads as scrambled blocks scattered over the world - and the bind
     *       itself looks perfectly correct in a debugger. Mixing raw
     *       {@code glBindTexture} with the state manager is just as bad: the
     *       manager caches the binding, skips a bind it thinks is redundant,
     *       and the sampler ends up on whatever was last actually bound.</li>
     *   <li><b>BGRA from an ARGB int buffer.</b> Windows is little-endian, so
     *       the int {@code 0xAARRGGBB} lands in memory as B,G,R,A - which is
     *       exactly {@code GL_BGRA}. Swapping the bytes by hand works too, but
     *       costs a pass over every pixel of a 768x576 canvas, every upload.</li>
     *   <li><b>CLAMP_TO_EDGE and no mipmaps.</b> The canvas is 768x576: neither
     *       dimension is a power of two, and with the default REPEAT wrap some
     *       drivers call the texture incomplete and sample it as black.</li>
     * </ol>
     *
     * <p>A full re-upload is forced every few seconds. Resource-pack reloads and
     * a handful of mods invalidate texture contents behind our back, and without
     * the periodic refresh the panel stays blank until something else dirties
     * the canvas - which, on a screen showing a steady reading, may be never.</p>
     */
    public void upload() {
        if (this.image == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean forced = now - this.lastUploadMs > 4000L;
        if (!this.dirty && !forced) {
            return;
        }
        this.dirty = false;
        this.lastUploadMs = now;
        ensureTexture();

        this.image.getRGB(0, 0, this.texW, this.texH, this.pixels, 0, this.texW);
        ByteBuffer buffer = buffer(this.texW * this.texH * 4);
        buffer.clear();
        buffer.asIntBuffer().put(this.pixels);
        buffer.position(0).limit(this.pixels.length * 4);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(this.texId);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, this.texW, this.texH,
                GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buffer);
        applyTextureParams();
    }

    /**
     * Creates the texture, or reallocates it when the panel was resized.
     *
     * <p>Reallocation has to happen explicitly: {@code glTexSubImage2D} cannot
     * change the dimensions of storage that already exists, so growing a 2x2
     * panel to 4x3 would silently keep writing into a 2x2 texture.</p>
     */
    private void ensureTexture() {
        if (this.texId == 0) {
            this.texId = GL11.glGenTextures();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(this.texId);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, this.texW, this.texH, 0,
                    GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            this.allocW = this.texW;
            this.allocH = this.texH;
            applyTextureParams();
            return;
        }
        if (this.allocW != this.texW || this.allocH != this.texH) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GlStateManager.bindTexture(this.texId);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, this.texW, this.texH, 0,
                    GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            this.allocW = this.texW;
            this.allocH = this.texH;
        }
    }

    private void applyTextureParams() {
        GlStateManager.bindTexture(this.texId);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }

    /** Own buffer: the shared MC data buffer gets dirtied by other mods. */
    private ByteBuffer buffer(int needed) {
        if (this.upload == null || this.upload.capacity() < needed) {
            this.upload = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder());
        }
        return this.upload;
    }

    public void markDirty() {
        this.dirty = true;
    }

    /** Drops the chart history; powering off does the same thing. */
    public void clearHistory() {
        this.tempSeries.clear();
        this.energySeries.clear();
        // Trend widgets keep their own rings; leaving them behind would show a
        // curve from before the screen was switched off the moment it came back.
        for (Series series : this.channelSeries.values()) {
            series.clear();
        }
        this.dirty = true;
    }

    public void dispose() {
        if (this.texId != 0) {
            GL11.glDeleteTextures(this.texId);
            this.texId = 0;
            this.allocW = 0;
            this.allocH = 0;
            this.lastUploadMs = 0L;
        }
        this.dirty = true;
    }

    public List<String> eventLines() {
        List<String> list = new ArrayList<String>(this.log.size());
        for (Entry entry : this.log) {
            list.add(entry.text);
        }
        return list;
    }

    // ==================================================================== frame

    /**
     * Redraws the panel.
     *
     * @param sinceOnMs       milliseconds since this screen powered on
     * @param powered         false paints the "no signal" state
     * @param data            latest telemetry, null when nothing is bound
     * @param page            {@link #PAGE_CORE} / {@link #PAGE_PARTS} / {@link #PAGE_GRAPH}
     * @param tempRate        degrees per tick, used for the trend readout
     * @param faultElapsedMs  ms since the core started collapsing, negative when healthy
     */
    public void render(long sinceOnMs, boolean powered, DFCSnapshot data, int page,
                       double tempRate, long faultElapsedMs, TileEntityMonitorCamera.CameraView cameraView) {
        if (this.image == null) {
            setGeometry(this.cols, this.rows);
        }
        long now = System.currentTimeMillis();
        this.phase = powered ? phaseAt(sinceOnMs) : PHASE_MAIN;
        boolean animated = !powered || this.phase != PHASE_MAIN || faultElapsedMs >= 0L;
        long step = animated ? 50L : 200L;
        long bucket = now / step;
        if (bucket == this.lastBucket && !this.dirty) {
            return;
        }
        this.lastBucket = bucket;
        this.frameMs = powered ? sinceOnMs : now;

        if (!powered) {
            clearHistory();
        }
        trackEvents(data);
        if (powered && data != null && this.phase == PHASE_MAIN) {
            sampleSeries(data);
        }

        this.alert = alertActive(data);

        resetCanvas();
        if (!powered) {
            drawOff();
        } else if (faultElapsedMs >= 0L && faultElapsedMs < FAULT_MS) {
            drawFault(faultElapsedMs);
        } else if (this.phase == PHASE_BOOT) {
            drawBoot(sinceOnMs);
        } else if (this.phase == PHASE_CONSOLE) {
            drawConsole(sinceOnMs);
        } else if (cameraView != null && page == PAGE_CUSTOM) {
            pageCamera(cameraView);
        } else if (page == PAGE_CUSTOM) {
            // Before the "no data" arms on purpose: a custom panel is often
            // built out of lamps and a countdown precisely to watch a monitor
            // whose core has not been bound yet, and blanking it to "未绑定"
            // would hide the very thing the operator assembled.
            pageCustom(data);
        } else if (data == null) {
            drawUnbound();
        } else if (!data.coreFound) {
            drawNoCore(data);
        } else if (data.temperature < ONLINE_MIN_TEMP && !data.collapsing) {
            drawOffline(data);
        } else if (page == PAGE_PARTS) {
            pageParts(data);
        } else if (page == PAGE_GRAPH) {
            pageGraph(data, tempRate);
        } else {
            pageCore(data, tempRate);
        }
        drawScanlines();
        drawAlarmFrame();
        this.dirty = true;
    }

    /** Clears to background and installs the canvas -> texture transform. */
    private void resetCanvas() {
        this.g.setTransform(new java.awt.geom.AffineTransform());
        this.g.setColor(new Color(BG, true));
        this.g.fillRect(0, 0, this.texW, this.texH);

        float scale = Math.min(this.texW / (float) BASE_W, this.texH / (float) BASE_H);
        float offX = (this.texW - BASE_W * scale) * 0.5F;
        float offY = (this.texH - BASE_H * scale) * 0.5F;
        this.g.translate(offX, offY);
        this.g.scale(scale, scale);
        this.g.setStroke(new BasicStroke(1.0F));
    }

    // ================================================================== phase

    private static int phaseAt(long sinceOnMs) {
        if (sinceOnMs < BOOT_MS) {
            return PHASE_BOOT;
        }
        if (sinceOnMs < BOOT_MS + consoleMs()) {
            return PHASE_CONSOLE;
        }
        return PHASE_MAIN;
    }

    private static long consoleMs() {
        return BOOT_LOG.length * CONSOLE_LINE_MS + CONSOLE_TAIL_MS;
    }

    public static long totalBootMs() {
        return BOOT_MS + consoleMs();
    }

    private static int consoleLines(long sinceOnMs) {
        if (sinceOnMs <= BOOT_MS) {
            return 0;
        }
        long count = (sinceOnMs - BOOT_MS) / CONSOLE_LINE_MS + 1L;
        return (int) Math.max(0L, Math.min(count, BOOT_LOG.length));
    }

    private static boolean blink(long ms) {
        long half = BLINK_MS / 2L;
        return (ms / half) % 2L == 0L;
    }

    private static boolean online(DFCSnapshot d) {
        return d != null && d.coreFound && d.temperature >= ONLINE_MIN_TEMP;
    }

    private static boolean alertActive(DFCSnapshot d) {
        return online(d) && d.collapsing;
    }

    // ============================================================ event log

    /**
     * Turns snapshot-to-snapshot changes into log lines.
     *
     * <p>Only threshold crossings are recorded - sampling every frame would
     * scroll the whole log away in two seconds.</p>
     */
    private void trackEvents(DFCSnapshot data) {
        if (data == null) {
            this.previous = null;
            return;
        }
        DFCSnapshot prev = this.previous;
        if (prev == null) {
            push("\u5df2\u8fde\u63a5", OK);
            push("\u6697\u6838\u805a\u53d8\u5806\u6838\u5fc3", DIM);
            if (data.coreFound) {
                push("\u5806\u82af\u4e0a\u7ebf \u7ed3\u6784" + parts(data) + "\u4ef6", DIM);
            }
            this.previous = data;
            return;
        }
        int heatPrev = DfcFormat.heatLevel(prev.temperature, prev.meltingPoint);
        int heatNow = DfcFormat.heatLevel(data.temperature, data.meltingPoint);
        if (heatNow > heatPrev) {
            push((heatNow == 2 ? "!! " : "! ") + "\u6e29\u5ea6 "
                    + DfcFormat.temperatureLog(data.temperature), heatNow == 2 ? CRIT : WARN);
        } else if (heatNow < heatPrev) {
            push("\u6e29\u5ea6\u56de\u843d " + DfcFormat.heatLabel(heatNow), OK);
        }

        int stabPrev = stabLevel(prev);
        int stabNow = stabLevel(data);
        if (stabNow > stabPrev) {
            push((stabNow == 2 ? "!! " : "! ") + "\u7a33\u5b9a\u5ea6\u4e0b\u964d",
                    stabNow == 2 ? CRIT : WARN);
        } else if (stabNow < stabPrev) {
            push("\u7a33\u5b9a\u5ea6\u6062\u590d", OK);
        }

        if (online(data) && !online(prev)) {
            push("\u5806\u82af\u5728\u7ebf", OK);
        } else if (!online(data) && online(prev)) {
            push("! \u5806\u82af\u79bb\u7ebf", WARN);
        }

        if (parts(data) != parts(prev)) {
            push("\u7ed3\u6784\u53d8\u5316 " + parts(data) + " \u4ef6", WARN);
        }
        if (data.hasCore != prev.hasCore) {
            push(data.hasCore ? "\u71c3\u6599\u6838\u5fc3 \u5df2\u88c5\u5165" : "\u71c3\u6599\u6838\u5fc3 \u5df2\u79fb\u9664",
                    data.hasCore ? OK : WARN);
        }
        if (data.collapsing && !prev.collapsing) {
            push("## \u5d29\u6e83\u573a\u5df2\u5efa\u7acb", CRIT);
        }
        if (data.predictionMode != 0 && prev.predictionMode == 0) {
            push("! \u50ac\u5316\u5242\u5feb\u901f\u635f\u8017", WARN);
        }
        this.previous = data;
    }

    /**
     * Feeds the two chart rings. One sample per redraw keeps the plot at the
     * canvas clock (5 Hz on the live page), so a 300 sample ring is a 60 second
     * window - the number printed on the graph page's ruler.
     */
    private void sampleSeries(DFCSnapshot d) {
        this.tempSeries.add(d.temperature);
        this.energySeries.add(d.containedEnergy * 1000.0D);
    }

    private void push(String text, int color) {        this.log.add(new Entry(text, color));
        while (this.log.size() > LOG_CAPACITY) {
            this.log.remove(0);
        }
        this.dirty = true;
    }

    private static int parts(DFCSnapshot d) {
        return d.stabilizers + d.absorbers + d.pulsers + d.emitters
                + d.injectors + d.exchangers + d.cemiters;
    }

    private static int stabLevel(DFCSnapshot d) {
        double ratio = DfcFormat.stabRatio(d.stabilization);
        if (d.temperature <= 0.0D && ratio <= 0.0D) {
            return 0;
        }
        return DfcFormat.stabLevel(ratio);
    }

    // =========================================================== chrome

    /**
     * The header ribbon: a wordmark, the view name and the link lamp. It is a
     * band rather than a card so the panel does not look like a window manager
     * screenshot - this is a device front panel.
     */
    private void ribbon(String suffix, String right, int rightColor) {
        rect(0, 0, BASE_W, HDR_H, this.alert ? ALERT_BG : PANEL);
        hline(0, HDR_H - 1, BASE_W, this.alert ? ALERT_LINE : LINE);

        // Wordmark, with an amber rule that doubles as the power lamp.
        rect(5, 6, 3, 7, this.alert ? CRIT : AMBER);
        text(OS_NAME, 12, 12, this.alert ? ALERT_TXT : TXT, ScreenFonts.monoBold(8));
        vline(66, 5, 9, this.alert ? ALERT_LINE : LINE);

        String view = suffix == null ? "\u5f85\u673a" : suffix;
        text(view, 71, 12, this.alert ? CRIT : AMBER, ScreenFonts.cjk(8));

        if (right != null) {
            Font font = ScreenFonts.hasCjk(right) ? ScreenFonts.cjk(7) : ScreenFonts.mono(7);
            textRight(right, 250, 12, rightColor, font);
        }
    }

    /** Page title used on the three live views. */
    private String pageTitle(int page) {
        int index = Math.max(0, Math.min(PAGE_NAMES.length - 1, page));
        return "0" + (index + 1) + "  " + PAGE_NAMES[index] + " \u00b7 " + PAGE_SUBTITLES[index];
    }

    /**
     * The footer ticker: one line of everything that has to be true for the
     * reading to mean anything - what is bound, how hot, is the feed alive.
     */
    private void ticker(DFCSnapshot d) {
        hline(0, FOOT_Y, BASE_W, this.alert ? ALERT_LINE : LINE);

        int lamp;
        String link;
        if (this.linkAge < 0L) {
            lamp = this.alert ? ALERT_TXT : OK;
            link = "LINK";
        } else if (this.linkAge < 3000L) {
            lamp = OK;
            link = "LINK OK";
        } else if (this.linkAge < 8000L) {
            lamp = WARN;
            link = "LINK SLOW";
        } else {
            lamp = CRIT;
            link = "\u6570\u636e\u4e2d\u65ad";
        }
        rect(5, 184, 3, 4, lamp);

        StringBuilder line = new StringBuilder();
        if (d != null && d.coreFound) {
            line.append(d.coreType == null ? "DFC" : d.coreType);
            line.append(" // ").append(DfcFormat.temperatureLog(d.temperature));
            line.append(" // STAB ").append(DfcFormat.percentShort(DfcFormat.stabRatio(d.stabilization)));
        } else {
            line.append("NO SOURCE");
        }
        line.append(" // ").append(link);

        Font font = ScreenFonts.mono(7);
        String text = clip(line.toString(), 214, font);
        text(text, 12, 188, this.alert ? ALERT_TXT : DIM, font);

        // Page dots: three, filled for the active one.
        for (int i = 0; i < PAGE_COUNT; i++) {
            rect(234 + i * 7, 185, 4, 4, i == lastPageShown ? AMBER : OFF_SEG);
        }
    }

    /**
     * The ticker needs the page that was last painted. Keeping it as a field is
     * cheaper than threading the page index through every draw call.
     */
    private int lastPageShown;

    // ================================================================ core page

    private void pageCore(DFCSnapshot d, double tempRate) {
        ribbon(pageTitle(PAGE_CORE), DfcFormat.uptime(d.tick), DIM);

        text("DFC \u5806\u82af", 6, 31, DIM, ScreenFonts.cjk(8));
        textRight(d.coreType == null ? "--" : d.coreType, 130, 31, AMBER, ScreenFonts.mono(7));

        // ---- left: the twin ring gauge ----
        int cx = 72;
        int cy = 94;
        int rOut = 44;
        int rIn = 30;

        double heatRatio = d.meltingPoint > 0.0D
                ? Math.max(0.0D, Math.min(1.0D, d.temperature / d.meltingPoint)) : 0.0D;
        double stabRatio = DfcFormat.stabRatio(d.stabilization);
        int heatLevel = DfcFormat.heatLevel(d.temperature, d.meltingPoint);
        int heatColor = gaugeColor(heatLevel);

        arcStroke(cx, cy, rOut, 5.0F, GAUGE_START, GAUGE_SWEEP, 0xFF2A2318);
        arcStroke(cx, cy, rOut, 5.0F, GAUGE_START, GAUGE_SWEEP * heatRatio, heatColor);
        // Ticks every tenth of the sweep, inside the ring.
        for (int i = 0; i <= 10; i++) {
            double angle = Math.toRadians(GAUGE_START + GAUGE_SWEEP * i / 10.0D);
            int x0 = cx + (int) Math.round(Math.cos(angle) * (rOut - 9));
            int y0 = cy - (int) Math.round(Math.sin(angle) * (rOut - 9));
            int x1 = cx + (int) Math.round(Math.cos(angle) * (rOut - 5));
            int y1 = cy - (int) Math.round(Math.sin(angle) * (rOut - 5));
            line(x0, y0, x1, y1, i % 5 == 0 ? DIM : 0xFF4A3F2C);
        }

        arcStroke(cx, cy, rIn, 3.0F, GAUGE_START, GAUGE_SWEEP, 0xFF2A2318);
        arcStroke(cx, cy, rIn, 3.0F, GAUGE_START, GAUGE_SWEEP * stabRatio, levelColor(stabLevel(d)));

        // Needle aimed at the temperature fraction on the outer scale.
        double needle = Math.toRadians(GAUGE_START + GAUGE_SWEEP * heatRatio);
        line(cx + (int) Math.round(Math.cos(needle) * (rOut - 12)),
                cy - (int) Math.round(Math.sin(needle) * (rOut - 12)),
                cx + (int) Math.round(Math.cos(needle) * (rOut + 4)),
                cy - (int) Math.round(Math.sin(needle) * (rOut + 4)), NEEDLE);

        textCenterAt("\u6e29\u5ea6", cx, cy - 14, DIM, ScreenFonts.cjk(7));
        // Whole degrees only: six characters is what fits inside the inner ring
        // at a size worth reading across a room, and two decimals on a wall
        // gauge are noise. The graph page and the terminal keep the precision.
        String value = String.format(Locale.ROOT, "%.0f", d.temperature);
        Font valueFont = fitMono(value, 60, 22, 10, true);
        int valueW = textWidth(value, valueFont);
        this.g.setFont(valueFont);
        this.g.setColor(new Color(heatLevel == 0 ? TXT : heatColor, true));
        this.g.drawString(value, cx - valueW / 2 - 3, cy + 14);
        text("\u00b0C", cx + valueW / 2 - 1, cy + 14, DIM, ScreenFonts.mono(8));
        textCenterAt("\u7a33\u5b9a " + DfcFormat.percentShort(stabRatio), cx, cy + 30,
                levelColor(stabLevel(d)), ScreenFonts.cjk(7));

        textCenterAt("\u7194\u70b9 " + DfcFormat.celsiusMark(d.meltingPoint), cx, 158, DIM,
                ScreenFonts.cjk(7));

        // ---- right: the seven channel rack ----
        text("\u7ed3\u6784\u706f\u67f1", RACK_X, 31, DIM, ScreenFonts.cjk(8));
        textRight(parts(d) + " \u4ef6", RACK_X + RACK_W, 31, AMBER, ScreenFonts.monoBold(9));
        hline(RACK_X, 34, RACK_W, LINE);

        int[] counts = {d.stabilizers, d.absorbers, d.pulsers, d.emitters,
                d.injectors, d.exchangers, d.cemiters};
        String[] tags = {"STA", "ABS", "PLS", "EMT", "INJ", "EXC", "CMT"};
        for (int i = 0; i < counts.length; i++) {
            int y = 43 + i * 10;
            text(tags[i], RACK_X, y, counts[i] > 0 ? DIM : 0xFF4E4433, ScreenFonts.mono(6));
            segBar(RACK_X + 24, y - 6, 70, 6, 10, Math.min(1.0D, counts[i] / 10.0D),
                    counts[i] > 0 ? AMBER : OFF_SEG);
            textRight(String.valueOf(counts[i]), RACK_X + RACK_W, y,
                    counts[i] > 0 ? TXT : 0xFF4E4433, ScreenFonts.monoBold(8));
        }
        hline(RACK_X, 106, RACK_W, LINE);

        energyRows(d, 116);

        hline(RACK_X, 138, RACK_W, LINE);
        double damage = Math.max(d.damageA, d.damageB);
        int damageColor = levelColor(DfcFormat.collapseLevel(damage / 100.0D));
        text("\u5d29\u6e83\u98ce\u9669", RACK_X, 147, DIM, ScreenFonts.cjk(7));
        textRight(DfcFormat.percentOf100(damage), RACK_X + RACK_W, 147, damageColor,
                ScreenFonts.monoBold(8));
        segBar(RACK_X, 151, RACK_W, 5, 18, damage / 100.0D, damageColor);

        String prediction = d.predictedSeconds > 0
                ? DfcFormat.countdown(d.predictedSeconds) : "--:--";
        text("\u9884\u6d4b", RACK_X, 168, DIM, ScreenFonts.cjk(7));
        textRight(prediction, RACK_X + RACK_W, 168,
                d.predictedSeconds > 0 ? WARN : DIM, ScreenFonts.mono(7));

        ticker(d);
        lastPageShown = PAGE_CORE;
    }

    /** Storage / output / gain, three aligned rows shared by two pages. */
    private void energyRows(DFCSnapshot d, int y) {
        row(RACK_X, y, RACK_W, "\u50a8\u80fd", DfcFormat.spk(d.containedEnergy), MINT);
        row(RACK_X, y + 9, RACK_W, "\u8f93\u51fa", DfcFormat.spkRate(d.expellingEnergy), TXT);
        row(RACK_X, y + 18, RACK_W, "\u589e\u76ca", DfcFormat.gain(d.potentialGain), NEEDLE);
    }

    // =============================================================== parts page

    /**
     * The mimic diagram: the seven structure channels sit on a ring around the
     * core and are wired to it. A filled wire means the channel is installed,
     * so the shape of the reactor is readable at a glance instead of being a
     * table of numbers.
     */
    private void pageParts(DFCSnapshot d) {
        ribbon(pageTitle(PAGE_PARTS), parts(d) + " \u4ef6", AMBER);

        text("\u53cd\u5e94\u5806\u7ed3\u6784", 6, 31, DIM, ScreenFonts.cjk(8));
        textRight(d.hasCore ? "\u71c3\u6599\u5df2\u88c5\u5165" : "\u65e0\u71c3\u6599", 250, 31,
                d.hasCore ? OK : WARN, ScreenFonts.cjk(7));

        int cx = 128;
        int cy = 88;
        int nodeR = 44;
        int[] counts = {d.stabilizers, d.absorbers, d.pulsers, d.emitters,
                d.injectors, d.exchangers, d.cemiters};
        String[] names = {"\u7a33\u5b9a", "\u5438\u6536", "\u8109\u51b2", "\u53d1\u5c04",
                "\u6ce8\u5165", "\u4ea4\u6362", "\u4e2d\u5b50"};

        for (int i = 0; i < counts.length; i++) {
            double angle = Math.toRadians(-90.0D + i * (360.0D / counts.length));
            int nx = cx + (int) Math.round(Math.cos(angle) * nodeR);
            int ny = cy + (int) Math.round(Math.sin(angle) * nodeR);
            boolean on = counts[i] > 0;
            // Wire from the core shell out to the node, stopping short of both.
            line(cx + (int) Math.round(Math.cos(angle) * 23),
                    cy + (int) Math.round(Math.sin(angle) * 23),
                    nx - (int) Math.round(Math.cos(angle) * 13),
                    ny - (int) Math.round(Math.sin(angle) * 13), on ? AMBER_DEEP : 0xFF2E2719);

            // The node names itself. Earlier versions put the Chinese name
            // outside the box along the radius, where the bottom two landed on
            // the fuel row and the top one on the header.
            int bx = nx - 17;
            int by = ny - 7;
            panel(bx, by, 34, 14, on ? PANEL_HI : PANEL);
            text(names[i], bx + 3, by + 10, on ? AMBER : 0xFF4E4433, ScreenFonts.cjk(6));
            textRight(String.valueOf(counts[i]), bx + 31, by + 10,
                    on ? TXT : 0xFF4E4433, ScreenFonts.monoBold(8));
        }

        // Core shell: two concentric rings and the part count in the middle.
        ovalStroke(cx - 21, cy - 21, 42, 42, this.alert ? ALERT_LINE : LINE, 1.0F);
        ovalStroke(cx - 17, cy - 17, 34, 34, 0xFF2E2719, 1.0F);
        textCenter("CORE", cy - 4, DIM, ScreenFonts.mono(6));
        textCenter(String.valueOf(parts(d)), cy + 12, AMBER, ScreenFonts.monoBold(15));

        // ---- fuel columns ----
        hline(4, 140, BASE_W - 8, LINE);
        tank(6, 146, 116, "A", d.fuel1Name, DfcFormat.percent(ratio(d.fuel1Fill, d.fuel1Max)),
                ratio(d.fuel1Fill, d.fuel1Max));
        tank(134, 146, 118, "B", d.fuel2Name, DfcFormat.percent(ratio(d.fuel2Fill, d.fuel2Max)),
                ratio(d.fuel2Fill, d.fuel2Max));

        Font kpi = ScreenFonts.mono(7);
        text("\u50a8\u80fd", 6, 176, DIM, ScreenFonts.cjk(7));
        text(DfcFormat.spk(d.containedEnergy), 30, 176, MINT, kpi);
        text("\u8f93\u51fa", 96, 176, DIM, ScreenFonts.cjk(7));
        text(DfcFormat.spkRate(d.expellingEnergy), 120, 176, TXT, kpi);
        text("\u589e\u76ca", 196, 176, DIM, ScreenFonts.cjk(7));
        text(DfcFormat.gain(d.potentialGain), 220, 176, NEEDLE, kpi);

        ticker(d);
        lastPageShown = PAGE_PARTS;
    }

    private void tank(int x, int y, int w, String tag, String name, String pct, double ratio) {
        int color = ratio > 0.2D ? AMBER : WARN;
        text(tag, x, y + 6, color, ScreenFonts.monoBold(8));
        Font nameFont = ScreenFonts.mono(7);
        String label = name == null ? "--" : name;
        int room = w - 34 - textWidth(pct, ScreenFonts.mono(7)) - 6;
        if (textWidth(label, nameFont) > room) {
            label = clip(label, room, nameFont);
        }
        text(label, x + 10, y + 6, DIM, nameFont);
        textRight(pct, x + w, y + 6, color, ScreenFonts.mono(7));
        segBar(x, y + 9, w, 5, 16, ratio, color);
    }

    // =============================================================== graph page

    private void pageGraph(DFCSnapshot d, double tempRate) {
        ribbon(pageTitle(PAGE_GRAPH), "\u5e45\u5ea6 60s", DIM);

        // Two chips name the traces; the plot itself carries no legend clutter.
        text("\u6e29\u5ea6", 8, 33, DIM, ScreenFonts.cjk(7));
        text(DfcFormat.temperature(d.temperature), 30, 33, AMBER, ScreenFonts.monoBold(9));
        text("\u53d1\u7535", 130, 33, DIM, ScreenFonts.cjk(7));
        text(DfcFormat.spk(d.containedEnergy), 152, 33, MINT, ScreenFonts.monoBold(9));

        int x0 = 30;
        int x1 = 248;
        int y0 = 40;
        int y1 = 118;
        int plotW = x1 - x0;
        int plotH = y1 - y0;

        double tempMax = this.tempSeries.max();
        double tempScale = niceCeiling(Math.max(tempMax, d.temperature));
        double energyMax = this.energySeries.max();
        double energyScale = niceCeiling(Math.max(energyMax, d.containedEnergy * 1000.0D));

        // Grid + the temperature scale on the left edge.
        for (int i = 0; i <= 4; i++) {
            int gy = y1 - plotH * i / 4;
            hline(x0, gy, plotW, GRID);
            textRight(axisLabel(tempScale * i / 4.0D), x0 - 3, gy + 3, 0xFF6B5C44,
                    ScreenFonts.mono(6));
        }

        // Time ruler: ticks on the top edge, labels inside the plot floor.
        // Drawing the rule four pixels above the grid put it straight through
        // the value chips that sit at the same height.
        for (int i = 0; i <= 6; i++) {
            int gx = x0 + plotW * i / 6;
            vline(gx, y0 - 3, 3, 0xFF4A3F2C);
        }
        text("-60s", x0 + 1, y1 - 2, 0xFF6B5C44, ScreenFonts.mono(6));
        textRight("now", x1 - 1, y1 - 2, 0xFF6B5C44, ScreenFonts.mono(6));

        if (this.tempSeries.size >= 2) {
            int n = this.tempSeries.size;
            int[] xs = new int[n];
            int[] ys = new int[n];
            for (int i = 0; i < n; i++) {
                xs[i] = x0 + plotW * i / Math.max(1, n - 1);
                double t = this.tempSeries.at(i) / tempScale;
                ys[i] = y1 - (int) Math.round(Math.max(0.0D, Math.min(1.0D, t)) * plotH);
            }
            fillBelow(xs, ys, y1, n, 0x22FFB03A);
            polyline(xs, ys, n, AMBER, 1.2F);
            ovalFill(xs[n - 1] - 2, ys[n - 1] - 2, 4, 4, NEEDLE);
        }

        if (this.energySeries.size >= 2 && energyScale > 0.0D) {
            int n = this.energySeries.size;
            int[] xs = new int[n];
            int[] ys = new int[n];
            for (int i = 0; i < n; i++) {
                xs[i] = x0 + plotW * i / Math.max(1, n - 1);
                double e = this.energySeries.at(i) / energyScale;
                ys[i] = y1 - (int) Math.round(Math.max(0.0D, Math.min(1.0D, e)) * plotH);
            }
            polyline(xs, ys, n, MINT, 1.0F);
        }

        if (this.tempSeries.size < 2) {
            textCenter("\u7b49\u5f85\u91c7\u6837", y0 + plotH / 2, DIM, ScreenFonts.cjk(9));
        }

        // Session statistics, two rows of three.
        hline(4, 126, BASE_W - 8, LINE);
        statCell(8, 134, "\u6700\u4f4e", axisValue(this.tempSeries.min(), true), AMBER);
        statCell(92, 134, "\u5e73\u5747", axisValue(this.tempSeries.average(), true), AMBER);
        statCell(176, 134, "\u6700\u9ad8", axisValue(this.tempSeries.max(), true), AMBER);
        statCell(8, 162, "\u5cf0\u503c", axisValue(this.energySeries.max(), false), MINT);
        statCell(92, 162, "\u5f53\u524d", axisValue(d.containedEnergy * 1000.0D, false), MINT);
        statCell(176, 162, "\u659c\u7387", DfcFormat.rate(tempRate), DIM);

        ticker(d);
        lastPageShown = PAGE_GRAPH;
    }

    private void statCell(int x, int y, String label, String value, int color) {
        text(label, x, y, DIM, ScreenFonts.cjk(7));
        text(value, x, y + 13, color, ScreenFonts.monoBold(11));
    }

    private static String axisValue(double value, boolean celsius) {
        if (value <= 0.0D) {
            return "--";
        }
        if (celsius) {
            return DfcFormat.temperatureLog(value);
        }
        return value >= 1000.0D ? String.format(Locale.ROOT, "%.1fk", value / 1000.0D)
                : String.format(Locale.ROOT, "%.0f", value);
    }

    private static double niceCeiling(double max) {
        if (max <= 0.0D) {
            return 1.0D;
        }
        double magnitude = Math.pow(10.0D, Math.floor(Math.log10(max)));
        double normalized = max / magnitude;
        double step;
        if (normalized <= 1.0D) {
            step = 1.0D;
        } else if (normalized <= 2.0D) {
            step = 2.0D;
        } else if (normalized <= 5.0D) {
            step = 5.0D;
        } else {
            step = 10.0D;
        }
        return step * magnitude;
    }

    private static String axisLabel(double value) {
        if (Math.abs(value) >= 1000.0D) {
            double k = value / 1000.0D;
            return Math.abs(k - Math.rint(k)) < 0.05D
                    ? String.format(Locale.ROOT, "%.0fk", k)
                    : String.format(Locale.ROOT, "%.1fk", k);
        }
        if (Math.abs(value) >= 10.0D) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        if (value == 0.0D) {
            return "0";
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    // ============================================================= custom page

    /**
     * Page 04: the operator's own layout, drawn widget by widget.
     *
     * <p>Nothing here knows what a gauge looks like - that is
     * {@link WidgetPainter}'s job, shared with the studio - so the page is just
     * "walk the layout, hand each control its value". Which also means a
     * control type added later shows up here without touching this method.</p>
     *
     * <p>The page works with {@code null} telemetry: every channel reads 0,
     * lamps go dark, and the banner falls back to 「系统正常」. That is the
     * honest rendering of "the screen is fine, the core is not talking".</p>
     */
    private void pageCamera(TileEntityMonitorCamera.CameraView camera) {
        ribbon(pageTitle(PAGE_CUSTOM), camera.name, DIM);

        rect(12, 30, BASE_W - 24, 94, 0xFF101418);
        hline(12, 30, BASE_W - 24, 0xFF334A5B);
        hline(12, 123, BASE_W - 24, 0xFF334A5B);
        vline(12, 30, 94, 0xFF334A5B);
        vline(BASE_W - 13, 30, 94, 0xFF334A5B);
        int cellW = 4;
        int cellH = 6;
        int startX = 18;
        int startY = 36;
        for (int y = 0; y < camera.height; y++) {
            for (int x = 0; x < camera.width; x++) {
                int idx = y * camera.width + x;
                int color = camera.pixels[idx];
                rect(startX + x * cellW, startY + y * cellH, cellW, cellH, color);
            }
        }

        text("频道 " + camera.channel, 18, 136, 0xFF8CD9D1, ScreenFonts.cjk(8));
        text("角度 " + camera.yaw + "° / " + camera.pitch + "°", 118, 136, 0xFFEDE1C4,
                ScreenFonts.cjk(8));
        text("范围 " + camera.width + "x" + camera.height + " 视野", 18, 150,
                0xFF9A8967, ScreenFonts.cjk(7));
        text("监控模式", 200, 150, 0xFFFFB03A, ScreenFonts.cjk(7));

        ticker(null);
        lastPageShown = PAGE_CUSTOM;
    }

    private void pageCustom(DFCSnapshot d) {
        PanelLayout layout = this.customLayout;
        int count = layout == null ? 0 : layout.size();
        ribbon(pageTitle(PAGE_CUSTOM), count + " \u63a7\u4ef6", DIM);

        if (count == 0) {
            textCenter("\u9762\u677f\u4e3a\u7a7a", 82, WARN, ScreenFonts.cjkBold(16));
            textCenter("\u5728\u7ec8\u7aef\u91cc\u6253\u5f00\u9762\u677f\u5de5\u574a", 100, DIM,
                    ScreenFonts.cjk(8));
            textCenter("\u6dfb\u52a0\u63a7\u4ef6\u540e\u4fdd\u5b58\u5373\u53ef\u751f\u6548", 112, 0xFF6B5C44,
                    ScreenFonts.cjk(7));
            ticker(d);
            lastPageShown = PAGE_CUSTOM;
            return;
        }

        for (Widget widget : layout.widgets) {
            int x = PanelLayout.ORIGIN_X + widget.gx * PanelLayout.GRID;
            int y = PanelLayout.ORIGIN_Y + widget.gy * PanelLayout.GRID;
            int w = widget.gw * PanelLayout.GRID;
            int h = widget.gh * PanelLayout.GRID;

            WidgetPainter.Ctx ctx = new WidgetPainter.Ctx();
            ctx.value = widget.value(d);
            ctx.banner = this.alarmText;
            ctx.level = Math.max(0, this.alarmLevel);
            ctx.active = this.alarmLevel >= 0 && !this.alarmText.isEmpty();
            if (widget.type == WidgetType.TREND) {
                fillTrend(ctx, widget.channel, d);
            }
            WidgetPainter.paint(this.g, x, y, w, h, widget, ctx);
        }

        ticker(d);
        lastPageShown = PAGE_CUSTOM;
    }

    /** Feeds one trend ring and hands the painter a contiguous copy of it. */
    private void fillTrend(WidgetPainter.Ctx ctx, Channel channel, DFCSnapshot d) {
        Series series = trendOf(channel);
        if (d != null) {
            series.add(channel.read(d));
        }
        ctx.trend = series.toArray();
        ctx.trendCount = series.size;
    }

    /**
     * The alarm overlay, painted on top of every page.
     *
     * <p>Deliberately not a page of its own: an operator watching the graph
     * must still see that the core is over temperature. The strip sits in the
     * gap between the header rule and the body so it covers as little content
     * as possible, and the corner brackets blink rather than the whole frame -
     * a full flashing border makes the numbers underneath unreadable, which is
     * the opposite of what an alarm is for.</p>
     */
    private void drawAlarmFrame() {
        if (this.alarmLevel < 0 || this.alarmText == null || this.alarmText.isEmpty()) {
            return;
        }
        boolean hot = this.alarmLevel >= 2;
        int color = hot ? CRIT : WARN;
        boolean on = blink(this.frameMs);

        rect(0, HDR_H, BASE_W, 12, hot ? ALERT_BG : 0xFF211B0C);
        rect(0, HDR_H, 3, 12, on ? color : ALERT_LINE);
        String line = (hot ? "!! " : "! ") + this.alarmText;
        text(clip(line, 218, ScreenFonts.cjk(8)), 9, HDR_H + 9,
                on ? color : ALERT_TXT, ScreenFonts.cjk(8));
        textRight(hot ? "\u62a5\u8b66" : "\u63d0\u793a", BASE_W - 6, HDR_H + 9, color,
                ScreenFonts.cjk(7));

        if (on) {
            int arm = 6;
            rect(0, HDR_H + 12, arm, 1, color);
            rect(0, BASE_H - 1, arm, 1, color);
            rect(BASE_W - arm, HDR_H + 12, arm, 1, color);
            rect(BASE_W - arm, BASE_H - 1, arm, 1, color);
            rect(0, HDR_H + 12, 1, arm, color);
            rect(BASE_W - 1, HDR_H + 12, 1, arm, color);
            rect(0, BASE_H - arm, 1, arm, color);
            rect(BASE_W - 1, BASE_H - arm, 1, arm, color);
        }
    }

    // ============================================================ special pages

    private void drawOff() {
        textCenter("\u65e0\u4fe1\u53f7", 88, DIM, ScreenFonts.cjkBold(20));
        textCenter("NO SIGNAL", 104, 0xFF4E4433, ScreenFonts.mono(9));
        if (blink(this.frameMs)) {
            rect(BASE_W / 2 - 26, 118, 52, 2, AMBER_DEEP);
        }
    }

    private void drawBoot(long ms) {
        // Soft amber halo behind the wordmark, three stacked ellipses.
        ovalFill(128 - 88, 52, 176, 52, 0x0CFFB03A);
        ovalFill(128 - 66, 58, 132, 40, 0x10FFB03A);
        ovalFill(128 - 44, 64, 88, 28, 0x14FFB03A);

        Font mark = fitMono(OS_NAME, 208, 30, 14, true);
        textCenter(OS_NAME, 96, 0xFFF3E4C2, mark);
        textCenter("\u53cd\u5e94\u5806\u9065\u6d4b\u4e0e\u8054\u9501\u7cfb\u7edf", 118, AMBER,
                ScreenFonts.cjk(10));

        String spaced = "A H B S S M F I - O S";
        textCenter(spaced, 134, 0xFF6B5A3E, ScreenFonts.mono(7));

        // Segmented loading bar: 24 cells, lit one at a time.
        int cells = 24;
        int barW = 168;
        int barX = (BASE_W - barW) / 2;
        int cellW = barW / cells - 1;
        float progress = Math.min(1.0F, ms / (float) BOOT_MS);
        int lit = Math.round(cells * progress);
        for (int i = 0; i < cells; i++) {
            rect(barX + i * (cellW + 1), 150, cellW, 6, i < lit ? AMBER : 0xFF2A2318);
        }
        textCenter("\u7248\u672c " + OS_VERSION, 172, DIM, ScreenFonts.cjk(8));
        if (blink(ms)) {
            rect(80, 178, 5, 5, OK);
        }
    }

    private void drawConsole(long ms) {
        int shown = consoleLines(ms);
        int y = 26;
        // Left gutter with a rail, so the log reads as a terminal and not a page.
        rect(3, 20, 1, 156, LINE);
        for (int i = 0; i < shown; i++) {
            String line = BOOT_LOG[i];
            int color = i == 0 ? TXT : line.contains("OK") || line.contains("online") ? OK : DIM;
            if (line.startsWith("  dev") || line.startsWith("  core")) {
                color = MINT;
            }
            rect(6, y - 5, 2, 2, color);
            text(line, 12, y, color, ScreenFonts.mono(7));
            y += 11;
        }
        if (shown < BOOT_LOG.length && blink(ms)) {
            int last = Math.max(0, shown - 1);
            rect(12 + textWidth(BOOT_LOG[last], ScreenFonts.mono(7)) + 3, y - 13, 5, 7, AMBER);
        }
    }

    private void drawFault(long elapsed) {
        rect(0, 0, BASE_W, BASE_H, FAULT_BG);
        hazard(0, 0, BASE_W, 10);
        hazard(0, BASE_H - 10, BASE_W, 10);

        boolean on = blink(this.frameMs);
        text("!!  SYSTEM FAULT", 8, 26, on ? CRIT : 0xFF7E2A18, ScreenFonts.monoBold(9));
        textCenter("301", 74, CRIT, ScreenFonts.monoBold(34));
        textCenter("\u4e0e\u5806\u82af\u5931\u53bb\u8fde\u63a5", 96, FAULT_TXT, ScreenFonts.cjkBold(14));

        text("dumping frame ................ 0xDFC301", 14, 120, 0xFF6E4A3A, ScreenFonts.mono(7));
        text("last telemetry ............... lost", 14, 131, 0xFF6E4A3A, ScreenFonts.mono(7));
        text("link state ................... DISCONNECTED", 14, 142, FAULT_TXT,
                ScreenFonts.mono(7));

        int remaining = (int) Math.ceil((FAULT_MS - elapsed) / 1000.0D);
        textCenter("\u5c4f\u5e55\u5c06\u5728 " + remaining + " \u79d2\u540e\u91cd\u542f", 162,
                FAULT_TXT, ScreenFonts.cjk(8));
        int barW = BASE_W - 40;
        int cells = 20;
        int cellW = barW / cells - 1;
        int lit = Math.round(cells * Math.max(0.0F, Math.min(1.0F, elapsed / (float) FAULT_MS)));
        for (int i = 0; i < cells; i++) {
            rect(20 + i * (cellW + 1), 170, cellW, 4, i < lit ? 0xFF8A2A18 : 0xFF3A0F0A);
        }
    }

    /** Diagonal hazard band, used on the fault page instead of a plain rule. */
    private void hazard(int x, int y, int w, int h) {
        rect(x, y, w, h, 0xFF2A0D08);
        this.g.setColor(new Color(0xFF5A1A10, true));
        this.g.setStroke(new BasicStroke(3.0F));
        for (int i = -h; i < w; i += 12) {
            this.g.drawLine(x + i, y + h, x + i + h, y);
        }
        this.g.setStroke(new BasicStroke(1.0F));
    }

    private void drawOffline(DFCSnapshot d) {
        ribbon("\u51b7\u5806\u5f85\u673a", DfcFormat.uptime(d.tick), DIM);
        textCenter("\u51b7\u5806", 68, WARN, ScreenFonts.cjkBold(22));
        textCenter("CORE BELOW IGNITION", 84, DIM, ScreenFonts.mono(6));

        int cx = 128;
        int cy = 108;
        arcStroke(cx, cy, 20, 4.0F, GAUGE_START, GAUGE_SWEEP, 0xFF2A2318);
        // One amber tick at the cold end of the scale, so the gauge reads as
        // "parked at zero" rather than as a broken ring.
        double cold = Math.toRadians(GAUGE_START);
        line(cx + (int) Math.round(Math.cos(cold) * 24), cy - (int) Math.round(Math.sin(cold) * 24),
                cx + (int) Math.round(Math.cos(cold) * 29), cy - (int) Math.round(Math.sin(cold) * 29),
                AMBER);

        int w = 120;
        int x = (BASE_W - w) / 2;
        hline(x, 140, w, LINE);
        row(x, 152, w, "\u6e29\u5ea6", DfcFormat.temperature(d.temperature), WARN);
        row(x, 164, w, "\u7a33\u5b9a\u5ea6", DfcFormat.stabilization(d.stabilization), DIM);
        textCenter("\u8fbe\u5230 " + DfcFormat.celsiusMark(ONLINE_MIN_TEMP) + " \u540e\u8fdb\u5165\u5728\u7ebf",
                176, 0xFF6B5C44, ScreenFonts.cjk(7));
        ticker(d);
        lastPageShown = PAGE_CORE;
    }

    private void drawUnbound() {
        ribbon(null, "\u79bb\u7ebf", CRIT);
        textCenter("\u672a\u7ed1\u5b9a\u6570\u636e\u6e90", 74, WARN, ScreenFonts.cjkBold(16));
        textCenter("\u5bf9\u7740 DFC \u5806\u82af\u4f7f\u7528\u94fe\u63a5\u5668\u5373\u53ef\u8fde\u63a5", 94, DIM,
                ScreenFonts.cjk(9));

        int x = 40;
        int w = BASE_W - 80;
        hline(x, 108, w, LINE);
        row(x, 122, w, "\u626b\u63cf\u534a\u5f84", "16 \u683c", DIM);
        row(x, 134, w, "\u72b6\u6001", "\u7b49\u5f85\u94fe\u63a5", WARN);
        ticker(null);
    }

    private void drawNoCore(DFCSnapshot d) {
        ribbon("\u65e0\u5806\u82af", "\u626b\u63cf\u4e2d", WARN);
        textCenter("NO CORE", 80, WARN, ScreenFonts.monoBold(22));
        textCenter("\u672a\u627e\u5230\u5806\u82af", 98, DIM, ScreenFonts.cjk(9));
        hline(50, 112, BASE_W - 100, LINE);
        row(50, 126, BASE_W - 100, "\u76d1\u89c6\u5750\u6807", "---", DIM);
        row(50, 138, BASE_W - 100, "\u63d0\u793a", "\u5806\u82af\u5df2\u62c6\u9664\u6216\u8d85\u51fa\u8303\u56f4", DIM);
        ticker(d);
    }

    // ============================================================== primitives

    private void rect(int x, int y, int w, int h, int argb) {
        this.g.setColor(new Color(argb, true));
        this.g.fillRect(x, y, w, h);
    }

    private void hline(int x, int y, int w, int argb) {
        rect(x, y, w, 1, argb);
    }

    private void vline(int x, int y, int h, int argb) {
        rect(x, y, 1, h, argb);
    }

    private void line(int x0, int y0, int x1, int y1, int argb) {
        this.g.setColor(new Color(argb, true));
        this.g.drawLine(x0, y0, x1, y1);
    }

    /** A card. Only used where a real inset panel exists, e.g. the mimic nodes. */
    private void panel(int x, int y, int w, int h, int fill) {
        rect(x, y, w, h, fill);
        hline(x, y, w, LINE);
        hline(x, y + h - 1, w, LINE);
        vline(x, y, h, LINE);
        vline(x + w - 1, y, h, LINE);
    }

    private void ovalFill(int x, int y, int w, int h, int argb) {
        this.g.setColor(new Color(argb, true));
        this.g.fillOval(x, y, w, h);
    }

    private void ovalStroke(int x, int y, int w, int h, int argb, float thickness) {
        this.g.setColor(new Color(argb, true));
        this.g.setStroke(new BasicStroke(thickness));
        this.g.drawOval(x, y, w, h);
        this.g.setStroke(new BasicStroke(1.0F));
    }

    /**
     * A gauge arc. Angles are degrees, 0 at 3 o'clock, counter-clockwise, the
     * way {@link Arc2D} measures them.
     */
    private void arcStroke(int cx, int cy, int radius, float thickness,
                           double startDeg, double extentDeg, int argb) {
        if (Math.abs(extentDeg) < 0.5D) {
            return;
        }
        this.g.setColor(new Color(argb, true));
        this.g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        this.g.draw(new Arc2D.Double(cx - radius, cy - radius, radius * 2.0D, radius * 2.0D,
                startDeg, extentDeg, Arc2D.OPEN));
        this.g.setStroke(new BasicStroke(1.0F));
    }

    /** Segmented bar: the lamp-rack readout. Counts cells, not pixels. */
    private void segBar(int x, int y, int w, int h, int cells, double ratio, int color) {
        double clamped = Math.max(0.0D, Math.min(1.0D, ratio));
        int lit = (int) Math.round(cells * clamped);
        int cellW = Math.max(1, (w - (cells - 1)) / cells);
        for (int i = 0; i < cells; i++) {
            rect(x + i * (cellW + 1), y, cellW, h, i < lit ? color : OFF_SEG);
        }
    }

    private void polyline(int[] xs, int[] ys, int n, int argb, float thickness) {
        this.g.setColor(new Color(argb, true));
        this.g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 1; i < n; i++) {
            this.g.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i]);
        }
        this.g.setStroke(new BasicStroke(1.0F));
    }

    private void fillBelow(int[] xs, int[] ys, int base, int n, int argb) {
        this.g.setColor(new Color(argb, true));
        java.awt.Polygon polygon = new java.awt.Polygon();
        for (int i = 0; i < n; i++) {
            polygon.addPoint(xs[i], ys[i]);
        }
        polygon.addPoint(xs[n - 1], base);
        polygon.addPoint(xs[0], base);
        this.g.fillPolygon(polygon);
    }

    private void row(int x, int y, int width, String label, String value, int valueColor) {
        Font labelFont = ScreenFonts.cjk(7);
        text(label, x, y, DIM, labelFont);
        int avail = Math.max(8, width - textWidth(label, labelFont) - 5);
        String text = value == null ? "--" : value;
        Font valueFont = ScreenFonts.hasCjk(text)
                ? fitCjk(text, avail, 8, 6)
                : fitMono(text, avail, 8, 6, true);
        if (textWidth(text, valueFont) > avail) {
            text = clip(text, avail, valueFont);
        }
        textRight(text, x + width, y, valueColor, valueFont);
    }

    private Font fitMono(String text, int maxWidth, int maxSize, int minSize, boolean bold) {
        for (int size = maxSize; size > minSize; size--) {
            Font font = bold ? ScreenFonts.monoBold(size) : ScreenFonts.mono(size);
            if (this.g.getFontMetrics(font).stringWidth(text) <= maxWidth) {
                return font;
            }
        }
        return bold ? ScreenFonts.monoBold(minSize) : ScreenFonts.mono(minSize);
    }

    private Font fitCjk(String text, int maxWidth, int maxSize, int minSize) {
        for (int size = maxSize; size > minSize; size--) {
            Font font = ScreenFonts.cjk(size);
            if (this.g.getFontMetrics(font).stringWidth(text) <= maxWidth) {
                return font;
            }
        }
        return ScreenFonts.cjk(minSize);
    }

    private void text(String text, int x, int y, int argb, Font font) {
        this.g.setFont(font);
        this.g.setColor(new Color(argb, true));
        this.g.drawString(text, x, y);
    }

    private void textRight(String text, int rightX, int y, int argb, Font font) {
        this.g.setFont(font);
        int width = this.g.getFontMetrics(font).stringWidth(text);
        this.g.setColor(new Color(argb, true));
        this.g.drawString(text, rightX - width, y);
    }

    private void textCenter(String text, int baselineY, int argb, Font font) {
        textCenterIn(text, 0, BASE_W, baselineY, argb, font);
    }

    /** Centred on an arbitrary x, for labels that belong to the gauge. */
    private void textCenterAt(String text, int centerX, int baselineY, int argb, Font font) {
        this.g.setFont(font);
        int width = this.g.getFontMetrics(font).stringWidth(text);
        this.g.setColor(new Color(argb, true));
        this.g.drawString(text, centerX - width / 2, baselineY);
    }

    private void textCenterIn(String text, int x, int w, int baselineY, int argb, Font font) {
        this.g.setFont(font);
        int width = this.g.getFontMetrics(font).stringWidth(text);
        this.g.setColor(new Color(argb, true));
        this.g.drawString(text, x + w / 2 - width / 2, baselineY);
    }

    private int textWidth(String text, Font font) {
        return this.g.getFontMetrics(font).stringWidth(text);
    }

    private String clip(String text, int maxWidth, Font font) {
        FontMetrics metrics = this.g.getFontMetrics(font);
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "..";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + ellipsis) > maxWidth) {
            end--;
        }
        return text.substring(0, Math.max(0, end)) + ellipsis;
    }

    /**
     * Horizontal scanlines. One every three virtual pixels at 6% black: any
     * denser and the strokes of 9px Han glyphs get eaten and the panel reads as
     * blurry instead of retro.
     */
    private void drawScanlines() {
        this.g.setColor(new Color(0, 0, 0, 15));
        for (int y = 0; y < BASE_H; y += 3) {
            this.g.fillRect(0, y, BASE_W, 1);
        }
    }

    private static int levelColor(int level) {
        return level == 2 ? CRIT : (level == 1 ? WARN : OK);
    }

    /** Gauge colour: amber when the reading is healthy, so the scale stays amber. */
    private static int gaugeColor(int level) {
        return level == 2 ? CRIT : (level == 1 ? WARN : AMBER);
    }

    private static double ratio(double value, double max) {
        return max <= 0.0D ? 0.0D : Math.max(0.0D, Math.min(1.0D, value / max));
    }
}
