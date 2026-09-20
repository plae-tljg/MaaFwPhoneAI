"""Unknown-goal safety policies — approval gates as data (ADR-009/018).

Skills carry their own ``autonomy``. A goal with no pipeline (AI fallback) has
no row to ask, so this table gates it:

    match_json:  {"app": "com.pay", "goal_keywords": ["pay", "付款"],
                  "goal_regex": ".*delete.*"}
    action_json: {"type": "allow" | "confirm" | "deny", "reason": "..."}

The first active row (priority, id) whose match has a recognised key wins;
no match means allow. The AI may propose rows through
``proposals.kind='policy'``; nothing is live before Review.
"""

import json
import re

_MATCH_KEYS = ("app", "goal_keywords", "goal_regex", "goal_contains")
_ACTIONS = ("allow", "confirm", "deny")


class PolicyError(ValueError):
    pass


def validate(candidate):
    """Validate a policy candidate; returns a normalized row dict."""
    if not isinstance(candidate, dict):
        raise PolicyError("policy must be an object")
    match = candidate.get("match") or candidate.get("match_json") or {}
    if isinstance(match, str):
        try:
            match = json.loads(match)
        except json.JSONDecodeError as exc:
            raise PolicyError(f"match_json is not JSON: {exc}")
    if not isinstance(match, dict) or not any(k in match for k in _MATCH_KEYS):
        raise PolicyError(f"match must contain one of {_MATCH_KEYS}")
    action = candidate.get("action") or candidate.get("action_json") or {"type": "confirm"}
    if isinstance(action, str):
        action = {"type": action}
    if not isinstance(action, dict) or action.get("type") not in _ACTIONS:
        raise PolicyError(f"action.type must be one of {_ACTIONS}")
    if "goal_keywords" in match and not isinstance(match["goal_keywords"], (list, tuple)):
        raise PolicyError("goal_keywords must be a list")
    return {
        "name": str(candidate.get("name") or "policy")[:80],
        "priority": int(candidate.get("priority", 100)),
        "match": match,
        "action": action,
        "app_id": candidate.get("app_id"),
    }


def _matches(match, goal, package=None):
    if "app" in match:
        if not package or match["app"] != package:
            return False
    if "goal_contains" in match:
        if str(match["goal_contains"]).lower() not in (goal or "").lower():
            return False
    if "goal_keywords" in match:
        text = (goal or "").lower()
        if not any(str(k).lower() in text for k in match["goal_keywords"]):
            return False
    if "goal_regex" in match:
        if not re.search(str(match["goal_regex"]), goal or "", re.IGNORECASE):
            return False
    return True


def decide(con, goal, package=None):
    """Return (action_dict, matched_row_or_None); default is allow."""
    for row in con.execute(
        "SELECT * FROM policies WHERE active=1 ORDER BY priority, id"
    ):
        try:
            match = json.loads(row["match_json"] or "{}")
        except json.JSONDecodeError:
            continue
        if _matches(match, goal, package):
            try:
                action = json.loads(row["action_json"] or "{}")
            except json.JSONDecodeError:
                action = {"type": "confirm"}
            return action, row
    return {"type": "allow"}, None
