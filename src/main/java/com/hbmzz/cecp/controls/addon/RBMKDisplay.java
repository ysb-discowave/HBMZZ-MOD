/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM Cursed Addon (CE Addon); see NOTICE.md for the list
 * of changes.
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

package com.hbmzz.cecp.controls.addon;

import com.hbmzz.cecp.Control;
import com.hbmzz.cecp.ControlEvent;
import com.hbmzz.cecp.ControlPanel;
import com.hbmzz.cecp.GuiControlEdit;
import com.hbmzz.cecp.block.BlockCtrlConsole;
import com.hbmzz.cecp.compat.AddonRes;
import com.hbmzz.cecp.compat.Brush;
import com.hbmzz.cecp.compat.CtrlBuf;
import com.hbmzz.cecp.compat.CtrlImm;
import com.hbmzz.cecp.compat.IModelCustom;
import com.hbmzz.cecp.compat.RBMKColumns;
import com.hbmzz.cecp.controls.ControlType;
import com.hbmzz.cecp.controls.configs.SubElementBaseConfig;
import com.hbmzz.cecp.controls.configs.addon.SubElementRBMKDisplay;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Map;

/**
 * Live top-down map of an RBMK core, one column per rod.
 *
 * <p>Ported from CE Addon's {@code RBMKDisplay}. Upstream reads
 * {@code BlockControlPanel.FACING} off the panel and
 * {@code TileEntityRBMKBase.getConsoleData()} off each rod; both belong to CE, so
 * the facing comes from this mod's own console block and the column data from
 * {@link RBMKColumns}.</p>
 */
public class RBMKDisplay extends Control {

    private int width = 1;
    private int height = 1;
    private int offsetU = 0;
    private int offsetV = 0;

