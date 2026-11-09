"""
Offline evaluation of the aiming model's input space: does a model trained on human ticks beat a
well-tuned proportional law, and by how much?

`configs/model_data.py` reads the recordings and says what they are worth as a training set. This
script is the next question: given those recordings, fit a ridge regression on exactly the six
inputs `CombatSample.fillAsInput` builds, hold out the last 20% of the *sessions* (rows are
correlated in time, so a row-wise split would leak), and compare the mean absolute error per axis
against three baselines:

  1. `zero`  - do not correct at all;
  2. `prop`  - the family the `Math` engine belongs to, a proportional law `step = alpha * delta`
               with alpha optimised in closed form on the train half (the best single number the
               law can wear);
  3. `curve` - the curve as shipped: `share = Strength/100 * shape(|deltaYaw|)`, the pitch share,
               the soft caps - with the parameters of the daily profile, read from the config json.

Then it measures what MAE cannot see: how *repetitive* each predictor is (distinct quantised steps,
longest identical run, entropy of the step distribution), because a geometric progression of steps
with a constant ratio is exactly the shape rotation checks look for, and how much structure the
model left in its residuals (lag-1 autocorrelation).

The one-line answer at the bottom is the only reason this file exists: **does the model beat a
well-tuned proportional law?** If no, no amount of training epochs will save it, and everything
built on top is cosmetic. If yes, the margin is the number the rest of the pipeline chases.

Stdlib only. `model_data` is imported, never modified.

Usage:
  python3 configs/ai_eval.py [--dir PATH] [--profile PATH] [--extra] [--synth SESSIONS]
                             [--rows N] [--seed S]

With no recordings under `--dir` (or the usual install) and no `--synth`, the script says so and
exits 2: an evaluation that silently invents its data is worse than no evaluation.
"""
import json
import math
import random
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import model_data  # noqa: E402  (read-only: the reader this repo already trusts)

DEFAULT_PROFILE = Path(__file__).resolve().parent / "liquidaddon-pvp189-ghost.json"

# AimEngine.kt, verbatim: the shape of the shipped curve, its caps, and the pitch limit's mean
# draw (0.85 + U(0,0.15) -> 0.925 in expectation; the per-tick draw cannot be replayed offline).
PITCH_STEP_MAX = 3.0
PITCH_LIMIT_DRAW = 0.925
RIDGE_LAMBDA = 1e-6
QUANTUM = 0.05  # degrees: finer than a mouse count, coarse enough to count repetitions


# --------------------------------------------------------------------------------------------
# the six inputs, exactly as CombatSample.fillAsInput builds them
# --------------------------------------------------------------------------------------------

def wrap(deg):
    return (deg + 180.0) % 360.0 - 180.0


def rotation_of(vec):
    """Rotation.fromRotationVec: yaw = wrap(deg(atan2(z, x)) - 90), pitch = wrap(-deg(atan2(y, h)))."""
    x, y, z = vec
    yaw = wrap(math.degrees(math.atan2(z, x)) - 90.0)
    pitch = wrap(-math.degrees(math.atan2(y, math.hypot(x, z))))
    return yaw, pitch


def delta_of(frm, to):
    """Rotation.rotationDeltaTo: wrap(to - from), per axis."""
    return wrap(to[0] - frm[0]), wrap(to[1] - frm[1])


def features(sample, extra=False):
    """-> the input vector, or None when the row is corrupt (missing/non-finite fields)."""
    current = model_data.vector(sample, model_data.KEY_CURRENT)
    previous = model_data.vector(sample, model_data.KEY_PREVIOUS)
    target = model_data.vector(sample, model_data.KEY_TARGET)
    pdiff = model_data.vector(sample, "g")
    tdiff = model_data.vector(sample, "h")
    raw_distance = sample.get(model_data.KEY_DISTANCE)
    if None in (current, previous, target, pdiff, tdiff) or not isinstance(raw_distance, (int, float)):
        return None
    total = delta_of(rotation_of(current), rotation_of(target))
    prev = delta_of(rotation_of(previous), rotation_of(current))
    speed = math.hypot(pdiff[0], pdiff[2]) + math.hypot(tdiff[0], tdiff[2])
    out = [total[0], total[1], prev[0], prev[1], speed, float(raw_distance)]
    if extra:
        hurt = sample.get("e", 0)
        age = sample.get("f", 0)
        out += [float(hurt), float(age)]
    return out if all(math.isfinite(v) for v in out) else None


