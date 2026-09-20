import re
import xml.etree.ElementTree as ET

BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def parse(xml_text):
    nodes = []
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return nodes
    for n in root.iter("node"):
        m = BOUNDS_RE.match(n.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        nodes.append(
            {
                "text": n.get("text", "") or "",
                "desc": n.get("content-desc", "") or "",
                "resource": n.get("resource-id", "") or "",
                "package": n.get("package", "") or "",
                "clickable": n.get("clickable", "false") == "true",
                "enabled": n.get("enabled", "true") == "true",
                "bounds": (x1, y1, x2, y2),
                "center": ((x1 + x2) // 2, (y1 + y2) // 2),
            }
        )
    return nodes


def find(nodes, match="text", value="", contains=True, index=0, clickable_only=True):
    hits = []
    for n in nodes:
        if clickable_only and not n["clickable"]:
            continue
        hay = n.get(match) or ""
        if not hay:
            continue
        ok = value in hay if contains else hay == value
        if ok:
            hits.append(n)
    return hits[index] if 0 <= index < len(hits) else None


def node_at(nodes, x, y):
    hit = None
    for n in nodes:
        x1, y1, x2, y2 = n["bounds"]
        if x1 <= x <= x2 and y1 <= y <= y2:
            if not hit or _area(n) <= _area(hit):
                hit = n
    return hit


def _area(n):
    x1, y1, x2, y2 = n["bounds"]
    return (x2 - x1) * (y2 - y1)


def summarize(nodes, limit=60):
    lines = []
    for i, n in enumerate(nodes):
        if not n["text"] and not n["desc"]:
            continue
        click = "clickable" if n["clickable"] else "static"
        lines.append(
            f"#{i} text={n['text']!r} desc={n['desc']!r} center={n['center']} {click}"
        )
        if len(lines) >= limit:
            break
    return "\n".join(lines)
