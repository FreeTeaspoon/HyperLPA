#include "es10c.h"
#include "es10c_ex.h"
#include "es8p.h"
#include "euicc.private.h"
#include "base64.h"
#include "derutil.h"
#include "rsp_limits.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static const uint8_t *stub_response;
static size_t stub_response_length;

void euicc_apdu_unhandled_tag_print(FILE *fp, const struct euicc_derutil_node *node) {
    (void)fp;
    (void)node;
}

int es10x_command(struct euicc_ctx *ctx, uint8_t **response, unsigned *response_length,
                  const uint8_t *request, unsigned request_length) {
    (void)ctx;
    (void)request;
    (void)request_length;
    *response = malloc(stub_response_length);
    assert(*response != NULL);
    memcpy(*response, stub_response, stub_response_length);
    *response_length = (unsigned)stub_response_length;
    return 0;
}

static void use_response(const uint8_t *response, size_t response_length) {
    stub_response = response;
    stub_response_length = response_length;
}

static size_t write_der_length(uint8_t *output, size_t length) {
    if (length < 0x80U) {
        output[0] = (uint8_t)length;
        return 1;
    }
    if (length <= 0xFFU) {
        output[0] = 0x81;
        output[1] = (uint8_t)length;
        return 2;
    }
    if (length <= 0xFFFFU) {
        output[0] = 0x82;
        output[1] = (uint8_t)(length >> 8);
        output[2] = (uint8_t)length;
        return 3;
    }
    abort();
}

static uint8_t *make_tlv(uint16_t tag, const uint8_t *value, size_t value_length, size_t *tlv_length) {
    const size_t tag_length = tag > 0xFFU ? 2U : 1U;
    uint8_t encoded_length[3];
    const size_t length_length = write_der_length(encoded_length, value_length);
    uint8_t *result = malloc(tag_length + length_length + value_length);
    size_t offset = 0;
    assert(result != NULL);

    if (tag_length == 2U)
        result[offset++] = (uint8_t)(tag >> 8);
    result[offset++] = (uint8_t)tag;
    memcpy(result + offset, encoded_length, length_length);
    offset += length_length;
    if (value_length != 0)
        memcpy(result + offset, value, value_length);
    *tlv_length = offset + value_length;
    return result;
}

static uint8_t *join(const uint8_t *first, size_t first_length, const uint8_t *second,
                     size_t second_length, size_t *joined_length) {
    uint8_t *result = malloc(first_length + second_length);
    assert(result != NULL);
    if (first_length != 0)
        memcpy(result, first, first_length);
    if (second_length != 0)
        memcpy(result + first_length, second, second_length);
    *joined_length = first_length + second_length;
    return result;
}

static uint8_t *make_profile_response(const uint8_t *fields, size_t fields_length, size_t *response_length) {
    size_t profile_length;
    size_t list_length;
    uint8_t *profile = make_tlv(0xE3, fields, fields_length, &profile_length);
    uint8_t *list = make_tlv(0xA0, profile, profile_length, &list_length);
    uint8_t *response = make_tlv(0xBF2D, list, list_length, response_length);
    free(profile);
    free(list);
    return response;
}

static uint8_t *make_profile_field_response(uint16_t tag, const uint8_t *value, size_t value_length,
                                            size_t *response_length) {
    size_t field_length;
    uint8_t *field = make_tlv(tag, value, value_length, &field_length);
    uint8_t *response = make_profile_response(field, field_length, response_length);
    free(field);
    return response;
}

static uint8_t *make_info_field_response(uint16_t tag, const uint8_t *value, size_t value_length,
                                         size_t *response_length) {
    size_t field_length;
    uint8_t *field = make_tlv(tag, value, value_length, &field_length);
    uint8_t *response = make_tlv(0xBF22, field, field_length, response_length);
    free(field);
    return response;
}

