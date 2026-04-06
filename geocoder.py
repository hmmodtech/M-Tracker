"""
Gaza Strip Geocoder — uses OpenStreetMap Nominatim for accurate coordinates.
Falls back to a curated local dictionary if the API is unavailable.
Results are cached in memory to avoid repeated API calls.
"""
import requests
import logging
import time

logger = logging.getLogger(__name__)

# In-memory cache: location_name -> result dict
_cache = {}

NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
NOMINATIM_HEADERS = {
    "User-Agent": "T-TRACKER-ACF/1.0 (Gaza GEOINT System)",
    "Accept-Language": "ar,en"
}

# Curated fallback dictionary with verified coordinates from OSM
# Format: "Arabic name": (lat, lon)
FALLBACK = {
    # Cities & major areas
    "غزة": (31.5017, 34.4674),
    "مدينة غزة": (31.5017, 34.4674),
    "خانيونس": (31.3462, 34.3023),
    "خان يونس": (31.3462, 34.3023),
    "رفح": (31.2968, 34.2471),
    "دير البلح": (31.4167, 34.3500),
    "النصيرات": (31.4394, 34.3853),
    "البريج": (31.4617, 34.3972),
    "المغازي": (31.4756, 34.4078),
    "جباليا": (31.5278, 34.4897),
    "بيت لاهيا": (31.5544, 34.5000),
    "بيت حانون": (31.5394, 34.5336),
    # Neighborhoods
    "الشجاعية": (31.5033, 34.5033),
    "الزيتون": (31.4944, 34.4772),
    "الرمال": (31.5247, 34.4464),
    "التفاح": (31.5089, 34.4831),
    "الدرج": (31.5089, 34.4653),
    "الصبرة": (31.4983, 34.4617),
    "الشيخ رضوان": (31.5383, 34.4578),
    "المنطار": (31.5178, 34.5061),
    "حي الأمل": (31.3522, 34.3100),
    "الشاطئ": (31.5233, 34.4317),
    "المواصي": (31.3551, 34.2634),
    "بني سهيلا": (31.3617, 34.3439),
    "عبسان": (31.3489, 34.3858),
    "الزوايدة": (31.4317, 34.3722),
    "الشوكة": (31.3178, 34.3622),
    # Refugee camps
    "مخيم جباليا": (31.5278, 34.4897),
    "مخيم الشاطئ": (31.5233, 34.4317),
    "مخيم النصيرات": (31.4394, 34.3853),
    "مخيم البريج": (31.4617, 34.3972),
    "مخيم المغازي": (31.4756, 34.4078),
    "مخيم رفح": (31.2968, 34.2471),
    "مخيم خانيونس": (31.3462, 34.3023),
    # Main roads (verified from OSM)
    "شارع صلاح الدين": (31.3800, 34.3300),
    "صلاح الدين": (31.3800, 34.3300),
    "طريق صلاح الدين": (31.3800, 34.3300),
    "شارع الرشيد": (31.5000, 34.4100),
    "الرشيد": (31.5000, 34.4100),
    "الطريق الساحلي": (31.5000, 34.4100),
    "شارع عمر المختار": (31.5178, 34.4472),
    "عمر المختار": (31.5178, 34.4472),
    "شارع الوحدة": (31.5197, 34.4508),
    # Strategic axes
    "محور نتساريم": (31.4750, 34.3950),
    "نتساريم": (31.4750, 34.3950),
    "محور فيلادلفيا": (31.2850, 34.2400),
    "فيلادلفيا": (31.2850, 34.2400),
    "ممر الرشيد": (31.4200, 34.3500),
    "محور نيتساريم": (31.4750, 34.3950),
    # Roundabouts / junctions
    "دوار النجمة": (31.2847, 34.2531),
    "دوار الكويت": (31.5200, 34.4472),
    "دوار الشهداء": (31.3500, 34.3050),
    "دوار بني سهيلا": (31.3617, 34.3439),
    "مفترق السرايا": (31.5194, 34.4481),
    "دوار الساعة": (31.5217, 34.4467),
    # Hospitals (verified)
    "مستشفى الشفاء": (31.5167, 34.4506),
    "الشفاء": (31.5167, 34.4506),
    "مجمع الشفاء": (31.5167, 34.4506),
    "مستشفى ناصر": (31.3489, 34.3072),
    "مستشفى الأوروبي": (31.3378, 34.2972),
    "المستشفى الأوروبي": (31.3378, 34.2972),
    "مستشفى القدس": (31.5233, 34.4394),
    "مستشفى الأقصى": (31.4167, 34.3500),
    "مستشفى كمال عدوان": (31.5544, 34.5000),
    "كمال عدوان": (31.5544, 34.5000),
    "مستشفى إندونيسيا": (31.5478, 34.4981),
    "مستشفى الرنتيسي": (31.5261, 34.4397),
    "مستشفى النجار": (31.2968, 34.2471),
    # Crossings
    "معبر رفح": (31.2700, 34.2200),
    "معبر كرم أبو سالم": (31.2889, 34.3417),
    "كرم أبو سالم": (31.2889, 34.3417),
    "معبر إيرز": (31.6025, 34.5114),
    # Landmarks
    "ميناء غزة": (31.5250, 34.4264),
    "الميناء": (31.5250, 34.4264),
    "السرايا": (31.5194, 34.4481),
    "وادي غزة": (31.4647, 34.4219),
}

