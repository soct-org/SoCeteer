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
 * mmap fakes coherence at page granularity instead of refusing outright: pages
 * are handed out write-protected, so the first store after a page is mapped (or
 * after it was last flushed) faults, and fb_deferred_io marks the page dirty.
 * A delayed worker then flushes the dirty pages through the same Flush64 path
 * the drawing operations use and re-protects them, so a user mapping is
 * coherent up to one flush-delay of latency - the same bound the console's own
 * damage worker already accepts. The trick needs a struct page per carve-out
 * page for fb_deferred_io's fault handler to hand out, which reserved no-map
 * memory is not guaranteed to have; where the probe for that fails, mmap is
 * refused as before. Reads, writes and the console's drawing operations
 * damage-track correctly regardless of mmap.
 *
 * When the carve-out holds two frames of the active mode, the fbdev exposes
 * them as a double-height virtual screen and whole-frame panning
 * (FBIOPAN_DISPLAY) becomes page flipping: render into the back frame, pan,
 * and the flush worker pushes the frame's damage out before parking the VDMA
 * on it at a frame boundary - tear-free presentation over the plain fbdev
 * UAPI. The console stays on frame 0 and never pans.
 *
 * On designs with a retunable pixel clock the fbdev also switches resolutions:
 * check_var accepts ANY complete timing whose pixel clock dp-main.c can solve
 * within the design's budget - there is no mode list; whether a monitor takes a
 * timing is the monitor's call - and set_par retunes the pipeline. Userspace
 * reaches it through the standard fbdev ioctl (the shell image ships `fbmode`,
 * which also confirms-and-reverts; its BusyBox has no fbset).
 */
#include <linux/fb.h>
#include <linux/io.h>
#include <linux/jiffies.h>
#include <linux/list.h>
#include <linux/math.h>
#include <linux/math64.h>
#include <linux/mm.h>
#include <linux/mmzone.h>
#include <linux/of.h>
#include <linux/of_address.h>
#include <linux/pfn.h>
#include <linux/printk.h>
#include <linux/spinlock.h>
#include <linux/uaccess.h>
#include <linux/workqueue.h>

#include "dp.h"
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
	resource_size_t carveout_size;
	struct soct_dp_mode mode;
	u32 width, height, stride;
	u32 frames; /* 2 when the carve-out holds a second frame: panning flips */
	bool mmap_ok; /* carve-out has struct pages; fb_deferred_io can track it */
	u32 defio_size; /* frame size fb_deferred_io was sized for, set at registration */
	spinlock_t lock;
	bool dirty;
	u32 x0, y0, x1, y1; /* dirty rectangle, inclusive; valid while dirty */
	bool park_req;  /* a pan asked for a flip; the flush worker applies it */
	u32 park_frame; /* ...to this frame, after pushing the damage out */
	struct delayed_work flush_work;
	u32 palette[16];
} fbs;

/* Whether a second frame of this geometry fits behind the first. */
static u32 fb_nframes(u32 stride, u32 height)
{
	return 2ull * stride * height <= fbs.carveout_size ? 2 : 1;
}

static void flush_range(phys_addr_t start, size_t len)
{
	phys_addr_t a = start & ~(phys_addr_t)(CACHE_LINE - 1);

	/* writeq, never a raw-write loop: a trigger that arrives while the
	 * previous line's flush is still in flight is dropped, and the dropped
	 * lines scan out as cache-line-striped columns of stale DRAM. The
	 * per-write fence spaces the triggers far enough apart that each one
	 * lands on an idle flush engine - it is pacing as much as ordering. */
	for (; a < start + len; a += CACHE_LINE)
		writeq(a, fbs.l2_ctrl + L2_FLUSH64_OFFSET);
}

static void soct_dp_fb_flush_work(struct work_struct *work);

/* Pushes the pending damage out right now, from whatever context. The flush is a
 * loop of register writes - no locks beyond the damage lock, no sleeping - so it
 * is safe where the worker cannot run. */
