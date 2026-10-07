package cn.tihaishitu.learning;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 练习业务日边界：统一 {@code Asia/Shanghai}。
 *
 * <p>长期规则：RANDOM 的“每天第一次”与“同一题当天最多出一次”都按这个业务日判断，
 * 不使用 UTC 自然日，也不依赖机器默认时区。日界按 Instant 计算后交给 JDBC 比较，
 * 因此在 MySQL 与 H2 上都只比较同一时间轴上的先后关系。</p>
 */
public final class PracticeBusinessDay {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private PracticeBusinessDay() {}

    /** 包含该时刻的业务日起点（当日 00:00 Asia/Shanghai）。 */
    public static Instant startOfDay(Instant at) {
        return at.atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant();
    }

    /** 业务日终点：下一日 00:00 Asia/Shanghai（开区间上界）。 */
    public static Instant endOfDay(Instant at) {
        return startOfDay(at).plus(Duration.ofDays(1));
    }
}
