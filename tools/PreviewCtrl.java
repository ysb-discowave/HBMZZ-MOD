import com.hbmzz.client.screen.CtrlCanvas;
import com.hbmzz.client.screen.TermCanvas;
import com.hbmzz.ctrl.CtrlPanel;
import com.hbmzz.ctrl.CtrlRule;
import com.hbmzz.ctrl.CtrlType;
import com.hbmzz.ctrl.CtrlWidget;
import com.hbmzz.ctrl.TerminalShell;

import javax.imageio.ImageIO;
import java.io.File;

/**
 * Renders the control panel window and the terminal window to PNG without
 * Minecraft.
 *
 * <p>Same idea as PreviewMain for the monitor: run the real canvas, skip only
 * the texture upload. The windows are the one thing in this mod that cannot be
 * verified by reading code, and they are also the thing that takes the longest
 * to check in game - so it is worth ten seconds of javac.</p>
 *
 * <p>The terminal joined this tool in 2.4.0 for the same reason the panel did:
 * font size, band heights and the completion list sitting over the scrollback
 * are all judgement calls that a screenshot settles and a diff does not.</p>
 *
 * <pre>
 * javac -encoding UTF-8 -d tmp/pc -cp build/classes/java/main \
 *       tools/PreviewCtrl.java $(find tools/linkercheck/stubs -name '*.java')
 * java -Djava.awt.headless=true -cp "tmp/pc;build/classes/java/main" PreviewCtrl dist
 * </pre>
 *
 * <p>The stand-ins are not optional: {@code CtrlPanel.defaults()} touches NBT,
 * and without them the very first panel blows up before anything is drawn.</p>
 */
