use mt_codec::{write_bytes, write_u16, write_u32, write_u64, write_u8};
use mt_crypto::{PublicKey, PUBLIC_KEY_SIZE};

use crate::codec::{put_flag, put_string, Reader, Short};
use crate::frame::{CHAIN_C, CHAIN_H, CHAIN_R, CHAIN_S};

pub const KIND_GENESIS: u8 = 0x01;
pub const KIND_INVITE: u8 = 0x02;
pub const KIND_JOIN: u8 = 0x03;
pub const KIND_ROLE: u8 = 0x04;
pub const KIND_DEPT: u8 = 0x05;
pub const KIND_ASSIGN: u8 = 0x06;
pub const KIND_REMOVE: u8 = 0x07;
pub const KIND_REKEY: u8 = 0x08;
pub const KIND_REVOKE: u8 = 0x09;
pub const KIND_OFFER: u8 = 0x0A;
pub const KIND_PROFILE: u8 = 0x0B;
pub const KIND_ITEM: u8 = 0x0C;
pub const KIND_NODE: u8 = 0x0D;
pub const KIND_NODE_STAFF: u8 = 0x0E;
pub const KIND_PHONE_BIND: u8 = 0x21;
pub const KIND_INVITE_PHONE: u8 = 0x22;
pub const KIND_SALARY: u8 = 0x23;
pub const KIND_PAY: u8 = 0x24;
pub const KIND_RECEIPT: u8 = 0x25;
pub const KIND_REDEEM: u8 = 0x26;
pub const KIND_FULFIL: u8 = 0x27;
pub const KIND_SHIFT_OPEN: u8 = 0x28;
pub const KIND_SHIFT_CLOSE: u8 = 0x29;
pub const KIND_SHIFT_CONFIRM: u8 = 0x2A;
pub const KIND_ORDER: u8 = 0x41;
pub const KIND_CONFIRM: u8 = 0x42;
pub const KIND_PACK: u8 = 0x43;
pub const KIND_HANDOFF: u8 = 0x44;
pub const KIND_ACCEPT: u8 = 0x45;
pub const KIND_SCAN: u8 = 0x46;
pub const KIND_SHELF: u8 = 0x47;
pub const KIND_SALE: u8 = 0x48;
pub const KIND_ISSUE: u8 = 0x49;
pub const KIND_CANCEL: u8 = 0x4A;
pub const KIND_OPEN: u8 = 0x61;
pub const KIND_LETTER: u8 = 0x62;
pub const KIND_EDIT: u8 = 0x63;
pub const KIND_DELETE: u8 = 0x64;
// A chat's people are followed on the map by its bosses' word (the author's word 06.10.2026 14:3x MSK: «a checkbox to track the
// group's employees by geolocation -- for couriers, logisticians, agents, representatives»). The record carries the switch
// alone: where anyone stands never enters a chain.
pub const KIND_TRACK: u8 = 0x65;
// The chat asks its followed people for their place once in every window of this many seconds (the author's word 06.10.2026
// 15:1x MSK: «in the groups a requirement can be set on the employees to send the geolocation in a given time window, otherwise
// the app itself sends it at random in the next time window»); 0 -- no window, the place follows the person.
pub const KIND_TRACK_WINDOW: u8 = 0x66;
pub const TRACK_WINDOW_MIN: u32 = 300;
pub const TRACK_WINDOW_MAX: u32 = 86_400;

pub const ROLE_OWNER: u8 = 0;
pub const ROLE_ADMIN: u8 = 1;
pub const ROLE_MANAGER: u8 = 2;
pub const ROLE_EMPLOYEE: u8 = 3;

pub const PAY_SALARY: u8 = 1;
pub const PAY_BONUS: u8 = 2;

pub const NAME_MAX: usize = 256;
pub const CARD_MAX: usize = 1024;
pub const NOTE_MAX: usize = 256;
pub const SIZE_MAX: usize = 32;
pub const UNIT_MAX: usize = 32;
pub const TEXT_MAX: usize = 4096;
pub const LINES_MAX: usize = 64;
pub const MID_MAX: usize = 64;

pub const NODE_SUPPLIER: u8 = 1;
pub const NODE_WAREHOUSE: u8 = 2;
pub const NODE_HUB: u8 = 3;
pub const NODE_CARRIER: u8 = 4;
pub const NODE_SHOP: u8 = 5;

