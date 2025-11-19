#pragma once

#define HTIF_DEV_SHIFT      56
#define HTIF_DEV_MASK       0xff
#define HTIF_CMD_SHIFT      48
#define HTIF_CMD_MASK       0xff
#define HTIF_PAYLOAD_MASK   ((1UL << HTIF_CMD_SHIFT) - 1)
#define HTIF_SECTION ".htif"
