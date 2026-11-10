"""
Reads the recordings LiquidBounce's DebugRecorder writes, and says what they are worth as a
training set. `clean` writes a filtered copy that `/models` can train on.

The client has no data stage. `/models create|improve` globs every `.json` under
`debug-recorder/Combat` and `debug-recorder/CombatTrainer`, concatenates the lot and fits it for a
fixed 100 epochs at a fixed learning rate, with L2 loss and **no validation set**. Whatever a
recording ever picked up stays in the training set forever:

  - a session recorded with an aim module on - DebugRecorder reads the rotation the client
    *sends*, so a silent rotation is what gets labelled as "the player's hand";
  - a session recorded at another sensitivity - the labels are on a different mouse lattice;
  - a lag spike - one 90° step in a set of a few thousand 1° steps dominates an L2 fit.

None of the three is visible from the client, and there is no metric to notice it afterwards. This
script is that missing stage: it prints what is in the files, flags the sessions that look wrong,
and (with `clean`) writes back only the rows a hand can have produced.

Sample layout, from `CombatSample`:
[a] current direction, [b] previous direction, [c] target direction - unit vectors, {x,y,z};
[d] the label: the next tick's Δyaw/Δpitch in degrees, {x,y}; [e] hurtTime, [f] age;
[g]/[h] player/target movement, {x,y,z}; [i] squared distance in blocks.

Usage:
  python3 configs/model_data.py report [--dir PATH] [--top N]
  python3 configs/model_data.py clean  [--dir PATH] [--out PATH] [--dry-run] [--install]
                                       [--max-label DEG] [--max-target DEG]
                                       [--max-distance BLOCKS] [--snap STEP|auto]
"""
import json
import math
import os
import shutil
import sys
from datetime import datetime
from pathlib import Path

MODES = ("Combat", "CombatTrainer")

KEY_CURRENT = "a"
KEY_PREVIOUS = "b"
KEY_TARGET = "c"
KEY_LABEL = "d"
KEY_DISTANCE = "i"

# A tick of a hand is a degree or two; 30° in one is a teleport, a world change or a lag spike.
MAX_LABEL = 30.0
# Beyond a quarter turn the recorder logged a target the player was not looking at (a fight that
# started behind them, an entity swap). Those labels teach a swing, not a track.
MAX_TARGET = 90.0
# The profiles fight at 3.4-4.2 blocks and the trainer spawns at 2-3: a sample from 8 blocks away
# describes a situation the assist will never be in.
MAX_DISTANCE = 5.5
# A direction vector that is not unit length is a corrupt row, not a far target.
UNIT_TOLERANCE = 0.05


# --------------------------------------------------------------------------------------------
# where the recordings are
# --------------------------------------------------------------------------------------------

def candidates():
    home = Path.home()
    roots = [
        Path.cwd() / ".liquidbounce",
        Path.cwd() / ".minecraft" / ".liquidbounce",
        home / ".minecraft" / ".liquidbounce",
        home / "Library" / "Application Support" / "minecraft" / ".liquidbounce",
    ]
    appdata = os.environ.get("APPDATA")
    if appdata:
        roots.append(Path(appdata) / ".minecraft" / ".liquidbounce")
    return roots


def recordings_dir(explicit):
    """The `debug-recorder` folder, either where the caller says or in the usual install."""
    if explicit:
        path = Path(explicit)
        for base in (path, path / "debug-recorder"):
            modes = [(base / mode).is_dir() for mode in MODES]
            if any(modes) or base.name == "debug-recorder":
                return base
        return path
    for root in candidates():
        if (root / "debug-recorder").is_dir():
            return root / "debug-recorder"
    return Path.cwd() / ".liquidbounce" / "debug-recorder"


def json_files(folder):
    if not folder.is_dir():
        return []
    return sorted(p for p in folder.rglob("*.json") if p.is_file())


def session_time(path):
    """`CombatTrainer/2026-11-06_15-04-22.json` -> the datetime, best effort."""
    stem = path.stem.split("_")
    if len(stem) >= 2:
        try:
            return datetime.strptime("_".join(stem[:2]), "%Y-%m-%d_%H-%M-%S")
        except ValueError:
            pass
    return None


# --------------------------------------------------------------------------------------------
# geometry, in the units the file stores
# --------------------------------------------------------------------------------------------

def vector(sample, key, size=3):
    value = sample.get(key)
    if not isinstance(value, dict):
        return None
    keys = ("x", "y", "z")[:size]
    try:
        out = tuple(float(value[k]) for k in keys)
    except (KeyError, TypeError, ValueError):
        return None
    if not all(math.isfinite(c) for c in out):
        return None
    return out


