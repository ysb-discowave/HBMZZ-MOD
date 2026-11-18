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

package com.hbmzz.cecp.nodes.addon;

import com.hbmzz.cecp.NodeConnection;
import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.NodeTextBox;
import com.hbmzz.cecp.modular.StockNodesRegister;
import com.hbmzz.cecp.nodes.Node;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValue.DataType;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import net.minecraft.nbt.NBTTagCompound;

import java.util.ArrayList;
import java.util.IllegalFormatException;
import java.util.List;

/**
 * {@code String.format} as a node.
 *
 * <p>Ported from CE Addon's {@code NodeFormat}. The format string is typed in,
 * and every {@code %s} / {@code %d} in it becomes an input port, so the node's
 * shape follows the text: editing the format rebuilds the ports in place. A
 * format that does not parse answers {@code ERROR} instead of throwing into the
 * graph.</p>
 */
public class NodeFormat extends Node {

    String format = "%s";

    public NodeFormat(float x, float y) {
        super(x, y);
        this.outputs.add(new NodeRetarded("Output", this, this.outputs.size(), false, DataType.STRING, new DataValueString("")));
        this.otherElements.add(new NodeTextBox("Format", this, this.otherElements.size(), this.format, s -> {
            this.format = s;
            this.updateFormat();
        }));
        this.evalCache = new DataValue[1];
        this.updateFormat();
        this.recalcSize();
    }

    /** Rebuilds the input ports from the placeholders in {@link #format}. */
    public void updateFormat() {
        for (NodeConnection c : this.inputs) {
            c.removeConnection();
        }
        this.inputs.clear();
        int argNum = 0;
        int i = 0;
        while (i < this.format.length()) {
            i = this.format.indexOf("%", i);
            if (i == -1 || i + 1 >= this.format.length()) {
                break;
            }
            boolean readNext = true;
            while (readNext) {
                readNext = false;
                String type = this.format.substring(i + 1, i + 2);
                switch (type) {
                    case "$":
                    case " ":
                    case "-":
                    case "#":
                    case "+":
                    case ".":
                    case ",":
                    case "(":
                    case "0":
                    case "1":
                    case "2":
                    case "3":
                    case "4":
                    case "5":
                    case "6":
                    case "7":
                    case "8":
                    case "9":
                        i++;
                        readNext = true;
                        break;
                    case "s":
                    case "S":
                        this.inputs.add(new NodeConnection(String.valueOf(++argNum), this, this.inputs.size(), true,
                                DataType.STRING, new DataValueString("")));
                        break;
                    case "d":
                    case "o":
                    case "x":
                    case "X":
                    case "e":
                    case "E":
                    case "f":
                    case "g":
                    case "G":
                    case "a":
                    case "A":
                        this.inputs.add(new NodeConnection(String.valueOf(++argNum), this, this.inputs.size(), true,
                                DataType.NUMBER, new DataValueFloat(0)));
                        break;
                    default:
                        break;
                }
            }
            i += 2;
        }
        this.recalcSize();
    }

    @Override
    public DataValue evaluate(int idx) {
        if (this.cacheValid) {
            return this.evalCache[0];
        }
        this.cacheValid = true;
        List<Object> argsList = new ArrayList<>();
        for (NodeConnection input : this.inputs) {
            DataValue value = input.evaluate();
            if (value instanceof DataValueFloat) {
                argsList.add(value.getNumber());
            } else if (value instanceof DataValueString) {
                argsList.add(value.toString());
            }
        }
        Object[] args = argsList.toArray();
        String output = "ERROR";
        try {
            output = String.format(this.format, args);
        } catch (IllegalFormatException ignored) {
            // Kept as ERROR; a bad format must not take the graph down with it.
        }
        return this.evalCache[0] = new DataValueString(output);
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorText;
    }

    @Override
    public String getDisplayName() {
        return "格式化";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "format");
        tag.setString("format", this.format);
        return super.writeToNBT(tag, sys);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag, NodeSystem sys) {
        this.format = tag.getString("format");
        super.readFromNBT(tag, sys);
    }
}
