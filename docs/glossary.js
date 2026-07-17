/*
 * The docs glossary: single source of truth for every term the guides use that
 * is not general CS knowledge. Renders the glossary page (glossary.html) and
 * annotates term occurrences in all other pages with a hover tooltip linking
 * to the glossary entry.
 *
 * Matching is case-sensitive on word boundaries, longest term first, and skips
 * headings, links, code and preformatted blocks. Each entry: `t` = the terms
 * (aliases) that map to it, `d` = the definition shown in the tooltip and on
 * the glossary page.
 */
"use strict";

const SOCT_GLOSSARY = [
  {
    group: "The MPSoC platform",
    entries: [
      { t: ["FPGA"], d: "Field-Programmable Gate Array - a chip whose logic is configured after manufacturing by loading a bitstream. The board's FPGA fabric hosts the whole SoCeteer SoC." },
      { t: ["MPSoC"], d: "Multiprocessor System-on-Chip - the Zynq UltraScale+ device family, combining hardened ARM logic (the PS) with FPGA fabric (the PL) on one chip. Boards like the ZCU104 carry one." },
      { t: ["PS", "Processing System"], d: "Processing System - the hardened half of the MPSoC: ARM cores, DDR controller, DisplayPort controller and other fixed peripherals. SoCeteer uses it only as a peripheral; its ARM cores never run." },
      { t: ["PL", "Programmable Logic"], d: "Programmable Logic - the FPGA-fabric half of the MPSoC, where the Rocket SoC and all SoCeteer components are built." },
      { t: ["APU"], d: "Application Processing Unit - the PS's ARM Cortex-A53 cores. Unused in SoCeteer designs." },
      { t: ["LPD", "FPD"], d: "Low-Power Domain / Full-Power Domain - the two power-and-clock domains of the PS. S_AXI_LPD enters through the LPD; the DisplayPort registers live in the FPD, reached over the PS's internal routing." },
      { t: ["MIO"], d: "Multiplexed I/O - the PS's dedicated, pin-multiplexed device pins, configured by the board preset." },
      { t: ["PSU"], d: "The Processing System as named by Xilinx firmware and tools (as in psu_init, the generated script that initializes PS clocks, DDR and SERDES once per power-up)." },
      { t: ["PS-GTR"], d: "The PS's gigabit serial transceivers; on MPSoC boards they carry the DisplayPort lanes to the connector." },
      { t: ["SERDES"], d: "Serializer/Deserializer - transceiver logic converting parallel data to high-speed serial lanes and back; here, the PS-GTR lanes behind the DisplayPort link." },
      { t: ["ZCU104"], d: "AMD/Xilinx evaluation board with a Zynq UltraScale+ MPSoC, a DDR4 SODIMM slot and a DisplayPort connector - SoCeteer's reference FPGA target." },
      { t: ["PMOD"], d: "Digilent's peripheral-module connector standard: small 12-pin headers for plug-on peripherals, used for the SD-card adapter." },
      { t: ["DIMM", "SODIMM"], d: "(Small Outline) Dual In-line Memory Module - the pluggable DRAM stick. The selected memory part must match the module actually inserted." },
      { t: ["ECC"], d: "Error-Correcting Code memory - modules with extra check bits (x72 instead of x64). The check bits are not addressable and do not add capacity." },
      { t: ["CAS"], d: "Column Address Strobe - CAS latency is the DRAM timing figure for how many cycles a column read takes; part of the memory part's speed grade." },
      { t: ["IOSTANDARD"], d: "Xilinx constraint selecting a pin's electrical standard (voltage levels, termination), e.g. LVCMOS33 for a 3.3 V pin." },
      { t: ["DRC", "DRCs"], d: "Design Rule Check - Vivado's electrical and structural checks; unconstrained pins or conflicting standards fail the design at DRC stage." },
    ],
  },
  {
    group: "Buses and interconnect",
    entries: [
      { t: ["AXI", "AXI4", "AXI4-Lite", "AXI4-Stream"], d: "Advanced eXtensible Interface - the ARM AMBA bus family used throughout: AXI4 for memory-mapped burst traffic, AXI4-Lite for simple register access, AXI4-Stream for unidirectional data streams (like the pixel stream)." },
      { t: ["MMIO"], d: "Memory-Mapped I/O - peripheral registers reached with ordinary loads and stores; Rocket exposes a dedicated MMIO port for this address range." },
      { t: ["DMA"], d: "Direct Memory Access - a device reading/writing memory without the CPU. SoCeteer's DMA masters (SD card, video) reach memory coherently through the Rocket L2 frontend, so no cache flushing is needed." },
      { t: ["VDMA"], d: "The Xilinx AXI Video DMA IP - fetches framebuffers from memory and emits them as an AXI4-Stream; supports multiple frame stores and a park pointer for tear-free buffer flips." },
      { t: ["SmartConnect"], d: "Xilinx AXI interconnect IP: a crossbar that also performs clock-domain crossing and data-width conversion between its ports." },
      { t: ["SAXIGP6"], d: "Slave AXI General-Purpose port 6 - the hardware name of the PS's S_AXI_LPD slave port the design uses. The preset's other PS ports (HP = High Performance, HPM = High-Performance Master) are disabled." },
      { t: ["AFIFM"], d: "AXI FIFO Interface Module - the PS-side adapter on each PS AXI port; its data-width registers are programmed by psu_init, and until then transactions into the port stall." },
      { t: ["GPIO"], d: "General-Purpose I/O - simple software-readable/writable wires; used here to expose the video pipeline's status flags as a register." },
      { t: ["UART"], d: "Universal Asynchronous Receiver-Transmitter - the serial console. The design instantiates a Xilinx UART Lite behind a custom device-tree binding." },
      { t: ["JTAG"], d: "The IEEE debug/access port. SoCeteer routes the Rocket debug module through the FPGA's JTAG chain, so xsdb/OpenOCD can halt the core and load programs." },
      { t: ["BSCAN"], d: "Boundary-scan - the FPGA primitive that lets fabric logic (here the RISC-V debug bridge) hang off the FPGA's own JTAG chain without extra pins." },
    ],
  },
  {
    group: "RISC-V and the SoC generator",
    entries: [
      { t: ["hart", "harts"], d: "Hardware thread - RISC-V's term for one logical CPU. Interrupt controllers and the debug module address harts, not cores." },
      { t: ["CLINT"], d: "Core-Local Interruptor - the RISC-V block providing the machine timer (mtime) and software interrupts, per hart." },
      { t: ["PLIC"], d: "Platform-Level Interrupt Controller - the RISC-V block that routes external device interrupts (UART, SD, VDMA) to harts." },
      { t: ["Rocket", "RocketChip"], d: "Rocket is the in-order RISC-V core; RocketChip is the Berkeley/SiFive-lineage SoC generator around it (buses, caches, CLINT, PLIC, debug) that SoCeteer builds on." },
      { t: ["BOOM"], d: "Berkeley Out-of-Order Machine - an out-of-order RISC-V core that can replace Rocket in RocketChip-based designs." },
      { t: ["Chisel"], d: "A hardware construction language embedded in Scala; SoCeteer and RocketChip describe hardware as Chisel generators rather than fixed HDL." },
      { t: ["FIRRTL"], d: "Flexible Intermediate Representation for RTL - the circuit IR that Chisel elaborates to, which the firtool compiler then lowers to Verilog." },
      { t: ["firtool", "CIRCT"], d: "The MLIR/CIRCT-based FIRRTL compiler; turns the elaborated FIRRTL circuit into synthesizable Verilog." },
      { t: ["Diplomacy"], d: "RocketChip's two-phase parameter-negotiation framework: bus masters and slaves negotiate widths, address maps and protocols before the hardware is elaborated." },
      { t: ["CDE"], d: "Context-Dependent Environments (org.chipsalliance.cde) - the Config/Field/View system used for all SoCeteer configuration fragments." },
      { t: ["HTIF"], d: "Host-Target Interface - the Berkeley protocol through which a simulated or tethered RISC-V target talks to a host (console, syscalls, exit codes)." },
      { t: ["FESVR"], d: "Frontend Server - the host-side counterpart of HTIF; in simulation it services the target's proxied syscalls." },
      { t: ["bootrom"], d: "The on-chip first-stage ROM. The SD variant mounts the card, loads BOOT.ELF into DRAM and jumps to it with the hart id in a0 and the DTB address in a1." },
      { t: ["PMP"], d: "Physical Memory Protection - RISC-V's machine-mode mechanism restricting what lower privilege levels may access; OpenSBI uses it to shield its own memory from the OS, advertised to Linux as reserved-memory nodes in the device tree." },
      { t: ["DTB", "DTS", "DT"], d: "Device Tree (Source/Blob) - the hardware self-description the generator emits and the bootrom passes to software; programs discover every peripheral, address and property from it instead of hardcoding." },
    ],
  },
  {
    group: "Vivado and EDA",
    entries: [
      { t: ["Vivado"], d: "AMD/Xilinx's FPGA toolchain: synthesis, place-and-route, bitstream generation and the hardware manager that programs the board." },
      { t: ["IP", "IPs"], d: "In EDA, 'IP' (intellectual-property core) means a pre-packaged, configurable hardware block from a vendor catalog - not a network address." },
      { t: ["TCL"], d: "The scripting language of EDA tools. SoCeteer emits deterministic TCL that recreates the whole Vivado project and block design from scratch." },
      { t: ["XDC"], d: "Xilinx Design Constraints - the file format for pin locations, electrical standards and timing constraints." },
      { t: ["VLNV"], d: "Vendor:Library:Name:Version - the four-part identifier of an IP core in the Vivado catalog (e.g. xilinx.com:ip:axi_vdma:6.3)." },
      { t: ["HDL"], d: "Hardware Description Language (Verilog, VHDL) - the level below Chisel; hand-written HDL modules can be wrapped as block-design components." },
      { t: ["MMCM"], d: "Mixed-Mode Clock Manager - the FPGA clock-synthesis primitive (inside the Clocking Wizard IP); generates the pixel clock and reports lock." },
      { t: ["DPI-C"], d: "SystemVerilog's Direct Programming Interface - lets simulated hardware call C functions; the simulation harness uses it to bridge HTIF." },
      { t: ["VCD", "FST"], d: "Waveform dump formats (Value Change Dump and its compressed successor) written by the simulator for viewing signals over time." },
      { t: ["Verilator"], d: "The open-source Verilog-to-C++ compiler and cycle simulator behind SoCeteer's simulation target." },
      { t: ["OpenOCD"], d: "Open On-Chip Debugger - bridges GDB to JTAG debug transports; used to debug the RISC-V core in simulation and on the board." },
    ],
  },
  {
    group: "DisplayPort and video",
    entries: [
      { t: ["DP"], d: "DisplayPort - the packet-based display link the PS's controller transmits. Software must train the link before video flows." },
      { t: ["VTC"], d: "Video Timing Controller - the Xilinx IP that generates sync and blanking timing for a video mode; the video out aligns the pixel stream to it." },
      { t: ["AVBuf"], d: "The DP subsystem's Audio/Video Buffer manager inside the PS: selects the video source (live vs. memory), the input format, the video clock, and blends layers over a background color." },
      { t: ["MSA"], d: "Main Stream Attributes - the DisplayPort registers describing the transmitted video (resolution, blanking, clock ratio); the sink relies on them to interpret the stream." },
      { t: ["HPD"], d: "Hot-Plug Detect - the DisplayPort signal (and interrupt) indicating a monitor is connected; also carries the sink's attention requests." },
      { t: ["M/N"], d: "The DisplayPort clock-ratio pair: the sink regenerates the pixel clock as M/N times the link clock, which is why a mismatched pixel clock shows up at the monitor." },
      { t: ["CEA", "CTA-861"], d: "The Consumer Technology Association's CTA-861 standard (formerly CEA) defining consumer video timings like 1280x720@60 and 1920x1080@60." },
      { t: ["UG934"], d: "Xilinx's 'AXI4-Stream Video IP and System Design Guide' - fixes conventions for video streams, including the pixel component order the framebuffer must follow." },
      { t: ["BSP"], d: "Board Support Package - the per-board header/driver layer Xilinx tools generate; dp-test replaces it with a small shim so the vendored drivers compile unmodified on RISC-V." },
    ],
  },
  {
    group: "Software stack",
    entries: [
      { t: ["soctglue"], d: "SoCeteer's bare-metal runtime library: startup code, linker script, syscall handling, FatFs and DTB integration shared by all programs and bootroms." },
      { t: ["smoldtb"], d: "The small device-tree parser library bundled with soctglue; programs use it to discover peripherals from the DTB at runtime." },
      { t: ["FatFs"], d: "ChaN's embedded FAT filesystem library - how programs and the bootrom read the SD card." },
      { t: ["newlib"], d: "The embedded C standard library (SoCeteer links its 'nano' variant); small, but with quirks like a byte-wise memcpy and a 2 GiB allocation limit." },
      { t: ["OpenSBI", "SBI"], d: "The Supervisor Binary Interface is RISC-V's contract between an OS kernel (S-mode) and machine-mode firmware - timers, inter-processor interrupts, early console. OpenSBI is its reference implementation; the Linux boot image wraps it around the kernel as fw_payload." },
      { t: ["initramfs"], d: "A file archive embedded in the kernel image and unpacked into a RAM-backed root filesystem at boot. It is the only root filesystem option when no storage driver exists - the boot image bakes a minimal /init into it." },
      { t: ["vDSO"], d: "Virtual dynamic shared object - a small shared library the kernel maps into every userspace process for fast syscalls and signal return. RISC-V MMU kernels cannot be built without it, which is why the kernel build demands a linker with shared-object support." },
    ],
  },
];