pub const SOURCE_TEXT: u8 = 1;
pub const SOURCE_VOICE: u8 = 2;

pub const ACCEPT_WHOLE: u8 = 1;
pub const ACCEPT_DAMAGED: u8 = 2;
pub const ACCEPT_SHORT: u8 = 3;

pub const ISSUE_RETURN: u8 = 1;
pub const ISSUE_DEFECT: u8 = 2;
pub const ISSUE_LOSS: u8 = 3;

pub const CHAT_GROUP: u8 = 1;
pub const CHAT_CHANNEL: u8 = 2;
// A letter of a chat: a letter of its own, or a comment under a channel's post -- the channel's people answer there.
pub const LETTER_TEXT: u8 = 1;
pub const LETTER_COMMENT: u8 = 2;

pub type Dept = [u8; 16];
pub const ZERO16: Dept = [0u8; 16];

// An item, a node or a place (a box, a pallet): sixteen bytes the author draws at random.
pub type Tag = [u8; 16];

// One line of goods: the item, its size as written on the label, how many, and the coins per line.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Line {
    pub item: Tag,
    pub size: String,
    pub qty: u32,
    pub coins: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Body {
    Genesis {
        name: String,
        owner_key: PublicKey,
        owner_name: String,
        owner_card: String,
    },
    Invite {
        invite_id: [u8; 32],
        role: u8,
        dept: Dept,
        expires_ms: u64,
        phone_bound: bool,
    },
    Join {
        secret: [u8; 32],
        key: PublicKey,
        name: String,
        card: String,
    },
    Role {
        member: [u8; 32],
        role: u8,
    },
    Dept {
        dept: Dept,
        name: String,
        archived: bool,
    },
    Assign {
        member: [u8; 32],
        dept: Dept,
        title: String,
    },
    Remove {
        member: [u8; 32],
    },
    Rekey {
        member: [u8; 32],
        key: PublicKey,
    },
    Revoke {
        invite_id: [u8; 32],
    },
    Offer {
        item: Dept,
        title: String,
        price: u64,
        stock: u32,
        active: bool,
    },
    Profile {
        name: String,
        card: String,
    },
    PhoneBind {
        attest: Vec<u8>,
    },
    InvitePhone {
        invite_id: [u8; 32],
        phone_hash: [u8; 32],
    },
    Salary {
        member: [u8; 32],
        coins: u64,
        period: u8,
        from_ms: u64,
    },
    Pay {
        member: [u8; 32],
        pay_kind: u8,
        period_key: u32,
        coins: u64,
        note: String,
    },
    Receipt {
        record: [u8; 32],
    },
    Redeem {
        item: Dept,
        qty: u32,
        coins: u64,
    },
    Fulfil {
        record: [u8; 32],
    },
    ShiftOpen {
        node: Tag,
    },
    ShiftClose {
        note: String,
    },
    ShiftConfirm {
        record: [u8; 32],
    },
    Item {
        item: Tag,
        title: String,
        sizes: String,
        unit: String,
        active: bool,
    },
    Node {
        node: Tag,
        node_kind: u8,
        name: String,
        place: String,
        active: bool,
    },
    NodeStaff {
        node: Tag,
        member: [u8; 32],
        on: bool,
    },
    Order {
        from: Tag,
        to: Tag,
        lines: Vec<Line>,
        source: u8,
        voice: [u8; 32],
        text: String,
    },
    Confirm {
        node: Tag,
    },
    Pack {
        place: Tag,
        node: Tag,
        lines: Vec<Line>,
    },
    Handoff {
        place: Tag,
        from: Tag,
        to: Tag,
    },
    Accept {
        place: Tag,
        node: Tag,
        condition: u8,
        lines: Vec<Line>,
        note: String,
        photo: [u8; 32],
    },
    Scan {
        place: Tag,
        node: Tag,
    },
    Shelf {
        node: Tag,
        lines: Vec<Line>,
    },
    Sale {
        node: Tag,
        lines: Vec<Line>,
    },
    Issue {
        place: Tag,
        issue_kind: u8,
        note: String,
    },
    Cancel {
        note: String,
    },
    Open {
        chat_kind: u8,
        name: String,
        dept: Dept,
        group: Tag,
    },
    Letter {
        letter: [u8; 32],
        mid: String,
        letter_kind: u8,
    },
    Edit {
        record: [u8; 32],
        letter: [u8; 32],
    },
    Delete {
        record: [u8; 32],
    },
    Track {
        on: bool,
    },
    TrackWindow {
        window_s: u32,
    },
}

