"""
T-TRACKER Scraper
- Telegram: fetches t.me/s/<username> public feed
- Website: scrapes headline links from any news site
- Only stores messages containing at least one keyword
"""
import requests, re, logging
from bs4 import BeautifulSoup
from urllib.parse import urljoin
import database as db
from geocoder import find_location

logger = logging.getLogger(__name__)

HEADERS = {
    'User-Agent': (
        'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
        'AppleWebKit/537.36 (KHTML, like Gecko) '
        'Chrome/124.0.0.0 Safari/537.36'
    ),
    'Accept-Language': 'ar,en-US;q=0.9,en;q=0.8',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
}

CRITICAL_WORDS = [
    "استهداف","قصف","شهيد","شهداء","جرحى",
    "إصابات","انتشال","تحت الأنقاض","أشلاء",
    # Evacuation / displacement
    "اشارة اخلاء","إشارة إخلاء","أوامر اخلاء","أوامر إخلاء",
    "اوامر اخلاء","اوامر إخلاء",
    "evacuation order","evacuation warning","forced displacement",
]

def _is_critical(text):
    return 1 if any(w in text for w in CRITICAL_WORDS) else 0

def _has_keyword(text):
    """
    Accept a message if it matches ANY of:
    1. A hardcoded Gaza security term — ensures critical news is NEVER missed
    2. A keyword from the DB (user-configured + code-seeded list)
    """
    GAZA_SECURITY = [
        # Locations — any message mentioning these passes
        "غزة","رفح","جباليا","خانيونس","خان يونس","دير البلح","النصيرات",
        "البريج","المغازي","الشجاعية","بيت لاهيا","بيت حانون","الشاطئ",
        "المواصي","تل السلطان","شابورة","بني سهيلا",
        "صلاح الدين","الرشيد","نتساريم","فيلادلفيا","كرم أبو سالم",
        # Security / military
        "استهداف","قصف","غارة","مدفعية","دبابة","اقتحام","توغل",
        "مسيرة","طائرة","زنانة","كواد","انفجار","إطلاق نار","اشتباك",
        "دورية","قناص","عبوة","صاروخ","مدفعي",
        # Casualties
        "شهيد","شهداء","جرحى","إصابة","إصابات","انتشال","أنقاض","أشلاء",
        # Displacement / evacuation
        "نزوح","نازح","نازحين","إخلاء","اخلاء","إيواء","خيام","تهجير",
        "أوامر إخلاء","أوامر اخلاء","اشارة اخلاء","إشارة إخلاء",
        # Humanitarian
        "مساعدات","إغاثة","مجاعة","وقود","أدوية","مستشفى","معبر",
        # Operations / political
        "عاجل","عملية","اعتقال","مداهمة","حصار","وقف إطلاق",
        "صفقة","تبادل","أسرى","مفاوضات",
    ]
    if any(kw in text for kw in GAZA_SECURITY):
        return True
    kws = db.get_all_keyword_words()
    if not kws:
        return True
    return any(k in text for k in kws)

def _summarize(text, max_chars=200):
    if len(text) <= max_chars:
        return text
    parts = re.split(r'(?<=[.،؟!\n])\s+', text)
    out = ''
    for p in parts:
        p = p.strip()
        if not p:
            continue
        if len(out) + len(p) <= max_chars:
            out += p + ' '
        else:
            break
    return (out.strip() or text[:max_chars]) + '…'

# ── Telegram ──────────────────────────────────────────────────
def scrape_telegram(username):
    url = f'https://t.me/s/{username}'
    try:
        resp = requests.get(url, headers=HEADERS, timeout=20)
        resp.raise_for_status()
    except requests.RequestException as e:
        logger.warning(f'[TG] {username} fetch failed: {e}')
        return 0

    soup = BeautifulSoup(resp.text, 'lxml')
    posts = soup.select('div.tgme_widget_message')
    if not posts:
        logger.warning(f'[TG] {username}: no posts found in page (possible block)')
        return 0

    new_count = 0
    for post in posts:
        tel = post.select_one('div.tgme_widget_message_text')
        if not tel:
            continue
        text = tel.get_text(separator=' ', strip=True)
        if not text:
            continue
        # Keyword filter
        if not _has_keyword(text):
            continue
        time_el  = post.select_one('time')
        link_el  = post.select_one('a.tgme_widget_message_date')
        msg_date = time_el.get('datetime', '') if time_el else ''
        link     = link_el.get('href', '')     if link_el else ''
        # Need a unique link to deduplicate
        if not link:
            continue
        loc = find_location(text)
        added = db.add_message(
            channel        = username,
            text           = text,
            msg_date       = msg_date,
            link           = link,
            summary        = _summarize(text),
            location_name  = loc['name']   if loc else '',
            location_coords= loc['coords'] if loc else '',
            location_gmaps = loc['gmaps']  if loc else '',
            has_critical   = _is_critical(text),
        )
        if added:
            new_count += 1

    logger.info(f'[TG] {username}: {new_count} new messages saved')
    return new_count

