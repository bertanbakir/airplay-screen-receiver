#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <openssl/crypto.h>
#include <openssl/evp.h>

extern "C" {
#include "raop.h"
#include "dnssd.h"
}

#define LOG_TAG "AirPlayJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static raop_t  *g_raop    = nullptr;
static dnssd_t *g_dnssd   = nullptr;
static JavaVM  *g_jvm     = nullptr;
static jobject  g_cb      = nullptr;
static jmethodID g_onVideoData    = nullptr;
static jmethodID g_onVideoSize    = nullptr;
static jmethodID g_onAudioFormat  = nullptr;
static jmethodID g_onAudioData    = nullptr;
static jmethodID g_onAudioFlush   = nullptr;
static jmethodID g_onConnected    = nullptr;
static jmethodID g_onDisconnected = nullptr;

// ── helpers ───────────────────────────────────────────────────────────────────

static JNIEnv *attachEnv(bool &attached) {
    JNIEnv *env = nullptr;
    attached = false;
    if (g_jvm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }
    return env;
}

// ── raop callbacks ────────────────────────────────────────────────────────────

static int g_videoFrames = 0;
static void cb_video_process(void *, raop_ntp_t *, video_decode_struct *data) {
    if (!g_cb || !g_onVideoData) return;
    if (++g_videoFrames <= 3) LOGI("video frame #%d len=%u h265=%d", g_videoFrames, data->data_len, data->is_h265);
    bool att; JNIEnv *env = attachEnv(att);

    jbyteArray jbuf = env->NewByteArray(data->data_len);
    env->SetByteArrayRegion(jbuf, 0, data->data_len, (jbyte *)data->data);
    env->CallVoidMethod(g_cb, g_onVideoData, jbuf, (jboolean)data->is_h265, (jlong)data->ntp_time_remote);
    env->DeleteLocalRef(jbuf);

    if (att) g_jvm->DetachCurrentThread();
}

static void cb_conn_init(void *) {
    LOGI("client connected");
    if (!g_cb || !g_onConnected) return;
    bool att; JNIEnv *env = attachEnv(att);
    env->CallVoidMethod(g_cb, g_onConnected);
    if (att) g_jvm->DetachCurrentThread();
}

static void cb_conn_destroy(void *) {
    LOGI("client disconnected");
    if (!g_cb || !g_onDisconnected) return;
    bool att; JNIEnv *env = attachEnv(att);
    env->CallVoidMethod(g_cb, g_onDisconnected);
    if (att) g_jvm->DetachCurrentThread();
}

static bool is_valid_audio_frame(unsigned char ct, const unsigned char *data, int data_len) {
    if (!data || data_len <= 0) return false;
    switch (ct) {
        case 8:
            switch (data[0]) {
                case 0x8c: case 0x8d: case 0x8e:
                case 0x80: case 0x81: case 0x82:
                    return true;
                default:
                    return false;
            }
        case 2:
            return data[0] == 0x20;
        case 4:
            return data[0] == 0xff;
        default:
            return true;
    }
}