def length(v):
    return math.sqrt(sum(c * c for c in v))


def angle_between(u, v):
    """Degrees between two unit vectors, in the same space the labels live in."""
    den = length(u) * length(v)
    if den <= 1e-9:
        return None
    cos = sum(a * b for a, b in zip(u, v)) / den
    return math.degrees(math.acos(max(-1.0, min(1.0, cos))))


def label(sample):
    return vector(sample, KEY_LABEL, size=2)


def label_size(v):
    return math.hypot(v[0], v[1])


def target_angle(sample):
    current, goal = vector(sample, KEY_CURRENT), vector(sample, KEY_TARGET)
    if current is None or goal is None:
        return None
    return angle_between(current, goal)


def distance_blocks(sample):
    """[i] is a *squared* boxed distance, so a recording at 3 blocks stores about 9."""
    raw = sample.get(KEY_DISTANCE)
    try:
        value = float(raw)
    except (TypeError, ValueError):
        return None
    return math.sqrt(value) if math.isfinite(value) and value >= 0 else None


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(fraction * (len(ordered) - 1)))))
    return ordered[index]


def lattice(samples):
    """
    One mouse count, in degrees, read off the labels.

    The client can only move in whole counts, so the smallest positive step a recording contains is
    one count - or the data was not recorded on a lattice at all. A session at another sensitivity
    has a visibly different value, which is the only way to catch two recordings being mixed.
    """
    steps = []
    for sample in samples:
        v = label(sample)
        if v is None:
            continue
        for axis in v:
            if axis > 1e-4:
                steps.append(axis)
    return percentile(steps, 0.05)


def longest_run(samples):
    """Ticks in a row with the very same label - a hand does not do this, a module does."""
    best = 1
    run = 1
    previous = object()
    for sample in samples:
        current = label(sample)
        if current is None:
            previous, run = object(), 1
            continue
        current = (round(current[0], 4), round(current[1], 4))
        if current == previous:
            run += 1
            best = max(best, run)
        else:
            run = 1
        previous = current
    return best


def read_file(path):
    """-> (samples, broken rows). A file the client wrote is a JSON array; anything else is not."""
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return [], 0
    if not isinstance(data, list):
        return [], 0
    good = [row for row in data if isinstance(row, dict)]
    return good, len(data) - len(good)


# --------------------------------------------------------------------------------------------
# report
# --------------------------------------------------------------------------------------------

class Session:
    def __init__(self, path, mode, samples, broken):
        self.path = path
        self.mode = mode
        self.samples = samples
        self.broken = broken
        self.time = session_time(path)

        labels = [v for v in (label(s) for s in samples) if v is not None]
        sizes = [label_size(v) for v in labels]
        targets = [a for a in (target_angle(s) for s in samples) if a is not None]
        distances = [d for d in (distance_blocks(s) for s in samples) if d is not None]

        self.count = len(samples)
        self.still = sum(1 for v in labels if label_size(v) < 1e-4)
        self.target_median = percentile(targets, 0.50)
        self.target_p95 = percentile(targets, 0.95)
        self.size_median = percentile(sizes, 0.50)
        self.size_p95 = percentile(sizes, 0.95)
        self.distance_median = percentile(distances, 0.50)
        self.count_degrees = lattice(samples)
        self.run = longest_run(samples)
        self.rejected = sum(1 for s in samples if reject_reason(s) is not None)

    @property
    def still_share(self):
        return self.still / self.count if self.count else 0.0


def fmt(value, spec="%.1f", suffix=""):
    return "-" if value is None else (spec % value) + suffix


