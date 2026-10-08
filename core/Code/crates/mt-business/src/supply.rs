//! The supply chain, chain S: every order is a TimeChain of its own -- its lane is the order key, each step signed by
//! the one who took it, from the manager's first word to the shop's counter. Where a thing is comes only from its
//! keeper: a place stands at the node of its last Accept; handed off and not yet accepted, it is on its way.

use std::collections::{BTreeMap, BTreeSet};

use crate::body::{
    Body, Line, Tag, ACCEPT_SHORT, ACCEPT_WHOLE, ISSUE_LOSS, ISSUE_RETURN, KIND_ACCEPT,
    KIND_CANCEL, KIND_HANDOFF, KIND_ORDER, KIND_PACK, NODE_SHOP, NODE_SUPPLIER, ROLE_ADMIN,
    ROLE_MANAGER, SOURCE_TEXT, SOURCE_VOICE, ZERO16,
};
use crate::fold::Member;
use crate::frame::{Header, Id, ZERO32};

// (node, item, size) to a count of units.
pub type Stock = BTreeMap<(Tag, Tag, String), u64>;

#[derive(Clone, Debug)]
pub struct ItemInfo {
    pub title: String,
    pub sizes: String,
    pub unit: String,
    pub active: bool,
}

#[derive(Clone, Debug)]
pub struct NodeInfo {
    pub kind: u8,
    pub name: String,
    pub place: String,
    pub active: bool,
}

#[derive(Clone, Debug)]
pub struct Place {
    pub lines: Vec<Line>,
    pub packed_at: Tag,
    pub holder: Option<Tag>,
    pub transit: Option<(Tag, Tag)>,
    pub condition: u8,
    pub note: String,
    pub photo: [u8; 32],
}

#[derive(Clone, Debug)]
pub struct Step {
    pub record: Id,
    pub kind: u8,
    pub at_ms: u64,
    pub author: [u8; 32],
    pub node: Tag,
    pub place: Tag,
    pub detail: u8,
    pub lines: Vec<Line>,
    pub note: String,
}

#[derive(Clone, Debug)]
pub struct Order {
    pub author: [u8; 32],
    pub at_ms: u64,
    pub from: Tag,
    pub to: Tag,
    pub lines: Vec<Line>,
    pub source: u8,
    pub voice: [u8; 32],
    pub text: String,
    pub confirmed: bool,
    pub cancelled: bool,
    pub places: BTreeMap<Tag, Place>,
    pub steps: Vec<Step>,
    // Every node the order has passed or named: its people hold the order's chain.
    pub path: BTreeSet<Tag>,
}

// The time of an order, read from its own steps (the economy of time, constitution 5): how long it has been on its way, the
// longest it stood at one node, and the stand going on now. The phone's clock enters only for what is still going on.
pub struct Times {
    pub way_ms: u64,
    pub longest: (Tag, u64),
    // node, since, ms
    pub standing: Option<(Tag, u64, u64)>,
}

