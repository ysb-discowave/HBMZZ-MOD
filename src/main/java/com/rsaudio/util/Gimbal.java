package com.rsaudio.util;

/**
 * 监控摄像头云台的角度换算 —— <b>纯数学，不 import 任何 Minecraft 类</b>。
 *
 * <h3>为什么单独拎出来</h3>
 * "摄像头看哪个方向"这件事看起来只有两行三角函数，但它有两条隐蔽的失败路径，
 * 而且<b>都不会崩、只会画面错</b>：
 * <ol>
 *   <li><b>角度叠加的口径错</b>：基础朝向（六向）换算出的 yaw/pitch，和玩家设的偏移，
 *       如果约定的正负号不一样，会得到"按 ▶ 往左转"这种反直觉行为。</li>
 *   <li><b>视线向量公式抄错一个符号</b>：探测锥会镜像翻转，表现为"画面里明明站着人，
 *       控制台却说没人"—— 而画面本身是对的，所以极难判断是哪儿错了。</li>
 * </ol>
 * 把这两件事放在一个<b>不依赖 Minecraft、能被 {@code tools/check_gimbal_core.py}
 * 真 javac 编译真运行</b>的类里，就能把"方向对不对"钉死在断言里，
 * 而不是靠进游戏肉眼转一圈。
 *
 * <h3>角度约定（和原版 {@code Entity} 完全一致）</h3>
 * <pre>
 *   yaw = 0    → 朝 +Z（南）
 *   yaw = 90   → 朝 -X（西）
 *   yaw = 180  → 朝 -Z（北）
 *   yaw = 270  → 朝 +X（东）
 *   pitch      → 正数往下看，-90 正上，+90 正下
 * </pre>
 * 注意 yaw 增大时是<b>顺时针（从上方俯视）</b>，也就是"往右转"。
 * 所以界面上 ▶ = yaw 增加 = 镜头右摇，▲ = pitch 减小 = 抬头。
 *
 * <h3>云台偏移是"叠加"不是"替换"</h3>
 * 摄像头贴哪个面是放置时定死的（只给 6 个方向，挂歪一点就拍不到东西），
 * 偏移量是在那个基础朝向上<b>再加</b>多少度。所以最终朝向 =
 * {@code baseRotation}（由方块实体的 FACING 求出）+ 偏移。
 */
public final class Gimbal {

    /**
     * 水平偏移范围（度）。±180 覆盖一整圈。
     * <p>
     * ★ 归一化用的是"对 360 取模"，不是 min/max 夹取 —— 见 {@link #clampYaw(int)}。
     */
    public static final int YAW_MIN = -180;
    public static final int YAW_MAX = 180;
    /** 界面上按一下转多少度。15° 一圈 24 下，既能精细对位又不用按到手酸 */
    public static final int YAW_STEP = 15;
    public static final int YAW_DEFAULT = 0;

    /**
     * 俯仰偏移范围（度）。正数 = 往下压。
     * <p>
     * ★ 对吸顶 / 落地的摄像头，<b>只有这一项有用</b>：一个正朝下看的镜头
     * look = (0,-1,0)，绕它自己自转画面完全不变（yaw 白转）。
     * 想让它"斜着往下看"只能靠 pitch 偏移。
     */
    public static final int PITCH_MIN = -90;
    public static final int PITCH_MAX = 90;
    public static final int PITCH_STEP = 10;
    public static final int PITCH_DEFAULT = 0;

    private Gimbal() {
    }

    // ------------------------------------------------------------------ 归一化

    /**
     * 把水平偏移归一化到 [-180, 180]。
     * <p>
     * ★ 不能写成 {@code min(180, max(-180, v))}：那样玩家连按 ▶ 到 195° 会被夹成 180°，
     * 再按还是 180°，看起来像"转不过去、卡住了"。按 360 取模之后 195° → -165°，
     * 是连续的 —— 界面上的数字会从 +180 跳到 -165，但镜头在继续转，符合直觉。
     * <p>
     * 另一个边界：{@code -180} 和 {@code 180} 是同一个方向。这里保留输入的符号
     * （{@code clampYaw(-180) == -180}，{@code clampYaw(180) == 180}），
     * 因为对渲染来说两者完全等价，没必要多一步归一。
     */
    public static int clampYaw(int value) {
        int v = value % 360;
        if (v > YAW_MAX) {
            v -= 360;
        } else if (v < YAW_MIN) {
            v += 360;
        }
        return v;
    }

    /**
     * 把俯仰偏移夹到 [-90, 90]。
     * <p>
     * ★ 这里必须用"夹"而不是"取模"：俯仰是绕水平轴转，越过 ±90 之后
     * 视线会翻到背面（原版 {@code getVectorForRotation} 在 |pitch| > 90 时会
     * 让 yaw 的贡献反向），画面上表现为"越过头顶的一瞬间视野整个甩到背后"。
     * 夹住之后顶到极限就停住，符合"抬头抬到头了"的直觉。
     */
    public static int clampPitch(int value) {
        if (value < PITCH_MIN) {
            return PITCH_MIN;
        }
        return value > PITCH_MAX ? PITCH_MAX : value;
    }