public final class PreviewCtrl {

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "dist");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("cannot create " + dir);
        }

        CtrlPanel panel = demo();
        CtrlCanvas canvas = new CtrlCanvas();

        // 运行: the panel as an operator meets it, with a few live numbers.
        // By type, not by index: the demo panel grows, and an index-based
        // preview silently starts colouring the wrong control.
        set(panel, CtrlType.LAMP, 1.0D);        // 在线 lamp on
        set(panel, CtrlType.BAR, 72.0D);        // 读数
        set(panel, CtrlType.SLIDER, 40.0D);     // 输出
        canvas.render(panel, 0, -1, -1, -1, 120, 150, "", true, "", "");
        save(canvas, dir, "ctrl-run.png");

        // 控件: palette plus the inspector for a selected control.
        canvas.render(panel, 1, 3, -1, -1, 470, 200, "已添加 旋钮", true, "", "");
        save(canvas, dir, "ctrl-widget.png");

        // 控件 with a field being typed into, to check the caret.
        canvas.render(panel, 1, 3, -1, -1, 470, 200, "", true, "field:label", "输出功率");
        save(canvas, dir, "ctrl-edit.png");

        // 联动: one rule, selected.
        canvas.render(panel, 2, 3, 0, -1, 470, 200, "拖满两条就能跑", true, "", "");
        save(canvas, dir, "ctrl-rule.png");

        canvas.dispose();

        term(dir);
        System.out.println("written to " + dir.getAbsolutePath());
    }

    /**
     * The terminal window, in the three states worth looking at.
     *
     * <p>Chosen because each one breaks in a different way: the plain state
     * tests whether 9 px text is legible at all, the completion state tests
     * whether the list covers the thing it is completing, and the long-output
     * state tests whether the scrollback clips or overflows its well.</p>
     */
    private static void term(File dir) throws Exception {
        TermCanvas canvas = new TermCanvas();

        // 1. Just opened, target already picked by the engine, a couple of reads.
        TermCanvas.View open = base();
        open.lines.add(line("> ls", TerminalShell.ECHO));
        open.lines.add(line("  A  RBMK 控制棒组", TerminalShell.OUT));
        open.lines.add(line("* B  RBMK 蒸汽发生器", TerminalShell.OK));
        open.lines.add(line("  C  涡轮机 T-1", TerminalShell.OUT));
        open.lines.add(line("  D  冷却泵 P-3", TerminalShell.OUT));
        open.lines.add(line("  E  —", TerminalShell.HINT));
        open.lines.add(line("  F  —", TerminalShell.HINT));
        open.lines.add(line("> read heat", TerminalShell.ECHO));
        open.lines.add(line("heat = 812", TerminalShell.OUT));
        open.input = "";
        canvas.markDirty();
        canvas.render(open, -1, -1);
        save(canvas, dir, "term-open.png");

        // 2. Completion open mid-word: does the list hide the prompt it serves?
        TermCanvas.View suggest = base();
        suggest.lines.add(line("> use B", TerminalShell.ECHO));
        suggest.lines.add(line("目标 → B  RBMK 蒸汽发生器", TerminalShell.OK));
        suggest.lines.add(line("> ev rbmk_ctrl", TerminalShell.ECHO));
        suggest.input = "ev rbmk_ctrl";
        suggest.caret = suggest.input.length();
        suggest.suggest.add("rbmk_ctrl_set_level");
        suggest.suggest.add("rbmk_ctrl_set_speed");
        suggest.suggest.add("rbmk_ctrl_set_direction");
        suggest.suggest.add("rbmk_ctrl_set_rod");
        suggest.suggest.add("rbmk_ctrl_set_auto");
        suggest.suggest.add("rbmk_ctrl_set_channel");
        suggest.suggest.add("rbmk_ctrl_set_group");
        suggest.suggest.add("rbmk_ctrl_sync");
        suggest.suggestSel = 1;
        suggest.status = "Tab 补全 · 8 个候选";
        canvas.markDirty();
        canvas.render(suggest, -1, -1);
        save(canvas, dir, "term-suggest.png");

        // 3. A screenful: help output plus an error mid-way.
        TermCanvas.View help = base();
        help.lines.add(line("> help", TerminalShell.ECHO));
        help.lines.add(line("help [命令] — 命令列表，或某条命令的用法", TerminalShell.OUT));
        help.lines.add(line("ls — 列出 A–F 上绑定的机器，* 是当前目标", TerminalShell.OUT));
        help.lines.add(line("use <A–F> — 切换当前目标机器", TerminalShell.OUT));
        help.lines.add(line("read [键] — 读当前机器的全部数据，或某一个键", TerminalShell.OUT));
        help.lines.add(line("keys — 只列可读的数据键名", TerminalShell.OUT));
        help.lines.add(line("events — 列出当前机器接受的事件及其变量", TerminalShell.OUT));
        help.lines.add(line("ev <事件> [变量=值]… — 发一个事件", TerminalShell.OUT));
        help.lines.add(line("set <变量> <值> — 按变量名发事件", TerminalShell.OUT));
        help.lines.add(line("clear — 清屏（历史仍可用 ↑ 取回）", TerminalShell.OUT));
        help.lines.add(line("> set level 120", TerminalShell.ECHO));
        help.lines.add(line("值超出范围，level 只能是 0–100。", TerminalShell.ERR));
        help.lines.add(line("> set level 80", TerminalShell.ECHO));
        help.lines.add(line("已发送 rbmk_ctrl_set_level level=80", TerminalShell.OK));
        help.scroll = 3;
        help.input = "re";
        help.caret = 2;
        help.status = "第 4 页 · 只读";
        canvas.markDirty();
        canvas.render(help, -1, -1);
        save(canvas, dir, "term-help.png");

        canvas.dispose();
    }

    private static TermCanvas.View base() {
        TermCanvas.View view = new TermCanvas.View();
        view.panelName = "RBMK-4 主控";
        view.subtitle = "B · RBMK 蒸汽发生器";
        view.slots[0] = "RBMK 控制棒组";
        view.slots[1] = "RBMK 蒸汽发生器";
        view.slots[2] = "涡轮机 T-1";
        view.slots[3] = "冷却泵 P-3";
        view.selected = 1;
        return view;
    }

    private static TermCanvas.Line line(String text, int kind) {
        return new TermCanvas.Line(text, kind);
    }

    /** First widget of a type, given a value. No-op if the demo never made one. */
    private static void set(CtrlPanel panel, CtrlType type, double value) {
        for (CtrlWidget widget : panel.widgets) {
            if (widget.type == type) {
                widget.value = value;
                return;
            }
        }
    }

    /** A panel that touches every corner of the layout. */
    private static CtrlPanel demo() {
        CtrlPanel panel = CtrlPanel.defaults();
        panel.name = "RBMK-4 主控";

        // The terminal sits among the readouts on purpose: it has to look like
        // one more instrument on the panel, not a separate window bolted on.
        CtrlWidget term = panel.add(CtrlType.TERMINAL);
        if (term != null) {
            term.gx = 2;
            term.gy = 2;
            term.label = "终端";
            term.link = 1;
        }
        CtrlWidget lamp = panel.add(CtrlType.LAMP);

        if (lamp != null) {
            lamp.gx = 12;
            lamp.gy = 3;
            lamp.label = "蒸汽";
            lamp.value = 1.0D;
        }
        CtrlWidget bar = panel.add(CtrlType.BAR);
        if (bar != null) {
            bar.gx = 1;
            bar.gy = 11;
            bar.gw = 10;
            bar.label = "水位";
            bar.value = 64.0D;
        }
        CtrlWidget lamp2 = panel.add(CtrlType.LAMP_RGB);
        if (lamp2 != null) {
            lamp2.gx = 15;
            lamp2.gy = 3;
            lamp2.label = "温度";
            lamp2.value = 78.0D;
        }
        CtrlWidget seg = panel.add(CtrlType.SEG7);
        if (seg != null) {
            seg.gx = 12;
            seg.gy = 7;
            seg.gw = 5;
            seg.label = "棒位";
            seg.value = 42.0D;
            seg.decimals = 0;
        }
        CtrlWidget dial = panel.add(CtrlType.DIAL);
        if (dial != null) {
            dial.gx = 5;
            dial.gy = 12;
            dial.label = "压力";
            dial.value = 30.0D;
        }
        CtrlWidget estop = panel.add(CtrlType.ESTOP);
        if (estop != null) {
            estop.gx = 20;
            estop.gy = 6;
            estop.label = "急停";
        }
        CtrlWidget sw = panel.add(CtrlType.SWITCH);
        if (sw != null) {
            sw.gx = 24;
            sw.gy = 3;
            sw.label = "泵";
            sw.value = 1.0D;
        }
        CtrlWidget rotary = panel.add(CtrlType.ROTARY);
        if (rotary != null) {
            rotary.gx = 20;
            rotary.gy = 10;
            rotary.label = "模式";
            rotary.steps = 4;
            rotary.value = 66.0D;
        }
        CtrlWidget slider = panel.add(CtrlType.SLIDER);
        if (slider != null) {
            slider.gx = 28;
            slider.gy = 6;
            slider.label = "流量";
            slider.value = 55.0D;
        }
        CtrlWidget text = panel.add(CtrlType.TEXT);
        if (text != null) {
            text.gx = 12;
            text.gy = 13;
            text.gw = 10;
            text.label = "状态";
            text.text = "冷却回路正常";
        }
        CtrlWidget button = panel.add(CtrlType.BUTTON);
        if (button != null) {
            button.gx = 24;
            button.gy = 13;
            button.label = "确认";
        }

        CtrlRule rule = panel.addRule();
        if (rule != null) {
            rule.srcLink = 0;
            rule.srcKey = "heat";
            rule.op = 0;
            rule.threshold = 900.0D;
            rule.dstLink = 1;
            rule.dstEvent = "rbmk_ctrl_set_level";
            rule.dstVar = "level";
            rule.value = 0.0D;
            rule.cooldown = 5;
        }
        return panel;
    }

    private static void save(CtrlCanvas canvas, File dir, String name) throws Exception {
        save(canvas.image(), dir, name);
    }

    private static void save(TermCanvas canvas, File dir, String name) throws Exception {
        save(canvas.image(), dir, name);
    }

    private static void save(java.awt.image.BufferedImage image, File dir, String name) throws Exception {
        if (image == null) {
            throw new IllegalStateException("nothing painted for " + name);
        }
        ImageIO.write(image, "png", new File(dir, name));
    }
}
