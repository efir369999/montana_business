use mt_crypto::{hash, PublicKey, SecretKey};
use serde_json::{Map, Value};

use crate::attest::valid_e164;
use crate::body::{Body, Line, ACCEPT_WHOLE, KIND_GENESIS, KIND_JOIN, SOURCE_TEXT, ZERO16};
use crate::codec::{from_hex, hex_array};
use crate::domain::DOMAIN_LETTER;
use crate::fold::{
    fold, invite_id_of, member_id_of, phone_hash_of, shift_lane_of, Outcome, FUTURE_SLACK_MS,
};
use crate::frame::{
    join_chain, seal, split_chain, Header, Record, CHAIN_C, CHAIN_H, CHAIN_R, CHAIN_S, ZERO32,
};
use crate::BizError;

struct Cmd(Map<String, Value>);

impl Cmd {
    fn parse(s: &str) -> Result<Self, BizError> {
        match serde_json::from_str::<Value>(s) {
            Ok(Value::Object(m)) => Ok(Self(m)),
            _ => Err(BizError::Command("command is not a JSON object")),
        }
    }

    fn opt(&self, k: &str) -> Option<&Value> {
        self.0.get(k).filter(|v| !v.is_null())
    }

    fn need(&self, k: &'static str) -> Result<&Value, BizError> {
        self.opt(k).ok_or(BizError::Command(k))
    }

    fn kind(&self) -> Result<&str, BizError> {
        self.need("kind")?.as_str().ok_or(BizError::Command("kind"))
    }

    fn hex<const N: usize>(&self, k: &'static str) -> Result<[u8; N], BizError> {
        self.need(k)?
            .as_str()
            .and_then(hex_array::<N>)
            .ok_or(BizError::Command(k))
    }

    fn opt_hex<const N: usize>(&self, k: &'static str) -> Result<Option<[u8; N]>, BizError> {
        match self.opt(k) {
            None => Ok(None),
            Some(v) => v
                .as_str()
                .and_then(hex_array::<N>)
                .map(Some)
                .ok_or(BizError::Command(k)),
        }
    }

    fn bytes(&self, k: &'static str) -> Result<Vec<u8>, BizError> {
        self.need(k)?
            .as_str()
            .and_then(from_hex)
            .ok_or(BizError::Command(k))
    }

