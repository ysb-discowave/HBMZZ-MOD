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

package com.hbmzz.client;

import com.hbmzz.entity.EntityRbmkLid;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws the flying lid.
 *
 * <p>The plate is a set of one-block lid plates in the arrangement they had on
 * the reactor, each textured with its own column's cover sprite, so a reactor
 * with a boiler and six fuel channels throws a lid that still looks like that
 * reactor's lid. It is drawn as boxes rather than as blocks because a block
 * cannot be rotated around a shared centre - and a rigid plate that tumbles is
 * the whole image being sold here.</p>
 *
 * <p>Parsed layouts are cached per entity: the string is decoded once, and the
 * sprites are resolved once per texture name, not per lid per frame.</p>
 */
public class RenderRbmkLid extends Render<EntityRbmkLid> {

    private static final float THICKNESS = 0.25F;

    /** Decoded plates, so the layout string is walked once and not per frame. */
    private static final Map<EntityRbmkLid, List<int[]>> PLATES =
            new WeakHashMap<EntityRbmkLid, List<int[]>>();
    private static final Map<EntityRbmkLid, String> SOURCES =
            new WeakHashMap<EntityRbmkLid, String>();
    /** Sprite cache: texture name plus lid kind to the sprite the atlas gave us. */
    private static final Map<String, TextureAtlasSprite> SPRITES = new java.util.HashMap<String, TextureAtlasSprite>();
    /** Set after a failure so a broken plate does not spam the log every frame. */
    private static volatile boolean REPORTED;

    public RenderRbmkLid(RenderManager manager) {
        super(manager);
    }

    @Override
    public void doRender(EntityRbmkLid entity, double x, double y, double z, float entityYaw, float partialTicks) {
        List<int[]> lids;
        try {
            lids = lidsOf(entity);
        } catch (Throwable broken) {
            report("layout", broken);
            return;
        }
        if (lids.isEmpty()) {
            return;
        }

        this.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        GlStateManager.pushMatrix();
        boolean begun = false;
        try {
            List<String> textures = com.hbmzz.rbmk.RbmkLayout.texturesOf(entity.layout());
            GlStateManager.translate(x, y, z);
            GlStateManager.rotate(entity.rotationYaw, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(entity.rotationPitch, 1.0F, 0.0F, 0.0F);
            GlStateManager.disableLighting();

            BufferBuilder buffer = Tessellator.getInstance().getBuffer();
            buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_LMAP_COLOR);
            begun = true;

            for (int[] lid : lids) {
                String texture = lid[3] >= 0 && lid[3] < textures.size() ? textures.get(lid[3]) : "";
                TextureAtlasSprite sprite = spriteFor(texture, lid[2]);
                // Offsets are stored doubled: half-block precision on a string.
                float dx = lid[0] / 2.0F;
                float dz = lid[1] / 2.0F;
                box(buffer, sprite, dx, 0.0F, dz, dx + 1.0F, THICKNESS, dz + 1.0F);
            }
        } catch (Throwable broken) {
            // A plate that cannot be textured is a cosmetic problem. Taking the
            // client down over one missing sprite is not, and the player cannot
            // even read the crash report to tell us which sprite it was - so the
            // failure is logged here, once, with the whole stack.
            report("render", broken);
        } finally {
            if (begun) {
                try {
                    Tessellator.getInstance().draw();
                } catch (Throwable ignored) {
                    // Nothing to draw into: the buffer was already lost.
                }
            }
            GlStateManager.enableLighting();
            GlStateManager.popMatrix();
        }
        super.doRender(entity, x, y, z, entityYaw, partialTicks);
    }

    /** Logs a rendering failure once, then stops trying to be clever about it. */
    private static void report(String where, Throwable broken) {
        if (REPORTED) {
            return;
        }
        REPORTED = true;
        if (com.hbmzz.HBMZZ.logger != null) {
            com.hbmzz.HBMZZ.logger.error("RBMK lid " + where + " failed; the plate will not be drawn", broken);
        }
    }

