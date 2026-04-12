package diary.ossupdownloadphoto.util;

import diary.ossupdownloadphoto.config.snowflakeconfig.SnowflakeIdConfig;

public class MyUtils {
    public static long getPrimaryKey() {
        // 使用雪花算法
        return SnowflakeIdConfig.nextId();
    }
}
