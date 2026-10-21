package com.akshaykadam.pixelboard.extension.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

public final class GboardSettingsText {
    private static final String LANGUAGE_ENGLISH = "en";

    private GboardSettingsText() {
    }

    public static String get(Context context, int resId) {
        String stableText = resolveStableText(context, resId);
        if (stableText != null) {
            return stableText;
        }
        return requireContext(context).getString(resId);
    }

    public static String format(Context context, int resId, Object... args) {
        String stableText = resolveStableText(context, resId, args);
        if (stableText != null) {
            return stableText;
        }
        return requireContext(context).getString(resId, args);
    }

    public static String quantity(Context context, int resId, int quantity) {
        String stableQuantity = resolveStableQuantity(context, resId, quantity);
        if (stableQuantity != null) {
            return stableQuantity;
        }
        return requireContext(context).getResources().getQuantityString(resId, quantity, quantity);
    }

    /**
     * The locale that text for {@code resId} is shown in, for formatting numbers and dates next
     * to it: English when the string has no translation for the context language.
     */
    public static Locale textLocale(Context context, int resId) {
        Locale locale = extractLocale(context);
        String languageTag = normalizeLanguageTag(locale != null ? locale.getLanguage() : null);
        if (!LANGUAGE_ENGLISH.equals(languageTag)) {
            String text = GboardSettingsTextCatalog.template(resId, languageTag);
            if (text == null
                    || text.equals(GboardSettingsTextCatalog.template(resId, LANGUAGE_ENGLISH))) {
                languageTag = LANGUAGE_ENGLISH;
                locale = Locale.ENGLISH;
            }
        }
        // The settings context may carry only a language; the system locale adds the region
        // (date order, digits) when it is the same language.
        Locale systemLocale = systemLocale();
        if (systemLocale != null
                && languageTag.equals(normalizeLanguageTag(systemLocale.getLanguage()))) {
            return systemLocale;
        }
        return locale != null ? locale : Locale.ENGLISH;
    }

    static String resolveStableTextForTesting(int resId, String languageTag, Object... args) {
        return resolveStableText(resId, normalizeLanguageTag(languageTag), args);
    }

    static String resolveStableQuantityForTesting(int resId, String languageTag, int quantity) {
        return resolveStableQuantity(resId, normalizeLanguageTag(languageTag), quantity);
    }

    private static String resolveStableText(Context context, int resId, Object... args) {
        return resolveStableText(resId, resolveLanguageTag(context), args);
    }

    private static String resolveStableText(int resId, String languageTag, Object... args) {
        return formatTemplate(
                languageTag,
                GboardSettingsTextCatalog.template(resId, languageTag),
                args);
    }

    private static String resolveStableQuantity(Context context, int resId, int quantity) {
        return resolveStableQuantity(resId, resolveLanguageTag(context), quantity);
    }

    private static String resolveStableQuantity(int resId, String languageTag, int quantity) {
        return formatTemplate(
                languageTag,
                GboardSettingsTextCatalog.quantityTemplate(resId, languageTag, quantity),
                quantity);
    }

    private static String formatTemplate(String languageTag, String template, Object... args) {
        if (template == null) {
            return null;
        }
        if (args == null || args.length == 0) {
            return template;
        }
        return String.format(Locale.US, template, args);
    }

    private static String resolveLanguageTag(Context context) {
        Locale locale = extractLocale(context);
        return normalizeLanguageTag(locale != null ? locale.getLanguage() : null);
    }

    private static Locale extractLocale(Context context) {
        if (context == null) {
            return Locale.getDefault();
        }
        Configuration configuration;
        try {
            if (context.getResources() == null) {
                return Locale.getDefault();
            }
            configuration = context.getResources().getConfiguration();
        } catch (Throwable ignored) {
            return Locale.getDefault();
        }
        if (configuration == null) {
            return Locale.getDefault();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && configuration.getLocales() != null
                && !configuration.getLocales().isEmpty()) {
            Locale locale = configuration.getLocales().get(0);
            return locale != null ? locale : Locale.getDefault();
        }
        Locale locale = configuration.locale;
        return locale != null ? locale : Locale.getDefault();
    }

    private static Locale systemLocale() {
        try {
            Configuration configuration = Resources.getSystem().getConfiguration();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    && configuration.getLocales() != null
                    && !configuration.getLocales().isEmpty()) {
                return configuration.getLocales().get(0);
            }
            return configuration.locale;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String normalizeLanguageTag(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) {
            return LANGUAGE_ENGLISH;
        }
        String lower = languageTag.toLowerCase(Locale.US).trim();
        if ("id".equals(lower)) {
            return "in";
        }
        int dashIndex = lower.indexOf('-');
        if (dashIndex > 0) {
            lower = lower.substring(0, dashIndex);
        }
        int underscoreIndex = lower.indexOf('_');
        if (underscoreIndex > 0) {
            lower = lower.substring(0, underscoreIndex);
        }
        return lower;
    }

    private static Context requireContext(Context context) {
        if (context == null) {
            throw new IllegalStateException("Context required for Gboard settings text lookup.");
        }
        return context;
    }
}
