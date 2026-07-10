//////////////////////////////////////////////////////////////////////
////                                                              ////
//// axi_addr_deinterleaver                                       ////
////                                                              ////
//// Maps the sparse (interleaved) address view of one memory     ////
//// channel onto a dense, contiguous address range.              ////
////                                                              ////
//// Rocket-chip distributes cache lines round-robin across its   ////
//// memory channels: with N channels and a block size of B       ////
//// bytes, channel i only ever sees addresses A where            ////
////   ((A - BASE) / B) mod N == i                                ////
//// i.e. the DROP_BITS bits starting at bit DROP_LSB of          ////
//// (A - BASE) are constant for a given channel. Feeding such    ////
//// addresses to a DDR controller directly leaves holes of size  ////
//// B * (N-1) between blocks and wastes/aliases capacity.        ////
////                                                              ////
//// This module removes those constant bits by shifting all      ////
//// higher address bits down, producing:                         ////
////   out = BASE + { rel[HI : DROP_LSB+DROP_BITS], rel[DROP_LSB-1:0] }
//// with rel = A - BASE, so each channel's traffic lands in the  ////
//// dense range [BASE, BASE + capacity).                         ////
////                                                              ////
//// The datapath is purely combinational: only AW.addr and       ////
//// AR.addr are rewritten, every other AXI4 signal is passed     ////
//// through unmodified. No transaction may cross an interleave   ////
//// block boundary - this is guaranteed by construction, since   ////
//// the system bus routes at block (cache-line) granularity and  ////
//// AXI transfers are size-aligned.                              ////
////                                                              ////
//// Constraints (enforced by the SoCeteer generator):            ////
////   * DROP_LSB  >= 1                                           ////
////   * DROP_BITS >= 1 (use a plain wire for DROP_BITS == 0)     ////
////   * BASE aligned to 2^(DROP_LSB+DROP_BITS)                   ////
////                                                              ////
//////////////////////////////////////////////////////////////////////