static void soct_dp_fb_flush_now(void)
{
	soct_dp_fb_flush_work(&fbs.flush_work.work);
}

static void soct_dp_fb_flush_work(struct work_struct *work)
{
	unsigned long flags;
	u32 x0, y0, x1, y1, y, park_frame;
	bool dirty, park_req;

	spin_lock_irqsave(&fbs.lock, flags);
	dirty = fbs.dirty;
	x0 = fbs.x0;
	y0 = fbs.y0;
	x1 = fbs.x1;
	y1 = fbs.y1;
	fbs.dirty = false;
	park_req = fbs.park_req;
	park_frame = fbs.park_frame;
	fbs.park_req = false;
	spin_unlock_irqrestore(&fbs.lock, flags);

	if (dirty)
		for (y = y0; y <= y1; y++)
			flush_range(fbs.phys + (phys_addr_t)y * fbs.stride + x0 * BYTES_PER_PIXEL,
				    (size_t)(x1 - x0 + 1) * BYTES_PER_PIXEL);
	/* Damage first, park second: by the time the scanout moves to the other
	 * frame, everything rendered into it is in DRAM. */
	if (park_req)
		soct_dp_set_scanout_frame(park_frame);
}

/* Callable from any context: the console draws from wherever printk runs. The
 * y range spans all frames of the carve-out - writes into the back frame (rows
 * height..2*height-1 of the virtual screen) damage-track like any others. */
static void soct_dp_fb_damage_area(struct fb_info *info, u32 x, u32 y, u32 w, u32 h)
{
	unsigned long flags;
	u32 vh = fbs.height * fbs.frames;
	u32 x1, y1;

	if (!w || !h || x >= fbs.width || y >= vh)
		return;
	x1 = min(x + w - 1, fbs.width - 1);
	y1 = min(y + h - 1, vh - 1);

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

	/* A dying kernel is exactly when the screen must be believed: the CPU that
	 * printed this may never run a worker again (an oops holding a CPU with
	 * interrupts disabled strands everything queued to it), which would leave
	 * the last messages - the interesting ones - recorded as damage and never
	 * pushed. Flush inline instead of deferring. */
	if (unlikely(oops_in_progress)) {
		soct_dp_fb_flush_now();
		return;
	}
	schedule_delayed_work(&fbs.flush_work, FLUSH_DELAY);
}

/* write() is linear in the buffer; the spanned lines become full-width damage. */
static void soct_dp_fb_damage_range(struct fb_info *info, off_t off, size_t len)
{
	u32 y0 = off / fbs.stride;
	u32 y1 = (off + len - 1) / fbs.stride;

	soct_dp_fb_damage_area(info, 0, y0, fbs.width, y1 - y0 + 1);
}

/* read()/write() copy directly between user memory and the carve-out. The
 * generic fb_io_* helpers bounce every page through a kmalloc buffer - twice
 * the memory traffic - and streaming bandwidth is this platform's frame-rate
 * ceiling for full-screen writers (the bounce costs ~60 ms on a 720p frame).
 * The carve-out is ordinary cacheable RAM, so no iomem accessors are needed. */
static ssize_t soct_dp_fb_read(struct fb_info *info, char __user *buf,
			       size_t count, loff_t *ppos)
{
	loff_t pos = *ppos;
	size_t total = info->fix.smem_len;

	if (pos < 0)
		return -EFBIG;
	if ((size_t)pos >= total || !count)
		return 0;
	if (count > total - pos)
		count = total - pos;
	if (copy_to_user(buf, (u8 *)fbs.virt + pos, count))
		return -EFAULT;
	*ppos = pos + count;
	return count;
}

