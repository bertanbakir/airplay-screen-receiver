/* Stub for xplist (XML plist) — AirPlay only uses binary plists.
   Avoids pulling in libxml2. */
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>

typedef void*    plist_t;
typedef uint32_t plist_err_t;
typedef uint32_t plist_write_options_t;

void plist_xml_init(void)           {}
void plist_xml_deinit(void)         {}
void plist_xml_set_debug(int dbg)   { (void)dbg; }

void plist_to_xml(plist_t plist, char **out, uint32_t *length) {
    (void)plist;
    if (out)    *out    = NULL;
    if (length) *length = 0;
}

void plist_from_xml(const char *xml, uint32_t length, plist_t *plist) {
    (void)xml; (void)length;
    if (plist) *plist = NULL;
}