impl Order {
    /// A stand is time at one node: at the supplier from the order to its first packing; a place at a node from its packing
    /// or acceptance there to its handoff away. The road (handoff to acceptance) and the arrival at the destination are not
    /// stands. The way ends at the delivery (the last acceptance at the destination) or at the cancellation.
    pub fn times(&self, now_ms: u64) -> Times {
        let delivered = self.state() == "delivered";
        let cancelled_at = self
            .steps
            .iter()
            .find(|s| s.kind == KIND_CANCEL)
            .map(|s| s.at_ms);
        let end = if delivered {
            self.steps
                .iter()
                .filter(|s| s.kind == KIND_ACCEPT && s.node == self.to)
                .map(|s| s.at_ms)
                .max()
                .unwrap_or(now_ms)
        } else {
            cancelled_at.unwrap_or(now_ms)
        };
        let going = !delivered && cancelled_at.is_none();
        // node, since, ms, still going
        let mut stands: Vec<(Tag, u64, u64, bool)> = Vec::new();
        match self.steps.iter().find(|s| s.kind == KIND_PACK) {
            Some(p) => stands.push((
                self.from,
                self.at_ms,
                p.at_ms.saturating_sub(self.at_ms),
                false,
            )),
            None => stands.push((self.from, self.at_ms, end.saturating_sub(self.at_ms), going)),
        }
        let mut held: BTreeMap<Tag, (Tag, u64)> = BTreeMap::new();
        for st in &self.steps {
            match st.kind {
                KIND_ACCEPT if st.node == self.to => held.retain(|place, _| place != &st.place),
                KIND_PACK | KIND_ACCEPT => {
                    held.insert(st.place, (st.node, st.at_ms));
                },
                KIND_HANDOFF => {
                    if let Some((node, since)) = held.get(&st.place).copied() {
                        stands.push((node, since, st.at_ms.saturating_sub(since), false));
                        held.retain(|place, _| place != &st.place);
                    }
                },
                _ => {},
            }
        }
        for (node, since) in held.values() {
            stands.push((*node, *since, end.saturating_sub(*since), going));
        }
        let longest = stands
            .iter()
            .map(|(node, _, ms, _)| (*ms, *node))
            .max()
            .map_or((self.from, 0), |(ms, node)| (node, ms));
        let standing = stands
            .iter()
            .filter(|(_, _, _, on)| *on)
            .map(|(node, since, ms, _)| (*ms, *node, *since))
            .max()
            .map(|(ms, node, since)| (node, since, ms));
        Times {
            way_ms: end.saturating_sub(self.at_ms),
            longest,
            standing,
        }
    }

    pub fn state(&self) -> &'static str {
        if self.cancelled {
            "cancelled"
        } else if self.places.values().any(|p| p.transit.is_some()) {
            "in_transit"
        } else if !self.places.is_empty() && self.places.values().all(|p| p.holder == Some(self.to))
        {
            "delivered"
        } else if !self.places.is_empty() {
            "packed"
        } else if self.confirmed {
            "confirmed"
        } else {
            "new"
        }
    }
}

#[derive(Default)]
pub struct Catalogue {
    pub items: BTreeMap<Tag, ItemInfo>,
    pub nodes: BTreeMap<Tag, NodeInfo>,
    pub staff: BTreeMap<(Tag, [u8; 32]), bool>,
}

// held: units inside the places a node keeps; shelved: of those, put on the counter; sold: of the counter, sold.
#[derive(Default)]
pub struct Stocks {
    pub held: Stock,
    pub shelved: Stock,
    pub sold: Stock,
}

#[derive(Default)]
pub struct Supply {
    pub catalogue: Catalogue,
    pub stocks: Stocks,
    pub orders: BTreeMap<[u8; 32], Order>,
}

impl Catalogue {
    pub fn on_staff(&self, node: &Tag, member: &[u8; 32]) -> bool {
        self.staff.get(&(*node, *member)).copied().unwrap_or(false)
    }

    pub fn people(&self, node: &Tag) -> Vec<[u8; 32]> {
        self.staff
            .iter()
            .filter(|((n, _), on)| n == node && **on)
            .map(|((_, m), _)| *m)
            .collect()
    }

    fn live_node(&self, node: &Tag) -> Result<(), &'static str> {
        match self.nodes.get(node) {
            Some(n) if n.active => Ok(()),
            _ => Err("unknown_node"),
        }
    }

    // Goods named by a line exist; a new order or a new place names only goods still offered.
    fn check_lines(&self, lines: &[Line], fresh: bool) -> Result<(), &'static str> {
        if lines.is_empty() {
            return Err("no_lines");
        }
        for l in lines {
            match self.items.get(&l.item) {
                Some(i) if i.active || !fresh => {},
                _ => return Err("unknown_item"),
            }
            if l.qty == 0 {
                return Err("bad_qty");
            }
        }
        Ok(())
    }
}

