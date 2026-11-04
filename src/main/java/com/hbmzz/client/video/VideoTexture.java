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

package com.hbmzz.client.video;

import net.minecraft.client.renderer.texture.TextureUtil;

import java.awt.image.BufferedImage;

/**
 * One OpenGL texture that lives for the length of a clip and is rewritten in
 * place for every frame.
 *
 * <p>Not registered with the texture manager on purpose. It has no resource
 * location, nothing else refers to it, and it must die the moment the screen
 * closes - so it is allocated here and deleted here. {@code TextureUtil} is used
 * for the upload because it already does the ARGB-int to BGRA-byte conversion
 * and binds through {@code GlStateManager}; hand-rolling that is how mods end up
 * with red and blue swapped.</p>
 *
 * <p>Every frame of a clip has the same size (the player normalises them), so
 * only the very first upload allocates; the rest are sub-image writes.</p>
 */
public final class VideoTexture {

    private int id = -1;
    private int width;
    private int height;

    public void upload(BufferedImage image) {
        if (image == null) {
            return;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        if (this.id < 0) {
            this.id = TextureUtil.glGenTextures();
            TextureUtil.uploadTextureImageAllocate(this.id, image, true, true);
            this.width = w;
            this.height = h;
        } else if (w == this.width && h == this.height) {
            TextureUtil.uploadTextureImageSub(this.id, image, 0, 0, true, true);
        } else {
            // Only reachable if the player failed to normalise; reallocate
            // rather than draw a torn image.
            TextureUtil.uploadTextureImageAllocate(this.id, image, true, true);
            this.width = w;
            this.height = h;
        }
    }

    public int id() {
        return this.id;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    /** Render thread only. */
    public void delete() {
        if (this.id >= 0) {
            TextureUtil.deleteTexture(this.id);
            this.id = -1;
        }
    }
}
