package com.rsaudio.client;

import com.rsaudio.formats.convert.BundledFfmpeg;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 进世界前下载 ffmpeg 的进度页。
 *
 * <p>下载在后台线程里跑（{@link BundledFfmpeg#ensure(File, List)}），本页每帧只读
 * {@link BundledFfmpeg#progress()} 画进度条 —— 主线程完全不阻塞，所以画面不会黑。
 * 下载/解压完成后自动关掉页面回到原界面，并回调 {@code onDone}（让音频那边 reset +
 * 重新扫描曲库，这样刚下好的 ffmpeg 立刻被识别）。</p>
 *
 * <p>失败也不会卡住：进度 phase 变成"失败"后同样自动关闭，由调用方决定下一步。</p>
 */
public class GuiFfmpegDownload extends GuiScreen {

    private final GuiScreen parent;          // 打开前的界面，关掉后回去
    private final File gameDir;
    private final Runnable onDone;
    private Thread worker;
    private boolean started;
    private long finishedAt = -1L;
    private volatile String workerResult = "";

    public GuiFfmpegDownload(GuiScreen parent, File gameDir, Runnable onDone) {
        this.parent = parent;
        this.gameDir = gameDir;
        this.onDone = onDone;
    }

    @Override
    public void initGui() {
        if (!started) {
            started = true;
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        List<String> notes = new ArrayList<String>();
                        java.io.File exe = BundledFfmpeg.ensure(gameDir, notes);
                        workerResult = exe == null ? "失败" : "完成";
                    } catch (Throwable t) {
                        workerResult = "失败: " + t;
                    }
                }
            }, "rsaudio-ffmpeg-download");
            worker.setDaemon(true);
            worker.start();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int cx = this.width / 2;
        BundledFfmpeg.Progress p = BundledFfmpeg.progress();

        this.drawCenteredString(this.fontRenderer, "正在准备音频播放器", cx, this.height / 2 - 62, 0xFFFFFF);
        this.drawCenteredString(this.fontRenderer, "首次使用需要下载 FFmpeg（约 190 MB），请稍候…",
                cx, this.height / 2 - 44, 0xAAAAAA);

        // 进度条外框
        int barW = Math.min(360, this.width - 80);
        int barX = cx - barW / 2;
        int barY = this.height / 2 - 18;
        drawRect(barX - 1, barY - 1, barX + barW + 1, barY + 13, 0xFF000000);
        drawRect(barX, barY, barX + barW, barY + 12, 0xFF2B2B2B);

        int pct = p.percent();
        if (pct < 0) {
            pct = 0;
        }
        if (pct > 100) {
            pct = 100;
        }
        int fill = barW * pct / 100;
        if (fill > 0) {
            drawRect(barX, barY, barX + fill, barY + 12, 0xFF3B8C3B);
        }

        String phase = p.phase;
        String detail;
        if ("下载中".equals(phase) || "下载中(单线程)".equals(phase)) {
            detail = phase + "  " + p.done + "/" + p.total + " 段  " + pct + "%";
        } else if ("连接中".equals(phase)) {
            detail = "连接下载服务器…";
        } else if ("合并中".equals(phase)) {
            detail = "合并下载数据…";
        } else if ("解压中".equals(phase)) {
            detail = "解压 FFmpeg…";
        } else if ("已缓存".equals(phase) || "完成".equals(phase)) {
            detail = "完成，正在识别音频…";
        } else if ("失败".equals(phase)) {
            detail = "下载失败，将尝试其它方式";
        } else {
            detail = phase;
        }
        this.drawCenteredString(this.fontRenderer, detail, cx, barY + 24, 0xFFDDDD55);

        String mb = String.format("%.0f / %.0f MB", p.bytes / 1048576.0F, p.totalBytes / 1048576.0F);
        this.drawCenteredString(this.fontRenderer, mb, cx, barY + 40, 0xFF888888);

        // 进度结束（或后台线程已给出结果）→ 停留约 1 秒让玩家看到"完成"，然后关闭并回调
        boolean ended = p.finished() || !workerResult.isEmpty();
        if (ended && finishedAt < 0L) {
            finishedAt = System.currentTimeMillis();
        }
        if (finishedAt > 0L && System.currentTimeMillis() - finishedAt > 1100L) {
            closeAndContinue();
        }
    }

    private void closeAndContinue() {
        Minecraft mc = Minecraft.getMinecraft();
        mc.displayGuiScreen(parent);
        if (onDone != null) {
            try {
                onDone.run();
            } catch (Throwable ignored) {
                // 识别失败不影响回到游戏
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // 下载中不允许 ESC 关掉（关掉也没回调，音频那边永远不会重新识别）
        // 结束后的那一秒也不拦，closeAndContinue 马上就执行了
    }
}