pub fn chain_of(kind: u8) -> u8 {
    match kind {
        0x00..=0x1F => CHAIN_R,
        0x20..=0x3F => CHAIN_H,
        0x40..=0x5F => CHAIN_S,
        _ => CHAIN_C,
    }
}

// The one word of each kind: the command that writes it and the journal and the order's steps that show it.
pub fn kind_name(kind: u8) -> &'static str {
    match kind {
        KIND_GENESIS => "genesis",
        KIND_INVITE => "invite",
        KIND_JOIN => "join",
        KIND_ROLE => "role",
        KIND_DEPT => "dept",
        KIND_ASSIGN => "assign",
        KIND_REMOVE => "remove",
        KIND_REKEY => "rekey",
        KIND_REVOKE => "revoke",
        KIND_OFFER => "offer",
        KIND_PROFILE => "profile",
        KIND_ITEM => "item",
        KIND_NODE => "node",
        KIND_NODE_STAFF => "node_staff",
        KIND_PHONE_BIND => "phone_bind",
        KIND_INVITE_PHONE => "invite_phone",
        KIND_SALARY => "salary",
        KIND_PAY => "pay",
        KIND_RECEIPT => "receipt",
        KIND_REDEEM => "redeem",
        KIND_FULFIL => "fulfil",
        KIND_SHIFT_OPEN => "shift_open",
        KIND_SHIFT_CLOSE => "shift_close",
        KIND_SHIFT_CONFIRM => "shift_confirm",
        KIND_ORDER => "order",
        KIND_CONFIRM => "confirm",
        KIND_PACK => "pack",
        KIND_HANDOFF => "handoff",
        KIND_ACCEPT => "accept",
        KIND_SCAN => "scan",
        KIND_SHELF => "shelf",
        KIND_SALE => "sale",
        KIND_ISSUE => "issue",
        KIND_CANCEL => "cancel",
        KIND_OPEN => "open",
        KIND_LETTER => "letter",
        KIND_EDIT => "edit",
        KIND_DELETE => "delete",
        KIND_TRACK => "track",
        KIND_TRACK_WINDOW => "track_window",
        _ => "unknown",
    }
}

fn read_key(r: &mut Reader<'_>) -> Short<PublicKey> {
    Ok(PublicKey::from_array(r.array::<PUBLIC_KEY_SIZE>()?))
}

// Lines: u16 count, then each line -- item 16, size (u16 length plus UTF-8), qty u32, coins u64.
fn put_lines(b: &mut Vec<u8>, lines: &[Line]) -> Short<()> {
    if lines.len() > LINES_MAX {
        return Err("too many lines");
    }
    let n = u16::try_from(lines.len()).map_err(|_| "too many lines")?;
    write_u16(b, n);
    for l in lines {
        write_bytes(b, &l.item);
        put_string(b, &l.size, SIZE_MAX)?;
        write_u32(b, l.qty);
        write_u64(b, l.coins);
    }
    Ok(())
}

fn read_lines(r: &mut Reader<'_>) -> Short<Vec<Line>> {
    let n = usize::from(r.u16()?);
    if n > LINES_MAX {
        return Err("too many lines");
    }
    let mut out = Vec::with_capacity(n);
    for _ in 0..n {
        out.push(Line {
            item: r.array()?,
            size: r.string(SIZE_MAX)?,
            qty: r.u32()?,
            coins: r.u64()?,
        });
    }
    Ok(out)
}

