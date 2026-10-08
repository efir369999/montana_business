// The fold: rights, salary due, receipts, the shop, phone confirmation, and the properties the
// contract names: one set of records in any order of arrival gives one view; merge is idempotent,
// commutative and associative.

use mt_business::attest::{test_service_key, Attest, CHANNEL_BOT};
use mt_business::codec::to_hex;
use mt_business::fold::{invite_id_of, member_id_of};
use mt_business::frame::{join_chain, seal, split_chain, Header, Record, CHAIN_R};
use mt_business::period::{period_key, PERIOD_DAY};
use mt_business::{author_with, merge, slice, view_with, BizError};
use mt_crypto::{keypair_from_seed, sha256_raw, PublicKey, SecretKey};
use serde_json::Value;

const T0: u64 = 1_791_234_567_890;
const DAY: u64 = 86_400_000;

struct Person {
    pk: PublicKey,
    sk: SecretKey,
    id: [u8; 32],
}

fn person(seed: u8) -> Person {
    let (pk, sk) = keypair_from_seed(&[seed; 32]).expect("keygen");
    let id = member_id_of(&pk);
    Person { pk, sk, id }
}

fn hx(b: &[u8]) -> String {
    to_hex(b)
}

struct Org {
    roster: Vec<u8>,
    hr: Vec<u8>,
    service: PublicKey,
    service_sk: SecretKey,
    clock: u64,
}

impl Org {
    fn new() -> Self {
        let (service, service_sk) = test_service_key().expect("keygen");
        Self {
            roster: Vec::new(),
            hr: Vec::new(),
            service,
            service_sk,
            clock: T0,
        }
    }

    fn try_do(&mut self, who: &Person, cmd: &str) -> Result<[u8; 32], BizError> {
        self.clock += 1000;
        let frame = author_with(
            who.sk.as_bytes(),
            who.pk.as_bytes(),
            &self.roster,
            &self.hr,
            cmd,
            self.clock,
            Some(&self.service),
        )?;
        let recs = split_chain(&frame).expect("one frame");
        assert_eq!(recs.len(), 1);
        let rec = &recs[0];
        let target = if rec.header.chain == CHAIN_R {
            &mut self.roster
        } else {
            &mut self.hr
        };
        let (merged, added) = merge(target, &frame).expect("merges");
        assert_eq!(added, 1);
        *target = merged;
        Ok(rec.id)
    }

    fn act(&mut self, who: &Person, cmd: &str) -> [u8; 32] {
        match self.try_do(who, cmd) {
            Ok(id) => id,
            Err(e) => panic!("{cmd}: {e}"),
        }
    }

    fn denied(&mut self, who: &Person, cmd: &str) -> &'static str {
        match self.try_do(who, cmd) {
            Err(BizError::Denied(reason)) => reason,
            other => panic!("{cmd}: expected a denial, got {other:?}"),
        }
    }

    fn view_at(&self, who: &Person, now: u64) -> Value {
        let json = view_with(
            &self.roster,
            &self.hr,
            who.pk.as_bytes(),
            now,
            Some(&self.service),
        )
        .expect("view");
        serde_json::from_str(&json).expect("view is JSON")
    }

    fn view(&self, who: &Person) -> Value {
        self.view_at(who, self.clock)
    }

    fn attest_for(&self, who: &PublicKey, e164: &str) -> String {
        let a = Attest {
            channel: CHANNEL_BOT,
            at_ms: self.clock,
            subject: sha256_raw(who.as_bytes()),
            e164: e164.into(),
        };
        hx(&a.seal(&self.service_sk).expect("seals"))
    }
}

struct Cast {
    owner: Person,
    admin: Person,
    manager: Person,
    employee: Person,
    stranger: Person,
}

const KITCHEN: &str = "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf";
const BAR: &str = "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf";
const LATTE: &str = "e0e1e2e3e4e5e6e7e8e9eaebecedeeef";

fn secret(n: u8) -> String {
    hx(&[n; 32])
}

fn invite(role: &str, dept: Option<&str>, n: u8, expires: u64) -> String {
    let dept = dept.map_or(String::new(), |d| format!(",\"dept\":\"{d}\""));
    format!(
        "{{\"kind\":\"invite\",\"secret\":\"{}\",\"role\":\"{role}\"{dept},\"expires_ms\":{expires}}}",
        secret(n)
    )
}

fn join(n: u8, name: &str) -> String {
    format!(
        "{{\"kind\":\"join\",\"secret\":\"{}\",\"name\":\"{name}\",\"card\":\"card-{name}\"}}",
        secret(n)
    )
}

