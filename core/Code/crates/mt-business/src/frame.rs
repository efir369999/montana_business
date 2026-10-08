use mt_codec::{write_bytes, write_u32, write_u64, write_u8};
use mt_crypto::{hash, sign, verify, PublicKey, SecretKey, Signature, HASH_SIZE, SIGNATURE_SIZE};

use crate::codec::{Reader, Short};
use std::collections::BTreeSet;
use std::sync::{Mutex, OnceLock};

use crate::domain::{DOMAIN_RECORD, DOMAIN_VERIFIED};
use crate::BizError;

pub const MAGIC_RECORD: [u8; 4] = *b"MTB1";
pub const CHAIN_R: u8 = 0x52;
pub const CHAIN_H: u8 = 0x48;
pub const CHAIN_S: u8 = 0x53;
pub const CHAIN_C: u8 = 0x43;
// magic 4, chain 1, kind 1, org 32, lane 32, n 8, prev 32, at_ms 8, author 32, blen 4
pub const HEADER_LEN: usize = 154;
pub const BODY_MAX: usize = 65536;
pub const FRAME_MAX: usize = HEADER_LEN + BODY_MAX + SIGNATURE_SIZE;

pub type Id = [u8; HASH_SIZE];
pub const ZERO32: [u8; 32] = [0u8; 32];

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Header {
    pub chain: u8,
    pub kind: u8,
    pub org: [u8; 32],
    pub lane: [u8; 32],
    pub n: u64,
    pub prev: [u8; 32],
    pub at_ms: u64,
    pub author: [u8; 32],
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Record {
    pub header: Header,
    pub body: Vec<u8>,
    pub id: Id,
    pub bytes: Vec<u8>,
}

impl Record {
    pub fn signature(&self) -> Option<Signature> {
        let at = self.bytes.len().checked_sub(SIGNATURE_SIZE)?;
        Signature::from_slice(self.bytes.get(at..)?)
    }

    pub fn order_key(&self) -> (u64, Id, &[u8]) {
        (self.header.at_ms, self.id, &self.bytes)
    }
}

pub fn unsigned_bytes(h: &Header, body: &[u8]) -> Result<Vec<u8>, BizError> {
    if body.len() > BODY_MAX {
        return Err(BizError::Frame("body longer than 65536"));
    }
    let blen = u32::try_from(body.len()).map_err(|_| BizError::Frame("body length"))?;
    let mut buf = Vec::with_capacity(HEADER_LEN + body.len() + SIGNATURE_SIZE);
    write_bytes(&mut buf, &MAGIC_RECORD);
    write_u8(&mut buf, h.chain);
    write_u8(&mut buf, h.kind);
    write_bytes(&mut buf, &h.org);
    write_bytes(&mut buf, &h.lane);
    write_u64(&mut buf, h.n);
    write_bytes(&mut buf, &h.prev);
    write_u64(&mut buf, h.at_ms);
    write_bytes(&mut buf, &h.author);
    write_u32(&mut buf, blen);
    write_bytes(&mut buf, body);
    Ok(buf)
}

pub fn record_id(unsigned: &[u8]) -> Id {
    hash(DOMAIN_RECORD, &[unsigned])
}

pub fn seal(h: &Header, body: &[u8], sk: &SecretKey) -> Result<Record, BizError> {
    let mut bytes = unsigned_bytes(h, body)?;
    let id = record_id(&bytes);
    let sig = sign(sk, &id).map_err(|_| BizError::Key("ML-DSA signing failed"))?;
    bytes.extend_from_slice(sig.as_bytes());
    Ok(Record {
        header: h.clone(),
        body: body.to_vec(),
        id,
        bytes,
    })
}

// A SIGNATURE PROVEN ONCE IS NOT PROVEN AGAIN: a phone folds its chains at every change, and every fold checked every
// record's ML-DSA signature anew. A pair that verified is remembered by SHA-256 over the key and the record's whole bytes,
// signature included -- a twin with other signature bytes is another pair -- at most VERIFIED_MAX of them, the memo cleared
// whole when full. It never changes an answer: only a pair that verified enters it.
const VERIFIED_MAX: usize = 1 << 16;

fn verified() -> &'static Mutex<BTreeSet<Id>> {
    static MEMO: OnceLock<Mutex<BTreeSet<Id>>> = OnceLock::new();
    MEMO.get_or_init(|| Mutex::new(BTreeSet::new()))
}

// For a measure only: the memo emptied, so the next fold proves every signature as a cold phone does.
#[doc(hidden)]
pub fn forget_verified() {
    if let Ok(mut m) = verified().lock() {
        m.clear();
    }
}

pub fn verify_record(rec: &Record, key: &PublicKey) -> bool {
    let parts: [&[u8]; 2] = [key.as_bytes(), &rec.bytes];
    let tag = hash(DOMAIN_VERIFIED, &parts);
    if verified().lock().is_ok_and(|m| m.contains(&tag)) {
        return true;
    }
    let ok = match rec.signature() {
        Some(sig) => verify(key, &rec.id, &sig),
        None => false,
    };
    if ok {
        // A poisoned memo is only a memo lost: the answer stands without it.
        if let Ok(mut m) = verified().lock() {
            if m.len() >= VERIFIED_MAX {
                m.clear();
            }
            m.insert(tag);
        }
    }
    ok
}