def label_of(sample):
    lab = model_data.label(sample)
    if lab is None or not all(math.isfinite(v) for v in lab):
        return None
    return lab


# --------------------------------------------------------------------------------------------
# the readers
# --------------------------------------------------------------------------------------------

def load_real(directory, extra=False):
    """-> list of (session_key, [ (features6, label) ]), one entry per recording file."""
    base = model_data.recordings_dir(str(directory)) if directory else model_data.recordings_dir(None)
    sessions = []
    for mode in model_data.MODES:
        for path in model_data.json_files(base / mode):
            samples, _broken = model_data.read_file(path)
            rows = []
            for sample in samples:
                feat, lab = features(sample, extra), label_of(sample)
                if feat is not None and lab is not None:
                    rows.append((feat, lab))
            if rows:
                sessions.append((path, rows))
    return sessions


def direction_of(yaw, pitch):
    """The unit vector a rotation looks along, the inverse of [rotation_of]."""
    theta = math.radians(yaw + 90.0)
    cp = math.cos(math.radians(pitch))
    return (cp * math.cos(theta), -math.sin(math.radians(pitch)), cp * math.sin(theta))


def load_synth(sessions, rows, seed, extra=False):
    """
    A declared synthetic dataset: a hand that damps its own velocity, on a mouse lattice, with
    pauses and overshoots - linear enough in the six inputs that a ridge can find the damping,
    human enough that a bare proportional law cannot.
    """
    rng = random.Random(seed)
    out = []
    for s in range(sessions):
        rows_out = []
        yaw, pitch = rng.uniform(-180, 180), rng.uniform(-20, 20)
        prev_dyaw = prev_dpitch = 0.0
        distance = rng.uniform(2.0, 4.5) ** 2
        speed = 0.0
        for _ in range(rows):
            # The hand: half the error, minus a fifth of its own last step (damping), plus a little
            # of the target's speed, on the lattice, with a paused tick here and there.
            target_dyaw = rng.gauss(0, 6.0)
            target_dpitch = rng.gauss(0, 2.0)
            if rng.random() < 0.06:
                lab = (0.0, 0.0)
            else:
                step_yaw = 0.50 * target_dyaw - 0.20 * prev_dyaw + 0.04 * speed
                step_pitch = 0.50 * target_dpitch - 0.20 * prev_dpitch
                step_yaw += rng.gauss(0, 0.25)
                step_pitch += rng.gauss(0, 0.15)
                lab = (round(step_yaw / 0.15) * 0.15, round(step_pitch / 0.15) * 0.15)
            current = direction_of(yaw, pitch)
            target = direction_of(yaw + target_dyaw, pitch + target_dpitch)
            previous = direction_of(yaw - prev_dyaw, pitch - prev_dpitch)
            sample = {
                "a": dict(zip("xyz", current)),
                "b": dict(zip("xyz", previous)),
                "c": dict(zip("xyz", target)),
                "d": {"x": lab[0], "y": lab[1]},
                "g": {"x": 0.0, "y": 0.0, "z": 0.0},
                "h": {"x": speed / 2, "y": 0.0, "z": speed / 2},
                "i": distance,
                "e": rng.randint(0, 10),
                "f": rng.randint(10, 200),
            }
            feat, got = features(sample, extra), label_of(sample)
            if feat is not None and got is not None:
                rows_out.append((feat, got))
            prev_dyaw, prev_dpitch = lab
            yaw, pitch = wrap(yaw + lab[0]), max(-89.0, min(89.0, pitch + lab[1]))
            speed = abs(rng.gauss(0.12, 0.06))
        out.append((Path("synthetic") / ("session_%02d.json" % s), rows_out))
    return out


# --------------------------------------------------------------------------------------------
# the maths: ridge, baselines, metrics
# --------------------------------------------------------------------------------------------

def solve(matrix, rhs):
    """Gaussian elimination with partial pivoting. No numpy, no excuses."""
    n = len(matrix)
    a = [row[:] + [rhs[i]] for i, row in enumerate(matrix)]
    for col in range(n):
        pivot = max(range(col, n), key=lambda r: abs(a[r][col]))
        if abs(a[pivot][col]) < 1e-12:
            return [0.0] * n
        a[col], a[pivot] = a[pivot], a[col]
        for row in range(n):
            if row == col:
                continue
            factor = a[row][col] / a[col][col]
            if factor:
                for k in range(col, n + 1):
                    a[row][k] -= factor * a[col][k]
    return [a[i][n] / a[i][i] for i in range(n)]


