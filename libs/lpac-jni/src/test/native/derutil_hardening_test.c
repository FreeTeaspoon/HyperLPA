#include "derutil.h"

#include <assert.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

static void test_checked_integer_conversion(void) {
    long result = -1;
    const uint8_t zero[] = {0x00};
    const uint8_t value[] = {0x01, 0x02, 0x03};
    uint8_t overflow[sizeof(long)];
    uint8_t oversized[sizeof(unsigned long) + 1U];

    assert(euicc_derutil_convert_bin2long(&result, zero, sizeof(zero)) == 0);
    assert(result == 0);
    assert(euicc_derutil_convert_bin2long(&result, value, sizeof(value)) == 0);
    assert(result == 0x010203L);

    memset(overflow, 0, sizeof(overflow));
    overflow[0] = 0x80;
    assert(euicc_derutil_convert_bin2long(&result, overflow, sizeof(overflow)) < 0);
    memset(oversized, 0, sizeof(oversized));
    assert(euicc_derutil_convert_bin2long(&result, oversized, sizeof(oversized)) < 0);
    assert(euicc_derutil_convert_bin2long(&result, zero, 0) < 0);
    assert(euicc_derutil_convert_bin2long(NULL, zero, sizeof(zero)) < 0);
    assert(euicc_derutil_convert_bin2long(&result, NULL, sizeof(zero)) < 0);
}

static void test_bit_string_validation(void) {
    static const char *descriptions[] = {"bit0", "bit1", "bit2", "bit3", NULL};
    const char **output = NULL;
    const uint8_t valid[] = {3, 0xA0};
    const uint8_t invalid_unused_count[] = {8, 0x00};
    const uint8_t invalid_large_unused_count[] = {0xFF, 0x00};
    const uint8_t invalid_empty[] = {1};
    const uint8_t invalid_padding[] = {3, 0xA1};

    assert(euicc_derutil_convert_bin2bits_str(
               &output, valid, (int)sizeof(valid), descriptions) == 0);
    assert(output != NULL);
    assert(strcmp(output[0], "bit0") == 0);
    assert(strcmp(output[1], "bit2") == 0);
    assert(output[2] == NULL);
    free(output);
    output = NULL;

    assert(euicc_derutil_convert_bin2bits_str(
               &output, invalid_unused_count, (int)sizeof(invalid_unused_count), descriptions) < 0);
    assert(output == NULL);
    assert(euicc_derutil_convert_bin2bits_str(
               &output, invalid_large_unused_count, (int)sizeof(invalid_large_unused_count), descriptions) < 0);
    assert(output == NULL);
    assert(euicc_derutil_convert_bin2bits_str(
               &output, invalid_empty, (int)sizeof(invalid_empty), descriptions) < 0);
    assert(output == NULL);
    assert(euicc_derutil_convert_bin2bits_str(
               &output, invalid_padding, (int)sizeof(invalid_padding), descriptions) < 0);
    assert(output == NULL);
    assert(euicc_derutil_validate_bit_string(valid, sizeof(valid)) == 0);
    assert(euicc_derutil_validate_bit_string(invalid_padding, sizeof(invalid_padding)) < 0);
}

static void test_der_header_validation(void) {
    struct euicc_derutil_node node;
    uint8_t valid_long[131] = {0x04, 0x81, 0x80};
    const uint8_t valid_short[] = {0x04, 0x01, 0xAA};
    const uint8_t indefinite[] = {0x04, 0x80};
    const uint8_t oversized_length[] = {0x04, 0x85, 0, 0, 0, 0, 0};
    const uint8_t leading_zero_length[] = {0x04, 0x82, 0x00, 0x80};
    const uint8_t non_minimal_length[] = {0x04, 0x81, 0x7F};
    const uint8_t unsupported_long_tag[] = {0x1F, 0x81, 0x01, 0x00};

    assert(euicc_derutil_unpack_first(&node, valid_short, sizeof(valid_short)) == 0);
    assert(node.tag == 0x04 && node.length == 1 && node.value[0] == 0xAA);
    assert(euicc_derutil_unpack_next(&node, &node, valid_short, sizeof(valid_short)) == 1);
    assert(euicc_derutil_unpack_first(&node, valid_long, sizeof(valid_long)) == 0);
    assert(node.length == 128);

    assert(euicc_derutil_unpack_first(&node, indefinite, sizeof(indefinite)) < 0);
    assert(euicc_derutil_unpack_first(&node, oversized_length, sizeof(oversized_length)) < 0);
    assert(euicc_derutil_unpack_first(&node, leading_zero_length, sizeof(leading_zero_length)) < 0);
    assert(euicc_derutil_unpack_first(&node, non_minimal_length, sizeof(non_minimal_length)) < 0);
    assert(euicc_derutil_unpack_first(&node, unsupported_long_tag, sizeof(unsupported_long_tag)) < 0);
    assert(euicc_derutil_unpack_first(NULL, valid_short, sizeof(valid_short)) < 0);
    assert(euicc_derutil_unpack_first(&node, NULL, sizeof(valid_short)) < 0);
}

static void test_utf8_validation(void) {
    const uint8_t valid[] = {'A', 0xC3, 0xA9, 0xF0, 0x9F, 0x98, 0x80};
    const uint8_t embedded_nul[] = {'A', 0x00, 'B'};
    const uint8_t overlong[] = {0xC0, 0x80};
    const uint8_t surrogate[] = {0xED, 0xA0, 0x80};
    const uint8_t too_large[] = {0xF4, 0x90, 0x80, 0x80};
    const uint8_t truncated[] = {0xE2, 0x82};

    assert(euicc_derutil_validate_utf8(valid, sizeof(valid), 3) == 0);
    assert(euicc_derutil_validate_utf8(valid, sizeof(valid), 2) < 0);
    assert(euicc_derutil_validate_utf8(NULL, 0, 0) == 0);
    assert(euicc_derutil_validate_utf8(NULL, 1, 1) < 0);
    assert(euicc_derutil_validate_utf8(embedded_nul, sizeof(embedded_nul), 3) < 0);
    assert(euicc_derutil_validate_utf8(overlong, sizeof(overlong), 1) < 0);
    assert(euicc_derutil_validate_utf8(surrogate, sizeof(surrogate), 1) < 0);
    assert(euicc_derutil_validate_utf8(too_large, sizeof(too_large), 1) < 0);
    assert(euicc_derutil_validate_utf8(truncated, sizeof(truncated), 1) < 0);
}

int main(void) {
    test_checked_integer_conversion();
    test_bit_string_validation();
    test_der_header_validation();
    test_utf8_validation();
    return 0;
}
