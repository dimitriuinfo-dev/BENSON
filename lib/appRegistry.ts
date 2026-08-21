export type AppCategory =
  | 'video' | 'music' | 'social' | 'food' | 'transport' | 'shopping' | 'travel'
  | 'messaging' | 'utility';

export type AppEntry = {
  id: string;
  name: string;
  category: AppCategory;
  scheme?: string;         // tried first via Linking.canOpenURL
  searchScheme?: string;   // used instead of `scheme` when a query is present — `${searchScheme}${encodeURIComponent(query)}`
  packageName?: string;    // Android package — best-effort fallback via 'android-app://<package>'
  fallbackUrl: string;     // web URL, final fallback — '' for system apps with no sensible web page
};

// Google Maps/Waze navigation stays a special case in appLauncherAgent.ts's NAV_PATTERN branch
// (it also produces the map card), so it isn't duplicated here.
export const APP_REGISTRY: AppEntry[] = [
  { id: 'netflix',    name: 'Netflix',       category: 'video',   scheme: 'netflix://', searchScheme: 'netflix://search?q=', packageName: 'com.netflix.mediaclient', fallbackUrl: 'https://www.netflix.com/' },
  { id: 'primevideo', name: 'Amazon Prime',  category: 'video',   packageName: 'com.amazon.avod.thirdpartyclient', fallbackUrl: 'https://www.primevideo.com/' },
  { id: 'disneyplus', name: 'Disney+',       category: 'video',   scheme: 'disneyplus://', packageName: 'com.disney.disneyplus', fallbackUrl: 'https://www.disneyplus.com/' },
  { id: 'hbomax',     name: 'HBO Max',       category: 'video',   scheme: 'max://', packageName: 'com.wbd.stream', fallbackUrl: 'https://play.max.com/' },
  { id: 'youtube',    name: 'YouTube',       category: 'video',   scheme: 'vnd.youtube://', searchScheme: 'vnd.youtube://results?search_query=', packageName: 'com.google.android.youtube', fallbackUrl: 'https://www.youtube.com/' },

  { id: 'spotify',    name: 'Spotify',       category: 'music',   scheme: 'spotify://', searchScheme: 'spotify:search:', packageName: 'com.spotify.music', fallbackUrl: 'https://open.spotify.com/' },

  { id: 'whatsapp',   name: 'WhatsApp',      category: 'messaging', scheme: 'whatsapp://', packageName: 'com.whatsapp', fallbackUrl: 'https://web.whatsapp.com/' },
  { id: 'telegram',   name: 'Telegram',      category: 'messaging', scheme: 'tg://', packageName: 'org.telegram.messenger', fallbackUrl: 'https://telegram.org/' },
  { id: 'gmail',      name: 'Gmail',         category: 'messaging', scheme: 'googlegmail://', packageName: 'com.google.android.gm', fallbackUrl: 'https://mail.google.com/' },

  { id: 'waze',       name: 'Waze',          category: 'transport', scheme: 'waze://', searchScheme: 'waze://?q=', packageName: 'com.waze', fallbackUrl: 'https://waze.com/ul' },
  { id: 'uber',       name: 'Uber',          category: 'transport', scheme: 'uber://', packageName: 'com.ubercab', fallbackUrl: 'https://m.uber.com/' },
  { id: 'bolt',       name: 'Bolt',          category: 'transport', scheme: 'bolt://', packageName: 'ee.mtakso.client', fallbackUrl: 'https://bolt.eu/' },
  { id: 'blablacar',  name: 'BlaBlaCar',     category: 'transport', packageName: 'com.comuto', fallbackUrl: 'https://www.blablacar.com/' },

  { id: 'thefork',    name: 'TheFork',       category: 'food',    scheme: 'thefork://', searchScheme: 'thefork://search?q=', packageName: 'com.lafourchette.lafourchette', fallbackUrl: 'https://www.thefork.com/' },
  { id: 'lieferando', name: 'Lieferando',    category: 'food',    packageName: 'de.lieferando.android', fallbackUrl: 'https://www.lieferando.de/' },
  { id: 'glovo',      name: 'Glovo',         category: 'food',    scheme: 'glovoapp://', packageName: 'com.glovoapp23', fallbackUrl: 'https://glovoapp.com/' },

  { id: 'booking',    name: 'Booking.com',   category: 'travel',  scheme: 'booking://', searchScheme: 'booking://search?ss=', packageName: 'com.booking', fallbackUrl: 'https://www.booking.com/' },
  { id: 'airbnb',     name: 'Airbnb',        category: 'travel',  scheme: 'airbnb://', packageName: 'com.airbnb.android', fallbackUrl: 'https://www.airbnb.com/' },

  { id: 'instagram',  name: 'Instagram',     category: 'social',  scheme: 'instagram://', packageName: 'com.instagram.android', fallbackUrl: 'https://www.instagram.com/' },
  { id: 'facebook',   name: 'Facebook',      category: 'social',  scheme: 'fb://', packageName: 'com.facebook.katana', fallbackUrl: 'https://www.facebook.com/' },
  { id: 'tiktok',     name: 'TikTok',        category: 'social',  scheme: 'tiktok://', packageName: 'com.zhiliaoapp.musically', fallbackUrl: 'https://www.tiktok.com/' },

  { id: 'ebay',       name: 'eBay',          category: 'shopping', scheme: 'ebay://', packageName: 'com.ebay.mobile', fallbackUrl: 'https://www.ebay.com/' },

  { id: 'easypark',   name: 'EasyPark',      category: 'utility', scheme: 'easypark://', searchScheme: 'easypark://search?q=', packageName: 'com.easypark.android', fallbackUrl: 'https://easypark.com/' },
  { id: 'chrome',     name: 'Chrome',        category: 'utility', scheme: 'googlechrome://', packageName: 'com.android.chrome', fallbackUrl: 'https://www.google.com/' },
  { id: 'settings',   name: 'Settings',      category: 'utility', packageName: 'com.android.settings', fallbackUrl: '' },
  { id: 'calculator', name: 'Calculator',    category: 'utility', packageName: 'com.google.android.calculator', fallbackUrl: '' },
  { id: 'camera',     name: 'Camera',        category: 'utility', packageName: 'com.android.camera2', fallbackUrl: '' },
];