impl Body {
    pub fn kind(&self) -> u8 {
        match self {
            Body::Genesis { .. } => KIND_GENESIS,
            Body::Invite { .. } => KIND_INVITE,
            Body::Join { .. } => KIND_JOIN,
            Body::Role { .. } => KIND_ROLE,
            Body::Dept { .. } => KIND_DEPT,
            Body::Assign { .. } => KIND_ASSIGN,
            Body::Remove { .. } => KIND_REMOVE,
            Body::Rekey { .. } => KIND_REKEY,
            Body::Revoke { .. } => KIND_REVOKE,
            Body::Offer { .. } => KIND_OFFER,
            Body::Profile { .. } => KIND_PROFILE,
            Body::PhoneBind { .. } => KIND_PHONE_BIND,
            Body::InvitePhone { .. } => KIND_INVITE_PHONE,
            Body::Salary { .. } => KIND_SALARY,
            Body::Pay { .. } => KIND_PAY,
            Body::Receipt { .. } => KIND_RECEIPT,
            Body::Redeem { .. } => KIND_REDEEM,
            Body::Fulfil { .. } => KIND_FULFIL,
            Body::ShiftOpen { .. } => KIND_SHIFT_OPEN,
            Body::ShiftClose { .. } => KIND_SHIFT_CLOSE,
            Body::ShiftConfirm { .. } => KIND_SHIFT_CONFIRM,
            Body::Item { .. } => KIND_ITEM,
            Body::Node { .. } => KIND_NODE,
            Body::NodeStaff { .. } => KIND_NODE_STAFF,
            Body::Order { .. } => KIND_ORDER,
            Body::Confirm { .. } => KIND_CONFIRM,
            Body::Pack { .. } => KIND_PACK,
            Body::Handoff { .. } => KIND_HANDOFF,
            Body::Accept { .. } => KIND_ACCEPT,
            Body::Scan { .. } => KIND_SCAN,
            Body::Shelf { .. } => KIND_SHELF,
            Body::Sale { .. } => KIND_SALE,
            Body::Issue { .. } => KIND_ISSUE,
            Body::Cancel { .. } => KIND_CANCEL,
            Body::Open { .. } => KIND_OPEN,
            Body::Letter { .. } => KIND_LETTER,
            Body::Edit { .. } => KIND_EDIT,
            Body::Delete { .. } => KIND_DELETE,
            Body::Track { .. } => KIND_TRACK,
            Body::TrackWindow { .. } => KIND_TRACK_WINDOW,
        }
    }

    pub fn chain(&self) -> u8 {
        chain_of(self.kind())
    }

