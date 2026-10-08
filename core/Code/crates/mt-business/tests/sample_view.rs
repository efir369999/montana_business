// THE SAMPLE OF THE VIEW (the iOS contract check, C-2.1): one rich organisation -- roster, departments, an open invitation,
// salary due, a pay and its receipt, the shop and a fulfilled purchase, an order from the word to the counter with its place
// and every step, stock, a channel with a letter and its edit, a confirmed shift, the journal and a rejected record -- and the
// owner's view of it, written where SAMPLE_OUT names (ignored by default; run with -- --ignored --nocapture).

use mt_business::codec::to_hex;
use mt_business::fold::member_id_of;
use mt_business::frame::{split_chain, CHAIN_R};
use mt_business::period::{period_key, PERIOD_DAY};
use mt_business::{author_with, merge, view_with};
use mt_crypto::{keypair_from_seed, PublicKey, SecretKey};
use serde_json::Value;

const T0: u64 = 1_791_234_567_890;
const DAY: u64 = 86_400_000;
const WAREHOUSE: &str = "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf";
const SHOP: &str = "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf";
const SNEAKER: &str = "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf";
const KITCHEN: &str = "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf";
const BOX1: &str = "e0e1e2e3e4e5e6e7e8e9eaebecedeeef";
const MUG: &str = "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff";
const GROUP: &str = "0102030405060708090a0b0c0d0e0f10";
const BOX2: &str = "e1e1e2e3e4e5e6e7e8e9eaebecedeeef";

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

fn key32(b: u8) -> String {
    hx(&[b; 32])
}

#[derive(Clone)]
struct Org {
    roster: Vec<u8>,
    private: Vec<u8>,
    clock: u64,
}

impl Org {
    fn frame(&mut self, who: &Person, cmd: &str) -> Vec<u8> {
        self.clock += 1000;
        author_with(
            who.sk.as_bytes(),
            who.pk.as_bytes(),
            &self.roster,
            &self.private,
            cmd,
            self.clock,
            None,
        )
        .unwrap_or_else(|e| panic!("{cmd}: {e}"))
    }
    fn take(&mut self, frame: &[u8]) -> [u8; 32] {
        let recs = split_chain(frame).expect("one frame");
        let target = if recs[0].header.chain == CHAIN_R {
            &mut self.roster
        } else {
            &mut self.private
        };
        let (merged, _) = merge(target, frame).expect("merges");
        *target = merged;
        recs[0].id
    }
    fn act(&mut self, who: &Person, cmd: &str) -> [u8; 32] {
        let f = self.frame(who, cmd);
        self.take(&f)
    }
}