static void cb_audio_process(void *, raop_ntp_t *, audio_decode_struct *data) {
    if (!g_cb || !g_onAudioData || !data || !data->data || data->data_len <= 0) return;
    if (!is_valid_audio_frame(data->ct, data->data, data->data_len)) return;

    bool att; JNIEnv *env = attachEnv(att);
    jbyteArray jbuf = env->NewByteArray(data->data_len);
    env->SetByteArrayRegion(jbuf, 0, data->data_len, (jbyte *) data->data);
    env->CallVoidMethod(g_cb, g_onAudioData, jbuf, (jlong) data->ntp_time_remote);
    env->DeleteLocalRef(jbuf);
    if (att) g_jvm->DetachCurrentThread();
}
static void   cb_video_pause(void *)                                         {}
static void   cb_video_resume(void *)                                        {}
static void   cb_conn_feedback(void *)                                       {}
static void   cb_conn_reset(void *, int)                                     {}
static void   cb_conn_teardown(void *, bool *, bool *)                       {}
static void cb_audio_flush(void *) {
    if (!g_cb || !g_onAudioFlush) return;
    bool att; JNIEnv *env = attachEnv(att);
    env->CallVoidMethod(g_cb, g_onAudioFlush);
    if (att) g_jvm->DetachCurrentThread();
}
static void   cb_video_flush(void *)                                         {}
static void   cb_video_reset(void *, reset_type_t)                           {}
static double cb_audio_set_client_volume(void *)                             { return 1.0; }
static void   cb_audio_set_volume(void *, float)                             {}
static void   cb_audio_set_metadata(void *, const void *, int)               {}
static void   cb_audio_set_coverart(void *, const void *, int)               {}
static void   cb_audio_stop_coverart_rendering(void *)                       {}
static void   cb_audio_remote_control_id(void *, const char *, const char *) {}
static void   cb_audio_set_progress(void *, uint32_t *, uint32_t *, uint32_t *) {}
static void cb_audio_get_format(void *, unsigned char *ct, unsigned short *spf,
                                   bool *usingScreen, bool *isMedia, uint64_t *fmt) {
    if (!ct) return;
    LOGI("audio format ct=%d spf=%d usingScreen=%d isMedia=%d audioFormat=0x%lx",
         (int) *ct, spf ? (int) *spf : -1,
         usingScreen ? (int) *usingScreen : -1,
         isMedia ? (int) *isMedia : -1,
         (unsigned long) (fmt ? *fmt : 0));

    if (g_cb && g_onAudioFormat) {
        bool att; JNIEnv *env = attachEnv(att);
        env->CallVoidMethod(
            g_cb,
            g_onAudioFormat,
            (jint) *ct,
            (jint) (spf ? *spf : 0)
        );
        if (att) g_jvm->DetachCurrentThread();
    }
}
static void cb_video_report_size(void *, float *width_source, float *height_source, float *width, float *height) {
    if (!g_cb || !g_onVideoSize) return;
    const int w = (int) *width;
    const int h = (int) *height;
    if (w <= 0 || h <= 0) return;
    bool att; JNIEnv *env = attachEnv(att);
    env->CallVoidMethod(g_cb, g_onVideoSize, (jint) w, (jint) h);
    if (att) g_jvm->DetachCurrentThread();
    LOGI("video size reported: %dx%d (source %.0fx%.0f)", w, h, *width_source, *height_source);
}
static int    cb_video_set_codec(void *, video_codec_t)                      { return 0; }

// ── JNI_OnLoad ────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    g_jvm = vm;
    OPENSSL_init_crypto(
        OPENSSL_INIT_ADD_ALL_CIPHERS |
        OPENSSL_INIT_ADD_ALL_DIGESTS |
        OPENSSL_INIT_LOAD_CONFIG, nullptr);
    return JNI_VERSION_1_6;
}

