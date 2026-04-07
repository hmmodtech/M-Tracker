"""
Gaza Strip Geocoder
Built from real humanitarian GIS data:
  - UNOCHA Gaza Strip Neighbourhoods (149 neighbourhoods, 6 communities)
  - OCHA/ESRI Road Network oPt — Gaza roads (343 named roads)
  - HOT OSM Palestine Buildings — named buildings in Gaza (1,839 entries)

Exposes:
  search(query, limit=10)          -> list of place dicts
  reverse(lat, lon, radius_km=0.5) -> nearest place dict or None
  find_location(text)              -> {name, lat, lon, coords, gmaps} or None
                                      Scans Arabic/English news text for Gaza
                                      location mentions.
"""
import json, os, math, re, unicodedata

_DB = None
_DB_PATH = os.path.join(os.path.dirname(__file__), 'static', 'gaza_geocoder_db.json')

def _load():
    global _DB
    if _DB is None:
        with open(_DB_PATH, encoding='utf-8') as f:
            _DB = json.load(f)
    return _DB

def _normalize(s):
    if not s: return ''
    s = s.lower().strip()
    s = unicodedata.normalize('NFD', s)
    s = ''.join(c for c in s if unicodedata.category(c) != 'Mn')
    s = re.sub(r'[^\w\u0600-\u06ff\s]', ' ', s)
    return re.sub(r'\s+', ' ', s).strip()

def _score(place, query_norm, query_tokens):
    candidates = [
        _normalize(place.get('n', '')),
        _normalize(place.get('ne', '') or ''),
        _normalize(place.get('na', '') or ''),
        _normalize(place.get('c', '') or ''),
        _normalize(place.get('d', '') or ''),
    ]
    best = 0
    for cand in candidates:
        if not cand: continue
        if cand == query_norm: best = max(best, 100)
        elif cand.startswith(query_norm): best = max(best, 90)
        elif query_norm in cand: best = max(best, 75)
        else:
            ctokens = set(cand.split())
            matched = sum(1 for t in query_tokens if t in ctokens or any(t in ct for ct in ctokens))
            if matched: best = max(best, 35 + matched * 12)
    t = place.get('t', ''); b = place.get('b', '')
    if t == 'community': best += 8
    if t == 'neighbourhood': best += 5
    if b in ('hospital','mosque','school','university','government','clinic'): best += 6
    if t == 'road' and place.get('rc','') == 'Main Road': best += 3
    return best

def search(query, limit=10):
    """Search for a place in Gaza Strip. Returns list of place dicts sorted by relevance."""
    if not query or len(query.strip()) < 2: return []
    db = _load()
    q = _normalize(query)
    qtokens = [t for t in q.split() if len(t) > 1]
    results = []
    for place in db:
        score = _score(place, q, qtokens)
        if score < 30: continue
        t = place.get('t', ''); b = place.get('b', '')
        parts = [place.get('n', '')]
        if t == 'neighbourhood' and place.get('c'): parts.append(place['c'])
        if t in ('neighbourhood','community','road') and place.get('d'): parts.append(place['d'])
        if t == 'building' and b and b not in ('yes','ruins'): parts.append(b.replace('_',' ').title())
        parts.append('Gaza Strip')
        results.append({'name': place.get('n',''), 'name_ar': place.get('na','') or '',
                        'lat': place['lat'], 'lon': place['lon'], 'type': t, 'building': b,
                        'display': ', '.join(p for p in parts if p), '_s': score})
    results.sort(key=lambda x: -x['_s'])
    for r in results: del r['_s']
    return results[:limit]

def reverse(lat, lon, radius_km=0.5):
    """Find nearest named place to given WGS-84 coordinates."""
    db = _load(); best = None; best_dist = float('inf')
    for place in db:
        dlat = place['lat'] - lat
        dlon = (place['lon'] - lon) * math.cos(math.radians(lat))
        dist = math.sqrt(dlat**2 + dlon**2) * 111.32
        if dist < best_dist and dist <= radius_km:
            best_dist = dist; best = place
    if best:
        return {'name': best.get('n',''), 'lat': best['lat'], 'lon': best['lon'],
                'type': best.get('t',''), 'dist_km': round(best_dist, 3)}
    return None


# ── find_location ─────────────────────────────────────────────────────────────