    // ------------------------------------------------------------------ 叠加

    /**
     * 最终 yaw = 基础朝向的 yaw + 水平偏移，再归一化到 [-180, 180]。
     * <p>
     * 归一化不是为了渲染（三角函数天然周期，450° 和 90° 画出来一模一样），
     * 而是为了让"最终朝向"这个值本身<b>可读、可比较、可断言</b>：
     * 调试日志里出现一个 450° 或者 -270°，读的人得自己心算一圈才知道镜头朝哪。
     */
    public static float combineYaw(float baseYaw, int offset) {
        return (float) clampYaw(Math.round(baseYaw) + offset);
    }

    /**
     * 最终 pitch = 基础朝向的 pitch + 俯仰偏移，再夹到 ±90。
     * <p>
     * 夹这一步是必需的：偏移自己已经被 {@link #clampPitch} 夹过，但<b>和</b>基础朝向
     * 相加之后仍可能越界 —— 例如吸顶镜头（基础 pitch = +90）配上 +10° 的偏移就是 100°。
     * 所以要在"相加之后"再夹一次。
     */
    public static float combinePitch(float basePitch, int offset) {
        float p = basePitch + (float) offset;
        if (p > PITCH_MAX) {
            return PITCH_MAX;
        }
        if (p < PITCH_MIN) {
            return PITCH_MIN;
        }
        return p;
    }

    /**
     * 基础朝向 + 偏移 -> 最终 {@code {yaw, pitch}}（注意顺序是 <b>yaw 在前</b>，
     * 因为 {@code Entity.setLocationAndAngles} 就是这个顺序）。
     * <p>
     * ★ 本类里有两套参数顺序，是因为它们各自对齐了一个原版方法，别想着统一：
     * <pre>
     *   rotation / combineYaw / combinePitch : (…, yaw 先, pitch 后)  ← 对齐 setLocationAndAngles
     *   lookVector / lookVectorFrom          : (pitch 先, yaw 后)  ← 对齐 getVectorForRotation
     * </pre>
     * 写反了在 pitch=0 附近完全看不出来（yaw 和 pitch 里有一个是 0 时结果一样），
     * 一抬镜头才开始错。所以这里每个方法的 javadoc 都标了顺序，测试里也用
     * {@code baseLook(...)} 这类包装函数彻底回避手写顺序。
     */
    public static float[] rotation(float baseYaw, float basePitch, int yawOffset, int pitchOffset) {
        return new float[] { combineYaw(baseYaw, yawOffset), combinePitch(basePitch, pitchOffset) };
    }

    // ------------------------------------------------------------------ 视线向量

    /**
     * yaw / pitch 对应的<b>单位</b>视线向量，返回 {@code {x, y, z}}。
     *
     * <h3>★ 这条公式是抄的，不是推的</h3>
     * 和原版 {@code Entity.getVectorForRotation(pitch, yaw)}（以及
     * {@code Vec3d.fromPitchYaw(pitch, yaw)}）逐条一致，已用
     * <pre>python tools/api_probe.py net.minecraft.util.math.Vec3d -c -m fromPitchYaw</pre>
     * 反汇编核对过字节码。
     * <p>
     * 为什么非要一致：渲染那边是把 yaw/pitch 交给原版去建投影矩阵，
     * 这边如果按六个 {@code EnumFacing} 的轴向去凑，两边迟早会在某个角度上跑偏。
     * 而"探测到谁"和"画面里看到谁"必须是同一个方向，否则整个监控功能就不可信了。
     * <p>
     * 参数顺序是 <b>(pitch, yaw)</b>，别写反 —— 写反了在 pitch=0 附近看不出问题，
     * 一抬镜头才开始错，属于最费时间的那种 bug。所以这里签名也照原版排。
     *
     * @param pitch 俯仰角（度），正数往下
     * @param yaw   水平角（度），0 = +Z
     */
    public static double[] lookVector(float pitch, float yaw) {
        // ↓ 以下四行 = 原版 getVectorForRotation 的四行，别"优化"
        float yawRad = -yaw * 0.017453292F - (float) Math.PI;
        float cosYaw = (float) Math.cos(yawRad);
        float sinYaw = (float) Math.sin(yawRad);
        float pitchCos = -(float) Math.cos(-pitch * 0.017453292F);
        float pitchSin = (float) Math.sin(-pitch * 0.017453292F);
        return new double[] { sinYaw * pitchCos, pitchSin, cosYaw * pitchCos };
    }

