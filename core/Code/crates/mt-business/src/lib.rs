//! Montana Business core: the organisation as a TimeChain of signed records.
//!
//! Four chains: R (the roster, held by every member), H (personnel, held by the administrators
//! and, lane by lane, by each employee), S (supply: every order a lane of its own, held by the
//! people of its path) and C (the organisation's chats and channels, a lane per chat, held by
//! those who hear it). R travels in the roster stream; H, S and C in the private stream, each
//! lane handed only to its own people. The doors below are mirrored one to one
//! by the C ABI (mt-bindings, ffi_business.rs) and by the JNI of the Android fork, so a byte
//! produced on one platform is the byte produced on the other. Application layer outside
//! consensus: no AccountTable, no apply_proposal, no spec change.

use std::collections::BTreeSet;

use mt_crypto::{PublicKey, PUBLIC_KEY_SIZE};

pub mod attest;
pub mod body;
pub mod chat;
pub mod codec;
mod command;
pub mod domain;
pub mod email;
pub mod fold;
pub mod frame;
pub mod json;
pub mod period;
pub mod supply;
pub mod sync;
pub mod view;

use frame::{join_chain, sorted_unique, split_chain, CHAIN_R};

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum BizError {
    Frame(&'static str),
    Command(&'static str),
    Denied(&'static str),
    Attest(&'static str),
    Key(&'static str),
}

impl std::fmt::Display for BizError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Frame(m) => write!(f, "malformed frame: {m}"),
            Self::Command(m) => write!(f, "bad command field: {m}"),
            Self::Denied(m) => write!(f, "denied: {m}"),
            Self::Attest(m) => write!(f, "attestation: {m}"),
            Self::Key(m) => write!(f, "key: {m}"),
        }
    }
}

impl std::error::Error for BizError {}

/// mt_biz_author: the author's next record as a one-frame chain (u32 LE length, record), ready
/// to be merged; lane, n and prev come from the chains, the right from the fold.
pub fn author(
    seckey: &[u8],
    pubkey: &[u8],
    roster: &[u8],
    hr: &[u8],
    command: &str,
    at_ms: u64,
) -> Result<Vec<u8>, BizError> {
    author_with(
        seckey,
        pubkey,
        roster,
        hr,
        command,
        at_ms,
        attest::pinned_key().ok(),
    )
}

/// `author` with the service key given explicitly (tests and the attest signer).
pub fn author_with(
    seckey: &[u8],
    pubkey: &[u8],
    roster: &[u8],
    hr: &[u8],
    command: &str,
    at_ms: u64,
    service: Option<&PublicKey>,
) -> Result<Vec<u8>, BizError> {
    command::author_with(seckey, pubkey, roster, hr, command, at_ms, service)
}

/// mt_biz_merge: union of two chains, sorted by (at_ms, id), frames checked; returns the merged
/// chain and how many frames of `incoming` were new.
pub fn merge(have: &[u8], incoming: &[u8]) -> Result<(Vec<u8>, usize), BizError> {
    let a = split_chain(have)?;
    let b = split_chain(incoming)?;
    let known: BTreeSet<&[u8]> = a.iter().map(|r| r.bytes.as_slice()).collect();
    let added = b
        .iter()
        .map(|r| r.bytes.as_slice())
        .filter(|bytes| !known.contains(bytes))
        .collect::<BTreeSet<_>>()
        .len();
    let mut all = a;
    all.extend(b);
    Ok((join_chain(&sorted_unique(all))?, added))
}

/// mt_biz_view: the JSON the screens draw from.
pub fn view(
    roster: &[u8],
    hr: &[u8],
    viewer_pubkey: &[u8],
    now_ms: u64,
) -> Result<String, BizError> {
    view_with(roster, hr, viewer_pubkey, now_ms, attest::pinned_key().ok())
}

pub fn view_with(
    roster: &[u8],
    hr: &[u8],
    viewer_pubkey: &[u8],
    now_ms: u64,
    service: Option<&PublicKey>,
) -> Result<String, BizError> {
    let pk = PublicKey::from_slice(viewer_pubkey)
        .ok_or(BizError::Key("public key is not 1952 bytes"))?;
    let r = split_chain(roster)?;
    let h = split_chain(hr)?;
    let f = fold::fold(&r, &h, now_ms, Some(&pk), service);
    Ok(view::render(&f, &pk, now_ms))
}

/// mt_biz_slice: the records of one private lane, sorted: what is handed to its people -- an
/// employee's H lane, an order's S lane, a chat's C lane.
pub fn slice(hr: &[u8], lane: &[u8]) -> Result<Vec<u8>, BizError> {
    let lane: [u8; 32] = lane
        .try_into()
        .map_err(|_| BizError::Frame("lane is not 32 bytes"))?;
    let mine: Vec<_> = split_chain(hr)?
        .into_iter()
        .filter(|r| r.header.chain != CHAIN_R && r.header.lane == lane)
        .collect();
    join_chain(&sorted_unique(mine))
}

/// mt_biz_attest_open: the attestation checked with the pinned service key, as JSON
/// {e164, subject, channel, at_ms}.
pub fn attest_open(attest: &[u8]) -> Result<String, BizError> {
    Ok(attest::open(attest)?.to_json())
}

/// mt_biz_email_open: the address confirmed by mail, checked with the same pinned service key, as JSON {email, subject,
/// at_ms}.
pub fn email_open(attest: &[u8]) -> Result<String, BizError> {
    Ok(email::open(attest)?.to_json())
}

/// mt_biz_service_key: the pinned public key of the phone service.
pub fn service_key() -> Result<[u8; PUBLIC_KEY_SIZE], BizError> {
    Ok(*attest::pinned_key()?.as_bytes())
}

/// mt_biz_member_id: SHA-256(mt-biz-member, key at join); it never changes on Rekey.
pub fn member_id(pubkey: &[u8]) -> Result<[u8; 32], BizError> {
    let pk = PublicKey::from_slice(pubkey).ok_or(BizError::Key("public key is not 1952 bytes"))?;
    Ok(fold::member_id_of(&pk))
}

/// mt_biz_invite_id: SHA-256(mt-biz-invite, secret) -- the id the view names an invitation by, so no app repeats the label
/// to find the invitation a secret it minted belongs to.
pub fn invite_id(secret: &[u8]) -> Result<[u8; 32], BizError> {
    let secret: [u8; 32] = secret
        .try_into()
        .map_err(|_| BizError::Frame("secret is not 32 bytes"))?;
    Ok(fold::invite_id_of(&secret))
}

pub use sync::{after, heads, keep};
