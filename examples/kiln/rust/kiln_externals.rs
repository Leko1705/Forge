// The externals of kiln.forge and jvm.forge.
#![allow(non_snake_case)]
use super::kiln::rt::{self, V};

pub fn str_utf8(this: V) -> V { rt::list(rt::str_of(&this).bytes().map(|b| V::Int(b as i32)).collect()) }
pub fn int_str(this: V) -> V { rt::s(&rt::int_of(&this).to_string()) }
pub fn str_toInt(this: V) -> V { V::Int(rt::str_of(&this).parse().unwrap()) }
pub fn str_unquote(this: V) -> V {
    let chars: Vec<char> = rt::str_of(&this).chars().collect();
    let mut out = String::new();
    let mut i = 1;
    while i + 1 < chars.len() {
        if chars[i] == '\\' && i + 2 < chars.len() {
            i += 1;
            out.push(match chars[i] { 'n' => '\n', 't' => '\t', c => c });
        } else {
            out.push(chars[i]);
        }
        i += 1;
    }
    rt::s(&out)
}
