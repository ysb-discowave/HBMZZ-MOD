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

import com.hbmzz.redstone.GateType;

/**
 * 四种红石门的真值表，离线跑。
 *
 * <p>门的「算术」部分不碰世界，也不碰 Minecraft——{@link GateType} 里没有一行
 * acquire 方块或读红石的代码，读输入、写输出都在方块那边。所以这张真值表可以
 * 在没有游戏的情况下逐条断言，而不必开一个存档、摆四根杆子、点十六次。</p>
 *
 * <p>它验的是「门算得对不对」，不是「门接得通不通」。后者要真的放下方块、接上
 * 红石粉看活塞动不动，那部分只能进游戏验。</p>
 *
 *     javac -d tmp/gatecheck-out src/main/java/com/hbmzz/redstone/GateType.java \
 *           tools/gatecheck/GateCheck.java
 *     java -cp tmp/gatecheck-out GateCheck
 */
public final class GateCheck {

    private static int failed = 0;

    public static void main(String[] args) {
        // AND
        expect(GateType.AND, false, false, false);
        expect(GateType.AND, true, false, false);
        expect(GateType.AND, false, true, false);
        expect(GateType.AND, true, true, true);

        // OR
        expect(GateType.OR, false, false, false);
        expect(GateType.OR, true, false, true);
        expect(GateType.OR, false, true, true);
        expect(GateType.OR, true, true, true);

        // XOR
        expect(GateType.XOR, false, false, false);
        expect(GateType.XOR, true, false, true);
        expect(GateType.XOR, false, true, true);
        expect(GateType.XOR, true, true, false);

        // NOT：只吃 a，b 传什么都不该影响结果
        expect(GateType.NOT, false, false, true);
        expect(GateType.NOT, false, true, true);
        expect(GateType.NOT, true, false, false);
        expect(GateType.NOT, true, true, false);

        // 输入个数：错一个，方块那边就会去读不该读的那一侧
        expectInputs(GateType.AND, 2);
        expectInputs(GateType.OR, 2);
        expectInputs(GateType.XOR, 2);
        expectInputs(GateType.NOT, 1);

        // 注册名：方块、贴图、语言键全靠它，拼错了是三处一起错
        expectId(GateType.AND, "and_gate");
        expectId(GateType.OR, "or_gate");
        expectId(GateType.XOR, "xor_gate");
        expectId(GateType.NOT, "not_gate");

        // 对称性：与/或/异或换两根线的位置结果不变；这条要是错了，
        // 玩家把线插反就会得到相反的结果，而这在真值表里看不出来。
        for (GateType type : new GateType[]{GateType.AND, GateType.OR, GateType.XOR}) {
            for (int a = 0; a < 2; a++) {
                for (int b = 0; b < 2; b++) {
                    boolean ab = type.eval(a == 1, b == 1);
                    boolean ba = type.eval(b == 1, a == 1);
                    if (ab != ba) {
                        fail(type.id() + " 不对称：" + (a == 1) + "," + (b == 1));
                    }
                }
            }
        }

        if (failed > 0) {
            System.out.println("FAIL  " + failed + " 条没过");
            System.exit(1);
        }
        System.out.println("ALL PASS  16 条真值 + 4 条输入数 + 4 条注册名 + 对称性");
    }

    private static void expect(GateType type, boolean a, boolean b, boolean want) {
        boolean got = type.eval(a, b);
        if (got != want) {
            fail(String.format("%s(%s, %s) = %s，应为 %s", type.id(), a, b, got, want));
        } else {
            System.out.printf("  ok  %-9s A=%-5s B=%-5s -> %s%n", type.id(), a, b, got);
        }
    }

    private static void expectInputs(GateType type, int want) {
        if (type.inputs() != want || type.twoInputs() != (want == 2)) {
            fail(type.id() + " 输入数应为 " + want + "，实际 " + type.inputs());
        }
    }

    private static void expectId(GateType type, String want) {
        if (!want.equals(type.id())) {
            fail(type.name() + " 的注册名应为 " + want + "，实际 " + type.id());
        }
    }

    private static void fail(String msg) {
        failed++;
        System.out.println("  FAIL " + msg);
    }

    private GateCheck() {
    }
}
