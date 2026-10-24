import base64, os
D = os.path.dirname(os.path.abspath(__file__))
def uri(name):
    return "data:image/svg+xml;base64," + base64.b64encode(open(os.path.join(D, name), "rb").read()).decode()
V = [("A", "blue", "classic", "Blue afro · classic horn"),
     ("B", "blue", "bolt", "Blue afro · lightning horn"),
     ("C", "rainbow", "classic", "Rainbow afro · classic horn"),
     ("D", "rainbow", "bolt", "Rainbow afro · lightning horn")]
def icon(src, size, shape):
    # adaptive icon: 108-unit canvas, the launcher shows the middle 72 cropped to its own shape
    return (f'<span class="ico {shape}" style="--s:{size}px"><img src="{src}" alt=""></span>')
cards = "".join(f'''<figure class="card">
  <div class="big"><img src="{uri(f"draft-{a}-{h}.svg")}" alt="Variant {k}: {label}"></div>
  <figcaption><b>{k}</b> {label}</figcaption>
</figure>''' for k, a, h, label in V)
def row(wall):
    cells = ""
    for k, a, h, label in V:
        src = uri(f"draft-{a}-{h}.svg")
        cells += f'''<div class="slot"><div class="shapes">{icon(src,56,"circle")}{icon(src,56,"squircle")}{icon(src,56,"rounded")}</div><span class="lbl">{k} · Stupify</span></div>'''
    return f'<div class="wall {wall}">{cells}</div>'
mono = "".join(f'''<div class="slot"><div class="shapes">
  <span class="ico circle themed-dark" style="--s:56px"><img src="{uri(f"mono-{h}.svg")}" alt=""></span>
  <span class="ico circle themed-light" style="--s:56px"><img src="{uri(f"mono-{h}.svg")}" alt=""></span>
  <span class="tiny"><img src="{uri(f"mono-{h}.svg")}" alt="" width="36"></span></div>
  <span class="lbl">{"Classic horn" if h=="classic" else "Lightning horn"}</span></div>''' for h in ("classic", "bolt"))
small = "".join(f'<div class="slot"><div class="shapes">' + "".join(icon(uri(f"draft-{a}-{h}.svg"), s, "circle") for s in (24, 32, 40)) + f'</div><span class="lbl">{k}</span></div>' for k, a, h, _ in V)

