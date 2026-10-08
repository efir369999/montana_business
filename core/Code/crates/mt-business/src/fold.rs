use std::collections::{BTreeMap, BTreeSet};

use mt_crypto::{hash, sha256_raw, PublicKey};

use crate::attest::open_with;
use crate::body::{
    Body, Dept, KIND_GENESIS, KIND_REKEY, PAY_BONUS, PAY_SALARY, ROLE_ADMIN, ROLE_EMPLOYEE,
    ROLE_MANAGER, ROLE_OWNER, ZERO16,
};
use crate::chat::Chats;
use crate::domain::{DOMAIN_INVITE, DOMAIN_MEMBER, DOMAIN_PHONE, DOMAIN_SHIFT};
use crate::frame::{verify_record, Header, Id, Record, CHAIN_C, CHAIN_R, CHAIN_S, ZERO32};
use crate::period::{self, PERIOD_DAY, PERIOD_MONTH, PERIOD_WEEK};
use crate::supply::Supply;

pub const FUTURE_SLACK_MS: u64 = 5 * 60 * 1000;

pub fn member_id_of(key: &PublicKey) -> [u8; 32] {
    hash(DOMAIN_MEMBER, &[key.as_bytes()])
}

pub fn invite_id_of(secret: &[u8; 32]) -> [u8; 32] {
    hash(DOMAIN_INVITE, &[secret])
}

pub fn phone_hash_of(org: &[u8; 32], e164: &str) -> [u8; 32] {
    hash(DOMAIN_PHONE, &[org, e164.as_bytes()])
}

// The lane of one member's shifts: in chain H, apart from the lane of their pay, so a manager of their department may hold
// the shifts without the pay.
pub fn shift_lane_of(member: &[u8; 32]) -> [u8; 32] {
    hash(DOMAIN_SHIFT, &[member])
}

