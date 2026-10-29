"""
Checks a LiquidBounce local config (AutoConfig JSON) against the client source.

For every module the config names, the module has to exist, every value it writes has to be a value
that module - or one of the groups inside it - actually declares, and the JSON type has to be the
one the value expects. LiquidBounce ignores all three failures silently: an unknown name is skipped,
a value of the wrong type throws inside a `runCatching` that only logs. A config can therefore look
applied and do nothing, which is exactly what this script exists to catch.

Usage: python3 check_config.py [config.json] [lb source] [addon source]
"""
import json
import os
import re
import sys

CONFIG = sys.argv[1] if len(sys.argv) > 1 else "configs/liquidaddon-pvp189-ghost.json"
SOURCES = [
    sys.argv[2] if len(sys.argv) > 2 else "/tmp/lb/src/main/kotlin",
    sys.argv[3] if len(sys.argv) > 3 else "/home/user/LiquidAddon/src/main/kotlin",
]

# Every constructor that registers a value under a literal name, with the JSON type it expects.
VALUE_CTOR = re.compile(
    r'\b(boolean|int|float|text|enumChoice|multiEnumChoice|multiChoice|choices|intRange|'
    r'floatRange|percentageChance|color|items|blocks)\s*\(\s*"([^"]+)"'
)
TOGGLEABLE = re.compile(r'\bToggleableValueGroup\s*\(\s*(?:this|[A-Za-z_][\w.]*)\s*,\s*"([^"]+)"')
TREE_ARG = re.compile(r'\btree\(\s*([A-Za-z_][\w.]*)')
GROUP_DECL = re.compile(r'\b(?:class|object)\s+([A-Za-z_]\w*)')
MODE_TAGS = [
    re.compile(r'\b(?:Mode|Choice)\(\s*"([^"]+)"'),
    re.compile(r':\s*[A-Za-z_][\w.]*\(\s*"([^"]+)"'),
]
# Any Tagged enum constant: NAME("Tag")
TAGGED_CONSTANT = re.compile(r'\b[A-Z][A-Z0-9_]*\(\s*"([^"]+)"')

BASE_VALUES = {"Enabled": "boolean", "Hidden": "boolean", "Bind": "bind"}

KIND_BOOL = "boolean"
KIND_NUMBER = "number"
KIND_RANGE = "range"
KIND_ENUM = "enum"
KIND_LIST = "list"
KIND_GROUP = "group"
KIND_ANY = "any"

KINDS = {
    "boolean": KIND_BOOL, "int": KIND_NUMBER, "float": KIND_NUMBER,
    "percentageChance": KIND_NUMBER, "intRange": KIND_RANGE, "floatRange": KIND_RANGE,
    "enumChoice": KIND_ENUM, "multiEnumChoice": KIND_LIST, "multiChoice": KIND_LIST,
    "choices": KIND_ANY, "text": KIND_ANY, "color": KIND_ANY, "items": KIND_LIST,
    "blocks": KIND_LIST,
}


def kotlin_files(root):
    for dirpath, _, files in os.walk(root):
        for name in files:
            if name.endswith(".kt"):
                yield os.path.join(dirpath, name)


def read(path):
    return open(path, encoding="utf-8", errors="replace").read()


def index_sources():
    modules, groups = {}, {}
    for root in SOURCES:
        for path in kotlin_files(root):
            text = read(path)
            for m in re.finditer(r'ClientModule\(\s*"([^"]+)"', text):
                modules.setdefault(m.group(1), path)
            for m in GROUP_DECL.finditer(text):
                groups.setdefault(m.group(1), set()).add(os.path.dirname(path))
    return modules, groups


def package_text(dirs):
    texts = []
    for directory in dirs:
        for dirpath, _, files in os.walk(directory):
            texts += [read(os.path.join(dirpath, n)) for n in files if n.endswith(".kt")]
    return "\n".join(texts)


def module_scope(module_file, groups):
    """The module's directory plus every directory a tree(...) group lives in (two levels deep)."""
    dirs = {os.path.dirname(module_file)}
    for _ in range(2):
        for m in TREE_ARG.finditer(package_text(dirs)):
            dirs |= groups.get(m.group(1).split(".")[-1], set())
    return dirs


