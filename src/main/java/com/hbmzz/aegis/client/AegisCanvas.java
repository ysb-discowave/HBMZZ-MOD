/*
 * HBMZZ - Aegis DFC telemetry terminal.
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
 */

package com.hbmzz.aegis.client;

import com.hbmzz.aegis.AegisTelemetry;

import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Paints the terminal face into an offscreen bitmap. The layout is designed at
 * a fixed logical resolution and supersampled for legibility at close range.
 *
 * <p>Three pages share one header and one status rail:
 * <ol>
 *   <li><b>Overview</b> — the heat gauge, stability and energy at a glance.</li>
 *   <li><b>Components</b> — counts of every assembled part plus tank levels.</li>
 *   <li><b>Diagnostics</b> — catalyst wear, power draw and signal integrity.</li>
 * </ol>
 * A boot sweep and a "signal lost" state sit in front of the normal pages so a
 * freshly placed or disconnected terminal never shows stale numbers.</p>
 */
public final class AegisCanvas {

    public static final int PAGE_COUNT = com.hbmzz.aegis.AegisTelemetry.PAGE_COUNT;
    public static final int LOGICAL_W = 384;
    public static final int LOGICAL_H = 256;
    private static final int SS = 2;

    private final BufferedImage image;
    private boolean dirty = true;
    private int shownPage;

    public AegisCanvas() {
        this.image = new BufferedImage(LOGICAL_W * SS, LOGICAL_H * SS, BufferedImage.TYPE_INT_ARGB);
    }

    public BufferedImage image() {
        return this.image;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }

    /** Renders one frame. Content is driven purely by {@code frame} plus the clock. */
    public void render(AegisTelemetry frame, int page, long nowMs, long bootAt, boolean powered) {
        if (frame == null) {
            frame = new AegisTelemetry();
        }
        this.shownPage = Math.max(0, Math.min(PAGE_COUNT - 1, page));

        BufferedImage img = this.image;
        AegisDraw d = new AegisDraw(img, LOGICAL_W, LOGICAL_H);
        d.graphics().scale(SS, SS);
        try {
            // base plate
            d.rect(0, 0, LOGICAL_W, LOGICAL_H, AegisPalette.CANVAS);
            d.rect(0, 0, LOGICAL_W, 30, AegisPalette.CANVAS_ALT);
            d.line(0, 30, LOGICAL_W, 30, AegisPalette.FRAME, 1.5F);

            if (!powered) {
                drawStandby(d);
            } else if (nowMs - bootAt < 1100L) {
                drawBoot(d, nowMs - bootAt);
            } else if (frame.worldTick == 0L || nowMs - lastFrameAt(frame) > 5000L) {
                drawNoSignal(d, frame, nowMs);
            } else {
                drawHeader(frame, d, nowMs);
                if (this.shownPage == 0) {
                    drawOverview(frame, d, nowMs);
                } else if (this.shownPage == 1) {
                    drawComponents(frame, d);
                } else {
                    drawDiagnostics(frame, d, nowMs);
                }
                drawPageRail(frame, d, nowMs);
            }

            d.frame(1, 1, LOGICAL_W - 2, LOGICAL_H - 2, AegisPalette.FRAME, 2.0F);
            d.scanlines(0x08FFFFFF, 4);
        } finally {
            d.dispose();
        }
        this.dirty = false;
    }

    // A frame carries world ticks, not wall-clock stamps. The renderer keeps the
    // arrival time on the telemetry object's worldTick companion via a small
    // side table here so "signal lost" reflects a genuinely quiet feed.
    private long lastArrival;

    private long lastFrameAt(AegisTelemetry frame) {
        if (frame.worldTick != this.lastTick) {
            this.lastTick = frame.worldTick;
            this.lastArrival = System.currentTimeMillis();
        }
        return this.lastArrival;
    }

    private long lastTick = Long.MIN_VALUE;

    // --------------------------------------------------------------- chrome

