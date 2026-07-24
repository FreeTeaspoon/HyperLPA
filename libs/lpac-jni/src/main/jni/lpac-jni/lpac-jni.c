#include <euicc/euicc.h>
#include <euicc/es10a.h>
#include <euicc/es10c.h>
#include <euicc/es10c_ex.h>
#include <euicc/interface.h>
#include <euicc/rsp_limits.h>
#include <malloc.h>
#include <limits.h>
#include <string.h>
#include <syslog.h>
#include "lpac-jni.h"
#include "lpac-download.h"
#include "lpac-notifications.h"
#include "interface-wrapper.h"

JavaVM *jvm = NULL;

jstring empty_string;

jclass string_class;
jmethodID string_constructor;

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    jvm = vm;
    interface_wrapper_init();
    lpac_download_init();

    LPAC_JNI_SETUP_ENV;
    string_class = (*env)->FindClass(env, "java/lang/String");
    string_class = (*env)->NewGlobalRef(env, string_class);
    string_constructor = (*env)->GetMethodID(env, string_class, "<init>",
                                             "([BLjava/lang/String;)V");

    const jchar _unused[1];
    empty_string = (*env)->NewString(env, _unused, 0);
    empty_string = (*env)->NewGlobalRef(env, empty_string);

    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_createContext(JNIEnv *env, jobject thiz,
                                                  jbyteArray isdr_aid,
                                                  jobject apdu_interface,
                                                  jobject http_interface) {
    struct lpac_jni_ctx *jni_ctx = NULL;
    struct euicc_ctx *ctx = NULL;
    jbyte *isdr_java = NULL;
    jsize isdr_len = 0;
    uint8_t *isdr_c = NULL;

    if (isdr_aid == NULL || apdu_interface == NULL || http_interface == NULL)
        return 0;

    isdr_len = (*env)->GetArrayLength(env, isdr_aid);
    if (isdr_len < LPAC_JNI_MIN_AID_BYTES || isdr_len > LPAC_JNI_MAX_AID_BYTES)
        return 0;

    ctx = calloc(1, sizeof(struct euicc_ctx));
    jni_ctx = calloc(1, sizeof(struct lpac_jni_ctx));
    isdr_c = calloc((size_t)isdr_len, sizeof(uint8_t));
    if (ctx == NULL || jni_ctx == NULL || isdr_c == NULL)
        goto err;

    isdr_java = (*env)->GetByteArrayElements(env, isdr_aid, JNI_FALSE);
    if (isdr_java == NULL)
        goto err;
    memcpy(isdr_c, isdr_java, (size_t)isdr_len);
    (*env)->ReleaseByteArrayElements(env, isdr_aid, isdr_java, JNI_ABORT);
    isdr_java = NULL;

    ctx->apdu.interface = &lpac_jni_apdu_interface;
    ctx->http.interface = &lpac_jni_http_interface;
    jni_ctx->apdu_interface = (*env)->NewGlobalRef(env, apdu_interface);
    jni_ctx->http_interface = (*env)->NewGlobalRef(env, http_interface);
    if (jni_ctx->apdu_interface == NULL || jni_ctx->http_interface == NULL)
        goto err;
    ctx->aid = (const uint8_t *) isdr_c;
    ctx->aid_len = (uint8_t)isdr_len;
    ctx->userdata = (void *) jni_ctx;
    return (jlong) ctx;

err:
    if (isdr_java != NULL)
        (*env)->ReleaseByteArrayElements(env, isdr_aid, isdr_java, JNI_ABORT);
    if (jni_ctx != NULL) {
        if (jni_ctx->apdu_interface != NULL)
            (*env)->DeleteGlobalRef(env, jni_ctx->apdu_interface);
        if (jni_ctx->http_interface != NULL)
            (*env)->DeleteGlobalRef(env, jni_ctx->http_interface);
    }
    free(isdr_c);
    free(jni_ctx);
    free(ctx);
    return 0;
}

JNIEXPORT void JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_destroyContext(JNIEnv *env, jobject thiz, jlong handle) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    struct lpac_jni_ctx *jni_ctx;

    if (ctx == NULL)
        return;
    jni_ctx = LPAC_JNI_CTX(ctx);

    euicc_http_cleanup(ctx);

    if (jni_ctx != NULL) {
        if (jni_ctx->apdu_interface != NULL)
            (*env)->DeleteGlobalRef(env, jni_ctx->apdu_interface);
        if (jni_ctx->http_interface != NULL)
            (*env)->DeleteGlobalRef(env, jni_ctx->http_interface);
        if (jni_ctx->pending_exception != NULL)
            (*env)->DeleteGlobalRef(env, jni_ctx->pending_exception);
        free(jni_ctx->owned_http_server_address);
    }
    free(jni_ctx);
    free((void *) ctx->aid);
    free(ctx);
}

