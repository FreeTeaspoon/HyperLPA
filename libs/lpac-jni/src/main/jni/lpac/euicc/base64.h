#pragma once

#include <stdint.h>

int euicc_base64_validate(const char *encoded, uint32_t max_encoded_length);
int euicc_base64_decode_len(const char *bufcoded);
int euicc_base64_decode(unsigned char *bufplain, const char *bufcoded);
int euicc_base64_encode_len(int len);
int euicc_base64_encode(char *encoded, const unsigned char *string, int len);