    pub fn encode(&self) -> Short<Vec<u8>> {
        let mut b = Vec::new();
        match self {
            Body::Genesis {
                name,
                owner_key,
                owner_name,
                owner_card,
            } => {
                put_string(&mut b, name, NAME_MAX)?;
                write_bytes(&mut b, owner_key.as_bytes());
                put_string(&mut b, owner_name, NAME_MAX)?;
                put_string(&mut b, owner_card, CARD_MAX)?;
            },
            Body::Invite {
                invite_id,
                role,
                dept,
                expires_ms,
                phone_bound,
            } => {
                write_bytes(&mut b, invite_id);
                write_u8(&mut b, *role);
                write_bytes(&mut b, dept);
                write_u64(&mut b, *expires_ms);
                put_flag(&mut b, *phone_bound);
            },
            Body::Join {
                secret,
                key,
                name,
                card,
            } => {
                write_bytes(&mut b, secret);
                write_bytes(&mut b, key.as_bytes());
                put_string(&mut b, name, NAME_MAX)?;
                put_string(&mut b, card, CARD_MAX)?;
            },
            Body::Role { member, role } => {
                write_bytes(&mut b, member);
                write_u8(&mut b, *role);
            },
            Body::Dept {
                dept,
                name,
                archived,
            } => {
                write_bytes(&mut b, dept);
                put_string(&mut b, name, NAME_MAX)?;
                put_flag(&mut b, *archived);
            },
            Body::Assign {
                member,
                dept,
                title,
            } => {
                write_bytes(&mut b, member);
                write_bytes(&mut b, dept);
                put_string(&mut b, title, NAME_MAX)?;
            },
            Body::Remove { member } => write_bytes(&mut b, member),
            Body::Rekey { member, key } => {
                write_bytes(&mut b, member);
                write_bytes(&mut b, key.as_bytes());
            },
            Body::Revoke { invite_id } => write_bytes(&mut b, invite_id),
            Body::Offer {
                item,
                title,
                price,
                stock,
                active,
            } => {
                write_bytes(&mut b, item);
                put_string(&mut b, title, NAME_MAX)?;
                write_u64(&mut b, *price);
                write_u32(&mut b, *stock);
                put_flag(&mut b, *active);
            },
            Body::Profile { name, card } => {
                put_string(&mut b, name, NAME_MAX)?;
                put_string(&mut b, card, CARD_MAX)?;
            },
            Body::PhoneBind { attest } => write_bytes(&mut b, attest),
            Body::InvitePhone {
                invite_id,
                phone_hash,
            } => {
                write_bytes(&mut b, invite_id);
                write_bytes(&mut b, phone_hash);
            },
            Body::Salary {
                member,
                coins,
                period,
                from_ms,
            } => {
                write_bytes(&mut b, member);
                write_u64(&mut b, *coins);
                write_u8(&mut b, *period);
                write_u64(&mut b, *from_ms);
            },
            Body::Pay {
                member,
                pay_kind,
                period_key,
                coins,
                note,
            } => {
                write_bytes(&mut b, member);
                write_u8(&mut b, *pay_kind);
                write_u32(&mut b, *period_key);
                write_u64(&mut b, *coins);
                put_string(&mut b, note, NOTE_MAX)?;
            },
            Body::Receipt { record } => write_bytes(&mut b, record),
            Body::Redeem { item, qty, coins } => {
                write_bytes(&mut b, item);
                write_u32(&mut b, *qty);
                write_u64(&mut b, *coins);
            },
            Body::Fulfil { record } => write_bytes(&mut b, record),
            Body::ShiftOpen { node } => write_bytes(&mut b, node),
            Body::ShiftClose { note } => put_string(&mut b, note, NOTE_MAX)?,
            Body::ShiftConfirm { record } => write_bytes(&mut b, record),
            Body::Item {
                item,
                title,
                sizes,
                unit,
                active,
            } => {
                write_bytes(&mut b, item);
                put_string(&mut b, title, NAME_MAX)?;
                put_string(&mut b, sizes, NAME_MAX)?;
                put_string(&mut b, unit, UNIT_MAX)?;
                put_flag(&mut b, *active);
            },
            Body::Node {
                node,
                node_kind,
                name,
                place,
                active,
            } => {
                write_bytes(&mut b, node);
                write_u8(&mut b, *node_kind);
                put_string(&mut b, name, NAME_MAX)?;
                put_string(&mut b, place, NAME_MAX)?;
                put_flag(&mut b, *active);
            },
            Body::NodeStaff { node, member, on } => {
                write_bytes(&mut b, node);
                write_bytes(&mut b, member);
                put_flag(&mut b, *on);
            },
            Body::Order {
                from,
                to,
                lines,
                source,
                voice,
                text,
            } => {
                write_bytes(&mut b, from);
                write_bytes(&mut b, to);
                put_lines(&mut b, lines)?;
                write_u8(&mut b, *source);
                write_bytes(&mut b, voice);
                put_string(&mut b, text, TEXT_MAX)?;
            },
            Body::Confirm { node } => write_bytes(&mut b, node),
            Body::Pack { place, node, lines } => {
                write_bytes(&mut b, place);
                write_bytes(&mut b, node);
                put_lines(&mut b, lines)?;
            },
            Body::Handoff { place, from, to } => {
                write_bytes(&mut b, place);
                write_bytes(&mut b, from);
                write_bytes(&mut b, to);
            },
            Body::Accept {
                place,
                node,
                condition,
                lines,
                note,
                photo,
            } => {
                write_bytes(&mut b, place);
                write_bytes(&mut b, node);
                write_u8(&mut b, *condition);
                put_lines(&mut b, lines)?;
                put_string(&mut b, note, NOTE_MAX)?;
                write_bytes(&mut b, photo);
            },
            Body::Scan { place, node } => {
                write_bytes(&mut b, place);
                write_bytes(&mut b, node);
            },
            Body::Shelf { node, lines } | Body::Sale { node, lines } => {
                write_bytes(&mut b, node);
                put_lines(&mut b, lines)?;
            },
            Body::Issue {
                place,
                issue_kind,
                note,
            } => {
                write_bytes(&mut b, place);
                write_u8(&mut b, *issue_kind);
                put_string(&mut b, note, NOTE_MAX)?;
            },
            Body::Cancel { note } => put_string(&mut b, note, NOTE_MAX)?,
            Body::Open {
                chat_kind,
                name,
                dept,
                group,
            } => {
                write_u8(&mut b, *chat_kind);
                put_string(&mut b, name, NAME_MAX)?;
                write_bytes(&mut b, dept);
                write_bytes(&mut b, group);
            },
            Body::Letter {
                letter,
                mid,
                letter_kind,
            } => {
                write_bytes(&mut b, letter);
                put_string(&mut b, mid, MID_MAX)?;
                write_u8(&mut b, *letter_kind);
            },
            Body::Edit { record, letter } => {
                write_bytes(&mut b, record);
                write_bytes(&mut b, letter);
            },
            Body::Delete { record } => write_bytes(&mut b, record),
            Body::Track { on } => put_flag(&mut b, *on),
            Body::TrackWindow { window_s } => write_u32(&mut b, *window_s),
        }
        Ok(b)
    }

