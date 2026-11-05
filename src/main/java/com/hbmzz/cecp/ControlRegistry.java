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

import com.hbmzz.cecp.controls.*;
import com.hbmzz.cecp.controls.addon.*;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class ControlRegistry {
	
	public static Map<String, Control> registry = new Object2ObjectLinkedOpenHashMap<>();

	public static List<Control> addonControls = new ArrayList<>();

	private ControlRegistry(){
	}
	
	public static void init(){
		registry.put("button_push", new ButtonPush("按钮","button_push",null));
		registry.put("button_emergency_push",new ButtonEmergencyPush("急停按钮","button_emergency_push",null));
		registry.put("button_encased_push",new ButtonEncasedPush("防护按钮","button_encased_push",null));

		registry.put("switch_toggle",new SwitchToggle("拨动开关","switch_toggle",null));
		registry.put("switch_rotary_toggle",new SwitchRotaryToggle("旋转开关","switch_rotary_toggle",null));

		registry.put("slider_vertical",new SliderVertical("垂直滑条","slider_vertical",null));

		registry.put("knob_control",new KnobControl("旋钮","knob_control",null));

		registry.put("display_7seg",new DisplaySevenSeg("七段数码","display_7seg",null));
		registry.put("display_text",new DisplayText("文字屏","display_text",null));

		registry.put("dial_square",new DialSquare("方形仪表","dial_square",null));
		registry.put("dial_large",new DialLarge("圆形仪表","dial_large",null));

		registry.put("indicator_lamp",new IndicatorLamp("指示灯","indicator_lamp",null));

		registry.put("indicator_lamp_rgb",new IndicatorLampRGB("指示灯 (RGB)","indicator_lamp_rgb",null));

		registry.put("label",new Label("标签","label",null));

		initAddonControls();

		for (Control control : addonControls)
			registry.put(control.registryName,control);
	}

	/**
	 * Instruments the addon registers on top of CE's list.
	 *
	 * <p>Registry names are the addon's own ({@code leafia_nixie},
	 * {@code leafia_lever_h}, …) so a panel saved with the addon installed
	 * resolves to these instead of falling back to UnknownControl. Display names
	 * are translated, like the rest of this list.</p>
	 */
	private static void initAddonControls(){
		if(!addonControls.isEmpty())
			return;
		// Before anything is added: the addon's control types live in a class
		// whose static initialiser appends to ControlType's list. Left lazy, that
		// append happens the first time the console editor walks the type list -
		// and the walk dies with a ConcurrentModificationException. See
		// AddonControlType.register().
		AddonControlType.register();
		addonControls.add(new NixieDisplay("辉光数码管","leafia_nixie",null));
		addonControls.add(new LargeSwitch("大型拨杆开关","leafia_sbswitch",null));
		addonControls.add(new LargeButton("大型按钮","leafia_sbbutton",null));
		addonControls.add(new TextIndicator("文字指示牌","leafia_textindicator",null));
		addonControls.add(new RBMKDisplay("RBMK 阵列","leafia_rbmk",null));
		addonControls.add(new Graph("图表","leafia_graph",null));
		addonControls.add(new MeterHorizontal("水平仪表","leafia_meter_h",null));
		addonControls.add(new MeterVertical("竖直仪表","leafia_meter_v",null));
		addonControls.add(new Drawing("方框标签","leafia_drawing_box",null));
		addonControls.add(new LeverHorizontal("水平拨杆","leafia_lever_h",null));
		addonControls.add(new LeverVertical("竖直拨杆","leafia_lever_v",null));
	}
	
	public static List<Control> getAllControls(){
		List<Control> l = new ArrayList<>(registry.size());
		for(Control c : registry.values())
			l.add(c);
		return l;
	}

	public static List<String> getAllControlsOfType(ControlType type) {
		List<String> l = new ArrayList<>();
		for (Entry<String, Control> c : registry.entrySet())
			if (c.getValue().getControlType() == type) {
				l.add(c.getKey());
			}
		return l;
	}

	public static Control getNew(String name, ControlPanel panel){
		Control control = registry.get(name);
		return control != null ? control.newControl(panel) : null;
	}

	public static boolean isRegistered(String name) {
		return registry.containsKey(name);
	}
}
