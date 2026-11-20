import { useSyncExternalStore } from "react";
import german from "./locales/de.json";
import spanish from "./locales/es.json";
import french from "./locales/fr.json";
import japanese from "./locales/ja.json";
import korean from "./locales/ko.json";
import portuguese from "./locales/pt-BR.json";
import simplifiedChinese from "./locales/zh-Hans.json";
import traditionalChinese from "./locales/zh-Hant.json";

export const languages = {
  en: "English",
  de: "Deutsch",
  es: "Español",
  fr: "Français",
  ja: "日本語",
  ko: "한국어",
  "pt-BR": "Português (Brasil)",
  "zh-Hans": "简体中文",
  "zh-Hant": "繁體中文",
} as const;

type Language = keyof typeof languages;
type LanguagePreference = Language | "system";
const catalogs: Record<Language, Readonly<Record<string, string>>> = {
  en: {}, de: german, es: spanish, fr: french, ja: japanese, ko: korean,
  "pt-BR": portuguese, "zh-Hans": simplifiedChinese, "zh-Hant": traditionalChinese,
};
const preferenceKey = "hyperlpa-language";
const listeners = new Set<() => void>();

export function matchLanguage(preferences: readonly string[]): Language {
  for (const preference of preferences) {
    try {
      const locale = new Intl.Locale(preference);
      if (locale.language === "zh") return locale.maximize().script === "Hant" ? "zh-Hant" : "zh-Hans";
      if (locale.language === "pt") return "pt-BR";
      if (Object.hasOwn(languages, locale.language)) return locale.language as Language;
    } catch {
      continue;
    }
  }
  return "en";
}

function storedPreference(): LanguagePreference {
  try {
    const stored = localStorage.getItem(preferenceKey);
    if (stored && Object.hasOwn(languages, stored)) return stored as Language;
  } catch {
    return "system";
  }
  return "system";
}

let preference = storedPreference();
let language = resolveLanguage();

function resolveLanguage(): Language {
  return preference === "system" ? matchLanguage(globalThis.navigator?.languages ?? []) : preference;
}

function updateLanguage() {
  language = resolveLanguage();
  if (typeof document !== "undefined") document.documentElement.lang = language;
  listeners.forEach(listener => listener());
}

updateLanguage();
if (typeof window !== "undefined") window.addEventListener("languagechange", updateLanguage);

export function setLanguage(value: string) {
  if (value !== "system" && !Object.hasOwn(languages, value)) return;
  preference = value as LanguagePreference;
  updateLanguage();
  try {
    if (preference === "system") localStorage.removeItem(preferenceKey);
    else localStorage.setItem(preferenceKey, preference);
  } catch {
    return;
  }
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export function useLanguage() {
  return useSyncExternalStore(subscribe, () => `${preference}/${language}`);
}

export function getLanguage() { return language; }
export function getLanguagePreference() { return preference; }

export function t(message: string, values?: Record<string, string | number>): string {
  const catalog = catalogs[language];
  const text = Object.hasOwn(catalog, message) ? catalog[message] : message;
  return values ? text.replace(/\{([a-zA-Z][a-zA-Z0-9]*)\}/g, (placeholder, name: string) => (
    Object.hasOwn(values, name) ? String(values[name]) : placeholder
  )) : text;
}
