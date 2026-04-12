package diary.ossupdownloadphoto.impl;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import diary.ossupdownloadphoto.config.mqconfig.RabbitMqConfig;
import diary.ossupdownloadphoto.po.OssUploadSuccessMsg;
import diary.ossupdownloadphoto.service.FileUploadService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class FileUploadServiceImpl implements FileUploadService {

    @Autowired
    private OSS ossClient;

    @Value("${aliyun.oss.bucket-name}")
    private String bucketName;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Async("ossUploadExecutor")
    @Override
    public void uploadAndSendMsgAsync(Map<String, Object> result, MultipartFile file) {
        if (result.get("data").getClass() != Long.class) {
            throw new RuntimeException("数据插入DB失败");
        }
        Long photoId = (Long) result.get("data");
        // 生成唯一文件名，避免同名覆盖
        String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
        try {
            // 1. 上传文件到 OSS（V4 客户端会自动使用 V4 签名）
            ossClient.putObject(bucketName, fileName, file.getInputStream());
            // 2. 生成 V4 预签名 URL，有效期设为 1 小时
            Date expiration = new Date(System.currentTimeMillis() + 3600 * 1000);
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, fileName);
            request.setExpiration(expiration);
            request.setMethod(HttpMethod.GET);
            String ossUrl = ossClient.generatePresignedUrl(request).toString();

            // 构建消息对象
            OssUploadSuccessMsg msg = new OssUploadSuccessMsg(
                    photoId, ossUrl, file.getOriginalFilename(), System.currentTimeMillis()
            );
            // 发送消息到rabbitmq
            // 创建关联ID
            String correlationId = "UPLOAD" + photoId + System.currentTimeMillis();
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.OSS_UPLOAD_EXCHANGE,
                    RabbitMqConfig.OSS_UPLOAD_ROUTING_KEY,
                    msg,
                    new CorrelationData(correlationId)
            );
            log.info("OSS 上传成功，消息已发送，photoId: {}, correlationId: {}", photoId, correlationId);
        } catch (IOException e) {
            log.error("OSS 上传失败，photoId: {}", photoId, e);
            // 可在此发送上传失败消息到另一个队列
            throw new RuntimeException("OSS 上传失败: " + e.getMessage(), e);
        }
    }
}
