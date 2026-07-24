#include "es9p.h"
#include "es9p_errors.h"
#include "logger.h"

#include <stdlib.h>
#include <string.h>
#include <strings.h>

#include <cjson-ext/cJSON_ex.h>

static const char *lpa_header[] = {
    "User-Agent: gsma-rsp-lpad",
    "X-Admin-Protocol: gsma/rsp/v2.2.2",
    "Content-Type: application/json",
    NULL,
};

static void es9p_base64_trim(char *str) {
    char *p = str;

    while (*p) {
        if (*p == '\n' || *p == '\r' || *p == ' ' || *p == '\t') {
            memmove(p, p + 1, strlen(p));
        } else {
            p++;
        }
    }
}

/*
 * RSP field names are case-sensitive. cJSON_GetObjectItem() is not: it returns
 * the first case-insensitive match and also silently accepts duplicate keys.
 * Apart from being ambiguous, that can make different protocol layers inspect
 * different values from the same response. Treat a duplicate or case-variant
 * spelling of a requested field as invalid.
 */
static cJSON *es9p_get_unique_case_sensitive_item(const cJSON *object, const char *name) {
    cJSON *item = NULL;
    cJSON *match = NULL;

    if (!cJSON_IsObject(object) || name == NULL) {
        return NULL;
    }

    cJSON_ArrayForEach(item, object) {
        if (item->string == NULL || strcasecmp(item->string, name) != 0) {
            continue;
        }
        if (strcmp(item->string, name) != 0 || match != NULL) {
            return NULL;
        }
        match = item;
    }

    return match;
}

static int es9p_trans_ex(struct euicc_ctx *ctx, const char *url, const char *url_postfix, uint32_t *rcode,
                         char **str_rx, const char *str_tx) {
    int fret = 0;
    uint32_t rcode_mearged;
    uint8_t *rbuf = NULL;
    uint32_t rlen;
    char *full_url = NULL;
    const char *url_prefix = "https://";

    if (!ctx->http.interface) {
        goto err;
    }

    full_url = malloc(strlen(url_prefix) + strlen(url) + strlen(url_postfix) + 1);
    if (full_url == NULL) {
        goto err;
    }

    full_url[0] = '\0';
    strcat(full_url, url_prefix);
    strcat(full_url, url);
    strcat(full_url, url_postfix);

    euicc_http_request_print(ctx->http.log_fp, full_url, str_tx);

    if (ctx->http.interface->transmit(ctx, full_url, &rcode_mearged, &rbuf, &rlen, (const uint8_t *)str_tx,
                                      strlen(str_tx), lpa_header)
        < 0) {
        goto err;
    }

    euicc_http_response_print(ctx->http.log_fp, rcode_mearged, (char *)rbuf);

    free(full_url);
    full_url = NULL;

    *str_rx = malloc(rlen + 1);
    if (*str_rx == NULL) {
        goto err;
    }
    memcpy(*str_rx, rbuf, rlen);
    (*str_rx)[rlen] = '\0';

    free(rbuf);
    rbuf = NULL;

    *rcode = rcode_mearged;

    fret = 0;
    goto exit;

err:
    fret = -1;
exit:
    free(full_url);
    free(rbuf);
    return fret;
}

