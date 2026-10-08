// THE ADDRESS CONFIRMED BY MAIL (the author's word 06.10.2026 17:5x MSK: «a second sign-in by e-mail ... the mechanism of
// sending codes -- apply it as the third way of signing in»): the Business service mails a code to the address, the person
// types it back, and the service signs that this address belongs to this key. A frame of its own (MTBE) under a label of its
// own beside the number's (MTBA, mt-biz-attest): an address is never read as a number, and the signature of one frame never
// verifies as the other's. The same pinned service key signs both. An address is not a member's key in chain R: it opens the
// door of the app, and the organisation's own roads (phone_bind, invite_phone) stay the number's.

use mt_codec::{write_bytes, write_u64, write_u8};
use mt_crypto::{hash, sign, verify, PublicKey, SecretKey, Signature, SIGNATURE_SIZE};

use crate::attest::pinned_key;
use crate::codec::{Reader, Short};
use crate::domain::DOMAIN_EMAIL_ATTEST;
use crate::json::Json;
use crate::BizError;

pub const MAGIC_EMAIL: [u8; 4] = *b"MTBE";
pub const EMAIL_VERSION: u8 = 1;
/// The longest address a mail path carries (RFC 5321, 4.5.3.1.3) and the longest local part (4.5.3.1.1).
pub const EMAIL_MAX: usize = 254;
pub const LOCAL_MAX: usize = 64;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EmailAttest {
    pub at_ms: u64,
    pub subject: [u8; 32],
    pub email: String,
}

/// The one shape an address is kept in: lower-case printable ASCII, one @, a local part of 1..=64 bytes, a domain with a dot
/// that neither begins nor ends it and no empty label. The service lower-cases before it mails, so one box is one address.
pub fn valid_email(s: &str) -> bool {
    let b = s.as_bytes();
    if b.is_empty() || EMAIL_MAX < b.len() {
        return false;
    }
    if !b
        .iter()
        .all(|c| c.is_ascii_graphic() && !c.is_ascii_uppercase())
    {
        return false;
    }
    let mut parts = s.split('@');
    let (Some(local), Some(domain), None) = (parts.next(), parts.next(), parts.next()) else {
        return false;
    };
    !local.is_empty()
        && local.len() <= LOCAL_MAX
        && domain.contains('.')
        && domain.split('.').all(|label| !label.is_empty())
}

impl EmailAttest {
    pub fn unsigned(&self) -> Result<Vec<u8>, BizError> {
        if !valid_email(&self.email) {
            return Err(BizError::Attest("email is not an address"));
        }
        let len = u8::try_from(self.email.len()).map_err(|_| BizError::Attest("email too long"))?;
        let mut b = Vec::with_capacity(4 + 1 + 8 + 32 + 1 + self.email.len() + SIGNATURE_SIZE);
        write_bytes(&mut b, &MAGIC_EMAIL);
        write_u8(&mut b, EMAIL_VERSION);
        write_u64(&mut b, self.at_ms);
        write_bytes(&mut b, &self.subject);
        write_u8(&mut b, len);
        write_bytes(&mut b, self.email.as_bytes());
        Ok(b)
    }

    pub fn seal(&self, sk: &SecretKey) -> Result<Vec<u8>, BizError> {
        let mut b = self.unsigned()?;
        let sig = sign(sk, &digest(&b)).map_err(|_| BizError::Key("ML-DSA signing failed"))?;
        b.extend_from_slice(sig.as_bytes());
        Ok(b)
    }

    pub fn to_json(&self) -> String {
        let mut j = Json::new();
        j.begin_obj();
        j.kv_str("email", &self.email);
        j.kv_hex("subject", &self.subject);
        j.kv_u64("at_ms", self.at_ms);
        j.end_obj();
        j.finish()
    }
}

pub fn digest(unsigned: &[u8]) -> [u8; 32] {
    hash(DOMAIN_EMAIL_ATTEST, &[unsigned])
}

