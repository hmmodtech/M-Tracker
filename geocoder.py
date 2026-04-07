"""
Gaza Strip Geocoder
Built from real humanitarian GIS data:
  - UNOCHA Gaza Strip Neighbourhoods (149 neighbourhoods, 6 communities)
  - OCHA/ESRI Road Network oPt — Gaza roads (343 named roads)
  - HOT OSM Palestine Buildings — named buildings in Gaza (1,839 entries)

Exposes:
  search(query, limit=10)  -> list of place dicts
  reverse(lat, lon, radius_km=0.5) -> nearest place dict or None
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
