package diary.ossupdownloadphoto.config.snowflakeconfig;

public class SnowflakeIdConfig {
    private static long lastTimestamp = -1L;
    private static long sequence = 0L;
    private static final long START_TIMESTAMP = 1704038400000L; // 2024-01-01

    public static synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            throw new RuntimeException("时钟回拨了");
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & 4095; // 12位序列号，最大值4095
            if (sequence == 0) {
                while (timestamp <= lastTimestamp) {
                    timestamp = System.currentTimeMillis();
                }
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - START_TIMESTAMP) << 22)  // 时间戳左移22位
                | (sequence);                          // 序列号
    }
}