static int es9p_trans_json(struct euicc_ctx *ctx, const char *smdp, const char *api, const char *ikey[],
                           const char *idata[], const char *okey[], const char *oobj, void **optr[]) {
    int fret = 0;
    cJSON *sjroot = NULL;
    char *sbuf = NULL;
    uint32_t rcode;
    char *rbuf = NULL;
    cJSON *rjroot = NULL, *rjheader = NULL, *rjfunctionExecutionStatus = NULL;
    void **pending_outputs = NULL;
    size_t output_count = 0;

    strncpy(ctx->http.status.reasonCode, "0.0.0", sizeof(ctx->http.status.reasonCode));
    strncpy(ctx->http.status.subjectCode, "0.0.0", sizeof(ctx->http.status.subjectCode));
    strncpy(ctx->http.status.subjectIdentifier, "unknown", sizeof(ctx->http.status.subjectIdentifier));
    strncpy(ctx->http.status.message, "unknown", sizeof(ctx->http.status.message));

    if (!(sjroot = cJSON_CreateObject())) {
        goto err;
    }

    for (int i = 0; ikey[i] != NULL; i++) {
        if (!cJSON_AddStringOrNullToObject(sjroot, ikey[i], idata[i])) {
            goto err;
        }
    }

    if (!(sbuf = cJSON_PrintUnformatted(sjroot))) {
        goto err;
    }
    cJSON_Delete(sjroot);
    sjroot = NULL;

    if (es9p_trans_ex(ctx, smdp, api, &rcode, &rbuf, sbuf) < 0) {
        strncpy(ctx->http.status.reasonCode, "0.0.0", sizeof(ctx->http.status.reasonCode));
        strncpy(ctx->http.status.subjectCode, "0.0.0", sizeof(ctx->http.status.subjectCode));
        strncpy(ctx->http.status.subjectIdentifier, "unknown", sizeof(ctx->http.status.subjectIdentifier));
        strncpy(ctx->http.status.message, "HTTP transport failed", sizeof(ctx->http.status.message));
        goto err;
    }
    cJSON_free(sbuf);
    sbuf = NULL;

    if (rcode / 100 != 2) {
        strncpy(ctx->http.status.reasonCode, "0.0.0", sizeof(ctx->http.status.reasonCode));
        strncpy(ctx->http.status.subjectCode, "0.0.0", sizeof(ctx->http.status.subjectCode));
        snprintf(ctx->http.status.subjectIdentifier, sizeof(ctx->http.status.subjectIdentifier), "%d", rcode);
        strncpy(ctx->http.status.message, "HTTP status code error", sizeof(ctx->http.status.message));
        goto err;
    }

    if (!okey) {
        fret = 0;
        goto exit;
    }

    if (!(rjroot = cJSON_ParseWithOpts((const char *)rbuf, NULL, 1))) {
        strncpy(ctx->http.status.reasonCode, "0.0.0", sizeof(ctx->http.status.reasonCode));
        strncpy(ctx->http.status.subjectCode, "0.0.0", sizeof(ctx->http.status.subjectCode));
        strncpy(ctx->http.status.subjectIdentifier, "root", sizeof(ctx->http.status.subjectIdentifier));
        strncpy(ctx->http.status.message, "Not JSON", sizeof(ctx->http.status.message));
        goto err;
    }
    free(rbuf);
    rbuf = NULL;

    if (!cJSON_IsObject(rjroot)) {
        strncpy(ctx->http.status.reasonCode, "0.0.0", sizeof(ctx->http.status.reasonCode));
        strncpy(ctx->http.status.subjectCode, "0.0.0", sizeof(ctx->http.status.subjectCode));
        strncpy(ctx->http.status.subjectIdentifier, "root", sizeof(ctx->http.status.subjectIdentifier));
        strncpy(ctx->http.status.message, "Not Object", sizeof(ctx->http.status.message));
        goto err;
    }

    rjheader = es9p_get_unique_case_sensitive_item(rjroot, "header");
    if (rjheader == NULL || !cJSON_IsObject(rjheader)) {
        strncpy(ctx->http.status.reasonCode, "0.0.0", sizeof(ctx->http.status.reasonCode));
        strncpy(ctx->http.status.subjectCode, "0.0.0", sizeof(ctx->http.status.subjectCode));
        strncpy(ctx->http.status.subjectIdentifier, "header", sizeof(ctx->http.status.subjectIdentifier));
        strncpy(ctx->http.status.message, "Critical object missing", sizeof(ctx->http.status.message));
        goto err;
    }

    rjfunctionExecutionStatus =
        es9p_get_unique_case_sensitive_item(rjheader, "functionExecutionStatus");
    if (rjfunctionExecutionStatus == NULL || !cJSON_IsObject(rjfunctionExecutionStatus)) {
        strncpy(ctx->http.status.reasonCode, "0.0.0", sizeof(ctx->http.status.reasonCode));
        strncpy(ctx->http.status.subjectCode, "0.0.0", sizeof(ctx->http.status.subjectCode));
        strncpy(ctx->http.status.subjectIdentifier, "functionExecutionStatus",
                sizeof(ctx->http.status.subjectIdentifier));
        strncpy(ctx->http.status.message, "Critical object missing", sizeof(ctx->http.status.message));
        goto err;
    }

    {
        cJSON *statusCodeData =
            es9p_get_unique_case_sensitive_item(rjfunctionExecutionStatus, "statusCodeData");

        if (statusCodeData != NULL && cJSON_IsObject(statusCodeData)) {
            cJSON *reasonCode = es9p_get_unique_case_sensitive_item(statusCodeData, "reasonCode");
            cJSON *subjectCode = es9p_get_unique_case_sensitive_item(statusCodeData, "subjectCode");
            cJSON *subjectIdentifier =
                es9p_get_unique_case_sensitive_item(statusCodeData, "subjectIdentifier");
            cJSON *message = es9p_get_unique_case_sensitive_item(statusCodeData, "message");

            if (cJSON_IsString(reasonCode)) {
                snprintf(ctx->http.status.reasonCode, sizeof(ctx->http.status.reasonCode), "%s",
                         reasonCode->valuestring);
            }
            if (cJSON_IsString(subjectCode)) {
                snprintf(ctx->http.status.subjectCode, sizeof(ctx->http.status.subjectCode), "%s",
                         subjectCode->valuestring);
            }
            if (cJSON_IsString(subjectIdentifier)) {
                snprintf(ctx->http.status.subjectIdentifier, sizeof(ctx->http.status.subjectIdentifier), "%s",
                         subjectIdentifier->valuestring);
            }
            if (cJSON_IsString(message)) {
                snprintf(ctx->http.status.message, sizeof(ctx->http.status.message), "%s",
                         message->valuestring);
            } else {
                const char *known_message =
                    es9p_error_message(ctx->http.status.subjectCode, ctx->http.status.reasonCode);
                if (known_message != NULL) {
                    snprintf(ctx->http.status.message, sizeof(ctx->http.status.message), "%s", known_message);
                } else {
                    snprintf(ctx->http.status.message, sizeof(ctx->http.status.message),
                             "subject-code: %s, reason-code: %s", ctx->http.status.subjectCode,
                             ctx->http.status.reasonCode);
                }
            }
        }
    }

    while (okey[output_count] != NULL) {
        output_count++;
    }
    pending_outputs = calloc(output_count, sizeof(void *));
    if (output_count > 0 && pending_outputs == NULL) {
        goto err;
    }

    for (int i = 0; okey[i] != NULL; i++) {
        cJSON *obj;

        obj = es9p_get_unique_case_sensitive_item(rjroot, okey[i]);
        if (!obj) {
            goto err;
        }

        if (cJSON_IsString(obj)) {
            pending_outputs[i] = strdup(obj->valuestring);
            if (pending_outputs[i] == NULL) {
                goto err;
            }
        } else {
            if (oobj[i] == 0) {
                goto err;
            }
            pending_outputs[i] = cJSON_Duplicate(obj, 1);
            if (pending_outputs[i] == NULL) {
                goto err;
            }
        }
    }

    for (size_t i = 0; i < output_count; i++) {
        *optr[i] = pending_outputs[i];
        pending_outputs[i] = NULL;
    }

    cJSON_Delete(rjroot);
    rjroot = NULL;

    fret = 0;
    goto exit;

err:
    fret = -1;
exit:
    if (pending_outputs != NULL) {
        for (size_t i = 0; i < output_count; i++) {
            if (pending_outputs[i] == NULL) {
                continue;
            }
            if (oobj[i] == 0) {
                free(pending_outputs[i]);
            } else {
                cJSON_Delete((cJSON *)pending_outputs[i]);
            }
        }
    }
    free(pending_outputs);
    cJSON_free(sbuf);
    cJSON_Delete(sjroot);
    free(rbuf);
    cJSON_Delete(rjroot);
    return fret;
}