html = f'''<title>Stupify Mascot Drafts</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Fredoka:wght@500;600&family=Nunito+Sans:opsz,wght@6..12,400;6..12,600&display=swap">
<style>
/* Layout: one column; four large variants in a grid, then the same four as real launcher icons. Single dark theme on purpose: it is the app's own navy. */
:root {{
  color-scheme: dark;
  --navy: #070D1A; --panel: #0F1A2E; --panel-2: #142238; --ink: #E3EAF7; --muted: #8E9BB2;
  --blue: #5AA9FF; --cyan: #5FE0E6; --gold: #F2B233; --line: #1F2B40;
  --display: "Fredoka", "Nunito Sans", system-ui, sans-serif;
  --body: "Nunito Sans", system-ui, -apple-system, "Segoe UI", sans-serif;
}}
* {{ box-sizing: border-box; }}
body {{ background: var(--navy); color: var(--ink); font: 16px/1.55 var(--body); margin: 0;
  padding-left: max(16px, env(safe-area-inset-left, 0px)); padding-right: max(16px, env(safe-area-inset-right, 0px)); }}
main {{ max-width: 1040px; margin: 0 auto; padding-block: 40px 64px; display: grid; gap: 48px; }}
header {{ display: grid; gap: 8px; }}
.eyebrow {{ font: 600 12px/1 var(--body); letter-spacing: .14em; text-transform: uppercase; color: var(--cyan); }}
h1 {{ font: 600 clamp(32px, 6vw, 52px)/1.05 var(--display); margin: 0; text-wrap: balance; }}
h1 span {{ background: linear-gradient(90deg, var(--blue), var(--cyan)); -webkit-background-clip: text; background-clip: text; color: transparent; }}
h2 {{ font: 600 24px/1.2 var(--display); margin: 0; text-wrap: balance; }}
p {{ margin: 0; max-width: 65ch; color: var(--muted); }}
section {{ display: grid; gap: 16px; }}
.grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; }}
.card {{ margin: 0; background: var(--panel); border: 1px solid var(--line); border-radius: 20px; padding: 12px; display: grid; gap: 12px; }}
.big {{ border-radius: 14px; overflow: hidden; aspect-ratio: 1; max-width: 100%; }}
.big img {{ width: 100%; height: 100%; display: block; }}
figcaption {{ font-size: 15px; color: var(--ink); padding-inline: 4px; }}
figcaption b {{ font: 600 15px var(--display); color: var(--gold); margin-right: 6px; }}
.wall {{ border-radius: 20px; padding: 24px 20px; display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 20px; }}
.wall.dark {{ background: radial-gradient(120% 140% at 10% 0%, #2B3F6B 0%, #101828 55%, #0A0F1A 100%); }}
.wall.light {{ background: radial-gradient(120% 140% at 90% 0%, #FFE9D6 0%, #E7EEFB 50%, #CFDCF2 100%); }}
.wall.light .lbl {{ color: #1A2436; }}
.slot {{ display: grid; gap: 10px; justify-items: center; }}
.shapes {{ display: flex; gap: 14px; align-items: center; flex-wrap: wrap; justify-content: center; }}
.lbl {{ font-size: 13px; color: #E6ECF7; }}
.ico {{ width: var(--s); height: var(--s); overflow: hidden; display: inline-block; position: relative; box-shadow: 0 2px 8px rgba(0,0,0,.25); flex: none; }}
.ico img {{ position: absolute; width: calc(var(--s) * 1.5); height: calc(var(--s) * 1.5); left: calc(var(--s) * -0.25); top: calc(var(--s) * -0.25); }}
.circle {{ border-radius: 50%; }}
.squircle {{ border-radius: 32%; }}
.rounded {{ border-radius: 22%; }}
.themed-dark {{ background: #2A3A5A; }}
.themed-dark img {{ filter: brightness(0) saturate(100%) invert(84%) sepia(18%) saturate(600%) hue-rotate(185deg); }}
.themed-light {{ background: #D5E5FF; }}
.themed-light img {{ filter: brightness(0) saturate(100%) invert(21%) sepia(64%) saturate(1800%) hue-rotate(205deg); }}
.tiny {{ display: inline-grid; place-items: center; width: 44px; height: 44px; border-radius: 10px; background: #1A2436; }}
.panel {{ background: var(--panel); border: 1px solid var(--line); border-radius: 20px; padding: 20px; display: grid; gap: 20px;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); }}
ul {{ margin: 0; padding-left: 20px; color: var(--muted); max-width: 65ch; display: grid; gap: 6px; }}
li b {{ color: var(--ink); font-weight: 600; }}
</style>
<main>
  <header>
    <span class="eyebrow">Stupify · logo, round 1</span>
    <h1>The afro unicorn, <span>four ways</span></h1>
    <p>Same character in every draft: a front-facing unicorn, eyes closed and vibing, with a round afro as the icon's
      silhouette and headphones over it. Only the afro colours and the horn change.</p>
  </header>

  <section aria-labelledby="v">
    <h2 id="v">The four variants</h2>
    <div class="grid">{cards}</div>
  </section>

  <section aria-labelledby="h">
    <h2 id="h">On a home screen</h2>
    <p>Real launcher size (56 px), cropped the way Android crops adaptive icons: circle, squircle and rounded square,
      on a dark and a light wallpaper.</p>
    {row("dark")}
    {row("light")}
  </section>

  <section aria-labelledby="t">
    <h2 id="t">One-colour version</h2>
    <p>Android's themed icons and the notification bar show a single-colour silhouette, so the ears, horn and
      headphones are separated by thin gaps.</p>
    <div class="panel">{mono}</div>
  </section>

  <section aria-labelledby="s">
    <h2 id="s">Small sizes</h2>
    <p>24, 32 and 40 px: shortcuts, share sheets, settings lists.</p>
    <div class="panel">{small}</div>
  </section>

  <section aria-labelledby="n">
    <h2 id="n">Choosing</h2>
    <ul>
      <li><b>Blue (A, B)</b> matches the app's navy and blue exactly; <b>rainbow (C, D)</b> adds violet and mint for unicorn flavour.</li>
      <li><b>Classic horn</b> says "unicorn" instantly; the <b>lightning horn</b> nods to the Stupefy spell.</li>
      <li>Anything can still change: face, colours, headphone style, expression.</li>
    </ul>
  </section>
</main>
'''
open(os.path.join(D, "mascot-drafts.html"), "w").write(html)
print("ok", len(html))