int lpac_jni_set_owned_http_server_address(struct euicc_ctx *ctx, const char *address) {
    struct lpac_jni_ctx *jni_ctx;
    char *copy;

    if (ctx == NULL || address == NULL)
        return -1;
    jni_ctx = LPAC_JNI_CTX(ctx);
    if (jni_ctx == NULL)
        return -1;
    copy = strdup(address);
    if (copy == NULL)
        return -1;
    free(jni_ctx->owned_http_server_address);
    jni_ctx->owned_http_server_address = copy;
    ctx->http.server_address = copy;
    return 0;
}

void lpac_jni_clear_owned_http_server_address(struct euicc_ctx *ctx) {
    struct lpac_jni_ctx *jni_ctx;

    if (ctx == NULL)
        return;
    jni_ctx = LPAC_JNI_CTX(ctx);
    if (jni_ctx == NULL)
        return;
    if (ctx->http.server_address == jni_ctx->owned_http_server_address)
        ctx->http.server_address = NULL;
    free(jni_ctx->owned_http_server_address);
    jni_ctx->owned_http_server_address = NULL;
}

void lpac_jni_capture_exception(struct euicc_ctx *ctx, JNIEnv *env) {
    struct lpac_jni_ctx *jni_ctx;
    jthrowable exception;

    if (ctx == NULL || env == NULL || !(*env)->ExceptionCheck(env))
        return;
    exception = (*env)->ExceptionOccurred(env);
    (*env)->ExceptionClear(env);
    if (exception == NULL)
        return;

    jni_ctx = LPAC_JNI_CTX(ctx);
    if (jni_ctx != NULL && jni_ctx->pending_exception == NULL) {
        jni_ctx->pending_exception = (jthrowable)(*env)->NewGlobalRef(env, exception);
    }
    (*env)->DeleteLocalRef(env, exception);
}

void lpac_jni_rethrow_captured_exception(struct euicc_ctx *ctx, JNIEnv *env) {
    struct lpac_jni_ctx *jni_ctx;
    jthrowable exception;

    if (ctx == NULL || env == NULL)
        return;
    jni_ctx = LPAC_JNI_CTX(ctx);
    if (jni_ctx == NULL || jni_ctx->pending_exception == NULL)
        return;

    exception = jni_ctx->pending_exception;
    jni_ctx->pending_exception = NULL;
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
    (*env)->Throw(env, exception);
    (*env)->DeleteGlobalRef(env, exception);
}

JNIEXPORT jint JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_euiccInit(JNIEnv *env, jobject thiz, jlong handle) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    int result;
    if (ctx == NULL)
        return -1;
    result = euicc_init(ctx);
    lpac_jni_rethrow_captured_exception(ctx, env);
    return result;
}

JNIEXPORT void JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_euiccFini(JNIEnv *env, jobject thiz, jlong handle) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    if (ctx != NULL) {
        euicc_fini(ctx);
        lpac_jni_rethrow_captured_exception(ctx, env);
    }
}

JNIEXPORT void JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_euiccSetMss(JNIEnv *env, jobject thiz, jlong handle,
                                                jbyte mss) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    if (ctx != NULL)
        ctx->es10x_mss = (uint8_t) mss;
}

jstring toJString(JNIEnv *env, const char *pat) {
    jbyteArray bytes = NULL;
    jstring encoding = NULL;
    jstring jstr = NULL;
    size_t len;

    if (pat == NULL)
        return (*env)->NewLocalRef(env, empty_string);

    len = strlen(pat);
    if (len > INT_MAX)
        return NULL;
    bytes = (*env)->NewByteArray(env, (jsize)len);
    if (bytes == NULL)
        return NULL;
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)len, (jbyte *) pat);
    if ((*env)->ExceptionCheck(env))
        goto out;
    encoding = (*env)->NewStringUTF(env, "utf-8");
    if (encoding == NULL)
        goto out;
    jstr = (jstring) (*env)->NewObject(env, string_class,
                                       string_constructor, bytes, encoding);