fn founded() -> (Org, Cast) {
    let c = Cast {
        owner: person(1),
        admin: person(2),
        manager: person(3),
        employee: person(4),
        stranger: person(5),
    };
    let mut o = Org::new();
    let far = T0 + 30 * DAY;
    o.act(
        &c.owner,
        r#"{"kind":"genesis","name":"Montana Coffee","owner_name":"Анна","owner_card":"card-anna"}"#,
    );
    o.act(
        &c.owner,
        &format!("{{\"kind\":\"dept\",\"dept\":\"{KITCHEN}\",\"name\":\"Kitchen\"}}"),
    );
    o.act(
        &c.owner,
        &format!("{{\"kind\":\"dept\",\"dept\":\"{BAR}\",\"name\":\"Bar\"}}"),
    );
    o.act(&c.owner, &invite("admin", None, 1, far));
    o.act(&c.admin, &join(1, "Boris"));
    o.act(&c.owner, &invite("manager", Some(KITCHEN), 2, far));
    o.act(&c.manager, &join(2, "Mila"));
    o.act(&c.manager, &invite("employee", Some(KITCHEN), 3, far));
    o.act(&c.employee, &join(3, "Eva"));
    (o, c)
}

fn salaried() -> (Org, Cast, [u8; 32], [u8; 32]) {
    let (mut o, c) = founded();
    let e = hx(&c.employee.id);
    o.act(
        &c.admin,
        &format!("{{\"kind\":\"salary\",\"member\":\"{e}\",\"coins\":100,\"period\":1,\"from_ms\":{T0}}}"),
    );
    o.act(
        &c.admin,
        &format!("{{\"kind\":\"offer\",\"item\":\"{LATTE}\",\"title\":\"Latte\",\"price\":30,\"stock\":3}}"),
    );
    o.act(
        &c.manager,
        &format!("{{\"kind\":\"assign\",\"member\":\"{e}\",\"dept\":\"{KITCHEN}\",\"title\":\"Barista\"}}"),
    );
    let key = period_key(PERIOD_DAY, T0).expect("key");
    let pay = o.act(
        &c.admin,
        &format!("{{\"kind\":\"pay\",\"member\":\"{e}\",\"pay_kind\":1,\"period_key\":{key},\"coins\":100,\"note\":\"day one\"}}"),
    );
    let redeem = o.act(
        &c.employee,
        &format!("{{\"kind\":\"redeem\",\"item\":\"{LATTE}\",\"qty\":2,\"coins\":60}}"),
    );
    (o, c, pay, redeem)
}

fn arr<'a>(v: &'a Value, k: &str) -> &'a Vec<Value> {
    v[k].as_array().expect("array")
}

fn find<'a>(v: &'a Value, list: &str, key: &str, want: &str) -> &'a Value {
    arr(v, list)
        .iter()
        .find(|x| x[key] == want)
        .unwrap_or_else(|| panic!("{list} has no {key}={want}"))
}

#[test]
fn founding_gives_four_members_with_their_roles() {
    let (o, c) = founded();
    let v = o.view(&c.owner);
    assert_eq!(v["org"]["name"], "Montana Coffee");
    assert_eq!(v["me"]["role"], "owner");
    assert_eq!(arr(&v, "members").len(), 4);
    assert_eq!(
        find(&v, "members", "member", &hx(&c.admin.id))["role"],
        "admin"
    );
    let m = find(&v, "members", "member", &hx(&c.manager.id));
    assert_eq!(m["role"], "manager");
    assert_eq!(m["dept"], KITCHEN);
    assert_eq!(
        find(&v, "members", "member", &hx(&c.employee.id))["role"],
        "employee"
    );
    assert_eq!(arr(&v, "rejected").len(), 0);
    assert_eq!(v["waiting"], 0);
    let used = invite_id_of(&[3; 32]);
    assert_eq!(find(&v, "invites", "id", &hx(&used))["state"], "used");
    let ev = o.view(&c.employee);
    assert_eq!(ev["me"]["role"], "employee");
    assert_eq!(
        ev["me"]["can"],
        // Contract v1.1: an employee's supply steps (refused by the fold off their own nodes) and chat letters.
        serde_json::json!([
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
            "delete"
        ])
    );
    let sv = o.view(&c.stranger);
    assert_eq!(sv["me"]["role"], "none");
    assert_eq!(sv["me"]["can"], serde_json::json!(["join"]));
}

