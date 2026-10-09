#!/usr/bin/env python3
"""Select only Android platforms whose version changed across a push."""
import json
import re
import subprocess
import sys
from pathlib import Path

PLATFORMS = {"phone": "Phone", "tvLegacy": "TvLegacy", "tvModern": "TvModern"}


def properties(text):
    return dict(line.strip().split("=", 1) for line in text.splitlines()
                if line.strip() and not line.lstrip().startswith("#") and "=" in line)


def matrix(old_text, new_text):
    old, new = properties(old_text), properties(new_text)
    entries = []
    for platform, task in PLATFORMS.items():
        prefix = f"lanshare.{platform}."
        name, code = new[prefix + "versionName"], int(new[prefix + "versionCode"])
        previous_name, previous_code = old.get(prefix + "versionName"), old.get(prefix + "versionCode")
        if name == previous_name and str(code) == previous_code:
            continue
        if not re.fullmatch(r"\d+\.\d+\.\d+(?:_feat\.[1-9]\d*)?", name):
            raise ValueError(f"{platform}: version must be three numeric segments, optionally followed by _feat.N")
        if previous_code is not None and code <= int(previous_code):
            raise ValueError(f"{platform}: versionCode must strictly increase")
        if previous_name == name:
            raise ValueError(f"{platform}: update versionName together with versionCode")
        entries.append({"platform": platform, "task": f":app:assemble{task}Release", "version": name})
    return {"include": entries}


if __name__ == "__main__":
    before = sys.argv[1]
    old = ""
    if before and set(before) != {"0"}:
        old = subprocess.check_output(["git", "show", f"{before}:gradle.properties"], text=True)
    result = matrix(old, Path("gradle.properties").read_text())
    print("matrix=" + json.dumps(result, separators=(",", ":")))
    print("changed=" + str(bool(result["include"])).lower())
