use std::sync::OnceLock;

use mt_codec::{write_bytes, write_u64, write_u8};
use mt_crypto::{
    hash, keypair_from_seed, sign, verify, PublicKey, SecretKey, Signature, KEYPAIR_SEED_SIZE,
    PUBLIC_KEY_SIZE, SIGNATURE_SIZE,
};

use crate::codec::{hex_array, Reader, Short};
use crate::domain::DOMAIN_ATTEST;
use crate::json::Json;
use crate::BizError;

pub const MAGIC_ATTEST: [u8; 4] = *b"MTBA";
pub const ATTEST_VERSION: u8 = 1;
pub const CHANNEL_BOT: u8 = 1;
pub const CHANNEL_MESSAGE: u8 = 2;
pub const E164_MIN_DIGITS: usize = 6;
pub const E164_MAX_DIGITS: usize = 15;

// NOT A PRODUCTION KEY. service_key.hex holds the public key of the seed below, so the whole
// path can be exercised before the service exists. The production key is generated on the
// server by `mt-business-attest keygen` at deployment, and its printed hex replaces this one file;
// nothing else in the code changes.
pub const TEST_SERVICE_SEED: [u8; KEYPAIR_SEED_SIZE] = *b"MT-BIZ-TEST-SERVICE-KEY-NOT-PROD";
const SERVICE_KEY_HEX: &str = include_str!("../service_key.hex");

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Attest {
    pub channel: u8,
    pub at_ms: u64,
    pub subject: [u8; 32],
    pub e164: String,
}

pub fn valid_e164(s: &str) -> bool {
    match s.as_bytes().split_first() {
        Some((b'+', digits)) => {
            (E164_MIN_DIGITS..=E164_MAX_DIGITS).contains(&digits.len())
                && digits.iter().all(u8::is_ascii_digit)
                && digits.first() != Some(&b'0')
        },
        _ => false,
    }
}

impl Attest {
    pub fn unsigned(&self) -> Result<Vec<u8>, BizError> {
        if !valid_e164(&self.e164) {
            return Err(BizError::Attest("e164 is not +digits"));
        }
        if self.channel != CHANNEL_BOT && self.channel != CHANNEL_MESSAGE {
            return Err(BizError::Attest("channel is not 1 or 2"));
        }
        let len = u8::try_from(self.e164.len()).map_err(|_| BizError::Attest("e164 too long"))?;
        let mut b = Vec::with_capacity(4 + 1 + 1 + 8 + 32 + 1 + self.e164.len() + SIGNATURE_SIZE);
        write_bytes(&mut b, &MAGIC_ATTEST);
        write_u8(&mut b, ATTEST_VERSION);
        write_u8(&mut b, self.channel);
        write_u64(&mut b, self.at_ms);
        write_bytes(&mut b, &self.subject);
        write_u8(&mut b, len);
        write_bytes(&mut b, self.e164.as_bytes());
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
        j.kv_str("e164", &self.e164);
        j.kv_hex("subject", &self.subject);
        j.kv_u64("channel", u64::from(self.channel));
        j.kv_u64("at_ms", self.at_ms);
        j.end_obj();
        j.finish()
    }
}

pub fn digest(unsigned: &[u8]) -> [u8; 32] {
    hash(DOMAIN_ATTEST, &[unsigned])
}

fn parse(bytes: &[u8]) -> Short<(Attest, usize)> {
    let mut r = Reader::new(bytes);
    if r.array::<4>()? != MAGIC_ATTEST {
        return Err("bad magic");
    }
    if r.u8()? != ATTEST_VERSION {
        return Err("unknown version");
    }
    let channel = r.u8()?;
    if channel != CHANNEL_BOT && channel != CHANNEL_MESSAGE {
        return Err("channel is not 1 or 2");
    }
    let at_ms = r.u64()?;
    let subject = r.array()?;
    let len = usize::from(r.u8()?);
    let e164 = std::str::from_utf8(r.take(len)?)
        .map_err(|_| "e164 is not ASCII")?
        .to_owned();
    if !valid_e164(&e164) {
        return Err("e164 is not +digits");
    }
    let unsigned_len = 4 + 1 + 1 + 8 + 32 + 1 + len;
    r.take(SIGNATURE_SIZE)?;
    if !r.done() {
        return Err("trailing bytes after the signature");
    }
    Ok((
        Attest {
            channel,
            at_ms,
            subject,
            e164,
        },
        unsigned_len,
    ))
}

pub fn open_with(key: &PublicKey, bytes: &[u8]) -> Result<Attest, BizError> {
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

static PINNED: OnceLock<Option<PublicKey>> = OnceLock::new();

pub fn pinned_key() -> Result<&'static PublicKey, BizError> {
    PINNED
        .get_or_init(|| {
            hex_array::<PUBLIC_KEY_SIZE>(SERVICE_KEY_HEX.trim()).map(PublicKey::from_array)
        })
        .as_ref()
        .ok_or(BizError::Attest("service_key.hex is not a 1952-byte key"))
}

pub fn open(bytes: &[u8]) -> Result<Attest, BizError> {
    open_with(pinned_key()?, bytes)
}

pub fn test_service_key() -> Result<(PublicKey, SecretKey), BizError> {
    keypair_from_seed(&TEST_SERVICE_SEED).map_err(|_| BizError::Key("ML-DSA keygen failed"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Attest {
        Attest {
            channel: CHANNEL_BOT,
            at_ms: 1_791_234_567_890,
            subject: [7; 32],
            e164: "+79161234567".into(),
        }
    }

    #[test]
    fn e164_shape() {
        assert!(valid_e164("+79161234567"));
        assert!(valid_e164("+123456"));
        assert!(!valid_e164("+12345"));
        assert!(!valid_e164("+1234567890123456"));
        assert!(!valid_e164("79161234567"));
        assert!(!valid_e164("+0123456"));
        assert!(!valid_e164("+7916 123"));
    }

    #[test]
    fn seal_open_roundtrip_and_every_damage_fails() {
        let (pk, sk) = test_service_key().expect("keygen");
        let bytes = sample().seal(&sk).expect("seal");
        assert_eq!(open_with(&pk, &bytes), Ok(sample()));
        let (other, _) = keypair_from_seed(&[1; 32]).expect("keygen");
        assert!(open_with(&other, &bytes).is_err());
        for at in [0usize, 4, 5, 6, 20, 50, bytes.len() - 1] {
            let mut bad = bytes.clone();
            bad[at] ^= 0x01;
            assert!(open_with(&pk, &bad).is_err(), "flip at {at}");
        }
        assert!(open_with(&pk, &bytes[..bytes.len() - 1]).is_err());
    }

    #[test]
    fn pinned_key_parses_and_is_a_key() {
        let k = pinned_key().expect("service_key.hex parses");
        assert_eq!(k.as_bytes().len(), PUBLIC_KEY_SIZE);
    }

    #[test]
    #[ignore = "prints the public key of TEST_SERVICE_SEED: this is how service_key.hex was written"]
    fn print_test_service_key() {
        let (pk, _) = test_service_key().expect("keygen");
        println!("{}", crate::codec::to_hex(pk.as_bytes()));
    }

    #[test]
    fn json_shape() {
        assert_eq!(
            sample().to_json(),
            format!(
                "{{\"e164\":\"+79161234567\",\"subject\":\"{}\",\"channel\":1,\"at_ms\":1791234567890}}",
                "07".repeat(32)
            )
        );
    }
}