static char *make_metadata(const uint8_t *provider, size_t provider_length, size_t icon_length,
                           int duplicate_provider) {
    const uint8_t iccid_value[EUICC_RSP_ICCID_BYTES] = {0x21};
    const uint8_t profile_name[] = {'N'};
    uint8_t *fields = NULL;
    size_t fields_length = 0;
    size_t item_length;
    uint8_t *item;

#define APPEND_METADATA_FIELD(TAG, VALUE, LENGTH)                                                        \
    do {                                                                                                  \
        item = make_tlv((TAG), (VALUE), (LENGTH), &item_length);                                          \
        uint8_t *new_fields = join(fields, fields_length, item, item_length, &fields_length);             \
        free(fields);                                                                                     \
        free(item);                                                                                       \
        fields = new_fields;                                                                              \
    } while (0)

    APPEND_METADATA_FIELD(0x5A, iccid_value, sizeof(iccid_value));
    APPEND_METADATA_FIELD(0x91, provider, provider_length);
    APPEND_METADATA_FIELD(0x92, profile_name, sizeof(profile_name));
    if (duplicate_provider)
        APPEND_METADATA_FIELD(0x91, provider, provider_length);
    if (icon_length != 0) {
        uint8_t *icon = calloc(icon_length, 1);
        assert(icon != NULL);
        APPEND_METADATA_FIELD(0x94, icon, icon_length);
        free(icon);
    }

#undef APPEND_METADATA_FIELD

    size_t metadata_length;
    uint8_t *metadata = make_tlv(0xBF25, fields, fields_length, &metadata_length);
    char *encoded = malloc((size_t)euicc_base64_encode_len((int)metadata_length));
    assert(encoded != NULL);
    assert(euicc_base64_encode(encoded, metadata, (int)metadata_length) > 0);
    free(fields);
    free(metadata);
    return encoded;
}

static void test_profile_singletons(void) {
    struct euicc_ctx context = {0};
    struct es10c_profile_info_list *profiles = NULL;
    const uint8_t valid_nickname[] = {
        0xBF, 0x2D, 0x07, 0xA0, 0x05, 0xE3, 0x03, 0x90, 0x01, 'A',
    };
    const uint8_t duplicate_nickname[] = {
        0xBF, 0x2D, 0x0A, 0xA0, 0x08, 0xE3, 0x06,
        0x90, 0x01, 'A', 0x90, 0x01, 'B',
    };
    const uint8_t duplicate_owner_field[] = {
        0xBF, 0x2D, 0x0C, 0xA0, 0x0A, 0xE3, 0x08,
        0xB7, 0x06, 0x80, 0x01, 0x21, 0x80, 0x01, 0x43,
    };

    use_response(valid_nickname, sizeof(valid_nickname));
    assert(es10c_get_profiles_info(&context, &profiles) == 0);
    assert(profiles != NULL);
    assert(strcmp(profiles->profileNickname, "A") == 0);
    es10c_profile_info_list_free_all(profiles);

    profiles = NULL;
    use_response(duplicate_nickname, sizeof(duplicate_nickname));
    assert(es10c_get_profiles_info(&context, &profiles) < 0);
    assert(profiles == NULL);

    use_response(duplicate_owner_field, sizeof(duplicate_owner_field));
    assert(es10c_get_profiles_info(&context, &profiles) < 0);
    assert(profiles == NULL);
}

static void test_euicc_info_singletons(void) {
    struct euicc_ctx context = {0};
    struct es10c_ex_euiccinfo2 info;
    const uint8_t valid_version[] = {
        0xBF, 0x22, 0x05, 0x81, 0x03, 0x01, 0x02, 0x03,
    };
    const uint8_t duplicate_version[] = {
        0xBF, 0x22, 0x0A,
        0x81, 0x03, 0x01, 0x02, 0x03,
        0x81, 0x03, 0x04, 0x05, 0x06,
    };
    const uint8_t duplicate_certification_field[] = {
        0xBF, 0x22, 0x08, 0xAC, 0x06,
        0x80, 0x01, 'A', 0x80, 0x01, 'B',
    };

    use_response(valid_version, sizeof(valid_version));
    assert(es10c_ex_get_euiccinfo2(&context, &info) == 0);
    assert(strcmp(info.profileVersion, "1.2.3") == 0);
    es10c_ex_euiccinfo2_free(&info);

    use_response(duplicate_version, sizeof(duplicate_version));
    assert(es10c_ex_get_euiccinfo2(&context, &info) < 0);
    assert(info.profileVersion == NULL);

    use_response(duplicate_certification_field, sizeof(duplicate_certification_field));
    assert(es10c_ex_get_euiccinfo2(&context, &info) < 0);
    assert(info.certificationDataObject.platformLabel == NULL);
}

