/**
 * JNI bridge for Linux layout direction detection.
 *
 * Uses Pango (via dlopen, no hard dependency) to detect the system locale's
 * text direction. Pango is available on virtually all Linux desktops as it
 * is a transitive dependency of GTK.
 *
 * Detection strategy:
 *   1. dlopen libpango → get sample string for default language → check base direction
 *   2. Fallback: parse locale env vars and compare against known RTL language codes
 *
 * Linked libraries: -ldl (for dlopen)
 */
#include <jni.h>
#include <locale.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

/* Pango direction constants (from pango-types.h) */
#define PANGO_DIRECTION_LTR      0
#define PANGO_DIRECTION_RTL      1
#define PANGO_DIRECTION_TTB_LTR  2
#define PANGO_DIRECTION_TTB_RTL  3
#define PANGO_DIRECTION_WEAK_LTR 4
#define PANGO_DIRECTION_WEAK_RTL 5
#define PANGO_DIRECTION_NEUTRAL  6

/* Fallback RTL language codes (ISO 639-1 / 639-3) */
static const char *RTL_LANGS[] = {
    "ar", "he", "fa", "ur", "yi", "ps", "sd", "ckb", "ku", "ug", "syr", "dv"
};
static const int RTL_LANGS_COUNT = sizeof(RTL_LANGS) / sizeof(RTL_LANGS[0]);

/**
 * Tries to detect RTL using Pango via dlopen.
 * Returns 1 for RTL, 0 for LTR, -1 if Pango is unavailable.
 */
static int detect_rtl_via_pango(void) {
    /* Ensure glibc locale is initialized so Pango and the JVM see the same state */
    setlocale(LC_ALL, "");

    void *libpango = dlopen("libpango-1.0.so.0", RTLD_LAZY | RTLD_LOCAL);
    if (!libpango) libpango = dlopen("libpango-1.0.so", RTLD_LAZY | RTLD_LOCAL);
    if (!libpango) return -1;

    typedef void*       (*fn_language_get_default)(void);
    typedef const char* (*fn_language_get_sample_string)(void*);
    typedef int         (*fn_find_base_dir)(const char*, int); /* length=-1 → strlen */

    fn_language_get_default       pango_lang_default =
        (fn_language_get_default)      dlsym(libpango, "pango_language_get_default");
    fn_language_get_sample_string pango_lang_sample  =
        (fn_language_get_sample_string)dlsym(libpango, "pango_language_get_sample_string");
    fn_find_base_dir              pango_base_dir     =
        (fn_find_base_dir)             dlsym(libpango, "pango_find_base_dir");

    int result = -1;
    if (pango_lang_default && pango_lang_sample && pango_base_dir) {
        void *lang = pango_lang_default();
        if (lang) {
            const char *sample = pango_lang_sample(lang);
            if (sample && sample[0] != '\0') {
                int dir = pango_base_dir(sample, -1);
                /* Consider both strong and weak RTL as RTL */
                result = (dir == PANGO_DIRECTION_RTL ||
                          dir == PANGO_DIRECTION_WEAK_RTL) ? 1 : 0;
            }
        }
    }
    dlclose(libpango);
    return result;
}

/**
 * Fallback: extract language code from locale env vars and compare
 * against known RTL languages.
 */
static int detect_rtl_via_locale(void) {
    const char *locale = getenv("LC_ALL");
    if (!locale || locale[0] == '\0') locale = getenv("LC_MESSAGES");
    if (!locale || locale[0] == '\0') locale = getenv("LANG");
    if (!locale || locale[0] == '\0') return 0;
    if (strcmp(locale, "C") == 0 || strcmp(locale, "POSIX") == 0) return 0;

    /* Extract language code (before '_', '.', or '@') */
    char lang[16];
    int i = 0;
    while (i < 15 && locale[i] != '\0'
           && locale[i] != '_' && locale[i] != '.' && locale[i] != '@') {
        lang[i] = locale[i];
        i++;
    }
    lang[i] = '\0';
    if (i == 0) return 0;

    for (int j = 0; j < RTL_LANGS_COUNT; j++) {
        if (strcmp(lang, RTL_LANGS[j]) == 0) return 1;
    }
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_io_github_kdroidfilter_nucleus_window_NativeLayoutDirectionBridge_nativeIsRTL(
    JNIEnv *env, jclass clazz)
{
    (void)env; (void)clazz;
    int result = detect_rtl_via_pango();
    if (result < 0) {
        result = detect_rtl_via_locale();
    }
    return result ? JNI_TRUE : JNI_FALSE;
}
