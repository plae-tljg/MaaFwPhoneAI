import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
SHOTS = DATA / "screenshots"
TEMPLATES = DATA / "templates"
RUNS = DATA / "runs"
EXPORTS = DATA / "exports"
MAA_DIR = DATA / "maa"
BUNDLE = ROOT / "bundle"
BUNDLE_IMAGE = BUNDLE / "resource" / "image"


def load_env(path=None):
    path = Path(path or ROOT / ".env")
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


load_env()

DB_PATH = Path(os.environ.get("MAA_PHONE_DB", DATA / "maa.db"))
SCHEMA_PATH = Path(os.environ.get("MAA_PHONE_SCHEMA", ROOT.parent / "schema.sql"))
DEVICE_SERIAL = os.environ.get("MAA_PHONE_SERIAL") or None
DEEPSEEK_API_KEY = os.environ.get("DEEPSEEK_API_KEY", "")
DEEPSEEK_BASE = os.environ.get("DEEPSEEK_BASE", "https://api.deepseek.com")
DEEPSEEK_MODEL = os.environ.get("DEEPSEEK_MODEL", "deepseek-flash")
VISION_WIDTH = int(os.environ.get("MAA_PHONE_VISION_WIDTH", "720"))
