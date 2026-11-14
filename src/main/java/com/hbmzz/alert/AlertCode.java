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

package com.hbmzz.alert;

/**
 * The facility status code.
 *
 * <p>Eight states, in the order a facility actually climbs them: nothing wrong,
 * something small, people rioting, people dead, the core melted, the core blew
 * up, a warhead went off, everything is gone. Order matters twice over - it is
 * the ladder the displays draw and it is the "which one is active" comparison
 * when a new code arrives.</p>
 *
 * <p>The ladder is deliberately not sorted by how bad the thing sounds. ALPHA
 * (a warhead) sits <em>above</em> OMEGA (a core explosion) because a warhead
 * takes the surrounding map with it, and DELTA exists because "the facility is
 * gone" is a different answer from "the facility is on fire".</p>
 *
 * <p>Text lives here rather than in the language files on purpose: the same
 * string is painted on a block face by the AWT canvas, where there is no
 * {@code I18n} - a language-file lookup would need a translation pass that the
 * canvas does not have. The bilingual fields are what the display shows side by
 * side; the chat broadcast uses them too, so a server and its screens can never
 * disagree about what RED means.</p>
 */
public enum AlertCode {

    GREEN(0x00, "GREEN", 0x6BF29A,
            "正常运行", "Normal operation",
            "全部系统在线，无异常读数。", "All systems nominal."),

    YELLOW(0x01, "YELLOW", 0xFFE066,
            "发生小插曲", "Minor incident",
            "局部异常，不影响主线运行。", "Local anomaly; operations continue."),

    ORANGE(0x02, "ORANGE", 0xFFA53C,
            "小型暴乱", "Small riot",
            "局部秩序失控，需要安保介入。", "Local order lost; security response required."),

    RED(0x03, "RED", 0xFF5A5A,
            "重大事故", "Major accident",
            "关键设备损毁或出现人员伤亡。", "Critical equipment lost, or casualties."),

    MED(0x04, "MED", 0xFF4F8C,
            "堆芯融毁事故", "Core meltdown",
            "反应堆堆芯熔毁，辐射外泄。", "Reactor core melted down; radiation release."),

    OMEGA(0x05, "OMEGA", 0xFF3A2E,
            "堆芯爆炸事故", "Core explosion",
            "堆芯发生爆炸，核心区报废。", "Core detonated; the core zone is lost."),

    ALPHA(0x06, "ALPHA", 0xFF2D6B,
            "核弹事件", "Nuclear event",
            "核装置被引爆，冲击波及地表。", "A nuclear device was detonated."),

    DELTA(0x07, "DELTA", 0xC05BFF,
            "设施毁灭级事故", "Facility annihilation",
            "整个设施不可恢复，立即全员撤离。", "Total loss; evacuate immediately.");

    private final int id;
    private final String label;
    private final int rgb;
    private final String zhTitle;
    private final String enTitle;
    private final String zhDetail;
    private final String enDetail;

    AlertCode(int id, String label, int rgb, String zhTitle, String enTitle,
              String zhDetail, String enDetail) {
        this.id = id;
        this.label = label;
        this.rgb = rgb;
        this.zhTitle = zhTitle;
        this.enTitle = enTitle;
        this.zhDetail = zhDetail;
        this.enDetail = enDetail;
    }

    public int id() {
        return this.id;
    }

    /** The word a display prints: {@code GREEN} ... {@code DELTA}. */
    public String label() {
        return this.label;
    }

    /** Packed 0xRRGGBB, no alpha. */
    public int rgb() {
        return this.rgb;
    }

    /** Fully opaque ARGB, for AWT. */
    public int argb() {
        return 0xFF000000 | this.rgb;
    }

    public String zhTitle() {
        return this.zhTitle;
    }

    public String enTitle() {
        return this.enTitle;
    }

    public String zhDetail() {
        return this.zhDetail;
    }

    public String enDetail() {
        return this.enDetail;
    }

    /** Position on the escalation ladder; higher wins. */
    public int severity() {
        return this.id;
    }

    /** True for the codes that get a klaxon and a flashing frame. */
    public boolean isAlarm() {
        return this.id >= RED.id;
    }

    /** True for the two codes that mean "the map has changed shape". */
    public boolean isCatastrophic() {
        return this == ALPHA || this == DELTA || this == OMEGA;
    }

    public AlertCode next() {
        AlertCode[] all = values();
        return all[(this.id + 1) % all.length];
    }

    public AlertCode previous() {
        AlertCode[] all = values();
        return all[(this.id + all.length - 1) % all.length];
    }

    public static AlertCode byId(int id) {
        for (AlertCode code : values()) {
            if (code.id == id) {
                return code;
            }
        }
        return GREEN;
    }

    public static AlertCode byLabel(String label) {
        if (label != null) {
            for (AlertCode code : values()) {
                if (code.label.equalsIgnoreCase(label)) {
                    return code;
                }
            }
        }
        return GREEN;
    }

    public static int count() {
        return values().length;
    }
}
