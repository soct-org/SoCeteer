#include <errno.h>
#include <string.h>
#include "ff.h"
#include "diskio.h"

#include "soct/soctglue.h"
#include "soct/syscall-handler.h"
#include "soct/defaults.h"
#include "soct/smoldtb.h"
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

extern void soct_add_setup_msg(const char *msg);

DSTATUS sdc_initialize(BYTE pdrv);

DSTATUS sdc_status(BYTE pdrv);

DRESULT sdc_read(BYTE pdrv, BYTE *buff, LBA_t sector, UINT count);

DRESULT sdc_write(BYTE pdrv, const BYTE *buff, LBA_t sector, UINT count);

DRESULT sdc_ioctl(BYTE pdrv, BYTE cmd, void *buff);

/* Volume names that are treated as SD card slots, in priority order */
static const char *const s_sd_vol_names[] = { SOCT_SD, SOCT_SD2 };
static const char *const s_sd_path_names[] = { SOCT_SD_PATH, SOCT_SD2_PATH };
#define SDC_MAX_DRIVES ((int)(sizeof(s_sd_vol_names) / sizeof(s_sd_vol_names[0])))

extern FATFS s_fs[FF_VOLUMES];

static struct sdc_regs *s_regs[FF_VOLUMES];
static BYTE s_card_type[FF_VOLUMES];
static uint32_t s_response[FF_VOLUMES][4];
static DSTATUS s_drv_status[FF_VOLUMES] = {
    [0 ... (FF_VOLUMES - 1)] = STA_NOINIT
};

static const soct_disk_ops_t s_sdc_ops = {
    .initialize = sdc_initialize,
    .status     = sdc_status,
    .read       = sdc_read,
    .write      = sdc_write,
    .ioctl      = sdc_ioctl,
};

/* Return the pdrv for a volume name, or -1 if not found */
static int find_pdrv(const char *vol_name) {
    for (int i = 0; i < FF_VOLUMES; i++) {
        if (strcmp(s_vol_str[i], vol_name) == 0)
            return i;
    }
    return -1;
}

static void soct_usleep(unsigned us) {
    uintptr_t cycles0;
    uintptr_t cycles1;
    __asm__ volatile ("csrr %0, 0xB00" : "=r" (cycles0));
    for (;;) {
        __asm__ volatile ("csrr %0, 0xB00" : "=r" (cycles1));
        if (cycles1 - cycles0 >= us * 100) break;
    }
}

static int _sdc_cmd_finish(BYTE pdrv, unsigned cmd) {
    while (1) {
        unsigned status = s_regs[pdrv]->cmd_int_status;
        if (status) {
            s_regs[pdrv]->cmd_int_status = 0;
            while (s_regs[pdrv]->software_reset != 0) {}
            if (status == SDC_CMD_INT_STATUS_CC) {
                s_response[pdrv][0] = s_regs[pdrv]->response1;
                s_response[pdrv][1] = s_regs[pdrv]->response2;
                s_response[pdrv][2] = s_regs[pdrv]->response3;
                s_response[pdrv][3] = s_regs[pdrv]->response4;
                return 0;
            }
            errno = FR_DISK_ERR;
            if (status & SDC_CMD_INT_STATUS_CTE)  errno = FR_TIMEOUT;
            if (status & SDC_CMD_INT_STATUS_CCRC) errno = ERR_CMD_CRC;
            if (status & SDC_CMD_INT_STATUS_CIE)  errno = ERR_CMD_CHECK;
            break;
        }
    }
    return -1;
}

static int _sdc_data_finish(BYTE pdrv) {
    int status;
    while ((status = s_regs[pdrv]->dat_int_status) == 0) {}
    s_regs[pdrv]->dat_int_status = 0;
    while (s_regs[pdrv]->software_reset != 0) {}

    if (status == SDC_DAT_INT_STATUS_TRS) return 0;
    errno = FR_DISK_ERR;
    if (status & SDC_DAT_INT_STATUS_CTE) errno = FR_TIMEOUT;
    if (status & SDC_DAT_INT_STATUS_CRC) errno = ERR_DATA_CRC;
    if (status & SDC_DAT_INT_STATUS_CFE) errno = ERR_DATA_FIFO;
    return -1;
}