static ssize_t soct_dp_fb_write(struct fb_info *info, const char __user *buf,
				size_t count, loff_t *ppos)
{
	loff_t pos = *ppos;
	size_t total = info->fix.smem_len;

	if (pos < 0 || (size_t)pos > total)
		return -EFBIG;
	if (count > total - pos)
		count = total - pos;
	if (!count)
		return -ENOSPC;
	if (copy_from_user((u8 *)fbs.virt + pos, buf, count))
		return -EFAULT;
	*ppos = pos + count;
	soct_dp_fb_damage_range(info, pos, count);
	return count;
}

/* Slow-path drawing: the iomem helpers draw, the touched area becomes damage. */
static void soct_dp_fb_defio_fillrect(struct fb_info *info,
				      const struct fb_fillrect *rect)
{
	cfb_fillrect(info, rect);
	soct_dp_fb_damage_area(info, rect->dx, rect->dy, rect->width, rect->height);
}

static void soct_dp_fb_defio_copyarea(struct fb_info *info,
				      const struct fb_copyarea *area)
{
	cfb_copyarea(info, area);
	soct_dp_fb_damage_area(info, area->dx, area->dy, area->width, area->height);
}

static void soct_dp_fb_defio_imageblit(struct fb_info *info,
				       const struct fb_image *image)
{
	cfb_imageblit(info, image);
	soct_dp_fb_damage_area(info, image->dx, image->dy, image->width, image->height);
}

/* The generic drawing helpers have no 24bpp fast path - every pixel goes
 * through their bit-shifting slow loop, and with fbcon hardwired to
 * SCROLL_REDRAW (no legacy acceleration) a single console scroll redraws the
 * whole screen through imageblit. The two ops the console actually leans on
 * get fast paths here; everything else falls back to the damage-tracking cfb
 * wrappers above.
 *
 * Glyphs are 1-bit bitmaps: each source byte selects one of 256 precomputed
 * 8-pixel patterns, so a glyph row is one small memcpy instead of eight
 * bit-tested pixel writes. The pattern table depends only on the fg/bg pair,
 * which the console changes rarely; it is rebuilt on change. fbcon serializes
 * drawing, so the table needs no locking. */
static struct {
	u32 fg, bg;
	bool valid;
	u8 pat[256][8 * BYTES_PER_PIXEL];
} glyph;

static void soct_dp_fb_imageblit(struct fb_info *info, const struct fb_image *image)
{
	const u32 *pal = info->pseudo_palette;
	const u8 *src = image->data;
	u32 spitch = (image->width + 7) / 8;
	u32 fg, bg, y;

	if (image->depth != 1) {
		soct_dp_fb_defio_imageblit(info, image);
		return;
	}
	fg = pal[image->fg_color];
	bg = pal[image->bg_color];
	if (!glyph.valid || glyph.fg != fg || glyph.bg != bg) {
		for (u32 b = 0; b < 256; b++) {
			for (u32 bit = 0; bit < 8; bit++) {
				u32 c = (b & (0x80u >> bit)) ? fg : bg;
				u8 *p = &glyph.pat[b][bit * BYTES_PER_PIXEL];

				p[0] = c & 0xff;
				p[1] = (c >> 8) & 0xff;
				p[2] = (c >> 16) & 0xff;
			}
		}
		glyph.fg = fg;
		glyph.bg = bg;
		glyph.valid = true;
	}

	for (y = 0; y < image->height; y++) {
		u8 *dst = (u8 *)fbs.virt +
			  (size_t)(image->dy + y) * fbs.stride +
			  (size_t)image->dx * BYTES_PER_PIXEL;
		u32 rem = image->width;

		for (u32 i = 0; i < spitch; i++) {
			u32 px = min(rem, 8u);

			memcpy(dst, glyph.pat[src[i]], (size_t)px * BYTES_PER_PIXEL);
			dst += px * BYTES_PER_PIXEL;
			rem -= px;
		}
		src += spitch;
	}
	soct_dp_fb_damage_area(info, image->dx, image->dy, image->width, image->height);
}

/* Console clears are solid black (and cursors invert to greys): any color whose
 * three bytes are equal is a plain memset per line. */
