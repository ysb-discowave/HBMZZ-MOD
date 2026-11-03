import com.hbmzz.client.screen.CtrlCanvas;
import com.hbmzz.ctrl.CtrlPanel;
import com.hbmzz.ctrl.CtrlRule;
import com.hbmzz.ctrl.CtrlType;
import com.hbmzz.ctrl.CtrlWidget;

import javax.imageio.ImageIO;
import java.io.File;

/**
 * Renders the control panel window to PNG without Minecraft.
 *
 * <p>Same idea as PreviewMain for the monitor: run the real canvas, skip only
 * the texture upload. The window is the one thing in this mod that cannot be
 * verified by reading code, and it is also the thing that takes the longest to
 * check in game - so it is worth ten seconds of javac.</p>
 *
 * <pre>
 * java -Djava.awt.headless=true -cp "tmp/pc;build/classes/java/main" PreviewCtrl dist
 * </pre>
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
        panel.widgets.get(1).value = 1.0D;      // 在线 lamp on
        panel.widgets.get(2).value = 72.0D;     // 读数
        panel.widgets.get(3).value = 40.0D;     // 输出 knob
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
        System.out.println("written to " + dir.getAbsolutePath());
    }

    /** A panel that touches every corner of the layout. */
    private static CtrlPanel demo() {
        CtrlPanel panel = CtrlPanel.defaults();
        panel.name = "RBMK-4 主控";

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
        ImageIO.write(canvas.image(), "png", new File(dir, name));
    }
}