# Curated Arabic->coords map for all major Gaza locations referenced in news.
# Verified against UNOCHA / OCHA / OSM sources.
# Sorted by name length (longest first) so "مخيم جباليا" beats "جباليا".
_ARABIC_MAP = {
    # Major cities
    "مدينة غزة":            (31.5017, 34.4674, "Gaza City"),
    "غزة":                  (31.5017, 34.4674, "Gaza City"),
    "خانيونس":              (31.3462, 34.3023, "Khan Yunis"),
    "خان يونس":             (31.3462, 34.3023, "Khan Yunis"),
    "رفح":                  (31.2968, 34.2471, "Rafah"),
    "دير البلح":            (31.4167, 34.3500, "Deir al-Balah"),
    "النصيرات":             (31.4394, 34.3853, "Nuseirat"),
    "البريج":               (31.4617, 34.3972, "Al-Bureij"),
    "المغازي":              (31.4756, 34.4078, "Al-Maghazi"),
    "جباليا":               (31.5278, 34.4822, "Jabalia"),
    "بيت لاهيا":            (31.5416, 34.4995, "Beit Lahiya"),
    "بيت حانون":            (31.5394, 34.5336, "Beit Hanoun"),
    "الزوايدة":             (31.4317, 34.3722, "Al-Zawayda"),
    "القرارة":              (31.3747, 34.3358, "Al-Qarara"),
    # Refugee camps
    "مخيم جباليا":          (31.5278, 34.4822, "Jabalia Camp"),
    "مخيم الشاطئ":          (31.5233, 34.4317, "Beach Camp"),
    "مخيم النصيرات":        (31.4394, 34.3853, "Nuseirat Camp"),
    "مخيم البريج":          (31.4617, 34.3972, "Bureij Camp"),
    "مخيم المغازي":         (31.4756, 34.4078, "Maghazi Camp"),
    "مخيم خانيونس":         (31.3462, 34.3023, "Khan Yunis Camp"),
    "مخيم رفح":             (31.2968, 34.2471, "Rafah Camp"),
    # Neighbourhoods
    "الشجاعية":             (31.5033, 34.5033, "Shujayya"),
    "شجاعية":               (31.5033, 34.5033, "Shujayya"),
    "حي الزيتون":           (31.4944, 34.4772, "Al-Zaytoun"),
    "الزيتون":              (31.4944, 34.4772, "Al-Zaytoun"),
    "حي الرمال":            (31.5247, 34.4464, "Al-Rimal"),
    "الرمال":               (31.5247, 34.4464, "Al-Rimal"),
    "التفاح":               (31.5089, 34.4831, "Al-Tuffah"),
    "الدرج":                (31.5089, 34.4653, "Al-Daraj"),
    "الصبرة":               (31.4983, 34.4617, "Al-Sabra"),
    "الشيخ رضوان":          (31.5383, 34.4578, "Sheikh Radwan"),
    "المنطار":              (31.5178, 34.5061, "Al-Mantar"),
    "الوحدة":               (31.5197, 34.4508, "Al-Wahda"),
    "الشيخ عجلين":          (31.5011, 34.4494, "Sheikh Ajlin"),
    "حي الأمل":             (31.3522, 34.3100, "Amal Quarter"),
    "الأمل":                (31.3522, 34.3100, "Amal Quarter"),
    "حي البرازيل":          (31.2911, 34.2511, "Brazil Quarter"),
    "البرازيل":             (31.2911, 34.2511, "Brazil Quarter"),
    "الشاطئ":               (31.5233, 34.4317, "Beach Camp"),
    "تل السلطان":           (31.2867, 34.2344, "Tel Sultan"),
    "شابورة":               (31.3000, 34.2578, "Shabura"),
    "يبنا":                 (31.2950, 34.2550, "Yabna"),
    "المواصي":              (31.3551, 34.2634, "Al-Mawasi"),
    "بني سهيلا":            (31.3617, 34.3439, "Bani Suhayla"),
    "عبسان الكبيرة":        (31.3489, 34.3858, "Abasan al-Kabira"),
    "عبسان":                (31.3489, 34.3858, "Abasan"),
    "خزاعة":                (31.3397, 34.4014, "Khuzaa"),
    "الفخاري":              (31.3733, 34.3550, "Al-Fukhari"),
    "وادي غزة":             (31.4647, 34.4219, "Wadi Gaza"),
    "الصفطاوي":             (31.5633, 34.5047, "Al-Saftawi"),
    "عطاطرة":               (31.5758, 34.4858, "Atatra"),
    # Roads
    "شارع صلاح الدين":      (31.3931, 34.3725, "Salah Al-Din Road"),
    "طريق صلاح الدين":      (31.3931, 34.3725, "Salah Al-Din Road"),
    "صلاح الدين":           (31.3931, 34.3725, "Salah Al-Din Road"),
    "شارع الرشيد":          (31.5355, 34.4451, "Al-Rasheed Street"),
    "الطريق الساحلي":       (31.5000, 34.4100, "Coastal Road"),
    "شارع عمر المختار":     (31.5178, 34.4472, "Omar Al-Mukhtar St."),
    "عمر المختار":          (31.5178, 34.4472, "Omar Al-Mukhtar St."),
    "شارع الوحدة":          (31.5197, 34.4508, "Al-Wahda Street"),
    "شارع النصر":           (31.5289, 34.4578, "Al-Nasr Street"),
    "شارع فلسطين":          (31.5200, 34.4550, "Palestine Street"),
    "الشارع الخامس":        (31.3512, 34.2985, "Street 5"),
    "شارع 5":               (31.3512, 34.2985, "Street 5"),
    "الرشيد":               (31.5355, 34.4451, "Al-Rasheed Street"),
    # Strategic axes & corridors
    "محور نتساريم":         (31.4750, 34.3950, "Netzarim Corridor"),
    "محور نيتساريم":        (31.4750, 34.3950, "Netzarim Corridor"),
    "محور فيلادلفيا":       (31.2850, 34.2400, "Philadelphi Corridor"),
    "ممر الرشيد":           (31.4200, 34.3500, "Rasheed Corridor"),
    "نتساريم":              (31.4750, 34.3950, "Netzarim"),
    "فيلادلفيا":            (31.2850, 34.2400, "Philadelphi"),
    # Roundabouts & junctions
    "دوار النجمة":          (31.2847, 34.2531, "Al-Najma Roundabout"),
    "دوار الكويت":          (31.5200, 34.4472, "Kuwait Roundabout"),
    "دوار الشهداء":         (31.3500, 34.3050, "Martyrs Roundabout"),
    "دوار بني سهيلا":       (31.3617, 34.3439, "Bani Suhayla R/A"),
    "دوار الساعة":          (31.5217, 34.4467, "Clock Roundabout"),
    "مفترق السرايا":        (31.5194, 34.4481, "Al-Saraya Junction"),
    "السرايا":              (31.5194, 34.4481, "Al-Saraya"),
    # Hospitals
    "مستشفى شهداء الأقصى": (31.4167, 34.3500, "Shuhada Al-Aqsa Hospital"),
    "مستشفى كمال عدوان":    (31.5544, 34.5000, "Kamal Adwan Hospital"),
    "مستشفى إندونيسيا":     (31.5478, 34.4981, "Indonesian Hospital"),
    "مستشفى الرنتيسي":      (31.5261, 34.4397, "Al-Rantisi Hospital"),
    "مستشفى الأوروبي":      (31.3378, 34.2972, "European Hospital"),
    "المستشفى الأوروبي":    (31.3378, 34.2972, "European Hospital"),
    "مجمع الشفاء":          (31.5167, 34.4506, "Al-Shifa Complex"),
    "مستشفى الشفاء":        (31.5167, 34.4506, "Al-Shifa Hospital"),
    "مستشفى الأقصى":        (31.4167, 34.3500, "Al-Aqsa Hospital"),
    "مستشفى القدس":         (31.5233, 34.4394, "Al-Quds Hospital"),
    "مستشفى النجار":        (31.2968, 34.2471, "Al-Najjar Hospital"),
    "مستشفى ناصر":          (31.3489, 34.3072, "Nasser Hospital"),
    "كمال عدوان":           (31.5544, 34.5000, "Kamal Adwan Hospital"),
    "الشفاء":               (31.5167, 34.4506, "Al-Shifa Hospital"),
    "ناصر":                 (31.3489, 34.3072, "Nasser Hospital"),
    # Crossings
    "معبر كرم أبو سالم":    (31.2889, 34.3417, "Kerem Shalom Crossing"),
    "كرم أبو سالم":         (31.2889, 34.3417, "Kerem Shalom"),
    "معبر رفح":             (31.2700, 34.2200, "Rafah Crossing"),
    "معبر إيرز":            (31.6025, 34.5114, "Erez Crossing"),
    # Landmarks
    "الجامعة الإسلامية":    (31.5156, 34.4469, "Islamic University"),
    "الجامعة الاسلامية":    (31.5156, 34.4469, "Islamic University"),
    "سوق الزاوية":          (31.5189, 34.4464, "Al-Zawiya Market"),
    "ميناء غزة":            (31.5250, 34.4264, "Gaza Port"),
    "الميناء":              (31.5250, 34.4264, "Gaza Port"),
    "بلدية غزة":            (31.5186, 34.4472, "Gaza Municipality"),
}

