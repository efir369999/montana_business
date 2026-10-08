/* mt_business.h — C ABI of Montana Business (crate mt-business, doors in ffi_business.rs).
 *
 * The organisation is a TimeChain of signed records in two chains: R (the roster, held by every
 * member) and H (personnel, held by the administrators and, lane by lane, by each employee).
 * A chain, in a file and in a door: frames back to back, each a u32 LE length then the record.
 * Every output goes into the caller's buffer; on MT_ERR_BUFFER_TOO_SMALL *out_len holds the
 * length needed (ask with cap 0 first; MT_BIZ_FRAME_MAX always holds one authored record).
 * JSON outputs are UTF-8 without a terminating NUL. Every door is deterministic.
 * The Android JNI (quest.montana.app.MtBusiness) calls the same Rust functions.
 */

#ifndef MT_BUSINESS_H
#define MT_BUSINESS_H

#include <stddef.h>
#include <stdint.h>
#include "mt_bindings.h"

#ifdef __cplusplus
extern "C" {
#endif

#define MT_ERR_BIZ_DENIED                -40  /* the fold refuses the record: no right or a rule */
#define MT_ERR_BIZ_ATTEST                -41  /* not an attestation signed by the pinned service key */
#define MT_ERR_BIZ_FRAME                 -42  /* a chain or a frame is malformed */
#define MT_ERR_BIZ_COMMAND               -43  /* the JSON command is malformed or misses a field */

#define MT_BIZ_ID_LEN                     32
#define MT_BIZ_FRAME_MAX               69003

/* The author's next record as a one-frame chain (u32 LE length, record), ready for mt_biz_merge.
 * seckey 4032 B, pubkey 1952 B, command_utf8 NUL-terminated JSON {"kind": ..., fields}.
 * Lane, n and prev come from the chains given; the right from the fold (MT_ERR_BIZ_DENIED). */
int mt_biz_author(const uint8_t *seckey, const uint8_t *pubkey,
                  const uint8_t *roster, size_t roster_len,
                  const uint8_t *hr, size_t hr_len,
                  const char *command_utf8, uint64_t at_ms,
                  uint8_t *out, size_t cap, size_t *out_len);

/* Union of two chains by record, sorted by (at_ms, id); frames are checked here, signatures by
 * the fold. *out_added: how many frames of incoming were new. */
int mt_biz_merge(const uint8_t *have, size_t have_len,
                 const uint8_t *incoming, size_t incoming_len,
                 uint8_t *out, size_t cap, size_t *out_len, size_t *out_added);

/* The view for the screens (JSON). viewer_pubkey 1952 B. */
int mt_biz_view(const uint8_t *roster, size_t roster_len,
                const uint8_t *hr, size_t hr_len,
                const uint8_t *viewer_pubkey, uint64_t now_ms,
                uint8_t *out_json, size_t cap, size_t *out_len);

/* The records of one private lane (32 B): an employee's lane of H, an order's lane of S, a chat's lane of C. */
int mt_biz_slice(const uint8_t *hr, size_t hr_len, const uint8_t *lane,
                 uint8_t *out, size_t cap, size_t *out_len);

/* The phone attestation checked with the pinned service key, as JSON
 * {"e164", "subject", "channel", "at_ms"}; otherwise MT_ERR_BIZ_ATTEST. */
int mt_biz_attest_open(const uint8_t *attest, size_t attest_len,
                       uint8_t *out_json, size_t cap, size_t *out_len);

/* The address confirmed by mail (MTBE) checked with the same pinned service key, as JSON
 * {"email", "subject", "at_ms"}; otherwise MT_ERR_BIZ_ATTEST. */
int mt_biz_email_open(const uint8_t *attest, size_t attest_len,
                      uint8_t *out_json, size_t cap, size_t *out_len);

/* The pinned public key of the phone service, 1952 B. */
int mt_biz_service_key(uint8_t *out);

/* member id = SHA-256("mt-biz-member", 0x00, key at join), 32 B; it never changes on Rekey. */
int mt_biz_member_id(const uint8_t *pubkey, uint8_t *out);

/* invite id = SHA-256("mt-biz-invite", 0x00, secret 32 B), 32 B: the id the view names an invitation by. */
int mt_biz_invite_id(const uint8_t *secret, uint8_t *out);

/* Sync by the heads of lanes (contract 1.1). The heads of a stream: u32 count, then per lane in (chain, lane) order --
 * chain 1, lane 32, the highest place held n u64, SHA-256 of the ids held at or below it 32. */
int mt_biz_heads(const uint8_t *stream, size_t stream_len,
                 uint8_t *out, size_t cap, size_t *out_len);

/* What the holder of `heads` lacks of `stream`: a lane it does not name, whole; of a named lane, every record past its
 * place, and the records at or below it too when the digests differ (a sibling of a fork). */
int mt_biz_after(const uint8_t *stream, size_t stream_len,
                 const uint8_t *heads, size_t heads_len,
                 uint8_t *out, size_t cap, size_t *out_len);

/* A stream kept to one organisation (org = its genesis id, 32 B): nothing of another organisation is merged. */
int mt_biz_keep(const uint8_t *org, const uint8_t *stream, size_t stream_len,
                uint8_t *out, size_t cap, size_t *out_len);

#ifdef __cplusplus
}
#endif

#endif
