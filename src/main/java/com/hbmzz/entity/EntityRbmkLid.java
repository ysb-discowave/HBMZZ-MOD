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

package com.hbmzz.entity;

import com.hbmzz.rbmk.RbmkTiming;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityFallingBlock;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;

import java.util.ArrayList;
import java.util.List;

/**
 * The lid, after the reactor has finished with it.
 *
 * <p>A rigid plate made of every lid the reactor had, thrown by the first
 * explosion, coming back down onto the core, thrown again by the second one,
 * and finally lying where it lands with the reactor's remains smoking around
 * it. The shape of the accident, not a single bang: the plate is what makes it
 * read as one machine coming apart rather than as a block update.</p>
 *
 * <p>The plate is not a falling block. A falling block establishes itself as a
 * real block when it lands, and an RBMK column re-established at ground level
 * would be a live reactor column - a new reactor, born from rubble, with a
 * working tile entity. This one exists only as an entity, and the world is
 * never modified by the landing.</p>
 *
 * <p>Lids are described by a compact string rather than a block list because
 * the client has to be able to build the plate from the spawn packet alone:
 * {@code texture|texture#dx,dz,lid,textureIndex;...}. Occupied cells are whole
 * blocks; the plate keeps the arrangement they had on the reactor.</p>
 */
public class EntityRbmkLid extends Entity implements IEntityAdditionalSpawnData {

