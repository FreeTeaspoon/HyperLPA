#include <string.h>
#include <malloc.h>
#include "interface-wrapper.h"

jmethodID method_apdu_connect;
jmethodID method_apdu_disconnect;
jmethodID method_apdu_logical_channel_open;
jmethodID method_apdu_logical_channel_close;
jmethodID method_apdu_transmit;

jmethodID method_http_transmit;

jfieldID field_resp_rcode;
jfieldID field_resp_data;

void interface_wrapper_init() {
    LPAC_JNI_SETUP_ENV;
    jclass apdu_class = (*env)->FindClass(env, "net/typeblog/lpac_jni/ApduInterface");
    method_apdu_connect = (*env)->GetMethodID(env, apdu_class, "connect", "()V");
    method_apdu_disconnect = (*env)->GetMethodID(env, apdu_class, "disconnect", "()V");
    method_apdu_logical_channel_open = (*env)->GetMethodID(env, apdu_class, "logicalChannelOpen",
                                                           "([B)I");
    method_apdu_logical_channel_close = (*env)->GetMethodID(env, apdu_class, "logicalChannelClose",
                                                            "(I)V");
    method_apdu_transmit = (*env)->GetMethodID(env, apdu_class, "transmit", "(I[B)[B");

    jclass http_class = (*env)->FindClass(env, "net/typeblog/lpac_jni/HttpInterface");
    method_http_transmit = (*env)->GetMethodID(env, http_class, "transmit",
                                               "(Ljava/lang/String;[B[Ljava/lang/String;)Lnet/typeblog/lpac_jni/HttpInterface$HttpResponse;");

    jclass resp_class = (*env)->FindClass(env, "net/typeblog/lpac_jni/HttpInterface$HttpResponse");
    field_resp_rcode = (*env)->GetFieldID(env, resp_class, "rcode", "I");
    field_resp_data = (*env)->GetFieldID(env, resp_class, "data", "[B");
}

static int apdu_interface_connect(struct euicc_ctx *ctx) {
    LPAC_JNI_SETUP_ENV;
    (*env)->CallVoidMethod(env, LPAC_JNI_CTX(ctx)->apdu_interface, method_apdu_connect);
    LPAC_JNI_EXCEPTION_RETURN;
    return 0;
}

static void apdu_interface_disconnect(struct euicc_ctx *ctx) {
    LPAC_JNI_SETUP_ENV;
    if ((*env)->ExceptionCheck(env))
        return;
    (*env)->CallVoidMethod(env, LPAC_JNI_CTX(ctx)->apdu_interface, method_apdu_disconnect);
}

static int
apdu_interface_logical_channel_open(struct euicc_ctx *ctx, const uint8_t *aid, uint8_t aid_len) {
    LPAC_JNI_SETUP_ENV;
    if (aid == NULL || aid_len < LPAC_JNI_MIN_AID_BYTES || aid_len > LPAC_JNI_MAX_AID_BYTES)
        return -1;
    jbyteArray jbarr = (*env)->NewByteArray(env, aid_len);
    if (jbarr == NULL) {
        lpac_jni_capture_exception(ctx, env);
        return -1;
    }
    (*env)->SetByteArrayRegion(env, jbarr, 0, aid_len, (const jbyte *) aid);
    if ((*env)->ExceptionCheck(env)) {
        lpac_jni_capture_exception(ctx, env);
        (*env)->DeleteLocalRef(env, jbarr);
        return -1;
    }
    jint ret = (*env)->CallIntMethod(env, LPAC_JNI_CTX(ctx)->apdu_interface,
                                     method_apdu_logical_channel_open, jbarr);
    (*env)->DeleteLocalRef(env, jbarr);
    if ((*env)->ExceptionCheck(env)) {
        lpac_jni_capture_exception(ctx, env);
        return -1;
    }
    LPAC_JNI_CTX(ctx)->logical_channel_id = ret;
    return ret;
}

static void apdu_interface_logical_channel_close(struct euicc_ctx *ctx,
                                                 __attribute__((unused)) uint8_t channel) {
    LPAC_JNI_SETUP_ENV;
    jint logical_channel_id = LPAC_JNI_CTX(ctx)->logical_channel_id;
    (*env)->CallVoidMethod(env, LPAC_JNI_CTX(ctx)->apdu_interface,
                           method_apdu_logical_channel_close, logical_channel_id);
    /* euicc_fini must still call disconnect, so retain the original failure in
     * the context and rethrow it only after native cleanup has completed. */
    lpac_jni_capture_exception(ctx, env);
}

