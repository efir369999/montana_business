//! The chats and channels of an organisation, chain C: a chat is a lane, every letter a link -- the letter's hash, its
//! kind and its author's signature. The letter itself travels and rests as every Messenger letter does (end to end,
//! the sealed archive); the chain proves who said what and when, and who may hear it comes from the roster.

use std::collections::BTreeMap;

use crate::body::{
    Body, Dept, Tag, CHAT_CHANNEL, CHAT_GROUP, LETTER_COMMENT, LETTER_TEXT, ROLE_ADMIN,
    ROLE_MANAGER, TRACK_WINDOW_MAX, TRACK_WINDOW_MIN, ZERO16,
};
use crate::fold::Member;
use crate::frame::{Header, Id, ZERO32};

#[derive(Clone, Debug)]
pub struct Letter {
    pub hash: [u8; 32],
    // The Messenger letter's one name: the row in the group's feed this link speaks for.
    pub mid: String,
    pub kind: u8,
    pub author: [u8; 32],
    pub at_ms: u64,
    pub edited: Option<[u8; 32]>,
    pub deleted: bool,
}

#[derive(Clone, Debug)]
pub struct Chat {
    pub kind: u8,
    pub name: String,
    pub dept: Dept,
    // The Messenger group or channel (MTGroup) that carries the chat's letters, by its id.
    pub group: Tag,
    pub author: [u8; 32],
    pub opened_ms: u64,
    pub letters: BTreeMap<Id, Letter>,
    // Its people are followed on the map, by the word of its author or an administrator (Track); off when it is born.
    pub track: bool,
    // The window its followed people send their place in, in seconds (TrackWindow); 0 -- none, the place follows the person.
    pub track_window_s: u32,
}

impl Chat {
    // Who holds the chat: its department's people and the administrators; a chat with no department is the whole
    // organisation's.
    pub fn hears(&self, m: &Member) -> bool {
        !m.removed && (self.dept == ZERO16 || m.dept == self.dept || m.role <= ROLE_ADMIN)
    }
}

#[derive(Default)]
pub struct Chats {
    pub chats: BTreeMap<[u8; 32], Chat>,
}

impl Chats {
    pub fn apply(
        &mut self,
        id: Id,
        h: &Header,
        body: Body,
        me: &Member,
    ) -> Result<(), &'static str> {
        let who = h.author;
        let boss = me.role <= ROLE_ADMIN;
        match body {
            Body::Open {
                chat_kind,
                name,
                dept,
                group,
            } => {
                if h.lane == ZERO32 {
                    return Err("bad_chat");
                }
                if self.chats.contains_key(&h.lane) {
                    return Err("duplicate_chat");
                }
                let allowed = match chat_kind {
                    // A manager opens the chat of their own department; the administrators open any.
                    CHAT_GROUP => {
                        boss || (me.role == ROLE_MANAGER && dept != ZERO16 && dept == me.dept)
                    },
                    CHAT_CHANNEL => boss,
                    _ => return Err("bad_chat_kind"),
                };
                if !allowed {
                    return Err("denied");
                }
                self.chats.insert(
                    h.lane,
                    Chat {
                        kind: chat_kind,
                        name,
                        dept,
                        group,
                        author: who,
                        opened_ms: h.at_ms,
                        letters: BTreeMap::new(),
                        track: false,
                        track_window_s: 0,
                    },
                );
                Ok(())
            },
            Body::Letter {
                letter,
                mid,
                letter_kind,
            } => {
                let chat = self.chats.get_mut(&h.lane).ok_or("unknown_chat")?;
                if !chat.hears(me) {
                    return Err("denied");
                }
                // A channel speaks with its author's and the administrators' voice; its people answer under a post -- a
                // comment, and a comment lives under a channel's post alone.
                match letter_kind {
                    LETTER_TEXT => {
                        if chat.kind == CHAT_CHANNEL && !(boss || who == chat.author) {
                            return Err("denied");
                        }
                    },
                    LETTER_COMMENT => {
                        if chat.kind != CHAT_CHANNEL {
                            return Err("bad_letter_kind");
                        }
                    },
                    _ => return Err("bad_letter_kind"),
                }
                chat.letters.insert(
                    id,
                    Letter {
                        hash: letter,
                        mid,
                        kind: letter_kind,
                        author: who,
                        at_ms: h.at_ms,
                        edited: None,
                        deleted: false,
                    },
                );
                Ok(())
            },
            Body::Edit { record, letter } => {
                let chat = self.chats.get_mut(&h.lane).ok_or("unknown_chat")?;
                let l = chat.letters.get_mut(&record).ok_or("unknown_letter")?;
                if l.author != who {
                    return Err("denied");
                }
                if l.deleted {
                    return Err("letter_deleted");
                }
                l.edited = Some(letter);
                Ok(())
            },
            Body::Delete { record } => {
                let chat = self.chats.get_mut(&h.lane).ok_or("unknown_chat")?;
                let l = chat.letters.get_mut(&record).ok_or("unknown_letter")?;
                if !(l.author == who || boss) {
                    return Err("denied");
                }
                if l.deleted {
                    return Err("letter_deleted");
                }
                l.deleted = true;
                Ok(())
            },
            // Whether its people are followed on the map is the word of those who speak for the chat: its author and the
            // administrators -- the same voices a channel has.
            Body::Track { on } => {
                let chat = self.chats.get_mut(&h.lane).ok_or("unknown_chat")?;
                if !(boss || who == chat.author) {
                    return Err("denied");
                }
                chat.track = on;
                Ok(())
            },
            // The window is the same voices' word; none, or from five minutes to a day.
            Body::TrackWindow { window_s } => {
                let chat = self.chats.get_mut(&h.lane).ok_or("unknown_chat")?;
                if !(boss || who == chat.author) {
                    return Err("denied");
                }
                if window_s != 0 && !(TRACK_WINDOW_MIN..=TRACK_WINDOW_MAX).contains(&window_s) {
                    return Err("bad_window");
                }
                chat.track_window_s = window_s;
                Ok(())
            },
            _ => Err("bad_body"),
        }
    }
}
