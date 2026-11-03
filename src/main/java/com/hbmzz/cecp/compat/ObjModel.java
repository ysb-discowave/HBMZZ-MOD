/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM: Cursed Edition's com.hbm.inventory.control_panel;
 * see NOTICE.md for the list of changes.
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

package com.hbmzz.cecp.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wavefront OBJ loader for the control-panel models.
 *
 * <p>NTM: Cursed Edition renders these through {@code WaveFrontObjectVAO}, which
 * bakes every group into its own VAO and only uploads them during a loading
 * screen. Controls are cheap models drawn a few dozen times per frame, so this
 * port reads the same {@code .obj} text and replays it through the vanilla
 * buffer instead - no VAO bookkeeping, no upload phase, same geometry and the
 * same {@code renderPart("group")} behaviour the controls rely on.</p>
 *
 * <p>Model data comes from NTM: Cursed Edition (assets/hbm/models/control_panel).</p>
 */
@SideOnly(Side.CLIENT)
public class ObjModel implements IModelCustom {

    private static final class Face {
        final float[] positions;
        final float[] uv;
        final float[] normals;

        Face(float[] positions, float[] uv, float[] normals) {
            this.positions = positions;
            this.uv = uv;
            this.normals = normals;
        }
    }

    private final Map<String, List<Face>> groups = new LinkedHashMap<>();
    private final ResourceLocation source;

    public ObjModel(ResourceLocation source) {
        this.source = source;
        load();
    }

    @Override
    public String getType() {
        return "obj";
    }

    private void load() {
        List<float[]> v = new ArrayList<>();
        List<float[]> vt = new ArrayList<>();
        List<float[]> vn = new ArrayList<>();
        String group = "default";
        try {
            InputStream in = Minecraft.getMinecraft().getResourceManager().getResource(this.source).getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] tok = line.split("\\s+");
                if (tok[0].equals("v")) {
                    v.add(new float[]{f(tok[1]), f(tok[2]), f(tok[3])});
                } else if (tok[0].equals("vt")) {
                    // OBJ's V axis points up, Minecraft's texture V axis points
                    // down, so the coordinate has to be flipped here. Skip this
                    // and every part samples the texture's empty (white) corner
                    // instead of its artwork: the whole control turns white and
                    // the small parts (levers, button caps, slider handles) end
                    // up pure white, i.e. invisible on the light panel.
                    // Forge's WavefrontObject does the same 1 - v.
                    float texV = tok.length > 2 ? f(tok[2]) : 0.0F;
                    vt.add(new float[]{f(tok[1]), 1.0F - texV});
                } else if (tok[0].equals("vn")) {
                    vn.add(new float[]{f(tok[1]), f(tok[2]), f(tok[3])});
                } else if (tok[0].equals("g") || tok[0].equals("o")) {
                    group = tok.length > 1 ? tok[1].toLowerCase(Locale.ROOT) : "default";
                } else if (tok[0].equals("f")) {
                    addFace(v, vt, vn, group, tok);
                }
            }
            reader.close();
        } catch (Exception e) {
            com.hbmzz.cecp.compat.CPEnv.logger.error("Failed to load control panel model {}", this.source, e);
        }
    }

    private static float f(String s) {
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return 0.0F;
        }
    }

    private void addFace(List<float[]> v, List<float[]> vt, List<float[]> vn, String group, String[] tok) {
        int n = tok.length - 1;
        if (n < 3) {
            return;
        }
        int[] vi = new int[n];
        int[] ti = new int[n];
        int[] ni = new int[n];
        for (int i = 0; i < n; i++) {
            String[] parts = tok[i + 1].split("/", -1);
            vi[i] = resolve(parts[0], v.size());
            ti[i] = parts.length > 1 && !parts[1].isEmpty() ? resolve(parts[1], vt.size()) : -1;
            ni[i] = parts.length > 2 && !parts[2].isEmpty() ? resolve(parts[2], vn.size()) : -1;
        }
        // Fan-triangulate so quads and n-gons both end up as triangles.
        for (int i = 1; i < n - 1; i++) {
            float[] p = new float[9];
            float[] t = new float[6];
            float[] nr = new float[9];
            int[] idx = {0, i, i + 1};
            for (int k = 0; k < 3; k++) {
                float[] pv = v.get(vi[idx[k]]);
                p[k * 3] = pv[0];
                p[k * 3 + 1] = pv[1];
                p[k * 3 + 2] = pv[2];
                if (ti[idx[k]] >= 0) {
                    float[] tv = vt.get(ti[idx[k]]);
                    t[k * 2] = tv[0];
                    t[k * 2 + 1] = tv[1];
                }
                if (ni[idx[k]] >= 0) {
                    float[] nv = vn.get(ni[idx[k]]);
                    nr[k * 3] = nv[0];
                    nr[k * 3 + 1] = nv[1];
                    nr[k * 3 + 2] = nv[2];
                }
            }
            List<Face> list = this.groups.get(group);
            if (list == null) {
                list = new ArrayList<>();
                this.groups.put(group, list);
            }
            list.add(new Face(p, t, nr));
        }
    }

    private static int resolve(String token, int size) {
        int i = Integer.parseInt(token);
        return i < 0 ? size + i : i - 1;
    }

    private void draw(List<Face> faces) {
        if (faces == null || faces.isEmpty()) {
            return;
        }
        CtrlImm.INSTANCE.ensureIdle();
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_TEX_NORMAL);
        for (Face face : faces) {
            for (int k = 0; k < 3; k++) {
                buf.pos(face.positions[k * 3], face.positions[k * 3 + 1], face.positions[k * 3 + 2])
                        .tex(face.uv[k * 2], face.uv[k * 2 + 1])
                        .normal(face.normals[k * 3], face.normals[k * 3 + 1], face.normals[k * 3 + 2])
                        .endVertex();
            }
        }
        tess.draw();
    }

    @Override
    public void renderAll() {
        for (List<Face> list : this.groups.values()) {
            draw(list);
        }
    }

    @Override
    public void renderOnly(String... groupNames) {
        for (String name : groupNames) {
            draw(this.groups.get(name.toLowerCase(Locale.ROOT)));
        }
    }

    @Override
    public void renderPart(String partName) {
        draw(this.groups.get(partName.toLowerCase(Locale.ROOT)));
    }

    @Override
    public void renderAllExcept(String... excludedGroupNames) {
        List<String> skip = new ArrayList<>();
        for (String name : excludedGroupNames) {
            skip.add(name.toLowerCase(Locale.ROOT));
        }
        for (Map.Entry<String, List<Face>> e : this.groups.entrySet()) {
            if (!skip.contains(e.getKey())) {
                draw(e.getValue());
            }
        }
    }
}