static int
apdu_interface_transmit(struct euicc_ctx *ctx, uint8_t **rx, uint32_t *rx_len, const uint8_t *tx,
                        uint32_t tx_len) {
    const int logic_channel = LPAC_JNI_CTX(ctx)->logical_channel_id;
    LPAC_JNI_SETUP_ENV;
    jbyteArray txArr = NULL;
    jbyteArray ret = NULL;
    jsize ret_len;
    int result = -1;

    if (rx == NULL || rx_len == NULL || tx == NULL || tx_len > LPAC_JNI_MAX_APDU_BYTES)
        return -1;
    *rx = NULL;
    *rx_len = 0;

    txArr = (*env)->NewByteArray(env, (jsize)tx_len);
    if (txArr == NULL)
        goto out;
    (*env)->SetByteArrayRegion(env, txArr, 0, (jsize)tx_len, (const jbyte *) tx);
    if ((*env)->ExceptionCheck(env))
        goto out;
    ret = (jbyteArray) (*env)->CallObjectMethod(
            env, LPAC_JNI_CTX(ctx)->apdu_interface,
            method_apdu_transmit, logic_channel, txArr
    );
    if ((*env)->ExceptionCheck(env) || ret == NULL)
        goto out;
    ret_len = (*env)->GetArrayLength(env, ret);
    if (ret_len < 2 || (uint32_t)ret_len > LPAC_JNI_MAX_APDU_BYTES)
        goto out;
    if (ret_len > 0) {
        *rx = malloc((size_t)ret_len);
        if (*rx == NULL)
            goto out;
        (*env)->GetByteArrayRegion(env, ret, 0, ret_len, (jbyte *)*rx);
        if ((*env)->ExceptionCheck(env))
            goto out;
    }
    *rx_len = (uint32_t)ret_len;
    result = 0;

out:
    if (result != 0) {
        free(*rx);
        *rx = NULL;
        *rx_len = 0;
    }
    if (txArr != NULL)
        (*env)->DeleteLocalRef(env, txArr);
    if (ret != NULL)
        (*env)->DeleteLocalRef(env, ret);
    return result;
}

static int
http_interface_transmit(struct euicc_ctx *ctx, const char *url, uint32_t *rcode, uint8_t **rx,
                        uint32_t *rx_len, const uint8_t *tx, uint32_t tx_len,
                        const char **headers) {
    LPAC_JNI_SETUP_ENV;
    jstring jurl = NULL;
    jbyteArray txArr = NULL;
    jobjectArray headersArr = NULL;
    jobject ret = NULL;
    jbyteArray rxArr = NULL;
    jsize ret_len;
    int result = -1;

    if (url == NULL || rcode == NULL || rx == NULL || rx_len == NULL || tx == NULL ||
        headers == NULL || tx_len > LPAC_JNI_MAX_HTTP_BYTES)
        return -1;
    *rcode = 0;
    *rx = NULL;
    *rx_len = 0;

    jurl = toJString(env, url);
    if (jurl == NULL)
        goto out;
    txArr = (*env)->NewByteArray(env, (jsize)tx_len);
    if (txArr == NULL)
        goto out;
    (*env)->SetByteArrayRegion(env, txArr, 0, (jsize)tx_len, (const jbyte *) tx);
    if ((*env)->ExceptionCheck(env))
        goto out;

    uint32_t num_headers = 0;
    while (num_headers < LPAC_JNI_MAX_HTTP_HEADERS && headers[num_headers] != NULL) {
        num_headers++;
    }
    if (num_headers == LPAC_JNI_MAX_HTTP_HEADERS)
        goto out;
    headersArr = (*env)->NewObjectArray(env, (jsize)num_headers, string_class, NULL);
    if (headersArr == NULL)
        goto out;
    for (uint32_t i = 0; i < num_headers; i++) {
        jstring header = toJString(env, headers[i]);
        if (header == NULL)
            goto out;
        (*env)->SetObjectArrayElement(env, headersArr, (jsize)i, header);
        (*env)->DeleteLocalRef(env, header);
        if ((*env)->ExceptionCheck(env))
            goto out;
    }

    ret = (*env)->CallObjectMethod(env, LPAC_JNI_CTX(ctx)->http_interface,
                                   method_http_transmit, jurl, txArr, headersArr);
    if ((*env)->ExceptionCheck(env) || ret == NULL)
        goto out;
    *rcode = (*env)->GetIntField(env, ret, field_resp_rcode);
    rxArr = (jbyteArray) (*env)->GetObjectField(env, ret, field_resp_data);
    if ((*env)->ExceptionCheck(env) || rxArr == NULL)
        goto out;
    ret_len = (*env)->GetArrayLength(env, rxArr);
    if (ret_len < 0 || (uint32_t)ret_len > LPAC_JNI_MAX_HTTP_BYTES)
        goto out;
    /* libeuicc diagnostics temporarily treat this response as a C string. */
    *rx = calloc((size_t)ret_len + 1U, 1U);
    if (*rx == NULL)
        goto out;
    if (ret_len > 0) {
        (*env)->GetByteArrayRegion(env, rxArr, 0, ret_len, (jbyte *)*rx);
        if ((*env)->ExceptionCheck(env))
            goto out;
    }
    *rx_len = (uint32_t)ret_len;
    result = 0;

out:
    if (result != 0) {
        free(*rx);
        *rx = NULL;
        *rx_len = 0;
    }
    if (jurl != NULL)
        (*env)->DeleteLocalRef(env, jurl);
    if (txArr != NULL)
        (*env)->DeleteLocalRef(env, txArr);
    if (rxArr != NULL)
        (*env)->DeleteLocalRef(env, rxArr);
    if (headersArr != NULL)
        (*env)->DeleteLocalRef(env, headersArr);
    if (ret != NULL)
        (*env)->DeleteLocalRef(env, ret);
    return result;
}

struct euicc_apdu_interface lpac_jni_apdu_interface = {
        .connect = &apdu_interface_connect,
        .disconnect = &apdu_interface_disconnect,
        .logic_channel_open = &apdu_interface_logical_channel_open,
        .logic_channel_close = &apdu_interface_logical_channel_close,
        .transmit = &apdu_interface_transmit
};

struct euicc_http_interface lpac_jni_http_interface = {
        .transmit = &http_interface_transmit
};
