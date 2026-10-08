use std::collections::{BTreeMap, BTreeSet};

use mt_crypto::PublicKey;

use crate::body::{
    kind_name, Dept, Line, Tag, CHAT_CHANNEL, NODE_CARRIER, NODE_HUB, NODE_SHOP, NODE_SUPPLIER,
    NODE_WAREHOUSE, PAY_SALARY, ROLE_ADMIN, ROLE_EMPLOYEE, ROLE_MANAGER, ROLE_OWNER, SOURCE_VOICE,
    ZERO16,
};
use crate::codec::to_hex;
use crate::fold::{can_list, member_id_of, shift_lane_of, Fold, Outcome};
use crate::frame::{Id, CHAIN_C, CHAIN_H, CHAIN_R, CHAIN_S};

// The journal the view carries: the newest records the viewer sees; the whole chains go to Files from the app.
pub const JOURNAL_MAX: usize = 200;
use crate::json::Json;
use crate::period::{self, period_key, PERIOD_MONTH, PERIOD_WEEK};
use crate::supply::{Order, Stock};

pub fn role_name(role: Option<u8>) -> &'static str {
    match role {
        Some(ROLE_OWNER) => "owner",
        Some(ROLE_ADMIN) => "admin",
        Some(ROLE_MANAGER) => "manager",
        Some(ROLE_EMPLOYEE) => "employee",
        _ => "none",
    }
}

// The name of the coin letter of a Pay: one name for its whole life, so the coin book pays it once.
pub fn coin_ref(record: &[u8; 32]) -> String {
    format!("biz:{}", to_hex(record))
}

fn node_kind_name(kind: u8) -> &'static str {
    match kind {
        NODE_SUPPLIER => "supplier",
        NODE_WAREHOUSE => "warehouse",
        NODE_HUB => "hub",
        NODE_CARRIER => "carrier",
        NODE_SHOP => "shop",
        _ => "unknown",
    }
}

fn chain_name(chain: u8) -> &'static str {
    match chain {
        CHAIN_R => "R",
        CHAIN_H => "H",
        CHAIN_S => "S",
        CHAIN_C => "C",
        _ => "unknown",
    }
}

fn kv_hash(j: &mut Json, k: &str, v: &[u8; 32]) {
    j.key(k);
    if v == &[0u8; 32] {
        j.null();
    } else {
        j.hex(v);
    }
}

fn kv_lines(j: &mut Json, k: &str, lines: &[Line]) {
    j.key(k);
    j.begin_arr();
    for l in lines {
        j.begin_obj();
        j.kv_hex("item", &l.item);
        j.kv_str("size", &l.size);
        j.kv_u64("qty", u64::from(l.qty));
        j.kv_u64("coins", l.coins);
        j.end_obj();
    }
    j.end_arr();
}

// What of an order no place holds yet: its lines, in their order, less what its places were packed with, per item and size --
// the storekeeper's next box at a glance. A line wholly packed is left out.
fn unpacked(o: &Order) -> Vec<(Tag, &str, u64)> {
    let mut packed: BTreeMap<(Tag, &str), u64> = BTreeMap::new();
    for p in o.places.values() {
        for l in &p.lines {
            let q = packed.entry((l.item, l.size.as_str())).or_default();
            *q = q.saturating_add(u64::from(l.qty));
        }
    }
    let mut out = Vec::new();
    for l in &o.lines {
        let want = u64::from(l.qty);
        let have = packed.entry((l.item, l.size.as_str())).or_default();
        let take = want.min(*have);
        *have -= take;
        if want > take {
            out.push((l.item, l.size.as_str(), want - take));
        }
    }
    out
}

// A period's moments, [from_ms, to_ms) on UTC boundaries, or nulls where there is no period.
fn kv_bounds(j: &mut Json, bounds: Option<(u64, u64)>) {
    match bounds {
        Some((from, to)) => {
            j.kv_u64("from_ms", from);
            j.kv_u64("to_ms", to);
        },
        None => {
            j.kv_null("from_ms");
            j.kv_null("to_ms");
        },
    }
}

