# Variables requires: vivado_board_name, xilinx_part, vsrcs_dir, vhdlsrcs_dir, tclsrcs_dir, workspace_dir, boards_dir, block_design_tcl, riscv_clock_frequency, riscv_module_name
# Variables optional requires: vivado_board_part


# If there is no project opened, create a project
set list_projs [get_projects -quiet]
if { $list_projs eq "" } {
   create_project ${vivado_board_name}-riscv vivado-${vivado_board_name}-riscv -part ${xilinx_part}
   # Allow projects with no BOARD_PART set. xilinx_part and /board constraints can suffice.
   if {[info exists vivado_board_part]} {
      set_property BOARD_PART ${vivado_board_part} [current_project]
   }
}

# Create 'sources_1' fileset (if not found)
if {[string equal [get_filesets -quiet sources_1] ""]} {
  create_fileset -srcset sources_1
}

# Create 'constrs_1' fileset (if not found)
if {[string equal [get_filesets -quiet constrs_1] ""]} {
  create_fileset -constrset constrs_1
}

# Set 'sources_1' fileset object
set source_fileset [get_filesets sources_1]

# Set 'constrs_1' fileset object
set constraint_fileset [get_filesets constrs_1]

# Set files needed in this project. Should be absolute already but normalize for good measure
set files [list \
 [file normalize "${workspace_dir}/riscv_system.vhdl"] \
 [file normalize "${workspace_dir}/riscv_system.v"] \
 [file normalize "${vsrcs_dir}/uart/uart.v"] \
 [file normalize "${vsrcs_dir}/sdc/sd_defines.h"] \
 [file normalize "${vsrcs_dir}/sdc/axi_sdc_controller.v"] \
 [file normalize "${vsrcs_dir}/sdc/sd_cmd_master.v"] \
 [file normalize "${vsrcs_dir}/sdc/sd_cmd_serial_host.v"] \
 [file normalize "${vsrcs_dir}/sdc/sd_data_master.v"] \
 [file normalize "${vsrcs_dir}/sdc/sd_data_serial_host.v"] \
 [file normalize "${vhdlsrcs_dir}/bscan2jtag.vhdl"] \
 [file normalize "${vsrcs_dir}/board/mem-reset-control.v"] \
 [file normalize "${vsrcs_dir}/board/fan-control.v"] \
]
add_files -norecurse -fileset $source_fileset $files

if {[file exists "${boards_dir}/${vivado_board_name}/ethernet-${vivado_board_name}.v"]} {
  add_files -norecurse -fileset $source_fileset [file normalize ${boards_dir}/${vivado_board_name}/ethernet-${vivado_board_name}.v"]
}

# Note: top.xdc must be first - other files depend on clocks defined in top.xdc
set files [list \
 [file normalize "${boards_dir}/${vivado_board_name}/top.xdc"] \
 [file normalize "${boards_dir}/${vivado_board_name}/sdc.xdc"] \
 [file normalize "${boards_dir}/${vivado_board_name}/uart.xdc"] \
]
add_files -norecurse -fileset $constraint_fileset $files

# <major>, <minor>
set block_design_ver [split [version -short] .]

# riscv-<major>.<minor>.tcl
set block_design_tcl "riscv-[lindex $block_design_ver 0].[lindex $block_design_ver 1].tcl"

# If the board has ethernet support, include its config
if {[file exists "${boards_dir}/${vivado_board_name}/ethernet-${vivado_board_name}.tcl"]} {
  source ${boards_dir}/${vivado_board_name}/ethernet-${vivado_board_name}.tcl
}

# Note: timing-constraints.tcl must be last
add_files -norecurse -fileset $constraint_fileset [file normalize ${tclsrcs_dir}/timing-constraints.tcl]

# Set file properties
set file_obj [get_files -of_objects $source_fileset [list "*/*.vhdl"]]
set_property -name "file_type" -value "VHDL" -objects $file_obj

set file_obj [get_files -of_objects $constraint_fileset [list "*/*.xdc"]]
set_property -name "file_type" -value "XDC" -objects $file_obj
set_property -name "used_in" -value "implementation" -objects $file_obj
set_property -name "used_in_synthesis" -value "0" -objects $file_obj

set file_obj [get_files -of_objects $constraint_fileset [list "*/*.tcl"]]
set_property -name "file_type" -value "TCL" -objects $file_obj
set_property -name "used_in" -value "implementation" -objects $file_obj
set_property -name "used_in_synthesis" -value "0" -objects $file_obj

# Create block design
source ${boards_dir}/${vivado_board_name}/${block_design_tcl}

# If the RocketChip module has a JTAG interface
if { [llength [get_bd_intf_pins -quiet RocketChip/JTAG]] == 1 } {
  create_bd_cell -type module -reference bscan2jtag JTAG
  # Connects the JTAG interface pin of the bscan2jtag module to the RocketChip JTAG pin
  connect_bd_intf_net -intf_net JTAG [get_bd_intf_pins JTAG/JTAG] [get_bd_intf_pins RocketChip/JTAG]
  # Instantiates a Debug Bridge IP core from Xilinx, named BSCAN
  create_bd_cell -type ip -vlnv xilinx.com:ip:debug_bridge:3.0 BSCAN
  # DEBUG_MODE = 7 (JTAG-to-AXI, ChipScope, or JTAG-to-JTAG bridge), One user scan chain, One master
  set_property -dict [list CONFIG.C_DEBUG_MODE {7} CONFIG.C_USER_SCAN_CHAIN {1} CONFIG.C_NUM_BS_MASTER {1}] [get_bd_cells BSCAN]
  connect_bd_intf_net -intf_net BSCAN [get_bd_intf_pins BSCAN/m0_bscan] [get_bd_intf_pins JTAG/S_BSCAN]
}

set_property CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $riscv_clock_frequency [get_bd_cells clk_wiz_0]
validate_bd_design

regenerate_bd_layout
save_bd_design

if { [get_files -quiet -of_objects $source_fileset [list "*/riscv_wrapper.v"]] == "" } {
  make_wrapper -files [get_files riscv.bd] -top -import
}
set_property top riscv_wrapper $source_fileset
update_compile_order -fileset $source_fileset
