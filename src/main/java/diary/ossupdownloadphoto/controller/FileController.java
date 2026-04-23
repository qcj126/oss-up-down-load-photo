package diary.ossupdownloadphoto.controller;

import diary.ossupdownloadphoto.config.resultconfig.ResultDto;
import diary.ossupdownloadphoto.service.FileService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/file")
public class FileController {
    @Resource
    private FileService fileService;

    @PostMapping("/upload")
    public ResultDto upload(@RequestParam("files") List<MultipartFile> files) {
        // 直接先插入数据
        Map<String, Object> result = fileService.addFileToDb(files);
        // 异步上传图片到OSS成功后，发送消息给mq
        fileService.uploadAndSendMsgAsync(result, files);
        return ResultDto.isSuccess(result);
    }

    @PostMapping("/download")
    public ResultDto download(@RequestParam("ossUrls") List<String> ossUrls) {
        // 批量下载图片
        Map<String, Object> result = fileService.batchDownloadImages(ossUrls);
        return ResultDto.isSuccess(result);
    }
}
