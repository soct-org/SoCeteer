import os
import sys
import subprocess
from pathlib import Path
import argparse

# Project paths
THIS_DIR = Path(__file__).resolve().parent
SOCETEER_ROOT = THIS_DIR.parent.parent

def compile_soceteer_jar(chisel_version: str):
    subprocess.run(["sbt", "generateVerilogParser"], cwd=SOCETEER_ROOT)
    subprocess.run(
        ["sbt", "assembly"],
        cwd=SOCETEER_ROOT,
        check=True,
        env={**os.environ, "SOCT_CHISEL_VERSION": chisel_version}
    )
    candidates = list(SOCETEER_ROOT.glob(f"target/assembly/chisel-{chisel_version}/soceteer-*.jar"))
    jar = candidates[0] if candidates else None
    if jar is None:
        raise FileNotFoundError("SoCeteer jar not found. Project was not built!")
    print(f"Built SoCeteer jar: {jar}")

def main():
    if len(sys.argv) < 2:
        raise ValueError("First argument must be the Chisel version (e.g., 3.6.1)")
    chisel_version = sys.argv[1]
    compile_soceteer_jar(chisel_version)


if __name__ == "__main__":
    main()