static void soct_dp_fb_fillrect(struct fb_info *info, const struct fb_fillrect *rect)
{
	const u32 *pal = info->pseudo_palette;
	u32 c = pal[rect->color];
	u8 b = c & 0xff;

	if (rect->rop != ROP_COPY || b != ((c >> 8) & 0xff) || b != ((c >> 16) & 0xff)) {
		soct_dp_fb_defio_fillrect(info, rect);
		return;
	}
	for (u32 y = 0; y < rect->height; y++)
		memset((u8 *)fbs.virt + (size_t)(rect->dy + y) * fbs.stride +
			       (size_t)rect->dx * BYTES_PER_PIXEL,
		       b, (size_t)rect->width * BYTES_PER_PIXEL);
	soct_dp_fb_damage_area(info, rect->dx, rect->dy, rect->width, rect->height);
}

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

/* fb_deferred_io write-protects mmap'd pages; the first store after a page is
 * mapped (or last flushed) faults and fb_deferred_io tracks it, then calls back
 * here after FLUSH_DELAY with everything touched since. Flush each page through
 * the same Flush64 path the rect worker uses, merging adjacent pages into one
 * flush_range call the way the rect worker merges one dirty rectangle. Runs in
 * workqueue context - sleeping is fine, and fbs.lock is not needed: flush_range
 * is just raw register writes, nothing here touches the rect-worker's state. */
static void soct_dp_fb_deferred_io(struct fb_info *info, struct list_head *pagelist)
{
	struct fb_deferred_io_pageref *pageref;
	phys_addr_t start = 0;
	size_t len = 0;

	list_for_each_entry(pageref, pagelist, list) {
		phys_addr_t phys = fbs.phys + pageref->offset;

		if (len && phys == start + len) {
			len += PAGE_SIZE;
			continue;
		}
		if (len)
			flush_range(start, len);
		start = phys;
		len = PAGE_SIZE;
	}
	if (len)
		flush_range(start, len);
}

/* Installed on info->fbdefio only when fbs.mmap_ok (soct_dp_fb_register), which
 * also sets .delay = FLUSH_DELAY there: msecs_to_jiffies() is not a
 * compile-time constant, so it cannot sit in this initializer. */
static struct fb_deferred_io soct_dp_fb_defio = {
	/* The coalescing walk in soct_dp_fb_deferred_io merges contiguous
	 * offsets into one flush_range call; that merge only finds them if the
	 * pagerefs arrive in ascending offset order. */
	.sort_pagereflist = true,
	.deferred_io = soct_dp_fb_deferred_io,
};

static int soct_dp_fb_mmap(struct fb_info *info, struct vm_area_struct *vma)
{
	if (fbs.mmap_ok)
		return fb_deferred_io_mmap(info, vma);
	pr_warn_once("soct-dp: fb mmap refused - the carve-out has no struct pages for fb_deferred_io to track; use write()\n");
	return -ENODEV;
}

/* Whole-frame panning = page flipping: yoffset selects which frame the
 * scanout parks on (fix.ypanstep is the frame height, so nothing finer ever
 * validates). The pan returns immediately; the flush worker pushes the
 * frame's damage to DRAM and only then moves the park pointer, which the
 * VDMA applies at the next frame boundary - a flip can never show a torn or
 * stale frame. Rendering into the back frame while the front one is scanned
 * out is what makes the write path tear-free end to end. */
static int soct_dp_fb_pan_display(struct fb_var_screeninfo *var, struct fb_info *info)
{
	unsigned long flags;

	if (var->xoffset || var->yoffset % fbs.height)
		return -EINVAL;
	if (var->yoffset / fbs.height >= fbs.frames)
		return -EINVAL;
	spin_lock_irqsave(&fbs.lock, flags);
	fbs.park_frame = var->yoffset / fbs.height;
	fbs.park_req = true;
	spin_unlock_irqrestore(&fbs.lock, flags);
	/* Zero delay: the frame is finished - push it now, not a frame later.
	 * system_percpu_wq is the queue schedule_delayed_work() puts this work
	 * on - a work item must not move between workqueues. */
	mod_delayed_work(system_percpu_wq, &fbs.flush_work, 0);
	return 0;
}