# Pre-sorted longest-first for greedy matching
_SORTED_AR = sorted(_ARABIC_MAP.keys(), key=len, reverse=True)

# English/transliterated aliases -> DB search term
_EN_ALIASES = {
    "jabalia camp":  "Jabalia",
    "beach camp":    "Al-Shati",
    "jabalia":       "Jabalia",
    "jabalya":       "Jabalya",
    "rafah":         "Rafah",
    "khan yunis":    "Khan Yunis",
    "khan younis":   "Khan Yunis",
    "deir al-balah": "Deir al-Balah",
    "nuseirat":      "Nuseirat",
    "shujayya":      "Shujayya",
    "beit hanoun":   "Beit Hanoun",
    "beit lahiya":   "Beit Lahiya",
    "salah al-din":  "Salah Ad Deen",
    "salah al din":  "Salah Ad Deen",
    "al-rasheed":    "Ar Rasheed",
    "rasheed":       "Ar Rasheed",
    "netzarim":      "Salah Ad Deen",
    "philadelphi":   "Yaser Arafat",
}
_SORTED_EN = sorted(_EN_ALIASES.keys(), key=len, reverse=True)


def _make_result(label, lat, lon):
    """Build the result dict that scraper.py expects."""
    coords = f"{lat},{lon}"
    return {
        "name":   label,
        "lat":    lat,
        "lon":    lon,
        "coords": coords,
        "gmaps":  f"https://www.google.com/maps?q={lat},{lon}&z=17",
    }