module axi_addr_deinterleaver #(
    parameter integer ADDR_WIDTH = 34,
    parameter integer DATA_WIDTH = 64,
    parameter integer ID_WIDTH   = 4,
    // Index of the lowest channel-select bit (log2 of the interleave block size, e.g. 6 for 64-byte cache lines)
    parameter integer DROP_LSB   = 6,
    // Number of channel-select bits to remove (log2 of the number of memory channels)
    parameter integer DROP_BITS  = 1,
    // Base address of the interleaved memory region (start of DRAM as seen by the cores)
    parameter [63:0]  BASE       = 64'h0000000080000000
) (
    // The clock does not drive any logic (the module is combinational);
    // it only tells Vivado which clock domain both AXI interfaces belong to.
    (* X_INTERFACE_INFO = "xilinx.com:signal:clock:1.0 aclk CLK" *)
    (* X_INTERFACE_PARAMETER = "ASSOCIATED_BUSIF S_AXI:M_AXI" *)
    input  wire                      aclk,

    // ------------------------------------------------------------------
    // S_AXI: sparse / interleaved side (from the core's memory port)
    // ------------------------------------------------------------------
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI AWID" *)
    (* X_INTERFACE_PARAMETER = "PROTOCOL AXI4" *)
    input  wire [ID_WIDTH-1:0]       s_axi_awid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI AWADDR" *)
    input  wire [ADDR_WIDTH-1:0]     s_axi_awaddr,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI AWLEN" *)
    input  wire [7:0]                s_axi_awlen,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI AWSIZE" *)
    input  wire [2:0]                s_axi_awsize,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI AWBURST" *)
    input  wire [1:0]                s_axi_awburst,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI AWLOCK" *)
    input  wire                      s_axi_awlock,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI AWCACHE" *)
    input  wire [3:0]                s_axi_awcache,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI AWPROT" *)
    input  wire [2:0]                s_axi_awprot,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI AWQOS" *)
    input  wire [3:0]                s_axi_awqos,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI AWVALID" *)
    input  wire                      s_axi_awvalid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI AWREADY" *)
    output wire                      s_axi_awready,

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI WDATA" *)
    input  wire [DATA_WIDTH-1:0]     s_axi_wdata,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI WSTRB" *)
    input  wire [(DATA_WIDTH/8)-1:0] s_axi_wstrb,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI WLAST" *)
    input  wire                      s_axi_wlast,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI WVALID" *)
    input  wire                      s_axi_wvalid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI WREADY" *)
    output wire                      s_axi_wready,

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI BID" *)
    output wire [ID_WIDTH-1:0]       s_axi_bid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI BRESP" *)
    output wire [1:0]                s_axi_bresp,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI BVALID" *)
    output wire                      s_axi_bvalid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI BREADY" *)
    input  wire                      s_axi_bready,

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI ARID" *)
    input  wire [ID_WIDTH-1:0]       s_axi_arid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI ARADDR" *)
    input  wire [ADDR_WIDTH-1:0]     s_axi_araddr,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI ARLEN" *)
    input  wire [7:0]                s_axi_arlen,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI ARSIZE" *)
    input  wire [2:0]                s_axi_arsize,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI ARBURST" *)
    input  wire [1:0]                s_axi_arburst,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI ARLOCK" *)
    input  wire                      s_axi_arlock,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI ARCACHE" *)
    input  wire [3:0]                s_axi_arcache,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI ARPROT" *)
    input  wire [2:0]                s_axi_arprot,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI ARQOS" *)
    input  wire [3:0]                s_axi_arqos,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI ARVALID" *)
    input  wire                      s_axi_arvalid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI ARREADY" *)
    output wire                      s_axi_arready,

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI RID" *)
    output wire [ID_WIDTH-1:0]       s_axi_rid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI RDATA" *)
    output wire [DATA_WIDTH-1:0]     s_axi_rdata,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI RRESP" *)
    output wire [1:0]                s_axi_rresp,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI RLAST" *)
    output wire                      s_axi_rlast,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI RVALID" *)
    output wire                      s_axi_rvalid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 S_AXI RREADY" *)
    input  wire                      s_axi_rready,

    // ------------------------------------------------------------------
    // M_AXI: dense / contiguous side (towards the DDR controller)
    // ------------------------------------------------------------------
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWID" *)
    (* X_INTERFACE_PARAMETER = "PROTOCOL AXI4" *)
    output wire [ID_WIDTH-1:0]       m_axi_awid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWADDR" *)
    output wire [ADDR_WIDTH-1:0]     m_axi_awaddr,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWLEN" *)
    output wire [7:0]                m_axi_awlen,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWSIZE" *)
    output wire [2:0]                m_axi_awsize,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWBURST" *)
    output wire [1:0]                m_axi_awburst,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWLOCK" *)
    output wire                      m_axi_awlock,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWCACHE" *)
    output wire [3:0]                m_axi_awcache,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWPROT" *)
    output wire [2:0]                m_axi_awprot,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWQOS" *)
    output wire [3:0]                m_axi_awqos,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWVALID" *)
    output wire                      m_axi_awvalid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI AWREADY" *)
    input  wire                      m_axi_awready,

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI WDATA" *)
    output wire [DATA_WIDTH-1:0]     m_axi_wdata,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI WSTRB" *)
    output wire [(DATA_WIDTH/8)-1:0] m_axi_wstrb,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI WLAST" *)
    output wire                      m_axi_wlast,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI WVALID" *)
    output wire                      m_axi_wvalid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI WREADY" *)
    input  wire                      m_axi_wready,

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI BID" *)
    input  wire [ID_WIDTH-1:0]       m_axi_bid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI BRESP" *)
    input  wire [1:0]                m_axi_bresp,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI BVALID" *)
    input  wire                      m_axi_bvalid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI BREADY" *)
    output wire                      m_axi_bready,

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARID" *)
    output wire [ID_WIDTH-1:0]       m_axi_arid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARADDR" *)
    output wire [ADDR_WIDTH-1:0]     m_axi_araddr,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARLEN" *)
    output wire [7:0]                m_axi_arlen,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARSIZE" *)
    output wire [2:0]                m_axi_arsize,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARBURST" *)
    output wire [1:0]                m_axi_arburst,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARLOCK" *)
    output wire                      m_axi_arlock,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARCACHE" *)
    output wire [3:0]                m_axi_arcache,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARPROT" *)
    output wire [2:0]                m_axi_arprot,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARQOS" *)
    output wire [3:0]                m_axi_arqos,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARVALID" *)
    output wire                      m_axi_arvalid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI ARREADY" *)
    input  wire                      m_axi_arready,

    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI RID" *)
    input  wire [ID_WIDTH-1:0]       m_axi_rid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI RDATA" *)
    input  wire [DATA_WIDTH-1:0]     m_axi_rdata,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI RRESP" *)
    input  wire [1:0]                m_axi_rresp,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI RLAST" *)
    input  wire                      m_axi_rlast,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI RVALID" *)
    input  wire                      m_axi_rvalid,
    (* X_INTERFACE_INFO = "xilinx.com:interface:aximm:1.0 M_AXI RREADY" *)
    output wire                      m_axi_rready
);

  // Lowest bit above the removed channel-select field
  localparam integer CH_MSB = DROP_LSB + DROP_BITS;

  // out = BASE + { rel[ADDR_WIDTH-1:CH_MSB], rel[DROP_LSB-1:0] }, rel = addr - BASE
  // The subtraction/re-addition of BASE keeps the dense range anchored at
  // BASE, so the DDR controller's MEMORY_MAP_BASEADDR does not change.
  // BASE is a compile-time constant, so each address channel folds into a
  // single carry chain plus the wire permutation.
  wire [ADDR_WIDTH-1:0] aw_rel = s_axi_awaddr - BASE[ADDR_WIDTH-1:0];
  wire [ADDR_WIDTH-1:0] ar_rel = s_axi_araddr - BASE[ADDR_WIDTH-1:0];

  wire [ADDR_WIDTH-1:0] aw_compact = BASE[ADDR_WIDTH-1:0]
      + {{DROP_BITS{1'b0}}, aw_rel[ADDR_WIDTH-1:CH_MSB], aw_rel[DROP_LSB-1:0]};
  wire [ADDR_WIDTH-1:0] ar_compact = BASE[ADDR_WIDTH-1:0]
      + {{DROP_BITS{1'b0}}, ar_rel[ADDR_WIDTH-1:CH_MSB], ar_rel[DROP_LSB-1:0]};

  // AW: address rewritten, everything else passed through
  assign m_axi_awid    = s_axi_awid;
  assign m_axi_awaddr  = aw_compact;
  assign m_axi_awlen   = s_axi_awlen;
  assign m_axi_awsize  = s_axi_awsize;
  assign m_axi_awburst = s_axi_awburst;
  assign m_axi_awlock  = s_axi_awlock;
  assign m_axi_awcache = s_axi_awcache;
  assign m_axi_awprot  = s_axi_awprot;
  assign m_axi_awqos   = s_axi_awqos;
  assign m_axi_awvalid = s_axi_awvalid;
  assign s_axi_awready = m_axi_awready;

  // W
  assign m_axi_wdata   = s_axi_wdata;
  assign m_axi_wstrb   = s_axi_wstrb;
  assign m_axi_wlast   = s_axi_wlast;
  assign m_axi_wvalid  = s_axi_wvalid;
  assign s_axi_wready  = m_axi_wready;

  // B
  assign s_axi_bid     = m_axi_bid;
  assign s_axi_bresp   = m_axi_bresp;
  assign s_axi_bvalid  = m_axi_bvalid;
  assign m_axi_bready  = s_axi_bready;

  // AR: address rewritten, everything else passed through
  assign m_axi_arid    = s_axi_arid;
  assign m_axi_araddr  = ar_compact;
  assign m_axi_arlen   = s_axi_arlen;
  assign m_axi_arsize  = s_axi_arsize;
  assign m_axi_arburst = s_axi_arburst;
  assign m_axi_arlock  = s_axi_arlock;
  assign m_axi_arcache = s_axi_arcache;
  assign m_axi_arprot  = s_axi_arprot;
  assign m_axi_arqos   = s_axi_arqos;
  assign m_axi_arvalid = s_axi_arvalid;
  assign s_axi_arready = m_axi_arready;

  // R
  assign s_axi_rid     = m_axi_rid;
  assign s_axi_rdata   = m_axi_rdata;
  assign s_axi_rresp   = m_axi_rresp;
  assign s_axi_rlast   = m_axi_rlast;
  assign s_axi_rvalid  = m_axi_rvalid;
  assign m_axi_rready  = s_axi_rready;

endmodule