#[test]
#[ignore]
fn the_view_of_a_rich_organisation() {
    let owner = person(21);
    let manager = person(22);
    let keeper = person(23);
    let clerk = person(24);
    let late = person(25);
    let mut o = Org {
        roster: Vec::new(),
        private: Vec::new(),
        clock: T0,
    };
    let invite = |role: &str, dept: &str, n: u8| {
        format!(
            "{{\"kind\":\"invite\",\"secret\":\"{}\",\"role\":\"{role}\"{dept},\"expires_ms\":{}}}",
            key32(n),
            T0 + 30 * DAY
        )
    };
    let join = |n: u8, name: &str| {
        format!("{{\"kind\":\"join\",\"secret\":\"{}\",\"name\":\"{name}\",\"card\":\"montana.xxx/c/{n}\"}}", key32(n))
    };
    let lines = |qty: u32| {
        format!("[{{\"item\":\"{SNEAKER}\",\"size\":\"42\",\"qty\":{qty},\"coins\":900}}]")
    };
    let step = |kind: &str, rest: String| {
        format!(
            "{{\"kind\":\"{kind}\",\"order\":\"{}\"{rest}}}",
            key32(0x51)
        )
    };

    o.act(&owner, r#"{"kind":"genesis","name":"Montana Shoes","owner_name":"Anna","owner_card":"montana.xxx/c/anna"}"#);
    o.act(
        &owner,
        &format!("{{\"kind\":\"dept\",\"dept\":\"{KITCHEN}\",\"name\":\"Kitchen\"}}"),
    );
    o.act(
        &owner,
        &invite("manager", &format!(",\"dept\":\"{KITCHEN}\""), 1),
    );
    o.act(&manager, &join(1, "Mila"));
    o.act(&owner, &invite("employee", "", 2));
    o.act(&keeper, &join(2, "Kim"));
    o.act(&owner, &invite("employee", "", 3));
    o.act(&clerk, &join(3, "Carl"));
    o.act(&owner, &invite("employee", "", 8)); // stays open
    o.act(
        &owner,
        &format!(
            "{{\"kind\":\"assign\",\"member\":\"{}\",\"dept\":\"{KITCHEN}\",\"title\":\"Keeper\"}}",
            hx(&keeper.id)
        ),
    );
    o.act(&owner, &format!("{{\"kind\":\"node\",\"node\":\"{WAREHOUSE}\",\"node_kind\":2,\"name\":\"North\",\"place\":\"Khimki\"}}"));
    o.act(
        &owner,
        &format!(
            "{{\"kind\":\"node\",\"node\":\"{SHOP}\",\"node_kind\":5,\"name\":\"Tverskaya\"}}"
        ),
    );
    o.act(&owner, &format!("{{\"kind\":\"item\",\"item\":\"{SNEAKER}\",\"title\":\"Sneaker\",\"sizes\":\"40,41,42\",\"unit\":\"pair\"}}"));
    o.act(
        &owner,
        &format!(
            "{{\"kind\":\"node_staff\",\"node\":\"{WAREHOUSE}\",\"member\":\"{}\"}}",
            hx(&keeper.id)
        ),
    );
    o.act(
        &owner,
        &format!(
            "{{\"kind\":\"node_staff\",\"node\":\"{SHOP}\",\"member\":\"{}\"}}",
            hx(&clerk.id)
        ),
    );

    // Salary, a pay of the first day, its receipt; the shop, a purchase and its handing over.
    o.act(
        &owner,
        &format!(
            "{{\"kind\":\"salary\",\"member\":\"{}\",\"coins\":100,\"period\":1,\"from_ms\":{T0}}}",
            hx(&keeper.id)
        ),
    );
    let key = period_key(PERIOD_DAY, T0).expect("key");
    let pay = o.act(&owner, &format!("{{\"kind\":\"pay\",\"member\":\"{}\",\"pay_kind\":1,\"period_key\":{key},\"coins\":100,\"note\":\"day one\"}}", hx(&keeper.id)));
    o.act(
        &keeper,
        &format!("{{\"kind\":\"receipt\",\"record\":\"{}\"}}", hx(&pay)),
    );
    o.act(
        &owner,
        &format!(
            "{{\"kind\":\"offer\",\"item\":\"{MUG}\",\"title\":\"Mug\",\"price\":30,\"stock\":3}}"
        ),
    );
    let redeem = o.act(
        &clerk,
        &format!("{{\"kind\":\"redeem\",\"item\":\"{MUG}\",\"qty\":1,\"coins\":30}}"),
    );
    o.act(
        &owner,
        &format!("{{\"kind\":\"fulfil\",\"record\":\"{}\"}}", hx(&redeem)),
    );

    // The order from the word to the counter.
    o.act(&manager, &format!("{{\"kind\":\"order\",\"order\":\"{}\",\"from\":\"{WAREHOUSE}\",\"to\":\"{SHOP}\",\"lines\":{},\"source\":2,\"voice\":\"{}\",\"text\":\"two pairs of forty-two\"}}", key32(0x51), lines(2), key32(0x77)));
    o.act(
        &keeper,
        &step("confirm", format!(",\"node\":\"{WAREHOUSE}\"")),
    );
    o.act(
        &keeper,
        &step(
            "pack",
            format!(
                ",\"place\":\"{BOX1}\",\"node\":\"{WAREHOUSE}\",\"lines\":{}",
                lines(2)
            ),
        ),
    );
    o.act(
        &keeper,
        &step(
            "handoff",
            format!(",\"place\":\"{BOX1}\",\"from\":\"{WAREHOUSE}\",\"to\":\"{SHOP}\""),
        ),
    );
    o.act(&clerk, &step("accept", format!(",\"place\":\"{BOX1}\",\"node\":\"{SHOP}\",\"condition\":1,\"note\":\"all there\",\"photo\":\"{}\"", key32(0x78))));
    o.act(
        &clerk,
        &step("scan", format!(",\"place\":\"{BOX1}\",\"node\":\"{SHOP}\"")),
    );
    o.act(
        &clerk,
        &step(
            "shelf",
            format!(",\"node\":\"{SHOP}\",\"lines\":{}", lines(2)),
        ),
    );
    o.act(
        &clerk,
        &step(
            "sale",
            format!(",\"node\":\"{SHOP}\",\"lines\":{}", lines(1)),
        ),
    );
    o.act(
        &clerk,
        &step(
            "issue",
            format!(",\"place\":\"{BOX1}\",\"issue_kind\":1,\"note\":\"a returned pair\""),
        ),
    );

    // A second order, by hand, still on its way: the text source (no voice) and a place in transit (no holder).
    let step2 = |kind: &str, rest: String| {
        format!(
            "{{\"kind\":\"{kind}\",\"order\":\"{}\"{rest}}}",
            key32(0x52)
        )
    };
    o.act(&manager, &format!("{{\"kind\":\"order\",\"order\":\"{}\",\"from\":\"{WAREHOUSE}\",\"to\":\"{SHOP}\",\"lines\":{},\"text\":\"one pair\"}}", key32(0x52), lines(1)));
    o.act(
        &keeper,
        &step2("confirm", format!(",\"node\":\"{WAREHOUSE}\"")),
    );
    o.act(
        &keeper,
        &step2(
            "pack",
            format!(
                ",\"place\":\"{BOX2}\",\"node\":\"{WAREHOUSE}\",\"lines\":{}",
                lines(1)
            ),
        ),
    );
    o.act(
        &keeper,
        &step2(
            "handoff",
            format!(",\"place\":\"{BOX2}\",\"from\":\"{WAREHOUSE}\",\"to\":\"{SHOP}\""),
        ),
    );

    // A third order, confirmed and not packed: it stands at its supplier now (standing is not null).
    o.act(&manager, &format!("{{\"kind\":\"order\",\"order\":\"{}\",\"from\":\"{WAREHOUSE}\",\"to\":\"{SHOP}\",\"lines\":{},\"text\":\"one more\"}}", key32(0x53), lines(1)));
    o.act(
        &keeper,
        &format!(
            "{{\"kind\":\"confirm\",\"order\":\"{}\",\"node\":\"{WAREHOUSE}\"}}",
            key32(0x53)
        ),
    );

    // A channel with a letter and its edit.
    let news = key32(0x61);
    o.act(&owner, &format!("{{\"kind\":\"open\",\"chat\":\"{news}\",\"chat_kind\":2,\"name\":\"News\",\"group\":\"{GROUP}\"}}"));
    let said = o.act(
        &owner,
        &format!(
            "{{\"kind\":\"letter\",\"chat\":\"{news}\",\"mid\":\"m1\",\"text\":\"Open at nine\"}}"
        ),
    );
    o.act(&owner, &format!("{{\"kind\":\"edit\",\"chat\":\"{news}\",\"record\":\"{}\",\"mid\":\"m1\",\"text\":\"Open at ten\"}}", hx(&said)));
    o.act(&owner, &format!("{{\"kind\":\"letter\",\"chat\":\"{news}\",\"mid\":\"m2\",\"text\":\"Closed on Sunday\"}}"));

    // A shift kept and confirmed.
    o.act(&keeper, r#"{"kind":"shift_open"}"#);
    let close = o.act(
        &keeper,
        r#"{"kind":"shift_close","note":"closed the register"}"#,
    );
    o.act(
        &manager,
        &format!(
            "{{\"kind\":\"shift_confirm\",\"record\":\"{}\"}}",
            hx(&close)
        ),
    );
    o.act(&clerk, r#"{"kind":"shift_open"}"#); // still open: no close, no seconds, nobody confirmed

    // A rejected record: a join by an invitation the owner revoked a moment before, written on a phone that had not heard it.
    o.act(&owner, &invite("employee", "", 9));
    let mut beside = o.clone();
    o.act(
        &owner,
        &format!("{{\"kind\":\"revoke\",\"secret\":\"{}\"}}", key32(9)),
    );
    beside.clock = o.clock + 1000;
    let late_join = beside.frame(&late, &join(9, "Lev"));
    o.take(&late_join);

    let at = T0 + 3 * DAY + 3_600_000;
    let raw = view_with(&o.roster, &o.private, owner.pk.as_bytes(), at, None).expect("view");
    let v: Value = serde_json::from_str(&raw).expect("JSON");
    for list in [
        "members", "depts", "invites", "salary", "pay", "due", "offers", "redeems", "rejected",
        "items", "nodes", "orders", "stock", "chats", "journal", "shifts", "time",
    ] {
        assert!(
            !v[list].as_array().expect(list).is_empty(),
            "{list} is empty in the sample"
        );
    }
    let order = &v["orders"][0];
    for list in ["lines", "places", "steps", "people"] {
        assert!(
            !order[list].as_array().expect(list).is_empty(),
            "orders[].{list} is empty"
        );
    }
    // The chats carry no letters (the group's feed draws them); an order still to pack names what no box holds yet.
    assert!(v["chats"][0].get("letters").is_none());
    assert!(v["orders"]
        .as_array()
        .expect("orders")
        .iter()
        .any(|x| !x["unpacked"].as_array().expect("unpacked").is_empty()));
    // Every order field the core may leave null is null once and filled once, so the contract check meets its keys.
    let orders = v["orders"].as_array().expect("orders");
    assert!(orders.iter().any(|x| x["standing"].is_null()));
    assert!(orders.iter().any(|x| x["standing"].is_object()));
    let pretty = serde_json::to_string_pretty(&v).expect("pretty");
    match std::env::var("SAMPLE_OUT") {
        Ok(path) => std::fs::write(&path, pretty + "\n").expect("written"),
        Err(_) => println!("{pretty}"),
    }
}
