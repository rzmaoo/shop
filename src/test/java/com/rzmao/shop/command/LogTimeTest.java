package com.rzmao.shop.command;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogTimeTest {
    @Test
    void commandTimeUsesConfiguredTimeZone() {
        Instant shanghai = LogTime.parseBound("2026-06-0100:00:00", ZoneId.of("Asia/Shanghai"));
        Instant utc = LogTime.parseBound("2026-06-0100:00:00", ZoneId.of("UTC"));

        assertThat(shanghai).isEqualTo(Instant.parse("2026-05-31T16:00:00Z"));
        assertThat(utc).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void dateOnlyUsesConfiguredTimeZoneStartOfDay() {
        Instant shanghai = LogTime.parseBound("2026-06-01", ZoneId.of("Asia/Shanghai"));
        Instant utc = LogTime.parseBound("2026-06-01", ZoneId.of("UTC"));

        assertThat(shanghai).isEqualTo(Instant.parse("2026-05-31T16:00:00Z"));
        assertThat(utc).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void commandFormattingUsesFixedLocalTimeFormat() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Instant start = LogTime.parseBound("2026-06-0101:02:03", zone);

        assertThat(LogTime.formatForCommand(start, zone)).isEqualTo("2026-06-0101:02:03");
    }

    @Test
    void oldOffsetFormatIsRejected() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");

        assertThatThrownBy(() -> LogTime.parseBound("2026-06-01T00:00:00+08:00", zone))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
