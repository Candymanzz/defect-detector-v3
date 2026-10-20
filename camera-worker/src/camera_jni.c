#ifndef _WIN32
#define _GNU_SOURCE
#endif
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "cw_runtime.h"
#ifdef HAVE_HIK_MVS
#include <MvCameraControl.h>
#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif
#endif

#define JNI_NAME(name) Java_com_example_iml_orchestrator_integration_camera_NativeCameraBindings_##name

static void throw_io(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/io/IOException");
    if (cls) (*env)->ThrowNew(env, cls, message);
}

static char *copy_bytes(JNIEnv *env, jbyteArray input) {
    if (!input) { throw_io(env, "Missing native camera argument"); return NULL; }
    jsize size = (*env)->GetArrayLength(env, input);
    if (size > 65536) { throw_io(env, "Native camera argument exceeds 64 KiB"); return NULL; }
    char *text = malloc((size_t)size + 1);
    if (!text) { throw_io(env, "Native camera allocation failed"); return NULL; }
    (*env)->GetByteArrayRegion(env, input, 0, size, (jbyte *)text);
    if ((*env)->ExceptionCheck(env)) { free(text); return NULL; }
    text[size] = '\0';
    if (memchr(text, '\0', (size_t)size)) {
        free(text); throw_io(env, "Native camera argument contains NUL"); return NULL;
    }
    return text;
}

JNIEXPORT jstring JNICALL JNI_NAME(sdkLibrary)(JNIEnv *env, jclass cls) {
    (void)cls;
#ifdef HAVE_HIK_MVS
#ifdef _WIN32
    HMODULE module = NULL;
    char path[32768];
    if (GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                          (LPCSTR)(uintptr_t)&MV_CC_Initialize, &module) &&
        GetModuleFileNameA(module, path, sizeof(path))) return (*env)->NewStringUTF(env, path);
#else
    Dl_info info;
    if (dladdr((void *)(uintptr_t)&MV_CC_Initialize, &info) && info.dli_fname)
        return (*env)->NewStringUTF(env, info.dli_fname);
#endif
    throw_io(env, "Cannot resolve the MVS SDK loaded by the camera JNI library");
#endif
    return NULL;
}

JNIEXPORT void JNICALL JNI_NAME(initializeSdk)(JNIEnv *env, jclass cls) {
    (void)cls;
#ifdef HAVE_HIK_MVS
    int code = MV_CC_Initialize();
    if (code != MV_OK) {
        char error[128]; snprintf(error, sizeof(error), "Camera MV_CC_Initialize failed: 0x%x", code);
        throw_io(env, error);
    }
#else
    (void)env;
#endif
}

JNIEXPORT void JNICALL JNI_NAME(finalizeSdk)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
#ifdef HAVE_HIK_MVS
    (void)MV_CC_Finalize();
#endif
}

JNIEXPORT jlong JNICALL JNI_NAME(open)(JNIEnv *env, jclass cls, jbyteArray config, jint camera_id) {
    (void)cls;
    char *path = copy_bytes(env, config);
    if (!path) return 0;
    char error[512] = {0};
    cw_camera *camera = cw_camera_open(path, camera_id, error, sizeof(error));
    free(path);
    if (!camera) { throw_io(env, error); return 0; }
    return (jlong)(intptr_t)camera;
}

JNIEXPORT jbyteArray JNICALL JNI_NAME(command)(JNIEnv *env, jclass cls, jlong handle, jbyteArray header) {
    (void)cls;
    if (!handle) { throw_io(env, "Native camera is closed"); return NULL; }
    char *json = copy_bytes(env, header);
    if (!json) return NULL;
    cw_response response;
    (void)cw_camera_command((cw_camera *)(intptr_t)handle, json, &response);
    free(json);
    jsize size = (jsize)strlen(response.json);
    jbyteArray result = (*env)->NewByteArray(env, size + 1);
    if (!result) return NULL;
    jbyte type = (jbyte)response.type;
    (*env)->SetByteArrayRegion(env, result, 0, 1, &type);
    (*env)->SetByteArrayRegion(env, result, 1, size, (const jbyte *)response.json);
    return result;
}

JNIEXPORT void JNICALL JNI_NAME(cancel)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    if (handle) cw_camera_cancel((cw_camera *)(intptr_t)handle);
}

JNIEXPORT void JNICALL JNI_NAME(close)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    cw_camera_close((cw_camera *)(intptr_t)handle);
}