static void test_profile_field_limits(void) {
    struct euicc_ctx context = {0};
    struct es10c_profile_info_list *profiles = NULL;
    size_t response_length;
    uint8_t *response;
    uint8_t nickname[65];
    uint8_t provider[33];
    uint8_t profile_name[65];
    uint8_t icon[EUICC_RSP_ICON_BYTES + 1U];
    uint8_t aid[EUICC_RSP_AID_MAX_BYTES + 1U];
    uint8_t unicode_nickname[EUICC_RSP_PROFILE_NICKNAME_CHARS * 4U];

    memset(nickname, 'N', sizeof(nickname));
    memset(provider, 'P', sizeof(provider));
    memset(profile_name, 'R', sizeof(profile_name));
    memset(icon, 0xAA, sizeof(icon));
    memset(aid, 0xA0, sizeof(aid));
    for (size_t i = 0; i < sizeof(unicode_nickname); i += 4U) {
        unicode_nickname[i] = 0xF0;
        unicode_nickname[i + 1U] = 0x9F;
        unicode_nickname[i + 2U] = 0x98;
        unicode_nickname[i + 3U] = 0x80;
    }

    response = make_profile_field_response(0x90, unicode_nickname, sizeof(unicode_nickname), &response_length);
    use_response(response, response_length);
    assert(es10c_get_profiles_info(&context, &profiles) == 0);
    assert(profiles != NULL && strlen(profiles->profileNickname) == sizeof(unicode_nickname));
    es10c_profile_info_list_free_all(profiles);
    profiles = NULL;
    free(response);

#define ASSERT_PROFILE_FIELD_REJECTED(TAG, VALUE)                                                    \
    do {                                                                                              \
        response = make_profile_field_response((TAG), (VALUE), sizeof(VALUE), &response_length);      \
        use_response(response, response_length);                                                       \
        assert(es10c_get_profiles_info(&context, &profiles) < 0);                                     \
        assert(profiles == NULL);                                                                      \
        free(response);                                                                                \
    } while (0)

    ASSERT_PROFILE_FIELD_REJECTED(0x90, nickname);
    ASSERT_PROFILE_FIELD_REJECTED(0x91, provider);
    ASSERT_PROFILE_FIELD_REJECTED(0x92, profile_name);
    ASSERT_PROFILE_FIELD_REJECTED(0x94, icon);
    ASSERT_PROFILE_FIELD_REJECTED(0x4F, aid);

#undef ASSERT_PROFILE_FIELD_REJECTED

    {
        const uint8_t short_iccid[EUICC_RSP_ICCID_BYTES - 1U] = {0};
        response = make_profile_field_response(0x5A, short_iccid, sizeof(short_iccid), &response_length);
        use_response(response, response_length);
        assert(es10c_get_profiles_info(&context, &profiles) < 0);
        assert(profiles == NULL);
        free(response);
    }

    {
        uint8_t address[EUICC_RSP_FQDN_BYTES + 1U];
        const uint8_t operation_value[] = {0, 0x80};
        size_t operation_length, address_length, configuration_fields_length, sequence_length, b6_length;
        uint8_t *operation;
        uint8_t *address_field;
        uint8_t *configuration_fields;
        uint8_t *sequence;
        uint8_t *b6;
        memset(address, 'a', sizeof(address));
        operation = make_tlv(0x80, operation_value, sizeof(operation_value), &operation_length);
        address_field = make_tlv(0x81, address, sizeof(address), &address_length);
        configuration_fields = join(operation, operation_length, address_field, address_length,
                                    &configuration_fields_length);
        sequence = make_tlv(0x30, configuration_fields, configuration_fields_length, &sequence_length);
        b6 = make_tlv(0xB6, sequence, sequence_length, &b6_length);
        response = make_profile_response(b6, b6_length, &response_length);
        use_response(response, response_length);
        assert(es10c_get_profiles_info(&context, &profiles) < 0);
        assert(profiles == NULL);
        free(operation);
        free(address_field);
        free(configuration_fields);
        free(sequence);
        free(b6);
        free(response);
    }

    {
        uint8_t gid[EUICC_RSP_GID_BYTES + 1U] = {0};
        const uint8_t mccmnc[] = {0x21, 0x43, 0x65};
        size_t mccmnc_length, gid_length, owner_fields_length, owner_length;
        uint8_t *mccmnc_field = make_tlv(0x80, mccmnc, sizeof(mccmnc), &mccmnc_length);
        uint8_t *gid_field = make_tlv(0x81, gid, sizeof(gid), &gid_length);
        uint8_t *owner_fields = join(mccmnc_field, mccmnc_length, gid_field, gid_length, &owner_fields_length);
        uint8_t *owner = make_tlv(0xB7, owner_fields, owner_fields_length, &owner_length);
        response = make_profile_response(owner, owner_length, &response_length);
        use_response(response, response_length);
        assert(es10c_get_profiles_info(&context, &profiles) < 0);
        assert(profiles == NULL);
        free(mccmnc_field);
        free(gid_field);
        free(owner_fields);
        free(owner);
        free(response);
    }
}

