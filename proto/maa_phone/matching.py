import numpy as np
from PIL import Image
from scipy.signal import fftconvolve


def _gray(img):
    return np.asarray(img.convert("L"), dtype=np.float64)


def find_template(image, template_path, threshold=0.75):
    template = Image.open(template_path)
    img = _gray(image)
    tpl = _gray(template)
    th, tw = tpl.shape
    if img.shape[0] < th or img.shape[1] < tw:
        return None
    ones = np.ones((th, tw), dtype=np.float64)
    sum_i = fftconvolve(img, ones, mode="valid")
    sum_i2 = fftconvolve(img * img, ones, mode="valid")
    n = float(th * tw)
    var_i = np.maximum(sum_i2 - sum_i * sum_i / n, 1e-6)
    zero = tpl - tpl.mean()
    tpl_norm = float(np.sqrt(np.maximum((zero * zero).sum(), 1e-6)))
    corr = fftconvolve(img, zero[::-1, ::-1], mode="valid")
    ncc = corr / (np.sqrt(var_i) * tpl_norm)
    y, x = np.unravel_index(int(np.argmax(ncc)), ncc.shape)
    score = float(ncc[y, x])
    if score < threshold:
        return None
    return {
        "box": (int(x), int(y), int(tw), int(th)),
        "center": (int(x + tw / 2), int(y + th / 2)),
        "score": score,
    }