static int send_data_cmd(BYTE pdrv, unsigned cmd, unsigned arg, void *buf, unsigned blocks) {
    unsigned command = (cmd & 0x3f) << 8;
    switch (cmd) {
        case CMD0:
        case CMD4:
        case CMD15:
            // No response
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
            command |= 1;       // 48 bits
            command |= 1 << 3;  // resp CRC
            command |= 1 << 4;  // resp OPCODE
            break;
        case CMD7:
        case CMD12:
        case CMD20:
        case CMD28:
        case CMD29:
        case CMD38:
            // R1b
            command |= 1;       // 48 bits
            command |= 1 << 2;  // busy
            command |= 1 << 3;  // resp CRC
            command |= 1 << 4;  // resp OPCODE
            break;
        case CMD2:
        case CMD9:
        case CMD10:
            // R2
            command |= 2;       // 136 bits
            command |= 1 << 3;  // resp CRC
            break;
        case ACMD41:
            // R3
            command |= 1;       // 48 bits
            break;
        case CMD3:
            // R6
            command |= 1;       // 48 bits
            command |= 1 << 2;  // busy
            command |= 1 << 3;  // resp CRC
            command |= 1 << 4;  // resp OPCODE
            break;
        case CMD8:
            // R7
            command |= 1;       // 48 bits
            command |= 1 << 3;  // resp CRC
            command |= 1 << 4;  // resp OPCODE
            break;
    }

    if (blocks) {
        command |= 1 << 5; // data transfer enable
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
        if ((intptr_t)buf & 3) {
            errno = ERR_BUF_ALIGNMENT;
            return -1;
        }
        s_regs[pdrv]->dma_addres    = (uint64_t)(intptr_t)buf;
        s_regs[pdrv]->block_size    = 511;
        s_regs[pdrv]->block_count   = blocks - 1;
        s_regs[pdrv]->data_timeout  = 0xFFFFFF;
    }

    s_regs[pdrv]->command     = command;
    s_regs[pdrv]->cmd_timeout = 0xFFFFF;
    s_regs[pdrv]->argument    = arg;

    if (_sdc_cmd_finish(pdrv, cmd) < 0) return -1;
    if (blocks) return _sdc_data_finish(pdrv);
    return 0;
}

#define send_cmd(pdrv, cmd, arg) send_data_cmd(pdrv, cmd, arg, NULL, 0)


DSTATUS sdc_initialize(BYTE pdrv) {
    unsigned rca;

    s_card_type[pdrv]  = 0;
    s_drv_status[pdrv] = STA_NOINIT;

    /* Reset controller */
    s_regs[pdrv]->software_reset = 1;
    while ((s_regs[pdrv]->software_reset & 1) == 0) {}
    s_regs[pdrv]->clock_divider = 0x7c;
    s_regs[pdrv]->software_reset = 0;
    while (s_regs[pdrv]->software_reset) {}
    soct_usleep(5000);

    if (s_regs[pdrv]->capability & SDC_CAPABILITY_SD_RESET) {
        /* Power cycle SD card */
        s_regs[pdrv]->control |= SDC_CONTROL_SD_RESET;
        soct_usleep(1000000);
        s_regs[pdrv]->control &= ~SDC_CONTROL_SD_RESET;
        soct_usleep(100000);
    }

    /* Enter Idle state */
    send_cmd(pdrv, CMD0, 0);

    s_card_type[pdrv] = CT_SD1;
    if (send_cmd(pdrv, CMD8, 0x1AA) == 0) {
        if ((s_response[pdrv][0] & 0xfff) != 0x1AA) {
            errno = ERR_CMD_CHECK;
            return s_drv_status[pdrv];
        }
        s_card_type[pdrv] = CT_SD2;
    }

    /* Wait for leaving idle state (ACMD41 with HCS bit) */
    while (1) {
        if (send_cmd(pdrv, CMD55, 0) < 0 || send_cmd(pdrv, ACMD41, 0x40300000) < 0)
            return s_drv_status[pdrv];
        if (s_response[pdrv][0] & (1 << 31)) {
            if (s_response[pdrv][0] & (1 << 30)) s_card_type[pdrv] |= CT_BLOCK;
            break;
        }
    }

    /* Enter Identification state */
    if (send_cmd(pdrv, CMD2, 0) < 0) return s_drv_status[pdrv];

    /* Get RCA (Relative Card Address) */
    rca = 0x1234;
    if (send_cmd(pdrv, CMD3, rca << 16) < 0) return s_drv_status[pdrv];
    rca = s_response[pdrv][0] >> 16;

    /* Select card */
    if (send_cmd(pdrv, CMD7, rca << 16) < 0) return s_drv_status[pdrv];

    /* Clock 25MHz */
    s_regs[pdrv]->clock_divider = 3;
    soct_usleep(10000);

    /* Bus width 1-bit */
    s_regs[pdrv]->control = 0;
    if (send_cmd(pdrv, CMD55, rca << 16) < 0 || send_cmd(pdrv, ACMD6, 0) < 0)
        return s_drv_status[pdrv];

    /* Set R/W block length to 512 */
    if (send_cmd(pdrv, CMD16, 512) < 0) return s_drv_status[pdrv];

    s_drv_status[pdrv] &= ~STA_NOINIT;
    return s_drv_status[pdrv];
}


