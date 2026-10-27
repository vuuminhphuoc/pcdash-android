#include <jni.h>
#include <stdlib.h>
#include <string.h>

#ifdef __cplusplus
extern "C" {
#endif

// Prototypes matching Go exported functions in libpcnet.so
extern int PcnetStart(char* cStateDir, char* cAuthKey, char* cHost, int cHostPort, int cLocalPort);
extern void PcnetStop(void);
extern int PcnetRunning(void);
extern char* PcnetNodeID(void);
extern char* PcnetLastError(void);
extern void PcnetFreeCString(char* s);
extern char* PcnetDiagnostics(void);
extern int PcnetBegin(char* cStateDir, char* cHostname, int cMode, char* cHost, int cHostPort, int cLocalPort);
extern char* PcnetState(void);

/*
 * vn.vuumi.pcdash.net.PcnetBridge JNI implementations
 */

JNIEXPORT jint JNICALL
Java_vn_vuumi_pcdash_net_PcnetBridge_nativeBegin(
    JNIEnv* env, jclass clazz,
    jstring jStateDir, jstring jHostname, jint jMode, jstring jHost, jint jHostPort, jint jLocalPort) {

    const char* stateDir = jStateDir ? (*env)->GetStringUTFChars(env, jStateDir, NULL) : NULL;
    const char* hostname = jHostname ? (*env)->GetStringUTFChars(env, jHostname, NULL) : NULL;
    const char* host = jHost ? (*env)->GetStringUTFChars(env, jHost, NULL) : NULL;

    int res = PcnetBegin(
        (char*)(stateDir ? stateDir : ""),
        (char*)(hostname ? hostname : ""),
        (int)jMode,
        (char*)(host ? host : ""),
        (int)jHostPort,
        (int)jLocalPort
    );

    if (jStateDir && stateDir) (*env)->ReleaseStringUTFChars(env, jStateDir, stateDir);
    if (jHostname && hostname) (*env)->ReleaseStringUTFChars(env, jHostname, hostname);
    if (jHost && host) (*env)->ReleaseStringUTFChars(env, jHost, host);

    return (jint)res;
}

JNIEXPORT jstring JNICALL
Java_vn_vuumi_pcdash_net_PcnetBridge_nativeStateJson(
    JNIEnv* env, jclass clazz) {
    char* st = PcnetState();
    if (st == NULL) {
        return NULL;
    }
    jstring result = (*env)->NewStringUTF(env, st);
    PcnetFreeCString(st);
    return result;
}

JNIEXPORT jint JNICALL
Java_vn_vuumi_pcdash_net_PcnetBridge_nativeStart(
    JNIEnv* env, jclass clazz,
    jstring jStateDir, jstring jAuthKey, jstring jHost, jint jHostPort, jint jLocalPort) {

    const char* stateDir = jStateDir ? (*env)->GetStringUTFChars(env, jStateDir, NULL) : NULL;
    const char* authKey = jAuthKey ? (*env)->GetStringUTFChars(env, jAuthKey, NULL) : NULL;
    const char* host = jHost ? (*env)->GetStringUTFChars(env, jHost, NULL) : NULL;

    int port = PcnetStart(
        (char*)(stateDir ? stateDir : ""),
        (char*)(authKey ? authKey : ""),
        (char*)(host ? host : ""),
        (int)jHostPort,
        (int)jLocalPort
    );

    if (jStateDir && stateDir) (*env)->ReleaseStringUTFChars(env, jStateDir, stateDir);
    if (jAuthKey && authKey) (*env)->ReleaseStringUTFChars(env, jAuthKey, authKey);
    if (jHost && host) (*env)->ReleaseStringUTFChars(env, jHost, host);

    return (jint)port;
}

JNIEXPORT void JNICALL
Java_vn_vuumi_pcdash_net_PcnetBridge_nativeStop(
    JNIEnv* env, jclass clazz) {
    PcnetStop();
}

JNIEXPORT jboolean JNICALL
Java_vn_vuumi_pcdash_net_PcnetBridge_nativeRunning(
    JNIEnv* env, jclass clazz) {
    return PcnetRunning() != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_vn_vuumi_pcdash_net_PcnetBridge_nativeNodeId(
    JNIEnv* env, jclass clazz) {
    char* nodeId = PcnetNodeID();
    if (nodeId == NULL) {
        return NULL;
    }
    jstring result = (*env)->NewStringUTF(env, nodeId);
    PcnetFreeCString(nodeId);
    return result;
}

JNIEXPORT jstring JNICALL
Java_vn_vuumi_pcdash_net_PcnetBridge_nativeLastError(
    JNIEnv* env, jclass clazz) {
    char* err = PcnetLastError();
    if (err == NULL) {
        return NULL;
    }
    jstring result = (*env)->NewStringUTF(env, err);
    PcnetFreeCString(err);
    return result;
}

JNIEXPORT jstring JNICALL
Java_vn_vuumi_pcdash_net_PcnetBridge_nativeDiagnostics(
    JNIEnv* env, jclass clazz) {
    char* diag = PcnetDiagnostics();
    if (diag == NULL) {
        return NULL;
    }
    jstring result = (*env)->NewStringUTF(env, diag);
    PcnetFreeCString(diag);
    return result;
}

/*
 * Legacy vn.vuumi.pcdash.remote.PcnetBridge forwarding stubs
 */

JNIEXPORT jint JNICALL
Java_vn_vuumi_pcdash_remote_PcnetBridge_nativeStart(
    JNIEnv* env, jclass clazz,
    jstring jStateDir, jstring jAuthKey, jstring jHost, jint jHostPort, jint jLocalPort) {
    return Java_vn_vuumi_pcdash_net_PcnetBridge_nativeStart(env, clazz, jStateDir, jAuthKey, jHost, jHostPort, jLocalPort);
}

JNIEXPORT void JNICALL
Java_vn_vuumi_pcdash_remote_PcnetBridge_nativeStop(
    JNIEnv* env, jclass clazz) {
    Java_vn_vuumi_pcdash_net_PcnetBridge_nativeStop(env, clazz);
}

JNIEXPORT jboolean JNICALL
Java_vn_vuumi_pcdash_remote_PcnetBridge_nativeRunning(
    JNIEnv* env, jclass clazz) {
    return Java_vn_vuumi_pcdash_net_PcnetBridge_nativeRunning(env, clazz);
}

JNIEXPORT jstring JNICALL
Java_vn_vuumi_pcdash_remote_PcnetBridge_nativeNodeId(
    JNIEnv* env, jclass clazz) {
    return Java_vn_vuumi_pcdash_net_PcnetBridge_nativeNodeId(env, clazz);
}

JNIEXPORT jstring JNICALL
Java_vn_vuumi_pcdash_remote_PcnetBridge_nativeLastError(
    JNIEnv* env, jclass clazz) {
    return Java_vn_vuumi_pcdash_net_PcnetBridge_nativeLastError(env, clazz);
}

JNIEXPORT jstring JNICALL
Java_vn_vuumi_pcdash_remote_PcnetBridge_nativeDiagnostics(
    JNIEnv* env, jclass clazz) {
    return Java_vn_vuumi_pcdash_net_PcnetBridge_nativeDiagnostics(env, clazz);
}

#ifdef __cplusplus
}
#endif
