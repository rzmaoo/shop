package com.rzmao.shop.command;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

final class LogTime {
    static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XXX");
    static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    static final DateTimeFormatter COMMAND_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-ddHH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);

    private LogTime() {}

    static Instant parseBound(String raw, ZoneId zone) {
        try {
            return LocalDate.parse(raw, DATE_FORMAT).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // 日期格式不匹配时，继续尝试精确时间格式。
        }
        try {
            return LocalDateTime.parse(raw, COMMAND_FORMAT).atZone(zone).toInstant();
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("时间格式错误，例如：2026-06-01 或 2026-06-0100:00:00");
        }
    }

    static String formatForCommand(Instant instant, ZoneId zone) {
        return COMMAND_FORMAT.format(LocalDateTime.ofInstant(instant, zone));
    }
}
