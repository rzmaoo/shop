package com.rzmao.shop.text;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;

import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** 在服务端解析文本，确保未安装本模组的客户端也能看到可读中文。 */
public final class ShopText {
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final Map<String, String> CHINESE = load();

    private ShopText() {}

    public static String get(String key, Object... arguments) {
        String template = CHINESE.getOrDefault(key, key);
        if (arguments.length == 0) return template;
        try {
            return String.format(Locale.ROOT, template, arguments);
        } catch (RuntimeException ex) {
            return template;
        }
    }

    public static MutableComponent text(String key, Object... arguments) {
        return Component.literal(get(key, arguments));
    }

    public static String auditAction(String action) {
        return get("shop.audit.action." + action);
    }

    public static String auditOutcome(String outcome) {
        return get("shop.audit.outcome." + outcome);
    }

    public static String dimension(String id) {
        if (id == null) return "";
        return CHINESE.getOrDefault("shop.dimension." + id, id);
    }

    private static Map<String, String> load() {
        try (var stream = ShopText.class.getResourceAsStream("/assets/shop/lang/zh_cn.json")) {
            if (stream == null) throw new IllegalStateException("Missing zh_cn language file");
            Map<String, String> values = new Gson().fromJson(
                    new InputStreamReader(stream, StandardCharsets.UTF_8), MAP_TYPE);
            return Map.copyOf(values);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
