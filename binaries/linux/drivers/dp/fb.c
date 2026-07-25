// SPDX-License-Identifier: GPL-2.0
/*
 * The console framebuffer of incoherent-fetch video designs.
 *
 * On these designs the VDMA fetches frames through its own memory-controller port,
 * bypassing the coherent fabric: pixels the CPU renders sit in its caches where the
 * scanout never sees them. simplefb would scan stale memory, so the device tree
 * carries no /chosen framebuffer node and this file registers the framebuffer
 * device instead - an fbdev on the reserved scanout carve-out whose drawing
 * operations record a dirty rectangle, and a delayed worker that pushes the dirty
 * lines out through the L2's Flush64 register (one blocking write per 64-byte
 * line; the cache is inclusive, so a flush also pulls the line out of every L1).
 * Damage is coalesced for about a frame time before flushing, so a burst of
 * console output costs one flush of the union rectangle, not one per drawing call.
 *
 * mmap is refused: this core has no page attributes that could uncache a user
 * mapping, so userspace stores would sit in the cache with nothing tracking them -
 * a silently stale display. Reads, writes and the console's drawing operations all
 * damage-track correctly.
 */
#include <linux/fb.h>
#include <linux/io.h>
#include <linux/jiffies.h>
#include <linux/of.h>
#include <linux/of_address.h>
#include <linux/spinlock.h>
#include <linux/workqueue.h>

#include "fb.h"

/* InclusiveCache control: write a physical address to flush that 64-byte line. */
#define L2_FLUSH64_OFFSET 0x200u
#define CACHE_LINE 64u
#define BYTES_PER_PIXEL 3u /* r8g8b8: blue, green, red from the low address up */

/* One 60 fps frame time: the longest a rendered pixel waits before it is visible. */
#define FLUSH_DELAY msecs_to_jiffies(16)

static struct {
	struct fb_info *info;
	void __iomem *l2_ctrl;
	void *virt;
	phys_addr_t phys;
	u32 width, height, stride;
	spinlock_t lock;
	bool dirty;
	u32 x0, y0, x1, y1; /* dirty rectangle, inclusive; valid while dirty */
	struct delayed_work flush_work;
	u32 palette[16];
} fbs;

static void flush_range(phys_addr_t start, size_t len)
{
	phys_addr_t a = start & ~(phys_addr_t)(CACHE_LINE - 1);

	for (; a < start + len; a += CACHE_LINE)
		writeq(a, fbs.l2_ctrl + L2_FLUSH64_OFFSET);
}

static void soct_dp_fb_flush_work(struct work_struct *work)
{
	unsigned long flags;
	u32 x0, y0, x1, y1, y;

	spin_lock_irqsave(&fbs.lock, flags);
	if (!fbs.dirty) {
		spin_unlock_irqrestore(&fbs.lock, flags);
		return;
	}
	x0 = fbs.x0;
	y0 = fbs.y0;
	x1 = fbs.x1;
	y1 = fbs.y1;
	fbs.dirty = false;
	spin_unlock_irqrestore(&fbs.lock, flags);

	for (y = y0; y <= y1; y++)
		flush_range(fbs.phys + (phys_addr_t)y * fbs.stride + x0 * BYTES_PER_PIXEL,
			    (size_t)(x1 - x0 + 1) * BYTES_PER_PIXEL);
}

/* Callable from any context: the console draws from wherever printk runs. */
static void soct_dp_fb_damage_area(struct fb_info *info, u32 x, u32 y, u32 w, u32 h)
{
	unsigned long flags;
	u32 x1, y1;

	if (!w || !h || x >= fbs.width || y >= fbs.height)
		return;
	x1 = min(x + w - 1, fbs.width - 1);
	y1 = min(y + h - 1, fbs.height - 1);

	spin_lock_irqsave(&fbs.lock, flags);
	if (fbs.dirty) {
		fbs.x0 = min(fbs.x0, x);
		fbs.y0 = min(fbs.y0, y);
		fbs.x1 = max(fbs.x1, x1);
		fbs.y1 = max(fbs.y1, y1);
	} else {
		fbs.x0 = x;
		fbs.y0 = y;
		fbs.x1 = x1;
		fbs.y1 = y1;
		fbs.dirty = true;
	}
	spin_unlock_irqrestore(&fbs.lock, flags);

	schedule_delayed_work(&fbs.flush_work, FLUSH_DELAY);
}

/* write() is linear in the buffer; the spanned lines become full-width damage. */
static void soct_dp_fb_damage_range(struct fb_info *info, off_t off, size_t len)
{
	u32 y0 = off / fbs.stride;
	u32 y1 = (off + len - 1) / fbs.stride;

	soct_dp_fb_damage_area(info, 0, y0, fbs.width, y1 - y0 + 1);
}

FB_GEN_DEFAULT_DEFERRED_IOMEM_OPS(soct_dp_fb, soct_dp_fb_damage_range,
				  soct_dp_fb_damage_area)

static int soct_dp_fb_setcolreg(u_int regno, u_int red, u_int green, u_int blue,
				u_int transp, struct fb_info *info)
{
	u32 *pal = info->pseudo_palette;

	if (regno >= ARRAY_SIZE(fbs.palette))
		return -EINVAL;
	pal[regno] = ((red >> 8) << info->var.red.offset) |
		     ((green >> 8) << info->var.green.offset) |
		     ((blue >> 8) << info->var.blue.offset);
	return 0;
}

