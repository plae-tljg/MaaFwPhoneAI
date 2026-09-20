import json
import shutil
import time
import zipfile
from pathlib import Path

from . import config, db


class RunBundle:
    def __init__(self, run_id):
        self.run_id = run_id
        self.dir = config.RUNS / str(run_id)
        (self.dir / "screenshots").mkdir(parents=True, exist_ok=True)
        (self.dir / "draws").mkdir(parents=True, exist_ok=True)

    def manifest(self, data):
        data = dict(data)
        data["run_id"] = self.run_id
        data["created_at"] = time.strftime("%Y-%m-%d %H:%M:%S")
        (self.dir / "manifest.json").write_text(
            json.dumps(data, ensure_ascii=False, indent=2)
        )

    def event(self, entry):
        entry = dict(entry)
        entry.setdefault("ts", time.strftime("%H:%M:%S"))
        with open(self.dir / "events.jsonl", "a", encoding="utf-8") as f:
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")

    def screenshot(self, name, image):
        path = self.dir / "screenshots" / name
        image.save(path)
        return str(path.relative_to(self.dir))

    def draw(self, name, ndarray):
        import numpy as np
        from PIL import Image

        path = self.dir / "draws" / name
        if ndarray is not None and getattr(ndarray, "size", 0):
            Image.fromarray(np.asarray(ndarray)[:, :, ::-1]).save(path)
        return str(path.relative_to(self.dir))

    def write_text(self, name, text):
        (self.dir / name).write_text(text, encoding="utf-8")
        return str(self.dir / name)

    def export(self, out_dir=None):
        out_dir = Path(out_dir or config.EXPORTS)
        out_dir.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y%m%d_%H%M%S")
        target = out_dir / f"run{self.run_id}_{stamp}.zip"
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as zf:
            for path in sorted(self.dir.rglob("*")):
                if path.is_file():
                    zf.write(path, path.relative_to(self.dir.parent))
        return target


def bundle_for(con, run_id):
    bundle = RunBundle(run_id)
    db.set_run_bundle(con, run_id, str(bundle.dir.relative_to(config.ROOT)))
    return bundle


def debug_level(con):
    return db.setting(con, "debug_level", "screens")


def trace(con, run_id):
    row = con.execute("SELECT * FROM runs WHERE id=?", (run_id,)).fetchone()
    if not row:
        return f"run {run_id} not found"
    lines = [
        f"run #{row['id']} goal={row['goal']!r} path={row['path']} engine={row['engine']}",
        f"state={row['state']} success={row['success']} tokens={row['ai_cost']} "
        f"duration_ms={row['duration_ms']} error={row['error']!r}",
        f"bundle={row['bundle_path'] or '(none)'}",
    ]
    bundle = config.ROOT / (row["bundle_path"] or "")
    events = bundle / "events.jsonl"
    if events.exists():
        lines.append("events:")
        for line in events.read_text(encoding="utf-8").splitlines()[-40:]:
            entry = json.loads(line)
            detail = {k: v for k, v in entry.items() if k not in ("ts",)}
            lines.append(f"  {entry.get('ts','')} {json.dumps(detail, ensure_ascii=False)}")
    else:
        steps = json.loads(row["steps_json"] or "[]")
        lines.append(f"steps: {len(steps)} (no debug bundle)")
        for index, step in enumerate(steps):
            lines.append(f"  {index}: {json.dumps(step, ensure_ascii=False)[:160]}")
    return "\n".join(lines)


def prune(con, keep=50):
    rows = con.execute(
        "SELECT id FROM runs ORDER BY id DESC LIMIT -1 OFFSET ?", (keep,)
    ).fetchall()
    removed = 0
    for row in rows:
        path = config.RUNS / str(row["id"])
        if path.exists():
            shutil.rmtree(path, ignore_errors=True)
            removed += 1
    return removed
