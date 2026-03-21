/**
 * JNI bridge for Linux taskbar progress via DBus.
 *
 * Sends com.canonical.Unity.LauncherEntry.Update signals on the session bus.
 * This protocol is supported by GNOME (Ubuntu Dock / Dash to Dock),
 * KDE Plasma, and other desktop environments.
 *
 * Dependencies: GLib/GIO (libgio-2.0)
 */

#include <jni.h>
#include <gio/gio.h>
#include <string.h>
#include <stdio.h>

/* State constants — must match TaskbarProgress.State.flag values */
#define STATE_NO_PROGRESS   0x00
#define STATE_INDETERMINATE 0x01
#define STATE_NORMAL        0x02
#define STATE_ERROR         0x04
#define STATE_PAUSED        0x08

static GDBusConnection *g_conn = NULL;
static const char *OBJECT_PATH = "/io/github/kdroidfilter/nucleus";

/* Track current state for combined updates */
static double   g_progress_value   = 0.0;
static gboolean g_progress_visible = FALSE;

/* ---- DBus connection management ----------------------------------- */

static GDBusConnection *get_connection(void) {
    if (g_conn != NULL && !g_dbus_connection_is_closed(g_conn)) {
        return g_conn;
    }
    if (g_conn != NULL) {
        g_object_unref(g_conn);
        g_conn = NULL;
    }
    GError *error = NULL;
    g_conn = g_bus_get_sync(G_BUS_TYPE_SESSION, NULL, &error);
    if (error != NULL) {
        g_error_free(error);
        g_conn = NULL;
    }
    return g_conn;
}

/* ---- Signal emission ---------------------------------------------- */

static int emit_update(const char *desktop_file, GVariant *properties) {
    GDBusConnection *conn = get_connection();
    if (conn == NULL) {
        g_variant_unref(properties);
        return -1;
    }

    /* Build app URI: "application://filename.desktop" */
    char app_uri[512];
    snprintf(app_uri, sizeof(app_uri), "application://%s", desktop_file);

    GVariant *params = g_variant_new("(s@a{sv})", app_uri, properties);

    GError *error = NULL;
    g_dbus_connection_emit_signal(
        conn,
        NULL,                                    /* destination (broadcast) */
        OBJECT_PATH,                             /* object path */
        "com.canonical.Unity.LauncherEntry",     /* interface */
        "Update",                                /* signal name */
        params,                                  /* parameters */
        &error
    );

    if (error != NULL) {
        g_error_free(error);
        return -2;
    }

    /* Flush to ensure the signal reaches the bus immediately */
    g_dbus_connection_flush_sync(conn, NULL, NULL);
    return 0;
}

/* ---- JNI: nativeSetProgress --------------------------------------- */

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_taskbarprogress_linux_NativeLinuxTaskbarBridge_nativeSetProgress(
    JNIEnv *env, jclass clazz, jstring desktopFilename, jlong completed, jlong total)
{
    (void)clazz;

    const char *filename = (*env)->GetStringUTFChars(env, desktopFilename, NULL);
    if (filename == NULL) return -1;

    g_progress_value = (total > 0) ? ((double)completed / (double)total) : 0.0;
    if (!g_progress_visible) g_progress_visible = TRUE;

    GVariantBuilder builder;
    g_variant_builder_init(&builder, G_VARIANT_TYPE("a{sv}"));
    g_variant_builder_add(&builder, "{sv}", "progress",
                          g_variant_new_double(g_progress_value));
    g_variant_builder_add(&builder, "{sv}", "progress-visible",
                          g_variant_new_boolean(g_progress_visible));

    int result = emit_update(filename, g_variant_builder_end(&builder));

    (*env)->ReleaseStringUTFChars(env, desktopFilename, filename);
    return result;
}

/* ---- JNI: nativeSetProgressState ---------------------------------- */

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_taskbarprogress_linux_NativeLinuxTaskbarBridge_nativeSetProgressState(
    JNIEnv *env, jclass clazz, jstring desktopFilename, jint flags)
{
    (void)clazz;

    const char *filename = (*env)->GetStringUTFChars(env, desktopFilename, NULL);
    if (filename == NULL) return -1;

    g_progress_visible = (flags != STATE_NO_PROGRESS) ? TRUE : FALSE;

    GVariantBuilder builder;
    g_variant_builder_init(&builder, G_VARIANT_TYPE("a{sv}"));
    g_variant_builder_add(&builder, "{sv}", "progress",
                          g_variant_new_double(g_progress_value));
    g_variant_builder_add(&builder, "{sv}", "progress-visible",
                          g_variant_new_boolean(g_progress_visible));

    int result = emit_update(filename, g_variant_builder_end(&builder));

    (*env)->ReleaseStringUTFChars(env, desktopFilename, filename);
    return result;
}

/* ---- JNI: nativeSetUrgent ----------------------------------------- */

JNIEXPORT jint JNICALL
Java_io_github_kdroidfilter_nucleus_taskbarprogress_linux_NativeLinuxTaskbarBridge_nativeSetUrgent(
    JNIEnv *env, jclass clazz, jstring desktopFilename, jboolean urgent)
{
    (void)clazz;

    const char *filename = (*env)->GetStringUTFChars(env, desktopFilename, NULL);
    if (filename == NULL) return -1;

    GVariantBuilder builder;
    g_variant_builder_init(&builder, G_VARIANT_TYPE("a{sv}"));
    g_variant_builder_add(&builder, "{sv}", "urgent",
                          g_variant_new_boolean(urgent ? TRUE : FALSE));

    int result = emit_update(filename, g_variant_builder_end(&builder));

    (*env)->ReleaseStringUTFChars(env, desktopFilename, filename);
    return result;
}