def report(root, top):
    sessions = []
    for mode in MODES:
        for path in json_files(root / mode):
            samples, broken = read_file(path)
            sessions.append(Session(path, mode, samples, broken))

    if not sessions:
        print(f"no recordings under {root}")
        print("record with DebugRecorder: Mode = Combat (real fights) or CombatTrainer (slimes)")
        return 1

    print(f"{root}\n")
    header = ("session", "count", "1 count", "target p50/p95", "step p50/p95", "still",
              "run", "drop")
    print("%-46s %7s %9s %17s %17s %6s %4s %5s" % header)
    print("-" * 114)
    for session in sorted(sessions, key=lambda s: (s.time or datetime.min, str(s.path))):
        name = f"{session.mode}/{session.path.name}"
        if len(name) > 46:
            name = "..." + name[-43:]
        print("%-46s %7d %9s %17s %17s %5.0f%% %4d %5d" % (
            name,
            session.count,
            fmt(session.count_degrees, "%.3f", "°"),
            "%s / %s" % (fmt(session.target_median), fmt(session.target_p95)),
            "%s / %s" % (fmt(session.size_median, "%.2f"), fmt(session.size_p95, "%.2f")),
            100 * session.still_share,
            session.run,
            session.rejected,
        ))

    total = sum(s.count for s in sessions)
    kept = sum(s.count - s.rejected for s in sessions)
    broken = sum(s.broken for s in sessions)
    still = sum(s.still for s in sessions)
    print("-" * 114)
    print(f"{len(sessions)} session(s), {total} samples"
          + (f", {broken} row(s) that are not an object" if broken else ""))
    print(f"{still} of them ({100 * still / total:.0f}%) are a still hand: label (0, 0)")

    steps = [s.count_degrees for s in sessions if s.count_degrees is not None]
    mixed = [s for s in sessions if s.count_degrees and len(steps) > 1
             and abs(s.count_degrees - percentile(steps, 0.5)) > 0.15 * percentile(steps, 0.5)]
    warnings = []
    if mixed:
        warnings.append(
            f"{len(mixed)} session(s) were recorded on a different lattice than the rest "
            f"(another sensitivity): " + ", ".join(s.path.name for s in mixed[:3])
            + ("..." if len(mixed) > 3 else "")
        )
    for session in sessions:
        if session.rejected:
            reasons = {}
            for sample in session.samples:
                reason = reject_reason(sample)
                if reason:
                    reasons[reason] = reasons.get(reason, 0) + 1
            warnings.append(
                f"{session.path.name}: {session.rejected} row(s) clean would drop ("
                + ", ".join(f"{k} {v}" for k, v in sorted(reasons.items())) + ")"
            )
        if session.run >= 8:
            warnings.append(
                f"{session.path.name}: {session.run} ticks in a row with an identical step - "
                f"a module was on, or the target never moved"
            )
        if session.count < 50:
            warnings.append(f"{session.path.name}: {session.count} samples, too few to matter")
        if session.size_p95 is not None and session.size_p95 > 15.0:
            warnings.append(
                f"{session.path.name}: p95 step {session.size_p95:.1f}°/tick - lag spikes?"
            )
        if session.distance_median is not None and session.distance_median > MAX_DISTANCE:
            warnings.append(
                f"{session.path.name}: median {session.distance_median:.1f} blocks away, "
                f"outside every fight this add-on takes"
            )
    if warnings:
        print(f"\n{len(warnings)} thing(s) to look at:")
        for warning in warnings[:top]:
            print("  -", warning)
        if len(warnings) > top:
            print(f"  ... and {len(warnings) - top} more (--top {len(warnings)})")

    print(f"\nclean would keep {kept} of {total} samples")
    print(verdict(total))
    print("\nnext: python3 configs/model_data.py clean --install   (then /models improve 19KC8KP)")
    return 0


def verdict(total):
    if total < 500:
        return ("  -> too few: /models batches 32 at a time and runs 100 epochs, it will memorise "
                "these. Record slimes with CombatTrainer for ten more minutes.")
    if total < 2000:
        return ("  -> a fine-tune, not a model: use `/models improve <existing>` on top of a "
                "bundled model, never `/models create` from scratch.")
    if total < 10000:
        return "  -> good for a fine-tune; still thin for training from zero."
    return "  -> enough for either, if the sessions above are clean."


# --------------------------------------------------------------------------------------------
# clean
# --------------------------------------------------------------------------------------------

def reject_reason(sample, max_label=MAX_LABEL, max_target=MAX_TARGET, max_distance=MAX_DISTANCE):
    """Why a row cannot have come from a hand, or None when it can."""
    vectors = {}
    for key in (KEY_CURRENT, KEY_PREVIOUS, KEY_TARGET):
        value = vector(sample, key)
        if value is None:
            return "not-a-number"
        if abs(length(value) - 1.0) > UNIT_TOLERANCE:
            return "not-a-direction"
        vectors[key] = value

    delta = label(sample)
    if delta is None:
        return "not-a-number"
    distance = distance_blocks(sample)
    if distance is None:
        return "not-a-number"

    if label_size(delta) > max_label:
        return "step-too-big"
    angle = angle_between(vectors[KEY_CURRENT], vectors[KEY_TARGET])
    if angle is not None and angle > max_target:
        return "target-behind"
    if distance > max_distance:
        return "out-of-range"
    return None


def snap(value, step):
    """Rounds a label onto the lattice, without the float noise of `n * step`."""
    return round(round(value / step) * step, 6) if step else value