    private void drawHeader(AegisTelemetry frame, AegisDraw d, long nowMs) {
        d.rect(0, 0, 6, 30, AegisPalette.CYAN_DARK);
        d.text("神盾", 14, 21, 17, AegisPalette.CYAN, true);
        d.text("// 聚变遥测", 74, 21, 10, AegisPalette.TEXT_DIM, false);

        String tag = com.hbmzz.aegis.AegisConfig.showDiagnostics ? "监控" : "精简";
        d.textRight(tag, LOGICAL_W - 12, 21, 9, AegisPalette.TEXT_FAINT, false);

        int dot = AegisPalette.GREEN;
        if (frame.tamperState > 0) {
            dot = AegisPalette.RED;
        } else if (!frame.loaded) {
            dot = AegisPalette.AMBER;
        }
        d.circle(LOGICAL_W - 92, 15, 3, dot, true);
        d.textRight("刻" + frame.worldTick, LOGICAL_W - 104, 20, 9, AegisPalette.TEXT_DIM, false);
    }

    private void drawPageRail(AegisTelemetry frame, AegisDraw d, long nowMs) {
        int y = LOGICAL_H - 20;
        d.rect(0, y - 4, LOGICAL_W, 24, AegisPalette.CANVAS_ALT);
        d.line(0, y - 4, LOGICAL_W, y - 4, AegisPalette.FRAME, 1.0F);
        String[] labels = {"01 总览", "02 部件", "03 诊断"};
        int x = 12;
        for (int i = 0; i < PAGE_COUNT; i++) {
            if (i == 2 && !com.hbmzz.aegis.AegisConfig.showDiagnostics) {
                break;
            }
            int colour = i == this.shownPage ? AegisPalette.CYAN : AegisPalette.TEXT_FAINT;
            d.text(labels[i], x, y + 12, 10, colour, i == this.shownPage);
            if (i == this.shownPage) {
                d.rect(x, y + 15, Math.min(96, d.measure(labels[i], 10, true)), 2, AegisPalette.CYAN);
            }
            x += 118;
        }
        if (frame.collapsing || frame.forecastKind == 1) {
            int blink = (int) (nowMs / 300L) % 2 == 0 ? AegisPalette.RED : AegisPalette.TEXT_FAINT;
            d.textRight("!! 紧急停堆", LOGICAL_W - 12, y + 12, 10, blink, true);
        }
    }

    // --------------------------------------------------------------- pages

