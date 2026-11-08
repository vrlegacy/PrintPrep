/*
 * Lists what Gutenprint offers for a printer, so the app can map its settings onto real driver options.
 * Usage: probe [driver]   (default: looks for the Canon G2010)
 */
#include <gutenprint/gutenprint.h>
#include <stdio.h>
#include <string.h>

static void describe(stp_vars_t *v, const char *name, const char *indent) {
    stp_parameter_t d;
    stp_describe_parameter(v, name, &d);
    if (d.p_type == STP_PARAMETER_TYPE_STRING_LIST && d.bounds.str) {
        printf("%s%s default=%s active=%d\n", indent, name, d.deflt.str ? d.deflt.str : "-", d.is_active);
        size_t n = stp_string_list_count(d.bounds.str);
        for (size_t j = 0; j < n; j++) {
            stp_param_string_t *ps = stp_string_list_param(d.bounds.str, j);
            printf("%s  - %s : %s\n", indent, ps->name, ps->text);
        }
    } else if (d.p_type == STP_PARAMETER_TYPE_BOOLEAN) {
        printf("%s%s (bool) default=%d active=%d\n", indent, name, d.deflt.boolean, d.is_active);
    } else if (d.p_type == STP_PARAMETER_TYPE_DOUBLE) {
        printf("%s%s (double) default=%g range=%g..%g active=%d\n", indent, name, d.deflt.dbl, d.bounds.dbl.lower, d.bounds.dbl.upper, d.is_active);
    } else if (d.p_type == STP_PARAMETER_TYPE_INT) {
        printf("%s%s (int) default=%d range=%d..%d active=%d\n", indent, name, d.deflt.integer, d.bounds.integer.lower, d.bounds.integer.upper, d.is_active);
    } else {
        printf("%s%s (type %d) active=%d\n", indent, name, d.p_type, d.is_active);
    }
    stp_parameter_description_destroy(&d);
}

int main(int argc, char **argv) {
    stp_init();
    int count = stp_printer_model_count();
    const char *driver = argc > 1 ? argv[1] : NULL;
    printf("== Canon G-series drivers (%d printers total)\n", count);
    for (int i = 0; i < count; i++) {
        const stp_printer_t *p = stp_get_printer_by_index(i);
        const char *d = stp_printer_get_driver(p);
        const char *ln = stp_printer_get_long_name(p);
        if (strstr(d, "canon-g") || strstr(ln, "G20") || strstr(ln, "G10")) {
            printf("PRINTER %s | %s | family=%s\n", d, ln, stp_printer_get_family(p));
            if (!driver && (strstr(ln, "G2010") || strstr(d, "g2010"))) driver = d;
        }
    }
    /* Gutenprint has no G2010 entry; G2010 owners use the G2000 series driver. */
    if (!driver) driver = "bjc-G2000-series";
    const stp_printer_t *p = stp_get_printer_by_driver(driver);
    if (!p) { printf("No driver %s\n", driver); return 1; }
    printf("\n== Parameters for %s\n", driver);

    stp_vars_t *v = stp_vars_create_copy(stp_printer_get_defaults(p));
    stp_set_driver(v, driver);
    stp_set_string_parameter(v, "PageSize", "A4");
    stp_parameter_list_t params = stp_get_parameter_list(v);
    size_t n = stp_parameter_list_count(params);
    for (size_t i = 0; i < n; i++) {
        const stp_parameter_t *pa = stp_parameter_list_param(params, i);
        if (pa->p_class != STP_PARAMETER_CLASS_FEATURE && pa->p_class != STP_PARAMETER_CLASS_CORE) continue;
        if (pa->p_level > STP_PARAMETER_LEVEL_ADVANCED1) continue;
        printf("PARAM %s \"%s\" class=%d level=%d\n", pa->name, pa->text, pa->p_class, pa->p_level);
        describe(v, pa->name, "    ");
    }
    stp_parameter_list_destroy(params);

    stp_dimension_t l, r, b, t, w, h;
    stp_get_media_size(v, &w, &h);
    stp_get_imageable_area(v, &l, &r, &b, &t);
    printf("\n== A4: media %gx%g pt, imageable left=%g right=%g top=%g bottom=%g\n", w, h, l, r, t, b);

    /* Resolutions and ink options per media type */
    stp_parameter_t media;
    stp_describe_parameter(v, "MediaType", &media);
    if (media.p_type == STP_PARAMETER_TYPE_STRING_LIST && media.bounds.str) {
        size_t m = stp_string_list_count(media.bounds.str);
        for (size_t j = 0; j < m; j++) {
            stp_param_string_t *ps = stp_string_list_param(media.bounds.str, j);
            stp_set_string_parameter(v, "MediaType", ps->name);
            printf("\n== With MediaType=%s\n", ps->name);
            describe(v, "Resolution", "    ");
            describe(v, "InkSet", "    ");
            describe(v, "InkType", "    ");
            describe(v, "Quality", "    ");
            describe(v, "FullBleed", "    ");
        }
    }
    stp_parameter_description_destroy(&media);
    return 0;
}
