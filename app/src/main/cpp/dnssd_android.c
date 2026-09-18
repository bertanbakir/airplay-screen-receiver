/*
 * dnssd_android.c — stub replacement for dnssd.c on Android.
 * mDNS registration is handled on the Kotlin side via jmDNS.
 * This file satisfies the dnssd.h API without depending on dns_sd.h.
 */

#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>
#include <assert.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "global.h"
#include "utils.h"

#define MAX_TXT_SIZE 1024

struct dnssd_s {
    char *name;
    int   name_len;
    char *hw_addr;
    int   hw_addr_len;
    char *pk;

    uint32_t features1;
    uint32_t features2;
    unsigned char pin_pw;

    char raop_txt[MAX_TXT_SIZE];
    int  raop_txt_len;
    char airplay_txt[MAX_TXT_SIZE];
    int  airplay_txt_len;
};

/* DNS TXT record: each entry = length_byte + "key=value" */
static int txt_append(char *buf, int *len, int max, const char *key, const char *value) {
    int klen = (int)strlen(key);
    int vlen = (int)strlen(value);
    int entry = klen + 1 + vlen; /* key=value */
    if (*len + 1 + entry > max) return -1;
    buf[(*len)++] = (char)entry;
    memcpy(buf + *len, key, klen);  *len += klen;
    buf[(*len)++] = '=';
    memcpy(buf + *len, value, vlen); *len += vlen;
    return 0;
}

dnssd_t *dnssd_init(const char *name, int name_len, const char *hw_addr,
                    int hw_addr_len, int *error, unsigned char pin_pw) {
    if (error) *error = DNSSD_ERROR_NOERROR;

    dnssd_t *d = (dnssd_t *)calloc(1, sizeof(dnssd_t));
    if (!d) { if (error) *error = DNSSD_ERROR_OUTOFMEM; return NULL; }

    d->pin_pw = pin_pw;

    char *end = NULL;
    unsigned long f = strtoul(FEATURES_1, &end, 16);
    if (!end || (f & 0xFFFFFFFF) != f) {
        free(d); if (error) *error = DNSSD_ERROR_BADFEATURES; return NULL;
    }
    d->features1 = (uint32_t)f;

    f = strtoul(FEATURES_2, &end, 16);
    if (!end || (f & 0xFFFFFFFF) != f) {
        free(d); if (error) *error = DNSSD_ERROR_BADFEATURES; return NULL;
    }
    d->features2 = (uint32_t)f;

    d->name = (char *)calloc(1, name_len + 1);
    if (!d->name) { free(d); if (error) *error = DNSSD_ERROR_OUTOFMEM; return NULL; }
    memcpy(d->name, name, name_len);
    d->name_len = name_len;

    d->hw_addr = (char *)calloc(1, hw_addr_len);
    if (!d->hw_addr) {
        free(d->name); free(d);
        if (error) *error = DNSSD_ERROR_OUTOFMEM; return NULL;
    }
    memcpy(d->hw_addr, hw_addr, hw_addr_len);
    d->hw_addr_len = hw_addr_len;

    return d;
}

int dnssd_register_raop(dnssd_t *dnssd, unsigned short port) {
    assert(dnssd);
    char features[22] = {0};
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd->features1, dnssd->features2);

    char *b  = dnssd->raop_txt;
    int  *ln = &dnssd->raop_txt_len;
    int   mx = MAX_TXT_SIZE;
    *ln = 0;

    txt_append(b, ln, mx, "txtvers", RAOP_TXTVERS);
    txt_append(b, ln, mx, "ch",      RAOP_CH);
    txt_append(b, ln, mx, "cn",      RAOP_CN);
    txt_append(b, ln, mx, "da",      RAOP_DA);
    txt_append(b, ln, mx, "et",      RAOP_ET);
    txt_append(b, ln, mx, "vv",      RAOP_VV);
    txt_append(b, ln, mx, "ft",      features);
    txt_append(b, ln, mx, "am",      GLOBAL_MODEL);
    txt_append(b, ln, mx, "md",      RAOP_MD);
    txt_append(b, ln, mx, "rhd",     RAOP_RHD);
    txt_append(b, ln, mx, "pw",      (dnssd->pin_pw > 0) ? "true" : "false");
    txt_append(b, ln, mx, "sf",      RAOP_SF);
    txt_append(b, ln, mx, "sr",      RAOP_SR);
    txt_append(b, ln, mx, "ss",      RAOP_SS);
    txt_append(b, ln, mx, "sv",      RAOP_SV);
    txt_append(b, ln, mx, "tp",      RAOP_TP);
    txt_append(b, ln, mx, "vs",      RAOP_VS);
    txt_append(b, ln, mx, "vn",      RAOP_VN);
    if (dnssd->pk) txt_append(b, ln, mx, "pk", dnssd->pk);

    return 0; /* actual mDNS registration handled by Kotlin/jmDNS */
}