def fit_ridge(train, extra):
    """Normal equations with a whisper of L2 on everything but the intercept."""
    width = 1 + (8 if extra else 6)
    xtx = [[0.0] * width for _ in range(width)]
    xty = [[0.0] for _ in range(width)]
    for feat, lab in train:
        x = [1.0] + feat
        for i in range(width):
            for j in range(width):
                xtx[i][j] += x[i] * x[j]
        for i in range(width):
            xty[i][0] += x[i] * lab[0]
    for i in range(1, width):
        xtx[i][i] += RIDGE_LAMBDA
    beta_yaw = solve(xtx, [row[0] for row in xty])
    xty2 = [[0.0] for _ in range(width)]
    for feat, lab in train:
        x = [1.0] + feat
        for i in range(width):
            xty2[i][0] += x[i] * lab[1]
    beta_pitch = solve(xtx, [row[0] for row in xty2])
    return beta_yaw, beta_pitch


def predict_ridge(betas, feat):
    x = [1.0] + feat
    return (sum(b * v for b, v in zip(betas[0], x)), sum(b * v for b, v in zip(betas[1], x)))


def fit_prop(train):
    """Closed-form alpha per axis: the best proportional law the train half can wear."""
    alphas = []
    for axis in (0, 1):
        num = den = 0.0
        for feat, lab in train:
            num += feat[axis] * lab[axis]
            den += feat[axis] * feat[axis]
        alphas.append(num / den if den > 1e-12 else 0.0)
    return alphas


def shape_of(smoothing, delta_yaw):
    t = max(0.0, min(1.0, abs(delta_yaw) / 12.0))
    if smoothing == "Linear":
        return 1.0
    if smoothing == "Sigmoid":
        return 0.45 + 0.55 / (1.0 + math.exp(-6.0 * (t - 0.45)))
    return 0.55 + 0.45 * (t * t * (3.0 - 2.0 * t))  # Bezier, the default


def soft_limit(value, limit):
    return limit * math.tanh(value / limit) if limit > 0 else 0.0


def curve_predictor(profile):
    strength = profile.get("Strength", 35.0) / 100.0
    max_speed = profile.get("MaxSpeed", 7.0)
    smoothing = profile.get("Smoothing", "Bezier")
    pitch_assist = profile.get("PitchAssist", 55.0) / 100.0
    pitch_limit = min(max_speed * 0.7, PITCH_STEP_MAX * PITCH_LIMIT_DRAW)

    def predict(feat):
        share = strength * shape_of(smoothing, feat[0])
        yaw = soft_limit(feat[0] * share, max_speed)
        pitch = soft_limit(feat[1] * share * pitch_assist, pitch_limit)
        return yaw, pitch

    return predict


def mae(errors):
    return sum(abs(e) for e in errors) / len(errors) if errors else float("nan")


def autocorr_lag1(values):
    if len(values) < 3:
        return float("nan")
    mean = sum(values) / len(values)
    num = sum((values[i] - mean) * (values[i + 1] - mean) for i in range(len(values) - 1))
    den = sum((v - mean) ** 2 for v in values)
    return num / den if den > 1e-12 else float("nan")


def repetition(steps):
    """Distinct quantised steps, longest identical run, entropy of the distribution, in bits."""
    quant = [(round(a / QUANTUM), round(b / QUANTUM)) for a, b in steps]
    distinct = len(set(quant))
    best = run = 1
    for i in range(1, len(quant)):
        run = run + 1 if quant[i] == quant[i - 1] else 1
        best = max(best, run)
    counts = {}
    for q in quant:
        counts[q] = counts.get(q, 0) + 1
    entropy = -sum((c / len(quant)) * math.log2(c / len(quant)) for c in counts.values())
    return distinct, best, entropy


def profile_params(path):
    try:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}
    for module in data.get("modules", {}).get("value", []):
        if module.get("name") != "Aimassist":
            continue
        out = {}
        for entry in module.get("value", []):
            out[entry.get("name")] = entry.get("value")
        return out
    return {}


# --------------------------------------------------------------------------------------------
# the report
# --------------------------------------------------------------------------------------------

