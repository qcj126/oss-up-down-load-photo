package diary.ossupdownloadphoto.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import diary.ossupdownloadphoto.config.snowflakeconfig.SnowflakeIdConfig;
import org.springframework.web.multipart.MultipartFile;

public class MyUtils {
    // 创建ObjectMapper实例，用于JSON转换
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static long getPrimaryKey() {
        // 使用雪花算法
        return SnowflakeIdConfig.nextId();
    }
    
    public static boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    public static boolean isObjEmpty(Object obj) {
        return obj == null;
    }

    public static boolean isFileEmpty(MultipartFile file) {
        return file == null || file.isEmpty();
    }

    /**
     * 对象转JSON字符串
     *
     * @param obj 待转换的对象
     * @return JSON字符串，如果转换失败返回null
     */
    public static String objectToJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * JSON字符串转对象
     *
     * @param json JSON字符串
     * @param clazz 目标对象类型
     * @return 转换后的对象，如果转换失败返回null
     */
    public static <T> T jsonToObject(String json, Class<T> clazz) {
        if (isEmpty(json) || clazz == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 对象转字符串（调用toString方法，处理null情况）
     *
     * @param obj 待转换的对象
     * @return 对象的字符串表示，如果对象为null返回""
     */
    public static String objectToString(Object obj) {
        if (obj == null) {
            return "";
        }
        return obj.toString();
    }
}