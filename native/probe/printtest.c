/*
 * Runs every option combination the app's DriverSettings can produce through the G2000 driver
 * with a generated test image, and reports whether the driver accepts it and how much data it makes.
 */
#include <gutenprint/gutenprint.h>
#include <stdio.h>
#include <string.h>

#define DRIVER "bjc-G2000-series"

typedef struct { int w, h; } rep_t;
static size_t g_bytes;
static unsigned char g_head[8];

static void im_noop(stp_image_t *i) { (void) i; }
static int im_w(stp_image_t *i) { return ((rep_t *) i->rep)->w; }
static int im_h(stp_image_t *i) { return ((rep_t *) i->rep)->h; }
static const char *im_app(stp_image_t *i) { (void) i; return "printtest"; }
static stp_image_status_t im_row(stp_image_t *i, unsigned char *d, size_t lim, int row) {
    rep_t *r = i->rep;
    for (int x = 0; x < r->w && (size_t) (x * 3 + 2) < lim; x++) {
        d[x * 3] = (unsigned char) (x * 255 / r->w);
        d[x * 3 + 1] = (unsigned char) (row * 255 / r->h);
        d[x * 3 + 2] = (unsigned char) ((x + row) % 256);
    }
    return STP_IMAGE_STATUS_OK;
}
static void out(void *d, const char *b, size_t n) {
    (void) d;
    if (g_bytes < 8) memcpy(g_head + g_bytes, b, n < 8 - g_bytes ? n : 8 - g_bytes);
    g_bytes += n;
}
static void err(void *d, const char *b, size_t n) { (void) d; fwrite(b, 1, n, stderr); }

static int run(const char *page, const char *media, const char *res, int bw, int bleed) {
    const stp_printer_t *p = stp_get_printer_by_driver(DRIVER);
    stp_vars_t *v = stp_vars_create_copy(stp_printer_get_defaults(p));
    stp_set_driver(v, DRIVER);
    stp_set_string_parameter(v, "PageSize", page);
    stp_set_string_parameter(v, "MediaType", media);
    stp_set_string_parameter(v, "InputSlot", "Rear");
    stp_set_string_parameter(v, "Quality", "None");
    stp_set_string_parameter(v, "Resolution", res);
    stp_set_string_parameter(v, "PrintingMode", bw ? "BW" : "Color");
    stp_set_string_parameter(v, "InkType", bw ? "Gray" : "CMYK");
    stp_set_string_parameter(v, "InputImageType", "RGB");
    stp_set_string_parameter(v, "ChannelBitDepth", "8");
    stp_set_string_parameter(v, "JobMode", "Page");
    stp_set_boolean_parameter(v, "FullBleed", bleed);
    stp_set_outfunc(v, out);
    stp_set_outdata(v, NULL);
    stp_set_errfunc(v, err);
    stp_set_errdata(v, NULL);
    stp_dimension_t l, r, b, t;
    stp_get_imageable_area(v, &l, &r, &b, &t);
    stp_set_left(v, l); stp_set_top(v, t); stp_set_width(v, r - l); stp_set_height(v, b - t);
    rep_t rep = { (int) ((r - l) / 72 * 75), (int) ((b - t) / 72 * 75) }; /* 75 dpi image keeps the test fast */
    stp_image_t im = { im_noop, im_noop, im_w, im_h, im_row, im_app, im_noop, &rep };
    g_bytes = 0;
    int ok = stp_verify(v) && stp_print(v, &im);
    printf("%-4s %-16s %-22s %-5s bleed=%d area=%.0f,%.0f-%.0f,%.0f -> %s %zu bytes head=%02x %02x %02x %02x\n",
           page, media, res, bw ? "BW" : "Color", bleed, l, t, r, b, ok ? "OK  " : "FAIL", g_bytes,
           g_head[0], g_head[1], g_head[2], g_head[3]);
    stp_vars_destroy(v);
    return ok;
}

int main(void) {
    stp_init();
    const char *media[] = {"Plain", "PhotoPlusGloss2", "PhotopaperMatte", "PhotopaperOther"};
    const char *plainRes[] = {"300x300dpi", "600x600dpi", "600x600dpi_high"};
    const char *photoRes[] = {"300x300dpi", "600x600dpi", "600x600dpi_high", "600x600dpi_photo", "600x600dpi_photohigh", "600x600dpi_photohigh2"};
    int fails = 0, total = 0;
    for (int m = 0; m < 4; m++) {
        int plain = m == 0;
        const char **res = plain ? plainRes : photoRes;
        int nres = plain ? 3 : 6;
        for (int i = 0; i < nres; i++)
            for (int bw = 0; bw < 2; bw++) {
                total++;
                if (!run("A4", media[m], res[i], bw, 0)) fails++;
                if (!plain) { total++; if (!run("A4", media[m], res[i], bw, 1)) fails++; }
            }
        total++;
        if (!run("w288h432", media[m], plain ? "600x600dpi" : "600x600dpi_photo", 0, !plain)) fails++;
    }
    printf("\n%d of %d combinations failed\n", fails, total);
    return fails ? 1 : 0;
}