int es9p_initiate_authentication_r(struct euicc_ctx *ctx, char **transaction_id,
                                   struct es10b_authenticate_server_param *resp, const char *server_address,
                                   const char *b64_euicc_challenge, const char *b64_euicc_info_1) {
    const char *ikey[] = {"smdpAddress", "euiccChallenge", "euiccInfo1", NULL};
    const char *idata[] = {server_address, b64_euicc_challenge, b64_euicc_info_1, NULL};
    const char *okey[] = {"transactionId",       "serverSigned1",     "serverSignature1",
                          "euiccCiPKIdToBeUsed", "serverCertificate", NULL};
    const char oobj[] = {0, 0, 0, 0, 0};
    void **optr[] = {(void **)transaction_id,
                     (void **)&resp->b64_serverSigned1,
                     (void **)&resp->b64_serverSignature1,
                     (void **)&resp->b64_euiccCiPKIdToBeUsed,
                     (void **)&resp->b64_serverCertificate,
                     NULL};

    if (es9p_trans_json(ctx, server_address, "/gsma/rsp2/es9plus/initiateAuthentication", ikey, idata, okey, oobj,
                        optr)) {
        return -1;
    }

    es9p_base64_trim(resp->b64_serverSigned1);
    es9p_base64_trim(resp->b64_serverSignature1);
    es9p_base64_trim(resp->b64_euiccCiPKIdToBeUsed);
    es9p_base64_trim(resp->b64_serverCertificate);

    return 0;
}

