# The externals of kiln.forge and jvm.forge.

def str_utf8(self):
    return list(self.encode("utf-8"))

def int_str(self):
    return str(self)

def str_toInt(self):
    return int(self)

def str_unquote(self):
    out = []
    i = 1
    while i < len(self) - 1:
        c = self[i]
        if c == "\\" and i + 1 < len(self) - 1:
            i += 1
            out.append({"n": "\n", "t": "\t"}.get(self[i], self[i]))
        else:
            out.append(c)
        i += 1
    return "".join(out)
