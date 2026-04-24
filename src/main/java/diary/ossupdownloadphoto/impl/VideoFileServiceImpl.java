package diary.ossupdownloadphoto.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.*;
import diary.ossupdownloadphoto.config.consts.FileTypeConst;
import diary.ossupdownloadphoto.config.consts.PhotoStatusConst;
import diary.ossupdownloadphoto.config.consts.PhotoTypeConst;
import diary.ossupdownloadphoto.config.mqconfig.RabbitMqConfig;
import diary.ossupdownloadphoto.mapper.PhotoMapper;
import diary.ossupdownloadphoto.po.OssUploadSuccessMsg;
import diary.ossupdownloadphoto.service.RedisService;
import diary.ossupdownloadphoto.service.VideoFileService;
import diary.ossupdownloadphoto.util.MyOssUtils;
import diary.ossupdownloadphoto.util.MyUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class VideoFileServiceImpl implements VideoFileService {
    @Resource
    private PhotoMapper photoMapper;

    @Resource
    private RedisService redisService;

    @Resource
    private OSS ossClient;

    @Value("${aliyun.oss.bucket-name}")
    private String bucketName;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private MyOssUtils  myOssUtils;

    // 分片大小：10MB
    private static final long PART_SIZE = 10 * 1024 * 1024L;
    
    // 大文件阈值：100MB
    private static final long LARGE_FILE_THRESHOLD = 100 * 1024 * 1024L;

    @Override
    public Map<String, Object> addVideoToDb(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Map.of("code", 500, "message", "文件为空", "data", "null");
        }

        try {
            // 验证是否为视频类型
            String contentType = file.getContentType();
            if (contentType == null || !contentType.startsWith(FileTypeConst.CONTENT_TYPE_VIDEO_PREFIX)) {
                // 进一步检查文件扩展名
                String originalFilename = file.getOriginalFilename();
                if (!isVideoFileByExtension(originalFilename)) {
                    return Map.of("code", 500, "message", "文件不是视频类型", "data", "null");
                }
            }

            long id = MyUtils.getPrimaryKey();
            String videoType = PhotoTypeConst.VIDEO_TYPE;
            String videoName = file.getOriginalFilename();

            // 查看同一视频类别下是否有相同名称的视频
            Integer isExist = photoMapper.selectPhotoByTypeAndName(videoType, videoName);
            if (isExist > 0) {
                return Map.of("code", 500, "message", "视频已存在", "data", "null");
            }

            long videoSize = file.getSize();
            String videoFormat = contentType != null ? contentType : getFileExtension(videoName);
            String videoStatus = PhotoStatusConst.PHOTO_STATUS_PROCESSING;

            // 获取当前Redis中的视频数量，作为sortOrder
            long currentCount = redisService.getPhotoCount();

            // 插入数据库
            Integer count = photoMapper.addPhotoToDb(id, videoType, videoName, videoSize, videoFormat, currentCount + 1, videoStatus);
            if (count != null && count > 0) {
                // 更新Redis计数
                redisService.updatePhotoCount(currentCount + 1);
                log.info("视频信息插入数据库成功，videoId: {}, videoName: {}", id, videoName);
                return Map.of("code", 200, "message", "视频信息保存成功", "data", id);
            } else {
                log.error("视频信息插入数据库失败，videoName: {}", videoName);
                return Map.of("code", 500, "message", "视频信息保存失败", "data", "null");
            }
        } catch (Exception e) {
            log.error("处理视频文件时发生异常，文件名: {}", file.getOriginalFilename(), e);
            return Map.of("code", 500, "message", "处理视频文件异常: " + e.getMessage(), "data", "null");
        }
    }

    @Async("ossUploadExecutor")
    @Override
    public void uploadAndSendMsgAsync(Map<String, Object> result, MultipartFile file) {
        // 获取videoId
        Object dataObj = result.get("data");
        if (dataObj == null) {
            log.error("数据库插入失败，result: {}", result);
            throw new RuntimeException("数据插入DB失败");
        }

        Long videoId;
        if (dataObj instanceof Long) {
            videoId = (Long) dataObj;
        } else {
            log.error("未知的数据类型: {}", dataObj.getClass());
            throw new RuntimeException("数据格式错误");
        }

        try {
            // 生成唯一文件名，避免同名覆盖
            String fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();

            String ossUrl;
            // 根据文件大小选择上传方式
            if (file.getSize() > LARGE_FILE_THRESHOLD) {
                // 大文件使用分片上传
                ossUrl = multipartUpload(file, fileName);
            } else {
                // 小文件使用普通上传
                ossUrl = simpleUpload(file, fileName);
            }

            // 构建消息对象
            OssUploadSuccessMsg msg = new OssUploadSuccessMsg(
                    videoId, ossUrl, file.getOriginalFilename(), System.currentTimeMillis()
            );

            // 发送消息到rabbitmq
            String correlationId = "VIDEO_UPLOAD" + videoId + System.currentTimeMillis();
            rabbitTemplate.convertAndSend(
                    RabbitMqConfig.OSS_UPLOAD_EXCHANGE,
                    RabbitMqConfig.OSS_UPLOAD_ROUTING_KEY,
                    msg,
                    new CorrelationData(correlationId)
            );
            log.info("视频OSS上传成功，消息已发送，videoId: {}, fileName: {}, correlationId: {}",
                    videoId, file.getOriginalFilename(), correlationId);
        } catch (IOException e) {
            log.error("视频OSS上传失败，videoId: {}, fileName: {}", videoId, file.getOriginalFilename(), e);
            // 可在此发送上传失败消息到另一个队列
        } catch (Exception e) {
            log.error("处理视频文件异常，videoId: {}, fileName: {}", videoId, file.getOriginalFilename(), e);
        }
    }

    /**
     * 简单上传（适用于小文件）
     */
    private String simpleUpload(MultipartFile file, String fileName) throws IOException {
        // 上传文件到OSS
        ossClient.putObject(bucketName, fileName, file.getInputStream());

        // 生成预签名URL，有效期1小时
        Date expiration = new Date(System.currentTimeMillis() + 3600 * 1000);
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucketName, fileName);
        request.setExpiration(expiration);
        request.setMethod(com.aliyun.oss.HttpMethod.GET);
        return ossClient.generatePresignedUrl(request).toString();
    }

    /**
     * 分片上传（适用于大文件，支持断点续传）
     */
    private String multipartUpload(MultipartFile file, String fileName) throws IOException {
        String uploadId = null;
        try {
            // 1. 初始化分片上传
            InitiateMultipartUploadRequest initiateRequest = new InitiateMultipartUploadRequest(bucketName, fileName);
            InitiateMultipartUploadResult initiateResult = ossClient.initiateMultipartUpload(initiateRequest);
            uploadId = initiateResult.getUploadId();

            // 2. 计算分片数量
            long contentLength = file.getSize();
            int partCount = (int) (contentLength / PART_SIZE);
            if (contentLength % PART_SIZE != 0) {
                partCount++;
            }

            // 3. 上传分片
            List<PartETag> partETags = new ArrayList<>();
            try (InputStream inputStream = file.getInputStream()) {
                for (int i = 0; i < partCount; i++) {
                    // 跳过已上传的分片
                    long startPos = i * PART_SIZE;
                    long curPartSize = (i + 1 == partCount) ? (contentLength - startPos) : PART_SIZE;

                    // 创建分片上传请求
                    UploadPartRequest uploadPartRequest = new UploadPartRequest();
                    uploadPartRequest.setBucketName(bucketName);
                    uploadPartRequest.setKey(fileName);
                    uploadPartRequest.setUploadId(uploadId);
                    uploadPartRequest.setInputStream(inputStream);
                    uploadPartRequest.setPartSize(curPartSize);
                    uploadPartRequest.setPartNumber(i + 1);

                    // 上传分片
                    UploadPartResult uploadPartResult = ossClient.uploadPart(uploadPartRequest);
                    partETags.add(uploadPartResult.getPartETag());

                    log.debug("视频分片上传成功，fileName: {}, partNumber: {}/{}", fileName, i + 1, partCount);
                }
            }

            // 4. 完成分片上传
            CompleteMultipartUploadRequest completeRequest = new CompleteMultipartUploadRequest(
                    bucketName, fileName, uploadId, partETags);
            ossClient.completeMultipartUpload(completeRequest);

            // 5. 生成预签名URL
            return myOssUtils.getSignedUrlByFileName(fileName);
        } catch (Exception e) {
            // 如果上传失败，取消分片上传
            if (uploadId != null) {
                try {
                    AbortMultipartUploadRequest abortRequest = new AbortMultipartUploadRequest(
                            bucketName, fileName, uploadId);
                    ossClient.abortMultipartUpload(abortRequest);
                    log.warn("取消分片上传，fileName: {}, uploadId: {}", fileName, uploadId);
                } catch (Exception abortEx) {
                    log.error("取消分片上传失败，fileName: {}, uploadId: {}", fileName, uploadId, abortEx);
                }
            }
            throw new IOException("分片上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 根据文件扩展名判断是否为视频文件
     */
    private boolean isVideoFileByExtension(String fileName) {
        if (fileName == null) return false;
        String extension = getFileExtension(fileName).toLowerCase();
        return FileTypeConst.VIDEO_EXTENSIONS.contains(extension);
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        if (fileName == null) return "";
        int dotIndex = fileName.lastIndexOf(".");
        return dotIndex > 0 && dotIndex < fileName.length() - 1
                ? fileName.substring(dotIndex + 1) : "";
    }
}