int es9p_get_bound_profile_package_r(struct euicc_ctx *ctx, char **b64_bound_profile_package,
                                     const char *server_address, const char *transaction_id,
                                     const char *b64_prepare_download_response) {
    const char *ikey[] = {"transactionId", "prepareDownloadResponse", NULL};
    const char *idata[] = {transaction_id, b64_prepare_download_response, NULL};
    const char *okey[] = {"boundProfilePackage", NULL};
    const char oobj[] = {0};
    void **optr[] = {(void **)b64_bound_profile_package, NULL};

    if (es9p_trans_json(ctx, server_address, "/gsma/rsp2/es9plus/getBoundProfilePackage", ikey, idata, okey, oobj,
                        optr)) {
        return -1;
    }

    es9p_base64_trim(*b64_bound_profile_package);

    return 0;
}

int es9p_authenticate_client_r(struct euicc_ctx *ctx, struct es10b_prepare_download_param *resp,
                               const char *server_address, const char *transaction_id,
                               const char *b64_authenticate_server_response) {
    const char *ikey[] = {"transactionId", "authenticateServerResponse", NULL};
    const char *idata[] = {transaction_id, b64_authenticate_server_response, NULL};
    const char *okey[] = {"profileMetadata", "smdpSigned2", "smdpSignature2", "smdpCertificate", NULL};
    const char oobj[] = {0, 0, 0, 0};
    void **optr[] = {(void **)&resp->b64_profileMetadata, (void **)&resp->b64_smdpSigned2,
                     (void **)&resp->b64_smdpSignature2, (void **)&resp->b64_smdpCertificate, NULL};

    if (es9p_trans_json(ctx, server_address, "/gsma/rsp2/es9plus/authenticateClient", ikey, idata, okey, oobj, optr)) {
        return -1;
    }

    es9p_base64_trim(resp->b64_profileMetadata);
    es9p_base64_trim(resp->b64_smdpSigned2);
    es9p_base64_trim(resp->b64_smdpSignature2);
    es9p_base64_trim(resp->b64_smdpCertificate);

    return 0;
}

