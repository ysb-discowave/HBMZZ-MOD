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

package com.hbmzz.redstone;

/**
 * 红石门能做的四种运算。
 *
 * <p>为什么是四种方块而不是一块「可切换的门」：1.12.2 的方块状态最终要落到
 * <strong>4 位 meta</strong> 上，最多 16 个。朝向占 4 个、亮/灭占 2 个，已经用掉
 * 8 个组合；再塞 8 种运算就得牺牲朝向或亮灭，两头都不划算。拆成四个方块之后，
 * 每块只有「朝向 × 亮灭」8 个状态，meta 装得下，客户端也不用为了知道自己是
 * 哪种门而跟服务端同步一遍。</p>
 *
 * <p>更实际的一条理由：门是<strong>没有状态</strong>的东西。一块与门就是一块与门，
 * 不给它可切换的运算，就不存在「存档里的门被谁改成了或门」这种事故。</p>
 */
public enum GateType {

    AND("and_gate", 2),
    OR("or_gate", 2),
    XOR("xor_gate", 2),
    NOT("not_gate", 1);

    private final String id;
    private final int inputs;

    GateType(String id, int inputs) {
        this.id = id;
        this.inputs = inputs;
    }

    /** 注册名与贴图名共用的短名，如 {@code and_gate}。 */
    public String id() {
        return this.id;
    }

    /** 1 = 单输入（背进正出），2 = 双输入（两侧进、正面出）。 */
    public int inputs() {
        return this.inputs;
    }

    public boolean twoInputs() {
        return this.inputs == 2;
    }

    /**
     * 真值表。{@code a} 是左输入（或非门的背输入），{@code b} 是右输入。
     *
     * <p>只认「有电 / 没电」两态，不按模拟量加权：门是数字电路的东西，15 级
     * 红石在里面只用来判两次「是不是零」。要做模拟量运算，那该是另一台设备。</p>
     */
    public boolean eval(boolean a, boolean b) {
        switch (this) {
            case AND:
                return a && b;
            case OR:
                return a || b;
            case XOR:
                return a ^ b;
            case NOT:
                return !a;
            default:
                return false;
        }
    }
}