static void soct_dp_fb_fill_var(struct fb_var_screeninfo *var, const struct soct_dp_mode *m)
{
	var->xres = m->hactive;
	var->yres = m->vactive;
	var->xres_virtual = m->hactive;
	var->yres_virtual = m->vactive *
			    fb_nframes(m->hactive * BYTES_PER_PIXEL, m->vactive);
	var->xoffset = 0;
	var->yoffset = 0;
	var->bits_per_pixel = 24;
	var->grayscale = 0;
	var->red = (struct fb_bitfield){ .offset = 16, .length = 8 };
	var->green = (struct fb_bitfield){ .offset = 8, .length = 8 };
	var->blue = (struct fb_bitfield){ .offset = 0, .length = 8 };
	var->transp = (struct fb_bitfield){ 0 };
	var->nonstd = 0;
	var->height = -1;
	var->width = -1;
	var->pixclock = DIV_ROUND_CLOSEST_ULL(1000000000000ULL, m->pixel_clock_hz);
	var->left_margin = m->hback_porch;
	var->right_margin = m->hfront_porch;
	var->upper_margin = m->vback_porch;
	var->lower_margin = m->vfront_porch;
	var->hsync_len = m->hsync_len;
	var->vsync_len = m->vsync_len;
	var->sync = (m->hsync_active ? FB_SYNC_HOR_HIGH_ACT : 0) |
		    (m->vsync_active ? FB_SYNC_VERT_HIGH_ACT : 0);
	var->vmode = FB_VMODE_NONINTERLACED;
}

/* The inverse of fill_var: a complete timing from the var. The refresh and the
 * clock both come out of the var's picosecond pixclock - the UAPI has no rate
 * field, the timing implies it. */
static int soct_dp_fb_var_to_mode(const struct fb_var_screeninfo *var,
				  struct soct_dp_mode *m)
{
	u64 dots;

	if (!var->pixclock || (var->vmode & FB_VMODE_MASK) != FB_VMODE_NONINTERLACED)
		return -EINVAL;
	*m = (struct soct_dp_mode){
		.hactive = var->xres,
		.hfront_porch = var->right_margin,
		.hsync_len = var->hsync_len,
		.hback_porch = var->left_margin,
		.vactive = var->yres,
		.vfront_porch = var->lower_margin,
		.vsync_len = var->vsync_len,
		.vback_porch = var->upper_margin,
		.hsync_active = !!(var->sync & FB_SYNC_HOR_HIGH_ACT),
		.vsync_active = !!(var->sync & FB_SYNC_VERT_HIGH_ACT),
		.pixel_clock_hz = DIV_ROUND_CLOSEST_ULL(1000000000000ULL, var->pixclock),
	};
	dots = (u64)var->pixclock *
	       (m->hactive + m->hfront_porch + m->hsync_len + m->hback_porch) *
	       (m->vactive + m->vfront_porch + m->vsync_len + m->vback_porch);
	/* NOT DIV_ROUND_CLOSEST_ULL: that macro truncates its divisor to 32 bits
	 * (do_div), and a frame time in picoseconds does not fit them. */
	m->fps = DIV64_U64_ROUND_CLOSEST(1000000000000ULL, dots);
	return m->fps ? 0 : -EINVAL;
}

/* Any complete timing is a candidate; dp-main.c decides whether the clock is
 * within this design's budget. The var comes back with the exactly-achieved
 * pixel clock. */