# ── Website ───────────────────────────────────────────────────
# English keywords for website filtering (mirrors Arabic keywords in intent)
WEB_KEYWORDS = [
    # Military / strikes
    'strike', 'attack', 'bomb', 'shell', 'shelling', 'airstrike', 'air strike',
    'missile', 'rocket', 'drone', 'explosion', 'blast', 'fire',
    'invasion', 'offensive', 'assault', 'raid', 'operation',
    # Casualties
    'killed', 'dead', 'death', 'deaths', 'martyr', 'wounded', 'injured',
    'casualties', 'toll', 'victim', 'massacre', 'genocide',
    # Locations / conflict zones
    'gaza', 'rafah', 'jabalia', 'khan yunis', 'deir al-balah', 'nuseirat',
    'west bank', 'jenin', 'nablus', 'tulkarm', 'hebron', 'ramallah',
    'lebanon', 'lebanese', 'beirut', 'iran', 'iranian', 'hamas', 'hezbollah', 'idf', 'israel', 'israeli',
    'palestine', 'palestinian',
    # Humanitarian
    'displaced', 'displacement', 'refugee', 'evacuation', 'famine',
    'aid', 'humanitarian', 'ceasefire', 'hostage', 'prisoner', 'detain',
    # Diplomacy / politics
    'negotiation', 'deal', 'agreement', 'sanction', 'resolution',
    'crossing', 'blockade', 'siege',
    # Arabic words that may appear in English sites
    'استهداف', 'قصف', 'شهيد', 'شهداء', 'جرحى', 'إصابات', 'انتشال',
    'تحت الأنقاض', 'أشلاء', 'غارة', 'عاجل',
]

def _has_web_keyword(text):
    """Check if an English/Arabic website title is relevant.
    Checks hardcoded WEB_KEYWORDS AND user-configured DB keywords."""
    tl = text.lower()
    if any(k.lower() in tl for k in WEB_KEYWORDS):
        return True
    # Also check DB keywords (includes user-added + code-seeded Arabic terms)
    kws = db.get_all_keyword_words()
    return any(k.lower() in tl for k in kws)

def scrape_website(username, source_url):
    if not source_url:
        return 0
    try:
        resp = requests.get(source_url, headers=HEADERS, timeout=20)
        resp.raise_for_status()
    except requests.RequestException as e:
        logger.warning(f'[WEB] {username} {source_url} failed: {e}')
        return 0

    soup = BeautifulSoup(resp.text, 'lxml')
    seen  = set()
    items = []

    # Broad selector list covering most news site structures
    selectors = [
        # Standard semantic tags
        'article a', 'h1 a', 'h2 a', 'h3 a', 'h4 a',
        # Common class patterns
        '[class*="article"] a', '[class*="story"] a', '[class*="card"] a',
        '[class*="headline"] a', '[class*="title"] a', '[class*="teaser"] a',
        '[class*="post"] a', '[class*="entry"] a', '[class*="item"] a',
        '[class*="news"] a', '[class*="report"] a', '[class*="update"] a',
        # List-based layouts
        'li a', '.content a',
        # OCHA / UN site patterns
        '.views-row a', '.view-content a', '.field-content a',
    ]

    for sel in selectors:
        for a in soup.select(sel):
            title = a.get_text(separator=' ', strip=True)
            href  = a.get('href', '')
            if not title or not href:
                continue
            # Resolve relative URLs
            if not href.startswith('http'):
                href = urljoin(source_url, href)
            # Skip navigation, footer, social links, anchors
            if href == source_url or href.endswith(('#', 'javascript:void(0)')):
                continue
            skip_patterns = ['/tag/', '/category/', '/author/', '/page/', '/search',
                             'facebook.com', 'twitter.com', 'x.com', 'instagram.com',
                             'youtube.com', 'linkedin.com', 'mailto:', 'tel:']
            if any(p in href for p in skip_patterns):
                continue
            if len(title) >= 12 and href not in seen:
                seen.add(href)
                items.append((href, title))

    new_count = 0
    for href, title in items[:60]:   # increased from 30 to 60
        # Use web-aware keyword filter (supports English + Arabic)
        if not _has_web_keyword(title):
            continue
        loc = find_location(title)
        added = db.add_message(
            channel        = username,
            text           = title,
            msg_date       = '',
            link           = href,
            summary        = _summarize(title),
            location_name  = loc['name']   if loc else '',
            location_coords= loc['coords'] if loc else '',
            location_gmaps = loc['gmaps']  if loc else '',
            has_critical   = _is_critical(title),
        )
        if added:
            new_count += 1

    logger.info(f'[WEB] {username}: {new_count} new items saved (scanned {len(items)} links)')
    return new_count

# ── Public API ────────────────────────────────────────────────
def scrape_channel(username):
    channels = db.get_channels()
    ch = next((c for c in channels if c['username'] == username), None)
    if ch and ch.get('source_type') == 'website':
        return scrape_website(username, ch.get('source_url', ''))
    return scrape_telegram(username)

def scrape_all():
    results = {}
    for ch in db.get_channels():
        if not ch.get('active', 1):
            continue
        username = ch['username']
        if ch.get('source_type') == 'website':
            results[username] = scrape_website(username, ch.get('source_url', ''))
        else:
            results[username] = scrape_telegram(username)
    return results