# Gaza Strip bounding box for validation
GAZA_BBOX = {
    "min_lat": 31.21, "max_lat": 31.61,
    "min_lon": 34.20, "max_lon": 34.55
}

def _in_gaza(lat, lon):
    return (GAZA_BBOX["min_lat"] <= lat <= GAZA_BBOX["max_lat"] and
            GAZA_BBOX["min_lon"] <= lon <= GAZA_BBOX["max_lon"])

def _nominatim_search(place_name):
    """Query OSM Nominatim for a place name, restricted to Gaza Strip area."""
    try:
        params = {
            "q": place_name + " غزة قطاع",
            "format": "json",
            "limit": 3,
            "countrycodes": "ps",
            "viewbox": "34.20,31.61,34.55,31.21",
            "bounded": 0,
        }
        resp = requests.get(
            NOMINATIM_URL,
            params=params,
            headers=NOMINATIM_HEADERS,
            timeout=5
        )
        resp.raise_for_status()
        results = resp.json()
        for r in results:
            lat = float(r.get("lat", 0))
            lon = float(r.get("lon", 0))
            if _in_gaza(lat, lon):
                return lat, lon
        # Try without "غزة" suffix
        params["q"] = place_name
        resp = requests.get(NOMINATIM_URL, params=params, headers=NOMINATIM_HEADERS, timeout=5)
        results = resp.json()
        for r in results:
            lat = float(r.get("lat", 0))
            lon = float(r.get("lon", 0))
            if _in_gaza(lat, lon):
                return lat, lon
    except Exception as e:
        logger.debug(f"Nominatim error for '{place_name}': {e}")
    return None, None

def _build_result(name, lat, lon, source=""):
    return {
        "name": name,
        "lat": lat,
        "lon": lon,
        "coords": f"{lat},{lon}",
        "gmaps": f"https://www.google.com/maps?q={lat},{lon}&z=17",
    }

def find_location(text):
    """
    Scan Arabic news text for Gaza location names.
    1. Check fallback dictionary (fast, sorted by name length desc for specificity)
    2. For unknown names, try Nominatim OSM API
    Returns a result dict or None.
    """
    if not text:
        return None

    # Sort by name length descending so longer/more-specific names match first
    sorted_names = sorted(FALLBACK.keys(), key=len, reverse=True)

    for name in sorted_names:
        if name in text:
            if name in _cache:
                return _cache[name]
            lat, lon = FALLBACK[name]
            # Try to get more precise coords from Nominatim first
            api_lat, api_lon = _nominatim_search(name)
            if api_lat and api_lon and _in_gaza(api_lat, api_lon):
                result = _build_result(name, api_lat, api_lon, "nominatim")
                logger.info(f"Nominatim match: {name} -> ({api_lat},{api_lon})")
            else:
                result = _build_result(name, lat, lon, "fallback")
            _cache[name] = result
            return result

    # No dictionary match — try to find any location-like phrase with Nominatim
    # Look for common Arabic location prefixes
    import re
    patterns = [
        r'(?:منطقة|حي|شارع|طريق|مخيم|دوار|مفترق|مستشفى|مدخل|شمال|جنوب|شرق|غرب)\s+[\u0600-\u06ff\s]{3,25}',
        r'[\u0600-\u06ff]{4,20}\s+(?:الشمالي|الجنوبي|الشرقي|الغربي|المركزي)',
    ]
    for pattern in patterns:
        matches = re.findall(pattern, text)
        for match in matches:
            match = match.strip()
            if match in _cache:
                return _cache[match]
            api_lat, api_lon = _nominatim_search(match)
            if api_lat and api_lon and _in_gaza(api_lat, api_lon):
                result = _build_result(match, api_lat, api_lon, "nominatim-pattern")
                _cache[match] = result
                logger.info(f"Pattern match via Nominatim: {match} -> ({api_lat},{api_lon})")
                return result

    return None
