"""Stupify mascot drafts: front-facing unicorn, afro, headphones. 108x108 = Android adaptive icon canvas
(the launcher shows roughly the central 72, so everything important sits within r~33 of the centre)."""
import math, sys, os
OUT = os.path.dirname(os.path.abspath(__file__))

AFROS = {
    "blue":    [("0", "#2F7CF6"), ("0.55", "#5AA9FF"), ("1", "#5FE0E6")],
    "rainbow": [("0", "#8B6CFF"), ("0.35", "#5AA9FF"), ("0.7", "#5FE0E6"), ("1", "#8CF5C2")],
}
HORNS = {
    # classic spiral horn: cone with three gold bands
    "classic": '''<path d="M54,15 L59.5,31 Q54,33.5 48.5,31 Z" fill="url(#gold)"/>
      <path d="M50.4,26.2 Q54,27.6 57.6,25.6 M51.6,22.2 Q54,23.4 56.4,21.6 M52.7,18.6 Q54,19.4 55.3,18.2"
            stroke="#B8860B" stroke-width="0.9" fill="none" stroke-linecap="round"/>''',
    # lightning-bolt horn (the "Stupefy" spell)
    "bolt": '''<path d="M56.5,13.5 L48.5,25 L53.2,25 L50.5,33 L60,20.5 L55,20.5 Z" fill="url(#gold)"
            stroke="#B8860B" stroke-width="0.6" stroke-linejoin="round"/>''',
}

def afro(cx, cy, core, ring, n, r):
    bumps = "".join(f'<circle cx="{cx + ring*math.cos(2*math.pi*i/n):.1f}" cy="{cy + ring*math.sin(2*math.pi*i/n):.1f}" r="{r}"/>'
                    for i in range(n))
    return f'<circle cx="{cx}" cy="{cy}" r="{core}"/>{bumps}'

def svg(afro_name, horn_name, bg=True, size=108):
    stops = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in AFROS[afro_name])
    navy = "#0D1B33"
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="{size}" height="{size}">
  <defs>
    <linearGradient id="afro" x1="0.15" y1="0" x2="0.85" y2="1">{stops}</linearGradient>
    <linearGradient id="gold" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFE58A"/><stop offset="1" stop-color="#F2B233"/></linearGradient>
    <radialGradient id="bg" cx="0.5" cy="0.4" r="0.7"><stop offset="0" stop-color="#16274A"/><stop offset="1" stop-color="#070D1A"/></radialGradient>
  </defs>
  {'<rect width="108" height="108" fill="url(#bg)"/>' if bg else ''}
  <!-- afro (behind the head) -->
  <g fill="url(#afro)">{afro(54, 47, 19, 19.5, 12, 8.6)}</g>
  <!-- horse ears, poking out of the afro -->
  <path d="M39.5,37 C36,30 35.5,23 37.5,19.5 C42,22.5 45,28 45,34 Z" fill="#FFFFFF"/>
  <path d="M40.4,33.5 C38.6,29 38.4,25 39.4,22.8 C41.8,25 43.2,28.5 43.4,32.2 Z" fill="#FFB3CF"/>
  <path d="M68.5,37 C72,30 72.5,23 70.5,19.5 C66,22.5 63,28 63,34 Z" fill="#FFFFFF"/>
  <path d="M67.6,33.5 C69.4,29 69.6,25 68.6,22.8 C66.2,25 64.8,28.5 64.6,32.2 Z" fill="#FFB3CF"/>
  <!-- long horse head: wide at the eyes, narrower cheeks, rounded muzzle -->
  <path d="M54,36 C65,36 70,44 69.5,53 C69,61 64,64.5 63.5,70.5 L63.5,76 C63.5,83.5 59,87 54,87 C49,87 44.5,83.5 44.5,76 L44.5,70.5 C44,64.5 39,61 38.5,53 C38,44 43,36 54,36 Z" fill="#FFFFFF"/>
  <ellipse cx="54" cy="78.5" rx="9.6" ry="8.2" fill="#F3E6FF"/>
  <ellipse cx="50.2" cy="78" rx="1.6" ry="2.3" fill="#B993DD"/>
  <ellipse cx="57.8" cy="78" rx="1.6" ry="2.3" fill="#B993DD"/>
  <path d="M50.5,82.6 Q54,85 57.5,82.6" stroke="#8C6BB8" stroke-width="1.1" fill="none" stroke-linecap="round"/>
  <!-- blissful closed eyes with lashes, blush -->
  <path d="M44.6,54.5 Q47.6,57.8 50.6,54.5 M57.4,54.5 Q60.4,57.8 63.4,54.5" stroke="{navy}" stroke-width="1.7" fill="none" stroke-linecap="round"/>
  <path d="M45.6,56 l-1.5,1.3 M47.6,57.1 l-0.5,1.7 M62.4,56 l1.5,1.3 M60.4,57.1 l0.5,1.7" stroke="{navy}" stroke-width="1" stroke-linecap="round"/>
  <ellipse cx="44.2" cy="61.5" rx="3" ry="1.7" fill="#FF8FB8" opacity="0.55"/>
  <ellipse cx="63.8" cy="61.5" rx="3" ry="1.7" fill="#FF8FB8" opacity="0.55"/>
  <!-- afro fringe over the forehead -->
  <g fill="url(#afro)"><circle cx="48" cy="40.5" r="5"/><circle cx="54" cy="39" r="5.4"/><circle cx="60" cy="40.5" r="5"/></g>
  <!-- headphones: band over the afro, cups on the sides of the head -->
  <path d="M38,56 C36,22 72,22 70,56" stroke="{navy}" stroke-width="4.6" fill="none" stroke-linecap="round"/>
  <path d="M39.3,47 C40,27 68,27 68.7,47" stroke="#5AA9FF" stroke-width="1.1" fill="none" stroke-linecap="round" opacity="0.9"/>
  <rect x="31.5" y="50" width="10.5" height="17" rx="4.8" fill="{navy}"/>
  <rect x="66" y="50" width="10.5" height="17" rx="4.8" fill="{navy}"/>
  <rect x="39.2" y="52.2" width="3.6" height="12.6" rx="1.8" fill="#5FE0E6"/>
  <rect x="65.2" y="52.2" width="3.6" height="12.6" rx="1.8" fill="#5FE0E6"/>
  <!-- horn -->
  {HORNS[horn_name]}
