// The externals of conformance.forge.
export function int_text(self) {
    return String(self);
}

export function str_toInt(self) {
    return parseInt(self, 10) | 0;
}

export function str_shout(self) {
    return self.toUpperCase() + "!";
}
