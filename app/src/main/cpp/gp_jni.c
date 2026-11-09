/*
 * PrintPrep <-> Gutenprint bridge.
 *
 * Kotlin renders each page in strips and hands rows to Gutenprint through RowSource.getRow();
 * Gutenprint turns them into the printer's own command language, written to a file that the
 * app then sends to the printer over USB. Gutenprint is GPL; so is this file.
 */
#include <jni.h>
#include <gutenprint/gutenprint.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define TAG "PrintPrepNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static int g_inited = 0;
static char g_err[2048];

static void err_cb(void *data, const char *buf, size_t bytes) {
    (void) data;
    size_t cur = strlen(g_err);
    size_t room = sizeof(g_err) - 1 - cur;
    if (bytes > room) bytes = room;
    memcpy(g_err + cur, buf, bytes);
    g_err[cur + bytes] = 0;
}

static void out_cb(void *data, const char *buf, size_t bytes) {
    fwrite(buf, 1, bytes, (FILE *) data);
}

/* ------------------------------------------------------------------ image source */

typedef struct {
    JNIEnv *env;
    jobject source;
    jmethodID get_row;
    jbyteArray buf;
    int width;
    int height;
    int aborted;
} img_rep;

static void im_init(stp_image_t *i) { (void) i; }
static void im_reset(stp_image_t *i) { (void) i; }
static int im_width(stp_image_t *i) { return ((img_rep *) i->rep)->width; }
static int im_height(stp_image_t *i) { return ((img_rep *) i->rep)->height; }
static const char *im_appname(stp_image_t *i) { (void) i; return "PrintPrep"; }
static void im_conclude(stp_image_t *i) { (void) i; }

static stp_image_status_t im_get_row(stp_image_t *i, unsigned char *data, size_t limit, int row) {
    img_rep *r = (img_rep *) i->rep;
    if (r->aborted) return STP_IMAGE_STATUS_ABORT;
    JNIEnv *env = r->env;
    jboolean ok = (*env)->CallBooleanMethod(env, r->source, r->get_row, (jint) row, r->buf);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        r->aborted = 1;
        return STP_IMAGE_STATUS_ABORT;
    }
    if (!ok) {
        r->aborted = 1;
        return STP_IMAGE_STATUS_ABORT;
    }
    size_t n = (size_t) r->width * 3;
    if (n > limit) n = limit;
    (*env)->GetByteArrayRegion(env, r->buf, 0, (jsize) n, (jbyte *) data);
    return STP_IMAGE_STATUS_OK;
}

/* ------------------------------------------------------------------ helpers */

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

/* Creates driver settings from alternating key/value strings. "FullBleed" is a boolean. */
static stp_vars_t *make_vars(JNIEnv *env, jstring jdriver, jobjectArray kv) {
    const char *driver = (*env)->GetStringUTFChars(env, jdriver, 0);
    const stp_printer_t *printer = stp_get_printer_by_driver(driver);
    if (!printer) {
        snprintf(g_err, sizeof(g_err), "Unknown printer driver: %s", driver);
        (*env)->ReleaseStringUTFChars(env, jdriver, driver);
        return NULL;
    }
    stp_vars_t *v = stp_vars_create_copy(stp_printer_get_defaults(printer));
    stp_set_driver(v, driver);
    (*env)->ReleaseStringUTFChars(env, jdriver, driver);

    jsize n = (*env)->GetArrayLength(env, kv);
    for (jsize i = 0; i + 1 < n; i += 2) {
        jstring jk = (jstring) (*env)->GetObjectArrayElement(env, kv, i);
        jstring jv = (jstring) (*env)->GetObjectArrayElement(env, kv, i + 1);
        const char *k = (*env)->GetStringUTFChars(env, jk, 0);
        const char *val = (*env)->GetStringUTFChars(env, jv, 0);
        if (strcmp(k, "FullBleed") == 0) {
            stp_set_boolean_parameter(v, k, strcmp(val, "true") == 0);
        } else {
            stp_set_string_parameter(v, k, val);
        }
        (*env)->ReleaseStringUTFChars(env, jk, k);
        (*env)->ReleaseStringUTFChars(env, jv, val);
        (*env)->DeleteLocalRef(env, jk);
        (*env)->DeleteLocalRef(env, jv);
    }
    fill_defaults(v);
    return v;
}

/* ------------------------------------------------------------------ JNI */

JNIEXPORT jboolean JNICALL
Java_io_printprep_app_print_Gutenprint_nativeInit(JNIEnv *env, jclass cls, jstring dataPath) {
    (void) cls;
    if (g_inited) return JNI_TRUE;
    const char *p = (*env)->GetStringUTFChars(env, dataPath, 0);
    setenv("STP_DATA_PATH", p, 1);
    (*env)->ReleaseStringUTFChars(env, dataPath, p);
    if (stp_init() != 0) {
        LOGE("stp_init failed");
        return JNI_FALSE;
    }
    g_inited = 1;
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_io_printprep_app_print_Gutenprint_nativeVersion(JNIEnv *env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, stp_get_version());
}