static int soct_dp_fb_mmap(struct fb_info *info, struct vm_area_struct *vma)
{
	pr_warn_once("soct-dp: fb mmap refused - a user mapping would be cached with nothing flushing it; use write()\n");
	return -ENODEV;
}

static void soct_dp_fb_destroy(struct fb_info *info)
{
	cancel_delayed_work_sync(&fbs.flush_work);
	memunmap(fbs.virt);
	fbs.virt = NULL;
	iounmap(fbs.l2_ctrl);
	fbs.l2_ctrl = NULL;
	framebuffer_release(info);
}

static const struct fb_ops soct_dp_fb_ops = {
	.owner = THIS_MODULE,
	.fb_read = soct_dp_fb_defio_read,
	.fb_write = soct_dp_fb_defio_write,
	.fb_fillrect = soct_dp_fb_defio_fillrect,
	.fb_copyarea = soct_dp_fb_defio_copyarea,
	.fb_imageblit = soct_dp_fb_defio_imageblit,
	.fb_mmap = soct_dp_fb_mmap,
	.fb_setcolreg = soct_dp_fb_setcolreg,
	.fb_destroy = soct_dp_fb_destroy,
};

int soct_dp_fb_prepare(phys_addr_t fb, resource_size_t fb_size, u32 width, u32 height)
{
	struct device_node *l2 = of_find_compatible_node(NULL, NULL, "sifive,inclusivecache0");
	size_t size = (size_t)width * BYTES_PER_PIXEL * height;

	if (!l2) {
		pr_err("soct-dp: the frame fetch is incoherent but the design has no L2 (sifive,inclusivecache0), so nothing can flush rendered pixels to where the scanout reads. Generate the design with soct.WithL2Cache.\n");
		return -ENODEV;
	}
	fbs.l2_ctrl = of_iomap(l2, 0);
	of_node_put(l2);
	if (!fbs.l2_ctrl) {
		pr_err("soct-dp: cannot map the L2 control registers\n");
		return -ENOMEM;
	}
	if (size > fb_size) {
		pr_err("soct-dp: the reserved framebuffer (%pap bytes) is smaller than one %ux%u frame\n",
		       &fb_size, width, height);
		goto err_unmap_l2;
	}
	/* Cacheable on purpose: rendering runs at cache speed, the flush worker makes
	 * it visible. The carve-out is no-map, so this is the only kernel mapping. */
	fbs.virt = memremap(fb, size, MEMREMAP_WB);
	if (!fbs.virt) {
		pr_err("soct-dp: cannot map the framebuffer carve-out\n");
		goto err_unmap_l2;
	}
	fbs.phys = fb;
	fbs.width = width;
	fbs.height = height;
	fbs.stride = width * BYTES_PER_PIXEL;
	spin_lock_init(&fbs.lock);
	INIT_DELAYED_WORK(&fbs.flush_work, soct_dp_fb_flush_work);

	/* The scanout must never fetch what DRAM held before: clear, then push it out. */
	memset(fbs.virt, 0, size);
	flush_range(fbs.phys, size);
	return 0;

err_unmap_l2:
	iounmap(fbs.l2_ctrl);
	fbs.l2_ctrl = NULL;
	return -EINVAL;
}

int soct_dp_fb_register(void)
{
	struct fb_info *info = framebuffer_alloc(0, NULL);
	int err;

	if (!info)
		return -ENOMEM;
	info->screen_base = (char __iomem *)fbs.virt;
	info->fix = (struct fb_fix_screeninfo){
		.type = FB_TYPE_PACKED_PIXELS,
		.visual = FB_VISUAL_TRUECOLOR,
		.smem_start = fbs.phys,
		.smem_len = fbs.stride * fbs.height,
		.line_length = fbs.stride,
		.accel = FB_ACCEL_NONE,
	};
	strscpy(info->fix.id, "soct-dp", sizeof(info->fix.id));
	info->var = (struct fb_var_screeninfo){
		.xres = fbs.width,
		.yres = fbs.height,
		.xres_virtual = fbs.width,
		.yres_virtual = fbs.height,
		.bits_per_pixel = 24,
		.red = { .offset = 16, .length = 8 },
		.green = { .offset = 8, .length = 8 },
		.blue = { .offset = 0, .length = 8 },
		.height = -1,
		.width = -1,
		.activate = FB_ACTIVATE_NOW,
		.vmode = FB_VMODE_NONINTERLACED,
	};
	info->fbops = &soct_dp_fb_ops;
	info->pseudo_palette = fbs.palette;

	err = register_framebuffer(info);
	if (err) {
		pr_err("soct-dp: registering the framebuffer failed (%d)\n", err);
		framebuffer_release(info);
		return err;
	}
	fbs.info = info;
	pr_info("soct-dp: fb%d: console framebuffer, dirty rectangles flushed through the L2\n",
		info->node);
	return 0;
}

void soct_dp_fb_teardown(void)
{
	if (fbs.info) {
		/* fb_destroy releases the mappings once the last reference drops. */
		unregister_framebuffer(fbs.info);
		fbs.info = NULL;
		return;
	}
	/* Prepared but never registered (bring-up failed before the display came up). */
	if (fbs.virt) {
		cancel_delayed_work_sync(&fbs.flush_work);
		memunmap(fbs.virt);
		fbs.virt = NULL;
	}
	if (fbs.l2_ctrl) {
		iounmap(fbs.l2_ctrl);
		fbs.l2_ctrl = NULL;
	}
}