    public RBMKDisplay(String name, String registryName, ControlPanel panel) {
        super(name, registryName, panel);
        configMap.put("width", new DataValueFloat(width));
        configMap.put("height", new DataValueFloat(height));
        configMap.put("offsetU", new DataValueFloat(offsetU));
        configMap.put("offsetV", new DataValueFloat(offsetV));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public SubElementBaseConfig getConfigSubElement(GuiControlEdit gui, Map<String, DataValue> configs) {
        return new SubElementRBMKDisplay(gui, configs);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderControl(float[] renderBox, Control selectedControl, GuiControlEdit gui) {
        CtrlBuf buf = CtrlImm.INSTANCE.beginPositionTexColorQuads(1);
        int packedColor = CtrlBuf.packColor(1F, this == selectedControl ? 0.8F : 1F, 1F, 1F);
        appendGuiQuad(buf, renderBox[0], renderBox[1], renderBox[2], renderBox[3],
                offsetU / 2F, offsetV / 2F,
                width / 2F + offsetU / 2F, height / 2F + offsetV / 2F,
                packedColor);
        CtrlImm.INSTANCE.draw();

        Brush brush = Brush.instance;
        float x = (renderBox[0] + renderBox[2]) / 2F;
        float y = (renderBox[1] + renderBox[3]) / 2F;
        GlStateManager.disableTexture2D();
        brush.startDrawing(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        float x0 = x - width / 2F;
        float x1 = x + width / 2F;
        float y0 = y - height / 2F;
        float y1 = y + height / 2F;
        brush.addVertexWithColor(x0, y0, 0, 1F, 1F, 1F, 1F);
        brush.addVertexWithColor(x1, y0, 0, 1F, 1F, 1F, 1F);
        brush.addVertexWithColor(x1, y0, 0, 1F, 1F, 1F, 1F);
        brush.addVertexWithColor(x1, y1, 0, 1F, 1F, 1F, 1F);
        brush.addVertexWithColor(x1, y1, 0, 1F, 1F, 1F, 1F);
        brush.addVertexWithColor(x0, y1, 0, 1F, 1F, 1F, 1F);
        brush.addVertexWithColor(x0, y1, 0, 1F, 1F, 1F, 1F);
        brush.addVertexWithColor(x0, y0, 0, 1F, 1F, 1F, 1F);
        brush.draw();
        GlStateManager.enableTexture2D();
    }

    @Override
    protected void onConfigMapChanged() {
        for (Map.Entry<String, DataValue> entry : configMap.entrySet()) {
            switch (entry.getKey()) {
                case "width":
                    width = (int) entry.getValue().getNumber();
                    break;
                case "height":
                    height = (int) entry.getValue().getNumber();
                    break;
                case "offsetU":
                    offsetU = entry.getValue().getBoolean() ? 1 : 0;
                    break;
                case "offsetV":
                    offsetV = entry.getValue().getBoolean() ? 1 : 0;
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public ControlType getControlType() {
        return AddonControlType.RBMK_DISPLAY;
    }

    @Override
    public AxisAlignedBB getBoundingBox() {
        return null;
    }

    @Override
    public float[] getSize() {
        return new float[]{1F, 1F, 0.1F};
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void render() {
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1F, 1F, 1F);
        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, 0.005F, posY);
        Minecraft.getMinecraft().getTextureManager().bindTexture(AddonRes.rbmk_tex);
        Brush brush = Brush.instance;
        brush.startDrawingQuads();
        brush.addVertexWithUV(-width / 2F, 0, height / 2F, offsetU / 2F, height / 2F + offsetV / 2F);
        brush.addVertexWithUV(width / 2F, 0, height / 2F, width / 2F + offsetU / 2F, height / 2F + offsetV / 2F);
        brush.addVertexWithUV(width / 2F, 0, -height / 2F, width / 2F + offsetU / 2F, offsetV / 2F);
        brush.addVertexWithUV(-width / 2F, 0, -height / 2F, offsetU / 2F, offsetV / 2F);
        brush.draw();
        GlStateManager.translate(0, 0.005F, 0);

        World world = panel.parent.getControlWorld();
        String error = null;
        if (taggedLinks.isEmpty()) {
            error = "RBMK$not linked";
        } else if (taggedLinks.size() > 1) {
            error = "Must have only$1 position$linked";
        }

        if (error != null) {
            GlStateManager.rotate(90F, 1F, 0F, 0F);
            GlStateManager.scale(0.1F, 0.1F, 0.1F);
            FontRenderer font = Minecraft.getMinecraft().fontRenderer;
            String[] str = error.split("\\$");
            for (int i = 0; i < str.length; i++) {
                float line = -(str.length - 1) + i;
                String s = str[i];
                font.drawString(s, -font.getStringWidth(s) / 2, (int) (line * 11F), 0xFFFFFF);
            }
        } else {
            BlockPos center = taggedLinks.values().toArray(new BlockPos[0])[0];
            EnumFacing facing = facingOf(world);
            GlStateManager.scale(8F, 8F, 8F);
            GlStateManager.rotate(90F, 0F, 1F, 0F);
            GlStateManager.rotate(90F, 0F, 0F, 1F);
            GlStateManager.disableTexture2D();
            brush.startDrawing(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            float startX = -(width - 1) / 2F;
            float startY = -(height - 1) / 2F;
            int startXInt = (int) Math.floor(startX);
            int startYInt = (int) Math.floor(startY);

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    BlockPos pos = center.offset(facing, -startYInt - y).offset(facing.rotateY(), startXInt + x);
                    processColumn(world, pos, brush.buf, (startY + y) * 0.125, (startX + x) * 0.125);
                }
            }
            brush.draw();
            GlStateManager.enableTexture2D();
        }
        GlStateManager.popMatrix();
    }

    /** Panel facing, opposite of the block's so the map reads from the front. */
    @SideOnly(Side.CLIENT)
    private EnumFacing facingOf(World world) {
        BlockPos pos = panel.parent.getControlPos();
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BlockCtrlConsole && state.getValue(BlockCtrlConsole.FACING) != null) {
            return state.getValue(BlockCtrlConsole.FACING).getOpposite();
        }
        return EnumFacing.NORTH;
    }

    @SideOnly(Side.CLIENT)
    void processColumn(World world, BlockPos pos, BufferBuilder buf, double ky, double kz) {
        RBMKColumns.Column col = RBMKColumns.at(world, pos);
        if (col == null) {
            return;
        }
        float r = 1F;
        float g = 1F;
        float b = 1F;
        if (col.isControl() || col.isControlAuto()) {
            if (col.color >= 0) {
                switch (col.color) {
                    case 0:
                        g = 0F;
                        b = 0F;
                        break;
                    case 1:
                        b = 0F;
                        break;
                    case 2:
                        r = 0F;
                        g = 0.5F;
                        b = 0F;
                        break;
                    case 3:
                        r = 0F;
                        g = 0F;
                        break;
                    case 4:
                        r = 0.5F;
                        g = 0F;
                        break;
                    default:
                        break;
                }
            } else {
                double heat = col.maxHeat > 0D ? Math.min(col.heat / col.maxHeat, 1D) : 0D;
                double baseColor = 0.65;
                r = (float) (baseColor + (1D - baseColor) * heat);
                g = (float) baseColor;
                b = (float) baseColor;
            }
        } else {
            double heat = col.maxHeat > 0D ? Math.min(col.heat / col.maxHeat, 1D) : 0D;
            double baseColor = 0.65;
            r = (float) (baseColor + (1D - baseColor) * heat);
            g = (float) baseColor;
            b = (float) baseColor;
        }

        drawColumn(buf, 0D, ky, kz, r, g, b);
        if (col.isFuel()) {
            drawDot(buf, 0.01, ky, kz, 0F, 0.25F + (float) (col.enrichment * 0.75), 0F);
        } else if (col.isControl()) {
            drawDot(buf, 0.01, ky, kz, (float) col.level, (float) col.level, 0F);
        } else if (col.isControlAuto()) {
            drawDot(buf, 0.01, ky, kz, (float) col.level, 0F, (float) col.level);
        }
    }

    @SideOnly(Side.CLIENT)
    private void drawColumn(BufferBuilder buf, double x, double y, double z, float r, float g, float b) {
        double w = 0.046875;
        buf.pos(x, y + w, z - w).color(r, g, b, 1F).endVertex();
        buf.pos(x, y + w, z + w).color(r, g, b, 1F).endVertex();
        buf.pos(x, y - w, z + w).color(r, g, b, 1F).endVertex();
        buf.pos(x, y - w, z - w).color(r, g, b, 1F).endVertex();
    }

    /** An octagon standing in for a round indicator dot. */
    @SideOnly(Side.CLIENT)
    private void drawDot(BufferBuilder buf, double x, double y, double z, float r, float g, float b) {
        double w = 0.03125;
        double edge = 0.022097;
        buf.pos(x, y + w, z).color(r, g, b, 1F).endVertex();
        buf.pos(x, y + edge, z + edge).color(r, g, b, 1F).endVertex();
        buf.pos(x, y, z + w).color(r, g, b, 1F).endVertex();
        buf.pos(x, y - edge, z + edge).color(r, g, b, 1F).endVertex();
        buf.pos(x, y + edge, z - edge).color(r, g, b, 1F).endVertex();
        buf.pos(x, y + w, z).color(r, g, b, 1F).endVertex();
        buf.pos(x, y - edge, z - edge).color(r, g, b, 1F).endVertex();
        buf.pos(x, y, z - w).color(r, g, b, 1F).endVertex();
        buf.pos(x, y + w, z).color(r, g, b, 1F).endVertex();
        buf.pos(x, y - edge, z + edge).color(r, g, b, 1F).endVertex();
        buf.pos(x, y - w, z).color(r, g, b, 1F).endVertex();
        buf.pos(x, y - edge, z - edge).color(r, g, b, 1F).endVertex();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IModelCustom getModel() {
        return null;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ResourceLocation getGuiTexture() {
        return AddonRes.rbmk_tex;
    }

    @Override
    public Control newControl(ControlPanel panel) {
        return new RBMKDisplay(name, registryName, panel);
    }

    @Override
    public void populateDefaultNodes(List<ControlEvent> receiveEvents) {
    }
}
