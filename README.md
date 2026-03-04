# SoCeteer

**SoCeteer** is a framework for the design and simulation of RISC-V (co-)processors. It provides a modular and transparent alternative to [Chipyard](https://github.com/ucb-bar/chipyard) and [Vivado-risc-v](https://github.com/eugene-tarassov/vivado-risc-v). The former provides comprehensive [docs](https://chipyard.readthedocs.io/en/stable/index.html); many concepts are similar in SoCeteer.

Key distinguishing features include:

* Support for multiple Chisel backend versions 
* Implementation primarily in **Scala** and **Python**, avoiding reliance on Makefiles and shell scripting.
* Modular design, no monolithic approach.

---

## Table of Contents

- [Setup and Dependencies](#setup-and-dependencies)
    - [Java](#java)
    - [Scala and SBT](#scala-and-sbt)
    - [CMake](#cmake)
    - [Device Tree Compiler](#device-tree-compiler)
    - [RISC-V Toolchain](#risc-v-toolchain)
    - [Verilator (for Simulation)](#verilator-for-simulation)
    - [Vivado (for Synthesis)](#vivado-for-synthesis)
- [Quick Start (IDE)](#quick-start-ide)
- [Quick Start (Command Line)](#quick-start-command-line)
- [Compiling RISC-V Programs](#compiling-risc-v-programs)
- [Simulation](#simulation)
    - [Building the Simulator](#building-the-simulator)
    - [Running the Simulation](#running-the-simulation)
- [Synthesis for FPGA](#synthesis-for-fpga)
- [More Information](#more-information)
    - [Important Files](#important-files)
    - [Development on Windows](#development-on-windows)
    - [Caches and Build Directories](#caches-and-build-directories)
    - [Known Issues](#known-issues)
---

## Setup and Dependencies

This project relies on several tools and libraries. For a full setup and how to run the project, refer to the [Dockerfile](Dockerfile) and the [GitLab CI scripts](scripts/cicd).

Clone the repository with submodules:

```bash
# Using SSH:
git clone --recurse-submodules git@github.com:soct-org/SoCeteer.git
# or, using HTTPS:
git clone --recurse-submodules https://github.com/soct-org/SoCeteer.git
# If already cloned without --recurse-submodules:
git submodule update --init --recursive
```

This may take significant time due to [Gemmini's](https://github.com/ucb-bar/gemmini) dependencies. For faster initialization when branch switching is not required, use `--depth 1`.

⚠️ Don't open the project in an IDE before initializing submodules, as it may add directories for uninitialized submodules which can cause issues with cloning.

### Java
* Required to run SBT and Scala.
* Java 11 or any newer version is recommended.
* (IDEA IntelliJ users) Download via `Actions -> Find Action -> Download JDK`, select at `File -> Project Structure -> SDK`.
* (CLI users) Set `JAVA_HOME` to the JDK installation path, add `$JAVA_HOME/bin` to `PATH`.
Verify with:
```bash
java -version
```
---
### Scala and SBT
* Required to run Chisel and SoCeteer.
* (IDEA IntelliJ users) Install the Scala plugin via `File -> Settings -> Plugins`, search for "Scala", install, and restart the IDE.
* (CLI users) Install [Scala + SBT](https://www.scala-lang.org/download/) or [SBT only](https://www.scala-sbt.org/1.x/docs/Setup.html).
  Ensure `sbt`, `scala`, and `java` are available in the system `PATH`.
---
### CMake
* Scripting language used in this project, mainly for building the simulator and RISC-V programs.
* Install via package manager:
```bash
# Ubuntu/Debian
sudo apt-get install cmake
# Arch Linux
sudo pacman -S cmake
# macOS
brew install cmake
# Windows (via Chocolatey)
choco install cmake
```
---
### Device Tree Compiler
* Required to compile device tree source files (.dts) into binary blobs (.dtb).
* Install via package manager:
```bash
# Ubuntu/Debian
sudo apt-get install device-tree-compiler
# Arch Linux
sudo pacman -S dtc
# macOS
brew install dtc
# Windows (via Chocolatey)
choco install dtc-msys2
```
---
### RISC-V Toolchain
* Required for compiling RISC-V programs and the boot ROM.
* Relies on [xpack-dev-tools](https://github.com/xpack-dev-tools/riscv-none-elf-gcc-xpack).
* Installed automatically when running SoCeteer via [install-rv-compiler.sh](scripts/install-deps/install-rv-compiler.sh) (Linux/macOS) or [install-rv-compiler.bat](scripts/install-deps/install-rv-compiler.bat) (Windows).
---
### Verilator (for Simulation)
* Required to simulate the generated Verilog.
* A maintained, compatible submodule is provided at [shared/verilator](shared/verilator).
* Requires `flex` and `bison`:
```bash
# Ubuntu/Debian
sudo apt-get install flex bison
# Arch Linux
sudo pacman -S flex bison
# macOS
brew install flex bison
# Windows (via Chocolatey)
choco install winflexbison3
```
---

#### On Windows:
By default, [CMakeLists.txt](sim/CMakeLists.txt) builds Verilator. However, manual build is often more reliable (ensure `WIN_FLEX_BISON` points to the correct directory). The recommended compiler is [MSVC](https://visualstudio.microsoft.com/downloads/).
```bash
cd shared/verilator
cmake -E make_directory build
cd build
cmake .. -DWIN_FLEX_BISON="C:\\ProgramData\\chocolatey\\lib\\winflexbison3\\tools" -DCMAKE_BUILD_TYPE=Release
cmake --build . --config Release
cmake --install . --prefix ../install
```

---
### Vivado (for Synthesis)
* Required for synthesizing designs for Xilinx FPGAs. Used by SoCeteer to emit project files.
* Install [Vivado](https://www.xilinx.com/support/download.html).
---

## Quick Start (IDE) 
1. Ensure all dependencies are installed.
2. Open SoCeteer (the root directory) in IntelliJ IDEA with the Scala plugin.
3. Navigate to the [RocketLauncher.scala](/src/main/scala/soct/RocketLauncher.scala)
4. Press on the green play button next to the `main` method to run the project.
   * In the case the play button is not visible, reload the SBT project (`Help -> Find Action -> sbt` and click `Sync all SBT projects`).
5. After building the project and downloading the RISC-V toolchain, it will emit a set of Verilog files, device tree sources, and regmap in the `workspace` directory (RocketB1-64)
   * Select and edit the Configuration in the top right corner and add `--help` to see all available options. Press the play button again to re-run with the new configuration.
6. Open the [sim](sim) directory as a separate project in CLion or VSCode. Build the simulator using CMake. 
   * Make sure to select the Release build type for **significantly better performance** (left of the configuration selector in CLion, Debug by default).
   * [Here](#building-the-simulator) is a list of available CMake options for building the simulator.
7. Open the [examples](binaries) directory as a separate project in CLion or VSCode. Build one of the example programs using CMake.
8. Run the simulator and pass the compiled ELF binary as an argument.

---

## Quick Start (Command Line)
Many steps become clear when following the [GitLab CI scripts](scripts/cicd). The following commands assume a Unix-like environment (Linux/macOS/WSL). Windows users can adapt the commands accordingly.
1. Ensure all dependencies are installed.
2. Build the SoCeteer JAR file by running (from the repository root):
```bash
sbt assembly
```
The output path depends on your Chisel version and the SoCeteer version and is defined in [build.sbt](build.sbt) (default: `target/assembly/chisel-<version>/soceteer-<version>.jar`). This path is referred to as `<path-to-jar>` in the following commands.
3. Emit Verilog files for simulation by running (from the repository root):
```bash
java -cp <path-to-jar> soct.RocketLauncher -c soct.RocketB1 -d workspace
```
This will download the RISC-V toolchain and create a `workspace` directory containing the Verilog files, device tree sources, and regmaps. You can pass `--help` to see all available options.
4. Build the simulator:
```bash
cd sim
mkdir build && cd build
cmake .. -DCMAKE_BUILD_TYPE=Release
cmake --build .
```
[Here](#building-the-simulator) is a list of available CMake options for building the simulator.
5. Compile one of the example programs:
```bash
cd examples
mkdir build && cd build
cmake ..
cmake --build programs/hello-cpp # or any other program in examples/programs
```
Compiled ELF binaries are placed by default in [examples/elfs](binaries/elfs).
6. Run the simulator and pass the compiled ELF binary as an argument:
```bash
./sim/build/RocketB1-64 examples/elfs/hello-cpp/boot-sim.elf 
```
---

## Compiling RISC-V Programs

Compiled ELF binaries are placed in [examples/elfs](binaries/elfs).

---

## Simulation

### Building the Simulator
Parameters:
* `-DVL_TRACE=ON` – enable waveform dumping.
* `-DPASS_UNKNOWN_SYSCALLS=ON` – forward unimplemented syscalls to the host system (Linux only).
* `-DVL_TRACE=ON` – enable waveform dumping.
* `-DVL_TRACE_DEPTH=<depth>` – set waveform dump depth (i.e., how many layers of subfields to include in the VCD).
* `-DVL_THREADS=<n>` – number of threads for Verilator (only set larger for large designs, otherwise it can slow down execution due to thread overhead).
* More options in [CMakeLists.txt](sim/CMakeLists.txt).

### Running the Simulation


---

## Synthesis for FPGA

FPGA synthesis is supported for boards listed in [syn/boards](syn/boards). A dedicated top module is used, omitting simulation-specific black boxes. Specify the board with `--board=<board>` and the target frequency with `--freq-mhz=<freq>`. 

For example, to synthesize for a [ZCU104](syn/boards/ZCU104) board at 100 MHz, pass `--board=ZCU104 --freq-mhz=100` to the RocketLauncher. 
To not build the simulator, add `--no-sim`. In order to generate Vivado project files, add `--vivado=<path-to-vivado>` or `--vivado-settings=<path-to-settings>` for script that is to be sourced before calling Vivado (process used by older Vivado versions).

---


## More Information

### Important Files
* **[build.sbt](build.sbt)** – dependencies and project definition. CLI users: note `assemblyOutputPath` which defines the JAR output path.
* **[RocketLauncher.scala](src/main/scala/soct/RocketLauncher.scala)** – main entry point, parses arguments, triggers Verilog emission.
* **[Configs Directory](src/main/scala/soct/config)** - Contains several predefined SoC configurations for [boom](src/main/scala/soct/config/boom), [rocket](src/main/scala/soct/config/rocketchip) and [gemmini](src/main/scala/soct/config/gemmini) based systems.
* **[Systems.scala](src/main/scala/soct/config/soceteer/Systems.scala)** – processor and harness definitions for SoCeteer.
* **[Configs.scala](src/main/scala/soct/config/soceteer/Configs.scala)** – configurations for synthesis and simulation for SoCeteer.

### Development on Windows
Windows support is available but not prioritized. Some features may not function as expected.
* [Chocolatey](https://chocolatey.org/) is the recommended (and the **only** supported) package manager, but manual installation is possible.
* Use **WSL2** for a more Unix-like environment.
* (Simulation) Native Windows builds are possible with **CLion** or **Visual Studio** using MinGW. CLion includes a bundled MinGW toolchain, make sure it is selected in `File -> Settings -> Build, Execution, Deployment -> Toolchains`.

⚠️ Some simulation features (e.g., [syscall_device.cpp](shared/risc-v-isa-sim/fesvr/syscall_device.cpp)) are not fully implemented on Windows.

---
### Caches and Build Directories
Several tools used in this project maintain caches or build directories that may need to be cleared if issues arise.

* **SBT**: `sbt clean`
* **CMake**: remove build directories and re-run
* **Verilator**: remove `verilator_bin` and rebuild
* **ccache**: `ccache -C`

---

### Known Issues
* **Conda environments**: May interfere with toolchain builds (e.g., when building Verilator). Ensure `/usr/bin` compilers take precedence in PATH.