    pub fn decode(kind: u8, bytes: &[u8]) -> Short<Body> {
        let mut r = Reader::new(bytes);
        let body = match kind {
            KIND_GENESIS => Body::Genesis {
                name: r.string(NAME_MAX)?,
                owner_key: read_key(&mut r)?,
                owner_name: r.string(NAME_MAX)?,
                owner_card: r.string(CARD_MAX)?,
            },
            KIND_INVITE => Body::Invite {
                invite_id: r.array()?,
                role: r.u8()?,
                dept: r.array()?,
                expires_ms: r.u64()?,
                phone_bound: r.flag()?,
            },
            KIND_JOIN => Body::Join {
                secret: r.array()?,
                key: read_key(&mut r)?,
                name: r.string(NAME_MAX)?,
                card: r.string(CARD_MAX)?,
            },
            KIND_ROLE => Body::Role {
                member: r.array()?,
                role: r.u8()?,
            },
            KIND_DEPT => Body::Dept {
                dept: r.array()?,
                name: r.string(NAME_MAX)?,
                archived: r.flag()?,
            },
            KIND_ASSIGN => Body::Assign {
                member: r.array()?,
                dept: r.array()?,
                title: r.string(NAME_MAX)?,
            },
            KIND_REMOVE => Body::Remove { member: r.array()? },
            KIND_REKEY => Body::Rekey {
                member: r.array()?,
                key: read_key(&mut r)?,
            },
            KIND_REVOKE => Body::Revoke {
                invite_id: r.array()?,
            },
            KIND_OFFER => Body::Offer {
                item: r.array()?,
                title: r.string(NAME_MAX)?,
                price: r.u64()?,
                stock: r.u32()?,
                active: r.flag()?,
            },
            KIND_PROFILE => Body::Profile {
                name: r.string(NAME_MAX)?,
                card: r.string(CARD_MAX)?,
            },
            KIND_PHONE_BIND => Body::PhoneBind {
                attest: r.take(bytes.len())?.to_vec(),
            },
            KIND_INVITE_PHONE => Body::InvitePhone {
                invite_id: r.array()?,
                phone_hash: r.array()?,
            },
            KIND_SALARY => Body::Salary {
                member: r.array()?,
                coins: r.u64()?,
                period: r.u8()?,
                from_ms: r.u64()?,
            },
            KIND_PAY => Body::Pay {
                member: r.array()?,
                pay_kind: r.u8()?,
                period_key: r.u32()?,
                coins: r.u64()?,
                note: r.string(NOTE_MAX)?,
            },
            KIND_RECEIPT => Body::Receipt { record: r.array()? },
            KIND_REDEEM => Body::Redeem {
                item: r.array()?,
                qty: r.u32()?,
                coins: r.u64()?,
            },
            KIND_FULFIL => Body::Fulfil { record: r.array()? },
            KIND_SHIFT_OPEN => Body::ShiftOpen { node: r.array()? },
            KIND_SHIFT_CLOSE => Body::ShiftClose {
                note: r.string(NOTE_MAX)?,
            },
            KIND_SHIFT_CONFIRM => Body::ShiftConfirm { record: r.array()? },
            KIND_ITEM => Body::Item {
                item: r.array()?,
                title: r.string(NAME_MAX)?,
                sizes: r.string(NAME_MAX)?,
                unit: r.string(UNIT_MAX)?,
                active: r.flag()?,
            },
            KIND_NODE => Body::Node {
                node: r.array()?,
                node_kind: r.u8()?,
                name: r.string(NAME_MAX)?,
                place: r.string(NAME_MAX)?,
                active: r.flag()?,
            },
            KIND_NODE_STAFF => Body::NodeStaff {
                node: r.array()?,
                member: r.array()?,
                on: r.flag()?,
            },
            KIND_ORDER => Body::Order {
                from: r.array()?,
                to: r.array()?,
                lines: read_lines(&mut r)?,
                source: r.u8()?,
                voice: r.array()?,
                text: r.string(TEXT_MAX)?,
            },
            KIND_CONFIRM => Body::Confirm { node: r.array()? },
            KIND_PACK => Body::Pack {
                place: r.array()?,
                node: r.array()?,
                lines: read_lines(&mut r)?,
            },
            KIND_HANDOFF => Body::Handoff {
                place: r.array()?,
                from: r.array()?,
                to: r.array()?,
            },
            KIND_ACCEPT => Body::Accept {
                place: r.array()?,
                node: r.array()?,
                condition: r.u8()?,
                lines: read_lines(&mut r)?,
                note: r.string(NOTE_MAX)?,
                photo: r.array()?,
            },
            KIND_SCAN => Body::Scan {
                place: r.array()?,
                node: r.array()?,
            },
            KIND_SHELF => Body::Shelf {
                node: r.array()?,
                lines: read_lines(&mut r)?,
            },
            KIND_SALE => Body::Sale {
                node: r.array()?,
                lines: read_lines(&mut r)?,
            },
            KIND_ISSUE => Body::Issue {
                place: r.array()?,
                issue_kind: r.u8()?,
                note: r.string(NOTE_MAX)?,
            },
            KIND_CANCEL => Body::Cancel {
                note: r.string(NOTE_MAX)?,
            },
            KIND_OPEN => Body::Open {
                chat_kind: r.u8()?,
                name: r.string(NAME_MAX)?,
                dept: r.array()?,
                group: r.array()?,
            },
            KIND_LETTER => Body::Letter {
                letter: r.array()?,
                mid: r.string(MID_MAX)?,
                letter_kind: r.u8()?,
            },
            KIND_EDIT => Body::Edit {
                record: r.array()?,
                letter: r.array()?,
            },
            KIND_DELETE => Body::Delete { record: r.array()? },
            KIND_TRACK => Body::Track { on: r.flag()? },
            KIND_TRACK_WINDOW => Body::TrackWindow { window_s: r.u32()? },
            _ => return Err("unknown kind"),
        };
        if !r.done() {
            return Err("trailing bytes in the body");
        }
        Ok(body)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn key(seed: u8) -> PublicKey {
        let mut k = [0u8; PUBLIC_KEY_SIZE];
        for (i, b) in k.iter_mut().enumerate() {
            *b = (i as u8).wrapping_mul(seed).wrapping_add(1);
        }
        PublicKey::from_array(k)
    }

    fn every_kind() -> Vec<Body> {
        vec![
            Body::Genesis {
                name: "Org".into(),
                owner_key: key(3),
                owner_name: "Анна".into(),
                owner_card: "card".into(),
            },
            Body::Invite {
                invite_id: [1; 32],
                role: ROLE_EMPLOYEE,
                dept: [2; 16],
                expires_ms: 7,
                phone_bound: true,
            },
            Body::Join {
                secret: [3; 32],
                key: key(5),
                name: "n".into(),
                card: String::new(),
            },
            Body::Role {
                member: [4; 32],
                role: ROLE_MANAGER,
            },
            Body::Dept {
                dept: [5; 16],
                name: "Kitchen".into(),
                archived: false,
            },
            Body::Assign {
                member: [6; 32],
                dept: [7; 16],
                title: "Barista".into(),
            },
            Body::Remove { member: [8; 32] },
            Body::Rekey {
                member: [9; 32],
                key: key(7),
            },
            Body::Revoke {
                invite_id: [10; 32],
            },
            Body::Offer {
                item: [11; 16],
                title: "Latte".into(),
                price: 30,
                stock: 5,
                active: true,
            },
            Body::Profile {
                name: "n".into(),
                card: "c".into(),
            },
            Body::PhoneBind {
                attest: vec![1, 2, 3],
            },
            Body::InvitePhone {
                invite_id: [12; 32],
                phone_hash: [13; 32],
            },
            Body::Salary {
                member: [14; 32],
                coins: 100,
                period: 1,
                from_ms: 9,
            },
            Body::Pay {
                member: [15; 32],
                pay_kind: PAY_SALARY,
                period_key: 0x1000_0001,
                coins: 100,
                note: "Oct".into(),
            },
            Body::Receipt { record: [16; 32] },
            Body::Redeem {
                item: [17; 16],
                qty: 2,
                coins: 60,
            },
            Body::Fulfil { record: [18; 32] },
            Body::ShiftOpen { node: [49; 16] },
            Body::ShiftClose {
                note: "closed the till".into(),
            },
            Body::ShiftConfirm { record: [50; 32] },
            Body::Item {
                item: [19; 16],
                title: "Sneaker".into(),
                sizes: "40,41,42".into(),
                unit: "pair".into(),
                active: true,
            },
            Body::Node {
                node: [20; 16],
                node_kind: NODE_WAREHOUSE,
                name: "North".into(),
                place: "Tverskaya 1".into(),
                active: true,
            },
            Body::NodeStaff {
                node: [21; 16],
                member: [22; 32],
                on: true,
            },
            Body::Order {
                from: [23; 16],
                to: [24; 16],
                lines: vec![
                    Line {
                        item: [25; 16],
                        size: "42".into(),
                        qty: 2,
                        coins: 900,
                    },
                    Line {
                        item: [26; 16],
                        size: String::new(),
                        qty: 1,
                        coins: 0,
                    },
                ],
                source: SOURCE_VOICE,
                voice: [27; 32],
                text: "две пары".into(),
            },
            Body::Confirm { node: [28; 16] },
            Body::Pack {
                place: [29; 16],
                node: [30; 16],
                lines: Vec::new(),
            },
            Body::Handoff {
                place: [31; 16],
                from: [32; 16],
                to: [33; 16],
            },
            Body::Accept {
                place: [34; 16],
                node: [35; 16],
                condition: ACCEPT_SHORT,
                lines: vec![Line {
                    item: [36; 16],
                    size: "41".into(),
                    qty: 1,
                    coins: 0,
                }],
                note: "one missing".into(),
                photo: [37; 32],
            },
            Body::Scan {
                place: [38; 16],
                node: [39; 16],
            },
            Body::Shelf {
                node: [40; 16],
                lines: Vec::new(),
            },
            Body::Sale {
                node: [41; 16],
                lines: Vec::new(),
            },
            Body::Issue {
                place: [42; 16],
                issue_kind: ISSUE_DEFECT,
                note: "sole".into(),
            },
            Body::Cancel {
                note: "late".into(),
            },
            Body::Open {
                chat_kind: CHAT_CHANNEL,
                name: "News".into(),
                dept: [43; 16],
                group: [48; 16],
            },
            Body::Letter {
                letter: [44; 32],
                mid: "0198a2b3c4d5".into(),
                letter_kind: 1,
            },
            Body::Edit {
                record: [45; 32],
                letter: [46; 32],
            },
            Body::Delete { record: [47; 32] },
            Body::Track { on: true },
            Body::TrackWindow { window_s: 1800 },
        ]
    }

    #[test]
    fn every_kind_roundtrips_and_refuses_a_trailing_byte() {
        for body in every_kind() {
            let bytes = body.encode().expect("encodes");
            assert_eq!(Body::decode(body.kind(), &bytes), Ok(body.clone()));
            if body.kind() != KIND_PHONE_BIND {
                let mut longer = bytes.clone();
                longer.push(0);
                assert!(Body::decode(body.kind(), &longer).is_err());
            }
        }
    }

    #[test]
    fn every_kind_has_its_own_word() {
        let words: Vec<&str> = every_kind().iter().map(|b| kind_name(b.kind())).collect();
        assert!(!words.contains(&"unknown"));
        let mut unique = words.clone();
        unique.sort_unstable();
        unique.dedup();
        assert_eq!(unique.len(), words.len());
        assert_eq!(kind_name(0x0F), "unknown");
    }

    #[test]
    fn chains_follow_the_kind() {
        for body in every_kind() {
            let expect = match body.kind() {
                0x00..=0x1F => CHAIN_R,
                0x20..=0x3F => CHAIN_H,
                0x40..=0x5F => CHAIN_S,
                _ => CHAIN_C,
            };
            assert_eq!(body.chain(), expect);
        }
        assert!(Body::decode(0x0F, &[]).is_err());
        assert!(Body::decode(0x28, &[]).is_err());
        assert!(Body::decode(0x4B, &[]).is_err());
        assert!(Body::decode(0x65, &[]).is_err());
    }
}