    /**
     * {@link #rotation} + {@link #lookVector} 的组合：从基础朝向直接得到视线向量。
     * <p>
     * ★ 参数顺序是 {@code (baseYaw, basePitch, …)}，和 {@link #rotation} / {@link #combineYaw}
     * 一套；而返回值是向量，和 {@link #lookVector} 一套。类注释里那张"两套顺序"的表
     * 就是为了防这个 —— 调用方（含测试）宁可用包装函数，也别手写参数。
     */
    public static double[] lookVectorFrom(float baseYaw, float basePitch, int yawOffset, int pitchOffset) {
        float[] rot = rotation(baseYaw, basePitch, yawOffset, pitchOffset);
        return lookVector(rot[1], rot[0]);
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 视锥半横角的正切。原版的 {@code fovSetting} 是<b>纵</b>向视野，
     * 横向要按屏幕宽高比放大 —— 所以"画面里有没有他"不能只拿 fov 当圆锥半角去比，
     * 否则站在画面左右边缘的人会被漏掉。
     */
    public static double tanHalfHorizontal(double fovDegrees, int width, int height) {
        double tanVertical = Math.tan(Math.toRadians(fovDegrees) / 2.0D);
        if (height <= 0) {
            return tanVertical;
        }
        return tanVertical * (double) width / (double) height;
    }

    /** 视锥半纵角的正切 */
    public static double tanHalfVertical(double fovDegrees) {
        return Math.tan(Math.toRadians(fovDegrees) / 2.0D);
    }

    // ------------------------------------------------------------------ 视锥判定

    /**
     * 由视线方向建一套相机坐标系，返回
     * {@code {rightX, rightY, rightZ, upX, upY, upZ}}。
     * <p>
     * 盒子是右手系，所以 {@code right = look × 世界上方}（不是反过来的
     * {@code 世界上方 × look}）—— 反了会让左右镜像。不过下面
     * {@link #insideFrustum} 取的是绝对值，镜像本身不影响判定结果；
     * 这里按正确的手性写是为了以后万一要做"左边的目标单独高亮"时不用回头改。
     * <p>
     * ★ 正上 / 正下看时 {@code look} 与世界上方平行，叉积退化成零向量。
     * 这时随便挑一个水平轴当 right 就是合法的正交基 —— 此时画面本身也没有
     * "左右"可言（绕着视线轴自转而已），选哪个都一样。
     */
    public static double[] cameraBasis(double lookX, double lookY, double lookZ) {
        // right = look × (0,1,0) = (lookY*0 - lookZ*1, lookZ*0 - lookX*0, lookX*1 - lookY*0)
        double rx = -lookZ;
        double ry = 0.0D;
        double rz = lookX;
        double rlen = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (rlen < 1.0E-4D) {
            rx = 1.0D;
            ry = 0.0D;
            rz = 0.0D;
        } else {
            rx /= rlen;
            ry /= rlen;
            rz /= rlen;
        }
        // up = right × look
        double ux = ry * lookZ - rz * lookY;
        double uy = rz * lookX - rx * lookZ;
        double uz = rx * lookY - ry * lookX;
        double ulen = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (ulen < 1.0E-9D) {
            ux = 0.0D;
            uy = 1.0D;
            uz = 0.0D;
        } else {
            ux /= ulen;
            uy /= ulen;
            uz /= ulen;
        }
        return new double[] { rx, ry, rz, ux, uy, uz };
    }

    /**
     * 目标点是否落在摄像头的视锥里。
     *
     * <h3>为什么不直接比"视线夹角 &lt; fov/2"</h3>
     * 那是一个<b>正圆锥</b>，而屏幕画面是<b>长方体视锥</b>：320×180 的画面上，
     * 左右的张角比上下大得多（宽高比 16:9）。用圆锥近似，站在画面左右边缘的人会
     * <b>明明看得见、却不被报出来</b>。云台能把视线指到任意方向之后，这个差别更明显
     * —— 尤其是把俯仰压到 -60° 俯看时，圆锥会把脚下一大片人漏掉。
     *
     * @param look   单位视线向量 {x, y, z}
     * @param delta  目标相对摄像头的位移向量 {x, y, z}
     * @param tanH   半横角正切（{@link #tanHalfHorizontal}）
     * @param tanV   半纵角正切（{@link #tanHalfVertical}）
     */
    public static boolean insideFrustum(double[] look, double[] delta, double tanH, double tanV) {
        double depth = look[0] * delta[0] + look[1] * delta[1] + look[2] * delta[2];
        // depth <= 0 = 在摄像头背后。★ 必须单独判：depth 为负时 fz*tan 也是负的，
        // |x| <= 负数 恒不成立、看着像"碰巧过滤掉了"，但 depth 恰好为 0 时两边都是 0，
        // |x| <= 0 会漏过正前方一点点的目标。用一个小正数阈值把这两种情况一起挡掉。
        if (depth <= 1.0E-4D) {
            return false;
        }
        double[] basis = cameraBasis(look[0], look[1], look[2]);
        double side = basis[0] * delta[0] + basis[1] * delta[1] + basis[2] * delta[2];
        double vert = basis[3] * delta[0] + basis[4] * delta[1] + basis[5] * delta[2];
        if (Math.abs(side) > depth * tanH) {
            return false;
        }
        return Math.abs(vert) <= depth * tanV;
    }
}