static uint8_t *make_profile_count_response(size_t count, size_t *response_length) {
    const uint8_t empty_profile[] = {0xE3, 0x00};
    uint8_t *profiles = malloc(count * sizeof(empty_profile));
    size_t list_length;
    uint8_t *list;
    uint8_t *response;
    assert(profiles != NULL);
    for (size_t i = 0; i < count; i++)
        memcpy(profiles + (i * sizeof(empty_profile)), empty_profile, sizeof(empty_profile));
    list = make_tlv(0xA0, profiles, count * sizeof(empty_profile), &list_length);
    response = make_tlv(0xBF2D, list, list_length, response_length);
    free(profiles);
    free(list);
    return response;
}

static void test_profile_count_limit(void) {
    struct euicc_ctx context = {0};
    struct es10c_profile_info_list *profiles = NULL;
    size_t response_length;
    uint8_t *response = make_profile_count_response(EUICC_RSP_PROFILE_COUNT, &response_length);
    use_response(response, response_length);
    assert(es10c_get_profiles_info(&context, &profiles) == 0);
    es10c_profile_info_list_free_all(profiles);
    profiles = NULL;
    free(response);

    response = make_profile_count_response(EUICC_RSP_PROFILE_COUNT + 1U, &response_length);
    use_response(response, response_length);
    assert(es10c_get_profiles_info(&context, &profiles) < 0);
    assert(profiles == NULL);
    free(response);
}

static void test_euicc_info_field_limits(void) {
    struct euicc_ctx context = {0};
    struct es10c_ex_euiccinfo2 info;
    size_t response_length;
    uint8_t *response;
    uint8_t sas[EUICC_RSP_SAS_NUMBER_CHARS + 1U];
    uint8_t key_id[EUICC_RSP_CI_PKID_BYTES + 1U];
    uint8_t label[EUICC_RSP_PLATFORM_LABEL_CHARS + 1U];
    uint8_t capability[EUICC_RSP_BIT_STRING_BYTES + 1U];
    memset(sas, 'S', sizeof(sas));
    memset(key_id, 0x11, sizeof(key_id));
    memset(label, 'L', sizeof(label));
    memset(capability, 0, sizeof(capability));

    response = make_info_field_response(0x0C, sas, sizeof(sas), &response_length);
    use_response(response, response_length);
    assert(es10c_ex_get_euiccinfo2(&context, &info) < 0);
    free(response);

    {
        size_t key_tlv_length;
        uint8_t *key_tlv = make_tlv(0x04, key_id, sizeof(key_id), &key_tlv_length);
        response = make_info_field_response(0xA9, key_tlv, key_tlv_length, &response_length);
        use_response(response, response_length);
        assert(es10c_ex_get_euiccinfo2(&context, &info) < 0);
        free(key_tlv);
        free(response);
    }

    {
        size_t label_tlv_length;
        uint8_t *label_tlv = make_tlv(0x80, label, sizeof(label), &label_tlv_length);
        response = make_info_field_response(0xAC, label_tlv, label_tlv_length, &response_length);
        use_response(response, response_length);
        assert(es10c_ex_get_euiccinfo2(&context, &info) < 0);
        free(label_tlv);
        free(response);
    }

    response = make_info_field_response(0x85, capability, sizeof(capability), &response_length);
    use_response(response, response_length);
    assert(es10c_ex_get_euiccinfo2(&context, &info) < 0);
    free(response);
}

