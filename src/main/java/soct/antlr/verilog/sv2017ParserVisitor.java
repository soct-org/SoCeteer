// Generated from /src/main/resources/sv2017Parser.g4 by ANTLR 4.9.3
package soct.antlr.verilog;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link sv2017Parser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface sv2017ParserVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#source_text}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSource_text(sv2017Parser.Source_textContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#description}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDescription(sv2017Parser.DescriptionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#assignment_operator}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAssignment_operator(sv2017Parser.Assignment_operatorContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#edge_identifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEdge_identifier(sv2017Parser.Edge_identifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#identifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdentifier(sv2017Parser.IdentifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#integer_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInteger_type(sv2017Parser.Integer_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#integer_atom_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInteger_atom_type(sv2017Parser.Integer_atom_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#integer_vector_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInteger_vector_type(sv2017Parser.Integer_vector_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#non_integer_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNon_integer_type(sv2017Parser.Non_integer_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#net_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNet_type(sv2017Parser.Net_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#unary_module_path_operator}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnary_module_path_operator(sv2017Parser.Unary_module_path_operatorContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#unary_operator}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnary_operator(sv2017Parser.Unary_operatorContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#inc_or_dec_operator}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInc_or_dec_operator(sv2017Parser.Inc_or_dec_operatorContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#implicit_class_handle}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitImplicit_class_handle(sv2017Parser.Implicit_class_handleContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#integral_number}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIntegral_number(sv2017Parser.Integral_numberContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#real_number}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitReal_number(sv2017Parser.Real_numberContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#any_system_tf_identifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAny_system_tf_identifier(sv2017Parser.Any_system_tf_identifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#signing}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSigning(sv2017Parser.SigningContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#number}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNumber(sv2017Parser.NumberContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#timeunits_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTimeunits_declaration(sv2017Parser.Timeunits_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#lifetime}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLifetime(sv2017Parser.LifetimeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#port_direction}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPort_direction(sv2017Parser.Port_directionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#always_keyword}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAlways_keyword(sv2017Parser.Always_keywordContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#join_keyword}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitJoin_keyword(sv2017Parser.Join_keywordContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#unique_priority}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnique_priority(sv2017Parser.Unique_priorityContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#drive_strength}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDrive_strength(sv2017Parser.Drive_strengthContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#strength0}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStrength0(sv2017Parser.Strength0Context ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#strength1}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStrength1(sv2017Parser.Strength1Context ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#charge_strength}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCharge_strength(sv2017Parser.Charge_strengthContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#sequence_lvar_port_direction}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequence_lvar_port_direction(sv2017Parser.Sequence_lvar_port_directionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#bins_keyword}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBins_keyword(sv2017Parser.Bins_keywordContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#class_item_qualifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClass_item_qualifier(sv2017Parser.Class_item_qualifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#random_qualifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRandom_qualifier(sv2017Parser.Random_qualifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#property_qualifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_qualifier(sv2017Parser.Property_qualifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#method_qualifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMethod_qualifier(sv2017Parser.Method_qualifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#constraint_prototype_qualifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstraint_prototype_qualifier(sv2017Parser.Constraint_prototype_qualifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#cmos_switchtype}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCmos_switchtype(sv2017Parser.Cmos_switchtypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#enable_gatetype}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEnable_gatetype(sv2017Parser.Enable_gatetypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#mos_switchtype}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMos_switchtype(sv2017Parser.Mos_switchtypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#n_input_gatetype}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitN_input_gatetype(sv2017Parser.N_input_gatetypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#n_output_gatetype}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitN_output_gatetype(sv2017Parser.N_output_gatetypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#pass_en_switchtype}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPass_en_switchtype(sv2017Parser.Pass_en_switchtypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#pass_switchtype}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPass_switchtype(sv2017Parser.Pass_switchtypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#any_implication}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAny_implication(sv2017Parser.Any_implicationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#timing_check_event_control}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTiming_check_event_control(sv2017Parser.Timing_check_event_controlContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#import_export}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitImport_export(sv2017Parser.Import_exportContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#array_method_name}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArray_method_name(sv2017Parser.Array_method_nameContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#operator_mul_div_mod}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOperator_mul_div_mod(sv2017Parser.Operator_mul_div_modContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#operator_plus_minus}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOperator_plus_minus(sv2017Parser.Operator_plus_minusContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#operator_shift}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOperator_shift(sv2017Parser.Operator_shiftContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#operator_cmp}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOperator_cmp(sv2017Parser.Operator_cmpContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#operator_eq_neq}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOperator_eq_neq(sv2017Parser.Operator_eq_neqContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#operator_xor}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOperator_xor(sv2017Parser.Operator_xorContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#operator_impl}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOperator_impl(sv2017Parser.Operator_implContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#udp_nonansi_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUdp_nonansi_declaration(sv2017Parser.Udp_nonansi_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#udp_ansi_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUdp_ansi_declaration(sv2017Parser.Udp_ansi_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#udp_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUdp_declaration(sv2017Parser.Udp_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#udp_declaration_port_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUdp_declaration_port_list(sv2017Parser.Udp_declaration_port_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#udp_port_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUdp_port_declaration(sv2017Parser.Udp_port_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#udp_output_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUdp_output_declaration(sv2017Parser.Udp_output_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#udp_input_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUdp_input_declaration(sv2017Parser.Udp_input_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#udp_reg_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUdp_reg_declaration(sv2017Parser.Udp_reg_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#udp_body}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUdp_body(sv2017Parser.Udp_bodyContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#combinational_body}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCombinational_body(sv2017Parser.Combinational_bodyContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#combinational_entry}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCombinational_entry(sv2017Parser.Combinational_entryContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#sequential_body}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequential_body(sv2017Parser.Sequential_bodyContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#udp_initial_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUdp_initial_statement(sv2017Parser.Udp_initial_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#sequential_entry}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequential_entry(sv2017Parser.Sequential_entryContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#seq_input_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSeq_input_list(sv2017Parser.Seq_input_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#level_input_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLevel_input_list(sv2017Parser.Level_input_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#edge_input_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEdge_input_list(sv2017Parser.Edge_input_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#edge_indicator}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEdge_indicator(sv2017Parser.Edge_indicatorContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#current_state}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCurrent_state(sv2017Parser.Current_stateContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#next_state}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNext_state(sv2017Parser.Next_stateContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#interface_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInterface_declaration(sv2017Parser.Interface_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#interface_header}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInterface_header(sv2017Parser.Interface_headerContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#interface_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInterface_item(sv2017Parser.Interface_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#modport_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModport_declaration(sv2017Parser.Modport_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#modport_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModport_item(sv2017Parser.Modport_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#modport_ports_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModport_ports_declaration(sv2017Parser.Modport_ports_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#modport_clocking_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModport_clocking_declaration(sv2017Parser.Modport_clocking_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#modport_simple_ports_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModport_simple_ports_declaration(sv2017Parser.Modport_simple_ports_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#modport_simple_port}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModport_simple_port(sv2017Parser.Modport_simple_portContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#modport_tf_ports_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModport_tf_ports_declaration(sv2017Parser.Modport_tf_ports_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#modport_tf_port}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModport_tf_port(sv2017Parser.Modport_tf_portContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#statement_or_null}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStatement_or_null(sv2017Parser.Statement_or_nullContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#initial_construct}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInitial_construct(sv2017Parser.Initial_constructContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#default_clocking_or_dissable_construct}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDefault_clocking_or_dissable_construct(sv2017Parser.Default_clocking_or_dissable_constructContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStatement(sv2017Parser.StatementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#statement_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStatement_item(sv2017Parser.Statement_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#cycle_delay}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCycle_delay(sv2017Parser.Cycle_delayContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#clocking_drive}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClocking_drive(sv2017Parser.Clocking_driveContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#clockvar_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClockvar_expression(sv2017Parser.Clockvar_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#final_construct}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFinal_construct(sv2017Parser.Final_constructContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#blocking_assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBlocking_assignment(sv2017Parser.Blocking_assignmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#procedural_timing_control_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProcedural_timing_control_statement(sv2017Parser.Procedural_timing_control_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#procedural_timing_control}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProcedural_timing_control(sv2017Parser.Procedural_timing_controlContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#event_control}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEvent_control(sv2017Parser.Event_controlContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#delay_or_event_control}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDelay_or_event_control(sv2017Parser.Delay_or_event_controlContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#delay3}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDelay3(sv2017Parser.Delay3Context ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#delay2}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDelay2(sv2017Parser.Delay2Context ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#delay_value}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDelay_value(sv2017Parser.Delay_valueContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#delay_control}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDelay_control(sv2017Parser.Delay_controlContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#nonblocking_assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNonblocking_assignment(sv2017Parser.Nonblocking_assignmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#procedural_continuous_assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProcedural_continuous_assignment(sv2017Parser.Procedural_continuous_assignmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#variable_assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariable_assignment(sv2017Parser.Variable_assignmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#action_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAction_block(sv2017Parser.Action_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#seq_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSeq_block(sv2017Parser.Seq_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#par_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPar_block(sv2017Parser.Par_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#case_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCase_statement(sv2017Parser.Case_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#case_keyword}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCase_keyword(sv2017Parser.Case_keywordContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#case_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCase_item(sv2017Parser.Case_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#case_pattern_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCase_pattern_item(sv2017Parser.Case_pattern_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#case_inside_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCase_inside_item(sv2017Parser.Case_inside_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#randcase_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRandcase_statement(sv2017Parser.Randcase_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#randcase_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRandcase_item(sv2017Parser.Randcase_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#cond_predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCond_predicate(sv2017Parser.Cond_predicateContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#conditional_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConditional_statement(sv2017Parser.Conditional_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#subroutine_call_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSubroutine_call_statement(sv2017Parser.Subroutine_call_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#disable_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDisable_statement(sv2017Parser.Disable_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#event_trigger}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEvent_trigger(sv2017Parser.Event_triggerContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#loop_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLoop_statement(sv2017Parser.Loop_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_variable_assignments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_variable_assignments(sv2017Parser.List_of_variable_assignmentsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#for_initialization}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFor_initialization(sv2017Parser.For_initializationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#for_variable_declaration_var_assign}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFor_variable_declaration_var_assign(sv2017Parser.For_variable_declaration_var_assignContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#for_variable_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFor_variable_declaration(sv2017Parser.For_variable_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#for_step}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFor_step(sv2017Parser.For_stepContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#loop_variables}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLoop_variables(sv2017Parser.Loop_variablesContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#jump_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitJump_statement(sv2017Parser.Jump_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#wait_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWait_statement(sv2017Parser.Wait_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#name_of_instance}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitName_of_instance(sv2017Parser.Name_of_instanceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#checker_instantiation}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitChecker_instantiation(sv2017Parser.Checker_instantiationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_checker_port_connections}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_checker_port_connections(sv2017Parser.List_of_checker_port_connectionsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#ordered_checker_port_connection}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrdered_checker_port_connection(sv2017Parser.Ordered_checker_port_connectionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#named_checker_port_connection}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNamed_checker_port_connection(sv2017Parser.Named_checker_port_connectionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#procedural_assertion_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProcedural_assertion_statement(sv2017Parser.Procedural_assertion_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#concurrent_assertion_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConcurrent_assertion_statement(sv2017Parser.Concurrent_assertion_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#assertion_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAssertion_item(sv2017Parser.Assertion_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#concurrent_assertion_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConcurrent_assertion_item(sv2017Parser.Concurrent_assertion_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#immediate_assertion_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitImmediate_assertion_statement(sv2017Parser.Immediate_assertion_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#simple_immediate_assertion_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSimple_immediate_assertion_statement(sv2017Parser.Simple_immediate_assertion_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#simple_immediate_assert_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSimple_immediate_assert_statement(sv2017Parser.Simple_immediate_assert_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#simple_immediate_assume_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSimple_immediate_assume_statement(sv2017Parser.Simple_immediate_assume_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#simple_immediate_cover_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSimple_immediate_cover_statement(sv2017Parser.Simple_immediate_cover_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#deferred_immediate_assertion_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDeferred_immediate_assertion_statement(sv2017Parser.Deferred_immediate_assertion_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#primitive_delay}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimitive_delay(sv2017Parser.Primitive_delayContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#deferred_immediate_assert_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDeferred_immediate_assert_statement(sv2017Parser.Deferred_immediate_assert_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#deferred_immediate_assume_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDeferred_immediate_assume_statement(sv2017Parser.Deferred_immediate_assume_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#deferred_immediate_cover_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDeferred_immediate_cover_statement(sv2017Parser.Deferred_immediate_cover_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#weight_specification}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitWeight_specification(sv2017Parser.Weight_specificationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#production_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProduction_item(sv2017Parser.Production_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#rs_code_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRs_code_block(sv2017Parser.Rs_code_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#randsequence_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRandsequence_statement(sv2017Parser.Randsequence_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#rs_prod}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRs_prod(sv2017Parser.Rs_prodContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#rs_if_else}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRs_if_else(sv2017Parser.Rs_if_elseContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#rs_repeat}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRs_repeat(sv2017Parser.Rs_repeatContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#rs_case}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRs_case(sv2017Parser.Rs_caseContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#rs_case_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRs_case_item(sv2017Parser.Rs_case_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#rs_rule}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRs_rule(sv2017Parser.Rs_ruleContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#rs_production_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRs_production_list(sv2017Parser.Rs_production_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#production}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProduction(sv2017Parser.ProductionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#tf_item_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTf_item_declaration(sv2017Parser.Tf_item_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#tf_port_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTf_port_list(sv2017Parser.Tf_port_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#tf_port_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTf_port_item(sv2017Parser.Tf_port_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#tf_port_direction}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTf_port_direction(sv2017Parser.Tf_port_directionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#tf_port_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTf_port_declaration(sv2017Parser.Tf_port_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_tf_variable_identifiers_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_tf_variable_identifiers_item(sv2017Parser.List_of_tf_variable_identifiers_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_tf_variable_identifiers}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_tf_variable_identifiers(sv2017Parser.List_of_tf_variable_identifiersContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#expect_property_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpect_property_statement(sv2017Parser.Expect_property_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#block_item_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBlock_item_declaration(sv2017Parser.Block_item_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#param_assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParam_assignment(sv2017Parser.Param_assignmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#type_assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitType_assignment(sv2017Parser.Type_assignmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_type_assignments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_type_assignments(sv2017Parser.List_of_type_assignmentsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_param_assignments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_param_assignments(sv2017Parser.List_of_param_assignmentsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#local_parameter_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLocal_parameter_declaration(sv2017Parser.Local_parameter_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#parameter_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParameter_declaration(sv2017Parser.Parameter_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#type_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitType_declaration(sv2017Parser.Type_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#net_type_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNet_type_declaration(sv2017Parser.Net_type_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#let_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLet_declaration(sv2017Parser.Let_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#let_port_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLet_port_list(sv2017Parser.Let_port_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#let_port_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLet_port_item(sv2017Parser.Let_port_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#let_formal_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLet_formal_type(sv2017Parser.Let_formal_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#package_import_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPackage_import_declaration(sv2017Parser.Package_import_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#package_import_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPackage_import_item(sv2017Parser.Package_import_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#property_list_of_arguments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_list_of_arguments(sv2017Parser.Property_list_of_argumentsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#property_actual_arg}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_actual_arg(sv2017Parser.Property_actual_argContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#property_formal_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_formal_type(sv2017Parser.Property_formal_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#sequence_formal_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequence_formal_type(sv2017Parser.Sequence_formal_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#property_instance}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_instance(sv2017Parser.Property_instanceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#property_spec}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_spec(sv2017Parser.Property_specContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#property_expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_expr(sv2017Parser.Property_exprContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#property_case_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_case_item(sv2017Parser.Property_case_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#bit_select}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBit_select(sv2017Parser.Bit_selectContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#identifier_with_bit_select}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdentifier_with_bit_select(sv2017Parser.Identifier_with_bit_selectContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#package_or_class_scoped_hier_id_with_select}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPackage_or_class_scoped_hier_id_with_select(sv2017Parser.Package_or_class_scoped_hier_id_with_selectContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#package_or_class_scoped_path_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPackage_or_class_scoped_path_item(sv2017Parser.Package_or_class_scoped_path_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#package_or_class_scoped_path}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPackage_or_class_scoped_path(sv2017Parser.Package_or_class_scoped_pathContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#hierarchical_identifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchical_identifier(sv2017Parser.Hierarchical_identifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#package_or_class_scoped_id}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPackage_or_class_scoped_id(sv2017Parser.Package_or_class_scoped_idContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#select}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSelect(sv2017Parser.SelectContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#event_expression_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEvent_expression_item(sv2017Parser.Event_expression_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#event_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEvent_expression(sv2017Parser.Event_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#boolean_abbrev}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBoolean_abbrev(sv2017Parser.Boolean_abbrevContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#sequence_abbrev}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequence_abbrev(sv2017Parser.Sequence_abbrevContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#consecutive_repetition}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConsecutive_repetition(sv2017Parser.Consecutive_repetitionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#non_consecutive_repetition}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNon_consecutive_repetition(sv2017Parser.Non_consecutive_repetitionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#goto_repetition}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGoto_repetition(sv2017Parser.Goto_repetitionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#cycle_delay_const_range_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCycle_delay_const_range_expression(sv2017Parser.Cycle_delay_const_range_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#sequence_instance}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequence_instance(sv2017Parser.Sequence_instanceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#sequence_expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequence_expr(sv2017Parser.Sequence_exprContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#sequence_match_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequence_match_item(sv2017Parser.Sequence_match_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#operator_assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOperator_assignment(sv2017Parser.Operator_assignmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#sequence_actual_arg}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequence_actual_arg(sv2017Parser.Sequence_actual_argContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dist_weight}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDist_weight(sv2017Parser.Dist_weightContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#clocking_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClocking_declaration(sv2017Parser.Clocking_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#clocking_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClocking_item(sv2017Parser.Clocking_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_clocking_decl_assign}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_clocking_decl_assign(sv2017Parser.List_of_clocking_decl_assignContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#clocking_decl_assign}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClocking_decl_assign(sv2017Parser.Clocking_decl_assignContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#default_skew}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDefault_skew(sv2017Parser.Default_skewContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#clocking_direction}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClocking_direction(sv2017Parser.Clocking_directionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#clocking_skew}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClocking_skew(sv2017Parser.Clocking_skewContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#clocking_event}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClocking_event(sv2017Parser.Clocking_eventContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#cycle_delay_range}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCycle_delay_range(sv2017Parser.Cycle_delay_rangeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#expression_or_dist}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpression_or_dist(sv2017Parser.Expression_or_distContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#covergroup_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCovergroup_declaration(sv2017Parser.Covergroup_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#cover_cross}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCover_cross(sv2017Parser.Cover_crossContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#identifier_list_2plus}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdentifier_list_2plus(sv2017Parser.Identifier_list_2plusContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#cross_body}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCross_body(sv2017Parser.Cross_bodyContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#cross_body_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCross_body_item(sv2017Parser.Cross_body_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#bins_selection_or_option}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBins_selection_or_option(sv2017Parser.Bins_selection_or_optionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#bins_selection}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBins_selection(sv2017Parser.Bins_selectionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#select_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSelect_expression(sv2017Parser.Select_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#select_condition}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSelect_condition(sv2017Parser.Select_conditionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#bins_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBins_expression(sv2017Parser.Bins_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#covergroup_range_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCovergroup_range_list(sv2017Parser.Covergroup_range_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#covergroup_value_range}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCovergroup_value_range(sv2017Parser.Covergroup_value_rangeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#covergroup_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCovergroup_expression(sv2017Parser.Covergroup_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#coverage_spec_or_option}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCoverage_spec_or_option(sv2017Parser.Coverage_spec_or_optionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#coverage_option}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCoverage_option(sv2017Parser.Coverage_optionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#coverage_spec}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCoverage_spec(sv2017Parser.Coverage_specContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#cover_point}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCover_point(sv2017Parser.Cover_pointContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#bins_or_empty}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBins_or_empty(sv2017Parser.Bins_or_emptyContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#bins_or_options}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBins_or_options(sv2017Parser.Bins_or_optionsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#trans_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTrans_list(sv2017Parser.Trans_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#trans_set}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTrans_set(sv2017Parser.Trans_setContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#trans_range_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTrans_range_list(sv2017Parser.Trans_range_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#repeat_range}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRepeat_range(sv2017Parser.Repeat_rangeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#coverage_event}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCoverage_event(sv2017Parser.Coverage_eventContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#block_event_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBlock_event_expression(sv2017Parser.Block_event_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#hierarchical_btf_identifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchical_btf_identifier(sv2017Parser.Hierarchical_btf_identifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#assertion_variable_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAssertion_variable_declaration(sv2017Parser.Assertion_variable_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dist_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDist_item(sv2017Parser.Dist_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#value_range}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValue_range(sv2017Parser.Value_rangeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#attribute_instance}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttribute_instance(sv2017Parser.Attribute_instanceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#attr_spec}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAttr_spec(sv2017Parser.Attr_specContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#class_new}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClass_new(sv2017Parser.Class_newContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#param_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParam_expression(sv2017Parser.Param_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#constant_param_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstant_param_expression(sv2017Parser.Constant_param_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#unpacked_dimension}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUnpacked_dimension(sv2017Parser.Unpacked_dimensionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#packed_dimension}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPacked_dimension(sv2017Parser.Packed_dimensionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#variable_dimension}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariable_dimension(sv2017Parser.Variable_dimensionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#struct_union}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStruct_union(sv2017Parser.Struct_unionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#enum_base_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEnum_base_type(sv2017Parser.Enum_base_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#data_type_primitive}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitData_type_primitive(sv2017Parser.Data_type_primitiveContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#data_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitData_type(sv2017Parser.Data_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#data_type_or_implicit}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitData_type_or_implicit(sv2017Parser.Data_type_or_implicitContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#implicit_data_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitImplicit_data_type(sv2017Parser.Implicit_data_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#sequence_list_of_arguments_named_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequence_list_of_arguments_named_item(sv2017Parser.Sequence_list_of_arguments_named_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#sequence_list_of_arguments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequence_list_of_arguments(sv2017Parser.Sequence_list_of_argumentsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_arguments_named_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_arguments_named_item(sv2017Parser.List_of_arguments_named_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_arguments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_arguments(sv2017Parser.List_of_argumentsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#primary_literal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimary_literal(sv2017Parser.Primary_literalContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#type_reference}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitType_reference(sv2017Parser.Type_referenceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#package_scope}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPackage_scope(sv2017Parser.Package_scopeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#ps_identifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPs_identifier(sv2017Parser.Ps_identifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_parameter_value_assignments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_parameter_value_assignments(sv2017Parser.List_of_parameter_value_assignmentsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#parameter_value_assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParameter_value_assignment(sv2017Parser.Parameter_value_assignmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#class_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClass_type(sv2017Parser.Class_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#class_scope}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClass_scope(sv2017Parser.Class_scopeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#range_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRange_expression(sv2017Parser.Range_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#constant_range_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstant_range_expression(sv2017Parser.Constant_range_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#constant_mintypmax_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstant_mintypmax_expression(sv2017Parser.Constant_mintypmax_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#mintypmax_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMintypmax_expression(sv2017Parser.Mintypmax_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#named_parameter_assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNamed_parameter_assignment(sv2017Parser.Named_parameter_assignmentContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryLit}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryLit(sv2017Parser.PrimaryLitContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryRandomize}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryRandomize(sv2017Parser.PrimaryRandomizeContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryAssig}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryAssig(sv2017Parser.PrimaryAssigContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryBitSelect}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryBitSelect(sv2017Parser.PrimaryBitSelectContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryTfCall}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryTfCall(sv2017Parser.PrimaryTfCallContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryTypeRef}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryTypeRef(sv2017Parser.PrimaryTypeRefContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryCallArrayMethodNoArgs}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryCallArrayMethodNoArgs(sv2017Parser.PrimaryCallArrayMethodNoArgsContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryCast}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryCast(sv2017Parser.PrimaryCastContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryPar}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryPar(sv2017Parser.PrimaryParContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryCall}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryCall(sv2017Parser.PrimaryCallContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryRandomize2}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryRandomize2(sv2017Parser.PrimaryRandomize2Context ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryDot}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryDot(sv2017Parser.PrimaryDotContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryStreaming_concatenation}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryStreaming_concatenation(sv2017Parser.PrimaryStreaming_concatenationContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryPath}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryPath(sv2017Parser.PrimaryPathContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryIndex}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryIndex(sv2017Parser.PrimaryIndexContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryCallWith}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryCallWith(sv2017Parser.PrimaryCallWithContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryConcat}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryConcat(sv2017Parser.PrimaryConcatContext ctx);
	/**
	 * Visit a parse tree produced by the {@code PrimaryCast2}
	 * labeled alternative in {@link sv2017Parser#primary}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPrimaryCast2(sv2017Parser.PrimaryCast2Context ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#constant_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstant_expression(sv2017Parser.Constant_expressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code Inc_or_dec_expressionPre}
	 * labeled alternative in {@link sv2017Parser#inc_or_dec_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInc_or_dec_expressionPre(sv2017Parser.Inc_or_dec_expressionPreContext ctx);
	/**
	 * Visit a parse tree produced by the {@code Inc_or_dec_expressionPost}
	 * labeled alternative in {@link sv2017Parser#inc_or_dec_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInc_or_dec_expressionPost(sv2017Parser.Inc_or_dec_expressionPostContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpression(sv2017Parser.ExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#concatenation}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConcatenation(sv2017Parser.ConcatenationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dynamic_array_new}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDynamic_array_new(sv2017Parser.Dynamic_array_newContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#const_or_range_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConst_or_range_expression(sv2017Parser.Const_or_range_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#variable_decl_assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariable_decl_assignment(sv2017Parser.Variable_decl_assignmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#assignment_pattern_variable_lvalue}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAssignment_pattern_variable_lvalue(sv2017Parser.Assignment_pattern_variable_lvalueContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#stream_operator}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStream_operator(sv2017Parser.Stream_operatorContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#slice_size}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSlice_size(sv2017Parser.Slice_sizeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#streaming_concatenation}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStreaming_concatenation(sv2017Parser.Streaming_concatenationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#stream_concatenation}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStream_concatenation(sv2017Parser.Stream_concatenationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#stream_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStream_expression(sv2017Parser.Stream_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#array_range_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArray_range_expression(sv2017Parser.Array_range_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#open_range_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOpen_range_list(sv2017Parser.Open_range_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#pattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPattern(sv2017Parser.PatternContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#assignment_pattern}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAssignment_pattern(sv2017Parser.Assignment_patternContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#structure_pattern_key}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStructure_pattern_key(sv2017Parser.Structure_pattern_keyContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#array_pattern_key}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitArray_pattern_key(sv2017Parser.Array_pattern_keyContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#assignment_pattern_key}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAssignment_pattern_key(sv2017Parser.Assignment_pattern_keyContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#struct_union_member}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStruct_union_member(sv2017Parser.Struct_union_memberContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#data_type_or_void}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitData_type_or_void(sv2017Parser.Data_type_or_voidContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#enum_name_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEnum_name_declaration(sv2017Parser.Enum_name_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#assignment_pattern_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAssignment_pattern_expression(sv2017Parser.Assignment_pattern_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#assignment_pattern_expression_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAssignment_pattern_expression_type(sv2017Parser.Assignment_pattern_expression_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#net_lvalue}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNet_lvalue(sv2017Parser.Net_lvalueContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#variable_lvalue}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariable_lvalue(sv2017Parser.Variable_lvalueContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#solve_before_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSolve_before_list(sv2017Parser.Solve_before_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#constraint_block_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstraint_block_item(sv2017Parser.Constraint_block_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#constraint_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstraint_expression(sv2017Parser.Constraint_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#uniqueness_constraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUniqueness_constraint(sv2017Parser.Uniqueness_constraintContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#constraint_set}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstraint_set(sv2017Parser.Constraint_setContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#randomize_call}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRandomize_call(sv2017Parser.Randomize_callContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#module_header_common}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModule_header_common(sv2017Parser.Module_header_commonContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#module_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModule_declaration(sv2017Parser.Module_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#module_keyword}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModule_keyword(sv2017Parser.Module_keywordContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#net_port_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNet_port_type(sv2017Parser.Net_port_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#var_data_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVar_data_type(sv2017Parser.Var_data_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#net_or_var_data_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNet_or_var_data_type(sv2017Parser.Net_or_var_data_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_defparam_assignments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_defparam_assignments(sv2017Parser.List_of_defparam_assignmentsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_net_decl_assignments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_net_decl_assignments(sv2017Parser.List_of_net_decl_assignmentsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_specparam_assignments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_specparam_assignments(sv2017Parser.List_of_specparam_assignmentsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_variable_decl_assignments}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_variable_decl_assignments(sv2017Parser.List_of_variable_decl_assignmentsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_variable_identifiers_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_variable_identifiers_item(sv2017Parser.List_of_variable_identifiers_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_variable_identifiers}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_variable_identifiers(sv2017Parser.List_of_variable_identifiersContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_variable_port_identifiers}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_variable_port_identifiers(sv2017Parser.List_of_variable_port_identifiersContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#defparam_assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDefparam_assignment(sv2017Parser.Defparam_assignmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#net_decl_assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNet_decl_assignment(sv2017Parser.Net_decl_assignmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#specparam_assignment}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSpecparam_assignment(sv2017Parser.Specparam_assignmentContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#error_limit_value}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitError_limit_value(sv2017Parser.Error_limit_valueContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#reject_limit_value}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitReject_limit_value(sv2017Parser.Reject_limit_valueContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#pulse_control_specparam}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPulse_control_specparam(sv2017Parser.Pulse_control_specparamContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#identifier_doted_index_at_end}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdentifier_doted_index_at_end(sv2017Parser.Identifier_doted_index_at_endContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#specify_terminal_descriptor}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSpecify_terminal_descriptor(sv2017Parser.Specify_terminal_descriptorContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#specify_input_terminal_descriptor}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSpecify_input_terminal_descriptor(sv2017Parser.Specify_input_terminal_descriptorContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#specify_output_terminal_descriptor}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSpecify_output_terminal_descriptor(sv2017Parser.Specify_output_terminal_descriptorContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#specify_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSpecify_item(sv2017Parser.Specify_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#pulsestyle_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPulsestyle_declaration(sv2017Parser.Pulsestyle_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#showcancelled_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitShowcancelled_declaration(sv2017Parser.Showcancelled_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#path_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPath_declaration(sv2017Parser.Path_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#simple_path_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSimple_path_declaration(sv2017Parser.Simple_path_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#path_delay_value}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPath_delay_value(sv2017Parser.Path_delay_valueContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_path_outputs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_path_outputs(sv2017Parser.List_of_path_outputsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_path_inputs}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_path_inputs(sv2017Parser.List_of_path_inputsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_paths}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_paths(sv2017Parser.List_of_pathsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_path_delay_expressions}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_path_delay_expressions(sv2017Parser.List_of_path_delay_expressionsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#t_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitT_path_delay_expression(sv2017Parser.T_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#trise_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTrise_path_delay_expression(sv2017Parser.Trise_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#tfall_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTfall_path_delay_expression(sv2017Parser.Tfall_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#tz_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTz_path_delay_expression(sv2017Parser.Tz_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#t01_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitT01_path_delay_expression(sv2017Parser.T01_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#t10_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitT10_path_delay_expression(sv2017Parser.T10_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#t0z_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitT0z_path_delay_expression(sv2017Parser.T0z_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#tz1_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTz1_path_delay_expression(sv2017Parser.Tz1_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#t1z_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitT1z_path_delay_expression(sv2017Parser.T1z_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#tz0_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTz0_path_delay_expression(sv2017Parser.Tz0_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#t0x_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitT0x_path_delay_expression(sv2017Parser.T0x_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#tx1_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTx1_path_delay_expression(sv2017Parser.Tx1_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#t1x_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitT1x_path_delay_expression(sv2017Parser.T1x_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#tx0_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTx0_path_delay_expression(sv2017Parser.Tx0_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#txz_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTxz_path_delay_expression(sv2017Parser.Txz_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#tzx_path_delay_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTzx_path_delay_expression(sv2017Parser.Tzx_path_delay_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#parallel_path_description}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParallel_path_description(sv2017Parser.Parallel_path_descriptionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#full_path_description}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFull_path_description(sv2017Parser.Full_path_descriptionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#identifier_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIdentifier_list(sv2017Parser.Identifier_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#specparam_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSpecparam_declaration(sv2017Parser.Specparam_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#edge_sensitive_path_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEdge_sensitive_path_declaration(sv2017Parser.Edge_sensitive_path_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#parallel_edge_sensitive_path_description}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParallel_edge_sensitive_path_description(sv2017Parser.Parallel_edge_sensitive_path_descriptionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#full_edge_sensitive_path_description}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFull_edge_sensitive_path_description(sv2017Parser.Full_edge_sensitive_path_descriptionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#data_source_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitData_source_expression(sv2017Parser.Data_source_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#data_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitData_declaration(sv2017Parser.Data_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#module_path_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModule_path_expression(sv2017Parser.Module_path_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#state_dependent_path_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitState_dependent_path_declaration(sv2017Parser.State_dependent_path_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#package_export_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPackage_export_declaration(sv2017Parser.Package_export_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#genvar_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGenvar_declaration(sv2017Parser.Genvar_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#net_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNet_declaration(sv2017Parser.Net_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#parameter_port_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParameter_port_list(sv2017Parser.Parameter_port_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#parameter_port_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParameter_port_declaration(sv2017Parser.Parameter_port_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_port_declarations_ansi_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_port_declarations_ansi_item(sv2017Parser.List_of_port_declarations_ansi_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_port_declarations}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_port_declarations(sv2017Parser.List_of_port_declarationsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#nonansi_port_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNonansi_port_declaration(sv2017Parser.Nonansi_port_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#nonansi_port}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNonansi_port(sv2017Parser.Nonansi_portContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#nonansi_port__expr}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNonansi_port__expr(sv2017Parser.Nonansi_port__exprContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#port_identifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPort_identifier(sv2017Parser.Port_identifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#ansi_port_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAnsi_port_declaration(sv2017Parser.Ansi_port_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#system_timing_check}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSystem_timing_check(sv2017Parser.System_timing_checkContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dolar_setup_timing_check}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDolar_setup_timing_check(sv2017Parser.Dolar_setup_timing_checkContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dolar_hold_timing_check}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDolar_hold_timing_check(sv2017Parser.Dolar_hold_timing_checkContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dolar_setuphold_timing_check}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDolar_setuphold_timing_check(sv2017Parser.Dolar_setuphold_timing_checkContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dolar_recovery_timing_check}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDolar_recovery_timing_check(sv2017Parser.Dolar_recovery_timing_checkContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dolar_removal_timing_check}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDolar_removal_timing_check(sv2017Parser.Dolar_removal_timing_checkContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dolar_recrem_timing_check}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDolar_recrem_timing_check(sv2017Parser.Dolar_recrem_timing_checkContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dolar_skew_timing_check}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDolar_skew_timing_check(sv2017Parser.Dolar_skew_timing_checkContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dolar_timeskew_timing_check}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDolar_timeskew_timing_check(sv2017Parser.Dolar_timeskew_timing_checkContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dolar_fullskew_timing_check}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDolar_fullskew_timing_check(sv2017Parser.Dolar_fullskew_timing_checkContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dolar_period_timing_check}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDolar_period_timing_check(sv2017Parser.Dolar_period_timing_checkContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dolar_width_timing_check}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDolar_width_timing_check(sv2017Parser.Dolar_width_timing_checkContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dolar_nochange_timing_check}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDolar_nochange_timing_check(sv2017Parser.Dolar_nochange_timing_checkContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#timecheck_condition}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTimecheck_condition(sv2017Parser.Timecheck_conditionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#controlled_reference_event}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitControlled_reference_event(sv2017Parser.Controlled_reference_eventContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#delayed_reference}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDelayed_reference(sv2017Parser.Delayed_referenceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#end_edge_offset}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEnd_edge_offset(sv2017Parser.End_edge_offsetContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#event_based_flag}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEvent_based_flag(sv2017Parser.Event_based_flagContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#notifier}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNotifier(sv2017Parser.NotifierContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#remain_active_flag}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRemain_active_flag(sv2017Parser.Remain_active_flagContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#timestamp_condition}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTimestamp_condition(sv2017Parser.Timestamp_conditionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#start_edge_offset}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStart_edge_offset(sv2017Parser.Start_edge_offsetContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#threshold}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitThreshold(sv2017Parser.ThresholdContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#timing_check_limit}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTiming_check_limit(sv2017Parser.Timing_check_limitContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#timing_check_event}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTiming_check_event(sv2017Parser.Timing_check_eventContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#timing_check_condition}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTiming_check_condition(sv2017Parser.Timing_check_conditionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#scalar_timing_check_condition}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitScalar_timing_check_condition(sv2017Parser.Scalar_timing_check_conditionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#controlled_timing_check_event}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitControlled_timing_check_event(sv2017Parser.Controlled_timing_check_eventContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#function_data_type_or_implicit}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunction_data_type_or_implicit(sv2017Parser.Function_data_type_or_implicitContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#extern_tf_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExtern_tf_declaration(sv2017Parser.Extern_tf_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#function_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunction_declaration(sv2017Parser.Function_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#task_prototype}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTask_prototype(sv2017Parser.Task_prototypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#function_prototype}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunction_prototype(sv2017Parser.Function_prototypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dpi_import_export}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDpi_import_export(sv2017Parser.Dpi_import_exportContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dpi_function_import_property}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDpi_function_import_property(sv2017Parser.Dpi_function_import_propertyContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#dpi_task_import_property}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDpi_task_import_property(sv2017Parser.Dpi_task_import_propertyContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#task_and_function_declaration_common}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTask_and_function_declaration_common(sv2017Parser.Task_and_function_declaration_commonContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#task_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTask_declaration(sv2017Parser.Task_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#method_prototype}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMethod_prototype(sv2017Parser.Method_prototypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#extern_constraint_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExtern_constraint_declaration(sv2017Parser.Extern_constraint_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#constraint_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstraint_block(sv2017Parser.Constraint_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#checker_port_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitChecker_port_list(sv2017Parser.Checker_port_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#checker_port_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitChecker_port_item(sv2017Parser.Checker_port_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#checker_port_direction}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitChecker_port_direction(sv2017Parser.Checker_port_directionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#checker_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitChecker_declaration(sv2017Parser.Checker_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#class_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClass_declaration(sv2017Parser.Class_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#always_construct}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAlways_construct(sv2017Parser.Always_constructContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#interface_class_type}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInterface_class_type(sv2017Parser.Interface_class_typeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#interface_class_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInterface_class_declaration(sv2017Parser.Interface_class_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#interface_class_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInterface_class_item(sv2017Parser.Interface_class_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#interface_class_method}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInterface_class_method(sv2017Parser.Interface_class_methodContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#package_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPackage_declaration(sv2017Parser.Package_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#package_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPackage_item(sv2017Parser.Package_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#program_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProgram_declaration(sv2017Parser.Program_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#program_header}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProgram_header(sv2017Parser.Program_headerContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#program_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProgram_item(sv2017Parser.Program_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#non_port_program_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNon_port_program_item(sv2017Parser.Non_port_program_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#anonymous_program}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAnonymous_program(sv2017Parser.Anonymous_programContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#anonymous_program_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAnonymous_program_item(sv2017Parser.Anonymous_program_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#sequence_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequence_declaration(sv2017Parser.Sequence_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#sequence_port_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequence_port_list(sv2017Parser.Sequence_port_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#sequence_port_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSequence_port_item(sv2017Parser.Sequence_port_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#property_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_declaration(sv2017Parser.Property_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#property_port_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_port_list(sv2017Parser.Property_port_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#property_port_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProperty_port_item(sv2017Parser.Property_port_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#continuous_assign}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitContinuous_assign(sv2017Parser.Continuous_assignContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#checker_or_generate_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitChecker_or_generate_item(sv2017Parser.Checker_or_generate_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#constraint_prototype}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstraint_prototype(sv2017Parser.Constraint_prototypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#class_constraint}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClass_constraint(sv2017Parser.Class_constraintContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#constraint_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConstraint_declaration(sv2017Parser.Constraint_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#class_constructor_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClass_constructor_declaration(sv2017Parser.Class_constructor_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#class_property}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClass_property(sv2017Parser.Class_propertyContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#class_method}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClass_method(sv2017Parser.Class_methodContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#class_constructor_prototype}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClass_constructor_prototype(sv2017Parser.Class_constructor_prototypeContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#class_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitClass_item(sv2017Parser.Class_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#parameter_override}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitParameter_override(sv2017Parser.Parameter_overrideContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#gate_instantiation}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGate_instantiation(sv2017Parser.Gate_instantiationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#enable_gate_or_mos_switch_or_cmos_switch_instance}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEnable_gate_or_mos_switch_or_cmos_switch_instance(sv2017Parser.Enable_gate_or_mos_switch_or_cmos_switch_instanceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#n_input_gate_instance}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitN_input_gate_instance(sv2017Parser.N_input_gate_instanceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#n_output_gate_instance}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitN_output_gate_instance(sv2017Parser.N_output_gate_instanceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#pass_switch_instance}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPass_switch_instance(sv2017Parser.Pass_switch_instanceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#pass_enable_switch_instance}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPass_enable_switch_instance(sv2017Parser.Pass_enable_switch_instanceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#pull_gate_instance}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPull_gate_instance(sv2017Parser.Pull_gate_instanceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#pulldown_strength}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPulldown_strength(sv2017Parser.Pulldown_strengthContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#pullup_strength}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPullup_strength(sv2017Parser.Pullup_strengthContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#enable_terminal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEnable_terminal(sv2017Parser.Enable_terminalContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#inout_terminal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInout_terminal(sv2017Parser.Inout_terminalContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#input_terminal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInput_terminal(sv2017Parser.Input_terminalContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#output_terminal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOutput_terminal(sv2017Parser.Output_terminalContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#udp_instantiation}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUdp_instantiation(sv2017Parser.Udp_instantiationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#udp_instance}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUdp_instance(sv2017Parser.Udp_instanceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#udp_instance_body}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUdp_instance_body(sv2017Parser.Udp_instance_bodyContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#module_or_interface_or_program_or_udp_instantiation}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModule_or_interface_or_program_or_udp_instantiation(sv2017Parser.Module_or_interface_or_program_or_udp_instantiationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#hierarchical_instance}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitHierarchical_instance(sv2017Parser.Hierarchical_instanceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#list_of_port_connections}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitList_of_port_connections(sv2017Parser.List_of_port_connectionsContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#ordered_port_connection}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrdered_port_connection(sv2017Parser.Ordered_port_connectionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#named_port_connection}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNamed_port_connection(sv2017Parser.Named_port_connectionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#bind_directive}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBind_directive(sv2017Parser.Bind_directiveContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#bind_target_instance}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBind_target_instance(sv2017Parser.Bind_target_instanceContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#bind_target_instance_list}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBind_target_instance_list(sv2017Parser.Bind_target_instance_listContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#bind_instantiation}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBind_instantiation(sv2017Parser.Bind_instantiationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#config_declaration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConfig_declaration(sv2017Parser.Config_declarationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#design_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDesign_statement(sv2017Parser.Design_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#config_rule_statement}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConfig_rule_statement(sv2017Parser.Config_rule_statementContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#inst_clause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInst_clause(sv2017Parser.Inst_clauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#inst_name}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitInst_name(sv2017Parser.Inst_nameContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#cell_clause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCell_clause(sv2017Parser.Cell_clauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#liblist_clause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLiblist_clause(sv2017Parser.Liblist_clauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#use_clause}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitUse_clause(sv2017Parser.Use_clauseContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#net_alias}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNet_alias(sv2017Parser.Net_aliasContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#specify_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSpecify_block(sv2017Parser.Specify_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#generate_region}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGenerate_region(sv2017Parser.Generate_regionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#genvar_expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGenvar_expression(sv2017Parser.Genvar_expressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#loop_generate_construct}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLoop_generate_construct(sv2017Parser.Loop_generate_constructContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#genvar_initialization}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGenvar_initialization(sv2017Parser.Genvar_initializationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#genvar_iteration}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGenvar_iteration(sv2017Parser.Genvar_iterationContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#conditional_generate_construct}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitConditional_generate_construct(sv2017Parser.Conditional_generate_constructContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#if_generate_construct}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIf_generate_construct(sv2017Parser.If_generate_constructContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#case_generate_construct}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCase_generate_construct(sv2017Parser.Case_generate_constructContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#case_generate_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCase_generate_item(sv2017Parser.Case_generate_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#generate_begin_end_block}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGenerate_begin_end_block(sv2017Parser.Generate_begin_end_blockContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#generate_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGenerate_item(sv2017Parser.Generate_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#program_generate_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitProgram_generate_item(sv2017Parser.Program_generate_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#module_or_generate_or_interface_or_checker_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModule_or_generate_or_interface_or_checker_item(sv2017Parser.Module_or_generate_or_interface_or_checker_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#module_or_generate_or_interface_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModule_or_generate_or_interface_item(sv2017Parser.Module_or_generate_or_interface_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#module_or_generate_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModule_or_generate_item(sv2017Parser.Module_or_generate_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#elaboration_system_task}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitElaboration_system_task(sv2017Parser.Elaboration_system_taskContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#module_item_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModule_item_item(sv2017Parser.Module_item_itemContext ctx);
	/**
	 * Visit a parse tree produced by {@link sv2017Parser#module_item}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModule_item(sv2017Parser.Module_itemContext ctx);
}