"""Recolor the bundled launcher artwork without changing its geometry. Requires Pillow."""

from pathlib import Path

from PIL import Image, ImageColor


ROOT = Path(__file__).resolve().parents[1]
RESOURCES = ROOT / "app/src/main/res/mipmap-nodpi"
THEMES = {
    "midnight": "#5f7ff0",
    "royal_purple": "#7139c6",
    "gold": "#a77a0a",
    "green": "#227452",
    "pink": "#bf3d78",
    "black": "#202124",
}


def blend(start, end, amount):
    return tuple(round(a + (b - a) * amount) for a, b in zip(start, end))


def render(source, accent, dark):
    background = (8, 10, 16) if dark else (246, 248, 246)
    # A three-stop palette retains the original metallic relief, rim and waveform.
    middle = accent if dark else blend(background, accent, 0.65)
    highlight = blend(accent, (255, 255, 255), 0.85) if dark else blend(accent, (0, 0, 0), 0.25)
    pixels = []
    for red, green, blue, alpha in source.getdata():
        brightness = max(red, green, blue) / 255
        if brightness < 0.5:
            color = blend(background, middle, brightness * 2)
        else:
            color = blend(middle, highlight, (brightness - 0.5) * 2)
        pixels.append((*color, alpha))
    result = Image.new("RGBA", source.size)
    result.putdata(pixels)
    return result


if __name__ == "__main__":
    with Image.open(RESOURCES / "ic_launcher.png") as source:
        for theme, color in THEMES.items():
            for mode in ("light", "dark"):
                render(source.convert("RGBA"), ImageColor.getrgb(color), mode == "dark").save(
                    RESOURCES / f"ic_launcher_{theme}_{mode}.png", optimize=True
                )