    private List<int[]> lidsOf(EntityRbmkLid entity) {
        String layout = entity.layout();
        String known = SOURCES.get(entity);
        if (known != null && known.equals(layout)) {
            List<int[]> cached = PLATES.get(entity);
            if (cached != null) {
                return cached;
            }
        }
        List<int[]> lids = new ArrayList<int[]>();
        List<String> textures = new ArrayList<String>();
        com.hbmzz.rbmk.RbmkLayout.decode(layout, lids, textures);
        PLATES.put(entity, lids);
        SOURCES.put(entity, layout);
        return lids;
    }

    /**
     * Resolves a lid sprite, trying the names the base mod could have used.
     *
     * <p>{@code columnTexture} is handed to {@code TextureMap.registerSprite}
     * as a relative path, and where that path points has changed between
     * versions of the base mod. Trying the two layouts costs one atlas lookup on
     * the first frame of the first plate and nothing afterwards.</p>
     */
    private static TextureAtlasSprite spriteFor(String texture, int lidType) {
        String suffix = lidType == 2 ? "_glass_top" : "_cover_top";
        String key = texture + suffix;
        TextureAtlasSprite cached = SPRITES.get(key);
        if (cached != null) {
            return cached;
        }
        TextureMap map = Minecraft.getMinecraft().getTextureMapBlocks();
        String[] candidates = {
                "hbm:" + texture + suffix,
                "hbm:blocks/rbmk/" + texture + suffix,
                "hbm:blocks/" + texture + suffix,
                "hbm:rbmk_blank" + suffix
        };
        TextureAtlasSprite sprite = null;
        for (String candidate : candidates) {
            TextureAtlasSprite found = map.getAtlasSprite(candidate);
            if (found != null && !"missingno".equals(found.getIconName())) {
                sprite = found;
                break;
            }
        }
        if (sprite == null) {
            sprite = map.getAtlasSprite("missingno");
        }
        SPRITES.put(key, sprite);
        return sprite;
    }

    private static void box(BufferBuilder buffer, TextureAtlasSprite sprite,
                            float x0, float y0, float z0, float x1, float y1, float z1) {
        float u0 = sprite.getMinU();
        float u1 = sprite.getMaxU();
        float v0 = sprite.getMinV();
        float v1 = sprite.getMaxV();
        int light = 15 << 20 | 15 << 4;

        // Top and bottom: the face a player looking down at the plate sees.
        quad(buffer, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, u0, v0, u1, v1, light);
        quad(buffer, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, u0, v0, u1, v1, light);
        // Sides, with the sprite squeezed into a quarter block of height.
        quad(buffer, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, u0, v0, u1, v1, light);
        quad(buffer, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, u0, v0, u1, v1, light);
        quad(buffer, x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, u0, v0, u1, v1, light);
        quad(buffer, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, u0, v0, u1, v1, light);
    }

    private static void quad(BufferBuilder buffer,
                             float ax, float ay, float az,
                             float bx, float by, float bz,
                             float cx, float cy, float cz,
                             float dx, float dy, float dz,
                             float u0, float v0, float u1, float v1, int light) {
        int lightU = light & 0xFFFF;
        int lightV = light >> 16 & 0xFFFF;
        buffer.pos(ax, ay, az).tex(u0, v1).lightmap(lightU, lightV).color(255, 255, 255, 255).endVertex();
        buffer.pos(bx, by, bz).tex(u0, v0).lightmap(lightU, lightV).color(255, 255, 255, 255).endVertex();
        buffer.pos(cx, cy, cz).tex(u1, v0).lightmap(lightU, lightV).color(255, 255, 255, 255).endVertex();
        buffer.pos(dx, dy, dz).tex(u1, v1).lightmap(lightU, lightV).color(255, 255, 255, 255).endVertex();
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityRbmkLid entity) {
        return TextureMap.LOCATION_BLOCKS_TEXTURE;
    }
}