def clean(root, out, dry_run, install, max_label, max_target, max_distance, snap_step):
    files = [(mode, path) for mode in MODES for path in json_files(root / mode)]
    if not files:
        print(f"no recordings under {root}")
        return 1

    # A run that drops more than the last one must not leave the extra files behind: `--install`
    # copies whatever is in `out`, and a stale session would walk straight back in.
    if not dry_run:
        for mode in MODES:
            shutil.rmtree(Path(out) / mode, ignore_errors=True)

    totals = {"in": 0, "out": 0}
    per_file = []
    for mode, path in files:
        samples, _ = read_file(path)
        kept = []
        local = {}
        for sample in samples:
            reason = reject_reason(sample, max_label, max_target, max_distance)
            if reason:
                local[reason] = local.get(reason, 0) + 1
                continue
            if snap_step:
                delta = label(sample)
                if delta is not None:
                    sample = dict(sample)
                    sample[KEY_LABEL] = {"x": snap(delta[0], snap_step),
                                         "y": snap(delta[1], snap_step)}
            kept.append(sample)
        totals["in"] += len(samples)
        totals["out"] += len(kept)
        per_file.append((mode, path, len(samples), len(kept), local))

        if kept and not dry_run:
            target = Path(out) / mode / path.name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(json.dumps(kept, separators=(",", ":")), encoding="utf-8")

    print("%-46s %7s %7s %7s  %s" % ("session", "in", "kept", "dropped", "why"))
    print("-" * 108)
    for mode, path, count, kept, local in per_file:
        name = f"{mode}/{path.name}"
        if len(name) > 46:
            name = "..." + name[-43:]
        why = ", ".join(f"{k} {v}" for k, v in sorted(local.items()))
        print("%-46s %7d %7d %7d  %s" % (name, count, kept, count - kept, why))
    print("-" * 108)
    share = 100.0 * totals["out"] / totals["in"] if totals["in"] else 0.0
    print(f"{totals['out']} of {totals['in']} samples kept ({share:.0f}%)")
    if snap_step:
        print(f"labels snapped to {snap_step:.3f}° - the grid the client quantizes to")
    if dry_run:
        print("dry run: nothing written")
        return 0
    if totals["out"] == 0:
        print("nothing left to train on: record again with no aim module on")
        return 1

    if install:
        stamp = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
        for mode in MODES:
            source = Path(out) / mode
            if not source.is_dir():
                continue
            live = root / mode
            if live.is_dir():
                backup = root / f"{mode}.bak-{stamp}"
                live.rename(backup)
                print(f"{live} -> {backup}")
            shutil.copytree(source, root / mode)
            print(f"{len(json_files(root / mode))} file(s) installed into {root / mode}")

    print(f"written to {out}")
    print("next: /models improve 19KC8KP   (or /models create <name> for a model from zero)")
    return 0


# --------------------------------------------------------------------------------------------

def option(argv, flag, default=None):
    if flag in argv:
        index = argv.index(flag)
        if index + 1 < len(argv):
            return argv[index + 1]
    return default


def flag(argv, name):
    return name in argv


def main(argv):
    command = argv[1] if len(argv) > 1 else "report"
    if command in ("-h", "--help", "help"):
        print(__doc__.strip())
        return 0
    if command not in ("report", "clean"):
        print(f"unknown command '{command}': use report or clean")
        return 2

    root = recordings_dir(option(argv, "--dir"))
    if command == "report":
        return report(root, int(option(argv, "--top", "5")) or 5)

    out = Path(option(argv, "--out", "debug-recorder-clean"))
    snap_option = option(argv, "--snap")
    snap_step = 0.0
    if snap_option:
        if snap_option == "auto":
            steps = [lattice(read_file(p)[0]) for mode in MODES for p in json_files(root / mode)]
            steps = [s for s in steps if s]
            snap_step = percentile(steps, 0.5) if steps else 0.0
            if not snap_step:
                print("could not read a lattice from the data: not snapping")
        else:
            try:
                snap_step = float(snap_option)
            except ValueError:
                print(f"--snap wants a number or 'auto', not '{snap_option}'")
                return 2
    return clean(
        root,
        out,
        dry_run=flag(argv, "--dry-run"),
        install=flag(argv, "--install"),
        max_label=float(option(argv, "--max-label", MAX_LABEL)),
        max_target=float(option(argv, "--max-target", MAX_TARGET)),
        max_distance=float(option(argv, "--max-distance", MAX_DISTANCE)),
        snap_step=snap_step,
    )


if __name__ == "__main__":
    sys.exit(main(sys.argv))