</svg>'''

EARS = ('M39.5,37 C36,30 35.5,23 37.5,19.5 C42,22.5 45,28 45,34 Z',
        'M68.5,37 C72,30 72.5,23 70.5,19.5 C66,22.5 63,28 63,34 Z')
HEAD = ('M54,36 C65,36 70,44 69.5,53 C69,61 64,64.5 63.5,70.5 L63.5,76 C63.5,83.5 59,87 54,87 '
        'C49,87 44.5,83.5 44.5,76 L44.5,70.5 C44,64.5 39,61 38.5,53 C38,44 43,36 54,36 Z')
HORN_SHAPES = {"classic": 'M54,15 L59.5,31 Q54,33.5 48.5,31 Z',
               "bolt": 'M56.5,13.5 L48.5,25 L53.2,25 L50.5,33 L60,20.5 L55,20.5 Z'}
EYES = 'M44.6,54.5 Q47.6,57.8 50.6,54.5 M57.4,54.5 Q60.4,57.8 63.4,54.5'
CUPS = '<rect x="31.5" y="50" width="10.5" height="17" rx="4.8"/><rect x="66" y="50" width="10.5" height="17" rx="4.8"/>'


def mono(horn_name, size=108, fg="#FFFFFF", bg=None, scale=1.0):
    """One-colour version (Android themed icon / notification): the afro is cut back around the ears,
    horn, head and cups so every part keeps its own outline, and the face features are cut out."""
    shapes = "".join(f'<path d="{d}"/>' for d in (*EARS, HEAD, HORN_SHAPES[horn_name])) + CUPS
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="{size}" height="{size}">
  {f'<rect width="108" height="108" fill="{bg}"/>' if bg else ''}
  <mask id="gap"><rect width="108" height="108" fill="#fff"/>
    <g fill="none" stroke="#000" stroke-width="4.2" stroke-linejoin="round">{shapes}</g>
    <path d="M38,56 C36,22 72,22 70,56" stroke="#000" stroke-width="2.6" fill="none"/>
    <path d="{EYES}" stroke="#000" stroke-width="2.2" fill="none" stroke-linecap="round"/>
  </mask>
  <mask id="face"><rect width="108" height="108" fill="#fff"/>
    <path d="{EYES}" stroke="#000" stroke-width="2.2" fill="none" stroke-linecap="round"/>
    <ellipse cx="50.2" cy="78" rx="1.8" ry="2.5" fill="#000"/><ellipse cx="57.8" cy="78" rx="1.8" ry="2.5" fill="#000"/>
    <g fill="none" stroke="#000" stroke-width="2.2">{CUPS}</g>
  </mask>
  <g transform="translate(54 51) scale({scale}) translate(-54 -51)">
  <g fill="{fg}" mask="url(#gap)">{afro(54, 47, 19, 19.5, 12, 8.6)}</g>
  <g fill="{fg}" mask="url(#face)">{shapes}</g>
  </g>
</svg>'''


if __name__ == "__main__":
    for a in AFROS:
        for h in HORNS:
            open(os.path.join(OUT, f"draft-{a}-{h}.svg"), "w").write(svg(a, h))
    for h in HORNS:
        open(os.path.join(OUT, f"mono-{h}.svg"), "w").write(mono(h))
    print(sorted(f for f in os.listdir(OUT) if f.endswith(".svg")))