#[test]
fn a_raise_before_the_payroll_keeps_the_old_days_due() {
    // 100 a day from T0, the first day paid (salaried). A raise to 150 from the third day, written before any payroll.
    let (mut o, c, _, _) = salaried();
    let e = hx(&c.employee.id);
    let salary = |coins: u64, from: u64| {
        format!("{{\"kind\":\"salary\",\"member\":\"{e}\",\"coins\":{coins},\"period\":1,\"from_ms\":{from}}}")
    };
    o.act(&c.admin, &salary(150, T0 + 2 * DAY));
    let later = T0 + 4 * DAY + 3_600_000;
    let first = u64::from(period_key(PERIOD_DAY, T0).expect("key"));
    let due = |o: &Org| -> Vec<(u64, u64)> {
        arr(&o.view_at(&c.owner, later), "due")
            .iter()
            .map(|d| {
                (
                    d["period_key"].as_u64().expect("key"),
                    d["coins"].as_u64().expect("coins"),
                )
            })
            .collect()
    };
    // The second day stays due at the old rate; the third, which holds the raise's start, and the fourth are the raise's.
    // Replacing the terms whole would lose the second day; letting the old run through the raise's day would owe it twice.
    assert_eq!(
        due(&o),
        vec![(first + 1, 100), (first + 2, 150), (first + 3, 150)]
    );
    // Each due row names its own day: [from_ms, to_ms) on UTC boundaries.
    let v = o.view_at(&c.owner, later);
    let rows = arr(&v, "due");
    let day0 = T0 / DAY;
    assert_eq!(rows[0]["from_ms"], (day0 + 1) * DAY);
    assert_eq!(rows[0]["to_ms"], (day0 + 2) * DAY);
    // A salary pay names its own day the same way; a bonus has no period.
    let paid = arr(&v, "pay")
        .iter()
        .find(|p| p["kind"] == 1)
        .expect("the first day's pay");
    assert_eq!(paid["from_ms"], day0 * DAY);
    assert_eq!(paid["to_ms"], (day0 + 1) * DAY);
    o.act(
        &c.admin,
        &format!("{{\"kind\":\"pay\",\"member\":\"{e}\",\"pay_kind\":2,\"period_key\":0,\"coins\":50,\"note\":\"thanks\"}}"),
    );
    let bonus = arr(&o.view_at(&c.owner, later), "pay")
        .iter()
        .find(|p| p["kind"] == 2)
        .expect("the bonus")
        .clone();
    assert!(bonus["from_ms"].is_null() && bonus["to_ms"].is_null());
    // A correction written later but dated back to the second day takes over from there, the raise included.
    o.act(&c.admin, &salary(120, T0 + DAY));
    assert_eq!(
        due(&o),
        vec![(first + 1, 120), (first + 2, 120), (first + 3, 120)]
    );
    let terms = find(&o.view_at(&c.owner, later), "salary", "member", &e).clone();
    assert_eq!(terms["coins"], 120);
}

#[test]
fn salary_due_pay_receipt_and_shop() {
    let (mut o, c, pay, redeem) = salaried();
    let e = hx(&c.employee.id);
    let later = T0 + 3 * DAY + 3_600_000;
    let v = o.view_at(&c.owner, later);
    let due: Vec<u64> = arr(&v, "due")
        .iter()
        .map(|d| d["period_key"].as_u64().expect("key"))
        .collect();
    let first = u64::from(period_key(PERIOD_DAY, T0).expect("key"));
    assert_eq!(due, vec![first + 1, first + 2]);
    assert!(arr(&v, "due")
        .iter()
        .all(|d| d["coins"] == 100 && d["coin_ref"].is_null()));
    let p = find(&v, "pay", "record", &hx(&pay));
    assert_eq!(p["state"], "sent");
    assert_eq!(p["coin_ref"], format!("biz:{}", hx(&pay)));
    assert_eq!(find(&v, "offers", "id", LATTE)["stock"], 1);
    assert_eq!(find(&v, "redeems", "record", &hx(&redeem))["state"], "paid");

    o.act(
        &c.employee,
        &format!("{{\"kind\":\"receipt\",\"record\":\"{}\"}}", hx(&pay)),
    );
    o.act(
        &c.admin,
        &format!("{{\"kind\":\"fulfil\",\"record\":\"{}\"}}", hx(&redeem)),
    );
    o.act(
        &c.owner,
        &format!("{{\"kind\":\"receipt\",\"record\":\"{}\"}}", hx(&redeem)),
    );
    let bonus = o.act(
        &c.admin,
        &format!("{{\"kind\":\"pay\",\"member\":\"{e}\",\"pay_kind\":2,\"coins\":50,\"note\":\"bonus\"}}"),
    );
    let v = o.view(&c.owner);
    assert_eq!(find(&v, "pay", "record", &hx(&pay))["state"], "confirmed");
    assert_eq!(find(&v, "pay", "record", &hx(&bonus))["kind"], 2);
    assert_eq!(
        find(&v, "redeems", "record", &hx(&redeem))["state"],
        "fulfilled"
    );
    assert_eq!(arr(&v, "rejected").len(), 0);
}