def declared(text):
    """Value name -> the kinds it can have, plus the mode tags and the Tagged enum tags."""
    kinds = {}

    def add(name, kind):
        kinds.setdefault(name, set()).add(kind)

    for ctor, name in VALUE_CTOR.findall(text):
        add(name, KINDS[ctor])
    for name in TOGGLEABLE.findall(text):
        add(name, KIND_GROUP)
    for m in TREE_ARG.finditer(text):
        add(m.group(1).split(".")[-1], KIND_GROUP)
    for name, kind in BASE_VALUES.items():
        add(name, kind)

    modes = set()
    for pattern in MODE_TAGS:
        modes |= set(pattern.findall(text))
    tags = set(TAGGED_CONSTANT.findall(text))
    return kinds, modes, tags


def type_of(value):
    if isinstance(value, bool):
        return KIND_BOOL
    if isinstance(value, (int, float)):
        return KIND_NUMBER
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "list"
    if isinstance(value, dict) and "from" in value and "to" in value:
        return KIND_RANGE
    if isinstance(value, dict):
        return "object"
    return "null"


def accepts(kind, value, tags):
    """Does a JSON value of this shape fit a value declared with `kind`?"""
    actual = type_of(value)
    if kind == KIND_ANY:
        return True
    if kind == KIND_BOOL:
        return actual == KIND_BOOL
    if kind == KIND_NUMBER:
        return actual == KIND_NUMBER
    if kind == KIND_RANGE:
        return actual in (KIND_NUMBER, KIND_RANGE)
    if kind == KIND_ENUM:
        # A ChoiceListValue is set by tag; unknown tags throw and the value is skipped.
        return actual == "string" and (not tags or value in tags)
    if kind == KIND_LIST:
        return actual == "list"
    if kind == KIND_GROUP:
        return actual == "list" or "active" in (value if isinstance(value, dict) else {})
    if kind == "bind":
        return True
    return True


def walk(entries, kinds, modes, tags, where, problems, everywhere, elsewhere):
    for entry in entries:
        if not isinstance(entry, dict) or "name" not in entry or "value" not in entry:
            problems.append(f"{where}: malformed entry {entry!r}")
            continue
        name = entry["name"]
        path = f"{where}/{name}"
        value = entry["value"]

        if name in kinds:
            for kind in kinds[name]:
                if accepts(kind, value, tags):
                    break
            else:
                problems.append(
                    f"{path}: {value!r} does not fit the value's type "
                    f"({', '.join(sorted(kinds[name]))})"
                )
        elif name in everywhere:
            elsewhere.append(path)
        else:
            problems.append(f"{path}: the client has no value called '{name}' anywhere")

        if isinstance(value, list):
            walk(value, kinds, modes, tags, path, problems, everywhere, elsewhere)

        if "active" in entry:
            if entry["active"] not in modes:
                problems.append(f"{path}: mode '{entry['active']}' does not exist")
            for choice_name, choice in (entry.get("choices") or {}).items():
                if choice_name not in modes:
                    problems.append(f"{path}: choice group '{choice_name}' does not exist")
                walk(choice.get("value") or [], kinds, modes, tags,
                     f"{path}/{choice_name}", problems, everywhere, elsewhere)


def main():
    config = json.load(open(CONFIG, encoding="utf-8"))
    problems = []
    elsewhere = []

    if config.get("name") != "autoconfig":
        problems.append("the root 'name' must be 'autoconfig'")
    modules_json = config.get("modules")
    if not modules_json or modules_json.get("name") != "modules":
        problems.append("'modules' must be a group named 'modules'")
        modules_json = {"value": []}

    modules, groups = index_sources()
    global_kinds, _, global_tags = declared(package_text([r for r in SOURCES if os.path.isdir(r)]))
    print(f"{len(modules)} modules, {len(groups)} groups, {len(global_kinds)} value names "
          f"in the client sources")

    seen = set()
    for module in modules_json.get("value", []):
        name = module.get("name")
        if name in seen:
            problems.append(f"module '{name}' appears twice")
        seen.add(name)
        if name not in modules:
            problems.append(f"module '{name}' does not exist")
            continue
        kinds, modes, tags = declared(package_text(module_scope(modules[name], groups)))
        # Enum tags are looked up in the module's own package first and in the client second: an
        # enum can be declared in a file the module only references (AimValues, Clicker, ...).
        walk(module.get("value") or [], kinds, modes, tags | global_tags, name, problems,
             global_kinds, elsewhere)

    if elsewhere:
        print(f"\n{len(elsewhere)} value(s) live outside the module's own package "
              f"(declared for the module, in another file):")
        for path in elsewhere:
            print("  -", path)

    if problems:
        print(f"\n{len(problems)} problem(s):")
        for problem in problems:
            print("  -", problem)
        return 1

    print(f"all {len(seen)} modules, every value and every type in {CONFIG} check out")
    return 0


if __name__ == "__main__":
    sys.exit(main())
