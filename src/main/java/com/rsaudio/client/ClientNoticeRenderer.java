package com.rsaudio.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 右下角弹窗的绘制。
 *
 * <h3>为什么挂在 {@code RenderGameOverlayEvent.Post} 上</h3>
 * 这是原版画完整个 HUD（血条、快捷栏、聊天栏）之后的那一瞬间，
 * 坐标系已经是"缩放后的 GUI 像素"，直接 {@code Gui.drawRect} 就是屏幕坐标。
 * 挂在 {@code ElementType.ALL} 上而不是 {@code ALL} 之外的类型，
 * 是因为我们不想参与原版那套"按元素类型分阶段"的顺序 —— 我们只要最后压在最上面。
 *
 * <h3>折行结果为什么要缓存</h3>
 * {@code listFormattedStringToWidth} 每帧对 200 多字重新跑一遍折行，
 * 在 240fps 下是白烧 CPU。弹窗内容由 {@code ClientNotice} 的 serial 标识，
 * serial 没变就复用上一次的结果。顺带把它做成 GUI 预览也能用的静态方法。
 */
public class ClientNoticeRenderer {

    /** 弹窗正文可用宽度（缩放后像素） */
    public static final int INNER_WIDTH = 148;
    /** 距屏幕右边缘 / 下边缘的留白 */
    private static final int MARGIN = 6;
    private static final int PAD = 4;
    /** 左侧强调条宽度 */
    private static final int ACCENT_WIDTH = 3;
    private static final int MAX_TITLE_LINES = 2;
    private static final int MAX_BODY_LINES = 12;
    /** 强调条颜色（青蓝，和模组整体的金属蓝灰调子一致） */
    private static final int ACCENT_COLOR = 0x3AA0FF;

    private int cachedSerial = -1;
    private FontRenderer cachedFont;
    private List<String> titleLines = Collections.emptyList();
    private List<String> bodyLines = Collections.emptyList();

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        // hideGUI = F1 隐藏 HUD，这时候弹窗也该跟着消失
        if (mc.gameSettings.hideGUI || mc.player == null || !ClientNotice.isActive()) {
            return;
        }
        render(mc, event.getResolution());
    }

    private void render(Minecraft mc, ScaledResolution resolution) {
        FontRenderer font = mc.fontRenderer;
        refresh(font);
        if (titleLines.isEmpty() && bodyLines.isEmpty()) {
            return;
        }

        int a = (int) (ClientNotice.alpha() * 255.0F);
        if (a <= 4) {
            return;
        }

        int lineHeight = font.FONT_HEIGHT + 1;
        boolean hasTitle = !titleLines.isEmpty();
        boolean hasBody = !bodyLines.isEmpty();
        int textHeight = titleLines.size() * lineHeight
                + bodyLines.size() * lineHeight
                + (hasTitle && hasBody ? 4 : 0);

        int panelWidth = INNER_WIDTH + PAD * 2 + ACCENT_WIDTH;
        int panelHeight = textHeight + PAD * 2;
        int right = resolution.getScaledWidth() - MARGIN;
        int bottom = resolution.getScaledHeight() - MARGIN;
        int left = right - panelWidth;
        int top = bottom - panelHeight;

        // 影子（先画，稍微偏移一格）+ 半透明底板
        Gui.drawRect(left + 1, top + 1, right + 1, bottom + 1, Math.min(a, 0x60) << 24);
        Gui.drawRect(left, top, right, bottom, Math.min(a, 0xD0) << 24);
        // 左侧强调条
        Gui.drawRect(left, top, left + ACCENT_WIDTH, bottom, (a << 24) | ACCENT_COLOR);
        // 顶部一条淡淡的高光，让面板不像一块死黑
        Gui.drawRect(left, top, right, top + 1, Math.min(a, 0x40) << 24 | 0xFFFFFF);

        int textX = left + ACCENT_WIDTH + PAD;
        int cursorY = top + PAD;
        int titleColor = (a << 24) | 0xFFFFFF;
        int bodyColor = (a << 24) | 0xDDDDDD;

        for (String line : titleLines) {
            font.drawStringWithShadow(line, textX, cursorY, titleColor);
            cursorY += lineHeight;
        }
        if (hasTitle && hasBody) {
            Gui.drawRect(textX, cursorY + 1, right - PAD, cursorY + 2, (Math.min(a, 0x50) << 24) | 0xFFFFFF);
            cursorY += 4;
        }
        for (String line : bodyLines) {
            font.drawStringWithShadow(line, textX, cursorY, bodyColor);
            cursorY += lineHeight;
        }
    }

    private void refresh(FontRenderer font) {
        int serial = ClientNotice.getSerial();
        if (serial == cachedSerial && font == cachedFont) {
            return;
        }
        cachedSerial = serial;
        cachedFont = font;
        titleLines = wrap(font, ClientNotice.getTitle(), INNER_WIDTH, MAX_TITLE_LINES);
        bodyLines = wrap(font, ClientNotice.getContent(), INNER_WIDTH, MAX_BODY_LINES);
    }

    /**
     * 按像素宽度折行，并限制行数。超出部分在最后一行用省略号收尾。
     * <p>
     * {@code I18n} 的格式化占位符（%s / 颜色码）会被原样保留，所以折行不会吃掉格式。
     * 做成静态的另一个原因是界面（{@link GuiAnnouncer}）要用它画"预览"，
     * 两边用同一份实现才能保证"预览里长什么样，实际就长什么样"。
     */
    public static List<String> wrap(FontRenderer font, String text, int width, int maxLines) {
        if (font == null || text == null || text.isEmpty() || maxLines <= 0) {
            // ★ maxLines <= 0 必须在这里就返回空：
            //   否则会掉进下面的截断分支，subList(0, 0) 得到空表再去 get(-1) 就抛越界。
            //   （界面里算可用行数时，标题占满剩余空间确实会算出 0。）
            return Collections.emptyList();
        }
        List<String> wrapped;
        try {
            // ★ 返回的是 Arrays.asList(...)，是定长不可变的，所以截断时必须自己拷一份
            wrapped = font.listFormattedStringToWidth(text, Math.max(8, width));
        } catch (Throwable t) {
            // 文本里出现半个格式码（§ 后面没跟字符）时原版会抛异常，
            // 这种时候退化成"原样一行"，总比整个 HUD 不画了好
            wrapped = Collections.singletonList(text);
        }
        if (wrapped.isEmpty()) {
            return Collections.emptyList();
        }
        if (wrapped.size() <= maxLines) {
            return wrapped;
        }
        List<String> out = new ArrayList<String>(wrapped.subList(0, maxLines));
        String last = out.get(maxLines - 1);
        String dots = "...";
        int room = Math.max(0, width - font.getStringWidth(dots));
        out.set(maxLines - 1, font.trimStringToWidth(last, room) + dots);
        return out;
    }

    /** 给界面用的"这份内容一共有几行"（不做行数上限，界面自己决定怎么用） */
    public static List<String> wrapAll(FontRenderer font, String text, int width) {
        return wrap(font, text, width, Integer.MAX_VALUE);
    }

    /** 调试/日志用：把折行结果拼成一行，方便对比 */
    static String join(List<String> lines) {
        return lines == null ? "" : Arrays.toString(lines.toArray());
    }
}