fn kv_dept(j: &mut Json, k: &str, dept: &Dept) {
    j.key(k);
    if dept == &ZERO16 {
        j.null();
    } else {
        j.hex(dept);
    }
}

pub fn render(f: &Fold, viewer: &PublicKey, now_ms: u64) -> String {
    let me = f
        .member_by_key(viewer)
        .unwrap_or_else(|| member_id_of(viewer));
    // A key the member has moved away from (Rekey) holds no place: the old phone shows no role and no rights it lost.
    let current = f.members.get(&me).is_some_and(|m| &m.key == viewer);
    let my_role = if current { f.active_role(&me) } else { None };
    let boss = matches!(my_role, Some(ROLE_OWNER | ROLE_ADMIN));
    let me_member = f.members.get(&me).filter(|m| current && !m.removed);
    let my_dept = me_member.map_or(ZERO16, |m| m.dept);
    // A member's shifts reach the member, the administrators and the manager of the member's own department -- never the pay.
    let sees_shifts = |member: &[u8; 32]| {
        boss || member == &me
            || (my_role == Some(ROLE_MANAGER)
                && my_dept != ZERO16
                && f.members.get(member).is_some_and(|m| m.dept == my_dept))
    };
    let shift_owner: BTreeMap<[u8; 32], [u8; 32]> = f
        .members
        .keys()
        .map(|id| (shift_lane_of(id), *id))
        .collect();
    // R reaches every member; a lane of H only its employee and the administrators; an order's lane the people of
    // its path; a chat's lane those who hear it.
    let sees = |chain: u8, lane: &[u8; 32]| match chain {
        CHAIN_R => true,
        CHAIN_S => {
            boss || f
                .supply
                .orders
                .get(lane)
                .is_some_and(|o| f.supply.party(o, &me))
        },
        CHAIN_C => {
            boss || f
                .chats
                .chats
                .get(lane)
                .zip(me_member)
                .is_some_and(|(c, m)| c.hears(m))
        },
        _ => boss || lane == &me || shift_owner.get(lane).is_some_and(sees_shifts),
    };
    let h = CHAIN_H;

    let mut j = Json::new();
    j.begin_obj();

    j.key("org");
    match &f.org {
        Some(o) => {
            j.begin_obj();
            j.kv_hex("id", &o.id);
            j.kv_str("name", &o.name);
            j.kv_u64("created_ms", o.created_ms);
            j.end_obj();
        },
        None => j.null(),
    }

    j.key("me");
    j.begin_obj();
    j.kv_hex("member", &me);
    j.kv_str("role", role_name(my_role));
    j.key("can");
    j.begin_arr();
    for c in can_list(my_role, f.org.is_some()) {
        j.str(c);
    }
    j.end_arr();
    j.end_obj();

    let mut members: Vec<_> = f.members.iter().collect();
    members.sort_by_key(|(id, m)| (m.joined_ms, **id));
    j.key("members");
    j.begin_arr();
    for (id, m) in members {
        let mismatch = boss
            && m.invite
                .and_then(|i| f.invite_phone.get(&i))
                .zip(m.phone_hash.as_ref())
                .is_some_and(|(a, b)| a != b);
        j.begin_obj();
        j.kv_hex("member", id);
        j.kv_str("name", &m.name);
        j.kv_str("role", role_name(Some(m.role)));
        kv_dept(&mut j, "dept", &m.dept);
        j.kv_str("title", &m.title);
        j.kv_str("card", &m.card);
        let status = if m.removed {
            "removed"
        } else if m.pending_device() {
            "pending_device"
        } else {
            "active"
        };
        j.kv_str("status", status);
        j.kv_u64("joined_ms", m.joined_ms);
        j.kv_bool("phone_confirmed", sees(h, id) && m.phone_confirmed());
        match m.phone.as_deref().filter(|_| sees(h, id)) {
            Some(e164) => j.kv_str("phone", e164),
            None => j.kv_null("phone"),
        }
        j.kv_bool("phone_mismatch", mismatch);
        j.end_obj();
    }
    j.end_arr();

    j.key("depts");
    j.begin_arr();
    for (id, d) in f.depts.iter().filter(|(_, d)| !d.archived) {
        j.begin_obj();
        j.kv_hex("id", id);
        j.kv_str("name", &d.name);
        j.end_obj();
    }
    j.end_arr();

    j.key("invites");
    j.begin_arr();
    for (id, inv) in &f.invites {
        let state = if inv.revoked {
            "revoked"
        } else if inv.used {
            "used"
        } else if now_ms > inv.expires_ms {
            "expired"
        } else {
            "open"
        };
        j.begin_obj();
        j.kv_hex("id", id);
        j.kv_str("role", role_name(Some(inv.role)));
        kv_dept(&mut j, "dept", &inv.dept);
        j.kv_u64("expires_ms", inv.expires_ms);
        j.kv_str("state", state);
        j.end_obj();
    }
    j.end_arr();

    j.key("salary");
    j.begin_arr();
    for (member, s) in f.salary.iter().filter(|(m, _)| sees(h, m)) {
        j.begin_obj();
        j.kv_hex("member", member);
        j.kv_u64("coins", s.coins);
        j.kv_u64("period", u64::from(s.period));
        j.kv_u64("from_ms", s.from_ms);
        j.end_obj();
    }
    j.end_arr();

    j.key("pay");
    j.begin_arr();
    for p in f.pays.iter().filter(|p| sees(h, &p.member)) {
        j.begin_obj();
        j.kv_hex("record", &p.record);
        j.kv_u64("at_ms", p.at_ms);
        j.kv_hex("member", &p.member);
        j.kv_u64("coins", p.coins);
        j.kv_u64("kind", u64::from(p.pay_kind));
        j.kv_u64("period_key", u64::from(p.period_key));
        // A salary pay names its period's own moments, as a due row does; a bonus has no period.
        kv_bounds(
            &mut j,
            if p.pay_kind == PAY_SALARY {
                period::bounds(p.period_key)
            } else {
                None
            },
        );
        j.kv_str("coin_ref", &coin_ref(&p.record));
        let state = if f.pay_confirmed(&p.record) {
            "confirmed"
        } else {
            "sent"
        };
        j.kv_str("state", state);
        j.end_obj();
    }
    j.end_arr();

    j.key("due");
    j.begin_arr();
    for (member, coins, key) in f.due(now_ms) {
        if !sees(h, &member) {
            continue;
        }
        j.begin_obj();
        j.kv_hex("member", &member);
        j.kv_u64("coins", coins);
        j.kv_u64("period_key", u64::from(key));
        // The period's own moments, [from_ms, to_ms) on UTC boundaries: a screen names the month by them, no calendar of its own.
        kv_bounds(&mut j, period::bounds(key));
        // The coin letter is named after its Pay record, which does not exist until it is written.
        j.kv_null("coin_ref");
        j.end_obj();
    }
    j.end_arr();

    j.key("offers");
    j.begin_arr();
    for (id, o) in &f.offers {
        j.begin_obj();
        j.kv_hex("id", id);
        j.kv_str("title", &o.title);
        j.kv_u64("price", o.price);
        j.kv_u64("stock", if o.counted { u64::from(o.remaining) } else { 0 });
        j.kv_bool("active", o.active);
        j.end_obj();
    }
    j.end_arr();

    j.key("redeems");
    j.begin_arr();
    for r in f.redeems.iter().filter(|r| sees(h, &r.member)) {
        j.begin_obj();
        j.kv_hex("record", &r.record);
        j.kv_u64("at_ms", r.at_ms);
        j.kv_hex("member", &r.member);
        j.kv_hex("offer", &r.item);
        j.kv_u64("qty", u64::from(r.qty));
        j.kv_u64("coins", r.coins);
        j.kv_str("state", if r.fulfilled { "fulfilled" } else { "paid" });
        j.end_obj();
    }
    j.end_arr();

    j.key("shifts");
    j.begin_arr();
    for s in f.shifts.iter().filter(|s| sees_shifts(&s.member)) {
        let dept = f.members.get(&s.member).map_or(ZERO16, |m| m.dept);
        j.begin_obj();
        j.kv_hex("member", &s.member);
        j.kv_hex("lane", &shift_lane_of(&s.member));
        kv_dept(&mut j, "node", &s.node);
        j.kv_hex("open_record", &s.open_record);
        j.kv_u64("open_ms", s.open_ms);
        match (s.close_record, s.close_ms) {
            (Some(r), Some(at)) => {
                j.kv_hex("close_record", &r);
                j.kv_u64("close_ms", at);
                j.kv_u64("seconds", at.saturating_sub(s.open_ms) / 1000);
            },
            _ => {
                j.kv_null("close_record");
                j.kv_null("close_ms");
                j.kv_null("seconds");
            },
        }
        j.kv_str("note", &s.note);
        match &s.confirmed_by {
            Some(m) => j.kv_hex("confirmed_by", m),
            None => j.kv_null("confirmed_by"),
        }
        j.key("people");
        j.begin_arr();
        for (id, m) in &f.members {
            let holds = !m.removed
                && (id == &s.member
                    || m.role <= ROLE_ADMIN
                    || (m.role == ROLE_MANAGER && dept != ZERO16 && m.dept == dept));
            if holds {
                j.hex(id);
            }
        }
        j.end_arr();
        j.end_obj();
    }
    j.end_arr();

    // The time of each person whose shifts this viewer sees: closed shifts begun in the current UTC week and month (period.rs,
    // the salary's own boundaries), the confirmed ones apart. Seconds, never coins.
    let (week, month) = (
        period_key(PERIOD_WEEK, now_ms),
        period_key(PERIOD_MONTH, now_ms),
    );
    let mut time: BTreeMap<[u8; 32], [u64; 4]> = BTreeMap::new();
    for s in f.shifts.iter().filter(|s| sees_shifts(&s.member)) {
        let Some(close) = s.close_ms else {
            continue;
        };
        let secs = close.saturating_sub(s.open_ms) / 1000;
        let confirmed = s.confirmed_by.is_some();
        let sums = time.entry(s.member).or_insert([0; 4]);
        for (i, now_key, period) in [(0, week, PERIOD_WEEK), (2, month, PERIOD_MONTH)] {
            if now_key.is_some() && period_key(period, s.open_ms) == now_key {
                sums[i] = sums[i].saturating_add(secs);
                if confirmed {
                    sums[i + 1] = sums[i + 1].saturating_add(secs);
                }
            }
        }
    }
    j.key("time");
    j.begin_arr();
    for (member, sums) in &time {
        j.begin_obj();
        j.kv_hex("member", member);
        j.kv_u64("week_s", sums[0]);
        j.kv_u64("week_confirmed_s", sums[1]);
        j.kv_u64("month_s", sums[2]);
        j.kv_u64("month_confirmed_s", sums[3]);
        j.end_obj();
    }
    j.end_arr();

    j.key("items");
    j.begin_arr();
    for (id, i) in &f.supply.catalogue.items {
        j.begin_obj();
        j.kv_hex("id", id);
        j.kv_str("title", &i.title);
        j.kv_str("sizes", &i.sizes);
        j.kv_str("unit", &i.unit);
        j.kv_bool("active", i.active);
        j.end_obj();
    }
    j.end_arr();

    j.key("nodes");
    j.begin_arr();
    for (id, n) in &f.supply.catalogue.nodes {
        j.begin_obj();
        j.kv_hex("id", id);
        j.kv_str("kind", node_kind_name(n.kind));
        j.kv_str("name", &n.name);
        j.kv_str("place", &n.place);
        j.kv_bool("active", n.active);
        j.kv_bool("mine", f.supply.catalogue.on_staff(id, &me));
        j.key("staff");
        j.begin_arr();
        for m in f.supply.catalogue.people(id) {
            j.hex(&m);
        }
        j.end_arr();
        j.end_obj();
    }
    j.end_arr();

    let bosses: BTreeSet<[u8; 32]> = f
        .members
        .iter()
        .filter(|(_, m)| !m.removed && m.role <= ROLE_ADMIN)
        .map(|(id, _)| *id)
        .collect();

    // An order's whole TimeChain for the people of its path: its lines, its places and where each one is, every step with
    // its time and signer, and who holds the order (the phones the order's lane is handed to).
    j.key("orders");
    j.begin_arr();
    for (key, o) in f
        .supply
        .orders
        .iter()
        .filter(|(_, o)| boss || f.supply.party(o, &me))
    {
        j.begin_obj();
        j.kv_hex("id", key);
        j.kv_hex("author", &o.author);
        j.kv_u64("at_ms", o.at_ms);
        j.kv_hex("from", &o.from);
        j.kv_hex("to", &o.to);
        j.kv_str(
            "source",
            if o.source == SOURCE_VOICE {
                "voice"
            } else {
                "text"
            },
        );
        kv_hash(&mut j, "voice", &o.voice);
        j.kv_str("text", &o.text);
        j.kv_str("state", o.state());
        let t = o.times(now_ms);
        j.kv_u64("way_ms", t.way_ms);
        j.key("longest_stand");
        j.begin_obj();
        j.kv_hex("node", &t.longest.0);
        j.kv_u64("ms", t.longest.1);
        j.end_obj();
        match t.standing {
            Some((node, since, ms)) => {
                j.key("standing");
                j.begin_obj();
                j.kv_hex("node", &node);
                j.kv_u64("since_ms", since);
                j.kv_u64("ms", ms);
                j.end_obj();
            },
            None => j.kv_null("standing"),
        }
        kv_lines(&mut j, "lines", &o.lines);
        j.key("unpacked");
        j.begin_arr();
        for (item, size, qty) in unpacked(o) {
            j.begin_obj();
            j.kv_hex("item", &item);
            j.kv_str("size", size);
            j.kv_u64("qty", qty);
            j.end_obj();
        }
        j.end_arr();
        j.key("places");
        j.begin_arr();
        for (pid, p) in &o.places {
            j.begin_obj();
            j.kv_hex("id", pid);
            kv_lines(&mut j, "lines", &p.lines);
            j.kv_hex("packed_at", &p.packed_at);
            match p.holder {
                Some(n) => j.kv_hex("holder", &n),
                None => j.kv_null("holder"),
            }
            match p.transit {
                Some((a, b)) => {
                    j.kv_hex("transit_from", &a);
                    j.kv_hex("transit_to", &b);
                },
                None => {
                    j.kv_null("transit_from");
                    j.kv_null("transit_to");
                },
            }
            j.kv_u64("condition", u64::from(p.condition));
            j.kv_str("note", &p.note);
            kv_hash(&mut j, "photo", &p.photo);
            j.end_obj();
        }
        j.end_arr();
        j.key("steps");
        j.begin_arr();
        for st in &o.steps {
            j.begin_obj();
            j.kv_hex("record", &st.record);
            j.kv_str("kind", kind_name(st.kind));
            j.kv_u64("at_ms", st.at_ms);
            j.kv_hex("author", &st.author);
            kv_dept(&mut j, "node", &st.node);
            kv_dept(&mut j, "place", &st.place);
            j.kv_u64("detail", u64::from(st.detail));
            kv_lines(&mut j, "lines", &st.lines);
            j.kv_str("note", &st.note);
            j.end_obj();
        }
        j.end_arr();
        let mut people = bosses.clone();
        people.insert(o.author);
        for n in &o.path {
            people.extend(f.supply.catalogue.people(n));
        }
        j.key("people");
        j.begin_arr();
        for m in &people {
            j.hex(m);
        }
        j.end_arr();
        j.end_obj();
    }
    j.end_arr();

    // Stock by node, for the node's people and the administrators: on hand (kept in places, not on the counter), on the
    // counter, sold.
    let stocks = &f.supply.stocks;
    let mut keys: BTreeSet<&(Dept, Dept, String)> = stocks.held.keys().collect();
    keys.extend(stocks.shelved.keys());
    keys.extend(stocks.sold.keys());
    let count = |m: &Stock, k: &(Dept, Dept, String)| m.get(k).copied().unwrap_or(0);
    j.key("stock");
    j.begin_arr();
    for k in keys {
        if !(boss || f.supply.catalogue.on_staff(&k.0, &me)) {
            continue;
        }
        let (held, shelved, sold) = (
            count(&stocks.held, k),
            count(&stocks.shelved, k),
            count(&stocks.sold, k),
        );
        j.begin_obj();
        j.kv_hex("node", &k.0);
        j.kv_hex("item", &k.1);
        j.kv_str("size", &k.2);
        j.kv_u64("on_hand", held.saturating_sub(shelved));
        j.kv_u64("counter", shelved.saturating_sub(sold));
        j.kv_u64("sold", sold);
        j.end_obj();
    }
    j.end_arr();

    j.key("chats");
    j.begin_arr();
    for (key, c) in f
        .chats
        .chats
        .iter()
        .filter(|(_, c)| boss || me_member.is_some_and(|m| c.hears(m)))
    {
        j.begin_obj();
        j.kv_hex("id", key);
        j.kv_str(
            "kind",
            if c.kind == CHAT_CHANNEL {
                "channel"
            } else {
                "group"
            },
        );
        j.kv_str("name", &c.name);
        kv_dept(&mut j, "dept", &c.dept);
        kv_dept(&mut j, "group", &c.group);
        j.kv_hex("author", &c.author);
        j.kv_u64("opened_ms", c.opened_ms);
        j.kv_bool("track", c.track);
        j.kv_u64("track_window_s", u64::from(c.track_window_s));
        j.key("people");
        j.begin_arr();
        for (id, m) in &f.members {
            if c.hears(m) {
                j.hex(id);
            }
        }
        j.end_arr();
        j.end_obj();
    }
    j.end_arr();

    let mut seen: Vec<(u64, Id)> = f
        .records
        .iter()
        .filter(|(_, m)| sees(m.chain, &m.lane))
        .map(|(id, m)| (m.at_ms, *id))
        .collect();
    seen.sort_unstable();
    j.key("journal");
    j.begin_arr();
    for (at, id) in seen.iter().skip(seen.len().saturating_sub(JOURNAL_MAX)) {
        let Some(m) = f.records.get(id) else {
            continue;
        };
        j.begin_obj();
        j.kv_hex("record", id);
        j.kv_str("chain", chain_name(m.chain));
        j.kv_str("kind", kind_name(m.kind));
        j.kv_u64("at_ms", *at);
        j.kv_hex("author", &m.author);
        j.kv_hex("lane", &m.lane);
        let outcome = match m.outcome {
            Outcome::Accepted => "applied",
            Outcome::Rejected(_) => "rejected",
            Outcome::Waiting => "waiting",
        };
        j.kv_str("outcome", outcome);
        j.end_obj();
    }
    j.end_arr();

    let mut rejected: Vec<_> = f
        .records
        .iter()
        .filter(|(_, m)| sees(m.chain, &m.lane))
        .filter_map(|(id, m)| match m.outcome {
            Outcome::Rejected(reason) => Some((m.at_ms, *id, reason)),
            _ => None,
        })
        .collect();
    rejected.sort();
    j.key("rejected");
    j.begin_arr();
    for (_, id, reason) in rejected {
        j.begin_obj();
        j.kv_hex("record", &id);
        j.kv_str("reason", reason);
        j.end_obj();
    }
    j.end_arr();

    let waiting = f
        .records
        .values()
        .filter(|m| m.outcome == Outcome::Waiting && sees(m.chain, &m.lane))
        .count();
    j.kv_u64("waiting", u64::try_from(waiting).unwrap_or(u64::MAX));
    j.kv_hex("head", &f.head);
    j.end_obj();
    j.finish()
}
