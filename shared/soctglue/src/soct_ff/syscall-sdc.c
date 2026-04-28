#include <errno.h>
#include <string.h>
#include "ff.h"
#include "diskio.h"

#include "syscall-sdc.h"
#include "syscall-handler.h"
#include "soct/defaults.h"
#include "soct/smoldtb.h"
#include "soct/common.h"
#include "soct/soct_ff.h"

/* Card type flags (card_type) */
#define CT_MMC          0x01            /* MMC ver 3 */
#define CT_SD1          0x02            /* SD ver 1 */
#define CT_SD2          0x04            /* SD ver 2 */
#define CT_SDC          (CT_SD1|CT_SD2) /* SD */
#define CT_BLOCK        0x08            /* Block addressing */

#define CMD0    (0)             /* GO_IDLE_STATE */
#define CMD1    (1)             /* SEND_OP_COND */
#define CMD2    (2)             /* SEND_CID */
#define CMD3    (3)             /* RELATIVE_ADDR */
#define CMD4    (4)
#define CMD5    (5)             /* SLEEP_WAKE (SDC) */
#define CMD6    (6)             /* SWITCH_FUNC */
#define CMD7    (7)             /* SELECT */
#define CMD8    (8)             /* SEND_IF_COND */
#define CMD9    (9)             /* SEND_CSD */
#define CMD10   (10)            /* SEND_CID */
#define CMD11   (11)
#define CMD12   (12)            /* STOP_TRANSMISSION */
#define CMD13   (13)
#define CMD15   (15)
#define CMD16   (16)            /* SET_BLOCKLEN */
#define CMD17   (17)            /* READ_SINGLE_BLOCK */
#define CMD18   (18)            /* READ_MULTIPLE_BLOCK */
#define CMD19   (19)
#define CMD20   (20)
#define CMD23   (23)
#define CMD24   (24)
#define CMD25   (25)
#define CMD27   (27)
#define CMD28   (28)
#define CMD29   (29)
#define CMD30   (30)
#define CMD32   (32)
#define CMD33   (33)
#define CMD38   (38)
#define CMD42   (42)
#define CMD55   (55)            /* APP_CMD */
#define CMD56   (56)
#define ACMD6   (0x80+6)        /* define the data bus width */
#define ACMD41  (0x80+41)       /* SEND_OP_COND (ACMD) */

// Capability bits
#define SDC_CAPABILITY_SD_4BIT  0x0001
#define SDC_CAPABILITY_SD_RESET 0x0002
#define SDC_CAPABILITY_ADDR     0xff00

// Control bits
#define SDC_CONTROL_SD_4BIT     0x0001
#define SDC_CONTROL_SD_RESET    0x0002

// Card detect bits
#define SDC_CARD_INSERT_INT_EN  0x0001
#define SDC_CARD_INSERT_INT_REQ 0x0002
#define SDC_CARD_REMOVE_INT_EN  0x0004
#define SDC_CARD_REMOVE_INT_REQ 0x0008

// Command status bits
#define SDC_CMD_INT_STATUS_CC   0x0001  // Command complete
#define SDC_CMD_INT_STATUS_EI   0x0002  // Any error
#define SDC_CMD_INT_STATUS_CTE  0x0004  // Timeout
#define SDC_CMD_INT_STATUS_CCRC 0x0008  // CRC error
#define SDC_CMD_INT_STATUS_CIE  0x0010  // Command code check error

// Data status bits
#define SDC_DAT_INT_STATUS_TRS  0x0001  // Transfer complete
#define SDC_DAT_INT_STATUS_ERR  0x0002  // Any error
#define SDC_DAT_INT_STATUS_CTE  0x0004  // Timeout
#define SDC_DAT_INT_STATUS_CRC  0x0008  // CRC error
#define SDC_DAT_INT_STATUS_CFE  0x0010  // Data FIFO underrun or overrun

#define ERR_EOF             30
#define ERR_NOT_ELF         31
#define ERR_ELF_BITS        32
#define ERR_ELF_ENDIANNESS  33
#define ERR_CMD_CRC         34
#define ERR_CMD_CHECK       35
#define ERR_DATA_CRC        36
#define ERR_DATA_FIFO       37
#define ERR_BUF_ALIGNMENT   38

#define MAX_BLOCK_CNT 0x1000