fn key(node: &Tag, line: &Line) -> (Tag, Tag, String) {
    (*node, line.item, line.size.clone())
}

fn amount(map: &Stock, k: &(Tag, Tag, String)) -> u64 {
    map.get(k).copied().unwrap_or(0)
}

fn tally(node: &Tag, lines: &[Line]) -> Result<Stock, &'static str> {
    let mut t = Stock::new();
    for l in lines {
        let v = t.entry(key(node, l)).or_insert(0);
        *v = v.checked_add(u64::from(l.qty)).ok_or("overflow")?;
    }
    Ok(t)
}

// Every count of the tally added, never past the ceiling -- or nothing at all.
fn credit(
    map: &mut Stock,
    t: &Stock,
    ceiling: Option<&Stock>,
    reason: &'static str,
) -> Result<(), &'static str> {
    for (k, q) in t {
        let next = amount(map, k).checked_add(*q).ok_or("overflow")?;
        if ceiling.is_some_and(|c| next > amount(c, k)) {
            return Err(reason);
        }
    }
    for (k, q) in t {
        let v = map.entry(k.clone()).or_insert(0);
        *v = v.saturating_add(*q);
    }
    Ok(())
}

// Every count of the tally taken away, never under the floor -- or nothing at all.
fn debit(
    map: &mut Stock,
    t: &Stock,
    floor: &Stock,
    reason: &'static str,
) -> Result<(), &'static str> {
    for (k, q) in t {
        let left = amount(map, k).checked_sub(*q).ok_or(reason)?;
        if left < amount(floor, k) {
            return Err(reason);
        }
    }
    for (k, q) in t {
        let v = map.entry(k.clone()).or_insert(0);
        *v = v.saturating_sub(*q);
    }
    Ok(())
}

impl Supply {
    pub fn party(&self, order: &Order, member: &[u8; 32]) -> bool {
        order.author == *member
            || order
                .path
                .iter()
                .any(|n| self.catalogue.on_staff(n, member))
    }

    // The catalogue: goods, nodes and the people of each node. Administrators only (the caller checks).
    pub fn catalogue(
        &mut self,
        body: Body,
        members: &BTreeMap<[u8; 32], Member>,
    ) -> Result<(), &'static str> {
        let c = &mut self.catalogue;
        match body {
            Body::Item {
                item,
                title,
                sizes,
                unit,
                active,
            } => {
                if item == ZERO16 {
                    return Err("bad_item");
                }
                c.items.insert(
                    item,
                    ItemInfo {
                        title,
                        sizes,
                        unit,
                        active,
                    },
                );
                Ok(())
            },
            Body::Node {
                node,
                node_kind,
                name,
                place,
                active,
            } => {
                if node == ZERO16 {
                    return Err("bad_node");
                }
                if !(NODE_SUPPLIER..=NODE_SHOP).contains(&node_kind) {
                    return Err("bad_node_kind");
                }
                c.nodes.insert(
                    node,
                    NodeInfo {
                        kind: node_kind,
                        name,
                        place,
                        active,
                    },
                );
                Ok(())
            },
            Body::NodeStaff { node, member, on } => {
                if !c.nodes.contains_key(&node) {
                    return Err("unknown_node");
                }
                if !members.get(&member).is_some_and(|m| !m.removed) {
                    return Err("unknown_member");
                }
                c.staff.insert((node, member), on);
                Ok(())
            },
            _ => Err("bad_body"),
        }
    }

    pub fn apply(&mut self, id: Id, h: &Header, body: Body, role: u8) -> Result<(), &'static str> {
        if let Body::Order { .. } = body {
            return self.open(id, h, body, role);
        }
        let order = self.orders.get_mut(&h.lane).ok_or("unknown_order")?;
        step(
            &self.catalogue,
            &mut self.stocks,
            order,
            id,
            h,
            body,
            role <= ROLE_ADMIN,
        )
    }

    fn open(&mut self, id: Id, h: &Header, body: Body, role: u8) -> Result<(), &'static str> {
        let Body::Order {
            from,
            to,
            lines,
            source,
            voice,
            text,
        } = body
        else {
            return Err("bad_body");
        };
        if h.lane == ZERO32 {
            return Err("bad_order");
        }
        if self.orders.contains_key(&h.lane) {
            return Err("duplicate_order");
        }
        let c = &self.catalogue;
        // Managers write orders; so does the shop's own person, for the shop.
        if !(role <= ROLE_MANAGER || c.on_staff(&to, &h.author)) {
            return Err("denied");
        }
        c.live_node(&from)?;
        c.live_node(&to)?;
        if from == to {
            return Err("same_node");
        }
        c.check_lines(&lines, true)?;
        match source {
            SOURCE_TEXT if voice != ZERO32 => return Err("bad_source"),
            SOURCE_TEXT | SOURCE_VOICE => {},
            _ => return Err("bad_source"),
        }
        let first = Step {
            record: id,
            kind: KIND_ORDER,
            at_ms: h.at_ms,
            author: h.author,
            node: to,
            place: ZERO16,
            detail: source,
            lines: lines.clone(),
            note: String::new(),
        };
        self.orders.insert(
            h.lane,
            Order {
                author: h.author,
                at_ms: h.at_ms,
                from,
                to,
                lines,
                source,
                voice,
                text,
                confirmed: false,
                cancelled: false,
                places: BTreeMap::new(),
                steps: vec![first],
                path: BTreeSet::from([from, to]),
            },
        );
        Ok(())
    }
}