// ── nativeStart ───────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_aquiles_airplayandroidtv_AirPlayBridge_nativeStart(
        JNIEnv *env, jobject,
        jint port, jstring jname, jstring jhwAddr, jint screenWidth, jint screenHeight, jobject callback) {

    const char *name     = env->GetStringUTFChars(jname,   nullptr);
    const char *hw_str   = env->GetStringUTFChars(jhwAddr, nullptr);

    unsigned char hw[6] = {0};
    sscanf(hw_str, "%hhx:%hhx:%hhx:%hhx:%hhx:%hhx",
           &hw[0], &hw[1], &hw[2], &hw[3], &hw[4], &hw[5]);

    if (g_cb) env->DeleteGlobalRef(g_cb);
    g_cb = env->NewGlobalRef(callback);
    jclass cls       = env->GetObjectClass(callback);
    g_onVideoData    = env->GetMethodID(cls, "onVideoData",    "([BZJ)V");
    g_onVideoSize    = env->GetMethodID(cls, "onVideoSize",    "(II)V");
    g_onAudioFormat  = env->GetMethodID(cls, "onAudioFormat",  "(II)V");
    g_onAudioData    = env->GetMethodID(cls, "onAudioData",    "([BJ)V");
    g_onAudioFlush   = env->GetMethodID(cls, "onAudioFlush",   "()V");
    g_onConnected    = env->GetMethodID(cls, "onConnected",    "()V");
    g_onDisconnected = env->GetMethodID(cls, "onDisconnected", "()V");

    raop_callbacks_t cbs = {};
    cbs.audio_process              = cb_audio_process;
    cbs.video_process              = cb_video_process;
    cbs.video_pause                = cb_video_pause;
    cbs.video_resume               = cb_video_resume;
    cbs.conn_feedback              = cb_conn_feedback;
    cbs.conn_reset                 = cb_conn_reset;
    cbs.conn_init                  = cb_conn_init;
    cbs.conn_destroy               = cb_conn_destroy;
    cbs.conn_teardown              = cb_conn_teardown;
    cbs.audio_flush                = cb_audio_flush;
    cbs.video_flush                = cb_video_flush;
    cbs.video_reset                = cb_video_reset;
    cbs.audio_set_client_volume    = cb_audio_set_client_volume;
    cbs.audio_set_volume           = cb_audio_set_volume;
    cbs.audio_set_metadata         = cb_audio_set_metadata;
    cbs.audio_set_coverart         = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    cbs.audio_remote_control_id    = cb_audio_remote_control_id;
    cbs.audio_set_progress         = cb_audio_set_progress;
    cbs.audio_get_format           = cb_audio_get_format;
    cbs.video_report_size          = cb_video_report_size;
    cbs.video_set_codec            = cb_video_set_codec;

    g_raop = raop_init(&cbs);
    if (!g_raop) {
        LOGE("raop_init failed");
        goto fail;
    }

    if (screenWidth > 0 && screenHeight > 0) {
        raop_set_plist(g_raop, "width", (int) screenWidth);
        raop_set_plist(g_raop, "height", (int) screenHeight);
        LOGI("Requested mirror resolution %dx%d", (int) screenWidth, (int) screenHeight);
    }

    {
        int err = DNSSD_ERROR_NOERROR;
        g_dnssd = dnssd_init(name, (int)strlen(name), (const char *)hw, 6, &err, 0);
        if (!g_dnssd || err != DNSSD_ERROR_NOERROR) {
            LOGE("dnssd_init failed: %d", err);
            raop_destroy(g_raop); g_raop = nullptr;
            goto fail;
        }
    }

    raop_set_dnssd(g_raop, g_dnssd);
    raop_set_log_level(g_raop, 1);

    {
        unsigned short p = (unsigned short)port;
        raop_set_port(g_raop, p);

        if (raop_init2(g_raop, 1, hw_str, "") < 0) {
            LOGE("raop_init2 failed");
            dnssd_destroy(g_dnssd); g_dnssd = nullptr;
            raop_destroy(g_raop);   g_raop  = nullptr;
            goto fail;
        }

        if (raop_start_httpd(g_raop, &p) < 0) {
            LOGE("raop_start_httpd failed");
            dnssd_destroy(g_dnssd); g_dnssd = nullptr;
            raop_destroy(g_raop);   g_raop  = nullptr;
            goto fail;
        }

        dnssd_register_raop(g_dnssd, p);
        dnssd_register_airplay(g_dnssd, p);
        LOGI("AirPlay server started on port %d", p);
    }

    env->ReleaseStringUTFChars(jname,   name);
    env->ReleaseStringUTFChars(jhwAddr, hw_str);
    return JNI_TRUE;

fail:
    env->ReleaseStringUTFChars(jname,   name);
    env->ReleaseStringUTFChars(jhwAddr, hw_str);
    return JNI_FALSE;
}

// ── nativeStop ────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_dev_aquiles_airplayandroidtv_AirPlayBridge_nativeStop(JNIEnv *env, jobject) {
    if (g_raop)  { raop_stop_httpd(g_raop); raop_destroy(g_raop);   g_raop  = nullptr; }
    if (g_dnssd) { dnssd_destroy(g_dnssd);                           g_dnssd = nullptr; }
    if (g_cb)    { env->DeleteGlobalRef(g_cb);                       g_cb    = nullptr; }
    LOGI("AirPlay server stopped");
}

// ── nativeGetPort ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jint JNICALL
Java_dev_aquiles_airplayandroidtv_AirPlayBridge_nativeGetPort(JNIEnv *, jobject) {
    return g_raop ? (jint)raop_get_port(g_raop) : -1;
}