struct sdc_regs {
    volatile uint32_t argument;
    volatile uint32_t command;
    volatile uint32_t response1;
    volatile uint32_t response2;
    volatile uint32_t response3;
    volatile uint32_t response4;
    volatile uint32_t data_timeout;
    volatile uint32_t control;
    volatile uint32_t cmd_timeout;
    volatile uint32_t clock_divider;
    volatile uint32_t software_reset;
    volatile uint32_t power_control;
    volatile uint32_t capability;
    volatile uint32_t cmd_int_status;
    volatile uint32_t cmd_int_enable;
    volatile uint32_t dat_int_status;
    volatile uint32_t dat_int_enable;
    volatile uint32_t block_size;
    volatile uint32_t block_count;
    volatile uint32_t card_detect;
    volatile uint32_t res_50;
    volatile uint32_t res_54;
    volatile uint32_t res_58;
    volatile uint32_t res_5c;
    volatile uint64_t dma_addres;
};

DSTATUS sdc_initialize(BYTE pdrv);

DSTATUS sdc_status(BYTE pdrv);

DRESULT sdc_read(BYTE pdrv, BYTE *buff, LBA_t sector, UINT count);

DRESULT sdc_write(BYTE pdrv, const BYTE *buff, LBA_t sector, UINT count);

DRESULT sdc_ioctl(BYTE pdrv, BYTE cmd, void *buff);


extern FATFS s_fs;
static struct sdc_regs *s_regs = (struct sdc_regs *) SOCT_DEFAULT_SDC_ADDR;
static BYTE s_card_type = 0;
static uint32_t s_response[4] = {0};
static DSTATUS s_drv_status;
static const soct_disk_ops_t s_sdc_ops = {
    .initialize = sdc_initialize,
    .status = sdc_status,
    .read = sdc_read,
    .write = sdc_write,
    .ioctl = sdc_ioctl,
};


int _sdc_cmd_finish(unsigned cmd) {
    while (1) {
        unsigned status = s_regs->cmd_int_status;
        if (status) {
            // clear interrupts
            s_regs->cmd_int_status = 0;
            while (s_regs->software_reset != 0) {
            }
            if (status == SDC_CMD_INT_STATUS_CC) {
                // get response
                s_response[0] = s_regs->response1;
                s_response[1] = s_regs->response2;
                s_response[2] = s_regs->response3;
                s_response[3] = s_regs->response4;
                return 0;
            }
            errno = FR_DISK_ERR;
            if (status & SDC_CMD_INT_STATUS_CTE)
                errno = FR_TIMEOUT;
            if (status & SDC_CMD_INT_STATUS_CCRC)
                errno = ERR_CMD_CRC;
            if (status & SDC_CMD_INT_STATUS_CIE)
                errno = ERR_CMD_CHECK;
            break;
        }
    }
    return -1;
}


int _sdc_data_finish(void) {
    int status;

    while ((status = s_regs->dat_int_status) == 0) {
    }
    s_regs->dat_int_status = 0;
    while (s_regs->software_reset != 0) {
    }

    if (status == SDC_DAT_INT_STATUS_TRS) return 0;
    errno = FR_DISK_ERR;
    if (status & SDC_DAT_INT_STATUS_CTE)
        errno = FR_TIMEOUT;
    if (status & SDC_DAT_INT_STATUS_CRC)
        errno = ERR_DATA_CRC;
    if (status & SDC_DAT_INT_STATUS_CFE)
        errno = ERR_DATA_FIFO;
    return -1;
}


