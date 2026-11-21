"""Builds app/src/main/assets/countries.geojson from Natural Earth 1:10m Admin 0 - Countries.

Usage:
    python scripts/build_countries.py ne_10m_admin_0_countries.geojson app/src/main/assets/countries.geojson

Download the source from
https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_0_countries.geojson

The 1:10m scale includes small islands (for example Praslin and La Digue in Seychelles)
that the 1:50m scale omits. Rings are simplified with Douglas-Peucker so the asset stays
small; tiny islands keep their original points so they never collapse.
"""
import json
import sys

TOLERANCE = 0.006  # degrees, about 650 m
SMALL_RING = 0.02   # rings with a smaller bounding box keep all their points
DECIMALS = 3        # about 110 m


def perpendicular(point, start, end):
    (x, y), (x1, y1), (x2, y2) = point, start, end
    dx, dy = x2 - x1, y2 - y1
    if dx == 0 and dy == 0:
        return ((x - x1) ** 2 + (y - y1) ** 2) ** 0.5
    t = max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)))
    return ((x - x1 - t * dx) ** 2 + (y - y1 - t * dy) ** 2) ** 0.5


def simplify(points, tolerance):
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        first, last = stack.pop()
        index, distance = 0, 0.0
        for i in range(first + 1, last):
            d = perpendicular(points[i], points[first], points[last])
            if d > distance:
                index, distance = i, d
        if distance > tolerance:
            keep[index] = True
            stack.append((first, index))
            stack.append((index, last))
    return [p for p, k in zip(points, keep) if k]


def ring(points):
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    small = max(xs) - min(xs) < SMALL_RING and max(ys) - min(ys) < SMALL_RING
    reduced = points if small else simplify(points, TOLERANCE)
    if len(reduced) < 4:
        reduced = points
    rounded = [[round(x, DECIMALS), round(y, DECIMALS)] for x, y in reduced]
    deduplicated = [p for i, p in enumerate(rounded) if i == 0 or p != rounded[i - 1]]
    return deduplicated if len(deduplicated) >= 4 else rounded


def polygon(rings):
    return [ring(r) for r in rings]


def main(source, target):
    with open(source, encoding="utf-8") as handle:
        document = json.load(handle)
    features = []
    for feature in document["features"]:
        properties = feature["properties"]
        iso = properties.get("ISO_A2_EH") or ""
        code = iso if len(iso) == 2 and iso != "-99" else properties["ADM0_A3"]
        geometry = feature["geometry"]
        if geometry["type"] == "Polygon":
            coordinates = polygon(geometry["coordinates"])
        elif geometry["type"] == "MultiPolygon":
            coordinates = [polygon(p) for p in geometry["coordinates"]]
        else:
            continue
        features.append({
            "type": "Feature",
            "properties": {"code": code, "name": properties.get("NAME_EN") or properties["NAME"]},
            "geometry": {"type": geometry["type"], "coordinates": coordinates},
        })
    features.sort(key=lambda f: f["properties"]["code"])
    with open(target, "w", encoding="utf-8", newline="\n") as handle:
        json.dump({"type": "FeatureCollection", "features": features}, handle, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
