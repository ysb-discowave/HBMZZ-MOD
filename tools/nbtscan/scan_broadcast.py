"""Scan every region file of a 1.12.2 world for control-panel node graphs and
report, for each broadcast node, how many input ports its NBT actually carries.

Usage:
    python tools/nbtscan/scan_broadcast.py <world_dir>
"""
import os
import struct
import sys
import zlib

TAG_END, TAG_BYTE, TAG_SHORT, TAG_INT, TAG_LONG = 0, 1, 2, 3, 4
TAG_FLOAT, TAG_DOUBLE, TAG_BYTE_ARRAY, TAG_STRING = 5, 6, 7, 8
TAG_LIST, TAG_COMPOUND, TAG_INT_ARRAY, TAG_LONG_ARRAY = 9, 10, 11, 12


class Reader(object):
    def __init__(self, data):
        self.d = data
        self.i = 0

    def u1(self):
        v = self.d[self.i]
        self.i += 1
        return v

    def u2(self):
        v = struct.unpack_from(">H", self.d, self.i)[0]
        self.i += 2
        return v

    def i4(self):
        v = struct.unpack_from(">i", self.d, self.i)[0]
        self.i += 4
        return v

    def i8(self):
        v = struct.unpack_from(">q", self.d, self.i)[0]
        self.i += 8
        return v

    def f4(self):
        v = struct.unpack_from(">f", self.d, self.i)[0]
        self.i += 4
        return v

    def f8(self):
        v = struct.unpack_from(">d", self.d, self.i)[0]
        self.i += 8
        return v

    def string(self):
        n = self.u2()
        s = self.d[self.i:self.i + n].decode("utf-8", "replace")
        self.i += n
        return s


def read_payload(r, tag):
    if tag == TAG_BYTE:
        return r.u1()
    if tag == TAG_SHORT:
        return r.u2()
    if tag == TAG_INT:
        return r.i4()
    if tag == TAG_LONG:
        return r.i8()
    if tag == TAG_FLOAT:
        return r.f4()
    if tag == TAG_DOUBLE:
        return r.f8()
    if tag == TAG_BYTE_ARRAY:
        n = r.i4()
        v = r.d[r.i:r.i + n]
        r.i += n
        return v
    if tag == TAG_STRING:
        return r.string()
    if tag == TAG_LIST:
        inner = r.u1()
        n = r.i4()
        return [read_payload(r, inner) for _ in range(n)]
    if tag == TAG_COMPOUND:
        out = {}
        while True:
            t = r.u1()
            if t == TAG_END:
                return out
            name = r.string()
            out[name] = read_payload(r, t)
    if tag == TAG_INT_ARRAY:
        n = r.i4()
        v = [r.i4() for _ in range(n)]
        return v
    if tag == TAG_LONG_ARRAY:
        n = r.i4()
        v = [r.i8() for _ in range(n)]
        return v
    raise ValueError("unknown tag %d at %d" % (tag, r.i))


def chunks(path):
    with open(path, "rb") as fh:
        blob = fh.read()
    if len(blob) < 8192:
        return
    locations = blob[:4096]
    for idx in range(1024):
        entry = locations[idx * 4:idx * 4 + 4]
        offset = (entry[0] << 16) | (entry[1] << 8) | entry[2]
        sectors = entry[3]
        if offset == 0 or sectors == 0:
            continue
        start = offset * 4096
        if start + 5 > len(blob):
            continue
        length = struct.unpack_from(">i", blob, start)[0]
        if length <= 0 or start + 4 + length > len(blob):
            continue
        kind = blob[start + 4]
        payload = blob[start + 5:start + 4 + length]
        try:
            if kind == 1:
                data = zlib.decompress(payload, 16 + zlib.MAX_WBITS)
            elif kind == 2:
                data = zlib.decompress(payload)
            else:
                continue
        except zlib.error:
            continue
        yield idx, data


def walk(node, path, hits):
    if isinstance(node, dict):
        if node.get("nodeType") == "eventBroadcast":
            ports = node.get("in") or {}
            names = []
            for i in range(len(ports)):
                con = ports.get("con%d" % i)
                if isinstance(con, dict):
                    names.append(str(con.get("name")))
                else:
                    names.append("<missing con%d>" % i)
            hits.append({
                "event": node.get("eventName"),
                "ports": names,
                "path": path,
            })
        for k, v in node.items():
            walk(v, path + "/" + str(k), hits)
    elif isinstance(node, list):
        for i, v in enumerate(node):
            walk(v, path + "[%d]" % i, hits)


def main():
    world = sys.argv[1]
    total = 0
    for name in sorted(os.listdir(os.path.join(world, "region"))):
        if not name.endswith(".mca"):
            continue
        for idx, data in chunks(os.path.join(world, "region", name)):
            r = Reader(data)
            try:
                t = r.u1()
                if t != TAG_COMPOUND:
                    continue
                r.string()
                root = read_payload(r, TAG_COMPOUND)
            except Exception as exc:  # noqa: BLE001 - a broken chunk is not interesting
                continue
            hits = []
            walk(root, name + "#" + str(idx), hits)
            for hit in hits:
                total += 1
                print("%-14s event=%-22s ports=%s" % (
                    hit["path"].split("/")[0], hit["event"], hit["ports"]))
    print("broadcast nodes found: %d" % total)


if __name__ == "__main__":
    main()
