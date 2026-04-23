package diary.ossupdownloadphoto.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface VideoFileService {
    Map<String, Object> addVideoToDb(MultipartFile file);

    void uploadAndSendMsgAsync(Map<String, Object> result, MultipartFile file);
}