    // Integers as JSON numbers or decimal strings; a fraction or a sign is refused, never rounded.
    fn u64(&self, k: &'static str) -> Result<u64, BizError> {
        match self.need(k)? {
            Value::Number(n) => n.as_u64(),
            Value::String(s) if !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit()) => {
                s.parse::<u64>().ok()
            },
            _ => None,
        }
        .ok_or(BizError::Command(k))
    }

    fn u64_or(&self, k: &'static str, default: u64) -> Result<u64, BizError> {
        match self.opt(k) {
            None => Ok(default),
            Some(_) => self.u64(k),
        }
    }

    fn u8(&self, k: &'static str) -> Result<u8, BizError> {
        u8::try_from(self.u64(k)?).map_err(|_| BizError::Command(k))
    }

    fn u8_or(&self, k: &'static str, default: u8) -> Result<u8, BizError> {
        u8::try_from(self.u64_or(k, u64::from(default))?).map_err(|_| BizError::Command(k))
    }

    // Lines as a JSON array of {item, size, qty, coins}: the item in hex, the size as written, whole numbers.
    fn lines(&self, k: &'static str) -> Result<Vec<Line>, BizError> {
        let Some(Value::Array(rows)) = self.opt(k) else {
            return Err(BizError::Command(k));
        };
        rows.iter()
            .map(|row| {
                let Value::Object(m) = row else {
                    return Err(BizError::Command(k));
                };
                let line = Cmd(m.clone());
                Ok(Line {
                    item: line.hex("item")?,
                    size: line.string_or_empty("size")?,
                    qty: line.u32_or("qty", 1)?,
                    coins: line.u64_or("coins", 0)?,
                })
            })
            .collect()
    }

    fn lines_or_empty(&self, k: &'static str) -> Result<Vec<Line>, BizError> {
        if self.opt(k).is_none() {
            Ok(Vec::new())
        } else {
            self.lines(k)
        }
    }

    fn u32_or(&self, k: &'static str, default: u32) -> Result<u32, BizError> {
        u32::try_from(self.u64_or(k, u64::from(default))?).map_err(|_| BizError::Command(k))
    }

    fn string(&self, k: &'static str) -> Result<String, BizError> {
        self.need(k)?
            .as_str()
            .map(str::to_owned)
            .ok_or(BizError::Command(k))
    }

    fn string_or_empty(&self, k: &'static str) -> Result<String, BizError> {
        match self.opt(k) {
            None => Ok(String::new()),
            Some(v) => v.as_str().map(str::to_owned).ok_or(BizError::Command(k)),
        }
    }

    fn flag_or(&self, k: &'static str, default: bool) -> Result<bool, BizError> {
        match self.opt(k) {
            None => Ok(default),
            Some(Value::Bool(b)) => Ok(*b),
            Some(Value::Number(n)) if n.as_u64() == Some(0) => Ok(false),
            Some(Value::Number(n)) if n.as_u64() == Some(1) => Ok(true),
            Some(_) => Err(BizError::Command(k)),
        }
    }

    fn role(&self, k: &'static str) -> Result<u8, BizError> {
        match self.need(k)? {
            Value::String(s) => match s.as_str() {
                "owner" => Some(0),
                "admin" => Some(1),
                "manager" => Some(2),
                "employee" => Some(3),
                _ => None,
            },
            Value::Number(n) => n.as_u64().and_then(|v| u8::try_from(v).ok()),
            _ => None,
        }
        .ok_or(BizError::Command(k))
    }
}

// A chat letter's hash: given as hex, or made here from the letter's one name and its words, so no app ever holds the label.
fn letter_hash(cmd: &Cmd) -> Result<[u8; 32], BizError> {
    match cmd.opt_hex::<32>("letter")? {
        Some(h) => Ok(h),
        None => {
            let mid = cmd.string("mid")?;
            let text = cmd.string("text")?;
            Ok(hash(
                DOMAIN_LETTER,
                &[mid.as_bytes(), &[0u8], text.as_bytes()],
            ))
        },
    }
}

