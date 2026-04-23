package diary.ossupdownloadphoto.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface FileService {
    /**
     * 批量添加文件信息到数据库
     * @param files 文件列表
     * @return 数据库插入结果
     */
    Map<String, Object> addFileToDb(List<MultipartFile> files);

    /**
     * 异步上传图片到OSS并发送消息
     * @param result 数据库插入结果
     * @param files 文件列表
     */
    void uploadAndSendMsgAsync(Map<String, Object> result, List<MultipartFile> files);

    /**
     * 批量下载图片
     * @param ossUrls OSS图片URL列表
     * @return 下载结果
     */
    Map<String, Object> batchDownloadImages(List<String> ossUrls);

    /**
     * 异步下载单个图片
     * @param ossUrl OSS图片URL
     * @param savePath 保存路径
     * @return 下载结果
     */
    CompletableFuture<Map<String, Object>> downloadImageAsync(String ossUrl, String savePath);
}