/* ------------------------------------------------------------------------ */

function soctGlossarySlug(term) {
  return "g-" + term.toLowerCase().replace(/[^a-z0-9]+/g, "-");
}

/** Render the glossary page (only when #glossary-root exists). */
function soctGlossaryRender(root) {
  for (const group of SOCT_GLOSSARY) {
    const h2 = document.createElement("h2");
    h2.textContent = group.group;
    root.appendChild(h2);
    const table = document.createElement("table");
    for (const e of group.entries) {
      const tr = document.createElement("tr");
      const td1 = document.createElement("td");
      td1.id = soctGlossarySlug(e.t[0]);
      td1.innerHTML = "<strong>" + e.t.join(" / ") + "</strong>";
      const td2 = document.createElement("td");
      td2.textContent = e.d;
      tr.appendChild(td1);
      tr.appendChild(td2);
      table.appendChild(tr);
    }
    root.appendChild(table);
  }
}

/** Wrap term occurrences in tooltip links (all pages except the glossary). */
function soctGlossaryAnnotate() {
  const base = location.pathname.includes("/guides/") ? "../" : "";
  const byTerm = new Map();
  for (const group of SOCT_GLOSSARY) {
    for (const e of group.entries) {
      for (const t of e.t) byTerm.set(t, e);
    }
  }
  // Longest first, so e.g. "PS-GTR" wins over "PS" at the same position.
  const terms = [...byTerm.keys()].sort((a, b) => b.length - a.length);
  const escaped = terms.map((t) => t.replace(/[.*+?^${}()|[\]\\/-]/g, "\\$&"));
  const re = new RegExp("(?<![\\w-])(" + escaped.join("|") + ")(?![\\w-])", "g");

  const skip = new Set(["A", "CODE", "PRE", "SCRIPT", "STYLE", "H1", "H2", "H3", "TITLE"]);
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      for (let el = node.parentElement; el; el = el.parentElement) {
        if (skip.has(el.tagName) || el.classList.contains("term")) {
          return NodeFilter.FILTER_REJECT;
        }
      }
      return NodeFilter.FILTER_ACCEPT;
    },
  });

  const nodes = [];
  for (let n = walker.nextNode(); n; n = walker.nextNode()) nodes.push(n);

  for (const node of nodes) {
    const text = node.nodeValue;
    re.lastIndex = 0;
    if (!re.test(text)) continue;
    re.lastIndex = 0;

    const frag = document.createDocumentFragment();
    let last = 0;
    for (const m of text.matchAll(re)) {
      const entry = byTerm.get(m[1]);
      frag.appendChild(document.createTextNode(text.slice(last, m.index)));
      const a = document.createElement("a");
      a.className = "term";
      a.href = base + "glossary.html#" + soctGlossarySlug(entry.t[0]);
      a.appendChild(document.createTextNode(m[1]));
      const tip = document.createElement("span");
      tip.className = "term-tip";
      tip.textContent = entry.d;
      a.appendChild(tip);
      frag.appendChild(a);
      last = m.index + m[1].length;
    }
    frag.appendChild(document.createTextNode(text.slice(last)));
    node.parentNode.replaceChild(frag, node);
  }
}

document.addEventListener("DOMContentLoaded", () => {
  const root = document.getElementById("glossary-root");
  if (root) {
    soctGlossaryRender(root);
  } else {
    soctGlossaryAnnotate();
  }
});