fn open_order(order: &Order) -> Result<(), &'static str> {
    if order.cancelled {
        Err("order_cancelled")
    } else {
        Ok(())
    }
}

// One step of an order. Every check comes before the first change, so a refused step leaves nothing behind.
fn step(
    c: &Catalogue,
    stocks: &mut Stocks,
    order: &mut Order,
    id: Id,
    h: &Header,
    body: Body,
    boss: bool,
) -> Result<(), &'static str> {
    let who = h.author;
    let keeps = |node: &Tag| boss || c.on_staff(node, &who);
    let mut s = Step {
        record: id,
        kind: h.kind,
        at_ms: h.at_ms,
        author: who,
        node: ZERO16,
        place: ZERO16,
        detail: 0,
        lines: Vec::new(),
        note: String::new(),
    };
    match body {
        Body::Confirm { node } => {
            open_order(order)?;
            if node != order.from {
                return Err("not_supplier");
            }
            if !keeps(&node) {
                return Err("denied");
            }
            if order.confirmed {
                return Err("already_confirmed");
            }
            order.confirmed = true;
            s.node = node;
        },
        Body::Pack { place, node, lines } => {
            open_order(order)?;
            if !order.confirmed {
                return Err("not_confirmed");
            }
            if place == ZERO16 {
                return Err("bad_place");
            }
            if order.places.contains_key(&place) {
                return Err("duplicate_place");
            }
            // Goods enter the road where the order takes them from: a box packed anywhere else would count goods already
            // lying in another box at that node a second time.
            if node != order.from {
                return Err("not_supplier");
            }
            c.live_node(&node)?;
            if !keeps(&node) {
                return Err("denied");
            }
            c.check_lines(&lines, true)?;
            credit(&mut stocks.held, &tally(&node, &lines)?, None, "overflow")?;
            order.places.insert(
                place,
                Place {
                    lines: lines.clone(),
                    packed_at: node,
                    holder: Some(node),
                    transit: None,
                    condition: 0,
                    note: String::new(),
                    photo: ZERO32,
                },
            );
            order.path.insert(node);
            s.node = node;
            s.place = place;
            s.lines = lines;
        },
        Body::Handoff { place, from, to } => {
            let p = order.places.get(&place).ok_or("unknown_place")?;
            if p.holder != Some(from) {
                return Err("not_holder");
            }
            if !keeps(&from) {
                return Err("denied");
            }
            c.live_node(&to)?;
            if from == to {
                return Err("same_node");
            }
            // Goods already put on this node's counter stay counted: the box leaving cannot take them along.
            let t = tally(&from, &p.lines)?;
            debit(&mut stocks.held, &t, &stocks.shelved, "goods_shelved")?;
            if let Some(p) = order.places.get_mut(&place) {
                p.holder = None;
                p.transit = Some((from, to));
            }
            order.path.insert(to);
            s.node = from;
            s.place = place;
        },
        Body::Accept {
            place,
            node,
            condition,
            lines,
            note,
            photo,
        } => {
            let p = order.places.get(&place).ok_or("unknown_place")?;
            if !matches!(p.transit, Some((_, to)) if to == node) {
                return Err("not_addressed");
            }
            if !keeps(&node) {
                return Err("denied");
            }
            if !(ACCEPT_WHOLE..=ACCEPT_SHORT).contains(&condition) {
                return Err("bad_condition");
            }
            // The keeper's own count, when given, is what arrived; otherwise what was packed. A count may fall short of the
            // packing (a shortage, in the keeper's words), never exceed it: a box does not grow on the road.
            let got = if lines.is_empty() {
                p.lines.clone()
            } else {
                c.check_lines(&lines, false)?;
                let packed = tally(&node, &p.lines)?;
                if tally(&node, &lines)?
                    .iter()
                    .any(|(k, q)| amount(&packed, k) < *q)
                {
                    return Err("more_than_packed");
                }
                lines
            };
            credit(&mut stocks.held, &tally(&node, &got)?, None, "overflow")?;
            if let Some(p) = order.places.get_mut(&place) {
                p.lines = got;
                p.holder = Some(node);
                p.transit = None;
                p.condition = condition;
                p.note = note.clone();
                p.photo = photo;
            }
            s.node = node;
            s.place = place;
            s.detail = condition;
            s.note = note;
        },
        Body::Scan { place, node } => {
            if !order.places.contains_key(&place) {
                return Err("unknown_place");
            }
            c.live_node(&node)?;
            if !keeps(&node) {
                return Err("denied");
            }
            order.path.insert(node);
            s.node = node;
            s.place = place;
        },
        Body::Shelf { node, lines } => {
            if !order.path.contains(&node) {
                return Err("foreign_node");
            }
            if !keeps(&node) {
                return Err("denied");
            }
            c.check_lines(&lines, false)?;
            credit(
                &mut stocks.shelved,
                &tally(&node, &lines)?,
                Some(&stocks.held),
                "short_stock",
            )?;
            s.node = node;
            s.lines = lines;
        },
        Body::Sale { node, lines } => {
            if !order.path.contains(&node) {
                return Err("foreign_node");
            }
            if !keeps(&node) {
                return Err("denied");
            }
            c.check_lines(&lines, false)?;
            credit(
                &mut stocks.sold,
                &tally(&node, &lines)?,
                Some(&stocks.shelved),
                "short_counter",
            )?;
            s.node = node;
            s.lines = lines;
        },
        Body::Issue {
            place,
            issue_kind,
            note,
        } => {
            let party =
                boss || who == order.author || order.path.iter().any(|n| c.on_staff(n, &who));
            if !party {
                return Err("denied");
            }
            if place != ZERO16 && !order.places.contains_key(&place) {
                return Err("unknown_place");
            }
            if !(ISSUE_RETURN..=ISSUE_LOSS).contains(&issue_kind) {
                return Err("bad_issue");
            }
            s.place = place;
            s.detail = issue_kind;
            s.note = note;
        },
        Body::Cancel { note } => {
            if !(boss || who == order.author) {
                return Err("denied");
            }
            if order.cancelled {
                return Err("already_cancelled");
            }
            order.cancelled = true;
            s.note = note;
        },
        _ => return Err("bad_body"),
    }
    order.steps.push(s);
    Ok(())
}