out:
    if (encoding != NULL)
        (*env)->DeleteLocalRef(env, encoding);
    if (bytes != NULL)
        (*env)->DeleteLocalRef(env, bytes);
    return jstr;
}

JNIEXPORT jstring JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_es10cGetEid(JNIEnv *env, jobject thiz, jlong handle) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    char *buf = NULL;

    if (ctx == NULL)
        return NULL;
    if (es10c_get_eid(ctx, &buf) < 0) {
        return NULL;
    }
    jstring ret = toJString(env, buf);
    free(buf);
    return ret;
}

JNIEXPORT jlong JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_es10cGetProfilesInfo(JNIEnv *env, jobject thiz, jlong handle) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    struct es10c_profile_info_list *info = NULL;

    if (ctx == NULL)
        return 0;
    if (es10c_get_profiles_info(ctx, &info) < 0) {
        return 0;
    }

    return (jlong) info;
}

JNIEXPORT jstring JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_profileGetStateString(JNIEnv *env, jobject thiz, jlong curr) {
    struct es10c_profile_info_list *info = (struct es10c_profile_info_list *) curr;
    const char *profileStateStr = NULL;

    if (info == NULL)
        return toJString(env, "unknown");
    switch (info->profileState) {
        case ES10C_PROFILE_STATE_ENABLED:
            profileStateStr = "enabled";
            break;
        case ES10C_PROFILE_STATE_DISABLED:
            profileStateStr = "disabled";
            break;
        default:
            profileStateStr = "unknown";
    }

    return toJString(env, profileStateStr);
}

JNIEXPORT jstring JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_profileGetClassString(JNIEnv *env, jobject thiz, jlong curr) {
    struct es10c_profile_info_list *info = (struct es10c_profile_info_list *) curr;
    const char *profileClassStr = NULL;

    if (info == NULL)
        return toJString(env, "unknown");
    switch (info->profileClass) {
        case ES10C_PROFILE_CLASS_TEST:
            profileClassStr = "test";
            break;
        case ES10C_PROFILE_CLASS_PROVISIONING:
            profileClassStr = "provisioning";
            break;
        case ES10C_PROFILE_CLASS_OPERATIONAL:
            profileClassStr = "operational";
            break;
        default:
            profileClassStr = "unknown";
            break;
    }

    return toJString(env, profileClassStr);
}

LPAC_JNI_STRUCT_GETTER_LINKED_LIST_NEXT(struct es10c_profile_info_list, profiles)
LPAC_JNI_STRUCT_FREE(struct es10c_profile_info_list, profiles, es10c_profile_info_list_free_all)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_profile_info_list, profile, iccid, Iccid)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_profile_info_list, profile, isdpAid, IsdpAid)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_profile_info_list, profile, profileName, Name)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_profile_info_list, profile, profileNickname, Nickname)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_profile_info_list, profile, serviceProviderName, ServiceProvider)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_profile_info_list, profile, icon, Icon)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_profile_info_list, profile, notificationConfigurationInfo.notificationAddress, NotificationAddress)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_profile_info_list, profile, profileOwner.mccmnc, MccMnc)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_profile_info_list, profile, profileOwner.gid1, Gid1)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_profile_info_list, profile, profileOwner.gid2, Gid2)
LPAC_JNI_STRUCT_GETTER_LONG(struct es10c_profile_info_list, profile,
                            notificationConfigurationInfo.profileManagementOperation, NotificationOperations)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_profile_info_list, profile, dpProprietaryData.dpOid, DpOid)
LPAC_JNI_STRUCT_GETTER_LONG(struct es10c_profile_info_list, profile, profilePolicyRules, PolicyRules)

static int lpac_jni_valid_iccid(const char *iccid) {
    size_t length;

    if (iccid == NULL)
        return 0;
    length = strnlen(iccid, 21U);
    if (length < 10U || length > 20U)
        return 0;
    for (size_t i = 0; i < length; i++) {
        if (iccid[i] < '0' || iccid[i] > '9')
            return 0;
    }
    return 1;
}

JNIEXPORT jint JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_es10cEnableProfile(JNIEnv *env, jobject thiz, jlong handle,
                                                       jstring iccid, jboolean refresh) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    const char *_iccid = NULL;
    int ret = -1;

    if (ctx == NULL || iccid == NULL)
        return -1;
    _iccid = (*env)->GetStringUTFChars(env, iccid, NULL);
    if (_iccid == NULL)
        return -1;
    if (!lpac_jni_valid_iccid(_iccid))
        goto out;
    ret = es10c_enable_profile(ctx, _iccid, refresh ? 1 : 0);