fn read_header(r: &mut Reader<'_>) -> Short<(Header, usize)> {
    if r.array::<4>()? != MAGIC_RECORD {
        return Err("bad magic");
    }
    let chain = r.u8()?;
    if ![CHAIN_R, CHAIN_H, CHAIN_S, CHAIN_C].contains(&chain) {
        return Err("bad chain");
    }
    let header = Header {
        chain,
        kind: r.u8()?,
        org: r.array()?,
        lane: r.array()?,
        n: r.u64()?,
        prev: r.array()?,
        at_ms: r.u64()?,
        author: r.array()?,
    };
    let blen = usize::try_from(r.u32()?).map_err(|_| "body length")?;
    if blen > BODY_MAX {
        return Err("body longer than 65536");
    }
    Ok((header, blen))
}

pub fn decode(bytes: &[u8]) -> Result<Record, BizError> {
    let mut r = Reader::new(bytes);
    let (header, blen) = read_header(&mut r).map_err(BizError::Frame)?;
    let body = r.take(blen).map_err(BizError::Frame)?.to_vec();
    r.take(SIGNATURE_SIZE).map_err(BizError::Frame)?;
    if !r.done() {
        return Err(BizError::Frame("trailing bytes after the signature"));
    }
    let unsigned = bytes
        .get(..HEADER_LEN + blen)
        .ok_or(BizError::Frame("truncated"))?;
    Ok(Record {
        header,
        body,
        id: record_id(unsigned),
        bytes: bytes.to_vec(),
    })
}

// A chain in a file and in a door: frames back to back, each one u32 LE length then the record.
pub fn split_chain(stream: &[u8]) -> Result<Vec<Record>, BizError> {
    let mut r = Reader::new(stream);
    let mut out = Vec::new();
    while !r.done() {
        let len = usize::try_from(r.u32().map_err(BizError::Frame)?)
            .map_err(|_| BizError::Frame("frame length"))?;
        if len > FRAME_MAX {
            return Err(BizError::Frame("frame longer than the largest record"));
        }
        out.push(decode(r.take(len).map_err(BizError::Frame)?)?);
    }
    Ok(out)
}

pub fn join_chain<'a, I>(records: I) -> Result<Vec<u8>, BizError>
where
    I: IntoIterator<Item = &'a Record>,
{
    let mut out = Vec::new();
    for rec in records {
        let len = u32::try_from(rec.bytes.len()).map_err(|_| BizError::Frame("frame length"))?;
        write_u32(&mut out, len);
        write_bytes(&mut out, &rec.bytes);
    }
    Ok(out)
}

// Sorted by (at_ms, id); twins with one id and different signatures are both kept, ordered by
// their bytes, because the id does not cover the signature and a forged twin must not be able
// to push the signed record out.
pub fn sorted_unique(mut records: Vec<Record>) -> Vec<Record> {
    records.sort_by(|a, b| a.order_key().cmp(&b.order_key()));
    records.dedup_by(|a, b| a.bytes == b.bytes);
    records
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header() -> Header {
        Header {
            chain: CHAIN_R,
            kind: 0x07,
            org: [1; 32],
            lane: [2; 32],
            n: 3,
            prev: [4; 32],
            at_ms: 5,
            author: [6; 32],
        }
    }

    #[test]
    fn header_len_matches_the_layout() {
        let u = unsigned_bytes(&header(), &[]).expect("encodes");
        assert_eq!(u.len(), HEADER_LEN);
    }

    #[test]
    fn decode_rejects_damage() {
        let (_, sk) = mt_crypto::keypair_from_seed(&[9; 32]).expect("keygen");
        let rec = seal(&header(), b"body", &sk).expect("seal");
        assert_eq!(decode(&rec.bytes).expect("decodes"), rec);
        let mut bad = rec.bytes.clone();
        bad[0] = b'X';
        assert!(decode(&bad).is_err());
        let mut bad = rec.bytes.clone();
        bad[4] = 0x41;
        assert!(decode(&bad).is_err());
        let mut long = rec.bytes.clone();
        long.push(0);
        assert!(decode(&long).is_err());
        assert!(decode(&rec.bytes[..rec.bytes.len() - 1]).is_err());
    }

    #[test]
    fn a_remembered_signature_answers_as_the_signature_does() {
        let (pk, sk) = mt_crypto::keypair_from_seed(&[9; 32]).expect("keygen");
        let (other, _) = mt_crypto::keypair_from_seed(&[8; 32]).expect("keygen");
        let rec = seal(&header(), b"memo", &sk).expect("seal");
        assert!(verify_record(&rec, &pk));
        assert!(verify_record(&rec, &pk));
        assert!(!verify_record(&rec, &other));
        // A twin with the same id and other signature bytes is another pair: it is checked, not remembered.
        let mut forged = rec.clone();
        let last = forged.bytes.len() - 1;
        forged.bytes[last] ^= 1;
        assert!(!verify_record(&forged, &pk));
    }

    #[test]
    fn chain_roundtrip_and_truncation() {
        let (_, sk) = mt_crypto::keypair_from_seed(&[9; 32]).expect("keygen");
        let a = seal(&header(), b"a", &sk).expect("seal");
        let b = seal(&header(), b"b", &sk).expect("seal");
        let stream = join_chain([&a, &b]).expect("joins");
        assert_eq!(split_chain(&stream).expect("splits"), vec![a, b]);
        assert!(split_chain(&stream[..stream.len() - 1]).is_err());
        assert_eq!(split_chain(&[]).expect("empty"), Vec::new());
    }
}
