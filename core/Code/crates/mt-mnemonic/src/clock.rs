// THE CLOCKS THE SOURCES READ (07.10.2026, MT Business's web page): the machine's monotonic clock and its calendar clock. On a
// phone or a node they are std's own; in WebAssembly std has neither, and the page's -- performance.now() and Date.now() --
// stand in their place. The sources and their one rule of liveness stay the same everywhere: a clock too coarse to make the
// samples differ leaves its source dead, and the rule of three refuses a birth honestly rather than lowering itself.

#[cfg(not(target_arch = "wasm32"))]
mod imp {
    use std::sync::OnceLock;
    use std::time::{Instant, SystemTime, UNIX_EPOCH};

    fn origin() -> Instant {
        static ORIGIN: OnceLock<Instant> = OnceLock::new();
        *ORIGIN.get_or_init(Instant::now)
    }

    pub fn mono_ns() -> u64 {
        origin().elapsed().as_nanos() as u64
    }

    pub fn wall_ns() -> u64 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos() as u64)
            .unwrap_or(0)
    }
}

// The page's clocks are JavaScript numbers -- milliseconds in an f64, by the platform. They measure a source here and enter
// no consensus quantity; the value leaves as whole nanoseconds.
#[cfg(target_arch = "wasm32")]
mod imp {
    use js_sys::{Date, Function, Reflect};
    use wasm_bindgen::{JsCast, JsValue};

    // FLOAT-OK: the page's clocks are JavaScript numbers by the platform; they measure an entropy source, never a consensus quantity.
    fn performance_ms() -> Option<f64> {
        let perf = Reflect::get(&js_sys::global(), &JsValue::from_str("performance")).ok()?;
        let now: Function = Reflect::get(&perf, &JsValue::from_str("now"))
            .ok()?
            .dyn_into()
            .ok()?;
        now.call0(&perf).ok()?.as_f64()
    }

    pub fn mono_ns() -> u64 {
        (performance_ms().unwrap_or_else(Date::now) * 1_000_000.0) as u64 // FLOAT-OK: whole nanoseconds leave, see above
    }

    pub fn wall_ns() -> u64 {
        (Date::now() * 1_000_000.0) as u64 // FLOAT-OK: whole nanoseconds leave, see above
    }
}

pub(crate) use imp::{mono_ns, wall_ns};

// The id of this process: a fresh process takes a fresh seed (fast.rs). WebAssembly has no process ids -- a page is its
// own one process, and its module's memory dies with it.
pub(crate) fn process_id() -> u32 {
    #[cfg(not(target_arch = "wasm32"))]
    {
        std::process::id()
    }
    #[cfg(target_arch = "wasm32")]
    {
        0
    }
}