def main(argv):
    directory = profile = None
    extra = False
    synth = rows = 0
    seed = 7
    i = 0
    while i < len(argv):
        arg = argv[i]
        if arg == "--dir":
            i += 1
            directory = argv[i]
        elif arg == "--profile":
            i += 1
            profile = argv[i]
        elif arg == "--extra":
            extra = True
        elif arg == "--synth":
            i += 1
            synth = int(argv[i])
        elif arg == "--rows":
            i += 1
            rows = int(argv[i])
        elif arg == "--seed":
            i += 1
            seed = int(argv[i])
        else:
            print("unknown argument: %s" % arg)
            return 2
        i += 1

    if synth > 0:
        sessions = load_synth(synth, rows or 3000, seed, extra)
        provenance = "SYNTHETIC (--synth %d sessions x %d rows, seed %d): a damped hand on a lattice" % (
            synth, rows or 3000, seed)
    else:
        sessions = load_real(directory, extra)
        if not sessions:
            print("nessuna registrazione trovata: usa --dir PATH o --synth N (dichiarato tale)")
            return 2
        provenance = "REAL recordings: %d sessioni" % len(sessions)

    def session_key(pair):
        moment = model_data.session_time(pair[0])
        return moment.timestamp() if moment else 0.0

    sessions.sort(key=session_key)
    cut = max(1, len(sessions) - max(1, len(sessions) // 5))
    train_sessions, test_sessions = sessions[:cut], sessions[cut:]
    train = [row for _p, rows_ in train_sessions for row in rows_]
    test = [row for _p, rows_ in test_sessions for row in rows_]
    if not train or not test:
        print("split troppo povero: %d righe train, %d test" % (len(train), len(test)))
        return 2

    params = profile_params(profile or DEFAULT_PROFILE)
    curve = curve_predictor(params)
    prop = fit_prop(train)
    betas = fit_ridge(train, extra)

    names = ["ridge", "zero", "prop", "curve"]
    errors = {name: ([], []) for name in names}
    steps = {name: [] for name in names}
    steps["label"] = []
    residuals = ([], [])
    for feat, lab in test:
        preds = {
            "ridge": predict_ridge(betas, feat),
            "zero": (0.0, 0.0),
            "prop": (prop[0] * feat[0], prop[1] * feat[1]),
            "curve": curve(feat),
        }
        for name in names:
            pred = preds[name]
            errors[name][0].append(pred[0] - lab[0])
            errors[name][1].append(pred[1] - lab[1])
            steps[name].append(pred)
        steps["label"].append(lab)
        ridge = preds["ridge"]
        residuals[0].append(ridge[0] - lab[0])
        residuals[1].append(ridge[1] - lab[1])

    print("ai_eval - %s" % provenance)
    print("split temporale: %d sessioni train (%d righe), %d test (%d righe); input %d"
          % (len(train_sessions), len(train), len(test_sessions), len(test), 8 if extra else 6))
    print("parametri curva dal profilo: Strength=%s MaxSpeed=%s Smoothing=%s PitchAssist=%s"
          % (params.get("Strength", 35.0), params.get("MaxSpeed", 7.0),
             params.get("Smoothing", "Bezier"), params.get("PitchAssist", 55.0)))
    print()
    print("MAE per asse sul test (gradi):")
    for name in names:
        print("  %-6s yaw %.4f  pitch %.4f" % (name, mae(errors[name][0]), mae(errors[name][1])))
    print()
    print("ripetitivita' sul test (passi quantizzati a %.2f gradi):" % QUANTUM)
    for name in ["label", "ridge", "prop", "curve"]:
        distinct, run, entropy = repetition(steps[name])
        print("  %-6s distinti %5d  run identica piu' lunga %3d  entropia %.3f bit"
              % (name, distinct, run, entropy))
    print()
    print("autocorrelazione lag-1 dei residui ridge: yaw %.3f pitch %.3f"
          % (autocorr_lag1(residuals[0]), autocorr_lag1(residuals[1])))
    print()
    for axis, label in ((0, "yaw"), (1, "pitch")):
        ridge_mae = mae(errors["ridge"][axis])
        prop_mae = mae(errors["prop"][axis])
        if prop_mae > 1e-9:
            gain = 100.0 * (prop_mae - ridge_mae) / prop_mae
            print("verdetto %s: il modello %s la legge proporzionale tarata, del %.1f%% di MAE"
                  % (label, "batte" if gain > 0 else "NON batte", abs(gain)))
        else:
            print("verdetto %s: legge proporzionale degenerata (alpha=0), confronto vuoto" % label)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