int send_data_cmd(unsigned cmd, unsigned arg, void *buf, unsigned blocks) {
    unsigned command = (cmd & 0x3f) << 8;
    switch (cmd) {
        case CMD0:
        case CMD4:
        case CMD15:
            // No responce
            break;
        case CMD11:
        case CMD13:
        case CMD16:
        case CMD17:
        case CMD18:
        case CMD19:
        case CMD23:
        case CMD24:
        case CMD25:
        case CMD27:
        case CMD30:
        case CMD32:
        case CMD33:
        case CMD42:
        case CMD55:
        case CMD56:
        case ACMD6:
            // R1
            command |= 1; // 48 bits
            command |= 1 << 3; // resp CRC
            command |= 1 << 4; // resp OPCODE
            break;
        case CMD7:
        case CMD12:
        case CMD20:
        case CMD28:
        case CMD29:
        case CMD38:
            // R1b
            command |= 1; // 48 bits
            command |= 1 << 2; // busy
            command |= 1 << 3; // resp CRC
            command |= 1 << 4; // resp OPCODE
            break;
        case CMD2:
        case CMD9:
        case CMD10:
            // R2
            command |= 2; // 136 bits
            command |= 1 << 3; // resp CRC
            break;
        case ACMD41:
            // R3
            command |= 1; // 48 bits
            break;
        case CMD3:
            // R6
            command |= 1; // 48 bits
            command |= 1 << 2; // busy
            command |= 1 << 3; // resp CRC
            command |= 1 << 4; // resp OPCODE
            break;
        case CMD8:
            // R7
            command |= 1; // 48 bits
            command |= 1 << 3; // resp CRC
            command |= 1 << 4; // resp OPCODE
            break;
    }

    if (blocks) {
        command |= 1 << 5; // data transfer enable
        // Set write direction bit for write commands
        switch (cmd) {
            case CMD24:
            case CMD25:
            case CMD27:
            case CMD28:
            case CMD29:
            case CMD38:
                command |= 1 << 6; // write direction
                break;
            default:
                break;
        }
        if ((intptr_t) buf & 3) {
            errno = ERR_BUF_ALIGNMENT;
            return -1;
        }
        s_regs->dma_addres = (uint64_t) (intptr_t) buf;
        s_regs->block_size = 511;
        s_regs->block_count = blocks - 1;
        s_regs->data_timeout = 0xFFFFFF;
    }

    s_regs->command = command;
    s_regs->cmd_timeout = 0xFFFFF;
    s_regs->argument = arg;

    if (_sdc_cmd_finish(cmd) < 0) return -1;
    if (blocks) return _sdc_data_finish();

    return 0;
}

#define send_cmd(cmd, arg) send_data_cmd(cmd, arg, NULL, 0)


DSTATUS sdc_initialize(BYTE pdrv) {
    unsigned rca;

    /* Reset controller */
    s_regs->software_reset = 1;
    while ((s_regs->software_reset & 1) == 0) {
    }
    s_regs->clock_divider = 0x7c;
    s_regs->software_reset = 0;
    while (s_regs->software_reset) {
    }
    soct_usleep(5000);

    s_card_type = 0;
    s_drv_status = STA_NOINIT;

    if (s_regs->capability & SDC_CAPABILITY_SD_RESET) {
        /* Power cycle SD card */
        s_regs->control |= SDC_CONTROL_SD_RESET;
        soct_usleep(1000000);
        s_regs->control &= ~SDC_CONTROL_SD_RESET;
        soct_usleep(100000);
    }

    /* Enter Idle state */
    send_cmd(CMD0, 0);

    s_card_type = CT_SD1;
    if (send_cmd(CMD8, 0x1AA) == 0) {
        if ((s_response[0] & 0xfff) != 0x1AA) {
            errno = ERR_CMD_CHECK;
            return s_drv_status;
        }
        s_card_type = CT_SD2;
    }

    /* Wait for leaving idle state (ACMD41 with HCS bit) */
    while (1) {
        /* ACMD41, Set Operating Conditions: Host High Capacity & 3.3V */
        if (send_cmd(CMD55, 0) < 0 || send_cmd(ACMD41, 0x40300000) < 0) return s_drv_status;
        if (s_response[0] & (1 << 31)) {
            if (s_response[0] & (1 << 30)) s_card_type |= CT_BLOCK;
            break;
        }
    }

    /* Enter Identification state */
    if (send_cmd(CMD2, 0) < 0) return s_drv_status;

    /* Get RCA (Relative Card Address) */
    rca = 0x1234;
    if (send_cmd(CMD3, rca << 16) < 0) return s_drv_status;
    rca = s_response[0] >> 16;

    /* Select card */
    if (send_cmd(CMD7, rca << 16) < 0) return s_drv_status;

    /* Clock 25MHz */
    s_regs->clock_divider = 3;
    soct_usleep(10000);

    /* Bus width 1-bit */
    s_regs->control = 0;
    if (send_cmd(CMD55, rca << 16) < 0 || send_cmd(ACMD6, 0) < 0) return s_drv_status;

    /* Set R/W block length to 512 */
    if (send_cmd(CMD16, 512) < 0) return s_drv_status;

    s_drv_status &= ~STA_NOINIT;
    return s_drv_status;
}


DSTATUS sdc_status(BYTE pdrv) {
    return s_drv_status;
}


