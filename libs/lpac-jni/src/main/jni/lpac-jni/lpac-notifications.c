#include "lpac-notifications.h"
#include <euicc/es9p.h>
#include <euicc/es10b.h>
#include <malloc.h>
#include <limits.h>
#include <stdint.h>
#include <string.h>
#include <syslog.h>

JNIEXPORT jlong JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_es10bListNotification(JNIEnv *env, jobject thiz, jlong handle) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    struct es10b_notification_metadata_list *info = NULL;

    if (ctx == NULL)
        return 0;
    if (es10b_list_notification(ctx, &info) < 0)
        return 0;

    return (jlong) info;
}

JNIEXPORT jint JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_handleNotification(JNIEnv *env, jobject thiz, jlong handle,
                                                       jlong seq_number) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    struct es10b_pending_notification notification;
    int res = -1;

    if (ctx == NULL || seq_number < 0 || (uint64_t)seq_number > ULONG_MAX)
        return -1;
    memset(&notification, 0, sizeof(notification));

    res = es10b_retrieve_notifications_list(ctx, &notification, (unsigned long) seq_number);
    syslog(LOG_DEBUG, "es10b_retrieve_notification = %d", res);
    if (res < 0)
        goto out;

    if (notification.notificationAddress == NULL ||
        notification.notificationAddress[0] == '\0' ||
        strlen(notification.notificationAddress) > 253U ||
        strpbrk(notification.notificationAddress, "/\\?#$@\r\n\t ") != NULL) {
        res = -1;
        goto out;
    }
    ctx->http.server_address = notification.notificationAddress;

    res = es9p_handle_notification(ctx, notification.b64_PendingNotification);
    syslog(LOG_DEBUG, "es9p_handle_notification = %d", res);
    if (res < 0)
        goto out;

out:
    euicc_http_cleanup(ctx);
    ctx->http.server_address = NULL;
    es10b_pending_notification_free(&notification);
    return res;
}

JNIEXPORT jstring JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_dumpNotificationPayload(JNIEnv *env, jobject thiz, jlong handle,
                                                             jlong seq_number) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    struct es10b_pending_notification notification;
    jstring result = NULL;

    (void) thiz;
    if (ctx == NULL || seq_number < 0 || (uint64_t)seq_number > ULONG_MAX)
        return NULL;

    memset(&notification, 0, sizeof(notification));
    if (es10b_retrieve_notifications_list(ctx, &notification, (unsigned long) seq_number) < 0)
        return NULL;

    result = toJString(env, notification.b64_PendingNotification);
    es10b_pending_notification_free(&notification);
    return result;
}

JNIEXPORT jint JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_replayNotification(JNIEnv *env, jobject thiz, jlong handle,
                                                        jstring notification_address,
                                                        jstring pending_notification) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    const char *address = NULL;
    const char *payload = NULL;
    int res = -1;

    (void) thiz;
    if (ctx == NULL || notification_address == NULL || pending_notification == NULL)
        return -1;

    address = (*env)->GetStringUTFChars(env, notification_address, NULL);
    payload = (*env)->GetStringUTFChars(env, pending_notification, NULL);
    if (address == NULL || payload == NULL)
        goto out;
    if (address[0] == '\0' || strlen(address) > 253U ||
        strpbrk(address, "/\\?#$@\r\n\t ") != NULL || payload[0] == '\0')
        goto out;

    ctx->http.server_address = address;
    res = es9p_handle_notification(ctx, payload);

out:
    euicc_http_cleanup(ctx);
    ctx->http.server_address = NULL;
    if (payload != NULL)
        (*env)->ReleaseStringUTFChars(env, pending_notification, payload);
    if (address != NULL)
        (*env)->ReleaseStringUTFChars(env, notification_address, address);
    return res;
}

JNIEXPORT jint JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_es10bDeleteNotification(JNIEnv *env, jobject thiz, jlong handle,
                                                            jlong seq_number) {
    struct euicc_ctx *ctx = (struct euicc_ctx *) handle;
    if (ctx == NULL || seq_number < 0 || (uint64_t)seq_number > ULONG_MAX)
        return -1;
    return es10b_remove_notification_from_list(ctx, (unsigned long) seq_number);
}

JNIEXPORT jstring JNICALL
Java_net_typeblog_lpac_1jni_LpacJni_notificationGetOperationString(JNIEnv *env, jobject thiz,
                                                                   jlong curr) {
    struct es10b_notification_metadata_list *info = (struct es10b_notification_metadata_list *) curr;
    const char *profileManagementOperationStr = NULL;
    if (info == NULL)
        return toJString(env, "unknown");
    switch (info->profileManagementOperation) {
        case ES10B_PROFILE_MANAGEMENT_OPERATION_INSTALL:
            profileManagementOperationStr = "install";
            break;
        case ES10B_PROFILE_MANAGEMENT_OPERATION_DELETE:
            profileManagementOperationStr = "delete";
            break;
        case ES10B_PROFILE_MANAGEMENT_OPERATION_ENABLE:
            profileManagementOperationStr = "enable";
            break;
        case ES10B_PROFILE_MANAGEMENT_OPERATION_DISABLE:
            profileManagementOperationStr = "disable";
            break;
        default:
            profileManagementOperationStr = "unknown";
            break;
    }

    return toJString(env, profileManagementOperationStr);
}

LPAC_JNI_STRUCT_GETTER_LINKED_LIST_NEXT(struct es10b_notification_metadata_list, notifications)
LPAC_JNI_STRUCT_FREE(struct es10b_notification_metadata_list, notifications, es10b_notification_metadata_list_free_all)
LPAC_JNI_STRUCT_GETTER_LONG(struct es10b_notification_metadata_list, notification, seqNumber, Seq)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10b_notification_metadata_list, notification, notificationAddress, Address)
LPAC_JNI_STRUCT_GETTER_STRING(struct es10b_notification_metadata_list, notification, iccid, Iccid)