static int soct_dp_fb_check_var(struct fb_var_screeninfo *var, struct fb_info *info)
{
	struct soct_dp_mode m;
	u64 frame_size;

	if (soct_dp_fb_var_to_mode(var, &m))
		return -EINVAL;
	frame_size = (u64)m.hactive * m.vactive * BYTES_PER_PIXEL;
	if (frame_size > fbs.carveout_size)
		return -EINVAL;
	/* fb_deferred_io sizes its page tracking from the frame length at
	 * fb_deferred_io_init() time, which runs once at registration - it
	 * cannot grow later. The registration-time mode is therefore the real
	 * ceiling under mmap, tighter than the carve-out itself; real designs
	 * never hit this since they only ever run the one mode they were
	 * timing-closed for. Both frames count: mmap covers the whole virtual
	 * screen. */
	if (fbs.mmap_ok &&
	    frame_size * fb_nframes(m.hactive * BYTES_PER_PIXEL, m.vactive) >
		    fbs.defio_size)
		return -EINVAL;
	if (soct_dp_validate_mode(&m))
		return -EINVAL;
	soct_dp_fb_fill_var(var, &m);
	return 0;
}

static int soct_dp_fb_set_par(struct fb_info *info)
{
	struct soct_dp_mode m;
	unsigned long flags;
	size_t size;
	int err;

	if (soct_dp_fb_var_to_mode(&info->var, &m) || soct_dp_validate_mode(&m))
		return -EINVAL;
	if (soct_dp_mode_equal(&m, &fbs.mode))
		return 0; /* console switches re-activate the current mode constantly */

	/* Quiesce the damage machinery; the fbdev locks exclude concurrent drawing. */
	cancel_delayed_work_sync(&fbs.flush_work);
	spin_lock_irqsave(&fbs.lock, flags);
	fbs.dirty = false;
	fbs.park_req = false; /* the mode switch restarts the scanout on frame 0 */
	spin_unlock_irqrestore(&fbs.lock, flags);

	err = soct_dp_switch_mode(&m);
	if (err)
		return err;

	fbs.mode = m;
	fbs.width = m.hactive;
	fbs.height = m.vactive;
	fbs.stride = m.hactive * BYTES_PER_PIXEL;
	fbs.frames = fb_nframes(fbs.stride, fbs.height);
	size = (size_t)fbs.stride * fbs.height;
	info->fix.line_length = fbs.stride;
	info->fix.smem_len = size * fbs.frames;
	info->fix.ypanstep = fbs.frames == 2 ? fbs.height : 0;

	/* The old frames' bytes are garbage under the new stride: present black,
	 * pushed out to where the scanout reads. fbcon repaints on top. */
	memset(fbs.virt, 0, size * fbs.frames);
	flush_range(fbs.phys, size * fbs.frames);
	return 0;
}

static void soct_dp_fb_destroy(struct fb_info *info)
{
	cancel_delayed_work_sync(&fbs.flush_work);
	/* Flushes and frees whatever fb_deferred_io still has tracked, through
	 * the same fbs.l2_ctrl the callback uses - must run before that (and
	 * fbs.virt) are torn down below. */
	if (fbs.mmap_ok)
		fb_deferred_io_cleanup(info);
	memunmap(fbs.virt);
	fbs.virt = NULL;
	iounmap(fbs.l2_ctrl);
	fbs.l2_ctrl = NULL;
	framebuffer_release(info);
}

static const struct fb_ops soct_dp_fb_ops = {
	.owner = THIS_MODULE,
	.fb_check_var = soct_dp_fb_check_var,
	.fb_set_par = soct_dp_fb_set_par,
	.fb_read = soct_dp_fb_read,
	.fb_write = soct_dp_fb_write,
	.fb_fillrect = soct_dp_fb_fillrect,
	.fb_copyarea = soct_dp_fb_defio_copyarea,
	.fb_imageblit = soct_dp_fb_imageblit,
	.fb_pan_display = soct_dp_fb_pan_display,
	.fb_mmap = soct_dp_fb_mmap,
	.fb_setcolreg = soct_dp_fb_setcolreg,
	.fb_destroy = soct_dp_fb_destroy,
};

