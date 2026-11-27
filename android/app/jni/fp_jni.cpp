// JNI bridge to libchromaprint (built from the official chromaprint
// 1.6.2 sources with KissFFT). One fingerprint session at a time:
// start(rate, channels) -> feed(short[])* -> finish() -> String.
#include <jni.h>
#include <chromaprint.h>

static ChromaprintContext* g_ctx = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_yyoo_ototag_FpNative_start(JNIEnv* env, jclass clazz, jint rate, jint channels) {
    if (g_ctx) { chromaprint_free(g_ctx); g_ctx = nullptr; }
    g_ctx = chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT);
    if (!g_ctx) return JNI_FALSE;
    return chromaprint_start(g_ctx, (int)rate, (int)channels) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_yyoo_ototag_FpNative_feed(JNIEnv* env, jclass clazz, jshortArray data) {
    if (!g_ctx || !data) return;
    jsize n = env->GetArrayLength(data);
    jshort* buf = env->GetShortArrayElements(data, nullptr);
    if (!buf) return;
    chromaprint_feed(g_ctx, buf, (int)n);
    env->ReleaseShortArrayElements(data, buf, JNI_ABORT);
}

JNIEXPORT jstring JNICALL
Java_com_yyoo_ototag_FpNative_finish(JNIEnv* env, jclass clazz) {
    if (!g_ctx) return nullptr;
    chromaprint_finish(g_ctx);
    char* fp = nullptr;
    chromaprint_get_fingerprint(g_ctx, &fp);
    jstring out = fp ? env->NewStringUTF(fp) : nullptr;
    if (fp) chromaprint_dealloc(fp);
    chromaprint_free(g_ctx);
    g_ctx = nullptr;
    return out;
}

}  // extern "C"