out:
    (*env)->ReleaseStringUTFChars(env, iccid, _iccid);
    return ret;
}

JNIEXPORT jint JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_es10cDisableProfile(JNIEnv *env, jobject thiz, jlong handle,
                                                        jstring iccid, jboolean refresh) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    const char *_iccid = NULL;
    int ret = -1;

    if (ctx == NULL || iccid == NULL)
        return -1;
    _iccid = (*env)->GetStringUTFChars(env, iccid, NULL);
    if (_iccid == NULL)
        return -1;
    if (!lpac_jni_valid_iccid(_iccid))
        goto out;
    ret = es10c_disable_profile(ctx, _iccid, refresh ? 1 : 0);
out:
    (*env)->ReleaseStringUTFChars(env, iccid, _iccid);
    return ret;
}

JNIEXPORT jint JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_es10cSetNickname(JNIEnv *env, jobject thiz, jlong handle,
                                                     jstring iccid, jbyteArray nick) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    const char *_iccid = NULL;
    jbyte *_nick = NULL;
    int ret = -1;
    jsize nick_len;

    if (ctx == NULL || iccid == NULL || nick == NULL)
        return -1;
    nick_len = (*env)->GetArrayLength(env, nick);
    if (nick_len < 1 || (uint32_t)nick_len > (EUICC_RSP_PROFILE_NICKNAME_CHARS * 4U) + 1U)
        return -1;
    _iccid = (*env)->GetStringUTFChars(env, iccid, NULL);
    if (_iccid == NULL)
        return -1;
    if (!lpac_jni_valid_iccid(_iccid))
        goto out;
    _nick = (*env)->GetByteArrayElements(env, nick, NULL);
    if (_nick == NULL)
        goto out;
    if (_nick[nick_len - 1] != 0)
        goto out;
    if (memchr(_nick, 0, (size_t)nick_len - 1U) != NULL)
        goto out;
    ret = es10c_set_nickname(ctx, _iccid, (const char *) _nick);
out:
    if (_nick != NULL)
        (*env)->ReleaseByteArrayElements(env, nick, _nick, JNI_ABORT);
    if (_iccid != NULL)
        (*env)->ReleaseStringUTFChars(env, iccid, _iccid);
    return ret;
}

JNIEXPORT jint JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_es10cDeleteProfile(JNIEnv *env, jobject thiz, jlong handle,
                                                       jstring iccid) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    const char *_iccid = NULL;
    int ret = -1;

    if (ctx == NULL || iccid == NULL)
        return -1;
    _iccid = (*env)->GetStringUTFChars(env, iccid, NULL);
    if (_iccid == NULL)
        return -1;
    if (!lpac_jni_valid_iccid(_iccid))
        goto out;
    ret = es10c_delete_profile(ctx, _iccid);
out:
    (*env)->ReleaseStringUTFChars(env, iccid, _iccid);
    return ret;
}

JNIEXPORT jlong JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_es10cexGetEuiccInfo2(JNIEnv *env, jobject thiz, jlong handle) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    struct es10c_ex_euiccinfo2 *info;

    if (ctx == NULL)
        return 0;
    info = calloc(1, sizeof(struct es10c_ex_euiccinfo2));
    if (info == NULL)
        return 0;

    if (es10c_ex_get_euiccinfo2(ctx, info) < 0) {
        free(info);
        return 0;
    }

    return (jlong) info;
}

JNIEXPORT jlong JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_es10aGetEuiccConfiguredAddresses(JNIEnv *env, jobject thiz, jlong handle) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    struct es10a_euicc_configured_addresses *addresses;
    if (ctx == NULL)
        return 0;
    addresses = calloc(1, sizeof(*addresses));
    if (addresses == NULL) {
        return 0;
    }
    if (es10a_get_euicc_configured_addresses(ctx, addresses) < 0) {
        es10a_euicc_configured_addresses_free(addresses);
        free(addresses);
        return 0;
    }
    return (jlong) addresses;
}

JNIEXPORT jint JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_es10aSetDefaultDpAddress(JNIEnv *env, jobject thiz,
                                                             jlong handle, jstring address) {
    struct euicc_ctx *ctx = (struct euicc_ctx *)handle;
    const char *native_address = NULL;
    int result = -1;

    if (ctx == NULL || address == NULL)
        return -1;
    native_address = (*env)->GetStringUTFChars(env, address, NULL);
    if (native_address == NULL)
        return -1;
    if (native_address[0] != '\0' && strlen(native_address) <= 253U)
        result = es10a_set_default_dp_address(ctx, native_address);
    (*env)->ReleaseStringUTFChars(env, address, native_address);
    return result;
}

