//! Sync by the heads of lanes. A phone names, for every lane it holds, the highest place it holds there and a digest of the
//! records it holds at or below it; the other phone answers with what the first lacks -- the records past that place, and
//! the whole lane at or below it when the digests differ (a sibling of a fork it does not hold). A word then carries what
//! is new, not everything. And a stream is kept to one organisation before it is merged, so no pipe can fill a phone with
//! another organisation's records.

use std::collections::BTreeMap;

use mt_codec::{write_bytes, write_u32, write_u64, write_u8};
use mt_crypto::sha256_raw;

use crate::codec::Reader;
use crate::frame::{join_chain, sorted_unique, split_chain, Record};
use crate::BizError;

// chain 1, lane 32, n 8, digest 32.
pub const HEAD_LEN: usize = 73;

type LaneKey = (u8, [u8; 32]);

struct Head {
    n: u64,
    digest: [u8; 32],
}

fn by_lane(records: &[Record]) -> BTreeMap<LaneKey, Vec<&Record>> {
    let mut out: BTreeMap<LaneKey, Vec<&Record>> = BTreeMap::new();
    for r in records {
        out.entry((r.header.chain, r.header.lane))
            .or_default()
            .push(r);
    }
    out
}

// SHA-256 over the ids of a lane's records at or below a place, in id order.
fn digest(records: &[&Record], upto: u64) -> [u8; 32] {
    let mut ids: Vec<&[u8; 32]> = records
        .iter()
        .filter(|r| r.header.n <= upto)
        .map(|r| &r.id)
        .collect();
    ids.sort_unstable();
    let mut cat = Vec::with_capacity(ids.len().saturating_mul(32));
    for id in ids {
        cat.extend_from_slice(id);
    }
    sha256_raw(&cat)
}

/// mt_biz_heads: u32 count, then for every lane in (chain, lane) order -- chain, lane, the highest place held, the digest
/// of the ids held at or below it.
pub fn heads(stream: &[u8]) -> Result<Vec<u8>, BizError> {
    let records = split_chain(stream)?;
    let lanes = by_lane(&records);
    let count = u32::try_from(lanes.len()).map_err(|_| BizError::Frame("too many lanes"))?;
    let mut out = Vec::with_capacity(4 + lanes.len().saturating_mul(HEAD_LEN));
    write_u32(&mut out, count);
    for ((chain, lane), recs) in &lanes {
        let n = recs.iter().map(|r| r.header.n).max().unwrap_or(0);
        write_u8(&mut out, *chain);
        write_bytes(&mut out, lane);
        write_u64(&mut out, n);
        write_bytes(&mut out, &digest(recs, n));
    }
    Ok(out)
}

fn read_heads(bytes: &[u8]) -> Result<BTreeMap<LaneKey, Head>, BizError> {
    let mut r = Reader::new(bytes);
    let count =
        usize::try_from(r.u32().map_err(BizError::Frame)?).map_err(|_| BizError::Frame("count"))?;
    // A count the bytes cannot hold is refused before anything is read for it.
    if count > bytes.len() / HEAD_LEN {
        return Err(BizError::Frame("more heads than bytes"));
    }
    let mut out = BTreeMap::new();
    for _ in 0..count {
        let chain = r.u8().map_err(BizError::Frame)?;
        let lane = r.array().map_err(BizError::Frame)?;
        let n = r.u64().map_err(BizError::Frame)?;
        let digest = r.array().map_err(BizError::Frame)?;
        out.insert((chain, lane), Head { n, digest });
    }
    if !r.done() {
        return Err(BizError::Frame("trailing bytes after the heads"));
    }
    Ok(out)
}

/// mt_biz_after: what the holder of `their_heads` lacks of `stream` -- a lane they do not name, whole; of a lane they name,
/// every record past their place, and every record at or below it too when the digests there differ.
pub fn after(stream: &[u8], their_heads: &[u8]) -> Result<Vec<u8>, BizError> {
    let theirs = read_heads(their_heads)?;
    let records = split_chain(stream)?;
    let mut out: Vec<Record> = Vec::new();
    for (key, recs) in by_lane(&records) {
        match theirs.get(&key) {
            None => out.extend(recs.into_iter().cloned()),
            Some(h) => {
                let sibling = digest(&recs, h.n) != h.digest;
                out.extend(
                    recs.into_iter()
                        .filter(|r| sibling || r.header.n > h.n)
                        .cloned(),
                );
            },
        }
    }
    join_chain(&sorted_unique(out))
}

/// mt_biz_keep: a stream kept to one organisation -- its genesis and the records naming it; nothing of another.
pub fn keep(org: &[u8], stream: &[u8]) -> Result<Vec<u8>, BizError> {
    let org: [u8; 32] = org
        .try_into()
        .map_err(|_| BizError::Frame("org is not 32 bytes"))?;
    let mine: Vec<Record> = split_chain(stream)?
        .into_iter()
        .filter(|r| r.header.org == org || r.id == org)
        .collect();
    join_chain(&sorted_unique(mine))
}