#[test]
fn rights_refuse_every_rule() {
    let (mut o, c, pay, redeem) = salaried();
    let far = T0 + 30 * DAY;
    let e = hx(&c.employee.id);
    let a = hx(&c.admin.id);
    let own = hx(&c.owner.id);
    let key = period_key(PERIOD_DAY, T0).expect("key");
    let cases: Vec<(&Person, String, &str)> = vec![
        (&c.employee, invite("employee", Some(KITCHEN), 10, far), "denied"),
        (&c.manager, invite("admin", Some(KITCHEN), 11, far), "denied"),
        (&c.manager, invite("employee", Some(BAR), 12, far), "denied"),
        (&c.admin, invite("admin", None, 13, far), "denied"),
        (&c.admin, format!("{{\"kind\":\"role\",\"member\":\"{e}\",\"role\":\"admin\"}}"), "denied"),
        (&c.admin, format!("{{\"kind\":\"remove\",\"member\":\"{own}\"}}"), "denied"),
        (&c.owner, format!("{{\"kind\":\"remove\",\"member\":\"{own}\"}}"), "bad_target"),
        (&c.owner, format!("{{\"kind\":\"role\",\"member\":\"{own}\",\"role\":1}}"), "bad_target"),
        (&c.manager, format!("{{\"kind\":\"remove\",\"member\":\"{e}\"}}"), "denied"),
        (
            &c.employee,
            format!("{{\"kind\":\"salary\",\"member\":\"{e}\",\"coins\":1,\"period\":1,\"from_ms\":{T0}}}"),
            "denied",
        ),
        (
            &c.admin,
            format!("{{\"kind\":\"salary\",\"member\":\"{e}\",\"coins\":1,\"period\":4,\"from_ms\":{T0}}}"),
            "bad_period",
        ),
        (
            &c.admin,
            format!("{{\"kind\":\"pay\",\"member\":\"{e}\",\"pay_kind\":1,\"period_key\":{key},\"coins\":100}}"),
            "period_paid",
        ),
        (
            &c.admin,
            format!("{{\"kind\":\"pay\",\"member\":\"{e}\",\"pay_kind\":3,\"coins\":100}}"),
            "bad_pay_kind",
        ),
        (
            &c.admin,
            format!("{{\"kind\":\"pay\",\"member\":\"{e}\",\"pay_kind\":2,\"coins\":0}}"),
            "zero_coins",
        ),
        (
            &c.admin,
            format!("{{\"kind\":\"pay\",\"member\":\"{e}\",\"pay_kind\":1,\"period_key\":5,\"coins\":100}}"),
            "bad_period",
        ),
        (&c.manager, format!("{{\"kind\":\"receipt\",\"record\":\"{}\"}}", hx(&pay)), "denied"),
        (&c.admin, format!("{{\"kind\":\"receipt\",\"record\":\"{}\"}}", hx(&redeem)), "denied"),
        (
            &c.employee,
            format!("{{\"kind\":\"redeem\",\"item\":\"{LATTE}\",\"qty\":1,\"coins\":29}}"),
            "bad_price",
        ),
        (
            &c.employee,
            format!("{{\"kind\":\"redeem\",\"item\":\"{LATTE}\",\"qty\":2,\"coins\":60}}"),
            "out_of_stock",
        ),
        (
            &c.employee,
            format!("{{\"kind\":\"redeem\",\"item\":\"{BAR}\",\"qty\":1,\"coins\":30}}"),
            "unknown_offer",
        ),
        (&c.employee, format!("{{\"kind\":\"fulfil\",\"record\":\"{}\"}}", hx(&redeem)), "denied"),
        (&c.employee, join(4, "Eva2"), "unknown_invite"),
        (
            &c.manager,
            format!("{{\"kind\":\"assign\",\"member\":\"{a}\",\"dept\":\"{KITCHEN}\",\"title\":\"x\"}}"),
            "denied",
        ),
        (
            &c.admin,
            format!("{{\"kind\":\"rekey\",\"member\":\"{e}\",\"key\":\"{}\"}}", hx(c.manager.pk.as_bytes())),
            "key_in_use",
        ),
        (
            &c.admin,
            format!("{{\"kind\":\"rekey\",\"member\":\"{own}\",\"key\":\"{}\"}}", hx(c.stranger.pk.as_bytes())),
            "denied",
        ),
        (&c.admin, invite("employee", Some(KITCHEN), 3, far), "duplicate_invite"),
        (&c.employee, format!("{{\"kind\":\"dept\",\"dept\":\"{BAR}\",\"name\":\"x\"}}"), "denied"),
        (
            &c.owner,
            r#"{"kind":"genesis","name":"Second","owner_name":"Анна"}"#.to_string(),
            "second_genesis",
        ),
    ];
    for (who, cmd, reason) in cases {
        assert_eq!(o.denied(who, &cmd), reason, "{cmd}");
    }
    assert!(matches!(
        o.try_do(&c.stranger, r#"{"kind":"profile","name":"x"}"#),
        Err(BizError::Denied("not_member"))
    ));
    assert!(matches!(
        o.try_do(&c.owner, r#"{"kind":"warp"}"#),
        Err(BizError::Command("kind"))
    ));
    assert!(matches!(
        o.try_do(&c.owner, r#"{"kind":"remove","member":"zz"}"#),
        Err(BizError::Command("member"))
    ));
    assert!(matches!(
        o.try_do(&c.owner, "not json"),
        Err(BizError::Command(_))
    ));
}

#[test]
fn invites_close_by_revoke_use_and_time() {
    let (mut o, c) = founded();
    let soon = o.clock + 10_000;
    o.act(&c.admin, &invite("employee", None, 20, soon));
    let revoked = invite_id_of(&[21; 32]);
    o.act(&c.admin, &invite("employee", None, 21, T0 + DAY));
    o.act(
        &c.admin,
        &format!("{{\"kind\":\"revoke\",\"invite_id\":\"{}\"}}", hx(&revoked)),
    );
    let used = invite_id_of(&[3; 32]);
    assert_eq!(
        o.denied(
            &c.admin,
            &format!("{{\"kind\":\"revoke\",\"invite_id\":\"{}\"}}", hx(&used))
        ),
        "invite_used"
    );
    assert_eq!(o.denied(&c.stranger, &join(21, "X")), "invite_revoked");
    assert_eq!(o.denied(&c.stranger, &join(3, "X")), "invite_used");
    o.clock += 60_000;
    assert_eq!(o.denied(&c.stranger, &join(20, "X")), "invite_expired");
    assert_eq!(
        o.denied(
            &c.employee,
            &invite("employee", Some(KITCHEN), 22, T0 + DAY)
        ),
        "denied"
    );
    o.act(
        &c.owner,
        &format!("{{\"kind\":\"dept\",\"dept\":\"{BAR}\",\"name\":\"Bar\",\"archived\":true}}"),
    );
    assert_eq!(
        o.denied(&c.owner, &invite("employee", Some(BAR), 23, T0 + DAY)),
        "unknown_dept"
    );
    let v = o.view(&c.owner);
    assert_eq!(find(&v, "invites", "id", &hx(&revoked))["state"], "revoked");
    assert_eq!(
        find(&v, "invites", "id", &hx(&invite_id_of(&[20; 32])))["state"],
        "expired"
    );
    assert!(arr(&v, "depts").iter().all(|d| d["id"] != BAR));
}

#[test]
fn ownership_passes_and_a_dismissed_member_cannot_write() {
    let (mut o, c) = founded();
    let a = hx(&c.admin.id);
    let e = hx(&c.employee.id);
    o.act(
        &c.owner,
        &format!("{{\"kind\":\"role\",\"member\":\"{a}\",\"role\":0}}"),
    );
    assert_eq!(o.view(&c.admin)["me"]["role"], "owner");
    assert_eq!(o.view(&c.owner)["me"]["role"], "admin");
    o.act(
        &c.admin,
        &format!("{{\"kind\":\"remove\",\"member\":\"{e}\"}}"),
    );
    assert_eq!(
        find(&o.view(&c.admin), "members", "member", &e)["status"],
        "removed"
    );
    assert_eq!(
        o.denied(&c.employee, r#"{"kind":"profile","name":"x"}"#),
        "not_active"
    );
    assert_eq!(o.view(&c.employee)["me"]["role"], "none");
}

#[test]
fn phone_binding_rekey_and_mismatch() {
    let (mut o, c) = founded();
    let e = hx(&c.employee.id);
    let inv = hx(&invite_id_of(&[3; 32]));
    o.act(
        &c.admin,
        &format!("{{\"kind\":\"invite_phone\",\"invite_id\":\"{inv}\",\"e164\":\"+79160000000\"}}"),
    );
    let wrong = o.attest_for(&c.manager.pk, "+79161234567");
    assert_eq!(
        o.denied(
            &c.employee,
            &format!("{{\"kind\":\"phone_bind\",\"attest\":\"{wrong}\"}}")
        ),
        "attest_subject"
    );
    let mut forged = o.attest_for(&c.employee.pk, "+79161234567").into_bytes();
    let last = forged.len() - 1;
    forged[last] = if forged[last] == b'0' { b'1' } else { b'0' };
    let forged = String::from_utf8(forged).expect("ascii");
    assert_eq!(
        o.denied(
            &c.employee,
            &format!("{{\"kind\":\"phone_bind\",\"attest\":\"{forged}\"}}")
        ),
        "bad_attest"
    );
    let good = o.attest_for(&c.employee.pk, "+79161234567");
    o.act(
        &c.employee,
        &format!("{{\"kind\":\"phone_bind\",\"attest\":\"{good}\"}}"),
    );
    let v = o.view(&c.owner);
    let m = find(&v, "members", "member", &e);
    assert_eq!(m["phone_confirmed"], true);
    assert_eq!(m["phone_mismatch"], true);
    // The number reaches the holders of the member's lane of H -- the administrators and the member -- and nobody else.
    assert_eq!(m["phone"], "+79161234567");
    assert_eq!(
        find(&o.view(&c.employee), "members", "member", &e)["phone"],
        "+79161234567"
    );
    let manager_view = o.view(&c.manager);
    let m = find(&manager_view, "members", "member", &e);
    assert_eq!(m["phone_confirmed"], false);
    assert_eq!(m["phone_mismatch"], false);
    assert_eq!(m["phone"], Value::Null);

    let new_device = person(9);
    o.act(
        &c.admin,
        &format!(
            "{{\"kind\":\"rekey\",\"member\":\"{e}\",\"key\":\"{}\"}}",
            hx(new_device.pk.as_bytes())
        ),
    );
    assert_eq!(
        find(&o.view(&c.owner), "members", "member", &e)["status"],
        "pending_device"
    );
    assert_eq!(
        o.denied(&c.employee, r#"{"kind":"profile","name":"old"}"#),
        "not_member"
    );
    // The old phone shows no role and no rights it lost: the place moved to the new key.
    let old = o.view(&c.employee);
    assert_eq!(old["me"]["role"], "none");
    assert_eq!(old["me"]["can"], serde_json::json!(["join"]));
    let fresh = o.attest_for(&new_device.pk, "+79161234567");
    o.act(
        &new_device,
        &format!("{{\"kind\":\"phone_bind\",\"attest\":\"{fresh}\"}}"),
    );
    o.act(
        &new_device,
        r#"{"kind":"profile","name":"Eva on a new phone"}"#,
    );
    let v = o.view(&new_device);
    assert_eq!(v["me"]["member"], e);
    let m = find(&v, "members", "member", &e);
    assert_eq!(m["status"], "active");
    assert_eq!(m["name"], "Eva on a new phone");
    assert_eq!(m["phone_confirmed"], true);
}

#[test]
fn h_lanes_reach_only_their_employee_and_the_administrators() {
    let (o, c, _, _) = salaried();
    let e = hx(&c.employee.id);
    assert_eq!(arr(&o.view(&c.manager), "salary").len(), 0);
    assert_eq!(arr(&o.view(&c.manager), "pay").len(), 0);
    let ev = o.view(&c.employee);
    assert_eq!(arr(&ev, "salary").len(), 1);
    assert_eq!(arr(&ev, "salary")[0]["member"], e);
    let mine = slice(&o.hr, &c.employee.id).expect("slices");
    assert_eq!(
        split_chain(&mine).expect("chain").len(),
        split_chain(&o.hr).expect("chain").len()
    );
    assert!(slice(&o.hr, &c.manager.id).expect("slices").is_empty());
    let from_slice = view_with(
        &o.roster,
        &mine,
        c.employee.pk.as_bytes(),
        o.clock,
        Some(&o.service),
    )
    .expect("view");
    let full = view_with(
        &o.roster,
        &o.hr,
        c.employee.pk.as_bytes(),
        o.clock,
        Some(&o.service),
    )
    .expect("view");
    let a: Value = serde_json::from_str(&from_slice).expect("json");
    let b: Value = serde_json::from_str(&full).expect("json");
    for k in [
        "members", "salary", "pay", "due", "redeems", "rejected", "waiting",
    ] {
        assert_eq!(a[k], b[k], "{k}");
    }
}

// xorshift64: a fixed sequence, so a failing permutation can be replayed.
fn shuffle<T>(v: &mut [T], mut seed: u64) {
    for i in (1..v.len()).rev() {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        let j = (seed % (i as u64 + 1)) as usize;
        v.swap(i, j);
    }
}

fn chain(records: &[Record]) -> Vec<u8> {
    join_chain(records).expect("joins")
}

#[test]
fn the_fold_does_not_depend_on_the_order_of_arrival() {
    let (o, c, _, _) = salaried();
    let roster = split_chain(&o.roster).expect("chain");
    let hr = split_chain(&o.hr).expect("chain");
    for who in [&c.owner, &c.employee, &c.manager] {
        let canonical = view_with(
            &o.roster,
            &o.hr,
            who.pk.as_bytes(),
            o.clock,
            Some(&o.service),
        )
        .expect("view");
        for seed in 1..8u64 {
            let mut r = roster.clone();
            let mut h = hr.clone();
            shuffle(&mut r, seed);
            shuffle(&mut h, seed.wrapping_mul(0x9e37_79b9_7f4a_7c15));
            let got = view_with(
                &chain(&r),
                &chain(&h),
                who.pk.as_bytes(),
                o.clock,
                Some(&o.service),
            )
            .expect("view");
            assert_eq!(got, canonical, "seed {seed}");
        }
    }
    // Arrival in pieces through merge, in different orders, ends in one chain.
    let mut all: Vec<Record> = roster.iter().chain(hr.iter()).cloned().collect();
    let mut finals = Vec::new();
    for seed in 1..6u64 {
        shuffle(&mut all, seed);
        let mut have = Vec::new();
        for piece in all.chunks(3) {
            have = merge(&have, &chain(piece)).expect("merges").0;
        }
        finals.push(have);
    }
    assert!(finals.windows(2).all(|w| w[0] == w[1]));
}

#[test]
fn merge_is_idempotent_commutative_and_associative() {
    let (o, _, _, _) = salaried();
    let mut all = split_chain(&o.roster).expect("chain");
    all.extend(split_chain(&o.hr).expect("chain"));
    shuffle(&mut all, 42);
    assert!(all.len() >= 12, "the scenario has {} records", all.len());
    let a = chain(&all[..6]);
    let b = chain(&all[4..10]);
    let c = chain(&all[8..]);
    let (aa, added) = merge(&a, &a).expect("merges");
    assert_eq!(added, 0);
    assert_eq!(merge(&aa, &aa).expect("merges"), (aa.clone(), 0));
    assert_eq!(merge(&a, &[]).expect("merges").0, aa);
    assert_eq!(merge(&[], &a).expect("merges"), (aa.clone(), 6));
    let (ab, added_ab) = merge(&a, &b).expect("merges");
    let (ba, _) = merge(&b, &a).expect("merges");
    assert_eq!(ab, ba);
    assert_eq!(added_ab, 4);
    let left = merge(&ab, &c).expect("merges").0;
    let right = merge(&a, &merge(&b, &c).expect("merges").0)
        .expect("merges")
        .0;
    assert_eq!(left, right);
    assert!(merge(&a, &[1, 0, 0, 0, 9]).is_err());
    assert!(merge(&a[..a.len() - 1], &b).is_err());
}

fn reseal(rec: &Record, who: &Person, edit: impl Fn(&mut Header)) -> Record {
    let mut h = rec.header.clone();
    edit(&mut h);
    seal(&h, &rec.body, &who.sk).expect("seals")
}

fn view_of(o: &Org, who: &Person, roster: &[Record], hr: &[Record], now: u64) -> Value {
    let json = view_with(
        &chain(roster),
        &chain(hr),
        who.pk.as_bytes(),
        now,
        Some(&o.service),
    )
    .expect("view");
    serde_json::from_str(&json).expect("json")
}

fn reason_of(v: &Value, id: &[u8; 32]) -> Option<String> {
    arr(v, "rejected")
        .iter()
        .find(|r| r["record"] == hx(id))
        .map(|r| r["reason"].as_str().unwrap_or_default().to_string())
}

#[test]
fn structure_waits_or_rejects() {
    let (mut o, c) = founded();
    let profile = o.act(&c.employee, r#"{"kind":"profile","name":"Eva"}"#);
    let roster = split_chain(&o.roster).expect("chain");
    let hr = split_chain(&o.hr).expect("chain");
    let joined = roster
        .iter()
        .find(|r| r.header.author == c.employee.id && r.header.n == 0)
        .expect("join")
        .clone();

    // A missing predecessor makes the lane wait; it does not reject it.
    let gap: Vec<Record> = roster
        .iter()
        .filter(|r| r.id != joined.id)
        .cloned()
        .collect();
    let v = view_of(&o, &c.owner, &gap, &hr, o.clock);
    assert_eq!(v["waiting"], 1);
    assert!(reason_of(&v, &profile).is_none());

    // A record from beyond now plus five minutes waits for its time.
    let head = roster
        .iter()
        .find(|r| r.id == profile)
        .expect("profile")
        .clone();
    let ahead = reseal(&head, &c.employee, |h| {
        h.n += 1;
        h.prev = head.id;
        h.at_ms = head.header.at_ms + 10 * 60_000;
    });
    let mut more = roster.clone();
    more.push(ahead.clone());
    assert_eq!(view_of(&o, &c.owner, &more, &hr, o.clock)["waiting"], 1);
    let later = view_of(&o, &c.owner, &more, &hr, o.clock + 20 * 60_000);
    assert_eq!(later["waiting"], 0);
    assert!(reason_of(&later, &ahead.id).is_none());

    // The clock back inside a lane, a broken link, a forged signature, the wrong chain.
    let back = reseal(&head, &c.employee, |h| {
        h.n += 1;
        h.prev = head.id;
    });
    let skip = reseal(&head, &c.employee, |h| {
        h.n += 2;
        h.prev = head.id;
        h.at_ms += 1;
    });
    let mut forged = reseal(&head, &c.employee, |h| {
        h.n += 1;
        h.prev = head.id;
        h.at_ms += 7;
    });
    let last = forged.bytes.len() - 1;
    forged.bytes[last] ^= 1;
    let mut bad = roster.clone();
    bad.extend([back.clone(), skip.clone(), forged.clone()]);
    let mut wrong_hr = hr.clone();
    wrong_hr.push(head.clone());
    let v = view_of(&o, &c.owner, &bad, &wrong_hr, o.clock);
    assert_eq!(reason_of(&v, &back.id).as_deref(), Some("time_back"));
    assert_eq!(reason_of(&v, &skip.id).as_deref(), Some("bad_link"));
    assert_eq!(reason_of(&v, &forged.id).as_deref(), Some("bad_signature"));
    assert!(reason_of(&v, &profile).is_none());
    let without: Vec<Record> = roster.iter().filter(|r| r.id != head.id).cloned().collect();
    let alone = view_of(&o, &c.owner, &without, std::slice::from_ref(&head), o.clock);
    assert_eq!(reason_of(&alone, &head.id).as_deref(), Some("wrong_chain"));

    // A forged twin beside the signed record changes nothing.
    let mut twin = head.clone();
    let last = twin.bytes.len() - 1;
    twin.bytes[last] ^= 1;
    let mut with_twin = roster.clone();
    with_twin.push(twin);
    let base = view_of(&o, &c.owner, &roster, &hr, o.clock);
    let tw = view_of(&o, &c.owner, &with_twin, &hr, o.clock);
    assert_eq!(base["members"], tw["members"]);
    assert_eq!(arr(&tw, "rejected").len(), 0);
}

#[test]
fn a_foreign_genesis_does_not_take_the_roster() {
    let (o, c) = founded();
    let mut other = Org::new();
    other.clock = T0 - 1_000_000;
    other.act(
        &c.stranger,
        r#"{"kind":"genesis","name":"Hijack","owner_name":"Mallory"}"#,
    );
    other.act(
        &c.stranger,
        &format!("{{\"kind\":\"dept\",\"dept\":\"{KITCHEN}\",\"name\":\"x\"}}"),
    );
    let (mixed, added) = merge(&o.roster, &other.roster).expect("merges");
    assert_eq!(added, 2);
    for who in [&c.owner, &c.employee] {
        let json =
            view_with(&mixed, &o.hr, who.pk.as_bytes(), o.clock, Some(&o.service)).expect("view");
        let v: Value = serde_json::from_str(&json).expect("json");
        assert_eq!(v["org"]["name"], "Montana Coffee");
        let reasons: Vec<&str> = arr(&v, "rejected")
            .iter()
            .filter_map(|r| r["reason"].as_str())
            .collect();
        assert_eq!(reasons, vec!["second_genesis", "foreign_org"]);
    }
}

#[test]
fn a_shift_is_kept_by_its_member_and_confirmed_by_another() {
    let (mut o, c) = founded();
    // Nothing to close before a shift is open; one open shift at a time.
    assert_eq!(
        o.denied(&c.employee, r#"{"kind":"shift_close"}"#),
        "no_open_shift"
    );
    o.act(&c.employee, r#"{"kind":"shift_open"}"#);
    assert_eq!(
        o.denied(&c.employee, r#"{"kind":"shift_open"}"#),
        "shift_open"
    );
    let close = o.act(
        &c.employee,
        r#"{"kind":"shift_close","note":"closed the register"}"#,
    );
    let confirm = format!(
        "{{\"kind\":\"shift_confirm\",\"record\":\"{}\"}}",
        hx(&close)
    );
    // Nobody confirms their own shift; the manager of the employee's department does.
    assert_eq!(o.denied(&c.employee, &confirm), "denied");
    o.act(&c.manager, &confirm);
    assert_eq!(o.denied(&c.admin, &confirm), "already_confirmed");
    let e = hx(&c.employee.id);
    for who in [&c.owner, &c.manager, &c.employee] {
        let v = o.view(who);
        let s = &arr(&v, "shifts")[0];
        assert_eq!(s["member"], e.as_str());
        // Opened, a refused second opening (the test clock still moves a second), closed: two seconds.
        assert_eq!(s["seconds"], 2);
        assert_eq!(s["note"], "closed the register");
        assert_eq!(s["confirmed_by"], hx(&c.manager.id));
    }
    // The time of the week and of the month, the confirmed apart; the manager sees it, the stranger sees nobody's.
    let manager_time = o.view(&c.manager);
    let mine = find(&manager_time, "time", "member", &e);
    for key in ["week_s", "week_confirmed_s", "month_s", "month_confirmed_s"] {
        assert_eq!(mine[key], 2, "{key}");
    }
    assert!(arr(&o.view(&c.stranger), "time").is_empty());
    // The manager holds the shifts and still not the pay.
    let manager_sees = o.view(&c.manager);
    assert!(arr(&manager_sees, "salary").is_empty());
    let holders: Vec<&str> = arr(&manager_sees, "shifts")[0]["people"]
        .as_array()
        .expect("people")
        .iter()
        .map(|x| x.as_str().expect("hex"))
        .collect();
    assert!(holders.contains(&e.as_str()) && holders.contains(&hx(&c.manager.id).as_str()));
    assert!(!holders.contains(&hx(&c.stranger.id).as_str()));
}

#[test]
fn a_shift_opened_before_joining_is_refused() {
    let (mut o, c) = founded();
    let v = o.view(&c.owner);
    let org =
        mt_business::codec::hex_array::<32>(v["org"]["id"].as_str().expect("org")).expect("hex");
    // A record no door would write (the author's clock is raised past what it saw): sealed by hand, dated before the joining.
    // The fold applies records in their time order, so at that moment its author is nobody in the organisation yet.
    let body = mt_business::body::Body::ShiftOpen { node: [0; 16] }
        .encode()
        .expect("encodes");
    let h = Header {
        chain: mt_business::frame::CHAIN_H,
        kind: mt_business::body::KIND_SHIFT_OPEN,
        org,
        lane: mt_business::fold::shift_lane_of(&c.employee.id),
        n: 0,
        prev: [0; 32],
        at_ms: T0,
        author: c.employee.id,
    };
    let rec = seal(&h, &body, &c.employee.sk).expect("seals");
    let (merged, added) = merge(&o.hr, &join_chain([&rec]).expect("joins")).expect("merges");
    assert_eq!(added, 1);
    o.hr = merged;
    let v = o.view(&c.owner);
    assert!(arr(&v, "rejected")
        .iter()
        .any(|r| r["record"] == hx(&rec.id).as_str() && r["reason"] == "unknown_author"));
    assert!(arr(&v, "shifts").is_empty());
}

#[test]
fn an_invitation_is_revoked_by_its_secret() {
    let (mut o, c) = founded();
    let far = T0 + 30 * DAY;
    o.act(&c.admin, &invite("employee", None, 7, far));
    // The app that minted the invitation holds its secret, never the label that names it: the core names it.
    o.act(
        &c.admin,
        &format!("{{\"kind\":\"revoke\",\"secret\":\"{}\"}}", secret(7)),
    );
    let id = hx(&invite_id_of(&[7; 32]));
    assert_eq!(
        find(&o.view(&c.owner), "invites", "id", &id)["state"],
        "revoked"
    );
    assert_eq!(o.denied(&c.stranger, &join(7, "Late")), "invite_revoked");
}
