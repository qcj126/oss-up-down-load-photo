package diary.ossupdownloadphoto.controller;

import diary.ossupdownloadphoto.config.resultconfig.ResultDto;
import diary.ossupdownloadphoto.service.FileAddService;
import diary.ossupdownloadphoto.service.FileUploadService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/file")
public class FileUploadController {
    @Resource
    private FileUploadService fileUploadService;

    @Resource
    private FileAddService fileAddService;

    @PostMapping("/upload")
    public ResultDto upload(@RequestParam @Nonnull MultipartFile file) {
        // 直接先插入数据
        Map<String, Object> result = fileAddService.addFileToDb(file);
        // 异步上传图片到OSS成功后，发送消息给mq
        fileUploadService.uploadAndSendMsgAsync(result, file);
        return ResultDto.isSuccess(result);
    }
}