int soct_dp_fb_prepare(phys_addr_t fb, resource_size_t fb_size,
		       const struct soct_dp_mode *mode)
{
	struct device_node *l2 = of_find_compatible_node(NULL, NULL, "sifive,inclusivecache0");
	size_t size = (size_t)mode->hactive * BYTES_PER_PIXEL * mode->vactive;

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
		       &fb_size, mode->hactive, mode->vactive);
		goto err_unmap_l2;
	}
	/* Cacheable on purpose: rendering runs at cache speed, the flush worker makes
	 * it visible. The carve-out is no-map, so this is the only kernel mapping.
	 * The whole carve-out is mapped, not just this mode's frame: a mode switch
	 * may grow the frame within it. */
	fbs.virt = memremap(fb, fb_size, MEMREMAP_WB);
	if (!fbs.virt) {
		pr_err("soct-dp: cannot map the framebuffer carve-out\n");
		goto err_unmap_l2;
	}
	fbs.phys = fb;
	fbs.carveout_size = fb_size;
	/* The carve-out is no-map reserved memory: nothing guarantees the
	 * platform actually backed it with struct pages, which is what
	 * fb_deferred_io's fault handler hands out. Probe both ends rather
	 * than assume; a hole anywhere inside is not expected to happen
	 * without also failing at one of the ends. */
	fbs.mmap_ok = pfn_valid(PHYS_PFN(fb)) && pfn_valid(PHYS_PFN(fb + fb_size - 1));
	fbs.mode = *mode;
	fbs.width = mode->hactive;
	fbs.height = mode->vactive;
	fbs.stride = mode->hactive * BYTES_PER_PIXEL;
	fbs.frames = fb_nframes(fbs.stride, fbs.height);
	spin_lock_init(&fbs.lock);
	INIT_DELAYED_WORK(&fbs.flush_work, soct_dp_fb_flush_work);

	/* The scanout must never fetch what DRAM held before: clear, then push it out. */
	memset(fbs.virt, 0, size * fbs.frames);
	flush_range(fbs.phys, size * fbs.frames);
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
		.smem_len = fbs.stride * fbs.height * fbs.frames,
		.line_length = fbs.stride,
		/* Panning granularity = a whole frame: yoffset picks a frame,
		 * nothing finer ever validates (and fbcon never pan-scrolls). */
		.ypanstep = fbs.frames == 2 ? fbs.height : 0,
		.accel = FB_ACCEL_NONE,
	};
	strscpy(info->fix.id, "soct-dp", sizeof(info->fix.id));
	soct_dp_fb_fill_var(&info->var, &fbs.mode);
	info->var.activate = FB_ACTIVATE_NOW;
	info->fbops = &soct_dp_fb_ops;
	info->pseudo_palette = fbs.palette;

	if (fbs.mmap_ok) {
		soct_dp_fb_defio.delay = FLUSH_DELAY;
		info->fbdefio = &soct_dp_fb_defio;
		err = fb_deferred_io_init(info);
		if (err) {
			pr_err("soct-dp: initializing deferred I/O failed (%d)\n", err);
			framebuffer_release(info);
			return err;
		}
		/* The ceiling soct_dp_fb_check_var must enforce from here on:
		 * fb_deferred_io_init() just sized its page tracking from
		 * info->fix.smem_len, fixed until fb_deferred_io_cleanup(). */
		fbs.defio_size = info->fix.smem_len;
	}

	err = register_framebuffer(info);
	if (err) {
		pr_err("soct-dp: registering the framebuffer failed (%d)\n", err);
		if (fbs.mmap_ok)
			fb_deferred_io_cleanup(info);
		framebuffer_release(info);
		return err;
	}
	fbs.info = info;
	/* No build stamp here: kbuild forbids __DATE__ (-Werror=date-time). The
	 * modules travel inside the boot image, whose identity /etc/soct-release
	 * carries - the banner and `soct` print it. */
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