def find_location(text):
    """
    Scan Arabic / English news text for Gaza Strip location mentions.

    Priority:
    1. Curated Arabic map — fast substring, longest-name-first.
    2. Known English/transliterated aliases — substring match on lowercase text.
    3. GIS DB n-gram search — 2-3 Arabic word phrases through search().

    Returns {name, lat, lon, coords, gmaps} or None.
    """
    if not text:
        return None

    # Pass 1 — Arabic curated map
    for ar in _SORTED_AR:
        if ar in text:
            lat, lon, label = _ARABIC_MAP[ar]
            return _make_result(label, lat, lon)

    # Pass 2 — English/transliterated aliases
    tl = text.lower()
    for alias in _SORTED_EN:
        if alias in tl:
            hits = search(_EN_ALIASES[alias], limit=1)
            if hits:
                h = hits[0]
                return _make_result(h["name"], h["lat"], h["lon"])

    # Pass 3 — GIS DB n-gram search (Arabic tokens only)
    tokens = [t for t in text.split() if len(t) >= 3 and any("\u0600" <= c <= "\u06ff" for c in t)]
    for n in (3, 2):
        for i in range(len(tokens) - n + 1):
            phrase = " ".join(tokens[i:i+n])
            hits = search(phrase, limit=1)
            if hits:
                h = hits[0]
                tp = h.get("type", "")
                if tp in ("community", "neighbourhood", "road"):
                    return _make_result(h["name"], h["lat"], h["lon"])
                if tp == "building" and n >= 3:
                    return _make_result(h["name"], h["lat"], h["lon"])

    # Pass 4 — single Arabic token, community/neighbourhood only
    for tok in tokens:
        if len(tok) < 4:
            continue
        hits = search(tok, limit=1)
        if hits and hits[0].get("type") in ("community", "neighbourhood"):
            h = hits[0]
            return _make_result(h["name"], h["lat"], h["lon"])

    return None
