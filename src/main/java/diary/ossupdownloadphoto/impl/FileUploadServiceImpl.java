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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    public void uploadAndSendMsgAsync(Map<String, Object> result, List<MultipartFile> files) {
        // 获取photoId列表
        Object dataObj = result.get("data");
        if (dataObj == null) {
            log.error("数据库插入失败，result: {}", result);
            throw new RuntimeException("数据插入DB失败");
        }

        List<Long> photoIds;
        if (dataObj instanceof Long) {
            // 兼容单个ID的情况
            photoIds = List.of((Long) dataObj);
        } else if (dataObj instanceof List) {
            photoIds = (List<Long>) dataObj;
        } else {
            log.error("未知的数据类型: {}", dataObj.getClass());
            throw new RuntimeException("数据格式错误");
        }

        if (photoIds.size() != files.size()) {
            log.warn("photoId数量({})与文件数量({})不匹配", photoIds.size(), files.size());
        }

        int successCount = 0;
        int failCount = 0;
        List<String> failedFiles = new ArrayList<>();

        // 遍历文件列表，逐个上传
        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            Long photoId = (i < photoIds.size()) ? photoIds.get(i) : null;

            if (photoId == null) {
                log.warn("文件 {} 没有对应的photoId，跳过上传", file.getOriginalFilename());
                failCount++;
                failedFiles.add(file.getOriginalFilename() + ": 缺少photoId");
                continue;
            }

            try {
                // 生成唯一文件名，避免同名覆盖
                String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();

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
                log.info("OSS 上传成功，消息已发送，photoId: {}, fileName: {}, correlationId: {}",
                        photoId, file.getOriginalFilename(), correlationId);
                successCount++;
            } catch (IOException e) {
                log.error("OSS 上传失败，photoId: {}, fileName: {}", photoId, file.getOriginalFilename(), e);
                failCount++;
                failedFiles.add(file.getOriginalFilename() + ": " + e.getMessage());
                // 可在此发送上传失败消息到另一个队列
            } catch (Exception e) {
                log.error("处理文件异常，photoId: {}, fileName: {}", photoId, file.getOriginalFilename(), e);
                failCount++;
                failedFiles.add(file.getOriginalFilename() + ": " + e.getMessage());
            }
        }

        log.info("批量上传完成，成功: {}, 失败: {}", successCount, failCount);
        if (!failedFiles.isEmpty()) {
            log.warn("失败文件列表: {}", failedFiles);
        }
    }
}