int es9p_cancel_session_r(struct euicc_ctx *ctx, const char *server_address, const char *transaction_id,
                          const char *b64_cancel_session_response) {
    const char *ikey[] = {"transactionId", "cancelSessionResponse", NULL};
    const char *idata[] = {transaction_id, b64_cancel_session_response, NULL};

    if (es9p_trans_json(ctx, server_address, "/gsma/rsp2/es9plus/cancelSession", ikey, idata, NULL, NULL, NULL)) {
        return -1;
    }

    return 0;
}

int es11_authenticate_client_r(struct euicc_ctx *ctx, char ***smdp_list, const char *server_address,
                               const char *transaction_id, const char *b64_authenticate_server_response) {
    int fret = 0;
    cJSON *j_eventEntries = NULL;
    int j_eventEntries_size = 0;
    const char *ikey[] = {"transactionId", "authenticateServerResponse", NULL};
    const char *idata[] = {transaction_id, b64_authenticate_server_response, NULL};
    const char *okey[] = {"eventEntries", NULL};
    const char oobj[] = {1};
    void **optr[] = {(void **)&j_eventEntries, NULL};

    if (es9p_trans_json(ctx, server_address, "/gsma/rsp2/es9plus/authenticateClient", ikey, idata, okey, oobj, optr)) {
        return -1;
    }

    if (j_eventEntries == NULL || !cJSON_IsArray(j_eventEntries))
        goto err;

    j_eventEntries_size = cJSON_GetArraySize(j_eventEntries);
    if (j_eventEntries_size < 0 || j_eventEntries_size > 1024)
        goto err;

    *smdp_list = malloc(sizeof(char *) * (j_eventEntries_size + 1));
    if (*smdp_list == NULL) {
        fret = -1;
        goto err;
    }
    memset(*smdp_list, 0, sizeof(char *) * (j_eventEntries_size + 1));

    for (int i = 0; i < j_eventEntries_size; i++) {
        cJSON *j_event = cJSON_GetArrayItem(j_eventEntries, i);
        cJSON *j_eventType = es9p_get_unique_case_sensitive_item(j_event, "rspServerAddress");

        if (j_eventType == NULL || !cJSON_IsString(j_eventType)) {
            fret = -1;
            goto err;
        }

        (*smdp_list)[i] = strdup(j_eventType->valuestring);
        if ((*smdp_list)[i] == NULL) {
            fret = -1;
            goto err;
        }
    }

    fret = 0;
    goto exit;

err:
    if (*smdp_list) {
        for (int i = 0; i < j_eventEntries_size; i++) {
            free((*smdp_list)[i]);
        }
        free(*smdp_list);
        *smdp_list = NULL;
    }

exit:
    cJSON_Delete(j_eventEntries);
    return fret;
}

int es9p_initiate_authentication(struct euicc_ctx *ctx) {
    int fret;

    if (ctx->http._internal.authenticate_server_param) {
        return -1;
    }

    if (ctx->http._internal.b64_euicc_challenge == NULL) {
        return -1;
    }

    if (ctx->http._internal.b64_euicc_info_1 == NULL) {
        return -1;
    }

    ctx->http._internal.authenticate_server_param = calloc(1, sizeof(struct es10b_authenticate_server_param));
    if (ctx->http._internal.authenticate_server_param == NULL) {
        return -1;
    }

    fret = es9p_initiate_authentication_r(
        ctx, &ctx->http._internal.transaction_id_http, ctx->http._internal.authenticate_server_param,
        ctx->http.server_address, ctx->http._internal.b64_euicc_challenge, ctx->http._internal.b64_euicc_info_1);
    if (fret < 0) {
        free(ctx->http._internal.authenticate_server_param);
        ctx->http._internal.authenticate_server_param = NULL;
        return fret;
    }

    free(ctx->http._internal.b64_euicc_challenge);
    ctx->http._internal.b64_euicc_challenge = NULL;

    free(ctx->http._internal.b64_euicc_info_1);
    ctx->http._internal.b64_euicc_info_1 = NULL;

    return fret;
}