fn body_of(
    cmd: &Cmd,
    kind: &str,
    pubkey: &PublicKey,
    org: Option<&[u8; 32]>,
) -> Result<Body, BizError> {
    Ok(match kind {
        "genesis" => Body::Genesis {
            name: cmd.string("name")?,
            owner_key: pubkey.clone(),
            owner_name: cmd.string("owner_name")?,
            owner_card: cmd.string_or_empty("owner_card")?,
        },
        "invite" => Body::Invite {
            invite_id: match cmd.opt_hex::<32>("invite_id")? {
                Some(id) => id,
                None => invite_id_of(&cmd.hex::<32>("secret")?),
            },
            role: cmd.role("role")?,
            dept: cmd.opt_hex::<16>("dept")?.unwrap_or(ZERO16),
            expires_ms: cmd.u64("expires_ms")?,
            phone_bound: cmd.flag_or("phone_bound", false)?,
        },
        "join" => Body::Join {
            secret: cmd.hex("secret")?,
            key: pubkey.clone(),
            name: cmd.string("name")?,
            card: cmd.string_or_empty("card")?,
        },
        "role" => Body::Role {
            member: cmd.hex("member")?,
            role: cmd.role("role")?,
        },
        "dept" => Body::Dept {
            dept: cmd.hex("dept")?,
            name: cmd.string("name")?,
            archived: cmd.flag_or("archived", false)?,
        },
        "assign" => Body::Assign {
            member: cmd.hex("member")?,
            dept: cmd.opt_hex::<16>("dept")?.unwrap_or(ZERO16),
            title: cmd.string_or_empty("title")?,
        },
        "remove" => Body::Remove {
            member: cmd.hex("member")?,
        },
        "rekey" => Body::Rekey {
            member: cmd.hex("member")?,
            key: PublicKey::from_slice(&cmd.bytes("key")?).ok_or(BizError::Command("key"))?,
        },
        // The invitation is named by its id, or by its secret -- the app that minted it holds the secret, never the label.
        "revoke" => Body::Revoke {
            invite_id: match cmd.opt_hex::<32>("invite_id")? {
                Some(id) => id,
                None => invite_id_of(&cmd.hex::<32>("secret")?),
            },
        },
        "offer" => Body::Offer {
            item: cmd.hex("item")?,
            title: cmd.string("title")?,
            price: cmd.u64("price")?,
            stock: cmd.u32_or("stock", 0)?,
            active: cmd.flag_or("active", true)?,
        },
        "profile" => Body::Profile {
            name: cmd.string("name")?,
            card: cmd.string_or_empty("card")?,
        },
        "phone_bind" => Body::PhoneBind {
            attest: cmd.bytes("attest")?,
        },
        "invite_phone" => {
            let phone_hash = match cmd.opt_hex::<32>("phone_hash")? {
                Some(hash) => hash,
                None => {
                    let e164 = cmd.string("e164")?;
                    if !valid_e164(&e164) {
                        return Err(BizError::Command("e164"));
                    }
                    phone_hash_of(org.ok_or(BizError::Denied("no_org"))?, &e164)
                },
            };
            Body::InvitePhone {
                invite_id: cmd.hex("invite_id")?,
                phone_hash,
            }
        },
        "salary" => Body::Salary {
            member: cmd.hex("member")?,
            coins: cmd.u64("coins")?,
            period: cmd.u8("period")?,
            from_ms: cmd.u64("from_ms")?,
        },
        "pay" => Body::Pay {
            member: cmd.hex("member")?,
            pay_kind: cmd.u8("pay_kind")?,
            period_key: cmd.u32_or("period_key", 0)?,
            coins: cmd.u64("coins")?,
            note: cmd.string_or_empty("note")?,
        },
        "receipt" => Body::Receipt {
            record: cmd.hex("record")?,
        },
        "redeem" => Body::Redeem {
            item: cmd.hex("item")?,
            qty: cmd.u32_or("qty", 1)?,
            coins: cmd.u64("coins")?,
        },
        "fulfil" => Body::Fulfil {
            record: cmd.hex("record")?,
        },
        "shift_open" => Body::ShiftOpen {
            node: cmd.opt_hex::<16>("node")?.unwrap_or(ZERO16),
        },
        "shift_close" => Body::ShiftClose {
            note: cmd.string_or_empty("note")?,
        },
        "shift_confirm" => Body::ShiftConfirm {
            record: cmd.hex("record")?,
        },
        "item" => Body::Item {
            item: cmd.hex("item")?,
            title: cmd.string("title")?,
            sizes: cmd.string_or_empty("sizes")?,
            unit: cmd.string_or_empty("unit")?,
            active: cmd.flag_or("active", true)?,
        },
        "node" => Body::Node {
            node: cmd.hex("node")?,
            node_kind: cmd.u8("node_kind")?,
            name: cmd.string("name")?,
            place: cmd.string_or_empty("place")?,
            active: cmd.flag_or("active", true)?,
        },
        "node_staff" => Body::NodeStaff {
            node: cmd.hex("node")?,
            member: cmd.hex("member")?,
            on: cmd.flag_or("on", true)?,
        },
        "order" => Body::Order {
            from: cmd.hex("from")?,
            to: cmd.hex("to")?,
            lines: cmd.lines("lines")?,
            source: cmd.u8_or("source", SOURCE_TEXT)?,
            voice: cmd.opt_hex::<32>("voice")?.unwrap_or(ZERO32),
            text: cmd.string_or_empty("text")?,
        },
        "confirm" => Body::Confirm {
            node: cmd.hex("node")?,
        },
        "pack" => Body::Pack {
            place: cmd.hex("place")?,
            node: cmd.hex("node")?,
            lines: cmd.lines("lines")?,
        },
        "handoff" => Body::Handoff {
            place: cmd.hex("place")?,
            from: cmd.hex("from")?,
            to: cmd.hex("to")?,
        },
        "accept" => Body::Accept {
            place: cmd.hex("place")?,
            node: cmd.hex("node")?,
            condition: cmd.u8_or("condition", ACCEPT_WHOLE)?,
            lines: cmd.lines_or_empty("lines")?,
            note: cmd.string_or_empty("note")?,
            photo: cmd.opt_hex::<32>("photo")?.unwrap_or(ZERO32),
        },
        "scan" => Body::Scan {
            place: cmd.hex("place")?,
            node: cmd.hex("node")?,
        },
        "shelf" => Body::Shelf {
            node: cmd.hex("node")?,
            lines: cmd.lines("lines")?,
        },
        "sale" => Body::Sale {
            node: cmd.hex("node")?,
            lines: cmd.lines("lines")?,
        },
        "issue" => Body::Issue {
            place: cmd.opt_hex::<16>("place")?.unwrap_or(ZERO16),
            issue_kind: cmd.u8("issue_kind")?,
            note: cmd.string_or_empty("note")?,
        },
        "cancel" => Body::Cancel {
            note: cmd.string_or_empty("note")?,
        },
        "open" => Body::Open {
            chat_kind: cmd.u8("chat_kind")?,
            name: cmd.string("name")?,
            dept: cmd.opt_hex::<16>("dept")?.unwrap_or(ZERO16),
            group: cmd.opt_hex::<16>("group")?.unwrap_or(ZERO16),
        },
        "letter" => Body::Letter {
            letter: letter_hash(cmd)?,
            mid: cmd.string("mid")?,
            letter_kind: cmd.u8_or("letter_kind", 1)?,
        },
        "edit" => Body::Edit {
            record: cmd.hex("record")?,
            letter: letter_hash(cmd)?,
        },
        "delete" => Body::Delete {
            record: cmd.hex("record")?,
        },
        "track" => Body::Track {
            on: cmd.flag_or("on", false)?,
        },
        "track_window" => Body::TrackWindow {
            window_s: cmd.u32_or("window_s", 0)?,
        },
        _ => return Err(BizError::Command("kind")),
    })
}