DRESULT sdc_read(BYTE pdrv, BYTE *buff, LBA_t sector, UINT count) {

    if (!count) return RES_PARERR;
    if (s_drv_status & STA_NOINIT) return RES_NOTRDY;

    /* Convert LBA to byte address if needed */
    if (!(s_card_type & CT_BLOCK)) sector *= 512;
    while (count > 0) {
        UINT bcnt = count > MAX_BLOCK_CNT ? MAX_BLOCK_CNT : count;
        unsigned bytes = bcnt * 512;
        if (send_data_cmd(bcnt == 1 ? CMD17 : CMD18, sector, buff, bcnt) < 0) return RES_ERROR;
        if (bcnt > 1 && send_cmd(CMD12, 0) < 0) return RES_ERROR;
        sector += (s_card_type & CT_BLOCK) ? bcnt : bytes;
        count -= bcnt;
        buff += bytes;
    }

    return RES_OK;
}


DRESULT sdc_write(BYTE pdrv, const BYTE *buff, LBA_t sector, UINT count) {
    if (!count) return RES_PARERR;
    if (s_drv_status & STA_NOINIT) return RES_NOTRDY;

    // Convert LBA to byte address if the card doesn't support block addressing
    if (!(s_card_type & CT_BLOCK)) sector *= 512;

    while (count > 0) {
        UINT bcnt = count > MAX_BLOCK_CNT ? MAX_BLOCK_CNT : count;
        unsigned bytes = bcnt * 512;

        if (send_data_cmd(bcnt == 1 ? CMD24 : CMD25, sector, (void *)buff, bcnt) < 0)
            return RES_ERROR;

        if (bcnt > 1 && send_cmd(CMD12, 0) < 0)
            return RES_ERROR;

        sector += (s_card_type & CT_BLOCK) ? bcnt : bytes;
        count -= bcnt;
        buff += bytes;
    }

    return RES_OK;
}


DRESULT sdc_ioctl(BYTE pdrv, BYTE cmd, void *buff) {
    if (s_drv_status & STA_NOINIT) return RES_NOTRDY;

    switch (cmd) {
        case CTRL_SYNC:
            // No caching used; assume always synced
            return RES_OK;

        case GET_SECTOR_COUNT:
            // Could return dummy value; real implementation needs CMD9 parsing
            *(LBA_t *)buff = 0x100000; // Example: 1 million sectors
            return RES_OK;

        case GET_SECTOR_SIZE:
            *(WORD *)buff = 512;
            return RES_OK;

        case GET_BLOCK_SIZE:
            *(DWORD *)buff = 1; // Erase block size in units of sectors (not always available)
            return RES_OK;

        default:
            return RES_PARERR;
    }
}


bool soct_init_from_dtb_sdc() {
    dtb_node *node = dtb_find_compatible(NULL, SOCT_SDC_NAME_DTS);
    if (!node) {
        soct_add_setup_msg("No SD card node found in DTB - SD card handler disabled");
        return false;
    }
    dtb_prop *reg_prop = dtb_find_prop(node, "reg");
    if (!reg_prop) {
        soct_add_setup_msg("SD card node found in DTB but it has no 'reg' property");
        return false;
    }
    const size_t addr_cells = dtb_get_addr_cells_for(node);
    uintmax_t regs[16] = {0};
    const size_t n = dtb_read_prop_1(reg_prop, addr_cells, regs);

    if (n < 1) {
        soct_add_setup_msg("SD card node found in DTB but failed to read base address from 'reg' property");
        return false;
    }

    s_regs = (struct sdc_regs *) regs[0];
    soct_set_mount_ops(&s_sdc_ops);
    const FRESULT res = f_mount(&s_fs, SOCT_SD_PATH, 1);
    if (res != FR_OK) {
        char msg[128];
        snprintf(msg, sizeof(msg), "SD card filesystem mount failed with error %d", res);
        soct_add_setup_msg(msg);
        return false;
    }
    char msg[128];
    snprintf(msg, sizeof(msg), "SD card node found in DTB with base address 0x%jx", regs[0]);
    soct_add_setup_msg(msg);
    return true;
}


void soct_handle_sdc(
    soct_handler_resp_t *resp,
    const uint32_t syscall,
    const uint64_t a0,
    const uint64_t a1,
    const uint64_t a2,
    const uint64_t a3,
    const uint64_t a4,
    const uint64_t a5,
    const uint64_t a6) {
    resp->status = SOCT_HANDLER_PASS;
}
