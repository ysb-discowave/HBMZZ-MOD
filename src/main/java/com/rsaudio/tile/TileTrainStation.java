package com.rsaudio.tile;

import com.rsaudio.train.TrainSpec;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

/**
 * 列车站台的方块实体：只存两样东西 —— <b>站名</b>和<b>停站时间</b>。
 *
 * <h3>★ 为什么站台"不需要 tick"是刻意的</h3>
 * 站台是<b>被读的一方</b>，不是<b>主动的一方</b>。列车实体每隔
 * {@code TrainSettings.routeCheckTicks} 把整片区域扫一遍，把扫描结果
 * 整份灌进 {@code TrainCore}；站台只是被扫到的其中一个方格。
 * <p>
 * 这条边界很重要：如果站台自己 tick、自己往列车里注册/注销，就会出现
 * "列车已经有这条线路的旧快照、站台又在推新的"—— 两个来源打架，
 * 而症状是"拆了站台列车还停在那儿"或者"放了站台列车看不见"。
 * <b>唯一权威是每次的全量扫描</b>，站台只管自己存着什么。
 *
 * <h3>站名与停站时间</h3>
 * 站名最长 {@link TrainSpec#STATION_NAME_MAX} 字符，空名回落到
 * {@link TrainSpec#STATION_NAME_DEFAULT}（读的时候回落到默认，<b>不</b>在写的时候强制填
 * —— 玩家正在输入框里清空重打的时候不该被塞一个默认名回去）。
 * <p>
 * 停站时间夹在 {@link TrainSpec#DWELL_MIN_TICKS}..{@link TrainSpec#DWELL_MAX_TICKS}
 * 之间。上下限都要夹：太小（几 tick）会让车"刚停就开"，玩家根本来不及上车；
 * 太大（几十万 tick）会让整条线看着像死机。
 */
public class TileTrainStation extends TileEntity {

    private String name = TrainSpec.STATION_NAME_DEFAULT;
    private int dwellTicks = TrainSpec.DEFAULT_DWELL_TICKS;

    public String getName() {
        return name == null || name.isEmpty() ? TrainSpec.STATION_NAME_DEFAULT : name;
    }

    /**
     * 设置站名。会裁掉首尾空白、截到长度上限。
     * <p>
     * ★ 空串是允许的（等于"没起名"），{@link #getName()} 会回落到默认名。
     * 拦下空串会让玩家没法"先清空再想"。
     */
    public void setName(String value) {
        String v = value == null ? "" : value.trim();
        if (v.length() > TrainSpec.STATION_NAME_MAX) {
            v = v.substring(0, TrainSpec.STATION_NAME_MAX);
        }
        this.name = v;
        markDirty();
    }

    public int getDwellTicks() {
        return dwellTicks;
    }

    /** 设置停站时间（tick）。自动夹到合法区间 */
    public void setDwellTicks(int ticks) {
        this.dwellTicks = clampDwell(ticks);
        markDirty();
    }

    public static int clampDwell(int ticks) {
        if (ticks < TrainSpec.DWELL_MIN_TICKS) {
            return TrainSpec.DWELL_MIN_TICKS;
        }
        if (ticks > TrainSpec.DWELL_MAX_TICKS) {
            return TrainSpec.DWELL_MAX_TICKS;
        }
        return ticks;
    }

    // ------------------------------------------------------------------ NBT

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        if (compound.hasKey("StationName")) {
            setName(compound.getString("StationName"));
        }
        // ★ 读档也要夹：存档可能来自"参数范围还没收紧"的旧版本，
        //   或者被人手改过。一个 0 tick 的停站时间会让车到站不停。
        if (compound.hasKey("DwellTicks")) {
            dwellTicks = clampDwell(compound.getInteger("DwellTicks"));
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setString("StationName", name == null ? "" : name);
        compound.setInteger("DwellTicks", clampDwell(dwellTicks));
        return compound;
    }
}
