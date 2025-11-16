from pathlib import Path
import subprocess
from dataclasses import dataclass, field
from typing import List, Tuple
import os
import argparse

# Get the directory of the current script
THIS_DIR = Path(__file__).resolve().parent
SOCETEER_ROOT = THIS_DIR.parent.parent
EXAMPLES_DIR = SOCETEER_ROOT / "examples"
ELFS_DIR = EXAMPLES_DIR / "elfs"
SIM_DIR = SOCETEER_ROOT / "sim"
SIM_CONFIGS_DIR = SIM_DIR / "configs"
PACKAGE = "soct."
LAUNCHER = PACKAGE + "RocketLauncher"
WORKSPACE_DIR = SOCETEER_ROOT / "workspace"
SUPPORTED_CHISEL_VERSIONS = ["3.6.1", "7.3.0"] # Must be kept in sync with gitlab-ci.yml and build.sbt

# Other constants
ELF_NAME_64 = "boot-sim.elf"
ELF_NAME_32 = "boot-sim-32.elf"

@dataclass
class Test:
    config_stem: str  # The stem of the config, e.g. "RocketB1". The <PACKAGE> prefix is added automatically.
    xlen: Tuple[int]
    n_cores: int  # Number of cores this core configuration has, used for program selection
    args: List[str] = field(default_factory=list)

XLEN_ALL = (32, 64)
XLEN_32 = (32,)
XLEN_64 = (64,)

boom3_tests = [
    Test("SmallBoomV3", XLEN_ALL, 1),
    Test("DualSmallBoomV3", XLEN_ALL, 2),
    Test("MediumBoomV3", XLEN_ALL, 1),
    Test("DualMediumBoomV3", XLEN_ALL, 2),
    Test("LargeBoomV3", XLEN_ALL, 1),
    Test("DualLargeBoomV3", XLEN_ALL, 2),
    Test("MegaBoomV3", XLEN_ALL, 1),
    Test("DualMegaBoomV3", XLEN_ALL, 2),
]

boom4_tests = [
    Test("SmallBoomV4", XLEN_ALL, 1),
    Test("DualSmallBoomV4", XLEN_ALL, 2),
    Test("MediumBoomV4", XLEN_ALL, 1),
    Test("DualMediumBoomV4", XLEN_ALL, 2),
    Test("LargeBoomV4", XLEN_ALL, 1),
    Test("DualLargeBoomV4", XLEN_ALL, 2),
    Test("MegaBoomV4", XLEN_ALL, 1),
    Test("DualMegaBoomV4", XLEN_ALL, 2),
]

rocket_tests = [
    Test("RocketS1", XLEN_ALL, 1),
    Test("RocketB1", XLEN_ALL, 1),
    Test("RocketS2", XLEN_ALL, 2),
    Test("RocketM1", XLEN_ALL, 1),
    Test("RocketM2", XLEN_ALL, 2),
    Test("RocketB2", XLEN_ALL, 2),
    Test("RocketH1", XLEN_ALL, 1),
    Test("RocketH2", XLEN_ALL, 2),
]

gemmini_tests = [
    Test("RocketB1Gem4Fp", XLEN_64, 1),
    Test("RocketB1Gem4", XLEN_64, 1),
]

default_programs = [
    "hello-cpp"
]

def emit_socs(soceteer_jar: Path, test: Test) -> None:
    for xlen in test.xlen:
        base_cmd = ["java", "-cp", str(soceteer_jar), LAUNCHER]
        args = [f"-c={PACKAGE}{test.config_stem}", f"--xlen={xlen}"] + test.args
        # if -d not in args, use default workspace directory
        if not any(a.startswith("-d") for a in args):
            args = ["-d", str(WORKSPACE_DIR)] + args
        # join the args with the base command
        cmd = base_cmd + args
        # run the command
        subprocess.run(cmd, check=True, cwd=SOCETEER_ROOT, env={**os.environ, "SOCETEER_ROOT": str(SOCETEER_ROOT)})

