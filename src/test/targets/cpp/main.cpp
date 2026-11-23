// Runs the conformance program; defines its externals.
#include <cctype>
#include <iostream>
#include "conformance.hpp"

namespace conformance {
rt::V int_text(rt::V self) { return rt::S(std::to_string(rt::int_of(self))); }
rt::V str_toInt(rt::V self) { return rt::I(std::stoi(rt::str_of(self))); }
rt::V str_shout(rt::V self) {
    std::string s = rt::str_of(self);
    for (auto& c : s) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
    return rt::S(s + "!");
}
}

int main() {
    rt::with_stack(512, [] {
        rt::V report = conformance::report();
        for (const auto& line : rt::items(report)) std::cout << rt::str_of(line) << "\n";
    });
}
