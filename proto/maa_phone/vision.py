import base64
import io
import json
import urllib.request

from PIL import Image

from . import config


class DeepSeekError(RuntimeError):
    pass


class DeepSeek:
    def __init__(self, api_key=None, base=None, model=None):
        self.api_key = api_key or config.DEEPSEEK_API_KEY
        self.base = (base or config.DEEPSEEK_BASE).rstrip("/")
        self.model = model or config.DEEPSEEK_MODEL
        self.last_usage = {}

    def chat_message(self, messages, json_mode=False, max_tokens=2000, timeout=150, tools=None):
        """Return the raw assistant message dict (content, tool_calls, reasoning)."""
        if not self.api_key:
            raise DeepSeekError("DEEPSEEK_API_KEY is not set")
        payload = {
            "model": self.model,
            "messages": messages,
            "max_tokens": max_tokens,
        }
        if json_mode:
            payload["response_format"] = {"type": "json_object"}
        if tools:
            payload["tools"] = tools
        req = urllib.request.Request(
            self.base + "/chat/completions",
            data=json.dumps(payload).encode(),
            headers={
                "Content-Type": "application/json",
                "Authorization": "Bearer " + self.api_key,
            },
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read())
        self.last_usage = data.get("usage", {}) or {}
        return data["choices"][0]["message"]

    def chat(self, messages, json_mode=True, max_tokens=2000, timeout=150):
        message = self.chat_message(
            messages, json_mode=json_mode, max_tokens=max_tokens, timeout=timeout
        )
        return (message.get("content") or "").strip()

    def complete_json(self, prompt, max_tokens=800):
        return self.chat(
            [{"role": "user", "content": prompt}], json_mode=True, max_tokens=max_tokens
        )

    @staticmethod
    def dhash(img, size=8):
        """Deterministic 64-bit difference hash (hex) for the decision cache."""
        gray = img.convert("L").resize((size + 1, size))
        pixels = gray.tobytes()
        bits = 0
        for y in range(size):
            row = y * (size + 1)
            for x in range(size):
                bits = (bits << 1) | (1 if pixels[row + x] > pixels[row + x + 1] else 0)
        return f"{bits:0{size * size // 4}x}"

    @staticmethod
    def image_data_url(img, width=None, quality=70):
        width = width or config.VISION_WIDTH
        if img.mode != "RGB":
            img = img.convert("RGB")
        w, h = img.size
        scale = width / float(w)
        small = img.resize((width, max(1, int(round(h * scale)))))
        buf = io.BytesIO()
        small.save(buf, "JPEG", quality=quality)
        url = "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode()
        return url, small.size
