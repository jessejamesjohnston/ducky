#!/usr/bin/env python3
"""Summarize the JaCoCo unit-test report and gate on line coverage.

Usage: scripts/coverage.py [report.xml] [--min PERCENT] [--files]
The gate is LINE coverage over every source file in the debug build. Branch and
instruction are printed too: Compose's generated $changed/$default bitmasks add
branches no test can reach, so those two run well below line coverage.
"""
import argparse
import sys
import xml.etree.ElementTree as ET

DEFAULT_REPORT = "app/build/reports/coverage/test/debug/report.xml"


def counter(el, kind):
    for c in el.findall("counter"):
        if c.get("type") == kind:
            return int(c.get("missed")), int(c.get("covered"))
    return 0, 0


def pct(missed, covered):
    return 100.0 * covered / max(1, missed + covered)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("report", nargs="?", default=DEFAULT_REPORT)
    ap.add_argument("--min", type=float, default=95.0, help="minimum LINE coverage, percent")
    ap.add_argument("--files", action="store_true", help="list files by missed lines")
    args = ap.parse_args()

    root = ET.parse(args.report).getroot()
    for kind in ("LINE", "BRANCH", "INSTRUCTION"):
        m, c = counter(root, kind)
        print(f"{kind:12s} {c:6d} / {m + c:6d}  {pct(m, c):5.1f}%")

    if args.files:
        rows = []
        for pkg in root.findall("package"):
            for sf in pkg.findall("sourcefile"):
                m, c = counter(sf, "LINE")
                if m + c:
                    rows.append((m, c, pkg.get("name").rsplit("/", 1)[-1] + "/" + sf.get("name")))
        for m, c, name in sorted(rows, reverse=True):
            if m:
                print(f"  {name:42s} missed {m:4d} of {m + c:4d}  ({pct(m, c):5.1f}%)")

    m, c = counter(root, "LINE")
    if pct(m, c) < args.min:
        print(f"FAIL: line coverage {pct(m, c):.1f}% is under {args.min:.1f}%")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