    private void drawOverview(AegisTelemetry f, AegisDraw d, long nowMs) {
        // Big heat readout on the left.
        d.text("堆芯温度", 14, 52, 10, AegisPalette.TEXT_DIM, false);
        double fraction = f.heatFraction();
        int heatColour = AegisPalette.heat(fraction);
        String degrees = String.format(Locale.ROOT, "%,.0f", f.heatC);
        d.text(degrees, 14, 108, 46, heatColour, true);
        int degW = d.measure(degrees, 46, true);
        d.text("\u00b0C", 20 + degW, 94, 18, AegisPalette.TEXT_DIM, false);

        float slope = f.heatSlope;
        String trend = slope > 0.05F ? "\u25b2 " + String.format(Locale.ROOT, "%.2f", slope)
                : slope < -0.05F ? "\u25bc " + String.format(Locale.ROOT, "%.2f", Math.abs(slope))
                : "\u25ac 稳定";
        int trendColour = slope > 0.05F ? AegisPalette.RED : slope < -0.05F ? AegisPalette.GREEN : AegisPalette.TEXT_DIM;
        d.text(trend, 16, 126, 11, trendColour, false);
        d.text("熔点 " + String.format(Locale.ROOT, "%,.0f", f.meltC) + " \u00b0C",
                14, 144, 9, AegisPalette.TEXT_FAINT, false);

        // Heat bar with melt marker.
        d.text("温度 / 上限", 14, 166, 9, AegisPalette.TEXT_DIM, false);
        d.bar(14, 172, 176, 12, fraction, heatColour, 1.0D, AegisPalette.RED);
        d.ticks(14, 186, 176, 8, AegisPalette.TEXT_FAINT);

        // Stability.
        d.text("稳定度", 14, 206, 9, AegisPalette.TEXT_DIM, false);
        d.text(String.format(Locale.ROOT, "%.0f%%", f.stability * 100.0F), 190, 207, 11,
                stabilityColour(f.stability), true);
        d.bar(14, 212, 176, 10, f.stability, stabilityColour(f.stability), -1.0D, 0);

        // Right column: core identity + energy + sparkline.
        int rx = 208;
        d.frame(rx, 44, 162, 62, AegisPalette.FRAME_SOFT, 1.0F);
        d.text("堆芯", rx + 10, 60, 9, AegisPalette.TEXT_FAINT, false);
        d.text(d.fit(f.coreTag, 16, true, 142), rx + 10, 82, 16, AegisPalette.TEXT, true);
        d.text(f.loaded ? "已装料" : (f.linked ? "无堆芯" : "未连接"), rx + 10, 99, 9,
                f.loaded ? AegisPalette.GREEN : AegisPalette.AMBER, false);

        d.text("能量", rx, 124, 9, AegisPalette.TEXT_DIM, false);
        d.text(formatEnergy(f.coreEnergy), rx, 142, 13, AegisPalette.CYAN, true);
        d.text("输出 " + formatEnergy(f.exhaustEnergy), rx, 158, 9, AegisPalette.TEXT_DIM, false);
        d.text("增益 " + formatEnergy(f.gainEnergy), rx + 84, 158, 9,
                f.gainEnergy >= 0.0D ? AegisPalette.GREEN : AegisPalette.RED, false);

        d.text("温度曲线", rx, 180, 9, AegisPalette.TEXT_DIM, false);
        d.frame(rx, 186, 162, 34, AegisPalette.FRAME_SOFT, 1.0F);
        float[] normalised = new float[f.trendHeat.length];
        double span = Math.max(1.0D, f.meltC);
        for (int i = 0; i < f.trendHeat.length; i++) {
            normalised[i] = (float) Math.max(0.0D, Math.min(1.0D, f.trendHeat[i] / span));
        }
        d.spark(normalised, f.trendCount, rx + 2, 188, 158, 30, AegisPalette.CYAN);

        if (f.collapsing || f.forecastKind == 1) {
            drawCountdownBanner(d, f, nowMs);
        }
    }

    private void drawComponents(AegisTelemetry f, AegisDraw d) {
        d.text("组件装配", 14, 52, 11, AegisPalette.TEXT_DIM, true);
        String[][] cells = {
                {"稳定器", String.valueOf(f.nStabiliser)},
                {"吸收器", String.valueOf(f.nAbsorber)},
                {"脉冲器", String.valueOf(f.nPulsar)},
                {"发射器", String.valueOf(f.nEmitter)},
                {"注入器", String.valueOf(f.nInjector)},
                {"交换器", String.valueOf(f.nExchanger)},
                {"控制极", String.valueOf(f.nCEmitter)},
        };
        int cols = 4;
        int cw = 88;
        int ch = 44;
        int gap = 6;
        for (int i = 0; i < cells.length; i++) {
            int col = i % cols;
            int row = i / cols;
            int x = 14 + col * (cw + gap);
            int y = 62 + row * (ch + gap);
            d.rect(x, y, cw, ch, AegisPalette.CANVAS_ALT);
            d.frame(x, y, cw, ch, AegisPalette.FRAME_SOFT, 1.0F);
            d.text(cells[i][0], x + 6, y + 14, 8, AegisPalette.TEXT_DIM, false);
            boolean zero = "0".equals(cells[i][1]);
            d.text(cells[i][1], x + 6, y + 35, 18, zero ? AegisPalette.TEXT_FAINT : AegisPalette.CYAN, true);
        }

        int by = 164;
        d.text("流体罐", 14, by, 10, AegisPalette.TEXT_DIM, true);
        drawTank(d, 14, by + 8, 176, "冷却剂", f.coolantTag, f.coolantFill, f.coolantCap, AegisPalette.CYAN);
        drawTank(d, 14, by + 52, 176, "慢化剂", f.moderatorTag, f.moderatorFill, f.moderatorCap, AegisPalette.VIOLET);
    }

