package diary.ossupdownloadphoto.util;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.Date;

@Slf4j
@Component
public class MyOssUtils {
    @Value("${aliyun.oss.bucket-name}")
    private String bucketName;

    @Resource
    private OSS ossClient;

    /**
     * 生成OSS签名URL
     * @param ossUrl OSS URL或object key
     * @return 签名URL
     */
    public String generateSignedUrl(String ossUrl) {
        try {
            // 如果传入的是完整的OSS URL，提取object key
            String objectKey = extractObjectKeyFromUrl(ossUrl);

            // 生成签名URL，有效期5分钟
            Date expiration = new Date(System.currentTimeMillis() + 5 * 60 * 1000);
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, objectKey);
            request.setExpiration(expiration);
            request.setMethod(HttpMethod.GET);

            String signedUrl = ossClient.generatePresignedUrl(request).toString();
            log.debug("生成签名URL成功，objectKey: {}", objectKey);
            return signedUrl;
        } catch (Exception e) {
            log.error("生成签名URL失败, ossUrl: {}", ossUrl, e);
            throw new RuntimeException("生成签名URL失败: " + e.getMessage(), e);
        }
    }
    /**
     * 从OSS URL中提取object key
     */
    public String extractObjectKeyFromUrl(String ossUrl) {
        // 如果已经是object key（不包含http），直接返回
        if (!ossUrl.startsWith("http://") && !ossUrl.startsWith("https://")) {
            return ossUrl;
        }

        try {
            // 从URL中提取object key
            // 格式: https://bucket-name.endpoint/object-key
            URL url = new URL(ossUrl);
            String path = url.getPath();
            // 移除开头的 /
            if (path.startsWith("/")) {
                path = path.substring(1);
            }
            return path;
        } catch (Exception e) {
            log.error("从URL提取object key失败: {}", ossUrl, e);
            // 如果提取失败，尝试直接返回
            return ossUrl;
        }
    }
}