pub fn subject_of(key: &PublicKey) -> [u8; 32] {
    sha256_raw(key.as_bytes())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Outcome {
    Accepted,
    Rejected(&'static str),
    Waiting,
}

#[derive(Clone, Debug)]
pub struct Meta {
    pub at_ms: u64,
    pub chain: u8,
    pub kind: u8,
    pub author: [u8; 32],
    pub lane: [u8; 32],
    pub outcome: Outcome,
}

#[derive(Clone, Debug)]
pub struct Org {
    pub id: Id,
    pub name: String,
    pub created_ms: u64,
}

#[derive(Clone, Debug)]
pub struct Member {
    pub key: PublicKey,
    pub name: String,
    pub card: String,
    pub role: u8,
    pub dept: Dept,
    pub title: String,
    pub removed: bool,
    pub joined_ms: u64,
    pub invite: Option<[u8; 32]>,
    pub phone_subject: Option<[u8; 32]>,
    pub phone_hash: Option<[u8; 32]>,
    // The number of the accepted PhoneBind: shown only to those who hold the member's lane of H.
    pub phone: Option<String>,
    pub rekeyed: bool,
}

impl Member {
    pub fn phone_confirmed(&self) -> bool {
        self.phone_subject == Some(subject_of(&self.key))
    }

    pub fn pending_device(&self) -> bool {
        !self.removed && self.rekeyed && self.phone_hash.is_some() && !self.phone_confirmed()
    }
}

#[derive(Clone, Debug)]
pub struct Invite {
    pub role: u8,
    pub dept: Dept,
    pub expires_ms: u64,
    pub phone_bound: bool,
    pub used: bool,
    pub revoked: bool,
}

#[derive(Clone, Debug)]
pub struct DeptInfo {
    pub name: String,
    pub archived: bool,
}

#[derive(Clone, Debug)]
pub struct Offer {
    pub title: String,
    pub price: u64,
    pub counted: bool,
    pub remaining: u32,
    pub active: bool,
}

#[derive(Clone, Debug)]
pub struct Salary {
    pub coins: u64,
    pub period: u8,
    pub from_ms: u64,
}

#[derive(Clone, Debug)]
pub struct Pay {
    pub record: Id,
    pub at_ms: u64,
    pub member: [u8; 32],
    pub pay_kind: u8,
    pub period_key: u32,
    pub coins: u64,
}

#[derive(Clone, Debug)]
pub struct Redeem {
    pub record: Id,
    pub at_ms: u64,
    pub member: [u8; 32],
    pub item: Dept,
    pub qty: u32,
    pub coins: u64,
    pub fulfilled: bool,
}

#[derive(Clone, Debug)]
pub struct Shift {
    pub member: [u8; 32],
    pub node: Dept,
    pub open_record: Id,
    pub open_ms: u64,
    pub close_record: Option<Id>,
    pub close_ms: Option<u64>,
    pub note: String,
    pub confirmed_by: Option<[u8; 32]>,
}

#[derive(Default)]
pub struct Fold {
    pub org: Option<Org>,
    pub owner: Option<[u8; 32]>,
    pub members: BTreeMap<[u8; 32], Member>,
    pub depts: BTreeMap<Dept, DeptInfo>,
    pub invites: BTreeMap<[u8; 32], Invite>,
    pub invite_phone: BTreeMap<[u8; 32], [u8; 32]>,
    pub offers: BTreeMap<Dept, Offer>,
    pub salary: BTreeMap<[u8; 32], Salary>,
    // Every Salary record of a member in the order the fold met them: each owes its own periods until a later one takes over.
    salaries: BTreeMap<[u8; 32], Vec<Salary>>,
    pub pays: Vec<Pay>,
    pub redeems: Vec<Redeem>,
    pub receipts: BTreeSet<Id>,
    pub records: BTreeMap<Id, Meta>,
    pub head: [u8; 32],
    pub supply: Supply,
    pub chats: Chats,
    pub shifts: Vec<Shift>,
    open_shift: BTreeMap<[u8; 32], usize>,
    shift_at: BTreeMap<Id, usize>,
    paid: BTreeSet<([u8; 32], u32)>,
    pay_at: BTreeMap<Id, usize>,
    redeem_at: BTreeMap<Id, usize>,
    service: Option<PublicKey>,
}

struct Entry<'a> {
    rec: &'a Record,
    twins: Vec<&'a Record>,
    right_slot: bool,
}

fn genesis_key(rec: &Record) -> Option<PublicKey> {
    let h = &rec.header;
    if h.chain != CHAIN_R
        || h.kind != KIND_GENESIS
        || h.org != ZERO32
        || h.n != 0
        || h.prev != ZERO32
        || h.lane != h.author
    {
        return None;
    }
    match Body::decode(KIND_GENESIS, &rec.body) {
        Ok(Body::Genesis { owner_key, .. }) if member_id_of(&owner_key) == h.author => {
            Some(owner_key)
        },
        _ => None,
    }
}

// The org of a roster is the genesis the viewer has signed into: a record that verifies with the
// viewer's own key names it, and nobody else can make one. Only a viewer who has signed nothing
// yet (a newcomer reading the roster the inviter handed over) falls back to the earliest genesis.
fn choose_genesis(entries: &BTreeMap<Id, Entry<'_>>, viewer: Option<&PublicKey>) -> Option<Id> {
    let mut candidates: Vec<(u64, Id)> = Vec::new();
    for (id, e) in entries {
        if !e.right_slot {
            continue;
        }
        if let Some(key) = genesis_key(e.rec) {
            if e.twins.iter().any(|t| verify_record(t, &key)) {
                candidates.push((e.rec.header.at_ms, *id));
            }
        }
    }
    candidates.sort();
    let Some(viewer) = viewer else {
        return candidates.first().map(|c| c.1);
    };
    let mut authors: BTreeSet<[u8; 32]> = BTreeSet::new();
    authors.insert(member_id_of(viewer));
    for e in entries.values() {
        if e.rec.header.kind == KIND_REKEY {
            if let Ok(Body::Rekey { member, key }) = Body::decode(KIND_REKEY, &e.rec.body) {
                if &key == viewer {
                    authors.insert(member);
                }
            }
        }
    }
    let mut anchored: BTreeSet<Id> = BTreeSet::new();
    for (id, e) in entries {
        let h = &e.rec.header;
        if !authors.contains(&h.author) || !e.twins.iter().any(|t| verify_record(t, viewer)) {
            continue;
        }
        anchored.insert(if h.org == ZERO32 { *id } else { h.org });
    }
    if anchored.is_empty() {
        return candidates.first().map(|c| c.1);
    }
    candidates
        .into_iter()
        .find(|c| anchored.contains(&c.1))
        .map(|c| c.1)
}

fn structural(
    e: &Entry<'_>,
    entries: &BTreeMap<Id, Entry<'_>>,
    status: &BTreeMap<Id, Outcome>,
    org_id: &Id,
    now_ms: u64,
) -> Outcome {
    let h = &e.rec.header;
    if !e.right_slot {
        return Outcome::Rejected("wrong_chain");
    }
    let future = h.at_ms > now_ms.saturating_add(FUTURE_SLACK_MS);
    if h.kind == KIND_GENESIS && h.org == ZERO32 {
        if &e.rec.id != org_id {
            return Outcome::Rejected("second_genesis");
        }
        return if future {
            Outcome::Waiting
        } else {
            Outcome::Accepted
        };
    }
    if &h.org != org_id {
        return Outcome::Rejected("foreign_org");
    }
    if h.n == 0 {
        if h.prev != ZERO32 {
            return Outcome::Rejected("bad_link");
        }
    } else {
        let Some(p) = entries.get(&h.prev) else {
            return Outcome::Waiting;
        };
        let ph = &p.rec.header;
        // The owner's lane opens with the genesis, whose org field is zero.
        let same_org = ph.org == h.org || (&p.rec.id == org_id && ph.org == ZERO32);
        if ph.chain != h.chain || !same_org || ph.lane != h.lane || ph.n.checked_add(1) != Some(h.n)
        {
            return Outcome::Rejected("bad_link");
        }
        match status.get(&h.prev) {
            Some(Outcome::Accepted) => {},
            Some(Outcome::Rejected(_)) => return Outcome::Rejected("broken_lane"),
            Some(Outcome::Waiting) | None => return Outcome::Waiting,
        }
        if h.at_ms <= ph.at_ms {
            return Outcome::Rejected("time_back");
        }
    }
    if future {
        Outcome::Waiting
    } else {
        Outcome::Accepted
    }
}

pub fn fold(
    roster: &[Record],
    hr: &[Record],
    now_ms: u64,
    viewer: Option<&PublicKey>,
    service: Option<&PublicKey>,
) -> Fold {
    let mut entries: BTreeMap<Id, Entry<'_>> = BTreeMap::new();
    // The roster holds chain R; the private stream holds the rest: H, and the lanes of S and C.
    for (private, set) in [(false, roster), (true, hr)] {
        for rec in set {
            let e = entries.entry(rec.id).or_insert_with(|| Entry {
                rec,
                twins: Vec::new(),
                right_slot: false,
            });
            if (rec.header.chain != CHAIN_R) == private {
                e.right_slot = true;
            }
            if !e.twins.iter().any(|t| t.bytes == rec.bytes) {
                e.twins.push(rec);
            }
        }
    }
    let mut f = Fold {
        service: service.cloned(),
        ..Fold::default()
    };
    let mut cat = Vec::with_capacity(entries.len().saturating_mul(32));
    for id in entries.keys() {
        cat.extend_from_slice(id);
    }
    f.head = sha256_raw(&cat);

    let mut status: BTreeMap<Id, Outcome> = BTreeMap::new();
    match choose_genesis(&entries, viewer) {
        None => {
            for (id, e) in &entries {
                let s = if e.right_slot {
                    Outcome::Waiting
                } else {
                    Outcome::Rejected("wrong_chain")
                };
                status.insert(*id, s);
            }
        },
        Some(org_id) => {
            let mut by_n: Vec<&Entry<'_>> = entries.values().collect();
            by_n.sort_by_key(|e| (e.rec.header.n, e.rec.id));
            for e in by_n {
                let s = structural(e, &entries, &status, &org_id, now_ms);
                status.insert(e.rec.id, s);
            }
            if status.get(&org_id) == Some(&Outcome::Waiting) {
                for s in status.values_mut() {
                    if *s == Outcome::Accepted {
                        *s = Outcome::Waiting;
                    }
                }
            }
            let mut ready: Vec<&Entry<'_>> = entries
                .values()
                .filter(|e| status.get(&e.rec.id) == Some(&Outcome::Accepted))
                .collect();
            ready.sort_by_key(|e| (e.rec.header.at_ms, e.rec.id));
            for e in ready {
                let out = match f.apply(e.rec, &e.twins) {
                    Ok(()) => Outcome::Accepted,
                    Err(reason) => Outcome::Rejected(reason),
                };
                status.insert(e.rec.id, out);
            }
        },
    }
    for (id, e) in &entries {
        let h = &e.rec.header;
        f.records.insert(
            *id,
            Meta {
                at_ms: h.at_ms,
                chain: h.chain,
                kind: h.kind,
                author: h.author,
                lane: h.lane,
                outcome: status.get(id).copied().unwrap_or(Outcome::Waiting),
            },
        );
    }
    f
}

impl Fold {
    pub fn member_by_key(&self, key: &PublicKey) -> Option<[u8; 32]> {
        self.members
            .iter()
            .find(|(_, m)| &m.key == key)
            .map(|(id, _)| *id)
    }

    pub fn active_role(&self, member: &[u8; 32]) -> Option<u8> {
        self.members
            .get(member)
            .filter(|m| !m.removed)
            .map(|m| m.role)
    }

    pub fn outcome(&self, id: &Id) -> Option<Outcome> {
        self.records.get(id).map(|m| m.outcome)
    }

    pub fn pay_confirmed(&self, record: &Id) -> bool {
        self.receipts.contains(record)
    }

    // Finished periods without a salary Pay, for every active member. A new salary never erases what an earlier one still owes:
    // each Salary record owes its own finished periods, from its from_ms until the earliest from_ms of a record written after
    // it (the period holding that moment is the later record's), so a raise before the payroll keeps the old days due at the
    // old rate, and a correction dated back takes over from its own start.
    pub fn due(&self, now_ms: u64) -> Vec<([u8; 32], u64, u32)> {
        let mut out = Vec::new();
        for (member, terms) in &self.salaries {
            if self.active_role(member).is_none() {
                continue;
            }
            for (i, s) in terms.iter().enumerate() {
                if s.coins == 0 {
                    continue;
                }
                let until = terms.iter().skip(i + 1).map(|t| t.from_ms).min();
                for key in period::owed_keys(s.period, s.from_ms, until, now_ms) {
                    if !self.paid.contains(&(*member, key)) {
                        out.push((*member, s.coins, key));
                    }
                }
            }
        }
        out.sort_by_key(|d| (d.0, d.2));
        out
    }

    fn key_in_use(&self, key: &PublicKey, except: &[u8; 32]) -> bool {
        self.members
            .iter()
            .any(|(id, m)| id != except && &m.key == key)
    }

    fn dept_usable(&self, dept: &Dept) -> Result<(), &'static str> {
        if dept == &ZERO16 {
            return Ok(());
        }
        match self.depts.get(dept) {
            Some(d) if !d.archived => Ok(()),
            _ => Err("unknown_dept"),
        }
    }

    fn set_role(&mut self, member: &[u8; 32], role: u8) {
        if let Some(m) = self.members.get_mut(member) {
            m.role = role;
        }
    }

    fn apply(&mut self, rec: &Record, twins: &[&Record]) -> Result<(), &'static str> {
        let h = &rec.header;
        let body = Body::decode(h.kind, &rec.body).map_err(|_| "bad_body")?;
        if body.chain() != h.chain {
            return Err("bad_body");
        }
        let signer = match &body {
            Body::Genesis { owner_key, .. } => owner_key.clone(),
            Body::Join { key, .. } => {
                if member_id_of(key) != h.author {
                    return Err("bad_author");
                }
                key.clone()
            },
            _ => match self.members.get(&h.author) {
                Some(m) => m.key.clone(),
                None => return Err("unknown_author"),
            },
        };
        if !twins.iter().any(|t| verify_record(t, &signer)) {
            return Err("bad_signature");
        }
        if h.chain == CHAIN_R && h.lane != h.author {
            return Err("bad_lane");
        }
        match body {
            Body::Genesis {
                name,
                owner_key,
                owner_name,
                owner_card,
            } => {
                if self.org.is_some() {
                    return Err("second_genesis");
                }
                self.org = Some(Org {
                    id: rec.id,
                    name,
                    created_ms: h.at_ms,
                });
                self.owner = Some(h.author);
                self.members.insert(
                    h.author,
                    Member {
                        key: owner_key,
                        name: owner_name,
                        card: owner_card,
                        role: ROLE_OWNER,
                        dept: ZERO16,
                        title: String::new(),
                        removed: false,
                        joined_ms: h.at_ms,
                        invite: None,
                        phone_subject: None,
                        phone_hash: None,
                        phone: None,
                        rekeyed: false,
                    },
                );
                Ok(())
            },
            Body::Join {
                secret,
                key,
                name,
                card,
            } => self.join(h, secret, key, name, card),
            other => {
                let (role, dept) = match self.members.get(&h.author) {
                    Some(m) if !m.removed => (m.role, m.dept),
                    _ => return Err("not_active"),
                };
                self.rule(rec.id, h, other, role, dept)
            },
        }
    }

    fn join(
        &mut self,
        h: &Header,
        secret: [u8; 32],
        key: PublicKey,
        name: String,
        card: String,
    ) -> Result<(), &'static str> {
        let invite_id = invite_id_of(&secret);
        let inv = self.invites.get(&invite_id).ok_or("unknown_invite")?;
        if inv.revoked {
            return Err("invite_revoked");
        }
        if inv.used {
            return Err("invite_used");
        }
        if h.at_ms > inv.expires_ms {
            return Err("invite_expired");
        }
        let (role, dept) = (inv.role, inv.dept);
        if self.active_role(&h.author).is_some() {
            return Err("already_member");
        }
        if self.key_in_use(&key, &h.author) {
            return Err("key_in_use");
        }
        if let Some(inv) = self.invites.get_mut(&invite_id) {
            inv.used = true;
        }
        self.members.insert(
            h.author,
            Member {
                key,
                name,
                card,
                role,
                dept,
                title: String::new(),
                removed: false,
                joined_ms: h.at_ms,
                invite: Some(invite_id),
                phone_subject: None,
                phone_hash: None,
                phone: None,
                rekeyed: false,
            },
        );
        Ok(())
    }

    fn rule(
        &mut self,
        id: Id,
        h: &Header,
        body: Body,
        role: u8,
        my_dept: Dept,
    ) -> Result<(), &'static str> {
        let author = h.author;
        let boss = role <= ROLE_ADMIN;
        match body.chain() {
            CHAIN_S => return self.supply.apply(id, h, body, role),
            CHAIN_C => {
                if let Body::Open { dept, .. } = &body {
                    self.dept_usable(dept)?;
                }
                let me = self.members.get(&author).cloned().ok_or("unknown_author")?;
                return self.chats.apply(id, h, body, &me);
            },
            _ => {},
        }
        match body {
            Body::Genesis { .. } | Body::Join { .. } => Err("bad_body"),
            body @ (Body::Item { .. } | Body::Node { .. } | Body::NodeStaff { .. }) => {
                if !boss {
                    return Err("denied");
                }
                self.supply.catalogue(body, &self.members)
            },
            Body::Invite {
                invite_id,
                role: r,
                dept,
                expires_ms,
                phone_bound,
            } => {
                if !(ROLE_ADMIN..=ROLE_EMPLOYEE).contains(&r) {
                    return Err("bad_role");
                }
                let allowed = match role {
                    ROLE_OWNER => true,
                    ROLE_ADMIN => r >= ROLE_MANAGER,
                    ROLE_MANAGER => r == ROLE_EMPLOYEE && my_dept != ZERO16 && dept == my_dept,
                    _ => false,
                };
                if !allowed {
                    return Err("denied");
                }
                self.dept_usable(&dept)?;
                if self.invites.contains_key(&invite_id) {
                    return Err("duplicate_invite");
                }
                self.invites.insert(
                    invite_id,
                    Invite {
                        role: r,
                        dept,
                        expires_ms,
                        phone_bound,
                        used: false,
                        revoked: false,
                    },
                );
                Ok(())
            },
            Body::Role { member, role: r } => {
                if r > ROLE_EMPLOYEE {
                    return Err("bad_role");
                }
                let target = self.active_role(&member).ok_or("unknown_member")?;
                match role {
                    ROLE_OWNER => {
                        if member == author {
                            return Err("bad_target");
                        }
                        self.set_role(&member, r);
                        if r == ROLE_OWNER {
                            self.set_role(&author, ROLE_ADMIN);
                            self.owner = Some(member);
                        }
                        Ok(())
                    },
                    ROLE_ADMIN if target >= ROLE_MANAGER && r >= ROLE_MANAGER => {
                        self.set_role(&member, r);
                        Ok(())
                    },
                    _ => Err("denied"),
                }
            },
            Body::Dept {
                dept,
                name,
                archived,
            } => {
                if !boss {
                    return Err("denied");
                }
                if dept == ZERO16 {
                    return Err("bad_dept");
                }
                self.depts.insert(dept, DeptInfo { name, archived });
                Ok(())
            },
            Body::Assign {
                member,
                dept,
                title,
            } => {
                let (t_role, t_dept) = match self.members.get(&member) {
                    Some(m) if !m.removed => (m.role, m.dept),
                    _ => return Err("unknown_member"),
                };
                let allowed = match role {
                    ROLE_OWNER => true,
                    ROLE_ADMIN => member == author || t_role >= ROLE_MANAGER,
                    ROLE_MANAGER => {
                        my_dept != ZERO16
                            && t_role == ROLE_EMPLOYEE
                            && t_dept == my_dept
                            && dept == my_dept
                    },
                    _ => false,
                };
                if !allowed {
                    return Err("denied");
                }
                self.dept_usable(&dept)?;
                if let Some(m) = self.members.get_mut(&member) {
                    m.dept = dept;
                    m.title = title;
                }
                Ok(())
            },
            Body::Remove { member } => {
                let target = self.active_role(&member).ok_or("unknown_member")?;
                match role {
                    ROLE_OWNER if member == author => return Err("bad_target"),
                    ROLE_OWNER => {},
                    ROLE_ADMIN if target >= ROLE_MANAGER => {},
                    _ => return Err("denied"),
                }
                if let Some(m) = self.members.get_mut(&member) {
                    m.removed = true;
                }
                Ok(())
            },
            Body::Rekey { member, key } => {
                let target = self.active_role(&member).ok_or("unknown_member")?;
                let allowed = match role {
                    ROLE_OWNER => true,
                    ROLE_ADMIN => member == author || target >= ROLE_MANAGER,
                    _ => false,
                };
                if !allowed {
                    return Err("denied");
                }
                if self.key_in_use(&key, &member) {
                    return Err("key_in_use");
                }
                if let Some(m) = self.members.get_mut(&member) {
                    m.key = key;
                    m.rekeyed = true;
                }
                Ok(())
            },
            Body::Revoke { invite_id } => {
                if !boss {
                    return Err("denied");
                }
                let inv = self.invites.get_mut(&invite_id).ok_or("unknown_invite")?;
                if inv.used {
                    return Err("invite_used");
                }
                if inv.revoked {
                    return Err("invite_revoked");
                }
                inv.revoked = true;
                Ok(())
            },
            Body::Offer {
                item,
                title,
                price,
                stock,
                active,
            } => {
                if !boss {
                    return Err("denied");
                }
                if item == ZERO16 {
                    return Err("bad_item");
                }
                self.offers.insert(
                    item,
                    Offer {
                        title,
                        price,
                        counted: stock > 0,
                        remaining: stock,
                        active,
                    },
                );
                Ok(())
            },
            Body::Profile { name, card } => {
                if let Some(m) = self.members.get_mut(&author) {
                    m.name = name;
                    m.card = card;
                }
                Ok(())
            },
            Body::PhoneBind { attest } => {
                if h.lane != author {
                    return Err("bad_lane");
                }
                let service = self.service.as_ref().ok_or("bad_attest")?;
                let a = open_with(service, &attest).map_err(|_| "bad_attest")?;
                let org = self.org.as_ref().map(|o| o.id).ok_or("unknown_author")?;
                let m = self.members.get_mut(&author).ok_or("unknown_author")?;
                if a.subject != subject_of(&m.key) {
                    return Err("attest_subject");
                }
                m.phone_subject = Some(a.subject);
                m.phone_hash = Some(phone_hash_of(&org, &a.e164));
                m.phone = Some(a.e164);
                Ok(())
            },
            Body::InvitePhone {
                invite_id,
                phone_hash,
            } => {
                if h.lane != invite_id {
                    return Err("bad_lane");
                }
                if !boss {
                    return Err("denied");
                }
                if !self.invites.contains_key(&invite_id) {
                    return Err("unknown_invite");
                }
                self.invite_phone.insert(invite_id, phone_hash);
                Ok(())
            },
            Body::Salary {
                member,
                coins,
                period,
                from_ms,
            } => {
                if h.lane != member {
                    return Err("bad_lane");
                }
                if !boss {
                    return Err("denied");
                }
                if self.active_role(&member).is_none() {
                    return Err("unknown_member");
                }
                if !matches!(period, PERIOD_DAY | PERIOD_WEEK | PERIOD_MONTH) {
                    return Err("bad_period");
                }
                let terms = Salary {
                    coins,
                    period,
                    from_ms,
                };
                self.salaries.entry(member).or_default().push(terms.clone());
                self.salary.insert(member, terms);
                Ok(())
            },
            Body::Pay {
                member,
                pay_kind,
                period_key,
                coins,
                ..
            } => {
                if h.lane != member {
                    return Err("bad_lane");
                }
                if !boss {
                    return Err("denied");
                }
                if !self.members.contains_key(&member) {
                    return Err("unknown_member");
                }
                if coins == 0 {
                    return Err("zero_coins");
                }
                match pay_kind {
                    PAY_SALARY => {
                        if !period::key_valid(period_key) {
                            return Err("bad_period");
                        }
                        if !self.paid.insert((member, period_key)) {
                            return Err("period_paid");
                        }
                    },
                    PAY_BONUS => {},
                    _ => return Err("bad_pay_kind"),
                }
                self.pay_at.insert(id, self.pays.len());
                self.pays.push(Pay {
                    record: id,
                    at_ms: h.at_ms,
                    member,
                    pay_kind,
                    period_key,
                    coins,
                });
                Ok(())
            },
            Body::Receipt { record } => {
                if self.receipts.contains(&record) {
                    return Err("duplicate_receipt");
                }
                let pay = self.pay_at.get(&record).and_then(|i| self.pays.get(*i));
                let redeem = self
                    .redeem_at
                    .get(&record)
                    .and_then(|i| self.redeems.get(*i));
                match (pay, redeem) {
                    // The payee's book took the coin letter: only the payee can say so.
                    (Some(p), _) => {
                        if h.lane != p.member {
                            return Err("bad_lane");
                        }
                        if author != p.member {
                            return Err("denied");
                        }
                    },
                    // A purchase is paid to the owner: only the owner's book can confirm it.
                    (None, Some(r)) => {
                        if h.lane != r.member {
                            return Err("bad_lane");
                        }
                        if self.owner != Some(author) {
                            return Err("denied");
                        }
                    },
                    (None, None) => return Err("unknown_record"),
                }
                self.receipts.insert(record);
                Ok(())
            },
            Body::Redeem { item, qty, coins } => {
                if h.lane != author {
                    return Err("bad_lane");
                }
                if qty == 0 {
                    return Err("bad_qty");
                }
                let offer = self.offers.get_mut(&item).ok_or("unknown_offer")?;
                if !offer.active {
                    return Err("offer_inactive");
                }
                if offer.price.checked_mul(u64::from(qty)) != Some(coins) {
                    return Err("bad_price");
                }
                if offer.counted {
                    if offer.remaining < qty {
                        return Err("out_of_stock");
                    }
                    offer.remaining -= qty;
                    // A counted offer that runs out closes; stock 0 alone would read as "no count".
                    if offer.remaining == 0 {
                        offer.active = false;
                    }
                }
                self.redeem_at.insert(id, self.redeems.len());
                self.redeems.push(Redeem {
                    record: id,
                    at_ms: h.at_ms,
                    member: author,
                    item,
                    qty,
                    coins,
                    fulfilled: false,
                });
                Ok(())
            },
            Body::ShiftOpen { node } => {
                if h.lane != shift_lane_of(&author) {
                    return Err("bad_lane");
                }
                if self.open_shift.contains_key(&author) {
                    return Err("shift_open");
                }
                if node != ZERO16 && !self.supply.catalogue.nodes.contains_key(&node) {
                    return Err("unknown_node");
                }
                self.open_shift.insert(author, self.shifts.len());
                self.shifts.push(Shift {
                    member: author,
                    node,
                    open_record: id,
                    open_ms: h.at_ms,
                    close_record: None,
                    close_ms: None,
                    note: String::new(),
                    confirmed_by: None,
                });
                Ok(())
            },
            Body::ShiftClose { note } => {
                if h.lane != shift_lane_of(&author) {
                    return Err("bad_lane");
                }
                let at = *self.open_shift.get(&author).ok_or("no_open_shift")?;
                let s = self.shifts.get_mut(at).ok_or("no_open_shift")?;
                s.close_record = Some(id);
                s.close_ms = Some(h.at_ms);
                s.note = note;
                self.shift_at.insert(id, at);
                self.open_shift.retain(|m, _| m != &author);
                Ok(())
            },
            // A shift is confirmed by somebody else: an administrator, or the manager of the member's own department.
            Body::ShiftConfirm { record } => {
                let at = *self.shift_at.get(&record).ok_or("unknown_record")?;
                let (member, confirmed) = match self.shifts.get(at) {
                    Some(s) => (s.member, s.confirmed_by.is_some()),
                    None => return Err("unknown_record"),
                };
                if h.lane != shift_lane_of(&member) {
                    return Err("bad_lane");
                }
                if member == author {
                    return Err("denied");
                }
                let their_dept = self.members.get(&member).map_or(ZERO16, |m| m.dept);
                if !(boss || (role == ROLE_MANAGER && my_dept != ZERO16 && my_dept == their_dept)) {
                    return Err("denied");
                }
                if confirmed {
                    return Err("already_confirmed");
                }
                if let Some(s) = self.shifts.get_mut(at) {
                    s.confirmed_by = Some(author);
                }
                Ok(())
            },
            Body::Fulfil { record } => {
                if !boss {
                    return Err("denied");
                }
                let at = *self.redeem_at.get(&record).ok_or("unknown_record")?;
                let r = self.redeems.get_mut(at).ok_or("unknown_record")?;
                if h.lane != r.member {
                    return Err("bad_lane");
                }
                if r.fulfilled {
                    return Err("already_fulfilled");
                }
                r.fulfilled = true;
                Ok(())
            },
            _ => Err("bad_body"),
        }
    }
}

