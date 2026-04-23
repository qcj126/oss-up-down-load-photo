package diary.ossupdownloadphoto.controller;

import diary.ossupdownloadphoto.config.resultconfig.ResultDto;
import diary.ossupdownloadphoto.service.PhotoFileService;
import diary.ossupdownloadphoto.service.VideoFileService;
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
    private PhotoFileService photoFileService;

    @Resource
    private VideoFileService videoFileService;

    @PostMapping("/upload/photo")
    public ResultDto upload(@RequestParam("files") List<MultipartFile> files) {
        // 直接先插入数据
        Map<String, Object> result = photoFileService.addPhotosToDb(files);
        // 异步上传图片到OSS成功后，发送消息给mq
        photoFileService.uploadAndSendMsgAsync(result, files);
        return ResultDto.isSuccess(result);
    }

    @PostMapping("/download/photo")
    public ResultDto download(@RequestParam("ossUrls") List<String> ossUrls) {
        // 批量下载图片
        Map<String, Object> result = photoFileService.batchDownloadPhotos(ossUrls);
        return ResultDto.isSuccess(result);
    }

    @PostMapping("/upload/video")
    public ResultDto uploadVideo(@RequestParam("file") MultipartFile file) {
        // 直接先插入数据
        Map<String, Object> result = videoFileService.addVideoToDb(file);
        // 异步上传视频到OSS成功后，发送消息给mq
        videoFileService.uploadAndSendMsgAsync(result, file);
        return ResultDto.isSuccess(result);
    }
}