int dnssd_register_airplay(dnssd_t *dnssd, unsigned short port) {
    assert(dnssd);
    char device_id[3 * MAX_HWADDR_LEN];
    if (utils_hwaddr_airplay(device_id, sizeof(device_id), dnssd->hw_addr, dnssd->hw_addr_len) < 0)
        return -1;

    char features[22] = {0};
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd->features1, dnssd->features2);

    char *b  = dnssd->airplay_txt;
    int  *ln = &dnssd->airplay_txt_len;
    int   mx = MAX_TXT_SIZE;
    *ln = 0;

    txt_append(b, ln, mx, "deviceid", device_id);
    txt_append(b, ln, mx, "features", features);
    txt_append(b, ln, mx, "flags",    AIRPLAY_FLAGS);
    txt_append(b, ln, mx, "model",    GLOBAL_MODEL);
    txt_append(b, ln, mx, "pi",       AIRPLAY_PI);
    txt_append(b, ln, mx, "srcvers",  AIRPLAY_SRCVERS);
    txt_append(b, ln, mx, "vv",       AIRPLAY_VV);
    txt_append(b, ln, mx, "pw",       (dnssd->pin_pw > 0) ? "true" : "false");
    if (dnssd->pk) txt_append(b, ln, mx, "pk", dnssd->pk);

    return 0;
}

void dnssd_unregister_raop(dnssd_t *dnssd)    { (void)dnssd; }
void dnssd_unregister_airplay(dnssd_t *dnssd) { (void)dnssd; }

const char *dnssd_get_raop_txt(dnssd_t *dnssd, int *length) {
    *length = dnssd->raop_txt_len;
    return dnssd->raop_txt;
}

const char *dnssd_get_airplay_txt(dnssd_t *dnssd, int *length) {
    *length = dnssd->airplay_txt_len;
    return dnssd->airplay_txt;
}

const char *dnssd_get_name(dnssd_t *dnssd, int *length) {
    *length = dnssd->name_len;
    return dnssd->name;
}

const char *dnssd_get_hw_addr(dnssd_t *dnssd, int *length) {
    *length = dnssd->hw_addr_len;
    return dnssd->hw_addr;
}

uint64_t dnssd_get_airplay_features(dnssd_t *dnssd) {
    return (((uint64_t)dnssd->features2) << 32) | (uint64_t)dnssd->features1;
}

void dnssd_set_pk(dnssd_t *dnssd, char *pk_str) {
    dnssd->pk = pk_str;
}

void dnssd_set_airplay_features(dnssd_t *dnssd, int bit, int val) {
    if (bit < 0 || bit > 63 || val < 0 || val > 1) return;
    uint32_t mask;
    uint32_t *feat;
    if (bit >= 32) {
        mask = 0x1u << (bit - 32);
        feat = &dnssd->features2;
    } else {
        mask = 0x1u << bit;
        feat = &dnssd->features1;
    }
    if (val) *feat |= mask; else *feat &= ~mask;
}

void dnssd_destroy(dnssd_t *dnssd) {
    if (dnssd) {
        free(dnssd->name);
        free(dnssd->hw_addr);
        free(dnssd);
    }
}
