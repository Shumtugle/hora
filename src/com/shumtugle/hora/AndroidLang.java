package com.shumtugle.hora;

import android.content.res.Resources;

import java.util.HashMap;
import java.util.Map;

/** Language data for the normalizer, read from app resources by name. */
final class AndroidLang implements Normalizer.Lang {
    private static final String PACKAGE = "com.shumtugle.hora";

    private final Resources res;
    private final Map<String, Integer> plurals = new HashMap<String, Integer>();

    AndroidLang(Resources res) {
        this.res = res;
    }

    @Override
    public String str(String name) {
        int id = res.getIdentifier(name, "string", PACKAGE);
        return id == 0 ? "" : res.getString(id);
    }

    @Override
    public String[] arr(String name) {
        int id = res.getIdentifier(name, "array", PACKAGE);
        return id == 0 ? new String[0] : res.getStringArray(id);
    }

    @Override
    public String plural(String name, long count) {
        Integer id = plurals.get(name);
        if (id == null) {
            id = res.getIdentifier(name, "plurals", PACKAGE);
            plurals.put(name, id);
        }
        if (id == 0) {
            return "";
        }
        int n = (int) Math.min(Integer.MAX_VALUE, count % 1000000);
        return res.getQuantityString(id, n);
    }
}
