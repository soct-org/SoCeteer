library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

entity reset_sync_stretch is
    generic (
        ARESETN_RELEASE_CYCLES : integer := 15;
        RESET_RELEASE_CYCLES   : integer := 31
    );
    port (
        clock    : in  std_logic;
        dinp     : in  std_logic; -- active-high raw reset request
        aresetn  : out std_logic; -- active-low reset output
        reset    : out std_logic  -- active-high reset output
    );
end reset_sync_stretch;

architecture Behavioral of reset_sync_stretch is
    ATTRIBUTE X_INTERFACE_INFO : STRING;
    ATTRIBUTE X_INTERFACE_PARAMETER : STRING;

    ATTRIBUTE X_INTERFACE_INFO of clock: SIGNAL is "xilinx.com:signal:clock:1.0 clock CLK";
    ATTRIBUTE X_INTERFACE_PARAMETER of clock: SIGNAL is "ASSOCIATED_RESET aresetn:reset";

    ATTRIBUTE X_INTERFACE_INFO of dinp: SIGNAL is "xilinx.com:signal:reset:1.0 dinp RST";
    ATTRIBUTE X_INTERFACE_PARAMETER of dinp: SIGNAL is "POLARITY ACTIVE_HIGH";

    ATTRIBUTE X_INTERFACE_INFO of aresetn: SIGNAL is "xilinx.com:signal:reset:1.0 aresetn RST";
    ATTRIBUTE X_INTERFACE_PARAMETER of aresetn: SIGNAL is "POLARITY ACTIVE_LOW";

    ATTRIBUTE X_INTERFACE_INFO of reset: SIGNAL is "xilinx.com:signal:reset:1.0 reset RST";
    ATTRIBUTE X_INTERFACE_PARAMETER of reset: SIGNAL is "POLARITY ACTIVE_HIGH";

    signal shreg      : std_logic_vector(2 downto 0) := (others => '1');
    signal reset_sync : std_logic := '1';

    signal reset_cnt : unsigned(4 downto 0) := (others => '0');

    attribute SHREG_EXTRACT : string;
    attribute SHREG_EXTRACT of shreg : signal is "no";

    attribute ASYNC_REG : string;
    attribute ASYNC_REG of shreg : signal is "TRUE";

begin

    reset_sync <= shreg(2);

    process (clock)
    begin
        if rising_edge(clock) then
            shreg <= shreg(1 downto 0) & dinp;
        end if;
    end process;

    process (clock)
    begin
        if rising_edge(clock) then
            if reset_sync = '1' then
                reset_cnt <= (others => '0');
                aresetn   <= '0';
                reset     <= '1';
            elsif to_integer(reset_cnt) < ARESETN_RELEASE_CYCLES then
                reset_cnt <= reset_cnt + 1;
                aresetn   <= '0';
                reset     <= '1';
            elsif to_integer(reset_cnt) < RESET_RELEASE_CYCLES then
                reset_cnt <= reset_cnt + 1;
                aresetn   <= '1';
                reset     <= '1';
            else
                aresetn   <= '1';
                reset     <= '0';
            end if;
        end if;
    end process;

end Behavioral;