    private static final DataParameter<String> LAYOUT =
            EntityDataManager.createKey(EntityRbmkLid.class, DataSerializers.STRING);
    private static final DataParameter<Boolean> SETTLED =
            EntityDataManager.createKey(EntityRbmkLid.class, DataSerializers.BOOLEAN);
    private static final DataParameter<Float> SPIN =
            EntityDataManager.createKey(EntityRbmkLid.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> YAW =
            EntityDataManager.createKey(EntityRbmkLid.class, DataSerializers.FLOAT);
    private static final DataParameter<Float> PITCH =
            EntityDataManager.createKey(EntityRbmkLid.class, DataSerializers.FLOAT);
    private static final DataParameter<Integer> AGE =
            EntityDataManager.createKey(EntityRbmkLid.class, DataSerializers.VARINT);

    /** Launches so far. Two is the whole script; three only on a bad landing. */
    private int launches;
    /** Counts up while flying, and again from zero once it has settled. */
    private int age;
    private boolean settling;
    private int settledFor;
    private float spinSpeed;
    private String pendingLayout = "";

    public EntityRbmkLid(World world) {
        super(world);
        this.noClip = true;
        this.preventEntitySpawning = false;
        this.setSize(1.0F, 0.25F);
    }

    public EntityRbmkLid(World world, double x, double y, double z, String layout) {
        this(world);
        this.pendingLayout = layout == null ? "" : layout;
        setPosition(x, y, z);
        this.motionX = 0.0D;
        this.motionY = RbmkTiming.LID_LAUNCH_VY;
        this.motionZ = 0.0D;
        this.spinSpeed = (this.rand.nextBoolean() ? 1.0F : -1.0F) * (1.2F + this.rand.nextFloat());
    }

    @Override
    protected void entityInit() {
        this.dataManager.register(LAYOUT, "");
        this.dataManager.register(SETTLED, Boolean.FALSE);
        this.dataManager.register(SPIN, Float.valueOf(0.0F));
        this.dataManager.register(YAW, Float.valueOf(0.0F));
        this.dataManager.register(PITCH, Float.valueOf(0.0F));
        this.dataManager.register(AGE, Integer.valueOf(0));
    }

    /**
     * Told by the spawn packet, before the first tick on the client.
     *
     * <p>Kept separate from {@code readEntityFromNBT} because a spawn packet
     * carries no NBT: without this the plate would arrive on the client as an
     * empty string and render nothing at all, which looks exactly like the
     * feature being broken.</p>
     */
    @Override
    public void readSpawnData(ByteBuf buffer) {
        int length = Math.min(buffer.readableBytes(), 8192);
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        if (!this.world.isRemote) {
            return;
        }
        String layout = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        this.pendingLayout = layout;
        this.dataManager.set(LAYOUT, layout);
    }

    @Override
    public void writeSpawnData(ByteBuf buffer) {
        byte[] bytes = this.dataManager.get(LAYOUT).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buffer.writeBytes(bytes, 0, Math.min(bytes.length, 8192));
    }

    @Override
    public void onUpdate() {
        // Entity.onUpdate copies the previous position/rotation and bumps
        // ticksExisted, which is exactly what the renderer interpolates with.
        super.onUpdate();

        if (!this.dataManager.get(LAYOUT).equals(this.pendingLayout) && !this.pendingLayout.isEmpty()) {
            this.dataManager.set(LAYOUT, this.pendingLayout);
        }

        if (this.world.isRemote) {
            // Client: follow the synced yaw and tumble. The pose is decided on
            // the server precisely so two players watching the same plate see
            // the same plate.
            this.rotationYaw = this.dataManager.get(YAW).floatValue();
            this.rotationPitch = this.dataManager.get(PITCH).floatValue();
            return;
        }

        if (this.settling) {
            tickSettled();
            return;
        }

        try {
            tickFlight();
        } catch (Throwable broken) {
            // The plate is scenery with a blast attached. If its own tick is
            // broken the honest outcome is a plate that vanishes and a line in
            // the log, not a server that stops ticking.
            report("flight", broken);
            setDead();
        }
    }

    private void tickFlight() {
        this.age++;
        this.motionY -= RbmkTiming.LID_GRAVITY;
        this.motionX *= RbmkTiming.LID_DRAG;
        this.motionZ *= RbmkTiming.LID_DRAG;
        this.motionY *= RbmkTiming.LID_DRAG_Y;

        this.posX += this.motionX;
        this.posY += this.motionY;
        this.posZ += this.motionZ;
        this.rotationYaw += this.spinSpeed;
        this.rotationPitch = MathHelper.clamp(this.rotationPitch + this.spinSpeed * 0.35F, -40.0F, 40.0F);
        syncPose();

        if (this.age % 3 == 0) {
            // A lid that size moving that fast leaves a trail whether or not it
            // is on fire; without it the plate reads as a floating texture.
            this.world.spawnParticle(EnumParticleTypes.SMOKE_LARGE,
                    this.posX + (this.rand.nextDouble() - 0.5D) * 3.0D,
                    this.posY + 0.2D,
                    this.posZ + (this.rand.nextDouble() - 0.5D) * 3.0D,
                    0.0D, 0.05D, 0.0D);
        }

        if (this.age > RbmkTiming.FLIGHT_LIMIT) {
            land();
            return;
        }
        double top = groundTop();
        if (top != Double.NEGATIVE_INFINITY && this.motionY <= 0.0D && this.posY <= top + 0.35D) {
            this.posY = top + 0.02D;
            land();
        }
    }

    private void tickSettled() {
        this.settledFor++;
        if (this.settledFor % 4 == 0) {
            this.world.spawnParticle(EnumParticleTypes.SMOKE_NORMAL,
                    this.posX + (this.rand.nextDouble() - 0.5D) * 4.0D,
                    this.posY + 0.4D,
                    this.posZ + (this.rand.nextDouble() - 0.5D) * 4.0D,
                    0.0D, 0.06D, 0.0D);
        }
        if (this.settledFor >= RbmkTiming.REST_TICKS) {
            this.world.spawnParticle(EnumParticleTypes.EXPLOSION_NORMAL, this.posX, this.posY, this.posZ, 0.0D, 0.0D, 0.0D);
            setDead();
        }
    }

    /**
     * Touches down. First landing answers, second one just stops.
     *
     * <p>The second blast is fired <em>by the landing</em>, not on a timer: the
     * lid is what hits the core. That is also why the relaunch is stronger -
     * the plate is being pushed off a core that is exploding into it, not by
     * the initial blast that had to lift it against gravity alone.</p>
     */
    private void land() {
        this.motionX = 0.0D;
        this.motionY = 0.0D;
        this.motionZ = 0.0D;
        this.launches++;
        if (this.launches == 1) {
            blast(9.0F, 3);
            this.motionY = RbmkTiming.launchVy(true);
            this.motionX = (this.rand.nextDouble() - 0.5D) * 0.12D;
            this.motionZ = (this.rand.nextDouble() - 0.5D) * 0.12D;
            this.spinSpeed = (this.rand.nextBoolean() ? 1.0F : -1.0F) * (1.8F + this.rand.nextFloat());
            this.rotationPitch = 0.0F;
            this.age = 0;
            syncPose();
            playAt(net.minecraft.init.SoundEvents.ENTITY_GENERIC_EXPLODE, 4.0F, 0.55F);
            return;
        }
        blast(4.0F, 1);
        playAt(net.minecraft.init.SoundEvents.ENTITY_GENERIC_EXPLODE, 3.0F, 0.7F);
        this.settling = true;
        this.settledFor = 0;
        this.dataManager.set(SETTLED, Boolean.TRUE);
        this.rotationPitch = 0.0F;
        syncPose();
        com.hbmzz.rbmk.RbmkSurge.onLidSettled(this);
    }

    private void blast(float power, int breaks) {
        this.world.createExplosion(null, this.posX, this.posY + 0.5D, this.posZ, power, true);
        if (breaks <= 0) {
            return;
        }
        // Vanilla explosions do not throw blocks; the debris is what sells the
        // weight of the thing. A handful, not a crater's worth.
        int count = 6 + this.rand.nextInt(4);
        for (int i = 0; i < count; i++) {
            BlockPos at = new BlockPos(this.posX + (this.rand.nextDouble() - 0.5D) * 3.0D,
                    this.posY + this.rand.nextDouble() * 2.0D,
                    this.posZ + (this.rand.nextDouble() - 0.5D) * 3.0D);
            if (this.world.isAirBlock(at)) {
                continue;
            }
            net.minecraft.block.state.IBlockState state = this.world.getBlockState(at);
            if (state.getBlockHardness(this.world, at) < 0.0F) {
                continue;
            }
            // A falling block *places itself* when it lands. That is fine for
            // stone and lethal for a machine: an RBMK column dropped on the
            // ground is a working reactor column with a fresh tile entity, born
            // out of rubble. So nothing with a tile entity is ever thrown.
            if (state.getBlock().hasTileEntity(state) || this.world.getTileEntity(at) != null) {
                continue;
            }
            this.world.setBlockToAir(at);
            EntityFallingBlock falling = new EntityFallingBlock(this.world, at.getX() + 0.5D, at.getY() + 0.5D, at.getZ() + 0.5D, state);
            falling.motionY = 0.35D + this.rand.nextDouble() * 0.4D;
            falling.motionX = (this.rand.nextDouble() - 0.5D) * 0.4D;
            falling.motionZ = (this.rand.nextDouble() - 0.5D) * 0.4D;
            this.world.spawnEntity(falling);
            breaks--;
            if (breaks <= 0) {
                return;
            }
        }
    }

    /** One line in the log with the whole stack; never a crash. */
    private static void report(String where, Throwable broken) {
        if (com.hbmzz.HBMZZ.logger != null) {
            com.hbmzz.HBMZZ.logger.error("RBMK lid " + where + " failed; the plate is removed", broken);
        }
    }

    private void playAt(SoundEvent sound, float volume, float pitch) {
        this.world.playSound((EntityPlayer) null, this.posX, this.posY, this.posZ, sound, SoundCategory.BLOCKS, volume, pitch);
    }

    /**
     * Highest solid surface under the plate's footprint, or negative infinity
     * while it is still over open air.
     *
     * <p>Sampled at five points rather than one: a plate three blocks wide whose
     * centre happens to be over a hole should still stop on its edge, and a
     * plate that stops only when its centre reaches the ground sinks halfway
     * into a hill.</p>
     */
    private double groundTop() {
        double best = Double.NEGATIVE_INFINITY;
        int[][] spread = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] offset : spread) {
            BlockPos probe = new BlockPos(this.posX + offset[0], this.posY - 0.1D, this.posZ + offset[1]);
            if (!this.world.isBlockLoaded(probe)) {
                continue;
            }
            for (int depth = 0; depth < 6; depth++) {
                BlockPos at = probe.down(depth);
                net.minecraft.block.state.IBlockState state = this.world.getBlockState(at);
                if (state.getBlock().isAir(state, this.world, at)) {
                    continue;
                }
                net.minecraft.util.math.AxisAlignedBB box = state.getCollisionBoundingBox(this.world, at);
                if (box == null || box.maxY <= 0.0D) {
                    continue;
                }
                best = Math.max(best, at.getY() + box.maxY);
                break;
            }
        }
        return best;
    }

    private void syncPose() {
        this.dataManager.set(YAW, Float.valueOf(this.rotationYaw));
        this.dataManager.set(PITCH, Float.valueOf(this.rotationPitch));
        this.dataManager.set(SPIN, Float.valueOf(this.spinSpeed));
        this.dataManager.set(AGE, Integer.valueOf(this.age));
    }

    public String layout() {
        return this.dataManager.get(LAYOUT);
    }


    public boolean isSettled() {
        return this.dataManager.get(SETTLED).booleanValue();
    }

    /**
     * How far the plate is allowed to be seen from.
     *
     * <p>Default range is a function of the entity's bounding box, which for a
     * flat lid is a couple of blocks - the plate would pop out of existence a
     * few steps away from the player watching it fall.</p>
     */
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(this.posX - 24.0D, this.posY - 8.0D, this.posZ - 24.0D,
                this.posX + 24.0D, this.posY + 40.0D, this.posZ + 24.0D);
    }

    @Override
    public boolean isInRangeToRenderDist(double distance) {
        return distance < 256.0D * 256.0D;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {
        compound.setString("Layout", this.dataManager.get(LAYOUT));
        compound.setBoolean("Settled", this.settling);
        compound.setInteger("Launches", this.launches);
        compound.setInteger("SettledFor", this.settledFor);
        compound.setFloat("Spin", this.spinSpeed);
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {
        String layout = compound.getString("Layout");
        this.pendingLayout = layout;
        this.dataManager.set(LAYOUT, layout);
        this.settling = compound.getBoolean("Settled");
        this.dataManager.set(SETTLED, Boolean.valueOf(this.settling));
        this.launches = compound.getInteger("Launches");
        this.settledFor = compound.getInteger("SettledFor");
        this.spinSpeed = compound.getFloat("Spin");
    }
}
