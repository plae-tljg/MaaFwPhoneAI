import difflib
import json
import re

from . import db

_SLOT_RE = re.compile(r"\{([a-zA-Z_][a-zA-Z0-9_]*)\}")


def normalize(text):
    return re.sub(r"\s+", "", text or "").strip().lower()


def canonical(text):
    """Lowercase + collapse whitespace, preserving word boundaries for slots."""
    return re.sub(r"\s+", " ", text or "").strip().lower()


def score(goal, alias):
    g, a = normalize(goal), normalize(alias)
    if not g or not a:
        return 0
    if g == a:
        return 100
    if a in g or g in a:
        return 70
    return int(difflib.SequenceMatcher(None, g, a).ratio() * 50)


def app_terms(con):
    """{alias/name -> app_id} for slot expansion; built from apps rows."""
    terms = {}
    for row in con.execute("SELECT id,name,aliases FROM apps WHERE active=1"):
        names = [row["name"]] + list(json.loads(row["aliases"] or "[]"))
        for name in names:
            name = canonical(name)
            if name:
                terms.setdefault(name, row["id"])
    return terms


def _pattern_score(goal, alias, apps):
    """Match an alias containing ``{slots}``.

    ``{app}`` expands against the apps registry (name + aliases); other slots
    match any non-empty text. Returns (score, slots) or (0, {}).
    """
    slots = _SLOT_RE.findall(alias)
    if not slots:
        return 0, {}
    pattern = canonical(alias)
    g = canonical(goal)
    if not g:
        return 0, {}

    # Expand {app} against the registry first (deterministic, closed set).
    if "app" in slots:
        best = (0, {})
        for term, app_id in apps.items():
            rendered = pattern.replace("{app}", term)
            sc = score(g, rendered)
            if sc > best[0]:
                best = (sc, {"app": app_id, "app_term": term})
        return best

    # Generic slots: compile the rest literally and the slots as captures.
    regex = []
    for part in re.split(r"(\{[a-zA-Z_][a-zA-Z0-9_]*\})", pattern):
        if _SLOT_RE.fullmatch(part):
            regex.append(f"(?P<{part[1:-1]}>.+?)")
        else:
            regex.append(re.escape(part))
    match = re.fullmatch("".join(regex), g)
    if not match:
        return 0, {}
    return 70, {k: v for k, v in match.groupdict().items() if v}


def resolve(con, goal, threshold=None):
    if threshold is None:
        threshold = int(db.setting(con, "resolve_threshold", "65"))
    apps = app_terms(con)
    best, best_score, best_slots = None, 0, {}
    for row in con.execute("SELECT * FROM pipelines WHERE status='live'"):
        aliases = list(json.loads(row["aliases"] or "[]"))
        if row["goal"]:
            aliases.append(row["goal"])
        for alias in aliases:
            if _SLOT_RE.search(alias):
                sc, slots = _pattern_score(goal, alias, apps)
                if sc > best_score:
                    best, best_score, best_slots = row, sc, slots
                continue
            sc = score(goal, alias)
            if sc > best_score:
                best, best_score, best_slots = row, sc, {}
    if best is None or best_score < threshold:
        return None, best_score
    if best_slots:
        # v0 keeps the two-tuple contract; the slots are available to callers
        # that need them through resolve_with_slots().
        best = dict(best)
        best["_slots"] = best_slots
    return best, best_score


def resolve_with_slots(con, goal, threshold=None):
    """Like resolve() but returns (pipeline, score, slots) explicitly."""
    best, best_score = resolve(con, goal, threshold)
    if best is None:
        return None, best_score, {}
    slots = best.get("_slots", {}) if isinstance(best, dict) else {}
    return best, best_score, slots