JNIEXPORT jboolean JNICALL
Java_io_printprep_app_print_Gutenprint_nativeHasDriver(JNIEnv *env, jclass cls, jstring jdriver) {
    (void) cls;
    const char *d = (*env)->GetStringUTFChars(env, jdriver, 0);
    jboolean ok = stp_get_printer_by_driver(d) != NULL;
    (*env)->ReleaseStringUTFChars(env, jdriver, d);
    return ok;
}

/* Returns [pageWidth, pageHeight, left, right, top, bottom] in points (all measured from the top-left). */
JNIEXPORT jdoubleArray JNICALL
Java_io_printprep_app_print_Gutenprint_nativeImageableArea(JNIEnv *env, jclass cls, jstring driver, jobjectArray kv) {
    (void) cls;
    g_err[0] = 0;
    stp_vars_t *v = make_vars(env, driver, kv);
    if (!v) return NULL;
    stp_dimension_t w = 0, h = 0, l = 0, r = 0, b = 0, t = 0;
    stp_get_media_size(v, &w, &h);
    stp_get_imageable_area(v, &l, &r, &b, &t);
    stp_vars_destroy(v);
    jdouble out[6] = {w, h, l, r, t, b};
    jdoubleArray arr = (*env)->NewDoubleArray(env, 6);
    (*env)->SetDoubleArrayRegion(env, arr, 0, 6, out);
    return arr;
}

/*
 * Renders one page into printer data at outPath. The image (widthPx x heightPx, RGB rows from
 * source.getRow) is placed at left/top with size width x height points.
 * Returns null on success, "cancelled" if the source stopped, or an error message.
 */
JNIEXPORT jstring JNICALL
Java_io_printprep_app_print_Gutenprint_nativePrintPage(
    JNIEnv *env, jclass cls, jstring driver, jobjectArray kv,
    jint widthPx, jint heightPx,
    jdouble left, jdouble top, jdouble width, jdouble height,
    jobject source, jstring outPath) {
    (void) cls;
    g_err[0] = 0;
    if (!g_inited) return (*env)->NewStringUTF(env, "Printer driver not initialised");

    stp_vars_t *v = make_vars(env, driver, kv);
    if (!v) return (*env)->NewStringUTF(env, g_err);

    const char *path = (*env)->GetStringUTFChars(env, outPath, 0);
    FILE *f = fopen(path, "wb");
    (*env)->ReleaseStringUTFChars(env, outPath, path);
    if (!f) {
        stp_vars_destroy(v);
        return (*env)->NewStringUTF(env, "Cannot create the print file");
    }

    stp_set_outfunc(v, out_cb);
    stp_set_outdata(v, f);
    stp_set_errfunc(v, err_cb);
    stp_set_errdata(v, NULL);
    stp_set_left(v, left);
    stp_set_top(v, top);
    stp_set_width(v, width);
    stp_set_height(v, height);

    jclass scls = (*env)->GetObjectClass(env, source);
    img_rep rep;
    rep.env = env;
    rep.source = source;
    rep.get_row = (*env)->GetMethodID(env, scls, "getRow", "(I[B)Z");
    rep.buf = (*env)->NewByteArray(env, widthPx * 3);
    rep.width = widthPx;
    rep.height = heightPx;
    rep.aborted = 0;
    (*env)->DeleteLocalRef(env, scls);

    stp_image_t image;
    memset(&image, 0, sizeof(image));
    image.init = im_init;
    image.reset = im_reset;
    image.width = im_width;
    image.height = im_height;
    image.get_row = im_get_row;
    image.get_appname = im_appname;
    image.conclude = im_conclude;
    image.rep = &rep;

    int ok = 0;
    if (!stp_verify(v)) {
        size_t cur = strlen(g_err);
        snprintf(g_err + cur, sizeof(g_err) - cur, "%sThe driver rejected these settings.", cur ? " " : "");
    } else {
        /* JobMode=Job: we frame each page as its own job. The Canon G-series needs the XML
         * start-of-job block, which Gutenprint only sends from stp_start_job(); in Page mode it
         * sends the end block but never the start, and the printer silently ignores the data. */
        ok = stp_start_job(v, &image);
        if (ok) ok = stp_print(v, &image);
        stp_end_job(v, &image);
    }
    fclose(f);
    stp_vars_destroy(v);
    (*env)->DeleteLocalRef(env, rep.buf);

    if (rep.aborted) return (*env)->NewStringUTF(env, "cancelled");
    if (!ok) return (*env)->NewStringUTF(env, g_err[0] ? g_err : "The printer driver failed.");
    return NULL;
}