static void lpac_jni_euicc_configured_addresses_free(struct es10a_euicc_configured_addresses *addresses) {
    es10a_euicc_configured_addresses_free(addresses);
    free(addresses);
}


JNIEXPORT jint JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_es10cEuiccMemoryReset(JNIEnv *env, jobject thiz, jlong handle) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    int ret;
    if (ctx == NULL)
        return -1;
    ret = es10c_euicc_memory_reset(ctx);
    return ret;
}

JNIEXPORT jstring JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_stringDeref(JNIEnv *env, jobject thiz, jlong curr) {
    if (curr == 0)
        return toJString(env, NULL);
    return toJString(env, *((char **) curr));
}

void lpac_jni_euiccinfo2_free(struct es10c_ex_euiccinfo2 *info) {
    es10c_ex_euiccinfo2_free(info);
    free(info);
}

LPAC_JNI_STRUCT_GETTER_NULL_TERM_LIST_NEXT(char*, stringArr)
LPAC_JNI_STRUCT_FREE(struct es10c_ex_euiccinfo2, euiccInfo2, lpac_jni_euiccinfo2_free)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_ex_euiccinfo2, euiccInfo2, svn, SGP22Version)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_ex_euiccinfo2, euiccInfo2, profileVersion, ProfileVersion)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_ex_euiccinfo2, euiccInfo2, euiccFirmwareVer, EuiccFirmwareVersion)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_ex_euiccinfo2, euiccInfo2, globalplatformVersion, GlobalPlatformVersion)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_ex_euiccinfo2, euiccInfo2, sasAcreditationNumber, SasAcreditationNumber)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_ex_euiccinfo2, euiccInfo2, ppVersion, PpVersion)
LPAC_JNI_STRUCT_GETTER_LONG(struct es10c_ex_euiccinfo2, euiccInfo2, extCardResource.freeNonVolatileMemory, FreeNonVolatileMemory)
LPAC_JNI_STRUCT_GETTER_LONG(struct es10c_ex_euiccinfo2, euiccInfo2, extCardResource.freeVolatileMemory, FreeVolatileMemory)
LPAC_JNI_STRUCT_GETTER_LONG(struct es10c_ex_euiccinfo2, euiccInfo2, extCardResource.installedApplication, InstalledApplication)
LPAC_JNI_STRUCT_GETTER_LONG(struct es10c_ex_euiccinfo2, euiccInfo2, uiccCapability, UiccCapability)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_ex_euiccinfo2, euiccInfo2, ts102241Version, Ts102241Version)
LPAC_JNI_STRUCT_GETTER_LONG(struct es10c_ex_euiccinfo2, euiccInfo2, rspCapability, RspCapability)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_ex_euiccinfo2, euiccInfo2, euiccCategory, EuiccCategory)
LPAC_JNI_STRUCT_GETTER_LONG(struct es10c_ex_euiccinfo2, euiccInfo2, forbiddenProfilePolicyRules, ForbiddenProfilePolicyRules)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_ex_euiccinfo2, euiccInfo2, certificationDataObject.platformLabel, PlatformLabel)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10c_ex_euiccinfo2, euiccInfo2, certificationDataObject.discoveryBaseURL, DiscoveryBaseUrl)

LPAC_JNI_STRUCT_GETTER_LONG(struct es10c_ex_euiccinfo2, euiccInfo2, euiccCiPKIdListForSigning, EuiccCiPKIdListForSigning)
LPAC_JNI_STRUCT_GETTER_LONG(struct es10c_ex_euiccinfo2, euiccInfo2, euiccCiPKIdListForVerification, EuiccCiPKIdListForVerification)

LPAC_JNI_STRUCT_GETTER_STRING(struct es10a_euicc_configured_addresses, euiccConfiguredAddresses,
                              defaultDpAddress, DefaultDpAddress)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10a_euicc_configured_addresses, euiccConfiguredAddresses,
                              rootDsAddress, RootDsAddress)
LPAC_JNI_STRUCT_FREE(struct es10a_euicc_configured_addresses, euiccConfiguredAddresses,
                     lpac_jni_euicc_configured_addresses_free)