    private void drawDiagnostics(AegisTelemetry f, AegisDraw d, long nowMs) {
        d.text("催化剂磨损", 14, 52, 11, AegisPalette.TEXT_DIM, true);
        double wear = f.worstWear();
        int wearColour = wear > 85.0D ? AegisPalette.RED : wear > 60.0D ? AegisPalette.AMBER : AegisPalette.GREEN;
        drawWear(d, 14, 62, 176, "槽 A", f.wearSlotA);
        drawWear(d, 14, 96, 176, "槽 B", f.wearSlotB);

        int rx = 208;
        d.text("功率计", rx, 52, 11, AegisPalette.TEXT_DIM, true);
        d.rect(rx, 60, 162, 80, AegisPalette.CANVAS_ALT);
        d.frame(rx, 60, 162, 80, AegisPalette.FRAME_SOFT, 1.0F);
        if (f.metered) {
            d.text("每刻增量", rx + 10, 78, 9, AegisPalette.TEXT_FAINT, false);
            d.text(formatPower(f.powerPerTick), rx + 10, 98, 16, AegisPalette.CYAN, true);
            d.text("上一秒", rx + 10, 118, 9, AegisPalette.TEXT_FAINT, false);
            d.text(formatPower(f.powerPerSecond), rx + 10, 134, 13, AegisPalette.TEXT, true);
        } else {
            d.textCenter("无电表", rx + 81, 104, 12, AegisPalette.TEXT_FAINT, true);
        }

        d.text("信号完整性", 14, 156, 11, AegisPalette.TEXT_DIM, true);
        String[] states = {"正常", "链路中断", "降级", "信号切断"};
        int state = Math.max(0, Math.min(3, f.tamperState));
        int stateColour = state == 0 ? AegisPalette.GREEN : state == 1 ? AegisPalette.AMBER : AegisPalette.RED;
        d.rect(14, 166, 356, 34, AegisPalette.CANVAS_ALT);
        d.frame(14, 166, 356, 34, stateColour, 1.0F);
        d.text(states[state], 24, 188, 16, stateColour, true);
        if (f.tamperTicks > 0) {
            d.textRight("T-" + f.tamperTicks + "刻", 360, 187, 11, AegisPalette.TEXT_DIM, false);
        }

        d.text("预测", rx, 156, 11, AegisPalette.TEXT_DIM, true);
        String forecast = f.forecastSeconds >= 0
                ? String.format(Locale.ROOT, "T-%02d:%02d", f.forecastSeconds / 60, f.forecastSeconds % 60)
                : "稳定";
        d.text(forecast, rx, 178, 14, f.forecastKind > 0 ? AegisPalette.RED : AegisPalette.GREEN, true);
        d.text(wear > 85.0D ? "催化剂临界" : "正常范围", rx, 196, 9, wearColour, false);
    }

    // --------------------------------------------------------------- widgets

    private void drawTank(AegisDraw d, int x, int y, int w, String label, String fluid,
                          double fill, double cap, int colour) {
        d.text(label, x, y + 10, 9, AegisPalette.TEXT_DIM, false);
        d.textRight(d.fit(fluid, 9, false, 90), x + w, y + 10, 9, AegisPalette.TEXT, false);
        double fraction = cap > 0.0D ? fill / cap : 0.0D;
        d.bar(x, y + 14, w, 10, fraction, colour, -1.0D, 0);
        d.textRight(String.format(Locale.ROOT, "%.0f%%", fraction * 100.0D), x + w, y + 34, 9,
                AegisPalette.TEXT_DIM, false);
    }

