package diary.ossupdownloadphoto.impl;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import diary.ossupdownloadphoto.config.consts.PhotoStatusConst;
import diary.ossupdownloadphoto.config.consts.PhotoTypeConst;
import diary.ossupdownloadphoto.config.mqconfig.RabbitMqConfig;
import diary.ossupdownloadphoto.mapper.PhotoMapper;
import diary.ossupdownloadphoto.po.OssUploadSuccessMsg;
import diary.ossupdownloadphoto.po.Photo;
import diary.ossupdownloadphoto.service.FileService;
import diary.ossupdownloadphoto.service.RedisService;
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

import javax.imageio.ImageIO;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static diary.ossupdownloadphoto.util.MyUtils.isEmpty;
import static diary.ossupdownloadphoto.util.MyUtils.isFileEmpty;

@Slf4j
@Service
public class FileServiceImpl implements FileService {
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

    @Value("${download.path:C:\\Users\\admin\\Pictures\\Saved Pictures}")
    private String defaultDownloadPath;

    @Value("${download.timeout:300000}")
    private int timeout;

    @Resource
    private MyOssUtils myOssUtils;

    @Override
    public Map<String, Object> addFileToDb(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return Map.of("code", 500, "message", "文件列表为空", "data", "null");
        }
    
        List<Photo> photoList = new ArrayList<>();
        List<String> failedFiles = new ArrayList<>();
    
        // 第一步：验证所有文件并构建Photo对象列表
        for (MultipartFile file : files) {
            try {
                if (isFileEmpty(file)) {
                    failedFiles.add(file.getOriginalFilename() + ": 文件为空");
                    continue;
                }
    
                // 验证是否为图片类型
                String photoFormat = file.getContentType();
                if (isEmpty(photoFormat) || !photoFormat.startsWith("image")) {
                    try {
                        if (ImageIO.read(file.getInputStream()) == null) {
                            failedFiles.add(file.getOriginalFilename() + ": 文件不是图片类型");
                        }
                    } catch (Exception e) {
                        failedFiles.add(file.getOriginalFilename() + ": 文件读取失败");
                    }
                    continue;
                }
    
                long id = MyUtils.getPrimaryKey();
                String photoType = PhotoTypeConst.PHOTO_TYPE_SWEETY;
                String photoName = file.getOriginalFilename();
    
                // 查看同一图片所属类别下是否有相同名称的图片
                Integer isExist = photoMapper.selectPhotoByTypeAndName(photoType, photoName);
                if (isExist > 0) {
                    failedFiles.add(photoName + ": 图片已存在");
                    continue;
                }
    
                long photoSize = file.getSize();
                String photoStatus = PhotoStatusConst.PHOTO_STATUS_PROCESSING;
    
                // 构建Photo对象（暂不设置sortOrder）
                Photo photo = new Photo();
                photo.setId(id);
                photo.setPhotoType(photoType);
                photo.setPhotoName(photoName);
                photo.setPhotoSize(photoSize);
                photo.setPhotoFormat(photoFormat);
                photo.setPhotoStatus(photoStatus);
    
                photoList.add(photo);
            } catch (Exception e) {
                log.error("处理文件 {} 时发生异常", file.getOriginalFilename(), e);
                failedFiles.add(file.getOriginalFilename() + ": " + e.getMessage());
            }
        }
    
        // 第二步：分批插入数据库，每批最多20条
        List<Long> photoIds = new ArrayList<>();
        int batchSize = 20;
        int totalSize = photoList.size();
            