DSTATUS sdc_status(BYTE pdrv) {
    return s_drv_status[pdrv];
}


DRESULT sdc_read(BYTE pdrv, BYTE *buff, LBA_t sector, UINT count) {
    if (!count) return RES_PARERR;
    if (s_drv_status[pdrv] & STA_NOINIT) return RES_NOTRDY;

    if (!(s_card_type[pdrv] & CT_BLOCK)) sector *= 512;
    while (count > 0) {
        UINT bcnt = count > MAX_BLOCK_CNT ? MAX_BLOCK_CNT : count;
        unsigned bytes = bcnt * 512;
        if (send_data_cmd(pdrv, bcnt == 1 ? CMD17 : CMD18, sector, buff, bcnt) < 0) return RES_ERROR;
        if (bcnt > 1 && send_cmd(pdrv, CMD12, 0) < 0) return RES_ERROR;
        sector += (s_card_type[pdrv] & CT_BLOCK) ? bcnt : bytes;
        count -= bcnt;
        buff += bytes;
    }
    return RES_OK;
}


DRESULT sdc_write(BYTE pdrv, const BYTE *buff, LBA_t sector, UINT count) {
    if (!count) return RES_PARERR;
    if (s_drv_status[pdrv] & STA_NOINIT) return RES_NOTRDY;

    if (!(s_card_type[pdrv] & CT_BLOCK)) sector *= 512;
    while (count > 0) {
        UINT bcnt = count > MAX_BLOCK_CNT ? MAX_BLOCK_CNT : count;
        unsigned bytes = bcnt * 512;
        if (send_data_cmd(pdrv, bcnt == 1 ? CMD24 : CMD25, sector, (void *)buff, bcnt) < 0)
            return RES_ERROR;
        if (bcnt > 1 && send_cmd(pdrv, CMD12, 0) < 0)
            return RES_ERROR;
        sector += (s_card_type[pdrv] & CT_BLOCK) ? bcnt : bytes;
        count -= bcnt;
        buff += bytes;
    }
    return RES_OK;
}


DRESULT sdc_ioctl(BYTE pdrv, BYTE cmd, void *buff) {
    if (s_drv_status[pdrv] & STA_NOINIT) return RES_NOTRDY;

    switch (cmd) {
        case CTRL_SYNC:
            return RES_OK;
        case GET_SECTOR_COUNT:
            *(LBA_t *)buff = 0x100000;
            return RES_OK;
        case GET_SECTOR_SIZE:
            *(WORD *)buff = 512;
            return RES_OK;
        case GET_BLOCK_SIZE:
            *(DWORD *)buff = 1;
            return RES_OK;
        default:
            return RES_PARERR;
    }
}


bool soct_init_from_dtb_sdc(void) {
    dtb_node *node = NULL;
    bool any_mounted = false;

    for (int slot = 0; slot < SDC_MAX_DRIVES; slot++) {
        node = dtb_find_compatible(node, SOCT_SDC_NAME_DTS);
        if (!node) break;

        const char *vol_name = s_sd_vol_names[slot];
        const int pdrv = find_pdrv(vol_name);
        if (pdrv < 0) {
            soct_add_setup_msg("SD card volume name not found in VolumeStr");
            continue;
        }

        dtb_prop *reg_prop = dtb_find_prop(node, "reg");
        if (!reg_prop) {
            soct_add_setup_msg("SD card node found in DTB but it has no 'reg' property");
            continue;
        }
        const size_t addr_cells = dtb_get_addr_cells_for(node);
        uintmax_t regs[16] = {0};
        if (dtb_read_prop_1(reg_prop, addr_cells, regs) < 1) {
            soct_add_setup_msg("SD card node found in DTB but failed to read base address from 'reg' property");
            continue;
        }

        s_regs[pdrv] = (struct sdc_regs *)regs[0];
        soct_set_mount_ops(&s_sdc_ops);

        const char *mount_path = s_sd_path_names[slot];
        const FRESULT res = f_mount(&s_fs[pdrv], mount_path, 1);
        if (res != FR_OK) {
            char msg[128];
            snprintf(msg, sizeof(msg), "%s: filesystem mount failed with error %d", mount_path, res);
            soct_add_setup_msg(msg);
            continue;
        }

        char msg[128];
        snprintf(msg, sizeof(msg), "%s: mounted from DTB node at 0x%lx", mount_path, regs[0]);
        soct_add_setup_msg(msg);
        any_mounted = true;
    }

    return any_mounted;
}