fn parse(bytes: &[u8]) -> Short<(EmailAttest, usize)> {
    let mut r = Reader::new(bytes);
    if r.array::<4>()? != MAGIC_EMAIL {
        return Err("bad magic");
    }
    if r.u8()? != EMAIL_VERSION {
        return Err("unknown version");
    }
    let at_ms = r.u64()?;
    let subject = r.array()?;
    let len = usize::from(r.u8()?);
    let email = std::str::from_utf8(r.take(len)?)
        .map_err(|_| "email is not ASCII")?
        .to_owned();
    if !valid_email(&email) {
        return Err("email is not an address");
    }
    let unsigned_len = 4 + 1 + 8 + 32 + 1 + len;
    r.take(SIGNATURE_SIZE)?;
    if !r.done() {
        return Err("trailing bytes after the signature");
    }
    Ok((
        EmailAttest {
            at_ms,
            subject,
            email,
        },
        unsigned_len,
    ))
}

pub fn open_with(key: &PublicKey, bytes: &[u8]) -> Result<EmailAttest, BizError> {
    let (a, unsigned_len) = parse(bytes).map_err(BizError::Attest)?;
    let unsigned = bytes
        .get(..unsigned_len)
        .ok_or(BizError::Attest("truncated"))?;
    let sig = bytes
        .get(unsigned_len..)
        .and_then(Signature::from_slice)
        .ok_or(BizError::Attest("signature length"))?;
    if !verify(key, &digest(unsigned), &sig) {
        return Err(BizError::Attest(
            "signature does not verify with the service key",
        ));
    }
    Ok(a)
}

pub fn open(bytes: &[u8]) -> Result<EmailAttest, BizError> {
    open_with(pinned_key()?, bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::attest::{self, test_service_key};

    fn sample() -> EmailAttest {
        EmailAttest {
            at_ms: 1_791_234_567_890,
            subject: [7; 32],
            email: "anna@montana.quest".into(),
        }
    }

    #[test]
    fn address_shape() {
        assert!(valid_email("anna@montana.quest"));
        assert!(valid_email("a.b+c@mail.example.org"));
        assert!(!valid_email("Anna@montana.quest"));
        assert!(!valid_email("anna@montana"));
        assert!(!valid_email("anna@@montana.quest"));
        assert!(!valid_email("anna@montana..quest"));
        assert!(!valid_email("anna@.montana.quest"));
        assert!(!valid_email("@montana.quest"));
        assert!(!valid_email("an na@montana.quest"));
        assert!(!valid_email(&("a".repeat(65) + "@montana.quest")));
        assert!(!valid_email(&("a@".to_owned() + &"b.".repeat(126) + "c")));
    }

    #[test]
    fn seal_open_roundtrip_and_every_damage_fails() {
        let (pk, sk) = test_service_key().expect("keygen");
        let bytes = sample().seal(&sk).expect("seal");
        assert_eq!(open_with(&pk, &bytes), Ok(sample()));
        for at in [0usize, 4, 5, 13, 40, 46, bytes.len() - 1] {
            let mut bad = bytes.clone();
            bad[at] ^= 0x01;
            assert!(open_with(&pk, &bad).is_err(), "flip at {at}");
        }
        assert!(open_with(&pk, &bytes[..bytes.len() - 1]).is_err());
    }

    #[test]
    fn an_address_never_opens_as_a_number_nor_a_number_as_an_address() {
        // Rejects an implementation that shared the number's label or frame: the same service key signs both kinds.
        let (pk, sk) = test_service_key().expect("keygen");
        let mail = sample().seal(&sk).expect("seal");
        assert!(attest::open_with(&pk, &mail).is_err());
        let unsigned = sample().unsigned().expect("encodes");
        let cross = sign(&sk, &attest::digest(&unsigned)).expect("signs");
        let mut forged = unsigned.clone();
        forged.extend_from_slice(cross.as_bytes());
        assert!(open_with(&pk, &forged).is_err());
        let number = attest::Attest {
            channel: attest::CHANNEL_BOT,
            at_ms: 1,
            subject: [7; 32],
            e164: "+79161234567".into(),
        }
        .seal(&sk)
        .expect("seal");
        assert!(open_with(&pk, &number).is_err());
    }

    #[test]
    fn json_shape() {
        assert_eq!(
            sample().to_json(),
            format!(
                "{{\"email\":\"anna@montana.quest\",\"subject\":\"{}\",\"at_ms\":1791234567890}}",
                "07".repeat(32)
            )
        );
    }
}
