"""Autonomy gates — pipelines.autonomy enforced as data.

ADR-009: autonomy is data (`auto|confirm|never`). This module is the one
place the bridge asks whether a pipeline may run; the Android notification /
prompt sheet will call the same decision with its own UI.
"""


def check_goal(con, goal, package=None, assume_yes=False, interactive=False,
               prompt=input):
    """Gate a goal that has no pipeline row (the AI fallback path)."""
    from . import policies

    action, row = policies.decide(con, goal, package)
    kind = str(action.get("type") or "allow").lower()
    reason = str(action.get("reason") or (row["name"] if row else "") or kind)
    if kind == "allow":
        return True, ""
    if kind == "deny":
        return False, f"denied by policy {reason!r}"
    if assume_yes:
        return True, ""
    if interactive:
        try:
            answer = prompt(f"Policy {reason!r}: run this goal? [y/N] ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            return False, "policy confirm cancelled"
        if answer in ("y", "yes"):
            return True, ""
        return False, "policy confirm declined"
    return False, f"policy {reason!r} requires --yes in non-interactive mode"


def check(pipeline, assume_yes=False, interactive=False, prompt=input, log=print):
    """Return (allowed, reason).

    ``auto``    -> run.
    ``confirm`` -> run only with ``assume_yes`` or an explicit interactive yes.
    ``never``   -> refuse.
    """
    autonomy = str(pipeline["autonomy"] or "confirm").lower()
    if autonomy == "never":
        return False, f"pipeline {pipeline['name']!r} is marked autonomy=never"
    if autonomy == "auto":
        return True, ""
    if assume_yes:
        return True, ""
    if interactive:
        try:
            answer = prompt(
                f"Run pipeline {pipeline['name']!r}? [y/N] "
            ).strip().lower()
        except (EOFError, KeyboardInterrupt):
            return False, "autonomy=confirm cancelled"
        if answer in ("y", "yes"):
            return True, ""
        return False, "autonomy=confirm declined"
    return False, "autonomy=confirm requires --yes in non-interactive mode"
