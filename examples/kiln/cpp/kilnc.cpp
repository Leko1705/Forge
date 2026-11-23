// The Kiln compiler, generated in C++: ./kilnc <file.kiln> [-d <dir>]
#include <fstream>
#include <iostream>
#include <sstream>
#include <sys/stat.h>
#include "kiln.hpp"

int main(int argc, char** argv) {
    if (argc < 2) { std::cerr << "usage: kilnc <file.kiln> [-d <dir>]\n"; return 1; }
    std::string path = argv[1];
    std::string dir = argc >= 4 && std::string(argv[2]) == "-d" ? argv[3] : ".";
    std::string base = path.substr(path.find_last_of('/') + 1);
    base = base.substr(0, base.find('.'));
    std::string className = base;
    className[0] = static_cast<char>(std::toupper(static_cast<unsigned char>(className[0])));
    std::ifstream in(path, std::ios::binary);
    std::stringstream source;
    source << in.rdbuf();
    int status = 0;
    rt::with_stack(512, [&] {
        rt::V result;
        try {
            result = kiln::compile(rt::S(className), rt::S(source.str()));
        } catch (const rt::ParseException& e) {
            std::cerr << path << ":" << e.what() << "\n";
            status = 1;
            return;
        }
        rt::V diagnostics = rt::field(result, 0);
        for (const rt::V& d : rt::items(diagnostics)) {
            std::string label = rt::show(rt::field(d, 0)) == "ERROR" ? "error" : "warning";
            std::cerr << path << ":" << rt::show(rt::field(d, 1)) << ":" << rt::show(rt::field(d, 2)) << ": " << label << ": " << rt::show(rt::field(d, 3)) << "\n";
        }
        rt::V bytes = rt::field(result, 1);
        if (bytes.is_null()) { status = 1; return; }
        mkdir(dir.c_str(), 0755);
        std::string out = dir + "/" + className + ".class";
        std::ofstream file(out, std::ios::binary);
        for (const rt::V& b : rt::items(bytes)) file.put(static_cast<char>(rt::int_of(b)));
        std::cout << "wrote " << out << ": " << rt::items(bytes).size() << " bytes\n";
    });
    return status;
}
