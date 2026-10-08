// The business layer lives outside consensus, so its labels stay with the crate (as
// mt-messenger-e2e keeps its own) instead of entering mt_codec::domain. Every hash below is
// mt_crypto::hash(label, parts) = SHA-256(label ‖ 0x00 ‖ parts): the 0x00 keeps one label from
// being a prefix of another.

pub const DOMAIN_RECORD: &[u8] = b"mt-biz-record";
pub const DOMAIN_MEMBER: &[u8] = b"mt-biz-member";
pub const DOMAIN_INVITE: &[u8] = b"mt-biz-invite";
pub const DOMAIN_PHONE: &[u8] = b"mt-biz-phone";
pub const DOMAIN_ATTEST: &[u8] = b"mt-biz-attest";
// The address confirmed by mail (MTBE): a label of its own, so a signature over one frame never verifies as the other.
pub const DOMAIN_EMAIL_ATTEST: &[u8] = b"mt-biz-email-attest";
// A letter of an organisation chat, as chain C names it: SHA-256(label, mid, 0x00, text).
pub const DOMAIN_LETTER: &[u8] = b"mt-biz-letter";
// The lane of one member's shifts in chain H: apart from the lane of their pay, so a manager may hold it.
pub const DOMAIN_SHIFT: &[u8] = b"mt-biz-shift";
// The tag of a (key, record) pair whose signature verified: a memo of this process, never on the wire.
pub const DOMAIN_VERIFIED: &[u8] = b"mt-biz-verified";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn labels_are_distinct_and_none_is_a_prefix_of_another() {
        let all = [
            DOMAIN_RECORD,
            DOMAIN_MEMBER,
            DOMAIN_INVITE,
            DOMAIN_PHONE,
            DOMAIN_ATTEST,
            DOMAIN_EMAIL_ATTEST,
            DOMAIN_LETTER,
            DOMAIN_SHIFT,
            DOMAIN_VERIFIED,
        ];
        for (i, a) in all.iter().enumerate() {
            for (j, b) in all.iter().enumerate() {
                if i != j {
                    assert!(!b.starts_with(a), "{i} is a prefix of {j}");
                }
            }
        }
    }
}