def build_simulator_binaries(test: Test) -> List[Tuple[int, Path]]:
    binaries = []
    for xlen in test.xlen:
        full_config = f"{test.config_stem}-{xlen}"
        config_path = SIM_CONFIGS_DIR / full_config
        assert config_path.exists(), f"Simulator config {full_config} does not exist!"
        # Run cmake to build the simulator
        build_dir = SIM_DIR / "build"
        build_dir.mkdir(exist_ok=True)
        # Remove CMake cache to force reconfiguration
        cache_file = build_dir / "CMakeCache.txt"
        if cache_file.exists():
            print(f"Removing CMake cache file {cache_file}")
            cache_file.unlink()
        subprocess.run(["cmake", "..", "-DCMAKE_BUILD_TYPE=Release", f"-DSOCETEER_ROOT={SOCETEER_ROOT}"], cwd=build_dir, check=True)
        num_cores = os.cpu_count() or 1
        subprocess.run(["cmake", "--build", ".", "--parallel", str(num_cores)], cwd=build_dir, check=True)
        # Return path to binary
        binary_path = build_dir / full_config
        assert binary_path.exists(), f"Simulator binary {full_config} was not built correctly!"
        binaries.append((xlen, binary_path))
    return binaries

def test_simulator(chisel_version: str, tests: List[Test], programs: List[str]) -> None:
    candidates = list(SOCETEER_ROOT.glob(f"target/assembly/chisel-{chisel_version}/soceteer-*.jar"))  # Check build.sbt for path
    soceteer_jar = candidates[0] if candidates else None
    if soceteer_jar is None:
        raise FileNotFoundError(f"SoCeteer jar not found for Chisel version {chisel_version}. Project was not built!")
    # Emit the Verilog for the tests:
    for test in tests:
        emit_socs(soceteer_jar, test)
        simulator_binaries = build_simulator_binaries(test)
        for (xlen, simulator) in simulator_binaries:
            # Run the simulator with all the test programs
            for program in programs:
                program_elf = ELFS_DIR / program / ELF_NAME_64 if xlen in XLEN_64 else ELFS_DIR / program / ELF_NAME_32
                assert program_elf.exists(), f"Program ELF {program_elf} does not exist!"
                cmd = [str(simulator), str(program_elf)]
                print(f"Running simulator with command: {' '.join(cmd)}")
                subprocess.run(cmd, check=True)

def validate_paths():
    # Check if the paths are valid
    assert SOCETEER_ROOT.exists(), f"Project directory {SOCETEER_ROOT} does not exist!"
    assert EXAMPLES_DIR.exists(), f"Examples directory {EXAMPLES_DIR} does not exist!"
    assert SIM_DIR.exists(), f"SIM directory {SIM_DIR} does not exist!"

def build_test_programs(programs: List[str]) -> None:
    # Use CMake to build the test programs in the examples directory
    build_dir = EXAMPLES_DIR / "build"
    build_dir.mkdir(exist_ok=True)
    # Remove CMake cache to force reconfiguration
    cache_file = build_dir / "CMakeCache.txt"
    if cache_file.exists():
        print(f"Removing CMake cache file {cache_file}")
        cache_file.unlink()
    subprocess.run(["cmake", "..", f"-DSOCETEER_ROOT={SOCETEER_ROOT}"], cwd=build_dir, check=True)
    for program in programs:
        subprocess.run(["cmake", "--build", f"programs/{program}"], cwd=build_dir, check=True)
        # Make sure it is copied to the elfs directory
        program_elf = ELFS_DIR / program / "boot-sim.elf"
        assert program_elf.exists(), f"Program {program} was not built correctly!"

def main():
    parser = argparse.ArgumentParser(description="Run SoCeteer simulation tests.")
    parser.add_argument("--group", choices=["boom3", "boom4", "rocket", "gemmini"], required=True,
                        help="Test group to run")
    parser.add_argument("--chisel-version", choices=SUPPORTED_CHISEL_VERSIONS + ["all"], default="all",
                        help="Chisel version to use")
    parser.add_argument("--first-n-only", help="Run only the first N tests. Default is to run all tests.", type=int, default=None)
    args = parser.parse_args()

    if args.group == "boom3":
        tests = boom3_tests
        programs = default_programs
    elif args.group == "boom4":
        tests = boom4_tests
        programs = default_programs
    elif args.group == "rocket":
        tests = rocket_tests
        programs = default_programs
    elif args.group == "gemmini":
        tests = gemmini_tests
        programs = default_programs
    else:
        print(f"Unknown group: {args.group}")
        return

    print("Building the programs for the tests...")
    build_test_programs(programs)
    print("Programs built successfully.")

    chisel_versions = SUPPORTED_CHISEL_VERSIONS if args.chisel_version == "all" else [args.chisel_version]
    for chisel_version in chisel_versions:
        if args.first_n_only is not None:
            tests = tests[:args.first_n_only]
        print(f"Running tests for Chisel version: {chisel_version}")
        test_simulator(chisel_version, tests, programs)
        print(f"Tests completed for Chisel version: {chisel_version}")

if __name__ == "__main__":
    validate_paths()
    main()