int es9p_get_bound_profile_package(struct euicc_ctx *ctx) {
    int fret;

    if (ctx->http._internal.b64_bound_profile_package) {
        return -1;
    }

    if (ctx->http._internal.b64_prepare_download_response == NULL) {
        return -1;
    }

    fret = es9p_get_bound_profile_package_r(ctx, &ctx->http._internal.b64_bound_profile_package,
                                            ctx->http.server_address, ctx->http._internal.transaction_id_http,
                                            ctx->http._internal.b64_prepare_download_response);
    if (fret < 0) {
        free(ctx->http._internal.b64_bound_profile_package);
        ctx->http._internal.b64_bound_profile_package = NULL;
        return fret;
    }

    free(ctx->http._internal.b64_prepare_download_response);
    ctx->http._internal.b64_prepare_download_response = NULL;

    return fret;
}

int es9p_authenticate_client(struct euicc_ctx *ctx) {
    int fret;

    if (ctx->http._internal.prepare_download_param) {
        return -1;
    }

    if (ctx->http._internal.b64_authenticate_server_response == NULL) {
        return -1;
    }

    ctx->http._internal.prepare_download_param = calloc(1, sizeof(struct es10b_prepare_download_param));
    if (ctx->http._internal.prepare_download_param == NULL) {
        return -1;
    }

    fret = es9p_authenticate_client_r(ctx, ctx->http._internal.prepare_download_param, ctx->http.server_address,
                                      ctx->http._internal.transaction_id_http,
                                      ctx->http._internal.b64_authenticate_server_response);
    if (fret < 0) {
        free(ctx->http._internal.prepare_download_param);
        ctx->http._internal.prepare_download_param = NULL;
        return fret;
    }

    free(ctx->http._internal.b64_authenticate_server_response);
    ctx->http._internal.b64_authenticate_server_response = NULL;

    return fret;
}

int es9p_cancel_session(struct euicc_ctx *ctx) {
    int fret;

    if (ctx->http._internal.b64_cancel_session_response == NULL) {
        return -1;
    }

    fret = es9p_cancel_session_r(ctx, ctx->http.server_address, ctx->http._internal.transaction_id_http,
                                 ctx->http._internal.b64_cancel_session_response);
    if (fret < 0) {
        return fret;
    }

    free(ctx->http._internal.b64_cancel_session_response);
    ctx->http._internal.b64_cancel_session_response = NULL;

    return fret;
}

int es11_authenticate_client(struct euicc_ctx *ctx, char ***smdp_list) {
    int fret;

    if (ctx->http._internal.b64_authenticate_server_response == NULL) {
        return -1;
    }

    fret = es11_authenticate_client_r(ctx, smdp_list, ctx->http.server_address, ctx->http._internal.transaction_id_http,
                                      ctx->http._internal.b64_authenticate_server_response);
    if (fret < 0) {
        return fret;
    }

    free(ctx->http._internal.b64_authenticate_server_response);
    ctx->http._internal.b64_authenticate_server_response = NULL;

    return fret;
}

int es9p_handle_notification(struct euicc_ctx *ctx, const char *b64_PendingNotification) {
    const char *ikey[] = {"pendingNotification", NULL};
    const char *idata[] = {b64_PendingNotification, NULL};

    return es9p_trans_json(ctx, ctx->http.server_address, "/gsma/rsp2/es9plus/handleNotification", ikey, idata, NULL,
                           NULL, NULL);
}

void es11_smdp_list_free_all(char **smdp_list) {
    if (smdp_list) {
        for (int i = 0; smdp_list[i] != NULL; i++) {
            free(smdp_list[i]);
        }
        free(smdp_list);
    }
}
