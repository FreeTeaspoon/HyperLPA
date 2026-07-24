#pragma once

#include <euicc/euicc.h>
#include <pthread.h>
#include <jni.h>

#define LPAC_JNI_MIN_AID_BYTES 5U
#define LPAC_JNI_MAX_AID_BYTES 16U
#define LPAC_JNI_MAX_APDU_BYTES (1024U * 1024U)
#define LPAC_JNI_MAX_HTTP_BYTES (32U * 1024U * 1024U)
#define LPAC_JNI_MAX_HTTP_HEADERS 64U

_Static_assert(sizeof(void *) <= sizeof(jlong),
               "jlong must be big enough to hold a platform raw pointer");

struct lpac_jni_ctx {
    jint logical_channel_id;
    jobject apdu_interface;
    jobject http_interface;
    char *owned_http_server_address;
    jthrowable pending_exception;
};

#define LPAC_JNI_CTX(ctx) ((struct lpac_jni_ctx *) ctx->userdata)
#define LPAC_JNI_SETUP_ENV \
    JNIEnv *env; \
    (*jvm)->AttachCurrentThread(jvm, &env, NULL)

extern JavaVM *jvm;
extern jclass string_class;

jstring toJString(JNIEnv *env, const char *pat);
int lpac_jni_set_owned_http_server_address(struct euicc_ctx *ctx, const char *address);
void lpac_jni_clear_owned_http_server_address(struct euicc_ctx *ctx);
void lpac_jni_capture_exception(struct euicc_ctx *ctx, JNIEnv *env);
void lpac_jni_rethrow_captured_exception(struct euicc_ctx *ctx, JNIEnv *env);

#define LPAC_JNI_STRUCT_GETTER_LINKED_LIST_NEXT(st, st_jname) \
        JNIEXPORT jlong JNICALL Java_net_typeblog_lpac_1jni_LpacJni_##st_jname##Next(JNIEnv *env, jobject thiz, jlong raw) { \
            st *p = (st *) raw;                       \
            if (p == NULL) return 0;                  \
            return (jlong) p->next;                   \
        }

#define LPAC_JNI_STRUCT_GETTER_NULL_TERM_LIST_NEXT(st, st_jname) \
        JNIEXPORT jlong JNICALL Java_net_typeblog_lpac_1jni_LpacJni_##st_jname##Next(JNIEnv *env, jobject thiz, jlong raw) { \
            st *p = (st *) raw;                     \
            if (p == NULL) return 0;                  \
            if (*p == NULL) return 0;                 \
            p++;                                      \
            if (*p == NULL) return 0;                 \
            return (jlong) p;                         \
        }

#define LPAC_JNI_STRUCT_FREE(st, st_jname, free_func) \
        JNIEXPORT void JNICALL Java_net_typeblog_lpac_1jni_LpacJni_##st_jname##Free(JNIEnv *env, jobject thiz, jlong raw) { \
            st *p = (st *) raw;                       \
            if (p == NULL) return;                    \
            free_func(p);                             \
        }

#define LPAC_JNI_STRUCT_GETTER_LONG(st, st_name, name, jname) \
        JNIEXPORT jlong JNICALL Java_net_typeblog_lpac_1jni_LpacJni_##st_name##Get##jname(JNIEnv *env, jobject thiz, jlong raw) { \
            st *p = (st *) raw;                       \
            if (p == NULL) return 0;                  \
            return (jlong) p->name;                   \
        }

#define LPAC_JNI_STRUCT_GETTER_STRING(st, st_name, name, jname) \
        JNIEXPORT jstring JNICALL Java_net_typeblog_lpac_1jni_LpacJni_##st_name##Get##jname(JNIEnv *env, jobject thiz, jlong raw) { \
            st *p = (st *) raw;                       \
            if (p == NULL) return toJString(env, NULL); \
            return toJString(env, p->name);           \
        }