    private void drawWear(AegisDraw d, int x, int y, int w, String label, double value) {
        int colour = value > 85.0D ? AegisPalette.RED : value > 60.0D ? AegisPalette.AMBER : AegisPalette.GREEN;
        d.text(label, x, y + 10, 9, AegisPalette.TEXT_DIM, false);
        d.textRight(String.format(Locale.ROOT, "%.1f%%", value), x + w, y + 10, 9, colour, true);
        d.bar(x, y + 14, w, 12, value / 100.0D, colour, -1.0D, 0);
    }

    private void drawCountdownBanner(AegisDraw d, AegisTelemetry f, long nowMs) {
        int y = 216;
        boolean bright = (nowMs / 250L) % 2L == 0L;
        d.rect(8, y, LOGICAL_W - 16, 26, bright ? 0xFF5A1414 : 0xFF3A0E0E);
        d.frame(8, y, LOGICAL_W - 16, 26, AegisPalette.RED, 1.5F);
        String text = f.collapsing ? "堆芯坍缩 / 立即撤离"
                : String.format(Locale.ROOT, "爆炸倒计时 %02d:%02d",
                        Math.max(0, f.forecastSeconds / 60), Math.max(0, f.forecastSeconds % 60));
        d.textCenter(text, LOGICAL_W / 2, y + 18, 13, AegisPalette.RED, true);
    }

    // --------------------------------------------------------------- states

    private void drawStandby(AegisDraw d) {
        d.textCenter("待机", LOGICAL_W / 2, LOGICAL_H / 2, 26, AegisPalette.TEXT_FAINT, true);
        d.textCenter("神盾终端离线", LOGICAL_W / 2, LOGICAL_H / 2 + 20, 10,
                AegisPalette.TEXT_FAINT, false);
    }

    private void drawBoot(AegisDraw d, long elapsed) {
        d.textCenter("神盾", LOGICAL_W / 2, 96, 34, AegisPalette.CYAN, true);
        d.textCenter("聚变遥测终端", LOGICAL_W / 2, 116, 11, AegisPalette.TEXT_DIM, false);
        int barW = 220;
        int x = (LOGICAL_W - barW) / 2;
        double progress = Math.min(1.0D, elapsed / 1000.0D);
        d.bar(x, 140, barW, 10, progress, AegisPalette.CYAN, -1.0D, 0);
        d.textCenter("初始化中", LOGICAL_W / 2, 170, 9, AegisPalette.TEXT_FAINT, false);
    }

    private void drawNoSignal(AegisDraw d, AegisTelemetry f, long nowMs) {
        d.textCenter("无信号", LOGICAL_W / 2, 112, 30, AegisPalette.AMBER, true);
        d.textCenter("等待遥测数据", LOGICAL_W / 2, 136, 10, AegisPalette.TEXT_DIM, false);
        int sweep = (int) ((nowMs / 12L) % (LOGICAL_W + 40)) - 20;
        d.line(sweep, 150, sweep + 30, 150, AegisPalette.CYAN_DARK, 2.0F);
    }

    // --------------------------------------------------------------- format

    private static String formatEnergy(double value) {
        if (value >= 1.0E9D) {
            return String.format(Locale.ROOT, "%.2fG", value / 1.0E9D);
        }
        if (value >= 1.0E6D) {
            return String.format(Locale.ROOT, "%.2fM", value / 1.0E6D);
        }
        if (value >= 1.0E3D) {
            return String.format(Locale.ROOT, "%.1fk", value / 1.0E3D);
        }
        return String.format(Locale.ROOT, "%.0f", value);
    }

    private static String formatPower(long value) {
        long v = Math.abs(value);
        String sign = value < 0 ? "-" : "+";
        if (v >= 1_000_000L) {
            return sign + String.format(Locale.ROOT, "%.2fM", v / 1_000_000.0D);
        }
        if (v >= 1000L) {
            return sign + String.format(Locale.ROOT, "%.1fk", v / 1000.0D);
        }
        return sign + v;
    }

    private static int stabilityColour(float s) {
        if (s < 0.25F) {
            return AegisPalette.RED;
        }
        if (s < 0.5F) {
            return AegisPalette.AMBER;
        }
        return AegisPalette.GREEN;
    }
}
