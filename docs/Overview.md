## Overview

The workflow of building hardware in this repository is as follows:

1. The system parts, like the RocketCore or Gemmini are described in Scala using the Chisel library. When simulating, a
   harness is used to provide memory and IO interfaces to the core. Each system is described by combining
   several Configs.
2. The individual parts are connected using Diplomacy (freechips.rocketchip.diplomacy). The transpilation,
   i.e., the conversion from Scala to FIRRTL/Verilog is done twice. The first time, the design is evaluated with a dummy bootrom.
   The emitted dts (device tree source) is used to generate the device tree binary (dtb) which is included in the actual bootrom.
   This correct bootrom is then used to generate the final design which is emitted as FIRRTL. This two-step process is
   necessary to allow operating systems to load the device tree binary (dtb) from the bootrom and to correctly configure the system.
3. This FIRRTL hardware description is transformed to Verilog (or other HDLs) using one of the following methods:
    - Using the `FirrtlStage` from the `edu.berkeley.cs.chisel3` package together with a custom `VerilogEmitter` annotation.
    - Using the `firtool` from Circt. This is a new tool that replaces the Scala/Chisel-based transpiler and can be
      used to generate Verilog from FIRRTL. It is more efficient and faster than the old tool and supports more features.
    - Using the `ChiselStage` from the `org.chipsalliance.chisel` package together with a custom `CIRCTTargetAnnotation`
      annotation. It acts as a drop-in replacement for the `FirrtlStage` but is currently not recommended.
      Finally, the Verilog code is emitted to the `workspace` directory.
4. There are now two ways to use the Verilog code:
    - The Verilog code is synthesized using a synthesis tool like Vivado to generate a bitstream that can be loaded onto
      an FPGA.
    - The Verilog code is compiled using Verilator to generate a C++ simulation environment, i.e. a binary that can be
      run to simulate the system. To run programs on the simulated system, compile your program using the shipped RISC-V
      compiler toolchain and then pass the generated .elf file to the simulation environment.
      The simulation environment will then load the instructions into the
      memory ([like this](https://chipyard.readthedocs.io/en/stable/Advanced-Concepts/Chip-Communication.html)) of the simulated system and start the simulation.

### Chisel, firtool, firrtl and friends
There are two Chisel versions this repository is (mainly) compatible with:
1. [edu.berkeley.cs.chisel3](https://repo1.maven.org/maven2/edu/berkeley/cs/chisel3_2.13/)
2. [org.chipsalliance.chisel](https://github.com/chipsalliance/chisel)

To stay compatible with edu.berkeley's chisel, org.chipsalliance also uses the package `chisel3`.
So when doing `import chisel3._`, you cannot be sure which one is used until you check the classpath.
For misconfigured build.sbt, this can lead to very confusing errors; thus it is recommended to only use exactly one of
these two packages. For now, we use (1). You should only change it to (2) if you want to check out the
(indeed very nice) new features. To test (2) modify the [build.sbt](../build.sbt) file accordingly.

Tip: To overwrite the chisel version used by a submodule with its own build.sbt, wrap it in a
`freshProject` call in the root build.sbt.

Other interesting java packages these two versions share are `circt` and `firrtl`.
If your project uses many Transform/FirrtlAnnotation based concepts
you are stuck with (1) for now. The support for annotation-based transformations in (2) is discouraged and will break as soon as
you use the firtool to generate FIRRTL, and the annotations are relevant to the transpilation process.

The aforementioned [firtool](https://github.com/llvm/circt/releases) is a binary that accepts intermediate representations
like FIRRTL and MLIR and converts them to other formats like Verilog. It replaces the transpiler from (1) which was annotation-driven.
For an example, how to use it to generate Verilog from chisel, check out the [Transpiler](src/main/scala-unmanaged/chisel/Transpiler.scala) class.

#### Version Compatibility
The latest firtool does not support when-print statements which are heavily used in RocketChip.
All Chisel versions prior to 7.0.0 do not convert these in a firtool compatible way.
Thus, using the latest firtool requires using a Chisel version >= 7.0.0 or to remove the when-print statements from
all subprojects. Read more [here](https://github.com/llvm/circt/issues/6970).
Another option is to go the Chipyard way and use the newest firtool (1.75.0) that did not error on the when-print statements.
In this case, you can also use a more stable Chisel version (like 6.7.0) and are not forced to use the latest version.
We recommend using the latest firtool that is shipped with chisel (the default for this repository).


#### Experimenting with Circt
To install circt, run [this script](scripts/install-circt.sh).
Make sure you have `clang`, `libstdc++-12-dev` (or any other fairly new version) and `ldd` installed.
The script will build circt from source and install it in the `buildtools/circt` directory.
You can then use the `firtool` binary in the `buildtools/circt/build-Release/install/bin` directory to generate Verilog.