// What the viewer's role may author at all; a target may still refuse it (rules above).
pub fn can_list(role: Option<u8>, has_org: bool) -> &'static [&'static str] {
    const BOSS: &[&str] = &[
        "invite",
        "role",
        "dept",
        "assign",
        "remove",
        "rekey",
        "revoke",
        "offer",
        "profile",
        "phone_bind",
        "invite_phone",
        "salary",
        "pay",
        "receipt",
        "redeem",
        "fulfil",
        "shift_open",
        "shift_close",
        "shift_confirm",
        "item",
        "node",
        "node_staff",
        "order",
        "confirm",
        "pack",
        "handoff",
        "accept",
        "scan",
        "shelf",
        "sale",
        "issue",
        "cancel",
        "open",
        "letter",
        "edit",
        "delete",
    ];
    const MANAGER: &[&str] = &[
        "invite",
        "assign",
        "profile",
        "phone_bind",
        "receipt",
        "redeem",
        "shift_open",
        "shift_close",
        "shift_confirm",
        "order",
        "confirm",
        "pack",
        "handoff",
        "accept",
        "scan",
        "shelf",
        "sale",
        "issue",
        "cancel",
        "open",
        "letter",
        "edit",
        "delete",
    ];
    // An employee's supply steps stand on the nodes they belong to; the fold refuses the rest.
    const EMPLOYEE: &[&str] = &[
        "profile",
        "phone_bind",
        "receipt",
        "redeem",
        "shift_open",
        "shift_close",
        "order",
        "confirm",
        "pack",
        "handoff",
        "accept",
        "scan",
        "shelf",
        "sale",
        "issue",
        "cancel",
        "letter",
        "edit",
        "delete",
    ];
    match role {
        Some(ROLE_OWNER | ROLE_ADMIN) => BOSS,
        Some(ROLE_MANAGER) => MANAGER,
        Some(_) => EMPLOYEE,
        None if has_org => &["join"],
        None => &["genesis"],
    }
}
