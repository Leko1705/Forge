// Runs the program of the language features.
#include <iostream>
#include "language.hpp"

int main() {
    rt::with_stack(512, [] {
        rt::V report = language::report();
        for (const auto& line : rt::items(report)) std::cout << rt::str_of(line) << "\n";
    });
}