        for (int i = 0; i < totalSize; i += batchSize) {
            // 计算当前批次的结束位置
            int end = Math.min(i + batchSize, totalSize);
            List<Photo> batchList = photoList.subList(i, end);
    
            try {
                // 获取当前Redis中的图片数量，作为本批次起始序号
                long currentCount = redisService.getPhotoCount();
                
                // 为本批次的Photo设置连续的sortOrder
                for (int j = 0; j < batchList.size(); j++) {
                    batchList.get(j).setSortOrder(currentCount + j + 1);
                }
                
                Integer count = photoMapper.batchAddPhotoToDb(batchList);
                if (count != null && count > 0) {
                    // 一次性更新Redis：当前数量 + 本批次插入数量
                    redisService.updatePhotoCount(currentCount + batchList.size());
                    
                    // 收集成功插入的id
                    for (Photo photo : batchList) {
                        photoIds.add(photo.getId());
                    }
                    log.info("批量插入照片成功，批次范围: {} - {}，插入数量: {}，sortOrder范围: {} - {}", 
                            i + 1, end, count, currentCount + 1, currentCount + batchList.size());
                } else {
                    // 记录失败的文件
                    for (Photo photo : batchList) {
                        failedFiles.add(photo.getPhotoName() + ": 批量插入失败");
                    }
                    log.error("批量插入照片失败，批次范围: {} - {}", i + 1, end);
                }
            } catch (Exception e) {
                log.error("批量插入照片异常，批次范围: {} - {}", i + 1, end, e);
                // 记录失败的文件
                for (Photo photo : batchList) {
                    failedFiles.add(photo.getPhotoName() + ": " + e.getMessage());
                }
            }
        }
    
        if (photoIds.isEmpty()) {
            return Map.of("code", 500, "message", "所有文件处理失败", "data", "null", "failedFiles", failedFiles);
        }
    
