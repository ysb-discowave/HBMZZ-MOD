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

package com.hbmzz.cecp;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;
import net.minecraftforge.fml.common.gameevent.TickEvent.WorldTickEvent;

import java.util.Collection;
import java.util.Map;

@Mod.EventBusSubscriber(modid = "hbmzz")
public class ControlEventSystem {

	private static final Map<World, ControlEventSystem> systems = new Reference2ObjectOpenHashMap<>();

	private final World world;
	private final ObjectOpenHashSet<IControllable> allControllables = new ObjectOpenHashSet<>();
	private final ObjectOpenHashSet<IControllable> tickables = new ObjectOpenHashSet<>();
	private final Map<String, Long2ObjectOpenHashMap<IControllable>> controllablesByEventName = new Object2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<ObjectOpenHashSet<IControllable>> positionSubscriptions = new Long2ObjectOpenHashMap<>();

	private ControlEventSystem(World world) {
		this.world = world;
	}
	
	public void addControllable(IControllable c){
		if(allControllables.contains(c))
			return;
        long controlPos = c.getControlPos().toLong();
		for(String s : c.getInEvents()){
			if(s.equals("tick")){
				tickables.add(c);
				continue;
			}
			Long2ObjectOpenHashMap<IControllable> map = controllablesByEventName.get(s);
			if(map == null){
				map = new Long2ObjectOpenHashMap<>();
				controllablesByEventName.put(s, map);
			}
			map.put(controlPos, c);
		}
		allControllables.add(c);
	}
	
	public void removeControllable(IControllable c){
        long controlPos = c.getControlPos().toLong();
		for(String s : c.getInEvents()){
			if(s.equals("tick")){
				tickables.remove(c);
				continue;
			}
			Long2ObjectOpenHashMap<IControllable> map = controllablesByEventName.get(s);
			if(map != null) {
				map.remove(controlPos);
				if(map.isEmpty()) {
					controllablesByEventName.remove(s);
				}
			}
		}
		// Keep subscribers for this target position so they survive ordinary unload/reload cycles.
		// Only remove the unloaded controllable from lists where it was itself the subscriber.
		java.util.List<Long> emptySubscriptions = new java.util.ArrayList<>();
		for(Long2ObjectOpenHashMap.Entry<ObjectOpenHashSet<IControllable>> entry : positionSubscriptions.long2ObjectEntrySet()) {
			ObjectOpenHashSet<IControllable> subscribers = entry.getValue();
			subscribers.remove(c);
			if(subscribers.isEmpty()) {
				emptySubscriptions.add(entry.getLongKey());
			}
		}
		// Collected first: removing while iterating would trip fastutil's modification check.
		for(Long key : emptySubscriptions) {
			positionSubscriptions.remove(key.longValue());
		}
		allControllables.remove(c);
	}
	
	public boolean isValid(IControllable c){
		return allControllables.contains(c);
	}
	
	public void subscribeTo(IControllable subscriber, IControllable target){
		subscribeTo(subscriber, target.getControlPos());
	}
	
	public void subscribeTo(IControllable subscriber, BlockPos target){
        long serializedTarget = target.toLong();
		ObjectOpenHashSet<IControllable> subscribers = positionSubscriptions.get(serializedTarget);
		if(subscribers == null){
			subscribers = new ObjectOpenHashSet<>();
			positionSubscriptions.put(serializedTarget, subscribers);
		}
		subscribers.add(subscriber);
	}
	
	public void unsubscribeFrom(IControllable subscriber, IControllable target){
		unsubscribeFrom(subscriber, target.getControlPos());
	}
	
	public void unsubscribeFrom(IControllable subscriber, BlockPos target){
        long serializedTarget = target.toLong();
		ObjectOpenHashSet<IControllable> subscribers = positionSubscriptions.get(serializedTarget);
		if(subscribers != null){
			subscribers.remove(subscriber);
			if(subscribers.isEmpty()) {
				positionSubscriptions.remove(serializedTarget);
			}
		}
	}
	
	public void broadcastEvent(BlockPos from, ControlEvent evt, BlockPos pos){
		deliver(from, evt, pos);
	}
	
	public void broadcastEvent(BlockPos from, ControlEvent evt, Collection<BlockPos> positions){
		if(positions == null){
			Long2ObjectOpenHashMap<IControllable> map = controllablesByEventName.get(evt.name);
			if(map == null)
				return;
			for(IControllable c : map.values()){
				c.receiveEvent(from, evt);
			}
			return;
		}
		// One position at a time, through the same door as the single-target
		// overload. Bailing out here because the local table has no entry for
		// this event name is what made the panel look wired up but do nothing:
		// the only thing this console ever registers is "tick", so that table is
		// always empty and every broadcast to a linked machine stopped right at
		// this line. NTM machines live in another registry entirely and reach
		// this method through CPMachines, which the loop below applies.
		for(BlockPos pos : positions){
			deliver(from, evt, pos);
		}
	}
	
	/**
	 * Hands one event to whatever is at {@code pos}: this mod's own controllable
	 * first, and an NTM machine from the other mod's registry second.
	 */
	private void deliver(BlockPos from, ControlEvent evt, BlockPos pos){
		if(pos == null)
			return;
		Long2ObjectOpenHashMap<IControllable> map = controllablesByEventName.get(evt.name);
		IControllable c = map == null ? null : map.get(pos.toLong());
		if(c == null) {
			// NTM machines never register here - they register into their own
			// (Cursed Edition's) event system, which knows nothing about this
			// panel. Without this fallback every event aimed at a linked RBMK rod
			// or door was accepted by the node editor and then delivered to
			// nobody, so the panel looked connected and did nothing.
			c = com.hbmzz.cecp.compat.CPMachines.at(this.world, pos);
		}
		if(c != null) {
			c.receiveEvent(from, evt);
		}
	}
	
	public void broadcastEvent(BlockPos from, ControlEvent c){
		broadcastEvent(from, c, (Collection<BlockPos>)null);
	}
	
	public void broadcastToSubscribed(IControllable ctrl, ControlEvent evt){
        ObjectOpenHashSet<IControllable> subscribed = positionSubscriptions.get(ctrl.getControlPos().toLong());
		if(subscribed == null)
			return;
		for(IControllable sub : subscribed){
			sub.receiveEvent(ctrl.getControlPos(), evt);
		}
	}
	
	public static ControlEventSystem get(World w){
		ControlEventSystem system = systems.get(w);
		if(system == null) {
			system = new ControlEventSystem(w);
			systems.put(w, system);
		}
		return system;
	}
	
	@SubscribeEvent
	public static void tick(WorldTickEvent evt){
		if(evt.phase != Phase.START || evt.world.isRemote)
			return;
		ControlEventSystem s = systems.get(evt.world);
		if(s != null){
			for(IControllable c : s.tickables){
				c.receiveEvent(c.getControlPos(), ControlEvent.newEvent("tick").setVar("time", evt.world.getTotalWorldTime()));
			}
		}
	}
	
	@SubscribeEvent
	public static void worldUnload(WorldEvent.Unload evt){
		systems.remove(evt.getWorld());
	}
}