static void test_store_metadata_limits(void) {
    struct es8p_metadata *metadata = NULL;
    const uint8_t provider[] = {'P'};
    uint8_t unicode_provider[EUICC_RSP_PROVIDER_NAME_CHARS * 4U];
    uint8_t oversized_provider[EUICC_RSP_PROVIDER_NAME_CHARS + 1U];
    char *encoded;

    for (size_t i = 0; i < sizeof(unicode_provider); i += 4U) {
        unicode_provider[i] = 0xF0;
        unicode_provider[i + 1U] = 0x9F;
        unicode_provider[i + 2U] = 0x98;
        unicode_provider[i + 3U] = 0x80;
    }
    memset(oversized_provider, 'P', sizeof(oversized_provider));

    encoded = make_metadata(unicode_provider, sizeof(unicode_provider), EUICC_RSP_ICON_BYTES, 0);
    assert(es8p_metadata_parse(&metadata, encoded) == 0);
    assert(metadata != NULL && strlen(metadata->serviceProviderName) == sizeof(unicode_provider));
    es8p_metadata_free(&metadata);
    free(encoded);

    encoded = make_metadata(oversized_provider, sizeof(oversized_provider), 0, 0);
    assert(es8p_metadata_parse(&metadata, encoded) < 0);
    assert(metadata == NULL);
    free(encoded);

    encoded = make_metadata(provider, sizeof(provider), EUICC_RSP_ICON_BYTES + 1U, 0);
    assert(es8p_metadata_parse(&metadata, encoded) < 0);
    assert(metadata == NULL);
    free(encoded);

    encoded = make_metadata(provider, sizeof(provider), 0, 1);
    assert(es8p_metadata_parse(&metadata, encoded) < 0);
    assert(metadata == NULL);
    free(encoded);

    encoded = malloc(EUICC_RSP_METADATA_BASE64_BYTES + 2U);
    assert(encoded != NULL);
    memset(encoded, 'A', EUICC_RSP_METADATA_BASE64_BYTES + 1U);
    encoded[EUICC_RSP_METADATA_BASE64_BYTES + 1U] = '\0';
    assert(es8p_metadata_parse(&metadata, encoded) < 0);
    assert(metadata == NULL);
    free(encoded);
}

static void test_rejects_truncated_trailing_fields(void) {
    struct euicc_ctx context = {0};
    struct es10c_profile_info_list *profiles = NULL;
    struct es10c_ex_euiccinfo2 info;
    const uint8_t nickname_field_and_trailing_tag[] = {0x90, 0x01, 'A', 0x91};
    const uint8_t version_field_and_trailing_tag[] = {0x81, 0x03, 1, 2, 3, 0x82};
    size_t response_length;
    uint8_t *response = make_profile_response(
        nickname_field_and_trailing_tag, sizeof(nickname_field_and_trailing_tag), &response_length);
    use_response(response, response_length);
    assert(es10c_get_profiles_info(&context, &profiles) < 0);
    assert(profiles == NULL);
    free(response);

    response = make_tlv(0xBF22, version_field_and_trailing_tag,
                        sizeof(version_field_and_trailing_tag), &response_length);
    use_response(response, response_length);
    assert(es10c_ex_get_euiccinfo2(&context, &info) < 0);
    assert(info.profileVersion == NULL);
    free(response);
}

static void test_strict_base64_validation(void) {
    assert(euicc_base64_validate("QQ==", 4) == 0);
    assert(euicc_base64_validate("QQ", 2) == 0);
    assert(euicc_base64_validate("QQ==trailing", 32) < 0);
    assert(euicc_base64_validate("Q", 1) < 0);
    assert(euicc_base64_validate("QR==", 4) < 0);
    assert(euicc_base64_validate("QQ==", 3) < 0);
}

int main(void) {
    test_profile_singletons();
    test_euicc_info_singletons();
    test_profile_field_limits();
    test_profile_count_limit();
    test_euicc_info_field_limits();
    test_store_metadata_limits();
    test_rejects_truncated_trailing_fields();
    test_strict_base64_validation();
    return 0;
}
