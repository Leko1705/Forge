// The externals of conformance.forge.
use super::conformance::rt::{self, V};

pub fn int_text(this: V) -> V { rt::s(&rt::int_of(&this).to_string()) }
pub fn str_toInt(this: V) -> V { V::Int(rt::str_of(&this).parse().unwrap()) }
pub fn str_shout(this: V) -> V { rt::s(&format!("{}!", rt::str_of(&this).to_uppercase())) }