fn h_lane(body: &Body, author: [u8; 32], hr: &[Record]) -> Result<[u8; 32], BizError> {
    let lane_of = |id: &[u8; 32]| {
        hr.iter()
            .find(|r| &r.id == id)
            .map(|r| r.header.lane)
            .ok_or(BizError::Denied("unknown_record"))
    };
    match body {
        Body::InvitePhone { invite_id, .. } => Ok(*invite_id),
        Body::Salary { member, .. } | Body::Pay { member, .. } => Ok(*member),
        Body::Receipt { record } | Body::Fulfil { record } | Body::ShiftConfirm { record } => {
            lane_of(record)
        },
        Body::ShiftOpen { .. } | Body::ShiftClose { .. } => Ok(shift_lane_of(&author)),
        _ => Ok(author),
    }
}

fn lane_head<'a>(
    set: &'a [Record],
    chain: u8,
    org: &[u8; 32],
    lane: &[u8; 32],
) -> Option<&'a Record> {
    set.iter()
        .filter(|r| {
            r.header.chain == chain
                && r.header.lane == *lane
                && (r.header.org == *org || r.id == *org)
        })
        .max_by_key(|r| (r.header.n, r.header.at_ms, r.id))
}

pub fn author_with(
    seckey: &[u8],
    pubkey: &[u8],
    roster: &[u8],
    hr: &[u8],
    command: &str,
    at_ms: u64,
    service: Option<&PublicKey>,
) -> Result<Vec<u8>, BizError> {
    let sk = SecretKey::from_slice(seckey).ok_or(BizError::Key("secret key is not 4032 bytes"))?;
    let pk = PublicKey::from_slice(pubkey).ok_or(BizError::Key("public key is not 1952 bytes"))?;
    let mut roster = split_chain(roster)?;
    let hr = split_chain(hr)?;
    let cmd = Cmd::parse(command)?;
    // Who the author is and in which organisation lies in the roster alone (Genesis, Join, Rekey, Remove are chain R): the
    // fold before the record leaves the private stream -- orders, chats, shifts -- unfolded; the fold after takes both.
    let before = fold(&roster, &[], at_ms, Some(&pk), service);
    let org_id = before.org.as_ref().map(|o| o.id);
    let body = body_of(&cmd, cmd.kind()?, &pk, org_id.as_ref())?;
    let kind = body.kind();
    let chain = body.chain();
    let (org, me) = match kind {
        KIND_GENESIS => (ZERO32, member_id_of(&pk)),
        KIND_JOIN => (org_id.ok_or(BizError::Denied("no_org"))?, member_id_of(&pk)),
        _ => (
            org_id.ok_or(BizError::Denied("no_org"))?,
            before
                .member_by_key(&pk)
                .ok_or(BizError::Denied("not_member"))?,
        ),
    };
    let lane = match chain {
        CHAIN_R => me,
        CHAIN_H => h_lane(&body, me, &hr)?,
        // An order's and a chat's lane is the key its first record names; every later step names it again.
        CHAIN_S => cmd.hex::<32>("order")?,
        _ => cmd.hex::<32>("chat")?,
    };
    // The author's clock, raised just past every record the author has already seen (never
    // past the slack), so an answer cannot sort before the record it answers.
    let horizon = at_ms.saturating_add(FUTURE_SLACK_MS);
    let seen = roster
        .iter()
        .chain(hr.iter())
        .map(|r| r.header.at_ms)
        .filter(|t| *t <= horizon)
        .max();
    let mut at = at_ms.max(seen.map_or(0, |t| t.saturating_add(1)));
    let (n, prev) = if kind == KIND_GENESIS {
        (0, ZERO32)
    } else {
        let set = if chain == CHAIN_R { &roster } else { &hr };
        match lane_head(set, chain, &org, &lane) {
            Some(head) => {
                at = at.max(head.header.at_ms.saturating_add(1));
                let n = head
                    .header
                    .n
                    .checked_add(1)
                    .ok_or(BizError::Denied("lane_full"))?;
                (n, head.id)
            },
            None => (0, ZERO32),
        }
    };
    let header = Header {
        chain,
        kind,
        org,
        lane,
        n,
        prev,
        at_ms: at,
        author: me,
    };
    let encoded = body.encode().map_err(BizError::Command)?;
    let rec = seal(&header, &encoded, &sk)?;
    let id = rec.id;
    // THE FOLD AFTER THE RECORD TAKES THE ROSTER AND THE RECORD'S OWN CHAIN: no rule of S, H or C reads another private chain
    // (an order's stock is all of S, a purchase's offer all of H, a chat its own lane; each reads the roster), so the answer
    // is the whole fold's, and a letter is not slowed by the orders, nor an order's step by ten thousand letters.
    let mut own: Vec<Record> = hr
        .into_iter()
        .filter(|r| r.header.chain == chain && (chain != CHAIN_C || r.header.lane == lane))
        .collect();
    if chain == CHAIN_R {
        roster.push(rec.clone());
    } else {
        own.push(rec.clone());
    }
    let after = fold(&roster, &own, at, Some(&pk), service);
    match after.outcome(&id) {
        Some(Outcome::Rejected(reason)) => Err(BizError::Denied(reason)),
        _ => join_chain([&rec]),
    }
}