        Map<String, Object> result = new HashMap<>();
        result.put("code", 200);
        result.put("data", photoIds);
        if (!failedFiles.isEmpty()) {
            result.put("failedFiles", failedFiles);
            result.put("message", "部分文件处理成功");
        }
        return result;
    }

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

    @Override
    public Map<String, Object> batchDownloadImages(List<String> ossUrls) {
        if (ossUrls == null || ossUrls.isEmpty()) {
            return Map.of("code", 500, "message", "URL列表为空", "data", "null");
        }

        // 确保下载目录存在
        Path downloadDir = Paths.get(defaultDownloadPath);
        try {
            if (!Files.exists(downloadDir)) {
                Files.createDirectories(downloadDir);
                log.info("创建下载目录: {}", defaultDownloadPath);
            }
        } catch (IOException e) {
            log.error("创建下载目录失败: {}", defaultDownloadPath, e);
            return Map.of("code", 500, "message", "创建下载目录失败: " + e.getMessage(), "data", "null");
        }

        // 用于存储所有下载任务的结果
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();

        // 并发计数器
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 提交所有下载任务
        for (String ossUrl : ossUrls) {
            CompletableFuture<Map<String, Object>> future = downloadImageAsync(ossUrl, defaultDownloadPath)
                    .thenApply(result -> {
                        if ("success".equals(result.get("status"))) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                        return result;
                    });
            futures.add(future);
        }

        // 等待所有任务完成
        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0])
        );

        try {
            // 阻塞等待所有任务完成
            allFutures.join();

            List<String> successFiles = new ArrayList<>();
            List<String> failedFiles = new ArrayList<>();

            for (CompletableFuture<Map<String, Object>> future : futures) {
                Map<String, Object> result = future.get();

                String ossUrl = (String) result.get("ossUrl");
                if ("success".equals(result.get("status"))) {
                    successFiles.add((String) result.get("filePath"));
                } else {
                    failedFiles.add(ossUrl + ": " + result.get("message"));
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("code", 200);
            response.put("message", String.format("下载完成，成功: %d, 失败: %d", successCount.get(), failCount.get()));
            response.put("total", ossUrls.size());
            response.put("successCount", successCount.get());
            response.put("failCount", failCount.get());
            response.put("successFiles", successFiles);
            if (!failedFiles.isEmpty()) {
                response.put("failedFiles", failedFiles);
            }

            log.info("批量下载完成，总数: {}, 成功: {}, 失败: {}", ossUrls.size(), successCount.get(), failCount.get());

            return response;
        } catch (Exception e) {
            log.error("批量下载异常", e);
            return Map.of("code", 500, "message", "批量下载异常: " + e.getMessage(), "data", "null");
        }
    }

    @Async("ossDownloadExecutor")
    @Override
    public CompletableFuture<Map<String, Object>> downloadImageAsync(String ossUrl, String savePath) {
        Map<String, Object> result = new HashMap<>();
        result.put("ossUrl", ossUrl);

        try {
            // 生成签名URL（有效期5分钟）
            String signedUrl = myOssUtils.generateSignedUrl(ossUrl);
            log.info("生成签名URL: {}", signedUrl);

            // 从签名URL中提取文件名
            String fileName = extractFileNameFromUrl(signedUrl);
            if (fileName == null || fileName.isEmpty()) {
                fileName = "image_" + System.currentTimeMillis() + ".jpg";
            }

            // 构建完整保存路径
            Path fullPath = Paths.get(savePath, fileName);

            // 如果文件已存在，添加时间戳避免覆盖
            if (Files.exists(fullPath)) {
                String nameWithoutExt = getFileNameWithoutExtension(fileName);
                String extension = getFileExtension(fileName);
                String newFileName = nameWithoutExt + "_" + System.currentTimeMillis() + "." + extension;
                fullPath = Paths.get(savePath, newFileName);
            }

            log.info("开始下载图片: {} -> {}", signedUrl, fullPath);

            // 使用HttpURLConnection下载文件，超时时间5分钟
            downloadFileWithHttpURLConnection(signedUrl, fullPath.toFile(), 300000);

            result.put("status", "success");
            result.put("filePath", fullPath.toString());
            result.put("fileName", fullPath.getFileName().toString());
            result.put("message", "下载成功");

            log.info("图片下载成功: {}", fullPath);

        } catch (Exception e) {
            log.error("图片下载失败, URL: {}", ossUrl, e);
            result.put("status", "failed");
            result.put("message", "下载失败: " + e.getMessage());
        }

        return CompletableFuture.completedFuture(result);
    }

    /**
     * 使用HttpURLConnection下载文件到本地
     * @param fileUrl 文件URL（签名URL）
     * @param destFile 目标文件
     * @param timeoutMillis 超时时间（毫秒）
     */
    private void downloadFileWithHttpURLConnection(String fileUrl, File destFile, int timeoutMillis) throws IOException {
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        FileOutputStream outputStream = null;

        try {
            URI uri = new URI(fileUrl);
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            // 设置连接超时和读取超时
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setInstanceFollowRedirects(true);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP响应码: " + responseCode);
            }

            // 获取文件大小
            long contentLength = connection.getContentLengthLong();
            log.debug("文件大小: {} bytes", contentLength);

            inputStream = connection.getInputStream();
            outputStream = new FileOutputStream(destFile);

            byte[] buffer = new byte[8192]; // 增大缓冲区到8KB
            int bytesRead;
            long totalBytesRead = 0;

            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
            }

            outputStream.flush();
            log.debug("已下载: {} bytes", totalBytesRead);

        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    log.error("关闭输出流失败", e);
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    log.error("关闭输入流失败", e);
                }
            }
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * 从URL中提取文件名
     */
    private String extractFileNameFromUrl(String url) {
        try {
            // 移除URL参数
            String urlWithoutParams = url.split("\\?")[0];
            String fileName = urlWithoutParams.substring(urlWithoutParams.lastIndexOf("/") + 1);

            // URL解码
            fileName = java.net.URLDecoder.decode(fileName, StandardCharsets.UTF_8);

            // 验证文件名是否合法
            if (fileName.contains(".")) return fileName;
        } catch (Exception e) {
            log.warn("从URL提取文件名失败: {}", url, e);
        }
        return null;
    }

    /**
     * 获取不带扩展名的文件名
     */
    private String getFileNameWithoutExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf(".");
        return dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf(".");
        return dotIndex > 0 && dotIndex < fileName.length() - 1
                ? fileName.substring(dotIndex + 1) : "jpg";
    }
}
