// Chains S and C: an order from the manager's word to the shop's counter, where each place is by its keeper's word, who
// may take which step, who sees an order, stock by node; the organisation's chats and channels; and the property the
// contract names -- one set of records in any order of arrival gives one view.

use mt_business::codec::to_hex;
use mt_business::fold::member_id_of;
use mt_business::frame::{join_chain, split_chain, CHAIN_R};
use mt_business::{author_with, merge, slice, view_with, BizError};
use mt_crypto::{keypair_from_seed, PublicKey, SecretKey};
use serde_json::Value;

const T0: u64 = 1_791_234_567_890;
const DAY: u64 = 86_400_000;
const WAREHOUSE: &str = "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf";
const SHOP: &str = "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf";
const SNEAKER: &str = "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf";
const KITCHEN: &str = "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf";
const BOX1: &str = "e0e1e2e3e4e5e6e7e8e9eaebecedeeef";

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

struct Org {
    roster: Vec<u8>,
    private: Vec<u8>,
    clock: u64,
}

impl Org {
    fn try_do(&mut self, who: &Person, cmd: &str) -> Result<[u8; 32], BizError> {
        self.clock += 1000;
        let frame = author_with(
            who.sk.as_bytes(),
            who.pk.as_bytes(),
            &self.roster,
            &self.private,
            cmd,
            self.clock,
            None,
        )?;
        let recs = split_chain(&frame).expect("one frame");
        assert_eq!(recs.len(), 1);
        let target = if recs[0].header.chain == CHAIN_R {
            &mut self.roster
        } else {
            &mut self.private
        };
        let (merged, added) = merge(target, &frame).expect("merges");
        assert_eq!(added, 1);
        *target = merged;
        Ok(recs[0].id)
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

    fn raw_view(&self, who: &Person) -> String {
        view_with(
            &self.roster,
            &self.private,
            who.pk.as_bytes(),
            self.clock,
            None,
        )
        .expect("view")
    }

    fn view(&self, who: &Person) -> Value {
        serde_json::from_str(&self.raw_view(who)).expect("view is JSON")
    }

    // The chats as the fold holds them: a letter's link lives in chain C, the Messenger's group feed draws the letter.
    fn fold(&self, who: &Person) -> mt_business::fold::Fold {
        let r = split_chain(&self.roster).expect("splits");
        let h = split_chain(&self.private).expect("splits");
        mt_business::fold::fold(&r, &h, self.clock, Some(&who.pk), None)
    }
}

struct Cast {
    owner: Person,
    manager: Person,
    keeper: Person,
    clerk: Person,
    outsider: Person,
}

fn invite(role: &str, dept: Option<&str>, n: u8) -> String {
    let dept = dept.map_or(String::new(), |d| format!(",\"dept\":\"{d}\""));
    format!(
        "{{\"kind\":\"invite\",\"secret\":\"{}\",\"role\":\"{role}\"{dept},\"expires_ms\":{}}}",
        key32(n),
        T0 + 30 * DAY
    )
}

fn join(n: u8, name: &str) -> String {
    format!(
        "{{\"kind\":\"join\",\"secret\":\"{}\",\"name\":\"{name}\"}}",
        key32(n)
    )
}

fn lines(qty: u32) -> String {
    format!("[{{\"item\":\"{SNEAKER}\",\"size\":\"42\",\"qty\":{qty},\"coins\":900}}]")
}

fn order_cmd(order: &str, text: &str) -> String {
    format!(
        "{{\"kind\":\"order\",\"order\":\"{order}\",\"from\":\"{WAREHOUSE}\",\"to\":\"{SHOP}\",\"lines\":{},\"text\":\"{text}\"}}",
        lines(2)
    )
}

fn step(kind: &str, order: &str, rest: &str) -> String {
    format!("{{\"kind\":\"{kind}\",\"order\":\"{order}\"{rest}}}")
}

fn founded() -> (Org, Cast) {
    let c = Cast {
        owner: person(11),
        manager: person(12),
        keeper: person(13),
        clerk: person(14),
        outsider: person(15),
    };
    let mut o = Org {
        roster: Vec::new(),
        private: Vec::new(),
        clock: T0,
    };
    o.act(
        &c.owner,
        r#"{"kind":"genesis","name":"Montana Shoes","owner_name":"Anna"}"#,
    );
    o.act(
        &c.owner,
        &format!("{{\"kind\":\"dept\",\"dept\":\"{KITCHEN}\",\"name\":\"Kitchen\"}}"),
    );
    o.act(&c.owner, &invite("manager", Some(KITCHEN), 1));
    o.act(&c.manager, &join(1, "Mila"));
    o.act(&c.owner, &invite("employee", None, 2));
    o.act(&c.keeper, &join(2, "Kim"));
    o.act(&c.owner, &invite("employee", None, 3));
    o.act(&c.clerk, &join(3, "Carl"));
    o.act(&c.owner, &invite("employee", None, 4));
    o.act(&c.outsider, &join(4, "Otto"));
    o.act(
        &c.owner,
        &format!("{{\"kind\":\"node\",\"node\":\"{WAREHOUSE}\",\"node_kind\":2,\"name\":\"North\",\"place\":\"Khimki\"}}"),
    );
    o.act(
        &c.owner,
        &format!(
            "{{\"kind\":\"node\",\"node\":\"{SHOP}\",\"node_kind\":5,\"name\":\"Tverskaya\"}}"
        ),
    );
    o.act(
        &c.owner,
        &format!("{{\"kind\":\"item\",\"item\":\"{SNEAKER}\",\"title\":\"Sneaker\",\"sizes\":\"40,41,42\",\"unit\":\"pair\"}}"),
    );
    o.act(
        &c.owner,
        &format!(
            "{{\"kind\":\"node_staff\",\"node\":\"{WAREHOUSE}\",\"member\":\"{}\"}}",
            hx(&c.keeper.id)
        ),
    );
    o.act(
        &c.owner,
        &format!(
            "{{\"kind\":\"node_staff\",\"node\":\"{SHOP}\",\"member\":\"{}\"}}",
            hx(&c.clerk.id)
        ),
    );
    (o, c)
}

fn delivered() -> (Org, Cast, String) {
    let (mut o, c) = founded();
    let order = key32(0x51);
    o.act(&c.manager, &order_cmd(&order, "two pairs of forty-two"));
    o.act(
        &c.keeper,
        &step("confirm", &order, &format!(",\"node\":\"{WAREHOUSE}\"")),
    );
    o.act(
        &c.keeper,
        &step(
            "pack",
            &order,
            &format!(
                ",\"place\":\"{BOX1}\",\"node\":\"{WAREHOUSE}\",\"lines\":{}",
                lines(2)
            ),
        ),
    );
    o.act(
        &c.keeper,
        &step(
            "handoff",
            &order,
            &format!(",\"place\":\"{BOX1}\",\"from\":\"{WAREHOUSE}\",\"to\":\"{SHOP}\""),
        ),
    );
    o.act(
        &c.clerk,
        &step(
            "accept",
            &order,
            &format!(",\"place\":\"{BOX1}\",\"node\":\"{SHOP}\",\"condition\":1"),
        ),
    );
    (o, c, order)
}

fn stock(v: &Value, node: &str) -> (u64, u64, u64) {
    let row = v["stock"]
        .as_array()
        .expect("stock")
        .iter()
        .find(|r| r["node"] == node && r["item"] == SNEAKER && r["size"] == "42");
    match row {
        Some(r) => (
            r["on_hand"].as_u64().expect("on_hand"),
            r["counter"].as_u64().expect("counter"),
            r["sold"].as_u64().expect("sold"),
        ),
        None => (0, 0, 0),
    }
}

#[test]
fn the_order_travels_from_the_word_to_the_counter() {
    let (mut o, c) = founded();
    let order = key32(0x51);
    o.act(&c.manager, &order_cmd(&order, "two pairs of forty-two"));
    assert_eq!(o.view(&c.clerk)["orders"][0]["state"], "new");
    o.act(
        &c.keeper,
        &step("confirm", &order, &format!(",\"node\":\"{WAREHOUSE}\"")),
    );
    o.act(
        &c.keeper,
        &step(
            "pack",
            &order,
            &format!(
                ",\"place\":\"{BOX1}\",\"node\":\"{WAREHOUSE}\",\"lines\":{}",
                lines(2)
            ),
        ),
    );
    assert_eq!(stock(&o.view(&c.keeper), WAREHOUSE), (2, 0, 0));
    let handoff = step(
        "handoff",
        &order,
        &format!(",\"place\":\"{BOX1}\",\"from\":\"{WAREHOUSE}\",\"to\":\"{SHOP}\""),
    );
    // Only the keeper of the place hands it on.
    assert_eq!(o.denied(&c.clerk, &handoff), "denied");
    o.act(&c.keeper, &handoff);
    let v = o.view(&c.clerk);
    let ord = &v["orders"][0];
    assert_eq!(ord["state"], "in_transit");
    assert_eq!(ord["places"][0]["holder"], Value::Null);
    assert_eq!(ord["places"][0]["transit_from"], WAREHOUSE);
    assert_eq!(ord["places"][0]["transit_to"], SHOP);
    assert_eq!(stock(&o.view(&c.keeper), WAREHOUSE), (0, 0, 0));
    let accept = step(
        "accept",
        &order,
        &format!(",\"place\":\"{BOX1}\",\"node\":\"{SHOP}\",\"condition\":1"),
    );
    // Where a place is comes from the one who received it, nobody else.
    assert_eq!(o.denied(&c.keeper, &accept), "denied");
    o.act(&c.clerk, &accept);
    let v = o.view(&c.clerk);
    assert_eq!(v["orders"][0]["state"], "delivered");
    assert_eq!(v["orders"][0]["places"][0]["holder"], SHOP);
    assert_eq!(stock(&v, SHOP), (2, 0, 0));
    o.act(
        &c.clerk,
        &step(
            "shelf",
            &order,
            &format!(",\"node\":\"{SHOP}\",\"lines\":{}", lines(2)),
        ),
    );
    o.act(
        &c.clerk,
        &step(
            "sale",
            &order,
            &format!(",\"node\":\"{SHOP}\",\"lines\":{}", lines(1)),
        ),
    );
    assert_eq!(
        o.denied(
            &c.clerk,
            &step(
                "sale",
                &order,
                &format!(",\"node\":\"{SHOP}\",\"lines\":{}", lines(2))
            )
        ),
        "short_counter"
    );
    let v = o.view(&c.owner);
    assert_eq!(stock(&v, SHOP), (0, 1, 1));
    let kinds: Vec<&str> = v["orders"][0]["steps"]
        .as_array()
        .expect("steps")
        .iter()
        .map(|s| s["kind"].as_str().expect("kind"))
        .collect();
    assert_eq!(
        kinds,
        ["order", "confirm", "pack", "handoff", "accept", "shelf", "sale"]
    );
}

#[test]
fn a_place_moves_only_by_its_keepers_word() {
    let (mut o, c, order) = delivered();
    // Accepted at the shop: the warehouse no longer holds it, and nobody accepts it twice.
    assert_eq!(
        o.denied(
            &c.keeper,
            &step(
                "handoff",
                &order,
                &format!(",\"place\":\"{BOX1}\",\"from\":\"{WAREHOUSE}\",\"to\":\"{SHOP}\"")
            )
        ),
        "not_holder"
    );
    assert_eq!(
        o.denied(
            &c.clerk,
            &step(
                "accept",
                &order,
                &format!(",\"place\":\"{BOX1}\",\"node\":\"{SHOP}\"")
            )
        ),
        "not_addressed"
    );
    // Goods on the counter stay counted: the box cannot leave with them.
    o.act(
        &c.clerk,
        &step(
            "shelf",
            &order,
            &format!(",\"node\":\"{SHOP}\",\"lines\":{}", lines(1)),
        ),
    );
    assert_eq!(
        o.denied(
            &c.clerk,
            &step(
                "handoff",
                &order,
                &format!(",\"place\":\"{BOX1}\",\"from\":\"{SHOP}\",\"to\":\"{WAREHOUSE}\"")
            )
        ),
        "goods_shelved"
    );
    // A second order: nothing is packed before the supplier confirms; a cancelled order takes no new work.
    let second = key32(0x52);
    o.act(&c.clerk, &order_cmd(&second, "the shop's own order"));
    let pack = step(
        "pack",
        &second,
        &format!(
            ",\"place\":\"{}\",\"node\":\"{WAREHOUSE}\",\"lines\":{}",
            "f1".repeat(16),
            lines(1)
        ),
    );
    assert_eq!(o.denied(&c.keeper, &pack), "not_confirmed");
    assert_eq!(o.denied(&c.keeper, &step("cancel", &second, "")), "denied");
    o.act(
        &c.clerk,
        &step("cancel", &second, ",\"note\":\"not needed\""),
    );
    assert_eq!(
        o.denied(
            &c.keeper,
            &step("confirm", &second, &format!(",\"node\":\"{WAREHOUSE}\""))
        ),
        "order_cancelled"
    );
    // A person of no node orders for nobody.
    assert_eq!(
        o.denied(&c.outsider, &order_cmd(&key32(0x53), "mine")),
        "denied"
    );
}

#[test]
fn an_order_is_seen_by_the_people_of_its_path_only() {
    let (o, c, order) = delivered();
    let v = o.view(&c.outsider);
    assert_eq!(v["orders"].as_array().expect("orders").len(), 0);
    assert_eq!(v["stock"].as_array().expect("stock").len(), 0);
    let v = o.view(&c.keeper);
    assert_eq!(v["orders"][0]["id"], order.as_str());
    let people: Vec<String> = v["orders"][0]["people"]
        .as_array()
        .expect("people")
        .iter()
        .map(|p| p.as_str().expect("hex").to_owned())
        .collect();
    for who in [&c.owner, &c.manager, &c.keeper, &c.clerk] {
        assert!(people.contains(&hx(&who.id)));
    }
    assert!(!people.contains(&hx(&c.outsider.id)));
    // The journal shows what the viewer may read: the keeper sees the order's steps, the outsider none of them.
    let kinds = |who: &Person| -> Vec<String> {
        o.view(who)["journal"]
            .as_array()
            .expect("journal")
            .iter()
            .map(|r| {
                format!(
                    "{}:{}",
                    r["chain"].as_str().expect("chain"),
                    r["kind"].as_str().expect("kind")
                )
            })
            .collect()
    };
    let keeper = kinds(&c.keeper);
    assert!(keeper.contains(&"S:order".to_owned()) && keeper.contains(&"S:accept".to_owned()));
    assert!(keeper.contains(&"R:genesis".to_owned()));
    assert!(!kinds(&c.outsider).iter().any(|k| k.starts_with("S:")));
    // The order's lane is handed whole: every step of it, nothing of another lane.
    let lane = mt_business::codec::hex_array::<32>(&order).expect("hex");
    let handed = split_chain(&slice(&o.private, &lane).expect("slice")).expect("splits");
    assert_eq!(handed.len(), 5);
    assert!(handed.iter().all(|r| r.header.lane == lane));
}

#[test]
fn one_set_of_records_in_any_order_gives_one_view() {
    let (mut o, c, order) = delivered();
    o.act(
        &c.clerk,
        &step(
            "shelf",
            &order,
            &format!(",\"node\":\"{SHOP}\",\"lines\":{}", lines(2)),
        ),
    );
    o.act(
        &c.clerk,
        &step(
            "sale",
            &order,
            &format!(",\"node\":\"{SHOP}\",\"lines\":{}", lines(1)),
        ),
    );
    // The streams handed to the fold unsorted, newest first: the fold alone has to put them in order.
    let reversed = |stream: &[u8]| {
        let mut recs = split_chain(stream).expect("splits");
        recs.reverse();
        join_chain(&recs).expect("joins")
    };
    let shuffled = Org {
        roster: reversed(&o.roster),
        private: reversed(&o.private),
        clock: o.clock,
    };
    for who in [&c.owner, &c.keeper, &c.clerk, &c.outsider] {
        assert_eq!(o.raw_view(who), shuffled.raw_view(who));
    }
}

#[test]
fn chats_and_channels_speak_by_the_roster() {
    let (mut o, c) = founded();
    let news = key32(0x61);
    let team = key32(0x62);
    let chat = |kind: &str, chat: &str, rest: &str| {
        format!("{{\"kind\":\"{kind}\",\"chat\":\"{chat}\"{rest}}}")
    };
    // A channel is the administrators' voice to everyone.
    assert_eq!(
        o.denied(
            &c.manager,
            &chat("open", &news, ",\"chat_kind\":2,\"name\":\"News\"")
        ),
        "denied"
    );
    o.act(
        &c.owner,
        &chat("open", &news, ",\"chat_kind\":2,\"name\":\"News\""),
    );
    let post = o.act(
        &c.owner,
        &chat("letter", &news, ",\"mid\":\"m1\",\"text\":\"Open at nine\""),
    );
    assert_eq!(
        o.denied(
            &c.keeper,
            &chat("letter", &news, ",\"mid\":\"m2\",\"text\":\"me too\"")
        ),
        "denied"
    );
    // Its people answer under a post: a comment is a listener's, a post stays the administrators'.
    let comment = o.act(
        &c.keeper,
        &chat(
            "letter",
            &news,
            ",\"mid\":\"m5\",\"text\":\"me too\",\"letter_kind\":2",
        ),
    );
    assert_eq!(
        o.denied(
            &c.keeper,
            &chat(
                "letter",
                &news,
                ",\"mid\":\"m6\",\"text\":\"x\",\"letter_kind\":3",
            )
        ),
        "bad_letter_kind"
    );
    let seen = o.fold(&c.keeper);
    let channel = seen
        .chats
        .chats
        .values()
        .find(|x| x.name == "News")
        .expect("the channel");
    assert_eq!(channel.letters.len(), 2);
    assert_eq!(channel.letters[&post].mid, "m1");
    assert_eq!(channel.letters[&comment].kind, 2);
    // The view carries the chats, never their letters: the screens draw them from the group's own feed.
    assert!(o.view(&c.keeper)["chats"][0].get("letters").is_none());
    // A department's group: its manager opens it; only its people speak in it and see it.
    o.act(
        &c.manager,
        &chat(
            "open",
            &team,
            &format!(",\"chat_kind\":1,\"name\":\"Kitchen\",\"dept\":\"{KITCHEN}\""),
        ),
    );
    let said = o.act(
        &c.manager,
        &chat(
            "letter",
            &team,
            ",\"mid\":\"m3\",\"text\":\"Kitchen at ten\"",
        ),
    );
    assert_eq!(
        o.denied(
            &c.keeper,
            &chat("letter", &team, ",\"mid\":\"m4\",\"text\":\"x\"")
        ),
        "denied"
    );
    // A comment lives under a channel's post alone: in a group its own people write letters.
    assert_eq!(
        o.denied(
            &c.manager,
            &chat(
                "letter",
                &team,
                ",\"mid\":\"m7\",\"text\":\"x\",\"letter_kind\":2",
            )
        ),
        "bad_letter_kind"
    );
    let keeper_sees: Vec<String> = o.view(&c.keeper)["chats"]
        .as_array()
        .expect("chats")
        .iter()
        .map(|x| x["name"].as_str().expect("name").to_owned())
        .collect();
    assert_eq!(keeper_sees, ["News"]);
    // A letter is edited by its author alone; an administrator may take it down.
    let edit = chat(
        "edit",
        &team,
        &format!(
            ",\"record\":\"{}\",\"mid\":\"m3\",\"text\":\"Kitchen at eleven\"",
            hx(&said)
        ),
    );
    assert_eq!(o.denied(&c.owner, &edit), "denied");
    o.act(&c.manager, &edit);
    o.act(
        &c.owner,
        &chat("delete", &team, &format!(",\"record\":\"{}\"", hx(&said))),
    );
    let f = o.fold(&c.manager);
    let group = f
        .chats
        .chats
        .values()
        .find(|x| x.name == "Kitchen")
        .expect("the group");
    // The core names the edited words: SHA-256(label, mid, 0x00, text) -- swapping mid and text, or dropping the 0x00, misses.
    let named = mt_crypto::hash(
        mt_business::domain::DOMAIN_LETTER,
        &[b"m3", &[0u8], b"Kitchen at eleven"],
    );
    assert_eq!(group.letters[&said].edited, Some(named));
    assert!(group.letters[&said].deleted);
}

#[test]
fn an_order_tells_what_no_box_holds_yet() {
    let (mut o, c) = founded();
    let order = key32(0x57);
    let line = |size: &str, qty: u32| {
        format!("{{\"item\":\"{SNEAKER}\",\"size\":\"{size}\",\"qty\":{qty},\"coins\":900}}")
    };
    o.act(
        &c.manager,
        &format!(
            "{{\"kind\":\"order\",\"order\":\"{order}\",\"from\":\"{WAREHOUSE}\",\"to\":\"{SHOP}\",\"lines\":[{},{}],\"text\":\"x\"}}",
            line("42", 2),
            line("41", 1)
        ),
    );
    o.act(
        &c.keeper,
        &step("confirm", &order, &format!(",\"node\":\"{WAREHOUSE}\"")),
    );
    let left = |o: &Org| -> Vec<(String, u64)> {
        let v = o.view(&c.keeper);
        let row = v["orders"]
            .as_array()
            .expect("orders")
            .iter()
            .find(|x| x["id"] == order.as_str())
            .expect("the order")
            .clone();
        row["unpacked"]
            .as_array()
            .expect("unpacked")
            .iter()
            .map(|l| {
                assert_eq!(l["item"], SNEAKER);
                (
                    l["size"].as_str().expect("size").to_owned(),
                    l["qty"].as_u64().expect("qty"),
                )
            })
            .collect()
    };
    // In the order's own order of lines, not sorted by size: a map's order would read 41 first.
    assert_eq!(left(&o), [("42".to_owned(), 2), ("41".to_owned(), 1)]);
    let pack = |o: &mut Org, place: &str, lines: String| {
        o.act(
            &c.keeper,
            &step(
                "pack",
                &order,
                &format!(",\"place\":\"{place}\",\"node\":\"{WAREHOUSE}\",\"lines\":{lines}"),
            ),
        );
    };
    // A box of one size takes nothing of the other: matching by the item alone would take one pair of 42 here.
    pack(&mut o, BOX1, format!("[{}]", line("41", 1)));
    assert_eq!(left(&o), [("42".to_owned(), 2)]);
    pack(
        &mut o,
        "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
        format!("[{}]", line("42", 2)),
    );
    assert!(left(&o).is_empty());
}

#[test]
fn a_box_does_not_grow_on_the_road() {
    let (mut o, c) = founded();
    let order = key32(0x54);
    o.act(&c.manager, &order_cmd(&order, "one pair"));
    o.act(
        &c.keeper,
        &step("confirm", &order, &format!(",\"node\":\"{WAREHOUSE}\"")),
    );
    o.act(
        &c.keeper,
        &step(
            "pack",
            &order,
            &format!(
                ",\"place\":\"{BOX1}\",\"node\":\"{WAREHOUSE}\",\"lines\":{}",
                lines(1)
            ),
        ),
    );
    o.act(
        &c.keeper,
        &step(
            "handoff",
            &order,
            &format!(",\"place\":\"{BOX1}\",\"from\":\"{WAREHOUSE}\",\"to\":\"{SHOP}\""),
        ),
    );
    // A box enters the road where the order takes its goods from, nowhere else.
    assert_eq!(
        o.denied(
            &c.clerk,
            &step(
                "pack",
                &order,
                &format!(
                    ",\"place\":\"{}\",\"node\":\"{SHOP}\",\"lines\":{}",
                    "f2".repeat(16),
                    lines(1)
                )
            )
        ),
        "not_supplier"
    );
    let accept = |qty: u32| {
        step(
            "accept",
            &order,
            &format!(
                ",\"place\":\"{BOX1}\",\"node\":\"{SHOP}\",\"condition\":3,\"lines\":{}",
                lines(qty)
            ),
        )
    };
    // The keeper counts what arrived: less than packed is a shortage in their words, more is refused.
    assert_eq!(o.denied(&c.clerk, &accept(2)), "more_than_packed");
    o.act(&c.clerk, &accept(1));
    assert_eq!(stock(&o.view(&c.clerk), SHOP), (1, 0, 0));
}

// A fixed little generator, so the thirty shuffles below are the same on every run and every machine.
struct Lcg(u64);

impl Lcg {
    fn below(&mut self, n: usize) -> usize {
        self.0 = self
            .0
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        usize::try_from(self.0 >> 33).expect("fits") % n.max(1)
    }
}

#[test]
fn two_phones_merging_in_any_order_draw_one_view() {
    let (mut o, c, order) = delivered();
    o.act(
        &c.clerk,
        &step(
            "shelf",
            &order,
            &format!(",\"node\":\"{SHOP}\",\"lines\":{}", lines(2)),
        ),
    );
    o.act(
        &c.clerk,
        &step(
            "sale",
            &order,
            &format!(",\"node\":\"{SHOP}\",\"lines\":{}", lines(1)),
        ),
    );
    let news = key32(0x61);
    o.act(
        &c.owner,
        &format!("{{\"kind\":\"open\",\"chat\":\"{news}\",\"chat_kind\":2,\"name\":\"News\"}}"),
    );
    o.act(
        &c.owner,
        &format!(
            "{{\"kind\":\"letter\",\"chat\":\"{news}\",\"mid\":\"m1\",\"text\":\"Open at nine\"}}"
        ),
    );
    o.act(&c.keeper, r#"{"kind":"shift_open"}"#);
    o.act(&c.keeper, r#"{"kind":"shift_close"}"#);
    let whole: Vec<_> = [&o.roster, &o.private]
        .iter()
        .flat_map(|stream| split_chain(stream).expect("splits"))
        .collect();
    let mut rng = Lcg(0x5EED);
    for _ in 0..30 {
        // Every record lands on one phone or the other, in an order of its own; then the phones trade streams.
        let mut deck = whole.clone();
        for i in (1..deck.len()).rev() {
            deck.swap(i, rng.below(i + 1));
        }
        let (mut r1, mut h1, mut r2, mut h2) = (Vec::new(), Vec::new(), Vec::new(), Vec::new());
        for rec in &deck {
            let one = join_chain([rec]).expect("joins");
            let first = rng.below(2) == 0;
            let target = match (rec.header.chain == CHAIN_R, first) {
                (true, true) => &mut r1,
                (true, false) => &mut r2,
                (false, true) => &mut h1,
                (false, false) => &mut h2,
            };
            let (m, _) = merge(target, &one).expect("merges");
            *target = m;
        }
        let (r, _) = merge(&r1, &r2).expect("merges");
        let (h, _) = merge(&h2, &h1).expect("merges");
        let phone = Org {
            roster: r,
            private: h,
            clock: o.clock,
        };
        for who in [&c.owner, &c.keeper, &c.clerk, &c.outsider] {
            assert_eq!(o.raw_view(who), phone.raw_view(who));
        }
    }
}

#[test]
fn an_order_tells_its_way_and_its_stands() {
    let (mut o, c, _) = delivered();
    let v = o.view(&c.owner);
    let ord = &v["orders"][0];
    let at = |kind: &str| -> u64 {
        ord["steps"]
            .as_array()
            .expect("steps")
            .iter()
            .find(|s| s["kind"] == kind)
            .and_then(|s| s["at_ms"].as_u64())
            .expect("the step")
    };
    let born = ord["at_ms"].as_u64().expect("at_ms");
    // On its way from the order to its last acceptance at the destination; nothing stands now.
    assert_eq!(ord["way_ms"].as_u64(), Some(at("accept") - born));
    assert_eq!(ord["standing"], Value::Null);
    // The longest stand: waiting at the supplier until packing, or the box at the warehouse until its handoff.
    let waited = at("pack") - born;
    let boxed = at("handoff") - at("pack");
    assert_eq!(ord["longest_stand"]["node"], WAREHOUSE);
    assert_eq!(ord["longest_stand"]["ms"].as_u64(), Some(waited.max(boxed)));
    // A second order confirmed and not packed stands at its supplier now, since it was written.
    let second = key32(0x55);
    o.act(&c.manager, &order_cmd(&second, "waiting"));
    o.act(
        &c.keeper,
        &step("confirm", &second, &format!(",\"node\":\"{WAREHOUSE}\"")),
    );
    let v = o.view(&c.owner);
    let w = v["orders"]
        .as_array()
        .expect("orders")
        .iter()
        .find(|x| x["id"] == second.as_str())
        .expect("the second order");
    let since = w["at_ms"].as_u64().expect("at_ms");
    assert_eq!(w["standing"]["node"], WAREHOUSE);
    assert_eq!(w["standing"]["since_ms"].as_u64(), Some(since));
    assert_eq!(w["standing"]["ms"].as_u64(), Some(o.clock - since));
}

#[test]
fn a_chat_follows_its_people_on_the_map_by_its_voices_word() {
    let (mut o, c) = founded();
    let team = key32(0x63);
    let chat =
        |kind: &str, rest: &str| format!("{{\"kind\":\"{kind}\",\"chat\":\"{team}\"{rest}}}");
    let track = |v: &serde_json::Value| {
        v["chats"]
            .as_array()
            .expect("chats")
            .iter()
            .find(|x| x["id"] == team.as_str())
            .expect("the chat")["track"]
            .clone()
    };
    o.act(
        &c.manager,
        &chat(
            "open",
            &format!(",\"chat_kind\":1,\"name\":\"Couriers\",\"dept\":\"{KITCHEN}\""),
        ),
    );
    // A chat is born with nobody followed.
    assert_eq!(track(&o.view(&c.manager)), serde_json::json!(false));
    // A listener does not switch it: its author and the administrators speak for the chat.
    assert_eq!(o.denied(&c.clerk, &chat("track", ",\"on\":true")), "denied");
    o.act(&c.manager, &chat("track", ",\"on\":true"));
    assert_eq!(track(&o.view(&c.manager)), serde_json::json!(true));
    o.act(&c.owner, &chat("track", ",\"on\":false"));
    assert_eq!(track(&o.view(&c.manager)), serde_json::json!(false));
    // No chat, nothing to follow.
    let stray = key32(0x64);
    assert_eq!(
        o.denied(
            &c.owner,
            &format!("{{\"kind\":\"track\",\"chat\":\"{stray}\",\"on\":true}}")
        ),
        "unknown_chat"
    );
}

#[test]
fn a_followed_chat_asks_its_people_in_windows_by_its_voices_word() {
    let (mut o, c) = founded();
    let team = key32(0x65);
    let chat =
        |kind: &str, rest: &str| format!("{{\"kind\":\"{kind}\",\"chat\":\"{team}\"{rest}}}");
    let window = |v: &serde_json::Value| {
        v["chats"]
            .as_array()
            .expect("chats")
            .iter()
            .find(|x| x["id"] == team.as_str())
            .expect("the chat")["track_window_s"]
            .clone()
    };
    o.act(
        &c.manager,
        &chat(
            "open",
            &format!(",\"chat_kind\":1,\"name\":\"Couriers\",\"dept\":\"{KITCHEN}\""),
        ),
    );
    // Born with no window: the place follows the person.
    assert_eq!(window(&o.view(&c.manager)), serde_json::json!(0));
    // A listener does not set it; its author and the administrators do.
    assert_eq!(
        o.denied(&c.clerk, &chat("track_window", ",\"window_s\":1800")),
        "denied"
    );
    o.act(&c.manager, &chat("track_window", ",\"window_s\":1800"));
    assert_eq!(window(&o.view(&c.manager)), serde_json::json!(1800));
    // From five minutes to a day, or none.
    assert_eq!(
        o.denied(&c.owner, &chat("track_window", ",\"window_s\":299")),
        "bad_window"
    );
    assert_eq!(
        o.denied(&c.owner, &chat("track_window", ",\"window_s\":86401")),
        "bad_window"
    );
    o.act(&c.owner, &chat("track_window", ",\"window_s\":0"));
    assert_eq!(window(&o.view(&c.manager)), serde_json::json!(0));
}
