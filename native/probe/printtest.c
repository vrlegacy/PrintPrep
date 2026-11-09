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
static FILE *g_file;

static void im_noop(stp_image_t *i) { (void) i; }
static int im_w(stp_image_t *i) { return ((rep_t *) i->rep)->w; }
static int im_h(stp_image_t *i) { return ((rep_t *) i->rep)->h; }
static const char *im_app(stp_image_t *i) { (void) i; return "printtest"; }
static stp_image_status_t im_row(stp_image_t *i, unsigned char *d, size_t lim, int row) {
    rep_t *r = i->rep;
    static const unsigned char bars[7][3] = {{0,255,255},{255,0,255},{255,255,0},{0,0,0},{255,0,0},{0,255,0},{0,0,255}};
    int band = row * 20 / r->h;               /* page split into 20 horizontal bands */
    for (int x = 0; x < r->w && (size_t) (x * 3 + 2) < lim; x++) {
        unsigned char c[3] = {255, 255, 255};
        int col = x * 7 / r->w;
        if (band == 2 || band == 3) { c[0] = bars[col][0]; c[1] = bars[col][1]; c[2] = bars[col][2]; }      /* colour bars */
        else if (band == 5) { unsigned char g = (unsigned char) (255 - x * 255 / r->w); c[0] = c[1] = c[2] = g; } /* grey ramp */
        else if (band >= 7 && band <= 12 && (row / 6) % 4 == 0 && x > r->w / 10 && x < r->w * 9 / 10) { c[0] = c[1] = c[2] = 0; } /* black lines */
        if (x < 4 || x >= r->w - 4 || row < 4 || row >= r->h - 4) { c[0] = c[1] = c[2] = 0; }                 /* frame */
        d[x * 3] = c[0]; d[x * 3 + 1] = c[1]; d[x * 3 + 2] = c[2];
    }
    return STP_IMAGE_STATUS_OK;
}
static void out(void *d, const char *b, size_t n) {
    (void) d;
    if (g_bytes < 8) memcpy(g_head + g_bytes, b, n < 8 - g_bytes ? n : 8 - g_bytes);
    g_bytes += n;
    if (g_file) fwrite(b, 1, n, g_file);
}
static void err(void *d, const char *b, size_t n) { (void) d; fwrite(b, 1, n, stderr); }

/* Fills in the driver's own default for every required setting we haven't chosen
 * (e.g. ColorCorrection, DitherAlgorithm, Orientation). */
static void fill_defaults(stp_vars_t *v) {
    stp_parameter_list_t params = stp_get_parameter_list(v);
    size_t n = stp_parameter_list_count(params);
    for (size_t i = 0; i < n; i++) {
        const stp_parameter_t *p = stp_parameter_list_param(params, i);
        stp_parameter_t d;
        stp_describe_parameter(v, p->name, &d);
        if (d.is_active && d.is_mandatory) {
            switch (d.p_type) {
            case STP_PARAMETER_TYPE_STRING_LIST:
                if (!stp_check_string_parameter(v, p->name, STP_PARAMETER_DEFAULTED) && d.deflt.str)
                    stp_set_string_parameter(v, p->name, d.deflt.str);
                break;
            case STP_PARAMETER_TYPE_BOOLEAN:
                if (!stp_check_boolean_parameter(v, p->name, STP_PARAMETER_DEFAULTED))
                    stp_set_boolean_parameter(v, p->name, d.deflt.boolean);
                break;
            case STP_PARAMETER_TYPE_DOUBLE:
                if (!stp_check_float_parameter(v, p->name, STP_PARAMETER_DEFAULTED))
                    stp_set_float_parameter(v, p->name, d.deflt.dbl);
                break;
            case STP_PARAMETER_TYPE_INT:
                if (!stp_check_int_parameter(v, p->name, STP_PARAMETER_DEFAULTED))
                    stp_set_int_parameter(v, p->name, d.deflt.integer);
                break;
            default:
                break;
            }
        }
        stp_parameter_description_destroy(&d);
    }
    stp_parameter_list_destroy(params);
}

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
    stp_set_string_parameter(v, "JobMode", "Job");
    stp_set_boolean_parameter(v, "FullBleed", bleed);
    fill_defaults(v);
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
    int ok = stp_verify(v);
    if (ok) {
        ok = stp_start_job(v, &im);       /* Canon G-series XML start-of-job block */
        if (ok) ok = stp_print(v, &im);
        stp_end_job(v, &im);              /* end-of-job block */
    }
    printf("%-4s %-16s %-22s %-5s bleed=%d area=%.0f,%.0f-%.0f,%.0f -> %s %zu bytes head=%02x %02x %02x %02x %02x (%.5s)\n",
           page, media, res, bw ? "BW" : "Color", bleed, l, t, r, b, ok ? "OK  " : "FAIL", g_bytes,
           g_head[0], g_head[1], g_head[2], g_head[3], g_head[4], (const char *) g_head);
    stp_vars_destroy(v);
    return ok;
}

/* Writes one real test page to a file, using exactly the app's options for that mode. */
static void write_file(const char *path, const char *res, int bw) {
    g_file = fopen(path, "wb");
    run("A4", "Plain", res, bw, 0);
    fclose(g_file);
    g_file = NULL;
}

int main(int argc, char **argv) {
    stp_init();
    if (argc > 1) {   /* printtest OUTDIR: make test pages to send raw from a computer */
        char p[512];
        snprintf(p, sizeof p, "%s/g2010_test_standard_colour.prn", argv[1]); write_file(p, "600x600dpi", 0);
        snprintf(p, sizeof p, "%s/g2010_test_inksaver_black.prn", argv[1]); write_file(p, "300x300dpi", 1);
        return 0;
    }
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
