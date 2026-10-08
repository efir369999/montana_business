use std::fmt::Write as _;

use crate::codec::to_hex;

// The view is written by hand so its bytes are fixed by this file alone: the same fold gives the
// same JSON on iOS and Android, field order included.
pub struct Json {
    out: String,
    filled: Vec<bool>,
    after_key: bool,
}

impl Default for Json {
    fn default() -> Self {
        Self::new()
    }
}

impl Json {
    pub fn new() -> Self {
        Self {
            out: String::new(),
            filled: Vec::new(),
            after_key: false,
        }
    }

    fn pre_value(&mut self) {
        if self.after_key {
            self.after_key = false;
            return;
        }
        if let Some(top) = self.filled.last_mut() {
            if *top {
                self.out.push(',');
            }
            *top = true;
        }
    }

    pub fn begin_obj(&mut self) {
        self.pre_value();
        self.out.push('{');
        self.filled.push(false);
    }

    pub fn end_obj(&mut self) {
        self.filled.pop();
        self.out.push('}');
    }

    pub fn begin_arr(&mut self) {
        self.pre_value();
        self.out.push('[');
        self.filled.push(false);
    }

    pub fn end_arr(&mut self) {
        self.filled.pop();
        self.out.push(']');
    }

    pub fn key(&mut self, k: &str) {
        self.pre_value();
        push_str_lit(&mut self.out, k);
        self.out.push(':');
        self.after_key = true;
    }

    pub fn str(&mut self, s: &str) {
        self.pre_value();
        push_str_lit(&mut self.out, s);
    }

    pub fn u64(&mut self, v: u64) {
        self.pre_value();
        let _ = write!(self.out, "{v}");
    }

    pub fn bool(&mut self, v: bool) {
        self.pre_value();
        self.out.push_str(if v { "true" } else { "false" });
    }

    pub fn null(&mut self) {
        self.pre_value();
        self.out.push_str("null");
    }

    pub fn hex(&mut self, bytes: &[u8]) {
        self.str(&to_hex(bytes));
    }

    pub fn kv_str(&mut self, k: &str, v: &str) {
        self.key(k);
        self.str(v);
    }

    pub fn kv_u64(&mut self, k: &str, v: u64) {
        self.key(k);
        self.u64(v);
    }

    pub fn kv_bool(&mut self, k: &str, v: bool) {
        self.key(k);
        self.bool(v);
    }

    pub fn kv_hex(&mut self, k: &str, v: &[u8]) {
        self.key(k);
        self.hex(v);
    }

    pub fn kv_null(&mut self, k: &str) {
        self.key(k);
        self.null();
    }

    pub fn finish(self) -> String {
        self.out
    }
}

fn push_str_lit(out: &mut String, s: &str) {
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if u32::from(c) < 0x20 => {
                let _ = write!(out, "\\u{:04x}", u32::from(c));
            },
            c => out.push(c),
        }
    }
    out.push('"');
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nested_commas_and_escapes() {
        let mut j = Json::new();
        j.begin_obj();
        j.kv_str("a", "q\"b\\s\n\u{1}Я");
        j.key("list");
        j.begin_arr();
        j.u64(1);
        j.begin_obj();
        j.kv_bool("t", true);
        j.kv_null("n");
        j.end_obj();
        j.hex(&[0xab]);
        j.end_arr();
        j.kv_u64("z", u64::MAX);
        j.end_obj();
        assert_eq!(
            j.finish(),
            "{\"a\":\"q\\\"b\\\\s\\n\\u0001Я\",\"list\":[1,{\"t\":true,\"n\":null},\"ab\"],\"z\":18446744073709551615}"
        );
    }
}
